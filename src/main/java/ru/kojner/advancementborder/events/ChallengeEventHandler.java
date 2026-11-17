package ru.kojner.advancementborder.events;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.storage.LevelData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.kojner.advancementborder.AdvancementBorder;
import ru.kojner.advancementborder.data.BorderSavedData;
import ru.kojner.advancementborder.network.SyncChallengeHudPayload;
import ru.kojner.advancementborder.world.ChallengeState;

import java.util.Set;

@EventBusSubscriber(modid = AdvancementBorder.MODID)
public class ChallengeEventHandler {

    public static final ResourceKey<WorldPreset> CHALLENGE_PRESET_KEY = ResourceKey.create(
            Registries.WORLD_PRESET,
            Identifier.fromNamespaceAndPath(AdvancementBorder.MODID, "challenge")
    );

    @SubscribeEvent
    public static void onWorldCreate(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != ServerLevel.OVERWORLD) return;

        MinecraftServer server = level.getServer();
        // Активируем режим либо при выборе пресета в клиенте, либо автоматически на выделенном сервере
        if (!ChallengeState.isChallengeSelected && !server.isDedicatedServer()) return;
        ChallengeState.isChallengeSelected = false;

        BorderSavedData data = BorderSavedData.get(level);
        data.setChallengeActive(true);

