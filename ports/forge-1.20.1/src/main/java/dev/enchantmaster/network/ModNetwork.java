package dev.enchantmaster.network;

import dev.enchantmaster.EnchantMaster;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * Optional SimpleChannel for in-game forge UI. Server+web work without a client mod.
 * Channel is optional so vanilla clients can join.
 */
public final class ModNetwork {
    private static final String PROTOCOL = "1";
    public static final ResourceLocation CHANNEL_NAME =
            new ResourceLocation(EnchantMaster.MODID, "main");

    private static SimpleChannel CHANNEL;

    private ModNetwork() {
    }

    public static void register(IEventBus modEventBus) {
        // Channel created in common setup (after registries ready)
    }

    public static void init() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                CHANNEL_NAME,
                () -> PROTOCOL,
                // Accept missing channel on other side (optional client mod)
                remote -> true,
                remote -> true
        );

        int id = 0;
        CHANNEL.registerMessage(
                id++,
                OpenForgeScreenPayload.class,
                OpenForgeScreenPayload::encode,
                OpenForgeScreenPayload::decode,
                OpenForgeScreenPayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                id++,
                ForgeResultPayload.class,
                ForgeResultPayload::encode,
                ForgeResultPayload::decode,
                ForgeResultPayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                id++,
                ForgeRequestPayload.class,
                ForgeRequestPayload::encode,
                ForgeRequestPayload::decode,
                ForgeRequestPayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        EnchantMaster.LOGGER.info("Enchant Master network channel registered ({})", CHANNEL_NAME);
    }

    public static SimpleChannel channel() {
        return CHANNEL;
    }

    public static boolean isReady() {
        return CHANNEL != null;
    }
}
