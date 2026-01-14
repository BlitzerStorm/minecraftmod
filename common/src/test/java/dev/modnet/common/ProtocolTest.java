package dev.modnet.common;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test
    void encodeDecodePacket() {
        UUID token = UUID.randomUUID();
        Protocol.TelemetryData telemetry = new Protocol.TelemetryData(18.5f, 60f, 12.4f, 120f, 2, System.currentTimeMillis());
        byte[] payload = telemetry.toBytes();

        byte[] encoded = Protocol.encode(Protocol.PacketType.TELEMETRY, token, payload);
        Protocol.Packet packet = Protocol.decode(encoded);

        assertNotNull(packet);
        assertEquals(Protocol.PacketType.TELEMETRY, packet.type());
        assertEquals(token, packet.token());
        assertArrayEquals(payload, packet.payload());
    }

    @Test
    void handleHandshake() {
        UUID token = UUID.randomUUID();
        byte[] hello = Protocol.writeClientHello(token);
        Protocol.ClientHello clientHello = Protocol.readClientHello(hello);
        assertNotNull(clientHello);
        assertEquals(token, clientHello.token());

        byte[] serverHello = Protocol.writeServerHello(token, 25566);
        Protocol.ServerHello parsed = Protocol.readServerHello(serverHello);
        assertNotNull(parsed);
        assertEquals(25566, parsed.udpPort());
    }

    @Test
    void telemetrySerialization() {
        Protocol.TelemetryData telemetry = new Protocol.TelemetryData(20f, 40f, 8f, 75f, 1, 123456);
        Protocol.TelemetryData copy = Protocol.TelemetryData.fromBytes(telemetry.toBytes());
        assertNotNull(copy);
        assertEquals(telemetry.health(), copy.health());
        assertEquals(telemetry.ping(), copy.ping());
        assertEquals(telemetry.bandwidthMbps(), copy.bandwidthMbps());
        assertEquals(telemetry.fps(), copy.fps());
        assertEquals(telemetry.packetLoss(), copy.packetLoss());
        assertEquals(telemetry.timestamp(), copy.timestamp());
    }

    @Test
    void hintSerialization() {
        String message = "reduce";
        Protocol.HintData hint = new Protocol.HintData(Protocol.HintData.FLAG_THROTTLE_COSMETICS, 1024, message);
        Protocol.HintData copy = Protocol.HintData.fromBytes(hint.toBytes());
        assertNotNull(copy);
        assertEquals(hint.flags(), copy.flags());
        assertEquals(hint.bandwidthBudget(), copy.bandwidthBudget());
        assertEquals(hint.message(), copy.message());
    }
}
