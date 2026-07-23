package dev.enchantmaster;

import net.fabricmc.api.ModInitializer;

/** Server-side entry is unused; this mod is client-environment only. */
public final class EnchantMasterMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // no-op on dedicated server
    }
}
