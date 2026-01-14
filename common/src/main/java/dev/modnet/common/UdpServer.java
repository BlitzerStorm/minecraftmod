package dev.modnet.common;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Common UDP server that listens for ModNet packets and routes telemetry/cosmetic/hint data.
 */
public final class UdpServer implements Closeable {
    public record Config(int maxPayloadBytes, int compressionThresholdBytes, int rateLimitPerSecond, int rateLimitBurst,
                         int sessionTimeoutSeconds, int hintRetryCount, long hintRetryIntervalMillis) {
        public static Config defaults() {
            return new Config(Protocol.DEFAULT_MAX_PAYLOAD, Protocol.DEFAULT_COMPRESSION_THRESHOLD, 100, 200, 90, 3, 750);
        }
    }

    public record Stats(int activeSessions, long packetsReceived, long packetsSent, long packetsDropped, long invalidPackets) {
    }

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
    private final Config config;
    private final AtomicLong packetsReceived = new AtomicLong();
    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong packetsDropped = new AtomicLong();
    private final AtomicLong invalidPackets = new AtomicLong();
    private final AtomicInteger hintSequence = new AtomicInteger(1);

    public UdpServer(int port, Consumer<String> log) throws SocketException {
        this(port, log, Config.defaults());
    }

    public UdpServer(int port, Consumer<String> log, Config config) throws SocketException {
        this.log = log == null ? message -> {
        } : log;
        this.config = config == null ? Config.defaults() : config;
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
        if (config.hintRetryIntervalMillis() > 0) {
            scheduler.scheduleAtFixedRate(this::retryHints, config.hintRetryIntervalMillis(), config.hintRetryIntervalMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public void register(UUID token) {
        sessions.computeIfAbsent(token, value -> new ClientSession(value, config.rateLimitPerSecond(), config.rateLimitBurst()));
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
        if (hint == null) {
            return;
        }
        ClientSession session = sessions.get(token);
        if (session == null) {
            return;
        }
        InetSocketAddress address = session.getAddress();
        if (address == null) {
            return;
        }
        Protocol.HintData toSend = hint.id() == 0
                ? new Protocol.HintData(hint.flags(), hintSequence.getAndIncrement(), hint.bandwidthBudget(), hint.message())
                : hint;
        if (config.hintRetryCount() > 0 && config.hintRetryIntervalMillis() > 0) {
            session.trackHint(toSend, config.hintRetryCount(), System.currentTimeMillis() + config.hintRetryIntervalMillis(),
                    config.hintRetryIntervalMillis());
        }
        sendPacket(address, Protocol.PacketType.HINT, toSend.toBytes(), session.getToken());
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
                    invalidPackets.incrementAndGet();
                    continue;
                }
                ClientSession session = sessions.get(parsed.token());
                if (session == null) {
                    packetsDropped.incrementAndGet();
                    continue;
                }
                if (!session.rateLimiter.tryConsume()) {
                    packetsDropped.incrementAndGet();
                    continue;
                }
                packetsReceived.incrementAndGet();
                if (packet.getSocketAddress() instanceof InetSocketAddress address) {
                    session.updateAddress(address);
                }
                handlePacket(session, parsed);
            } catch (IOException e) {
                if (running && !(e instanceof SocketTimeoutException)) {
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
            case HINT_ACK -> handleHintAck(session, packet.payload());
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

    private void handleHintAck(ClientSession session, byte[] payload) {
        Integer hintId = Protocol.readHintAck(payload);
        if (hintId == null) {
            return;
        }
        session.ackHint(hintId);
    }

    private void sendPacket(InetSocketAddress target, Protocol.PacketType type, byte[] payload, UUID token) {
        if (target == null || token == null) {
            return;
        }
        byte[] encoded;
        try {
            encoded = Protocol.encode(type, token, payload, config.maxPayloadBytes(), config.compressionThresholdBytes());
        } catch (IllegalArgumentException e) {
            packetsDropped.incrementAndGet();
            log.accept("UDP server packet dropped: " + e.getMessage());
            return;
        }
        DatagramPacket packet = new DatagramPacket(encoded, encoded.length, target);
        try {
            socket.send(packet);
            packetsSent.incrementAndGet();
        } catch (IOException e) {
            packetsDropped.incrementAndGet();
            log.accept("UDP server send failed: " + e.getMessage());
        }
    }

    private void cleanupSessions() {
        long threshold = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(config.sessionTimeoutSeconds());
        sessions.values().removeIf(session -> session.lastSeen < threshold);
    }

    private void retryHints() {
        long now = System.currentTimeMillis();
        for (ClientSession session : sessions.values()) {
            session.retryHints(now, hint -> sendPacket(session.getAddress(), Protocol.PacketType.HINT, hint.toBytes(), session.getToken()),
                    droppedHint -> {
                        packetsDropped.incrementAndGet();
                        log.accept("Hint " + droppedHint.id() + " dropped after retries");
                    });
        }
    }

    @Override
    public void close() {
        running = false;
        socket.close();
        receiver.shutdownNow();
        scheduler.shutdownNow();
    }

    public Stats stats() {
        return new Stats(sessions.size(), packetsReceived.get(), packetsSent.get(), packetsDropped.get(), invalidPackets.get());
    }

    public int getPort() {
        return socket.getLocalPort();
    }

    private static final class ClientSession {
        private final UUID token;
        private volatile InetSocketAddress address;
        private volatile long lastSeen = System.currentTimeMillis();
        @SuppressWarnings("unused")
        private volatile Protocol.TelemetryData telemetry;
        @SuppressWarnings("unused")
        private volatile Protocol.CosmeticData cosmetic;
        private final TokenBucket rateLimiter;
        private final ConcurrentHashMap<Integer, PendingHint> pendingHints = new ConcurrentHashMap<>();

        private ClientSession(UUID token, int rateLimitPerSecond, int rateLimitBurst) {
            this.token = token;
            this.rateLimiter = new TokenBucket(rateLimitPerSecond, rateLimitBurst);
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

        private void trackHint(Protocol.HintData hint, int retries, long nextRetryAt, long retryIntervalMillis) {
            pendingHints.put(hint.id(), new PendingHint(hint, retries, nextRetryAt, retryIntervalMillis));
        }

        private void retryHints(long now, Consumer<Protocol.HintData> resend, Consumer<Protocol.HintData> dropHandler) {
            pendingHints.values().forEach(pending -> {
                if (pending == null) {
                    return;
                }
                if (pending.shouldRetry(now)) {
                    resend.accept(pending.hint());
                    pending.onRetry(now);
                }
            });
            pendingHints.entrySet().removeIf(entry -> {
                PendingHint pending = entry.getValue();
                if (pending == null) {
                    return true;
                }
                if (pending.isExpired()) {
                    dropHandler.accept(pending.hint());
                    return true;
                }
                return false;
            });
        }

        private void ackHint(int id) {
            pendingHints.remove(id);
        }
    }

    private static final class PendingHint {
        private final Protocol.HintData hint;
        private int remainingRetries;
        private long nextRetryAt;
        private final long retryIntervalMillis;

        private PendingHint(Protocol.HintData hint, int remainingRetries, long nextRetryAt, long retryIntervalMillis) {
            this.hint = hint;
            this.remainingRetries = remainingRetries;
            this.nextRetryAt = nextRetryAt;
            this.retryIntervalMillis = retryIntervalMillis;
        }

        private boolean shouldRetry(long now) {
            return remainingRetries > 0 && now >= nextRetryAt;
        }

        private void onRetry(long now) {
            remainingRetries--;
            nextRetryAt = now + retryIntervalMillis;
        }

        private boolean isExpired() {
            return remainingRetries <= 0;
        }

        private Protocol.HintData hint() {
            return hint;
        }
    }

    private static final class TokenBucket {
        private final int ratePerSecond;
        private final int burst;
        private double tokens;
        private long lastRefillNanos;

        private TokenBucket(int ratePerSecond, int burst) {
            this.ratePerSecond = ratePerSecond;
            this.burst = burst;
            this.tokens = burst;
            this.lastRefillNanos = System.nanoTime();
        }

        private synchronized boolean tryConsume() {
            if (ratePerSecond <= 0 || burst <= 0) {
                return true;
            }
            refill();
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }

        private void refill() {
            long now = System.nanoTime();
            double deltaSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            if (deltaSeconds > 0) {
                tokens = Math.min(burst, tokens + deltaSeconds * ratePerSecond);
                lastRefillNanos = now;
            }
        }
    }
}
