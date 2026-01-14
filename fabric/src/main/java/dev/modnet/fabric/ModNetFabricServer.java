package dev.modnet.fabric;

import com.mojang.brigadier.CommandDispatcher;
import dev.modnet.common.LogLevel;
import dev.modnet.common.Protocol;
import dev.modnet.common.UdpServer;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Dedicated server entrypoint for ModNet on Fabric. */
public final class ModNetFabricServer implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("modnet");
    private static final Identifier CHANNEL = new Identifier(Protocol.CHANNEL);

    private final ConcurrentHashMap<UUID, ServerPlayerEntity> tokenToPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Protocol.TelemetryData> telemetryStats = new ConcurrentHashMap<>();
    private UdpServer udpServer;
    private ModNetConfig config;
    private int udpPort;

    @Override
    public void onInitializeServer() {
        config = ModNetConfig.load();
        if (config.isUdpEnabled()) {
            try {
                udpServer = new UdpServer(config.resolveUdpPort(), message -> log(message, LogLevel.INFO), config.serverConfig());
                udpPort = udpServer.getPort();
                udpServer.setTelemetryListener(this::handleTelemetry);
                udpServer.setCosmeticListener((token, data) -> {
                });
                udpServer.setHintListener((token, hint) -> log("Received hint from " + token + ": " + hint.message(), LogLevel.INFO));
            } catch (SocketException e) {
                LOGGER.error("Failed to start ModNet UDP server", e);
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CHANNEL, this::handleClientHello);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> handleLogout(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> close());
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    private void handleClientHello(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        Protocol.ClientHello hello = Protocol.readClientHello(data);
        if (hello == null) {
            return;
        }
        UUID token = hello.token();
        tokenToPlayer.put(token, player);
        if (udpServer != null) {
            udpServer.register(token);
        }
        PacketByteBuf response = new PacketByteBuf(Unpooled.buffer());
        int port = udpServer != null ? udpPort : 0;
        response.writeBytes(Protocol.writeServerHello(token, port));
        ServerPlayNetworking.send(player, CHANNEL, response);
    }

    private void handleTelemetry(UUID token, Protocol.TelemetryData data) {
        telemetryStats.put(token, data);
    }

    private void handleLogout(ServerPlayerEntity player) {
        tokenToPlayer.entrySet().removeIf(entry -> {
            if (!entry.getValue().getUUID().equals(player.getUUID())) {
                return false;
            }
            telemetryStats.remove(entry.getKey());
            if (udpServer != null) {
                udpServer.unregister(entry.getKey());
            }
            return true;
        });
    }

    private void close() {
        if (udpServer != null) {
            udpServer.close();
            udpServer = null;
            udpPort = 0;
        }
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess access, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("modnet").then(CommandManager.literal("stats").executes(context -> {
            context.getSource().sendFeedback(Text.literal(buildStats()), false);
            return 1;
        })));
    }

    private String buildStats() {
        int playerCount = tokenToPlayer.size();
        double avgLoss = telemetryStats.values().stream().mapToInt(Protocol.TelemetryData::packetLoss).average().orElse(0.0);
        if (udpServer == null) {
            return "ModNet UDP stats: disabled";
        }
        UdpServer.Stats stats = udpServer.stats();
        return "ModNet UDP stats: players=" + playerCount
                + ", avgLoss=" + String.format("%.2f", avgLoss) + "%"
                + ", packetsRx=" + stats.packetsReceived()
                + ", packetsTx=" + stats.packetsSent()
                + ", drops=" + stats.packetsDropped()
                + ", invalid=" + stats.invalidPackets();
    }

    private void log(String message, LogLevel level) {
        if (config != null && !config.getLogLevel().allows(level)) {
            return;
        }
        if (level == LogLevel.ERROR) {
            LOGGER.error(message);
        } else if (level == LogLevel.WARN) {
            LOGGER.warn(message);
        } else {
            LOGGER.info(message);
        }
    }
}
