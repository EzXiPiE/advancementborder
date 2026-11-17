package ru.kojner.advancementborder.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import ru.kojner.advancementborder.AdvancementBorder;
import ru.kojner.advancementborder.world.ChallengeState;

public record SyncChallengeHudPayload(int completed, int total) implements CustomPacketPayload {

    public static final Type<SyncChallengeHudPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AdvancementBorder.MODID, "sync_hud")
    );

    public static final StreamCodec<ByteBuf, SyncChallengeHudPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncChallengeHudPayload::completed,
            ByteBufCodecs.VAR_INT, SyncChallengeHudPayload::total,
            SyncChallengeHudPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(SyncChallengeHudPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ChallengeState.completedAdvancements = payload.completed();
            ChallengeState.totalAdvancements = payload.total();
        });
    }
}
