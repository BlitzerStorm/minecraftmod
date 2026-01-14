package dev.modnet.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Shared binary protocol that carries UDP packets between the ModNet client and server.
 * <p>
 * Header layout:
 * <pre>
 * [magic 4 bytes][version 1][type 1][flags 1][length 2][token 16][checksum 4][payload length bytes]
 * </pre>
 */
public final class Protocol {
    public static final int MAGIC = 0x4d4f444e; // 'MODN'
    public static final byte VERSION = 1;
    public static final String CHANNEL = "modnet:main";
    public static final int HEADER_SIZE = 4 + 1 + 1 + 1 + 2 + 16 + 4;
    public static final int DEFAULT_MAX_PAYLOAD = 8192;
    public static final int DEFAULT_COMPRESSION_THRESHOLD = 512;

    public static final byte FLAG_COMPRESSED = 0x1;

    private Protocol() {
    }

    public enum PacketType {
        PING((byte) 0),
        PONG((byte) 1),
        TELEMETRY((byte) 2),
        COSMETIC((byte) 3),
        HINT((byte) 4),
        HINT_ACK((byte) 5);

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
        return encode(type, token, payload, DEFAULT_MAX_PAYLOAD, DEFAULT_COMPRESSION_THRESHOLD);
    }

    public static byte[] encode(PacketType type, UUID token, byte[] payload, int maxPayload, int compressionThreshold) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(token, "token");
        payload = payload == null ? new byte[0] : payload;
        if (payload.length > maxPayload) {
            throw new IllegalArgumentException("Payload too large: " + payload.length);
        }

        byte flags = 0;
        byte[] encodedPayload = payload;
        if (compressionThreshold > 0 && payload.length >= compressionThreshold) {
            byte[] compressed = gzip(payload);
            if (compressed != null && compressed.length < payload.length) {
                flags |= FLAG_COMPRESSED;
                encodedPayload = compressed;
            }
        }

        if (encodedPayload.length > maxPayload) {
            throw new IllegalArgumentException("Payload too large after compression: " + encodedPayload.length);
        }
        if (encodedPayload.length > 0xFFFF) {
            throw new IllegalArgumentException("Payload exceeds protocol length limits: " + encodedPayload.length);
        }

        int checksum = checksum(encodedPayload);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + encodedPayload.length);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.put(type.id);
        buffer.put(flags);
        buffer.putShort((short) encodedPayload.length);
        buffer.putLong(token.getMostSignificantBits());
        buffer.putLong(token.getLeastSignificantBits());
        buffer.putInt(checksum);
        buffer.put(encodedPayload);
        return buffer.array();
    }

    public static Packet decode(byte[] data) {
        return decode(data, DEFAULT_MAX_PAYLOAD);
    }

    public static Packet decode(byte[] data, int maxPayload) {
        if (data == null || data.length < HEADER_SIZE) {
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
        byte flags = buffer.get();
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length > maxPayload) {
            return null;
        }
        if (buffer.remaining() < 16 + 4 + length) {
            return null;
        }
        UUID token = new UUID(buffer.getLong(), buffer.getLong());
        int checksum = buffer.getInt();
        byte[] payload = new byte[length];
        buffer.get(payload);
        if (checksum(payload) != checksum) {
            return null;
        }
        if ((flags & FLAG_COMPRESSED) != 0) {
            byte[] inflated = gunzip(payload);
            if (inflated == null) {
                return null;
            }
            if (inflated.length > maxPayload) {
                return null;
            }
            payload = inflated;
        }
        return new Packet(type, token, payload);
    }

    public record Packet(PacketType type, UUID token, byte[] payload) {
    }

    public static byte[] writeClientHello(UUID token) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 16);
        buffer.put((byte) 1);
        buffer.put(VERSION);
        buffer.putLong(token.getMostSignificantBits());
        buffer.putLong(token.getLeastSignificantBits());
        return buffer.array();
    }

    public static ClientHello readClientHello(byte[] data) {
        if (data == null || data.length != 1 + 1 + 16) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.get() != 1) {
            return null;
        }
        if (buffer.get() != VERSION) {
            return null;
        }
        return new ClientHello(new UUID(buffer.getLong(), buffer.getLong()));
    }

    public static byte[] writeServerHello(UUID token, int udpPort) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 16 + 4);
        buffer.put((byte) 2);
        buffer.put(VERSION);
        buffer.putLong(token.getMostSignificantBits());
        buffer.putLong(token.getLeastSignificantBits());
        buffer.putInt(udpPort);
        return buffer.array();
    }

    public static ServerHello readServerHello(byte[] data) {
        if (data == null || data.length != 1 + 1 + 16 + 4) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.get() != 2) {
            return null;
        }
        if (buffer.get() != VERSION) {
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

    public static byte[] writeHintAck(int hintId) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(hintId);
        return buffer.array();
    }

    public static Integer readHintAck(byte[] payload) {
        if (payload == null || payload.length != 4) {
            return null;
        }
        return ByteBuffer.wrap(payload).getInt();
    }

    private static int checksum(byte[] payload) {
        CRC32 crc32 = new CRC32();
        crc32.update(payload);
        return (int) crc32.getValue();
    }

    private static byte[] gzip(byte[] payload) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(payload);
            gzip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] gunzip(byte[] payload) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(payload);
             GZIPInputStream gzip = new GZIPInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
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
        private final int id;
        private final int bandwidthBudget;
        private final String message;

        public static final byte FLAG_THROTTLE_COSMETICS = 0x1;
        public static final byte FLAG_REQUEST_COMPRESSION = 0x2;

        public HintData(byte flags, int bandwidthBudget, String message) {
            this(flags, 0, bandwidthBudget, message);
        }

        public HintData(byte flags, int id, int bandwidthBudget, String message) {
            this.flags = flags;
            this.id = id;
            this.bandwidthBudget = bandwidthBudget;
            this.message = message != null ? message : "";
        }

        public byte[] toBytes() {
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(1 + Integer.BYTES + Integer.BYTES + 2 + messageBytes.length);
            buffer.put(flags);
            buffer.putInt(id);
            buffer.putInt(bandwidthBudget);
            buffer.putShort((short) messageBytes.length);
            buffer.put(messageBytes);
            return buffer.array();
        }

        public static HintData fromBytes(byte[] bytes) {
            if (bytes == null || bytes.length < 1 + Integer.BYTES + Integer.BYTES + 2) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte flags = buffer.get();
            int id = buffer.getInt();
            int budget = buffer.getInt();
            int length = Short.toUnsignedInt(buffer.getShort());
            if (buffer.remaining() < length) {
                return null;
            }
            byte[] messageBytes = new byte[length];
            buffer.get(messageBytes);
            String message = new String(messageBytes, StandardCharsets.UTF_8);
            return new HintData(flags, id, budget, message);
        }

        public byte flags() {
            return flags;
        }

        public int id() {
            return id;
        }

        public int bandwidthBudget() {
            return bandwidthBudget;
        }

        public String message() {
            return message;
        }
    }
}
