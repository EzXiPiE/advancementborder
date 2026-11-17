package ru.kojner.advancementborder.scanner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;


import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class StructureLootScanner {

    public record LootCheckResult(
            boolean isValidStructure,
            int totalChests,
            int ominousVaults,
            boolean hasEndShip,
            List<LootEntry> generatedLootTables
    ) {}

    public record LootEntry(ResourceKey<?> lootTableKey, long lootSeed, BlockPos pos) {}

    public static LootCheckResult inspectStructure(
            MinecraftServer server,
            ServerLevel level,
            NoiseBasedChunkGenerator chunkGen,
            RandomState randomState,
            ResourceKey<Structure> structureKey,
            BlockPos approxPos,
            long worldSeed
    ) {
        var structureLookup = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var holderOpt = structureLookup.get(structureKey);
        if (holderOpt.isEmpty()) {
            return new LootCheckResult(false, 0, 0, false, List.of());
        }

        Holder.Reference<Structure> holder = holderOpt.get();
        Structure structure = holder.value();
        ChunkPos chunkPos = new ChunkPos(approxPos.getX() >> 4, approxPos.getZ() >> 4);

        StructureStart start = structure.generate(
                holder,
                level.dimension(),
                server.registryAccess(),
                chunkGen,
                chunkGen.getBiomeSource(),
                randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED),
                randomState,
                level.getStructureTemplateManager(),
                worldSeed,
                chunkPos,
                0,
                level,
                biome -> structure.biomes().contains(biome)
        );

        if (start == null || !start.isValid()) {
            return new LootCheckResult(false, 0, 0, false, List.of());
        }

        List<LootEntry> lootEntries = new ArrayList<>();
        AtomicInteger chestCounter = new AtomicInteger(0);
        AtomicInteger ominousVaultCounter = new AtomicInteger(0);
        AtomicBoolean endShipDetected = new AtomicBoolean(false);
        Map<BlockPos, BlockEntity> virtualBlockEntities = new HashMap<>();
        Map<BlockPos, BlockState> virtualBlocks = new HashMap<>();
        WorldgenRandom pieceRandom = new WorldgenRandom(new LegacyRandomSource(worldSeed));

        WorldGenLevel dummyLevel = (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("equals") && args != null && args.length == 1) return proxy == Array.get(args, 0);
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("toString")) return "DummyLootWorldGenLevel";

                    if (name.equals("setBlock") && args != null && args.length >= 2) {
                        BlockPos pos = ((BlockPos) Array.get(args, 0)).immutable();
                        BlockState state = (BlockState) Array.get(args, 1);
                        virtualBlocks.put(pos, state);

                        // Варочная стойка внутри END_CITY генерируется исключительно в корабле Энда
                        if (state.is(Blocks.BREWING_STAND)) {
                            endShipDetected.set(true);
                        }

                        if (state.is(Blocks.VAULT) && state.hasProperty(VaultBlock.OMINOUS)) {
                            if (state.getValue(VaultBlock.OMINOUS)) {
                                ominousVaultCounter.incrementAndGet();
                            }
                        } else if (state.is(Blocks.CHEST)
                                || state.is(Blocks.BARREL)
                                || state.is(Blocks.SUSPICIOUS_SAND)
                                || state.is(Blocks.SUSPICIOUS_GRAVEL)) {
                            chestCounter.incrementAndGet();
                            BlockEntity be = state.hasBlockEntity()
                                    ? ((EntityBlock) state.getBlock()).newBlockEntity(pos, state)
                                    : null;
                            if (be != null) {
                                virtualBlockEntities.put(pos, be);
                            }
                        }
                        return true;
                    }

                    if (name.equals("getBlockState") && args != null && args.length == 1) {
                        BlockPos pos = (BlockPos) Array.get(args, 0);
                        BlockState existing = virtualBlocks.get(pos);
                        if (existing != null) return existing;

                        if (level.dimension() == ServerLevel.OVERWORLD) {
                            return pos.getY() <= 62
                                    ? Blocks.SAND.defaultBlockState()
                                    : Blocks.WATER.defaultBlockState();
                        }
                        return Blocks.AIR.defaultBlockState();
                    }

                    if (name.equals("hasChunk")) return true;
                    if (name.equals("getSeaLevel")) return level.getSeaLevel();
                    if (name.equals("isEmptyBlock") && args != null && args.length == 1) {
                        BlockPos pos = (BlockPos) Array.get(args, 0);
                        BlockState st = virtualBlocks.get(pos);
                        return st == null || st.isAir();
                    }

                    if (name.equals("getFluidState") && args != null && args.length == 1) {
                        BlockPos pos = (BlockPos) Array.get(args, 0);
                        return virtualBlocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()).getFluidState();
                    }

                    if (name.equals("getBlockEntity") && args != null) {
                        if (args.length == 1) {
                            return virtualBlockEntities.get((BlockPos) Array.get(args, 0));
                        }
                        if (args.length == 2) {
                            BlockEntity be = virtualBlockEntities.get((BlockPos) Array.get(args, 0));
                            var expectedType = (BlockEntityType<?>) Array.get(args, 1);
                            if (be != null && be.getType() == expectedType) {
                                return Optional.of(be);
                            }
                            return Optional.empty();
                        }
                    }

                    if (name.equals("getChunk")) {
                        int cx = 0, cz = 0;
                        if (args != null && args.length >= 2 && Array.get(args, 0) instanceof Integer x && Array.get(args, 1) instanceof Integer z) {
                            cx = x;
                            cz = z;
                        } else if (args != null && args.length == 1 && Array.get(args, 0) instanceof BlockPos bp) {
                            cx = bp.getX() >> 4;
                            cz = bp.getZ() >> 4;
                        }
                        return new net.minecraft.world.level.chunk.ProtoChunk(
                                new ChunkPos(cx, cz),
                                net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                                level,
                                level.palettedContainerFactory(),
                                null
                        );
                    }

                    if (name.equals("getLevel")) return level;
                    if (name.equals("getRandom")) return pieceRandom;
                    if (name.equals("getSeed")) return worldSeed;
                    if (name.equals("registryAccess")) return server.registryAccess();
                    if (name.equals("holderLookup") && args != null && args.length == 1) {
                        return server.registryAccess().lookupOrThrow((ResourceKey) Array.get(args, 0));
                    }
                    if (name.equals("enabledFeatures")) return level.enabledFeatures();
                    if (name.equals("getMinY")) return level.getMinY();
                    if (name.equals("getHeight") && args != null && args.length >= 2) return 63;
                    if (name.equals("getMaxY")) return level.getMaxY();

                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    if (ret == float.class) return 0f;
                    if (ret == double.class) return 0d;
                    return null;
                }
        );

        for (StructurePiece piece : start.getPieces()) {
            BoundingBox rawBox = piece.getBoundingBox();
            int minChunkX = rawBox.minX() >> 4;
            int maxChunkX = rawBox.maxX() >> 4;
            int minChunkZ = rawBox.minZ() >> 4;
            int maxChunkZ = rawBox.maxZ() >> 4;

            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    ChunkPos pieceChunk = new ChunkPos(cx, cz);
                    BoundingBox chunkBox = new BoundingBox(
                            cx << 4, level.getMinY(), cz << 4,
                            (cx << 4) + 15, level.getMaxY(), (cz << 4) + 15
                    );
                    BlockPos chunkCenter = chunkBox.getCenter();
                    pieceRandom.setLargeFeatureSeed(worldSeed, cx, cz);
                    try {
                        piece.postProcess(
                                dummyLevel,
                                level.structureManager(),
                                chunkGen,
                                pieceRandom,
                                chunkBox,
                                pieceChunk,
                                chunkCenter
                        );
                    } catch (Exception ignored) {

                    }
                }
            }
        }
        for (var entry : virtualBlockEntities.entrySet()) {
            BlockEntity be = entry.getValue();
            if (be instanceof RandomizableContainerBlockEntity container && container.getLootTable() != null) {
                lootEntries.add(new LootEntry(
                        container.getLootTable(),
                        container.getLootTableSeed(),
                        entry.getKey()
                ));
            } else if (be instanceof BrushableBlockEntity) {
                CompoundTag tag = be.saveWithoutMetadata(server.registryAccess());
                String tableId = tag.getString("loot_table")
                        .or(() -> tag.getString("LootTable"))
                        .orElse("");
                if (!tableId.isEmpty()) {
                    long seed = tag.contains("loot_table_seed")
                            ? tag.getLongOr("loot_table_seed", 0L)
                            : tag.getLongOr("LootTableSeed", 0L);
                    ResourceKey<?> key = ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(tableId));
                    lootEntries.add(new LootEntry(key, seed, entry.getKey()));
                }
            }
        }
        return new LootCheckResult(true, chestCounter.get(), ominousVaultCounter.get(), endShipDetected.get(), lootEntries);
    }
}