        level.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, server);

        // Гарантируем наличие стартового дерева на (0, 0)
        ensureStarterTreeAtZero(level, data);

        int safeY = getSafeZeroSpawnY(level);
        level.setRespawnData(LevelData.RespawnData.of(level.dimension(), new BlockPos(0, safeY, 0), 0.0f, 0.0f));
        event.setCanceled(true);

        syncAllBorders(server, data.getBorderDiameter());
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel overworld = player.level().getServer().overworld();
        BorderSavedData data = BorderSavedData.get(overworld);

        if (!data.isChallengeActive()) return;

        // Страховка на случай, если дерево на (0, 0) ещё не было создано
        ensureStarterTreeAtZero(overworld, data);

        syncAllBorders(overworld.getServer(), data.getBorderDiameter());
        broadcastHudSync(overworld.getServer(), data);

        // Для любого нового игрока (включая 2-го, 3-го и т.д.) вычисляем АКТУАЛЬНУЮ безопасную высоту на (0, 0)
        if (player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) < 20) {
            int safeY = getSafeZeroSpawnY(overworld);
            player.teleportTo(overworld, 0.5, safeY, 0.5, Set.of(), 0.0f, 0.0f, true);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        ServerLevel overworld = server.overworld();
        BorderSavedData data = BorderSavedData.get(overworld);
        if (!data.isChallengeActive()) return;

        // Если у игрока нет своей кровати или якоря возрождения — возвращаем строго на (0.5, safeY, 0.5),
        // чтобы его не выбросило за границу барьера или в пустоту на месте срубленного дерева
        if (player.getRespawnConfig() == null) {
            int safeY = getSafeZeroSpawnY(overworld);
            overworld.setRespawnData(LevelData.RespawnData.of(overworld.dimension(), new BlockPos(0, safeY, 0), 0.0f, 0.0f));
            player.teleportTo(overworld, 0.5, safeY, 0.5, Set.of(), 0.0f, 0.0f, true);
        }
    }

    /**
     * Вычисляет текущую безопасную высоту для спавна игрока на (0, 0)
     * и гарантирует 2 блока воздуха над ногами, чтобы игрок не задохнулся и не упал со старой высоты дерева.
     */
    private static int getSafeZeroSpawnY(ServerLevel overworld) {
        overworld.getChunk(0, 0);
        // MOTION_BLOCKING учитывает листву как твёрдую опору — игрок заспавнится прямо на верхушке листвы
        int surfaceY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);

        if (surfaceY <= overworld.getMinY()) {
            surfaceY = overworld.getMinY() + 1;
            overworld.setBlock(new BlockPos(0, overworld.getMinY(), 0), Blocks.BEDROCK.defaultBlockState(), 3);
        }

        return surfaceY;
    }

    private static void ensureStarterTreeAtZero(ServerLevel level, BorderSavedData data) {
        if (data.isStarterTreeGenerated()) return;
        data.setStarterTreeGenerated(true);

        level.getChunk(0, 0);

        // 1. Проверяем, нет ли уже бревна в столбце (0, 0) — если есть, ничего не трогаем
        BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos(0, 0, 0);
        for (int y = level.getMinY(); y < level.getMaxY(); y++) {
            checkPos.set(0, y, 0);
            if (level.getBlockState(checkPos).is(BlockTags.LOGS)) {
                return;
            }
        }

        // 2. Находим поверхность на (0, 0) и ставим блок земли под корень
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        BlockPos treeBase = new BlockPos(0, surfaceY, 0);
        level.setBlock(treeBase.below(), Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(treeBase, Blocks.AIR.defaultBlockState(), 3);

        // 3. Подбираем породу дерева под биом
        Holder<Biome> biome = level.getBiome(treeBase);
        BlockState logState = Blocks.OAK_LOG.defaultBlockState();
        BlockState leavesState = Blocks.OAK_LEAVES.defaultBlockState();

        if (biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)) {
            logState = Blocks.BIRCH_LOG.defaultBlockState();
            leavesState = Blocks.BIRCH_LEAVES.defaultBlockState();
        } else if (biome.is(BiomeTags.IS_TAIGA) || biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.GROVE)) {
            logState = Blocks.SPRUCE_LOG.defaultBlockState();
            leavesState = Blocks.SPRUCE_LEAVES.defaultBlockState();
        } else if (biome.is(BiomeTags.IS_SAVANNA)) {
            logState = Blocks.ACACIA_LOG.defaultBlockState();
            leavesState = Blocks.ACACIA_LEAVES.defaultBlockState();
        } else if (biome.is(Biomes.CHERRY_GROVE)) {
            logState = Blocks.CHERRY_LOG.defaultBlockState();
            leavesState = Blocks.CHERRY_LEAVES.defaultBlockState();
        } else if (biome.is(BiomeTags.IS_JUNGLE)) {
            logState = Blocks.JUNGLE_LOG.defaultBlockState();
            leavesState = Blocks.JUNGLE_LEAVES.defaultBlockState();
        }

        // 4. Строим естественный ствол (5 блоков) и полную крону листвы (включая макушку над стволом)
        for (int dy = 0; dy < 5; dy++) {
            level.setBlock(treeBase.above(dy), logState, 3);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 3; dy <= 4; dy++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    BlockPos leafPos = treeBase.offset(dx, dy, dz);
                    if (level.getBlockState(leafPos).isAir()) {
                        level.setBlock(leafPos, leavesState, 3);
                    }
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos leafPos = treeBase.offset(dx, 5, dz);
                if (level.getBlockState(leafPos).isAir()) {
                    level.setBlock(leafPos, leavesState, 3);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onAdvancementEarn(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.level();
        BorderSavedData data = BorderSavedData.get(level);
        if (!data.isChallengeActive()) return;
        AdvancementHolder holder = event.getAdvancement();
        DisplayInfo display = holder.value().display().orElse(null);
        if (display == null) return;
        if (!data.markAdvancementCompleted(holder.id())) return;
        int rewardRadius = getRewardForAdvancement(display);
        if (rewardRadius <= 0) return;
        double oldDiameter = data.getBorderDiameter();
        data.addRadius(rewardRadius);
        double newDiameter = data.getBorderDiameter();
        MinecraftServer server = level.getServer();
        if (server != null) {
            for (ServerLevel dim : server.getAllLevels()) {
                WorldBorder border = dim.getWorldBorder();
                border.setCenter(0.5, 0.5);
                border.lerpSizeBetween(oldDiameter, newDiameter, 20L, dim.getGameTime());
            }
            level.playSound(
                    null,
                    player.getX(), player.getY(), player.getZ(),
                    SoundEvents.EXPERIENCE_ORB_PICKUP,
                    SoundSource.PLAYERS,
                    0.8f,
                    rewardRadius >= 50 ? 1.4f : 1.15f
            );
            broadcastHudSync(server, data);
            int newSize = (int) Math.round(newDiameter);
            Component actionMsg = Component.literal(
                    "§a§l+" + rewardRadius + " к радиусу! §7(Граница: §b" + newSize + "x" + newSize + "§7)"
            );
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(actionMsg, true);
            }
        }
    }
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("borderinfo")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            BorderSavedData data = BorderSavedData.get(player.level());
                            if (!data.isChallengeActive()) {
                                ctx.getSource().sendFailure(Component.literal("Этот мир не использует режим 1 Блок = 1 Достижение."));
                                return 0;
                            }
                            int total = countTotalVisibleAdvancements(player.level().getServer());
                            int size = (int) Math.round(data.getBorderDiameter());
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§6=== 1 Блок = 1 Достижение ===\n" +
                                            "§7Размер границы: §b" + size + "x" + size + " §7(Радиус: §f" + data.getCurrentRadius() + " §7бл.)\n" +
                                            "§7Командный прогресс: §e" + data.getCompletedCount() + " §7из §e" + total
                            ), false);
                            return 1;
                        })
        );
        event.getDispatcher().register(
                Commands.literal("borderstart")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            ServerLevel overworld = server.overworld();
                            BorderSavedData data = BorderSavedData.get(overworld);

                            data.setChallengeActive(true);
                            overworld.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, server);
                            ensureStarterTreeAtZero(overworld, data);

                            int safeY = getSafeZeroSpawnY(overworld);
                            overworld.setRespawnData(LevelData.RespawnData.of(overworld.dimension(), new BlockPos(0, safeY, 0), 0.0f, 0.0f));
                            syncAllBorders(server, data.getBorderDiameter());
                            broadcastHudSync(server, data);

                            ctx.getSource().sendSuccess(() -> Component.literal("§a[AdvancementBorder] Режим 1 Блок = 1 Достижение принудительно активирован!"), true);
                            return 1;
                        })
        );

    }
    private static void broadcastHudSync(MinecraftServer server, BorderSavedData data) {
        if (server == null) return;
        int total = countTotalVisibleAdvancements(server);
        int completed = data.getCompletedCount();
        ChallengeState.completedAdvancements = completed;
        ChallengeState.totalAdvancements = total;
        PacketDistributor.sendToAllPlayers(new SyncChallengeHudPayload(completed, total));
    }
    private static int countTotalVisibleAdvancements(MinecraftServer server) {
        if (server == null) return 126;
        int total = 0;
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (holder.value().display().isPresent()) {
                total++;
            }
        }
        return total;
    }
    private static int getRewardForAdvancement(DisplayInfo display) {
        if (display.hidden()) return 150;
        return switch (display.type()) {
            case TASK -> 1;
            case GOAL -> 10;
            case CHALLENGE -> 50;
        };
    }
    private static void syncAllBorders(MinecraftServer server, double diameter) {
        if (server == null) return;
        for (ServerLevel dim : server.getAllLevels()) {
            WorldBorder border = dim.getWorldBorder();
            border.setCenter(0.5, 0.5);
            border.setSize(diameter);
            border.setDamagePerBlock(0.2);
            border.setWarningBlocks(0);
        }
    }
}