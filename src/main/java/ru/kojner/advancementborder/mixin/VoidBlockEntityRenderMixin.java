package ru.kojner.advancementborder.mixin;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.kojner.advancementborder.client.ClientBorderHelper;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class VoidBlockEntityRenderMixin {

    @Inject(
            method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;ZLnet/minecraft/client/renderer/culling/Frustum;)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
            at = @At("HEAD"),
            cancellable = true
    )
    private <E extends BlockEntity, S extends BlockEntityRenderState> void hideBlockEntitiesOutsideBorder(
            E blockEntity,
            float partialTicks,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress,
            boolean isGloballyRendered,
            @Nullable Frustum frustum,
            CallbackInfoReturnable<@Nullable S> cir
    ) {
        BlockPos pos = blockEntity.getBlockPos();
        if (ClientBorderHelper.isOutsideChallengeBorder(pos.getX() + 0.5, pos.getZ() + 0.5)) {
            cir.setReturnValue(null);
        }
    }
}
