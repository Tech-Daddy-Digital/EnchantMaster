package dev.enchantmaster.client;

import dev.enchantmaster.EnchantMaster;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/** 1.21.1 client entry — payloads registered on common bus via ModNetwork. */
@Mod(value = EnchantMaster.MODID, dist = Dist.CLIENT)
public class EnchantMasterClient {
    public EnchantMasterClient(IEventBus modEventBus, ModContainer container) {
        // no-op: client payload handlers registered in ModNetwork for 1.21.1
    }
}
