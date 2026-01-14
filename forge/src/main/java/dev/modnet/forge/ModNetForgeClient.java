package dev.modnet.forge;

import dev.modnet.common.LogLevel;
import dev.modnet.common.Protocol;
import dev.modnet.common.UdpClient;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientCustomPayloadEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

@Mod.EventBusSubscriber(modid = ModNetForge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModNetForgeClient {
    private static final ResourceLocation CHANNEL = new ResourceLocation("modnet", "main");
    private static final Logger LOGGER = Logger.getLogger("modnet");
    private static UdpClient udpClient;
    private static UUID currentToken = UUID.randomUUID();
    private static final AtomicInteger cosmeticCounter = new AtomicInteger();
    private static long lastFrameTime = System.nanoTime();
    private static ModNetForgeConfig config;

    private ModNetForgeClient() {
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        config = ModNetForgeConfig.load();
        currentToken = UUID.randomUUID();
        sendHello(event.getConnection());
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        stopUdp();
    }

    @SubscribeEvent
    public static void onClientPayload(ClientCustomPayloadEvent.Play event) {
        if (!CHANNEL.equals(event.getName())) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(event.getData().copy());
        byte[] raw = new byte[buf.readableBytes()];
        buf.readBytes(raw);
        Protocol.ServerHello hello = Protocol.readServerHello(raw);
        if (hello == null || hello.udpPort() <= 0) {
            stopUdp();
            return;
        }
        startUdp(hello);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (udpClient == null || client.player == null) {
            return;
        }
        int tick = cosmeticCounter.incrementAndGet();
        int rateLimit = config != null ? Math.max(1, config.getCosmeticRateLimitTicks()) : 5;
        if (tick % rateLimit == 0) {
            sendCosmetic(client);
        }
    }

    private static void sendHello(Connection connection) {
        if (connection == null) {
            return;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBytes(Protocol.writeClientHello(currentToken));
        connection.send(new ServerboundCustomPayloadPacket(CHANNEL, buffer));
    }

    private static void startUdp(Protocol.ServerHello hello) {
        stopUdp();
        Minecraft client = Minecraft.getInstance();
        InetSocketAddress address = resolveAddress(client, hello.udpPort());
        if (address == null) {
            return;
        }
        try {
            UdpClient.Config clientConfig = config != null ? config.clientConfig() : UdpClient.Config.defaults();
            udpClient = new UdpClient(address, hello.token(), ModNetForgeClient::handleHint, message -> log(message, LogLevel.INFO), clientConfig);
            udpClient.setTelemetrySupplier(ModNetForgeClient::createTelemetry);
            udpClient.start();
        } catch (Exception e) {
            // ignore startup failures
        }
    }

    private static Protocol.TelemetryData createTelemetry() {
        Minecraft client = Minecraft.getInstance();
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

    private static float calculateFps() {
        long now = System.nanoTime();
        long delta = Math.max(1, now - lastFrameTime);
        lastFrameTime = now;
        return 1_000_000_000f / delta;
    }

    private static void sendCosmetic(Minecraft client) {
        Protocol.CosmeticData cosmetic = new Protocol.CosmeticData(
                client.player.getX(),
                client.player.getY(),
                client.player.getZ(),
                client.player.getYRot(),
                client.player.getXRot(),
                client.player.getDeltaMovement().x,
                client.player.getDeltaMovement().y,
                client.player.getDeltaMovement().z,
                client.player.isSprinting() ? 1 : 0
        );
        udpClient.sendCosmetic(cosmetic);
    }

    private static void handleHint(Protocol.HintData hint) {
        if ((hint.flags() & Protocol.HintData.FLAG_THROTTLE_COSMETICS) != 0) {
            log("Server requested cosmetic throttle: " + hint.message(), LogLevel.INFO);
        }
    }

    private static InetSocketAddress resolveAddress(Minecraft client, int port) {
        if (client.getNetworkHandler() == null) {
            return null;
        }
        SocketAddress addr = client.getNetworkHandler().getConnection().getRemoteAddress();
        if (addr instanceof InetSocketAddress inet) {
            return new InetSocketAddress(inet.getHostString(), port);
        }
        return null;
    }

    private static void stopUdp() {
        if (udpClient != null) {
            udpClient.close();
            udpClient = null;
        }
    }

    private static void log(String message, LogLevel level) {
        if (config != null && !config.getLogLevel().allows(level)) {
            return;
        }
        if (level == LogLevel.ERROR) {
            LOGGER.severe(message);
        } else if (level == LogLevel.WARN) {
            LOGGER.warning(message);
        } else {
            LOGGER.info(message);
        }
    }
}
