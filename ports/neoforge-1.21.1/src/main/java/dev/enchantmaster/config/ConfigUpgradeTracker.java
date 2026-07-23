package dev.enchantmaster.config;

import dev.enchantmaster.EnchantMaster;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Tracks mod/config upgrades so existing server configs are preserved across updates.
 * <p>
 * NeoForge's {@link net.neoforged.neoforge.common.ModConfigSpec} merges by key:
 * values already present in {@code config/enchantmaster-server.toml} are kept, and only
 * missing keys receive defaults. This class records the previous install version and
 * logs upgrades; it never rewrites the whole config to stock defaults.
 */
public final class ConfigUpgradeTracker {
    private static final String STATE_FILE = "enchantmaster-install.properties";

    private ConfigUpgradeTracker() {
    }

    public static void register(ModContainer container) {
        container.getEventBus().addListener(ConfigUpgradeTracker::onConfigLoading);
        container.getEventBus().addListener(ConfigUpgradeTracker::onConfigReloading);
        NeoForge.EVENT_BUS.addListener(ConfigUpgradeTracker::onServerAboutToStart);
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        if (!isOurServerConfig(event.getConfig())) return;
        EnchantMaster.LOGGER.info(
                "Enchant Master server config loaded (schema {}). "
                        + "Existing keys are preserved; only missing options get defaults.",
                EnchantMasterConfig.CONFIG_SCHEMA_VERSION
        );
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (!isOurServerConfig(event.getConfig())) return;
        EnchantMaster.LOGGER.info(
                "Enchant Master server config reloaded (schema {}). User values preserved.",
                EnchantMasterConfig.CONFIG_SCHEMA_VERSION
        );
    }

    private static boolean isOurServerConfig(ModConfig config) {
        return config != null
                && EnchantMaster.MODID.equals(config.getModId())
                && config.getType() == ModConfig.Type.SERVER;
    }

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        try {
            Path configDir = event.getServer().getServerDirectory().resolve("config");
            Files.createDirectories(configDir);
            Path statePath = configDir.resolve(STATE_FILE);
            Path serverToml = configDir.resolve("enchantmaster-server.toml");

            String currentVersion = currentModVersion();
            Properties props = new Properties();
            String previousVersion = null;
            int previousSchema = 0;

            if (Files.isRegularFile(statePath)) {
                try (var in = Files.newInputStream(statePath)) {
                    props.load(in);
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
                    // Upgrading from a build that predates install tracking
                    EnchantMaster.LOGGER.info(
                            "Enchant Master {} detected existing config/enchantmaster-server.toml "
                                    + "with no prior install state. Keeping all existing settings; "
                                    + "new options (if any) will use defaults only where missing.",
                            currentVersion
                    );
                } else {
                    EnchantMaster.LOGGER.info(
                            "Enchant Master {} first install on this server (config schema {}).",
                            currentVersion,
                            currentSchema
                    );
                }
            } else if (!previousVersion.equals(currentVersion) || previousSchema < currentSchema) {
                EnchantMaster.LOGGER.info(
                        "Enchant Master upgrade: {} → {} (config schema {} → {}). "
                                + "Preserving existing config values; only new keys get defaults.",
                        previousVersion,
                        currentVersion,
                        previousSchema,
                        currentSchema
                );
                runMigrations(previousSchema, currentSchema, previousVersion, currentVersion);
            }

            props.setProperty("modVersion", currentVersion);
            props.setProperty("configSchemaVersion", Integer.toString(currentSchema));
            props.setProperty("note", "Managed by Enchant Master. Safe to delete; recreated on next start.");
            try (var out = Files.newOutputStream(statePath)) {
                props.store(out, "Enchant Master install / upgrade state — not secrets");
            }
        } catch (IOException e) {
            EnchantMaster.LOGGER.warn("Could not update Enchant Master install state: {}", e.toString());
        }
    }

    /**
     * One-way migrations when a schema jump needs more than “add missing keys”.
     * Never overwrite user-set values unless a key was renamed/removed.
     */
    private static void runMigrations(int fromSchema, int toSchema, String fromMod, String toMod) {
        if (fromSchema >= toSchema) return;
        // 1.0.2 / schema 3: access-control + publicUrl keys are Spec defaults for missing entries only.
        EnchantMaster.LOGGER.info(
                "Config migration {}→{} finished (mod {}→{}).",
                fromSchema,
                toSchema,
                fromMod,
                toMod
        );
    }

    private static String currentModVersion() {
        Optional<? extends ModContainer> container = ModList.get().getModContainerById(EnchantMaster.MODID);
        if (container.isPresent()) {
            return container.get().getModInfo().getVersion().toString();
        }
        return "unknown";
    }
}
