package dev.modnet.common;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Common UDP server that listens for ModNet packets and routes telemetry/cosmetic/hint data.
 */
public final class UdpServer implements Closeable {
    private final DatagramSocket socket;
    private final ExecutorService receiver;
    private final ScheduledExecutorService scheduler;
    private final Consumer<String> log;
    private final ConcurrentHashMap<UUID, ClientSession> sessions = new ConcurrentHashMap<>();
    private volatile BiConsumer<UUID, Protocol.TelemetryData> telemetryListener = (token, data) -> {
    };
    private volatile BiConsumer<UUID, Protocol.CosmeticData> cosmeticListener = (token, data) -> {
    };
    private volatile BiConsumer<UUID, Protocol.HintData> hintListener = (token, data) -> {
    };
    private volatile boolean running = true;

    public UdpServer(int port, Consumer<String> log) throws SocketException {
        this.log = log == null ? message -> {
        } : log;
        this.socket = new DatagramSocket(port);
        this.socket.setSoTimeout(1000);
        this.receiver = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "modnet-udp-server");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "modnet-udp-server-sched");
            thread.setDaemon(true);
            return thread;
        });
        receiver.submit(this::receiveLoop);
        scheduler.scheduleAtFixedRate(this::cleanupSessions, 30, 30, TimeUnit.SECONDS);
    }

    public void register(UUID token) {
        sessions.computeIfAbsent(token, ClientSession::new);
    }

    public void unregister(UUID token) {
        sessions.remove(token);
    }

    public void setTelemetryListener(BiConsumer<UUID, Protocol.TelemetryData> listener) {
        telemetryListener = listener == null ? (token, data) -> {
        } : listener;
    }

    public void setCosmeticListener(BiConsumer<UUID, Protocol.CosmeticData> listener) {
        cosmeticListener = listener == null ? (token, data) -> {
        } : listener;
    }

    public void setHintListener(BiConsumer<UUID, Protocol.HintData> listener) {
        hintListener = listener == null ? (token, data) -> {
        } : listener;
    }

    public void sendHint(UUID token, Protocol.HintData hint) {
        ClientSession session = sessions.get(token);
        if (session == null) {
            return;
        }
        InetSocketAddress address = session.getAddress();
        if (address == null) {
            return;
        }
        sendPacket(address, Protocol.PacketType.HINT, hint.toBytes(), session.getToken());
    }

    private void receiveLoop() {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running && !socket.isClosed()) {
            try {
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                Protocol.Packet parsed = Protocol.decode(data);
                if (parsed == null) {
                    continue;
                }
                ClientSession session = sessions.get(parsed.token());
                if (session == null) {
                    continue;
                }
                if (packet.getSocketAddress() instanceof InetSocketAddress address) {
                    session.updateAddress(address);
                }
                handlePacket(session, parsed);
            } catch (IOException e) {
                if (running) {
                    log.accept("UDP server receive error: " + e.getMessage());
                }
            }
        }
    }

    private void handlePacket(ClientSession session, Protocol.Packet packet) {
        switch (packet.type()) {
            case PING -> sendPacket(session.getAddress(), Protocol.PacketType.PONG, new byte[0], session.getToken());
            case TELEMETRY -> handleTelemetry(session, packet.payload());
            case COSMETIC -> handleCosmetic(session, packet.payload());
            case HINT -> handleHint(session, packet.payload());
            default -> {
            }
        }
    }

    private void handleTelemetry(ClientSession session, byte[] payload) {
        Protocol.TelemetryData data = Protocol.TelemetryData.fromBytes(payload);
        if (data == null) {
            return;
        }
        session.updateTelemetry(data);
        telemetryListener.accept(session.getToken(), data);
        if (data.packetLoss() > 3 || data.bandwidthMbps() < 2.0f) {
            Protocol.HintData hint = new Protocol.HintData(Protocol.HintData.FLAG_THROTTLE_COSMETICS, 2048, "telemetry:throttle");
            sendHint(session.getToken(), hint);
        }
    }

    private void handleCosmetic(ClientSession session, byte[] payload) {
        Protocol.CosmeticData data = Protocol.CosmeticData.fromBytes(payload);
        if (data == null) {
            return;
        }
        session.updateCosmetic(data);
        cosmeticListener.accept(session.getToken(), data);
    }

    private void handleHint(ClientSession session, byte[] payload) {
        Protocol.HintData hint = Protocol.HintData.fromBytes(payload);
        if (hint == null) {
            return;
        }
        hintListener.accept(session.getToken(), hint);
    }

    private void sendPacket(InetSocketAddress target, Protocol.PacketType type, byte[] payload, UUID token) {
        if (target == null || token == null) {
            return;
        }
        byte[] encoded = Protocol.encode(type, token, payload);
        DatagramPacket packet = new DatagramPacket(encoded, encoded.length, target);
        try {
            socket.send(packet);
        } catch (IOException e) {
            log.accept("UDP server send failed: " + e.getMessage());
        }
    }

    private void cleanupSessions() {
        long threshold = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(90);
        sessions.values().removeIf(session -> session.lastSeen < threshold);
    }

    @Override
    public void close() {
        running = false;
        socket.close();
        receiver.shutdownNow();
        scheduler.shutdownNow();
    }

    private static final class ClientSession {
        private final UUID token;
        private volatile InetSocketAddress address;
        private volatile long lastSeen = System.currentTimeMillis();
        @SuppressWarnings("unused")
        private volatile Protocol.TelemetryData telemetry;
        @SuppressWarnings("unused")
        private volatile Protocol.CosmeticData cosmetic;

        private ClientSession(UUID token) {
            this.token = token;
        }

        private void updateAddress(InetSocketAddress address) {
            this.address = address;
            this.lastSeen = System.currentTimeMillis();
        }

        private InetSocketAddress getAddress() {
            return address;
        }

        private UUID getToken() {
            return token;
        }

        private void updateTelemetry(Protocol.TelemetryData telemetry) {
            this.telemetry = telemetry;
            this.lastSeen = System.currentTimeMillis();
        }

        private void updateCosmetic(Protocol.CosmeticData cosmetic) {
            this.cosmetic = cosmetic;
            this.lastSeen = System.currentTimeMillis();
        }
    }
}
