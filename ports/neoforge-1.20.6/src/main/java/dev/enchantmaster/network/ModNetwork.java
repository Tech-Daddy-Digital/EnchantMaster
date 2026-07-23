package dev.enchantmaster.network;

import net.neoforged.bus.api.IEventBus;

/** Optional networking stubbed for server+web NeoForge 1.20.x ports. */
public final class ModNetwork {
    private ModNetwork() {}
    public static void register(IEventBus modEventBus) {}
    public static void init() {}
    public static boolean isReady() { return false; }
}
