package ru.kojner.advancementborder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.border.WorldBorder;
import ru.kojner.advancementborder.world.ChallengeState;

public class ClientBorderHelper {

    public static boolean isOutsideChallengeBorder(double x, double z) {
        if (!ChallengeState.voidOutsideBorder) return false;

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;

        WorldBorder border = level.getWorldBorder();
        boolean isChallengeWorld = Math.abs(border.getCenterX() - 0.5) < 0.01
                && Math.abs(border.getCenterZ() - 0.5) < 0.01
                && border.getSize() < 50_000_000.0;

        if (!isChallengeWorld) return false;

        double halfSize = border.getSize() / 2.0;
        double dx = Math.abs(x - border.getCenterX());
        double dz = Math.abs(z - border.getCenterZ());

        return dx > halfSize + 0.01 || dz > halfSize + 0.01;
    }
}
