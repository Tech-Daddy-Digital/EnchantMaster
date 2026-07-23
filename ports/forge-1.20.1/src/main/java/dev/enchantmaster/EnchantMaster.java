package dev.enchantmaster;

import com.mojang.logging.LogUtils;
import dev.enchantmaster.audit.AuditLog;
import dev.enchantmaster.command.EnchantMasterCommands;
import dev.enchantmaster.config.ConfigUpgradeTracker;
import dev.enchantmaster.config.EnchantMasterConfig;
import dev.enchantmaster.network.ModNetwork;
import dev.enchantmaster.web.EnchantMasterHttpServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(EnchantMaster.MODID)
public class EnchantMaster {
    public static final String MODID = "enchantmaster";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EnchantMaster() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        ModNetwork.register(modEventBus);
        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, EnchantMasterConfig.SPEC);
        ConfigUpgradeTracker.register(modEventBus);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::init);
        LOGGER.info("Enchant Master common setup complete (Forge 1.20.1)");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        EnchantMasterCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        EnchantMasterHttpServer.bindServer(event.getServer());
        AuditLog.bindServerDirectory(event.getServer().getServerDirectory().toPath());
        LOGGER.info(
                "Enchant Master ready. Use /enchantmaster web start (bind {}:{}, public {}). "
                        + "Audit log: logs/enchantmaster-audit.log",
                EnchantMasterConfig.WEB_HOST.get(),
                EnchantMasterConfig.WEB_PORT.get(),
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
