package ru.kojner.advancementborder.scanner;

import com.mojang.brigadier.arguments.LongArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import ru.kojner.advancementborder.AdvancementBorder;

@EventBusSubscriber(modid = AdvancementBorder.MODID)
public class ScannerCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("scanworld")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("start")
                                .then(Commands.argument("attempts", LongArgumentType.longArg(1, 100_000_000L))
                                        .executes(ctx -> {
                                            long attempts = LongArgumentType.getLong(ctx, "attempts");
                                            if (SeedScannerEngine.isRunning()) {
                                                ctx.getSource().sendFailure(Component.literal("Сканер уже запущен! Сначала введи /scanworld stop"));
                                                return 0;
                                            }
                                            SeedScannerEngine.startScan(ctx.getSource().getServer(), attempts);
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("stats")
                                .executes(ctx -> {
                                    StringBuilder fails = new StringBuilder();
                                    SeedScannerEngine.STRUCTURE_FAILS.forEach((k, v) -> fails.append(k).append(": ").append(v.get()).append(" | "));
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "§6=== Статистика сканера ===\n" +
                                                    "§7Проверено сидов: §f" + SeedScannerEngine.TOTAL_CHECKED.get() + "\n" +
                                                    "§7Прошли биомы: §e" + SeedScannerEngine.PASSED_BIOMES.get() + "\n" +
                                                    "§7Прошли структуры: §b" + SeedScannerEngine.PASSED_STRUCTURES.get() + "\n" +
                                                    "§7Идеальных найдено: §a" + SeedScannerEngine.PASSED_ALL.get() + "\n" +
                                                    "§8Отсев структур: " + (fails.isEmpty() ? "нет" : fails.toString())
                                    ), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("testloot")
                                .executes(ctx -> {
                                    var server = ctx.getSource().getServer();
                                    long currentSeed = server.overworld().getSeed();
                                    ctx.getSource().sendSuccess(() -> Component.literal("§e[Test Loot] Запуск проверки сида " + currentSeed + " в фоне..."), false);
                                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                                        try {
                                            SeedScannerEngine.testSingleSeedNow(server, currentSeed);
                                        } catch (Exception e) {
                                            AdvancementBorder.LOGGER.error("Ошибка в testloot", e);
                                        }
                                    });
                                    return 1;
                                })
                        )
                        .then(Commands.literal("stop")
                                .executes(ctx -> {
                                    SeedScannerEngine.stopScan(ctx.getSource().getServer());
                                    return 1;
                                })
                        )
                        .then(Commands.literal("verify")
                                .executes(ctx -> {
                                    var server = ctx.getSource().getServer();
                                    ctx.getSource().sendSuccess(() -> Component.literal("§e[Verify] Запуск перепроверки сидов в фоне..."), false);
                                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                                        try {
                                            SeedScannerEngine.verifyExistingSeeds(server);
                                        } catch (Exception e) {
                                            AdvancementBorder.LOGGER.error("Ошибка в verify", e);
                                        }
                                    });
                                    return 1;
                                })
                        )
        );
    }

    @SubscribeEvent
    public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (event.getServer().isDedicatedServer()) {
            SeedScannerEngine.startScan(event.getServer(), 99_999_999L);
        }
    }

}
