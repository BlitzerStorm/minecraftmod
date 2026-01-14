package dev.modnet.fabric;

import dev.modnet.common.Protocol;
import dev.modnet.common.UdpClient;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Fabric client entrypoint for ModNet. */
public final class ModNetFabricClient implements ClientModInitializer {
    private static final Identifier CHANNEL = new Identifier(Protocol.CHANNEL);
    private static final Logger LOGGER = LoggerFactory.getLogger("modnet");

    private volatile UdpClient udpClient;
    private volatile UUID currentToken = UUID.randomUUID();
    private final AtomicInteger cosmeticTick = new AtomicInteger();
    private volatile long lastFrameTime = System.nanoTime();

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(CHANNEL, this::handleServerHello);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sendHello());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stopUdp());
        ClientTickEvents.END_CLIENT_TICK.register(this::handleTick);
    }

    private void handleTick(MinecraftClient client) {
        if (udpClient == null || client.player == null) {
            return;
        }
        int tick = cosmeticTick.incrementAndGet();
        if (tick % 5 == 0) {
            sendCosmetic(client);
        }
    }

    private void sendHello() {
        currentToken = UUID.randomUUID();
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer());
        buffer.writeBytes(Protocol.writeClientHello(currentToken));
        ClientPlayNetworking.send(CHANNEL, buffer);
    }

    private void handleServerHello(MinecraftClient client, ClientPlayNetworkHandler handler, PacketByteBuf buf, PacketSender sender) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        Protocol.ServerHello hello = Protocol.readServerHello(data);
        if (hello == null || hello.udpPort() <= 0) {
            stopUdp();
            return;
        }
        startUdp(client, hello.udpPort(), hello.token());
    }

    private void startUdp(MinecraftClient client, int udpPort, UUID token) {
        stopUdp();
        InetSocketAddress address = resolveServerAddress(client, udpPort);
        if (address == null) {
            LOGGER.warn("Cannot resolve UDP address");
            return;
        }
        try {
            udpClient = new UdpClient(address, token, this::handleHint, LOGGER::info);
            udpClient.setTelemetrySupplier(this::createTelemetry);
            udpClient.start();
            LOGGER.info("Started UDP client at {}", address);
        } catch (Exception e) {
            LOGGER.error("Failed to start UDP client", e);
        }
    }

    private Protocol.TelemetryData createTelemetry() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) {
            return null;
        }
        float health = client.player.getHealth();
        float ping = client.getNetworkHandler().getAverageRoundTripTime();
        float bandwidth = 0f;
        float fps = calculateFps();
        int packetLoss = 0;
        return new Protocol.TelemetryData(health, ping, bandwidth, fps, packetLoss, System.currentTimeMillis());
    }

    private float calculateFps() {
        long now = System.nanoTime();
        long delta = Math.max(1, now - lastFrameTime);
        lastFrameTime = now;
        return 1_000_000_000f / delta;
    }

    private void sendCosmetic(MinecraftClient client) {
        Vec3d velocity = client.player.getVelocity();
        Protocol.CosmeticData cosmetic = new Protocol.CosmeticData(
                client.player.getX(),
                client.player.getY(),
                client.player.getZ(),
                client.player.getYaw(),
                client.player.getPitch(),
                velocity.x,
                velocity.y,
                velocity.z,
                client.player.isSprinting() ? 1 : 0
        );
        udpClient.sendCosmetic(cosmetic);
    }

    private void handleHint(Protocol.HintData hint) {
        if ((hint.flags() & Protocol.HintData.FLAG_THROTTLE_COSMETICS) != 0) {
            LOGGER.info("Server requested cosmetic throttle: {}", hint.message());
        }
    }

    private InetSocketAddress resolveServerAddress(MinecraftClient client, int port) {
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null) {
            return null;
        }
        SocketAddress address = handler.getConnection().getAddress();
        if (address instanceof InetSocketAddress inetAddress) {
            return new InetSocketAddress(inetAddress.getHostString(), port);
        }
        return null;
    }

    private void stopUdp() {
        if (udpClient != null) {
            udpClient.close();
            udpClient = null;
        }
    }
}
