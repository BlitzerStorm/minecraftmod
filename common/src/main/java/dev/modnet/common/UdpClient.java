package dev.modnet.common;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** UDP client for ModNet that sends telemetry/cosmetics/hints over fire-and-forget packets. */
public final class UdpClient implements Closeable {
    public record Config(int maxPayloadBytes, int compressionThresholdBytes, int socketTimeoutMillis, int fallbackTimeoutMillis) {
        public static Config defaults() {
            return new Config(Protocol.DEFAULT_MAX_PAYLOAD, Protocol.DEFAULT_COMPRESSION_THRESHOLD, 1000, 5000);
        }
    }

    private final DatagramSocket socket;
    private final InetSocketAddress server;
    private final UUID token;
    private final Consumer<String> log;
    private final Consumer<Protocol.HintData> hintHandler;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService receiver;
    private final Config config;
    private final AtomicLong lastPong = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean fallback = new AtomicBoolean(false);
    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong packetsReceived = new AtomicLong();
    private final AtomicLong packetsDropped = new AtomicLong();

    private volatile boolean running;
    private volatile Supplier<Protocol.TelemetryData> telemetrySupplier;

    public UdpClient(InetSocketAddress server, UUID token, Consumer<Protocol.HintData> hintHandler, Consumer<String> log)
            throws SocketException {
        this(server, token, hintHandler, log, Config.defaults());
    }

    public UdpClient(InetSocketAddress server, UUID token, Consumer<Protocol.HintData> hintHandler, Consumer<String> log, Config config)
            throws SocketException {
        this.server = Objects.requireNonNull(server, "server");
        this.token = Objects.requireNonNull(token, "token");
        this.hintHandler = hintHandler == null ? data -> {
        } : hintHandler;
        this.log = log == null ? message -> {
        } : log;
        this.config = config == null ? Config.defaults() : config;
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(this.config.socketTimeoutMillis());
        this.scheduler = Executors.newScheduledThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "modnet-udp-client-sched");
            thread.setDaemon(true);
            return thread;
        });
        this.receiver = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "modnet-udp-client-receive");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        receiver.submit(this::receiveLoop);
        scheduler.scheduleAtFixedRate(this::sendPing, 0, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::checkFallback, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::emitTelemetry, 0, 1, TimeUnit.SECONDS);
    }

    public void setTelemetrySupplier(Supplier<Protocol.TelemetryData> supplier) {
        telemetrySupplier = supplier;
    }

    public void sendTelemetry(Protocol.TelemetryData telemetry) {
        if (telemetry == null) {
            return;
        }
        sendPacket(Protocol.PacketType.TELEMETRY, telemetry.toBytes());
    }

    public void sendCosmetic(Protocol.CosmeticData cosmetic) {
        if (cosmetic == null) {
            return;
        }
        sendPacket(Protocol.PacketType.COSMETIC, cosmetic.toBytes());
    }

    public void sendHint(Protocol.HintData hint) {
        if (hint == null) {
            return;
        }
        sendPacket(Protocol.PacketType.HINT, hint.toBytes());
    }

    private void emitTelemetry() {
        Supplier<Protocol.TelemetryData> supplier = telemetrySupplier;
        if (supplier == null) {
            return;
        }
        try {
            Protocol.TelemetryData telemetry = supplier.get();
            sendTelemetry(telemetry);
        } catch (Exception e) {
            log.accept("Failed to emit telemetry: " + e.getMessage());
        }
    }

    private void sendPing() {
        sendPacket(Protocol.PacketType.PING, new byte[0]);
    }

    private void sendPacket(Protocol.PacketType type, byte[] payload) {
        byte[] encoded;
        try {
            encoded = Protocol.encode(type, token, payload, config.maxPayloadBytes(), config.compressionThresholdBytes());
        } catch (IllegalArgumentException e) {
            packetsDropped.incrementAndGet();
            log.accept("Unable to encode UDP packet: " + e.getMessage());
            return;
        }
        DatagramPacket packet = new DatagramPacket(encoded, encoded.length, server);
        try {
            socket.send(packet);
            packetsSent.incrementAndGet();
        } catch (IOException e) {
            packetsDropped.incrementAndGet();
            log.accept("Unable to send UDP packet: " + e.getMessage());
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[Math.max(Protocol.DEFAULT_MAX_PAYLOAD, config.maxPayloadBytes()) + Protocol.HEADER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running && !socket.isClosed()) {
            try {
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                Protocol.Packet parsed = Protocol.decode(data, config.maxPayloadBytes());
                if (parsed == null) {
                    packetsDropped.incrementAndGet();
                    continue;
                }
                packetsReceived.incrementAndGet();
                handlePacket(parsed);
            } catch (IOException e) {
                if (running && !(e instanceof SocketTimeoutException)) {
                    log.accept("UDP receive failed: " + e.getMessage());
                }
            }
        }
    }

    private void handlePacket(Protocol.Packet packet) {
        if (!packet.token().equals(token)) {
            return;
        }
        if (packet.type() == Protocol.PacketType.PONG) {
            lastPong.set(System.currentTimeMillis());
            if (fallback.get()) {
                fallback.set(false);
                log.accept("UDP channel recovered.");
            }
            return;
        }
        if (packet.type() == Protocol.PacketType.HINT) {
            Protocol.HintData hint = Protocol.HintData.fromBytes(packet.payload());
            if (hint != null) {
                hintHandler.accept(hint);
                sendPacket(Protocol.PacketType.HINT_ACK, Protocol.writeHintAck(hint.id()));
            }
        }
    }

    private void checkFallback() {
        if (System.currentTimeMillis() - lastPong.get() > config.fallbackTimeoutMillis() && !fallback.get()) {
            fallback.set(true);
            log.accept("UDP fallback triggered (no pong).");
        }
    }

    @Override
    public void close() {
        running = false;
        socket.close();
        scheduler.shutdownNow();
        receiver.shutdownNow();
    }

    public long getPacketsSent() {
        return packetsSent.get();
    }

    public long getPacketsReceived() {
        return packetsReceived.get();
    }

    public long getPacketsDropped() {
        return packetsDropped.get();
    }
}
