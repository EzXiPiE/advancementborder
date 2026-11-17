package ru.kojner.advancementborder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import ru.kojner.advancementborder.AdvancementBorder;
import ru.kojner.advancementborder.world.ChallengeState;

@EventBusSubscriber(modid = AdvancementBorder.MODID, value = Dist.CLIENT)
public class ChallengeHudOverlay {

    private static int lastKnownBorderSize = -1;

    @SubscribeEvent
    public static void onRenderGui(RenderGuiLayerEvent.Post event) {
        if (!event.getName().equals(VanillaGuiLayers.HOTBAR)) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;

        if (player == null || level == null) return;

        WorldBorder border = level.getWorldBorder();
        boolean isChallengeWorld = Math.abs(border.getCenterX() - 0.5) < 0.01
                && Math.abs(border.getCenterZ() - 0.5) < 0.01
                && border.getSize() < 50_000_000.0;

        if (!isChallengeWorld) return;

        int currentSize = (int) Math.round(border.getSize());
        if (lastKnownBorderSize != -1 && currentSize != lastKnownBorderSize && ChallengeState.voidOutsideBorder) {
            refreshBorderChunks(level, currentSize);
        }
        lastKnownBorderSize = currentSize;

        if (mc.getDebugOverlay().showDebugScreen()) return;

        int done = ChallengeState.completedAdvancements;
        int total = ChallengeState.totalAdvancements;
        Component line = Component.literal("§7Достижения: §e" + done + "/" + total);

        var graphics = event.getGuiGraphics();
        graphics.text(mc.font, line, 6, 6, 0xFFFFFFFF, true);
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("bordervoid")
                        .executes(ctx -> {
                            ChallengeState.voidOutsideBorder = !ChallengeState.voidOutsideBorder;
                            Minecraft mc = Minecraft.getInstance();
                            if (mc.level != null && mc.player != null) {
                                int pcx = mc.player.getBlockX() >> 4;
                                int pcz = mc.player.getBlockZ() >> 4;
                                for (int cx = pcx - 10; cx <= pcx + 10; cx++) {
                                    for (int cz = pcz - 10; cz <= pcz + 10; cz++) {
                                        for (int cy = mc.level.getMinSectionY(); cy <= mc.level.getMaxSectionY(); cy++) {
                                            mc.level.setSectionDirtyWithNeighbors(cx, cy, cz);
                                        }
                                    }
                                }
                            }
                            String status = ChallengeState.voidOutsideBorder ? "§aВКЛЮЧЕНА" : "§cВЫКЛЮЧЕНА";
                            ctx.getSource().sendSuccess(() -> Component.literal("§7Пустота за границей мира: " + status), false);
                            return 1;
                        })
        );
    }

    private static void refreshBorderChunks(ClientLevel level, int size) {
        int maxChunk = ((size / 2) >> 4) + 1;
        for (int cx = -maxChunk; cx <= maxChunk; cx++) {
            for (int cz = -maxChunk; cz <= maxChunk; cz++) {
                if (Math.abs(cx) >= maxChunk - 1 || Math.abs(cz) >= maxChunk - 1) {
                    for (int cy = level.getMinSectionY(); cy <= level.getMaxSectionY(); cy++) {
                        level.setSectionDirtyWithNeighbors(cx, cy, cz);
                    }
                }
            }
        }
    }
}
