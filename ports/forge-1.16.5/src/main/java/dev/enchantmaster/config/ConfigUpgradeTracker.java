package dev.enchantmaster.config;

import dev.enchantmaster.EnchantMaster;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.server.FMLServerAboutToStartEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ConfigUpgradeTracker {
    private static final String STATE_FILE = "enchantmaster-install.properties";

    private ConfigUpgradeTracker() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(ConfigUpgradeTracker.class);
    }

    @SubscribeEvent
    public static void onServerAboutToStart(FMLServerAboutToStartEvent event) {
        try {
            Path configDir = event.getServer().getServerDirectory().toPath().resolve("config");
            Files.createDirectories(configDir);
            Path statePath = configDir.resolve(STATE_FILE);
            Path serverToml = configDir.resolve("enchantmaster-server.toml");

            String currentVersion = currentModVersion();
            Properties props = new Properties();
            String previousVersion = null;
            int previousSchema = 0;

            if (Files.isRegularFile(statePath)) {
                InputStream in = Files.newInputStream(statePath);
                try {
                    props.load(in);
                } finally {
                    in.close();
                }
                previousVersion = props.getProperty("modVersion");
                try {
                    previousSchema = Integer.parseInt(props.getProperty("configSchemaVersion", "0"));
                } catch (NumberFormatException ignored) {
                    previousSchema = 0;
                }
            }

            int currentSchema = EnchantMasterConfig.CONFIG_SCHEMA_VERSION;
            boolean configExisted = Files.isRegularFile(serverToml);

            if (previousVersion == null) {
                if (configExisted) {
                    EnchantMaster.LOGGER.info(
                            "Enchant Master {} detected existing config; keeping existing settings.",
                            currentVersion
                    );
                } else {
                    EnchantMaster.LOGGER.info(
                            "Enchant Master {} first install (config schema {}).",
                            currentVersion,
                            Integer.valueOf(currentSchema)
                    );
                }
            } else if (!previousVersion.equals(currentVersion) || previousSchema < currentSchema) {
                EnchantMaster.LOGGER.info(
                        "Enchant Master upgrade: {} → {} (config schema {} → {}). Preserving existing values.",
                        previousVersion,
                        currentVersion,
                        Integer.valueOf(previousSchema),
                        Integer.valueOf(currentSchema)
                );
            }

            props.setProperty("modVersion", currentVersion);
            props.setProperty("configSchemaVersion", Integer.toString(currentSchema));
            props.setProperty("note", "Managed by Enchant Master. Safe to delete; recreated on next start.");
            OutputStream out = Files.newOutputStream(statePath);
            try {
                props.store(out, "Enchant Master install / upgrade state");
            } finally {
                out.close();
            }
        } catch (IOException e) {
            EnchantMaster.LOGGER.warn("Could not update Enchant Master install state: {}", e.toString());
        }
    }

    private static String currentModVersion() {
        return ModList.get().getModContainerById(EnchantMaster.MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }
}
