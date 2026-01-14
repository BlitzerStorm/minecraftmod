package dev.modnet.paper;

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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        udpPort = getConfig().getInt("udp-port", 25566);
        udpEnabled = getConfig().getBoolean("udp-enabled", true);

        getServer().getMessenger().registerIncomingPluginChannel(this, Protocol.CHANNEL, this::handlePluginMessage);
        getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);

        if (udpEnabled) {
            try {
                udpServer = new UdpServer(udpPort, getLogger()::info);
                udpServer.setTelemetryListener((token, data) -> telemetryCache.put(token, data));
                udpServer.setCosmeticListener((token, data) -> {
                });
                udpServer.setHintListener((token, hint) -> getLogger().info("Hint for {}: {}", token, hint.message()));
                getLogger().info("UDP listener started on port " + udpPort);
            } catch (Exception e) {
                getLogger().warning("Unable to start UDP listener: " + e.getMessage());
            }
        } else {
            getLogger().info("UDP listener disabled");
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
        sender.sendMessage("ModNet UDP stats: players=" + tokenToPlayer.size() + ", avgLoss=" + String.format("%.2f", avgLoss) + "%");
        return true;
    }
}
