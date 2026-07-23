package dev.enchantmaster;

import com.mojang.logging.LogUtils;
import dev.enchantmaster.audit.AuditLog;
import dev.enchantmaster.command.EnchantMasterCommands;
import dev.enchantmaster.config.ConfigUpgradeTracker;
import dev.enchantmaster.config.EnchantMasterConfig;
import dev.enchantmaster.network.ModNetwork;
import dev.enchantmaster.web.EnchantMasterHttpServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(EnchantMaster.MODID)
public class EnchantMaster {
    public static final String MODID = "enchantmaster";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EnchantMaster(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        ModNetwork.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
        // SERVER config: NeoForge merges by key on upgrade (keeps user values, adds missing defaults only)
        modContainer.registerConfig(ModConfig.Type.SERVER, EnchantMasterConfig.SPEC);
        ConfigUpgradeTracker.register(modContainer);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Enchant Master common setup complete (version from mods.toml / jar metadata)");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        EnchantMasterCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        EnchantMasterHttpServer.bindServer(event.getServer());
        AuditLog.bindServerDirectory(event.getServer().getServerDirectory());
        LOGGER.info(
                "Enchant Master ready. Use /enchantmaster web start (bind {}:{}, public {}). "
                        + "Audit log: logs/enchantmaster-audit.log",
                EnchantMasterConfig.WEB_HOST.get(),
                EnchantMasterConfig.WEB_PORT.getAsInt(),
                EnchantMasterConfig.publicWebUrl()
        );
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        EnchantMasterHttpServer.stopIfRunning();
        EnchantMasterHttpServer.bindServer(null);
        AuditLog.bindServerDirectory(null);
        dev.enchantmaster.forge.ItemCatalog.invalidateCache();
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            dev.enchantmaster.web.WebAccessControl.removePlayer(player.getUUID());
        }
    }
}
