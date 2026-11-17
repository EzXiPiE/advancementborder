package ru.kojner.advancementborder.mixin;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.kojner.advancementborder.client.ClientBorderHelper;

@Mixin(RenderSectionRegion.class)
public abstract class VoidOutsideBorderMixin {

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void hideBlocksOutsideBorder(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (ClientBorderHelper.isOutsideChallengeBorder(pos.getX() + 0.5, pos.getZ() + 0.5)) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void hideFluidsOutsideBorder(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (ClientBorderHelper.isOutsideChallengeBorder(pos.getX() + 0.5, pos.getZ() + 0.5)) {
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
        }
    }
}
