package ru.kojner.advancementborder.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.kojner.advancementborder.events.ChallengeEventHandler;
import ru.kojner.advancementborder.world.ChallengeState;
import ru.kojner.advancementborder.world.SeedPoolManager;

@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class CreateWorldScreenMixin {

    @Shadow
    @Final
    private EditBox seedEdit;

    @Shadow
    @Final
    CreateWorldScreen this$0;

    @Unique
    private boolean advancementborder$isUpdating = false;

    @Unique
    private boolean advancementborder$wasOurPreset = false;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onWorldTabInit(CreateWorldScreen helper, CallbackInfo ci) {
        WorldCreationUiState uiState = this$0.getUiState();
        uiState.addListener(this::advancementborder$updateSeedField);
        this.advancementborder$updateSeedField(uiState);
    }

    @Unique
    private void advancementborder$updateSeedField(WorldCreationUiState data) {
        if (data == null || this.seedEdit == null || this.advancementborder$isUpdating) return;

        this.advancementborder$isUpdating = true;
        try {
            WorldCreationUiState.WorldTypeEntry worldType = data.getWorldType();
            boolean isOurPreset = worldType != null
                    && worldType.preset() != null
                    && worldType.preset().is(ChallengeEventHandler.CHALLENGE_PRESET_KEY);

            ChallengeState.isChallengeSelected = isOurPreset;

            if (isOurPreset) {
                if (!this.advancementborder$wasOurPreset) {
                    // Выбираем новый сид из нашей проверенной базы при переключении на наш пресет
                    long selectedSeed = SeedPoolManager.pickNextSeed();
                    data.setSeed(String.valueOf(selectedSeed));
                    this.seedEdit.setValue("");
                    this.seedEdit.setHint(Component.literal("Случайный из списка проходимых"));
                }
                this.seedEdit.active = false;
            } else {
                if (this.advancementborder$wasOurPreset) {
                    data.setSeed("");
                    this.seedEdit.setValue("");
                    this.seedEdit.setHint(Component.translatable("selectWorld.seedInfo"));
                }
                this.seedEdit.active = true;
            }
            this.advancementborder$wasOurPreset = isOurPreset;
        } finally {
            this.advancementborder$isUpdating = false;
        }
    }
}
