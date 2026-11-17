package ru.kojner.advancementborder.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.kojner.advancementborder.client.ClientBorderHelper;

@Mixin(EntityRenderDispatcher.class)
public abstract class VoidEntityRenderMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void hideEntitiesOutsideBorder(
            E entity,
            Frustum culler,
            double camX,
            double camY,
            double camZ,
            float partialTicks,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (ClientBorderHelper.isOutsideChallengeBorder(entity.getX(), entity.getZ())) {
            cir.setReturnValue(false);
        }
    }
}
