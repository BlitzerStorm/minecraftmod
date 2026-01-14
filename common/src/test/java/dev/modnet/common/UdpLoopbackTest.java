package dev.modnet.common;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpLoopbackTest {
    @Test
    void telemetryLoopback() throws Exception {
        UdpServer.Config serverConfig = new UdpServer.Config(8192, 256, 500, 1000, 30, 2, 250);
        try (UdpServer server = new UdpServer(0, message -> {
        }, serverConfig)) {
            UUID token = UUID.randomUUID();
            server.register(token);
            CountDownLatch latch = new CountDownLatch(1);
            server.setTelemetryListener((id, data) -> latch.countDown());

            UdpClient.Config clientConfig = new UdpClient.Config(8192, 256, 1000, 3000);
            try (UdpClient client = new UdpClient(new InetSocketAddress("127.0.0.1", server.getPort()), token, hint -> {
            }, message -> {
            }, clientConfig)) {
                client.start();
                client.sendTelemetry(new Protocol.TelemetryData(20f, 40f, 5f, 60f, 0, System.currentTimeMillis()));
                assertTrue(latch.await(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void rateLimitDropsPackets() throws Exception {
        UdpServer.Config serverConfig = new UdpServer.Config(8192, 256, 1, 1, 30, 1, 200);
        try (UdpServer server = new UdpServer(0, message -> {
        }, serverConfig)) {
            UUID token = UUID.randomUUID();
            server.register(token);
            UdpClient.Config clientConfig = new UdpClient.Config(8192, 256, 1000, 3000);
            try (UdpClient client = new UdpClient(new InetSocketAddress("127.0.0.1", server.getPort()), token, hint -> {
            }, message -> {
            }, clientConfig)) {
                client.start();
                client.sendTelemetry(new Protocol.TelemetryData(20f, 40f, 5f, 60f, 0, System.currentTimeMillis()));
                client.sendTelemetry(new Protocol.TelemetryData(21f, 41f, 5f, 60f, 0, System.currentTimeMillis()));
                TimeUnit.MILLISECONDS.sleep(300);
                assertEquals(1, server.stats().packetsReceived());
                assertTrue(server.stats().packetsDropped() >= 1);
            }
        }
    }

    @Test
    void unregisteredTokenIsDropped() throws Exception {
        UdpServer.Config serverConfig = new UdpServer.Config(8192, 256, 100, 200, 30, 1, 200);
        try (UdpServer server = new UdpServer(0, message -> {
        }, serverConfig)) {
            UUID token = UUID.randomUUID();
            UdpClient.Config clientConfig = new UdpClient.Config(8192, 256, 1000, 3000);
            try (UdpClient client = new UdpClient(new InetSocketAddress("127.0.0.1", server.getPort()), token, hint -> {
            }, message -> {
            }, clientConfig)) {
                client.start();
                client.sendTelemetry(new Protocol.TelemetryData(20f, 40f, 5f, 60f, 0, System.currentTimeMillis()));
                TimeUnit.MILLISECONDS.sleep(300);
                assertEquals(0, server.stats().packetsReceived());
                assertTrue(server.stats().packetsDropped() >= 1);
            }
        }
    }
}
