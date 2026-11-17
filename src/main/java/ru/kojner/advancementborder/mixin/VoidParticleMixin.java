package ru.kojner.advancementborder.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.kojner.advancementborder.client.ClientBorderHelper;

@Mixin(ParticleEngine.class)
public abstract class VoidParticleMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void hideParticlesOutsideBorder(Particle effect, CallbackInfo ci) {
        if (effect == null) return;
        AABB box = effect.getBoundingBox();
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        if (ClientBorderHelper.isOutsideChallengeBorder(cx, cz)) {
            ci.cancel();
        }
    }
}
