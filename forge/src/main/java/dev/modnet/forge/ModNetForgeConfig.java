package dev.modnet.forge;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class ModNetForgeConfig {
    private final boolean udpEnabled;
    private final int udpPort;

    private ModNetForgeConfig(boolean udpEnabled, int udpPort) {
        this.udpEnabled = udpEnabled;
        this.udpPort = udpPort;
    }

    static ModNetForgeConfig load() {
        Properties props = new Properties();
        try (InputStream defaults = ModNetForgeConfig.class.getResourceAsStream("/modnet-default.properties")) {
            if (defaults != null) {
                props.load(defaults);
            }
        } catch (IOException ignored) {
        }

        Path configPath = FMLPaths.CONFIGDIR.get().resolve("modnet.properties");
        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        } else {
            try (InputStream defaults = ModNetForgeConfig.class.getResourceAsStream("/modnet-default.properties")) {
                if (defaults != null) {
                    Files.createDirectories(configPath.getParent());
                    Files.copy(defaults, configPath);
                }
            } catch (IOException ignored) {
            }
        }

        boolean udpEnabled = Boolean.parseBoolean(props.getProperty("udp.enabled", "true"));
        int udpPort = Integer.parseInt(props.getProperty("udp.port", "25566"));
        return new ModNetForgeConfig(udpEnabled, udpPort);
    }

    boolean isUdpEnabled() {
        return udpEnabled;
    }

    int getUdpPort() {
        return udpPort;
    }
}
