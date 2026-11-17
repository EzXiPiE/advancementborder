package ru.kojner.advancementborder.scanner;

import com.mojang.brigadier.arguments.LongArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import ru.kojner.advancementborder.AdvancementBorder;

import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = AdvancementBorder.MODID)
public class ScannerCommand {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Автозапуск сработает только если сервер запущен с флагом -Dadvancementborder.autoscan=true
        // На обычном игровом сервере сканер не будет нагружать процессор
        if (Boolean.getBoolean("advancementborder.autoscan")) {
            AdvancementBorder.LOGGER.info("Обнаружен флаг -Dadvancementborder.autoscan=true! Запуск сканера сидов...");
            SeedScannerEngine.startScan(event.getServer(), 99_999_999L);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Корректно останавливаем потоки и сохраняем базу при выключении сервера или выходе из мира
        if (SeedScannerEngine.isRunning()) {
            AdvancementBorder.LOGGER.info("Остановка сервера — сохранение результатов сканера...");
            SeedScannerEngine.stopScan(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("scanworld")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("start")
                                .executes(ctx -> {
                                    SeedScannerEngine.startScan(ctx.getSource().getServer(), 500_000L);
                                    return 1;
                                })
                                .then(Commands.argument("attempts", LongArgumentType.longArg(1000, 100_000_000L))
                                        .executes(ctx -> {
                                            long attempts = LongArgumentType.getLong(ctx, "attempts");
                                            SeedScannerEngine.startScan(ctx.getSource().getServer(), attempts);
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("stop")
                                .executes(ctx -> {
                                    SeedScannerEngine.stopScan(ctx.getSource().getServer());
                                    return 1;
                                })
                        )
                        .then(Commands.literal("stats")
                                .executes(ctx -> {
                                    long checked = SeedScannerEngine.TOTAL_CHECKED.get();
                                    long rare = SeedScannerEngine.PASSED_RARE.get();
                                    long biomes = SeedScannerEngine.PASSED_BIOMES.get();
                                    long structs = SeedScannerEngine.PASSED_STRUCTURES.get();
                                    long found = SeedScannerEngine.FOUND_SEEDS.size();
                                    long perfectTree = SeedScannerEngine.PERFECT_TREE_SEEDS.size();
                                    String fails = SeedScannerEngine.STRUCTURE_FAILS.toString();

                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "§6=== Статистика сканера ===\n" +
                                                    "§7Проверено: §f" + checked + "\n" +
                                                    "§7Прошли редкие биомы: §e" + rare + "\n" +
                                                    "§7Прошли все биомы: §a" + biomes + "\n" +
                                                    "§7Прошли структуры: §b" + structs + "\n" +
                                                    "§7Найдено идеальных: §6§l" + found + " §7(с природным деревом: §6" + perfectTree + "§7)\n" +
                                                    "§7Причины отсева: §c" + fails
                                    ), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("testloot")
                                .executes(ctx -> {
                                    var server = ctx.getSource().getServer();
                                    long currentSeed = server.overworld().getSeed();
                                    ctx.getSource().sendSuccess(() -> Component.literal("§e[Test Loot] Запуск проверки сида " + currentSeed + " в фоне..."), false);
                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            SeedScannerEngine.testSingleSeedNow(server, currentSeed);
                                        } catch (Exception e) {
                                            AdvancementBorder.LOGGER.error("Ошибка в testloot", e);
                                        }
                                    });
                                    return 1;
                                })
                        )
                        .then(Commands.literal("verify")
                                .executes(ctx -> {
                                    var server = ctx.getSource().getServer();
                                    ctx.getSource().sendSuccess(() -> Component.literal("§e[Verify] Запуск перепроверки сидов в фоне..."), false);
                                    CompletableFuture.runAsync(() -> {
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
}
