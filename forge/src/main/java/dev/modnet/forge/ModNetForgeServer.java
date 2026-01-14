package dev.modnet.forge;

import dev.modnet.common.Protocol;
import dev.modnet.common.UdpServer;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.commands.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
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

    private static final ConcurrentHashMap<UUID, ServerPlayerEntity> tokenToPlayer = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Protocol.TelemetryData> telemetryStats = new ConcurrentHashMap<>();
    private static UdpServer udpServer;
    private static ModNetForgeConfig config;

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
            udpServer = new UdpServer(config.getUdpPort(), LOGGER::info);
            udpServer.setTelemetryListener((token, data) -> telemetryStats.put(token, data));
            udpServer.setCosmeticListener((token, data) -> {
            });
            udpServer.setHintListener((token, hint) -> LOGGER.info("Received client hint: " + hint.message()));
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
        ServerPlayerEntity player = event.getSource().getPlayer();
        if (player == null) {
            return;
        }
        tokenToPlayer.put(token, player);
        if (udpServer != null) {
            udpServer.register(token);
        }
        FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
        int port = udpServer != null ? config.getUdpPort() : 0;
        response.writeBytes(Protocol.writeServerHello(token, port));
        player.connection.send(new ClientboundCustomPayloadPacket(CHANNEL, response));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(CommandManager.literal("modnet").then(CommandManager.literal("stats").executes(context -> {
            context.getSource().sendSuccess(Text.literal(buildStats()), false);
            return 1;
        })));
    }

    private static String buildStats() {
        int players = tokenToPlayer.size();
        double avgLoss = telemetryStats.values().stream().mapToInt(Protocol.TelemetryData::packetLoss).average().orElse(0);
        return "ModNet UDP stats: players=" + players + ", avg loss=" + String.format("%.2f", avgLoss) + "%";
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayerEntity player)) {
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
}
