package dev.modnet.forge;

import dev.modnet.common.LogLevel;
import dev.modnet.common.Protocol;
import dev.modnet.common.UdpServer;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.network.ServerCustomPayloadEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.net.SocketException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Mod.EventBusSubscriber(modid = ModNetForge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModNetForgeServer {
    private static final Logger LOGGER = Logger.getLogger("modnet");
    private static final ResourceLocation CHANNEL = new ResourceLocation("modnet", "main");

    private static final ConcurrentHashMap<UUID, ServerPlayer> tokenToPlayer = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Protocol.TelemetryData> telemetryStats = new ConcurrentHashMap<>();
    private static UdpServer udpServer;
    private static ModNetForgeConfig config;
    private static int udpPort;

    private ModNetForgeServer() {
    }

    static void init() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        config = ModNetForgeConfig.load();
        if (!config.isUdpEnabled()) {
            return;
        }
        try {
            udpServer = new UdpServer(config.resolveUdpPort(), message -> log(message, LogLevel.INFO), config.serverConfig());
            udpPort = udpServer.getPort();
            udpServer.setTelemetryListener((token, data) -> telemetryStats.put(token, data));
            udpServer.setCosmeticListener((token, data) -> {
            });
            udpServer.setHintListener((token, hint) -> log("Received client hint: " + hint.message(), LogLevel.INFO));
        } catch (SocketException e) {
            LOGGER.warning("Unable to start ModNet UDP server: " + e.getMessage());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppedEvent event) {
        if (udpServer != null) {
            udpServer.close();
            udpServer = null;
        }
        tokenToPlayer.clear();
        telemetryStats.clear();
        udpPort = 0;
    }

    @SubscribeEvent
    public static void onCustomPayload(ServerCustomPayloadEvent event) {
        if (!CHANNEL.equals(event.getName())) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(event.getData().copy());
        byte[] raw = new byte[buf.readableBytes()];
        buf.readBytes(raw);
        Protocol.ClientHello hello = Protocol.readClientHello(raw);
        if (hello == null) {
            return;
        }
        UUID token = hello.token();
        ServerPlayer player = event.getSource().getPlayer();
        if (player == null) {
            return;
        }
        tokenToPlayer.put(token, player);
        if (udpServer != null) {
            udpServer.register(token);
        }
        FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
        int port = udpServer != null ? udpPort : 0;
        response.writeBytes(Protocol.writeServerHello(token, port));
        player.connection.send(new ClientboundCustomPayloadPacket(CHANNEL, response));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("modnet").then(Commands.literal("stats").executes(context -> {
            context.getSource().sendSuccess(Component.literal(buildStats()), false);
            return 1;
        })));
    }

    private static String buildStats() {
        int players = tokenToPlayer.size();
        double avgLoss = telemetryStats.values().stream().mapToInt(Protocol.TelemetryData::packetLoss).average().orElse(0);
        if (udpServer == null) {
            return "ModNet UDP stats: disabled";
        }
        UdpServer.Stats stats = udpServer.stats();
        return "ModNet UDP stats: players=" + players
                + ", avgLoss=" + String.format("%.2f", avgLoss) + "%"
                + ", packetsRx=" + stats.packetsReceived()
                + ", packetsTx=" + stats.packetsSent()
                + ", drops=" + stats.packetsDropped()
                + ", invalid=" + stats.invalidPackets();
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        UUID token = tokenToPlayer.entrySet().stream()
                .filter(entry -> entry.getValue().getUUID().equals(player.getUUID()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (token != null) {
            tokenToPlayer.remove(token);
            if (udpServer != null) {
                udpServer.unregister(token);
            }
            telemetryStats.remove(token);
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
