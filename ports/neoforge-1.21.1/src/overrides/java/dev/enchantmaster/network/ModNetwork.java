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
        PayloadRegistrar registrar = event.registrar("1").optional();
        // 1.21.1: clientbound handlers registered here (no separate client event required)
        registrar.playToClient(
                OpenForgeScreenPayload.TYPE,
                OpenForgeScreenPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        dev.enchantmaster.client.ClientForgeScreens.open(payload.canForgeForOthers()))
        );
        registrar.playToClient(
                ForgeResultPayload.TYPE,
                ForgeResultPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        dev.enchantmaster.client.ClientForgeScreens.onForgeResult(payload.success(), payload.message()))
        );
        registrar.playToServer(
                ForgeRequestPayload.TYPE,
                ForgeRequestPayload.STREAM_CODEC,
                ForgeRequestPayload::handleServer
        );
    }
}
