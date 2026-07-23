package dev.enchantmaster;

import dev.enchantmaster.audit.AuditLog;
import dev.enchantmaster.command.EnchantMasterCommands;
import dev.enchantmaster.config.ConfigUpgradeTracker;
import dev.enchantmaster.config.EnchantMasterConfig;
import dev.enchantmaster.forge.ItemCatalog;
import dev.enchantmaster.web.EnchantMasterHttpServer;
import dev.enchantmaster.web.WebAccessControl;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(EnchantMaster.MODID)
public class EnchantMaster {
    public static final String MODID = "enchantmaster";
    public static final Logger LOGGER = LogManager.getLogger();

    public EnchantMaster() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, EnchantMasterConfig.SPEC);
        ConfigUpgradeTracker.register();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Enchant Master common setup complete (Forge 1.16.5)");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        EnchantMasterCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        EnchantMasterHttpServer.bindServer(event.getServer());
        AuditLog.bindServerDirectory(event.getServer().getServerDirectory().toPath());
        LOGGER.info(
                "Enchant Master ready. Use /enchantmaster web start (bind {}:{}, public {}). Audit: logs/enchantmaster-audit.log",
                EnchantMasterConfig.WEB_HOST.get(),
                Integer.valueOf(EnchantMasterConfig.WEB_PORT.get()),
                EnchantMasterConfig.publicWebUrl()
        );
    }

    @SubscribeEvent
    public void onServerStopping(FMLServerStoppingEvent event) {
        EnchantMasterHttpServer.stopIfRunning();
        EnchantMasterHttpServer.bindServer(null);
        AuditLog.bindServerDirectory(null);
        ItemCatalog.invalidateCache();
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getPlayer() instanceof ServerPlayerEntity) {
            ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
            WebAccessControl.removePlayer(player.getUUID());
        }
    }
}
