package dev.modnet.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared binary protocol that carries UDP packets between the ModNet client and server.
 * <p>
 * Header layout:
 * <pre>
 * [magic 4 bytes][version 1][type 1][length 2][token 16][payload length bytes]
 * </pre>
 */
public final class Protocol {
    public static final int MAGIC = 0x4d4f444e; // 'MODN'
    public static final byte VERSION = 1;
    public static final String CHANNEL = "modnet:main";

    private Protocol() {
    }

    public enum PacketType {
        PING((byte) 0),
        PONG((byte) 1),
        TELEMETRY((byte) 2),
        COSMETIC((byte) 3),
        HINT((byte) 4);

        private final byte id;

        PacketType(byte id) {
            this.id = id;
        }

        public byte id() {
            return id;
        }

        public static PacketType fromByte(byte value) {
            for (PacketType type : values()) {
                if (type.id == value) {
                    return type;
                }
            }
            return null;
        }
    }

    public static byte[] encode(PacketType type, UUID token, byte[] payload) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(token, "token");
        payload = payload == null ? new byte[0] : payload;
        if (payload.length > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Payload too large: " + payload.length);
        }

        ByteBuffer buffer = ByteBuffer.allocate(4 + 1 + 1 + 2 + 16 + payload.length);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.put(type.id);
        buffer.putShort((short) payload.length);
        buffer.putLong(token.getMostSignificantBits());
        buffer.putLong(token.getLeastSignificantBits());
        buffer.put(payload);
        return buffer.array();
    }

    public static Packet decode(byte[] data) {
        if (data == null || data.length < 4 + 1 + 1 + 2 + 16) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.getInt() != MAGIC) {
            return null;
        }
        byte version = buffer.get();
        if (version != VERSION) {
            return null;
        }
        PacketType type = PacketType.fromByte(buffer.get());
        if (type == null) {
            return null;
        }
        int length = Short.toUnsignedInt(buffer.getShort());
        if (buffer.remaining() < 16 + length) {
            return null;
        }
        UUID token = new UUID(buffer.getLong(), buffer.getLong());
        byte[] payload = new byte[length];
        buffer.get(payload);
        return new Packet(type, token, payload);
    }

    public record Packet(PacketType type, UUID token, byte[] payload) {
    }

    public static byte[] writeClientHello(UUID token) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 16);
        buffer.put((byte) 1);
        buffer.putLong(token.getMostSignificantBits());
        buffer.putLong(token.getLeastSignificantBits());
        return buffer.array();
    }

    public static ClientHello readClientHello(byte[] data) {
        if (data == null || data.length != 1 + 16) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.get() != 1) {
            return null;
        }
        return new ClientHello(new UUID(buffer.getLong(), buffer.getLong()));
    }

    public static byte[] writeServerHello(UUID token, int udpPort) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 16 + 4);
        buffer.put((byte) 2);
        buffer.putLong(token.getMostSignificantBits());
        buffer.putLong(token.getLeastSignificantBits());
        buffer.putInt(udpPort);
        return buffer.array();
    }

    public static ServerHello readServerHello(byte[] data) {
        if (data == null || data.length != 1 + 16 + 4) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.get() != 2) {
            return null;
        }
        UUID token = new UUID(buffer.getLong(), buffer.getLong());
        int port = buffer.getInt();
        return new ServerHello(token, port);
    }

    public record ClientHello(UUID token) {
    }

    public record ServerHello(UUID token, int udpPort) {
    }

    public static final class TelemetryData {
        private static final int SIZE = Float.BYTES * 4 + Integer.BYTES + Long.BYTES;

        private final float health;
        private final float ping;
        private final float bandwidthMbps;
        private final float fps;
        private final int packetLoss;
        private final long timestamp;

        public TelemetryData(float health, float ping, float bandwidthMbps, float fps, int packetLoss, long timestamp) {
            this.health = health;
            this.ping = ping;
            this.bandwidthMbps = bandwidthMbps;
            this.fps = fps;
            this.packetLoss = packetLoss;
            this.timestamp = timestamp;
        }

        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(SIZE);
            buffer.putFloat(health);
            buffer.putFloat(ping);
            buffer.putFloat(bandwidthMbps);
            buffer.putFloat(fps);
            buffer.putInt(packetLoss);
            buffer.putLong(timestamp);
            return buffer.array();
        }

        public static TelemetryData fromBytes(byte[] bytes) {
            if (bytes == null || bytes.length != SIZE) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            float health = buffer.getFloat();
            float ping = buffer.getFloat();
            float bandwidth = buffer.getFloat();
            float fps = buffer.getFloat();
            int loss = buffer.getInt();
            long timestamp = buffer.getLong();
            return new TelemetryData(health, ping, bandwidth, fps, loss, timestamp);
        }

        public float health() {
            return health;
        }

        public float ping() {
            return ping;
        }

        public float bandwidthMbps() {
            return bandwidthMbps;
        }

        public float fps() {
            return fps;
        }

        public int packetLoss() {
            return packetLoss;
        }

        public long timestamp() {
            return timestamp;
        }
    }

    public static final class CosmeticData {
        private static final int SIZE = Double.BYTES * 3 + Float.BYTES * 2 + Double.BYTES * 3 + Integer.BYTES;

        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;
        private final double vx;
        private final double vy;
        private final double vz;
        private final int designId;

        public CosmeticData(double x, double y, double z, float yaw, float pitch, double vx, double vy, double vz, int designId) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.designId = designId;
        }

        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(SIZE);
            buffer.putDouble(x);
            buffer.putDouble(y);
            buffer.putDouble(z);
            buffer.putFloat(yaw);
            buffer.putFloat(pitch);
            buffer.putDouble(vx);
            buffer.putDouble(vy);
            buffer.putDouble(vz);
            buffer.putInt(designId);
            return buffer.array();
        }

        public static CosmeticData fromBytes(byte[] bytes) {
            if (bytes == null || bytes.length != SIZE) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            double x = buffer.getDouble();
            double y = buffer.getDouble();
            double z = buffer.getDouble();
            float yaw = buffer.getFloat();
            float pitch = buffer.getFloat();
            double vx = buffer.getDouble();
            double vy = buffer.getDouble();
            double vz = buffer.getDouble();
            int designId = buffer.getInt();
            return new CosmeticData(x, y, z, yaw, pitch, vx, vy, vz, designId);
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public float yaw() {
            return yaw;
        }

        public float pitch() {
            return pitch;
        }

        public double vx() {
            return vx;
        }

        public double vy() {
            return vy;
        }

        public double vz() {
            return vz;
        }

        public int designId() {
            return designId;
        }
    }

    public static final class HintData {
        private final byte flags;
        private final int bandwidthBudget;
        private final String message;

        public static final byte FLAG_THROTTLE_COSMETICS = 0x1;
        public static final byte FLAG_REQUEST_COMPRESSION = 0x2;

        public HintData(byte flags, int bandwidthBudget, String message) {
            this.flags = flags;
            this.bandwidthBudget = bandwidthBudget;
            this.message = message != null ? message : "";
        }

        public byte[] toBytes() {
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(1 + Integer.BYTES + 2 + messageBytes.length);
            buffer.put(flags);
            buffer.putInt(bandwidthBudget);
            buffer.putShort((short) messageBytes.length);
            buffer.put(messageBytes);
            return buffer.array();
        }

        public static HintData fromBytes(byte[] bytes) {
            if (bytes == null || bytes.length < 1 + Integer.BYTES + 2) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte flags = buffer.get();
            int budget = buffer.getInt();
            int length = Short.toUnsignedInt(buffer.getShort());
            if (buffer.remaining() < length) {
                return null;
            }
            byte[] messageBytes = new byte[length];
            buffer.get(messageBytes);
            String message = new String(messageBytes, StandardCharsets.UTF_8);
            return new HintData(flags, budget, message);
        }

        public byte flags() {
            return flags;
        }

        public int bandwidthBudget() {
            return bandwidthBudget;
        }

        public String message() {
            return message;
        }
    }
}
