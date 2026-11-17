package ru.kojner.advancementborder.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.kojner.advancementborder.world.ChallengeState;
import ru.kojner.advancementborder.world.SeedPoolManager;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldOnCreateMixin {

    @Shadow
    @Final
    WorldCreationUiState uiState;

    @Inject(method = "onCreate", at = @At("HEAD"))
    private void onBeforeCreateWorld(CallbackInfo ci) {
        if (ChallengeState.isChallengeSelected) {
            long selectedSeed = SeedPoolManager.pickNextSeed();
            this.uiState.setSeed(String.valueOf(selectedSeed));
        }
    }
}
