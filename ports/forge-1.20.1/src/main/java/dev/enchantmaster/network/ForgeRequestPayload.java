package dev.enchantmaster.network;

import com.google.gson.JsonParser;
import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.forge.ForgeRequest;
import dev.enchantmaster.forge.GiveService;
import dev.enchantmaster.util.PermissionHelper;
import dev.enchantmaster.web.WebAccessControl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: forge request as JSON. */
public record ForgeRequestPayload(String json) {
    public static void encode(ForgeRequestPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.json == null ? "{}" : msg.json, 32767);
    }

    public static ForgeRequestPayload decode(FriendlyByteBuf buf) {
        return new ForgeRequestPayload(buf.readUtf(32767));
    }

    public static void handle(ForgeRequestPayload payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!PermissionHelper.canUseInGameForge(player)) {
                reply(player, false, "You must be an operator to forge items in-game.");
                return;
            }

            try {
                ForgeRequest request = ForgeRequest.fromJson(JsonParser.parseString(payload.json()).getAsJsonObject());

                if (request.targetPlayerUuid == null
                        && (request.targetPlayerName == null || request.targetPlayerName.isBlank())) {
                    request = new ForgeRequest(
                            request.itemId,
                            request.overrideLimits,
                            request.name,
                            request.lore,
                            request.enchantments,
                            request.attributes,
                            player.getUUID(),
                            player.getGameProfile().getName(),
                            request.dryRun
                    );
                }

                var server = player.serverLevel().getServer();
                if (server == null) {
                    reply(player, false, "Server not available.");
                    return;
                }
                String ip = WebAccessControl.resolvePlayerIp(player);
                AuditActor actor = AuditActor.ingame(player, ip);
                GiveService.Result result = GiveService.forgeAndGive(server, request, actor);
                reply(player, result.success(), result.message());
                if (result.success()) {
                    player.sendSystemMessage(Component.literal(result.message()));
                }
            } catch (Exception e) {
                EnchantMaster.LOGGER.warn("Forge request failed for {}: {}",
                        player.getGameProfile().getName(), e.toString());
                reply(player, false, "Invalid forge request: " + e.getMessage());
            }
        });
        context.setPacketHandled(true);
    }

    private static void reply(ServerPlayer player, boolean success, String message) {
        boolean sent = ClientModSupport.sendResult(player, success, message);
        if (!sent) {
            player.sendSystemMessage(Component.literal("[Enchant Master] " + message));
        }
    }
}
