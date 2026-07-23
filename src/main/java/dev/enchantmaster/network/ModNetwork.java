package dev.enchantmaster.network;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {
    private ModNetwork() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModNetwork::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // OPTIONAL payloads are required for server-only deployment:
        // - Server has Enchant Master, client does not → join succeeds (missing optional channels ignored)
        // - Client has Enchant Master, server does not → join succeeds
        // - Both have it → channels negotiate and in-game UI works
        // Non-optional payloads would disconnect clients without this mod.
        PayloadRegistrar registrar = event.registrar("1").optional();
        // Client handlers registered in EnchantMasterClient via RegisterClientPayloadHandlersEvent
        registrar.playToClient(OpenForgeScreenPayload.TYPE, OpenForgeScreenPayload.STREAM_CODEC);
        registrar.playToClient(ForgeResultPayload.TYPE, ForgeResultPayload.STREAM_CODEC);
        registrar.playToServer(
                ForgeRequestPayload.TYPE,
                ForgeRequestPayload.STREAM_CODEC,
                ForgeRequestPayload::handleServer
        );
    }
}
