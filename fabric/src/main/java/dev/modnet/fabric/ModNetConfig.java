package dev.modnet.fabric;

import dev.modnet.common.Protocol;
import dev.modnet.common.UdpClient;
import dev.modnet.common.UdpServer;
import dev.modnet.common.LogLevel;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class ModNetConfig {
    private final boolean udpEnabled;
    private final int udpPort;
    private final int udpPortMin;
    private final int udpPortMax;
    private final int udpMaxPayloadBytes;
    private final int udpCompressionThresholdBytes;
    private final int udpRateLimitPerSecond;
    private final int udpRateLimitBurst;
    private final int udpSessionTimeoutSeconds;
    private final int udpHintRetryCount;
    private final long udpHintRetryIntervalMillis;
    private final int udpSocketTimeoutMillis;
    private final int udpFallbackTimeoutMillis;
    private final int cosmeticRateLimitTicks;
    private final LogLevel logLevel;

    private ModNetConfig(boolean udpEnabled, int udpPort, int udpPortMin, int udpPortMax, int udpMaxPayloadBytes, int udpCompressionThresholdBytes,
                         int udpRateLimitPerSecond, int udpRateLimitBurst, int udpSessionTimeoutSeconds,
                         int udpHintRetryCount, long udpHintRetryIntervalMillis, int udpSocketTimeoutMillis,
                         int udpFallbackTimeoutMillis, int cosmeticRateLimitTicks, LogLevel logLevel) {
        this.udpEnabled = udpEnabled;
        this.udpPort = udpPort;
        this.udpPortMin = udpPortMin;
        this.udpPortMax = udpPortMax;
        this.udpMaxPayloadBytes = udpMaxPayloadBytes;
        this.udpCompressionThresholdBytes = udpCompressionThresholdBytes;
        this.udpRateLimitPerSecond = udpRateLimitPerSecond;
        this.udpRateLimitBurst = udpRateLimitBurst;
        this.udpSessionTimeoutSeconds = udpSessionTimeoutSeconds;
        this.udpHintRetryCount = udpHintRetryCount;
        this.udpHintRetryIntervalMillis = udpHintRetryIntervalMillis;
        this.udpSocketTimeoutMillis = udpSocketTimeoutMillis;
        this.udpFallbackTimeoutMillis = udpFallbackTimeoutMillis;
        this.cosmeticRateLimitTicks = cosmeticRateLimitTicks;
        this.logLevel = logLevel == null ? LogLevel.INFO : logLevel;
    }

    static ModNetConfig load() {
        Properties props = new Properties();
        try (InputStream defaults = ModNetConfig.class.getResourceAsStream("/modnet-default.properties")) {
            if (defaults != null) {
                props.load(defaults);
            }
        } catch (IOException ignored) {
        }

        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("modnet.properties");
        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        } else {
            try (InputStream defaults = ModNetConfig.class.getResourceAsStream("/modnet-default.properties")) {
                if (defaults != null) {
                    Files.createDirectories(configPath.getParent());
                    Files.copy(defaults, configPath);
                }
            } catch (IOException ignored) {
            }
        }

        boolean udpEnabled = Boolean.parseBoolean(props.getProperty("udp.enabled", "true"));
        int udpPort = Integer.parseInt(props.getProperty("udp.port", "25566"));
        int udpPortMin = Integer.parseInt(props.getProperty("udp.portMin", "25566"));
        int udpPortMax = Integer.parseInt(props.getProperty("udp.portMax", "25566"));
        int udpMaxPayload = Integer.parseInt(props.getProperty("udp.maxPayloadBytes", String.valueOf(Protocol.DEFAULT_MAX_PAYLOAD)));
        int udpCompressionThreshold = Integer.parseInt(props.getProperty("udp.compressionThresholdBytes", String.valueOf(Protocol.DEFAULT_COMPRESSION_THRESHOLD)));
        int udpRateLimitPerSecond = Integer.parseInt(props.getProperty("udp.rateLimitPerSecond", "100"));
        int udpRateLimitBurst = Integer.parseInt(props.getProperty("udp.rateLimitBurst", "200"));
        int udpSessionTimeoutSeconds = Integer.parseInt(props.getProperty("udp.sessionTimeoutSeconds", "90"));
        int udpHintRetryCount = Integer.parseInt(props.getProperty("udp.hintRetryCount", "3"));
        long udpHintRetryInterval = Long.parseLong(props.getProperty("udp.hintRetryIntervalMillis", "750"));
        int udpSocketTimeout = Integer.parseInt(props.getProperty("udp.socketTimeoutMillis", "1000"));
        int udpFallbackTimeout = Integer.parseInt(props.getProperty("udp.fallbackTimeoutMillis", "5000"));
        int cosmeticRateLimitTicks = Integer.parseInt(props.getProperty("cosmetic.rateLimitTicks", "5"));
        LogLevel logLevel = LogLevel.fromString(props.getProperty("log.level", "info"));
        return new ModNetConfig(udpEnabled, udpPort, udpPortMin, udpPortMax, udpMaxPayload, udpCompressionThreshold, udpRateLimitPerSecond,
                udpRateLimitBurst, udpSessionTimeoutSeconds, udpHintRetryCount, udpHintRetryInterval, udpSocketTimeout,
                udpFallbackTimeout, cosmeticRateLimitTicks, logLevel);
    }

    boolean isUdpEnabled() {
        return udpEnabled;
    }

    int getUdpPort() {
        return udpPort;
    }

    int resolveUdpPort() {
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

    int getCosmeticRateLimitTicks() {
        return cosmeticRateLimitTicks;
    }

    LogLevel getLogLevel() {
        return logLevel;
    }

    UdpServer.Config serverConfig() {
        return new UdpServer.Config(udpMaxPayloadBytes, udpCompressionThresholdBytes, udpRateLimitPerSecond,
                udpRateLimitBurst, udpSessionTimeoutSeconds, udpHintRetryCount, udpHintRetryIntervalMillis);
    }

    UdpClient.Config clientConfig() {
        return new UdpClient.Config(udpMaxPayloadBytes, udpCompressionThresholdBytes, udpSocketTimeoutMillis, udpFallbackTimeoutMillis);
    }
}
