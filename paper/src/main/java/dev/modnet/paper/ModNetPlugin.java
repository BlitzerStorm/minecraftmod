package dev.modnet.paper;

import dev.modnet.common.LogLevel;
import dev.modnet.common.Protocol;
import dev.modnet.common.UdpServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ModNetPlugin extends JavaPlugin implements Listener {
    private final ConcurrentHashMap<UUID, Player> tokenToPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> playerToToken = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Protocol.TelemetryData> telemetryCache = new ConcurrentHashMap<>();
    private UdpServer udpServer;
    private int udpPort;
    private boolean udpEnabled;
    private UdpServer.Config udpConfig;
    private LogLevel logLevel;
    private int udpPortMin;
    private int udpPortMax;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        udpPort = getConfig().getInt("udp-port", 25566);
        udpPortMin = getConfig().getInt("udp-port-min", 25566);
        udpPortMax = getConfig().getInt("udp-port-max", 25566);
        udpEnabled = getConfig().getBoolean("udp-enabled", true);
        int maxPayload = getConfig().getInt("udp-max-payload-bytes", Protocol.DEFAULT_MAX_PAYLOAD);
        int compressionThreshold = getConfig().getInt("udp-compression-threshold-bytes", Protocol.DEFAULT_COMPRESSION_THRESHOLD);
        int rateLimitPerSecond = getConfig().getInt("udp-rate-limit-per-second", 100);
        int rateLimitBurst = getConfig().getInt("udp-rate-limit-burst", 200);
        int sessionTimeout = getConfig().getInt("udp-session-timeout-seconds", 90);
        int hintRetryCount = getConfig().getInt("udp-hint-retry-count", 3);
        long hintRetryInterval = getConfig().getLong("udp-hint-retry-interval-millis", 750L);
        udpConfig = new UdpServer.Config(maxPayload, compressionThreshold, rateLimitPerSecond, rateLimitBurst, sessionTimeout,
                hintRetryCount, hintRetryInterval);
        logLevel = LogLevel.fromString(getConfig().getString("log-level", "info"));

        getServer().getMessenger().registerIncomingPluginChannel(this, Protocol.CHANNEL, this::handlePluginMessage);
        getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);

        if (udpEnabled) {
            try {
                udpServer = new UdpServer(resolveUdpPort(), message -> log(message, LogLevel.INFO), udpConfig);
                udpPort = udpServer.getPort();
                udpServer.setTelemetryListener((token, data) -> telemetryCache.put(token, data));
                udpServer.setCosmeticListener((token, data) -> {
                });
                udpServer.setHintListener((token, hint) -> log("Hint for " + token + ": " + hint.message(), LogLevel.INFO));
                log("UDP listener started on port " + udpPort, LogLevel.INFO);
            } catch (Exception e) {
                getLogger().warning("Unable to start UDP listener: " + e.getMessage());
            }
        } else {
            log("UDP listener disabled", LogLevel.INFO);
        }
    }

    @Override
    public void onDisable() {
        if (udpServer != null) {
            udpServer.close();
            udpServer = null;
        }
        telemetryCache.clear();
        tokenToPlayer.clear();
        playerToToken.clear();
        udpPort = 0;
    }

    private void handlePluginMessage(String channel, Player player, byte[] data) {
        if (!Protocol.CHANNEL.equals(channel)) {
            return;
        }
        Protocol.ClientHello hello = Protocol.readClientHello(data);
        if (hello == null) {
            return;
        }
        UUID token = hello.token();
        tokenToPlayer.put(token, player);
        playerToToken.put(player.getUniqueId(), token);
        if (udpServer != null) {
            udpServer.register(token);
        }

        int port = udpEnabled && udpServer != null ? udpPort : 0;
        player.sendPluginMessage(this, Protocol.CHANNEL, Protocol.writeServerHello(token, port));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID token = playerToToken.remove(event.getPlayer().getUniqueId());
        if (token != null) {
            tokenToPlayer.remove(token);
            telemetryCache.remove(token);
            if (udpServer != null) {
                udpServer.unregister(token);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("modnet")) {
            return false;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("stats")) {
            sender.sendMessage("Usage: /modnet stats");
            return true;
        }
        double avgLoss = telemetryCache.values().stream().mapToInt(Protocol.TelemetryData::packetLoss).average().orElse(0.0);
        if (udpServer == null) {
            sender.sendMessage("ModNet UDP stats: disabled");
            return true;
        }
        UdpServer.Stats stats = udpServer.stats();
        sender.sendMessage("ModNet UDP stats: players=" + tokenToPlayer.size()
                + ", avgLoss=" + String.format("%.2f", avgLoss) + "%"
                + ", packetsRx=" + stats.packetsReceived()
                + ", packetsTx=" + stats.packetsSent()
                + ", drops=" + stats.packetsDropped()
                + ", invalid=" + stats.invalidPackets());
        return true;
    }

    private void log(String message, LogLevel level) {
        if (logLevel != null && !logLevel.allows(level)) {
            return;
        }
        if (level == LogLevel.ERROR) {
            getLogger().severe(message);
        } else if (level == LogLevel.WARN) {
            getLogger().warning(message);
        } else {
            getLogger().info(message);
        }
    }

    private int resolveUdpPort() {
        if (udpPort > 0) {
            return udpPort;
        }
        if (udpPortMin <= 0 || udpPortMax < udpPortMin) {
            return 0;
        }
        if (udpPortMin == udpPortMax) {
            return udpPortMin;
        }
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(udpPortMin, udpPortMax + 1);
    }
}
