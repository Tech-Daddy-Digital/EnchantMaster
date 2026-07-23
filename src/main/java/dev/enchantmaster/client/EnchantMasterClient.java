package dev.enchantmaster.client;

import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.network.ForgeResultPayload;
import dev.enchantmaster.network.OpenForgeScreenPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

@Mod(value = EnchantMaster.MODID, dist = Dist.CLIENT)
public class EnchantMasterClient {
    public EnchantMasterClient(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(EnchantMasterClient::registerClientPayloads);
    }

    private static void registerClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(OpenForgeScreenPayload.TYPE, (payload, context) ->
                context.enqueueWork(() -> ClientForgeScreens.open(payload.canForgeForOthers())));
        event.register(ForgeResultPayload.TYPE, (payload, context) ->
                context.enqueueWork(() -> ClientForgeScreens.onForgeResult(payload.success(), payload.message())));
    }
}
