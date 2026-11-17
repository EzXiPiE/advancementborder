package ru.kojner.advancementborder.scanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import ru.kojner.advancementborder.AdvancementBorder;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.material.Fluids;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;



public class SeedScannerEngine {

    private static final AtomicBoolean IS_RUNNING = new AtomicBoolean(false);
    private static ExecutorService executor;
    private static final Map<Holder<Structure>, List<RandomSpreadStructurePlacement>> PLACEMENT_CACHE = new ConcurrentHashMap<>();

    private static void buildPlacementCache(RegistryAccess registries) {
        PLACEMENT_CACHE.clear();
        var setLookup = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        for (var setHolder : setLookup.listElements().toList()) {
            var set = setHolder.value();
            if (set.placement() instanceof RandomSpreadStructurePlacement spread) {
                for (var entry : set.structures()) {
                    PLACEMENT_CACHE.computeIfAbsent(entry.structure(), k -> new ArrayList<>()).add(spread);
                }
            }
        }
    }


    public static final AtomicLong TOTAL_CHECKED = new AtomicLong(0);
    public static final AtomicLong PASSED_RARE = new AtomicLong(0);
    public static final AtomicLong PASSED_BIOMES = new AtomicLong(0);
    public static final AtomicLong PASSED_STRUCTURES = new AtomicLong(0);
    public static final AtomicLong PASSED_ALL = new AtomicLong(0);

    public static final Map<String, AtomicLong> STRUCTURE_FAILS = new ConcurrentHashMap<>();

    public static final List<FoundSeedReport> FOUND_SEEDS = new CopyOnWriteArrayList<>();
    public static final AtomicLong PASSED_PERFECT_TREE = new AtomicLong(0);
    public static final List<FoundSeedReport> PERFECT_TREE_SEEDS = new CopyOnWriteArrayList<>();

    public record FoundSeedReport(
            long seed,
            String strongholdPos,
            String warmRuinPos,
            String ancientCityPos,
            String mansionPos,
            String netherFortressPos,
            String bastionPos,
            String treeInfo
    ) {
    }

    public static boolean isRunning() {
        return IS_RUNNING.get();
    }

    public static void startScan(MinecraftServer server, long attempts) {
        if (IS_RUNNING.getAndSet(true)) return;

        // Подгружаем старые найденные сиды, чтобы не затереть файлы при сохранении
        loadExistingResultsFromFiles();

        TOTAL_CHECKED.set(0);
        PASSED_RARE.set(0);
        PASSED_BIOMES.set(0);
        PASSED_STRUCTURES.set(0);
        PASSED_ALL.set(0);
        STRUCTURE_FAILS.clear();
        PASSED_PERFECT_TREE.set(0);

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        executor = Executors.newFixedThreadPool(threads);

        ServerLevel overworld = server.overworld();
        ServerLevel nether = server.getLevel(ServerLevel.NETHER);
        RegistryAccess registries = server.registryAccess();
        buildPlacementCache(registries);

        broadcast(server, "§a[Scanner] Турбо-поиск запущен на §e" + threads + " §aпотоках (" + attempts + " сидов). В базе уже: §f" + FOUND_SEEDS.size() + " §a(с деревом: §6" + PERFECT_TREE_SEEDS.size() + "§a)...");

        final AtomicLong lastReportTime = new AtomicLong(System.currentTimeMillis());

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                while (IS_RUNNING.get()) {
                    long checked = TOTAL_CHECKED.incrementAndGet();
                    if (checked > attempts) break;

                    long seed = random.nextLong();
                    try {
                        checkSingleSeed(server, overworld, nether, registries, seed);
                    } catch (Exception e) {
                        AdvancementBorder.LOGGER.error("Ошибка при проверке сида " + seed, e);
                    }

                    if (checked % 50_000 == 0) {
                        long now = System.currentTimeMillis();
                        long prev = lastReportTime.getAndSet(now);
                        double sec = Math.max(0.05, (now - prev) / 1000.0);
                        long speed = (long) (50_000 / sec);

                        AdvancementBorder.LOGGER.info(
                                "[Scanner] Проверено: {} | Редкие: {} | Все биомы: {} | Структуры: {} | НАЙДЕНО: {} ({} сид/сек) | С деревом: {} | Отсев: {}",
                                checked,
                                PASSED_RARE.get(),
                                PASSED_BIOMES.get(),
                                PASSED_STRUCTURES.get(),
                                PASSED_ALL.get(),
                                speed,
                                PASSED_PERFECT_TREE.get(),
                                STRUCTURE_FAILS
                        );
                    }
                }

                if (TOTAL_CHECKED.get() >= attempts && IS_RUNNING.getAndSet(false)) {
                    if (executor != null) executor.shutdown();
                    saveResultsToFile();
                    String finishMsg = "[Scanner] Поиск завершён! Проверено " + attempts + ", всего в базе идеальных сидов: " + FOUND_SEEDS.size();
                    AdvancementBorder.LOGGER.info(finishMsg);
                    broadcast(server, "§6" + finishMsg);
                }
            });
        }
    }

    public static void stopScan(MinecraftServer server) {
        if (IS_RUNNING.getAndSet(false)) {
            if (executor != null) executor.shutdownNow();
            saveResultsToFile();
            broadcast(server, "§e[Scanner] Поиск остановлен. Проверено: " + TOTAL_CHECKED.get() + ", найдено: " + FOUND_SEEDS.size());
        }
    }


    private static void recordStructureFail(String name) {
        STRUCTURE_FAILS.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
    }

    private static void checkSingleSeed(MinecraftServer server, ServerLevel overworld, ServerLevel nether, RegistryAccess registries, long seed) {
        ChunkGenerator owGen = overworld.getChunkSource().getGenerator();
        ChunkGenerator netherGen = nether.getChunkSource().getGenerator();
        ServerLevel endLevel = server.getLevel(ServerLevel.END);

        if (!(owGen instanceof NoiseBasedChunkGenerator owNoiseGen) || !(netherGen instanceof NoiseBasedChunkGenerator netherNoiseGen)) {
            return;
        }

        RandomState owRandom = RandomState.create(
                registries.lookupOrThrow(Registries.NOISE),
                seed,
                owNoiseGen.generatorSettings().value()
        );
        BiomeResolver owResolver = owGen.getBiomeSource().createCachingResolver(owRandom);

        // --- СТУПЕНЬ 1: 6 САМЫХ РЕДКИХ БИОМОВ ---
        if (!checkRareBiomesFast(owResolver)) {
            return;
        }
        PASSED_RARE.incrementAndGet();

        // --- СТУПЕНЬ 2: ЛЁГКАЯ ПРОВЕРКА ОСОБНЯКА И ДРЕВНЕГО ГОРОДА (без createState!) ---
        var structureLookup = registries.lookupOrThrow(Registries.STRUCTURE);
        var structureSetLookup = registries.lookupOrThrow(Registries.STRUCTURE_SET);

        var mansionOpt = structureLookup.get(BuiltinStructures.WOODLAND_MANSION);
        if (mansionOpt.isPresent()) {
            BlockPos mPos = findRandomSpreadFast(structureSetLookup, owResolver, mansionOpt.get(), seed, ScanCriteria.OVERWORLD_STRUCTURES.get(BuiltinStructures.WOODLAND_MANSION));
            if (mPos == null) {
                recordStructureFail("woodland_mansion");
                return;
            }
        }

        var cityOpt = structureLookup.get(BuiltinStructures.ANCIENT_CITY);
        if (cityOpt.isPresent()) {
            BlockPos cPos = findRandomSpreadFast(structureSetLookup, owResolver, cityOpt.get(), seed, ScanCriteria.OVERWORLD_STRUCTURES.get(BuiltinStructures.ANCIENT_CITY));
            if (cPos == null) {
                recordStructureFail("ancient_city");
                return;
            }
        }

        // --- СТУПЕНЬ 3: ПОЛНАЯ ПРОВЕРКА ВСЕХ БИОМОВ ОВЕРВОРЛДА И НЕЗЕРА ---
        if (!checkBiomes(owResolver, registries, ScanCriteria.REQUIRED_OVERWORLD_BIOMES, true)) {
            return;
        }

        RandomState netherRandom = RandomState.create(
                registries.lookupOrThrow(Registries.NOISE),
                seed,
                netherNoiseGen.generatorSettings().value()
        );
        BiomeResolver netherResolver = netherGen.getBiomeSource().createCachingResolver(netherRandom);
        if (!checkBiomes(netherResolver, registries, ScanCriteria.REQUIRED_NETHER_BIOMES, false)) {
            return;
        }
        PASSED_BIOMES.incrementAndGet();

        // --- СТУПЕНЬ 4: ОСТАЛЬНЫЕ СТРУКТУРЫ (включая Крепость Края) ---
        ChunkGeneratorStructureState owStructState = owGen.createState(structureSetLookup, owRandom, seed);

        var strongholdOpt = structureLookup.get(BuiltinStructures.STRONGHOLD);
        if (strongholdOpt.isEmpty()) return;
        BlockPos strongholdPos = findNearestStructurePos(owStructState, owResolver, strongholdOpt.get(), ScanCriteria.OVERWORLD_STRUCTURES.get(BuiltinStructures.STRONGHOLD));
        if (strongholdPos == null) {
            recordStructureFail("Stronghold");
            return;
        }


        int villageMaxDist = ScanCriteria.OVERWORLD_STRUCTURES.get(BuiltinStructures.VILLAGE_PLAINS);
        var villageTagOpt = structureLookup.get(StructureTags.VILLAGE);
        boolean hasVillage = false;
        if (villageTagOpt.isPresent()) {
            for (Holder<Structure> vHolder : villageTagOpt.get()) {
                if (vHolder instanceof Holder.Reference<Structure> ref && findRandomSpreadFast(structureSetLookup, owResolver, ref, seed, villageMaxDist) != null) {
                    hasVillage = true;
                    break;
                }
            }
        }
        if (!hasVillage) {
            recordStructureFail("Village");
            return;
        }

        Map<ResourceKey<Structure>, BlockPos> foundOw = new HashMap<>();
        foundOw.put(BuiltinStructures.STRONGHOLD, strongholdPos);

        for (var entry : ScanCriteria.OVERWORLD_STRUCTURES.entrySet()) {
            if (entry.getKey().equals(BuiltinStructures.STRONGHOLD) || entry.getKey().equals(BuiltinStructures.VILLAGE_PLAINS))
                continue;
            var opt = structureLookup.get(entry.getKey());
            if (opt.isEmpty()) continue;

            BlockPos pos = findRandomSpreadFast(structureSetLookup, owResolver, opt.get(), seed, entry.getValue());
            if (pos == null) {
                recordStructureFail(entry.getKey().identifier().getPath());
                return;
            }
            foundOw.put(entry.getKey(), pos);
        }


        Map<ResourceKey<Structure>, BlockPos> foundNether = new HashMap<>();
        for (var entry : ScanCriteria.NETHER_STRUCTURES.entrySet()) {
            var opt = structureLookup.get(entry.getKey());
            if (opt.isEmpty()) continue;

            BlockPos pos = findRandomSpreadFast(structureSetLookup, netherResolver, opt.get(), seed, entry.getValue());
            if (pos == null) {
                recordStructureFail(entry.getKey().identifier().getPath());
                return;
            }
            foundNether.put(entry.getKey(), pos);
        }
        PASSED_STRUCTURES.incrementAndGet();

        // 4.4. Проверка Города Края в Энде (<= 1550 бл.)
        if (endLevel != null && endLevel.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator endNoiseGen) {
            RandomState endRandom = RandomState.create(
                    registries.lookupOrThrow(Registries.NOISE),
                    seed,
                    endNoiseGen.generatorSettings().value()
            );
            BiomeResolver endResolver = endNoiseGen.getBiomeSource().createCachingResolver(endRandom);
            var endCityOpt = structureLookup.get(BuiltinStructures.END_CITY);
            if (endCityOpt.isPresent()) {
                BlockPos endCityPos = findRandomSpreadFast(structureSetLookup, endResolver, endCityOpt.get(), seed, ScanCriteria.END_CITY_MAX_RADIUS);
                if (endCityPos == null) {
                    recordStructureFail("end_city");
                    return;
                }
            }
        }

        // --- СТУПЕНЬ 5: ОБЯЗАТЕЛЬНАЯ ПРОВЕРКА КРИТИЧЕСКОГО ЛУТА ---
        String lootStatus = inspectCriticalLootReport(
                server, overworld, nether, owNoiseGen, netherNoiseGen,
                owRandom, netherRandom, foundOw, foundNether, seed
        );
        if (!lootStatus.startsWith("Лут: ИДЕАЛЬНЫЙ")) {
            recordStructureFail("MissingLoot");
            return;
        }

        // --- СТУПЕНЬ 6: ПРОВЕРКА ТВЁРДОЙ ПОВЕРХНОСТИ И БРЕВНА В СТОЛБЦЕ (0, 0) ---
        int zeroCheckResult = checkZeroStartCondition(server, overworld, owNoiseGen, owRandom, owResolver, seed);
        if (zeroCheckResult == -999) {
            recordStructureFail("WaterOrLavaAtZero");
            return;
        }

        boolean hasTreeAtZero = (zeroCheckResult > -999 && zeroCheckResult != -100);
        String treeStatus = hasTreeAtZero
                ? "БРЕВНО НА (0,0) ОБНАРУЖЕНО на Y=" + zeroCheckResult + " | " + lootStatus
                : "Твёрдая суша на (0,0) (дерево поставит мод) | " + lootStatus;

        // --- ФИКСАЦИЯ РЕЗУЛЬТАТА В НУЖНЫЙ ФАЙЛ ---
        PASSED_ALL.incrementAndGet();
        FoundSeedReport report = new FoundSeedReport(
                seed,
                posToStr(foundOw.get(BuiltinStructures.STRONGHOLD)),
                posToStr(foundOw.get(BuiltinStructures.OCEAN_RUIN_WARM)),
                posToStr(foundOw.get(BuiltinStructures.ANCIENT_CITY)),
                posToStr(foundOw.get(BuiltinStructures.WOODLAND_MANSION)),
                posToStr(foundNether.get(BuiltinStructures.FORTRESS)),
                posToStr(foundNether.get(BuiltinStructures.BASTION_REMNANT)),
                treeStatus
        );

        if (hasTreeAtZero) {
            PASSED_PERFECT_TREE.incrementAndGet();
            PERFECT_TREE_SEEDS.add(report);
            String msg = "[!!!] АБСОЛЮТНО ИДЕАЛЬНЫЙ СИД (С ДЕРЕВОМ НА 0,0): " + seed + " (Y=" + zeroCheckResult + ")";
            AdvancementBorder.LOGGER.info(msg);
            broadcast(server, "§6§l" + msg);
        }

     else {
            FOUND_SEEDS.add(report);
            String msg = "[!] ИДЕАЛЬНЫЙ СИД (без дерева на 0,0): " + seed;
            AdvancementBorder.LOGGER.info(msg);
            broadcast(server, "§a" + msg);
        }
        saveResultsToFile();


        String foundMsg = "[!] НАЙДЕН ГЕОГРАФИЧЕСКИ ИДЕАЛЬНЫЙ СИД: " + seed + " (" + report.treeInfo + ")";
        AdvancementBorder.LOGGER.info(foundMsg);
        broadcast(server, "§a§l" + foundMsg);

    }

    /**
     * Проверяет столбец (0, 0):
     * Возвращает Y бревна, если дерево уже выросло.
     * Возвращает -100, если дерева нет, но самый верхний блок на (0, 0) — твёрдая суша (не вода/лава).
     * Возвращает -999, если сверху вода, лава, пустота или произошла ошибка.
     */
    private static int checkZeroStartCondition(
            MinecraftServer server,
            ServerLevel overworld,
            NoiseBasedChunkGenerator owNoiseGen,
            RandomState owRandom,
            BiomeResolver owResolver,
            long seed
    ) {
        try {
            ChunkPos centerPos = new ChunkPos(0, 0);
            Map<ChunkPos, ProtoChunk> chunkGrid = new HashMap<>(9);
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    ChunkPos cp = new ChunkPos(cx, cz);
                    chunkGrid.put(cp, new ProtoChunk(
                            cp,
                            UpgradeData.EMPTY,
                            overworld,
                            overworld.palettedContainerFactory(),
                            null
                    ));
                }
            }

            WorldGenLevel fakeRegion = (WorldGenLevel) Proxy.newProxyInstance(
                    WorldGenLevel.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("equals") && args != null && args.length == 1) return proxy == Array.get(args, 0);
                        if (name.equals("hashCode")) return System.identityHashCode(proxy);
                        if (name.equals("toString")) return "FakeWorldGenRegion";

                        if (name.equals("getCenter")) return centerPos;
                        if (name.equals("getSeed")) return seed;
                        if (name.equals("getLevel")) return overworld;
                        if (name.equals("registryAccess")) return server.registryAccess();
                        if (name.equals("enabledFeatures")) return overworld.enabledFeatures();
                        if (name.equals("getMinY")) return overworld.getMinY();
                        if (name.equals("getHeight") && (args == null || args.length == 0)) return overworld.getHeight();
                        if (name.equals("getMaxY")) return overworld.getMaxY();
                        if (name.equals("getRandom")) {
                            return new WorldgenRandom(new LegacyRandomSource(seed));
                        }

                        if (name.equals("hasChunk") && args != null && args.length == 2) {
                            int cx = (int) Array.get(args, 0);
                            int cz = (int) Array.get(args, 1);
                            return Math.abs(cx) <= 1 && Math.abs(cz) <= 1;
                        }

                        if (name.equals("ensureCanWrite") && args != null && args.length == 1) {
                            BlockPos p = (BlockPos) Array.get(args, 0);
                            return Math.abs(p.getX() >> 4) <= 1 && Math.abs(p.getZ() >> 4) <= 1;
                        }

                        if (name.equals("getChunk")) {
                            if (args != null && args.length >= 2 && Array.get(args, 0) instanceof Integer cx && Array.get(args, 1) instanceof Integer cz) {
                                int safeCx = Math.max(-1, Math.min(1, cx));
                                int safeCz = Math.max(-1, Math.min(1, cz));
                                return chunkGrid.get(new ChunkPos(safeCx, safeCz));
                            }
                            return chunkGrid.get(centerPos);
                        }


                        if (name.equals("getBlockState") && args != null && args.length == 1) {
                            BlockPos p = (BlockPos) Array.get(args, 0);
                            ProtoChunk c = chunkGrid.get(new ChunkPos(p.getX() >> 4, p.getZ() >> 4));
                            return c != null ? c.getBlockState(p) : Blocks.AIR.defaultBlockState();
                        }

                        if (name.equals("getFluidState") && args != null && args.length == 1) {
                            BlockPos p = (BlockPos) Array.get(args, 0);
                            ProtoChunk c = chunkGrid.get(new ChunkPos(p.getX() >> 4, p.getZ() >> 4));
                            return c != null ? c.getFluidState(p) : Fluids.EMPTY.defaultFluidState();
                        }

                        if (name.equals("setBlock") && args != null && args.length >= 2) {
                            BlockPos p = (BlockPos) Array.get(args, 0);
                            BlockState st = (BlockState) Array.get(args, 1);
                            ProtoChunk c = chunkGrid.get(new ChunkPos(p.getX() >> 4, p.getZ() >> 4));
                            if (c != null) {
                                c.setBlockState(p, st, 0);
                            }
                            return true;
                        }

                        if (name.equals("getBiome") && args != null && args.length == 1) {
                            BlockPos p = (BlockPos) Array.get(args, 0);
                            return owResolver.getNoiseBiome(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2);
                        }

                        if (name.equals("getNoiseBiome") && args != null && args.length == 3) {
                            int qx = (int) Array.get(args, 0);
                            int qy = (int) Array.get(args, 1);
                            int qz = (int) Array.get(args, 2);
                            return owResolver.getNoiseBiome(qx, qy, qz);
                        }

                        if (name.equals("getHeight") && args != null && args.length >= 3) {
                            var type = (Heightmap.Types) Array.get(args, 0);
                            int x = (int) Array.get(args, 1);
                            int z = (int) Array.get(args, 2);
                            ProtoChunk c = chunkGrid.get(new ChunkPos(x >> 4, z >> 4));
                            if (c != null) {
                                return c.getHeight(type, x & 15, z & 15) + 1;
                            }
                            return 63;
                        }

                        Class<?> ret = method.getReturnType();
                        if (ret == boolean.class) return false;
                        if (ret == int.class) return 0;
                        if (ret == long.class) return 0L;
                        if (ret == float.class) return 0f;
                        if (ret == double.class) return 0d;
                        return null;
                    }
            );

            BiomeManager biomeManager = new BiomeManager(fakeRegion, BiomeManager.obfuscateSeed(seed));

            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    ProtoChunk pc = chunkGrid.get(new ChunkPos(cx, cz));
                    Holder<Biome> localBiome = owResolver.getNoiseBiome((cx << 2) + 2, 16, (cz << 2) + 2);
                    owNoiseGen.buildTerrain(
                            pc,
                            Blender.empty(),
                            owRandom,
                            overworld.structureManager(),
                            biomeManager,
                            null,
                            Set.of(localBiome)
                    ).get(5, TimeUnit.SECONDS);
                }
            }

            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    // Генерируем декорации только для центрального чанка (0, 0) — все его соседи [-1..1] уже построены
                    owNoiseGen.applyBiomeDecoration(fakeRegion, chunkGrid.get(centerPos), overworld.structureManager());
                }
            }

            ProtoChunk centerChunk = chunkGrid.get(centerPos);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(0, 0, 0);

            int foundLogY = -100;
            boolean foundTopSolid = false;

            // Идём сверху вниз по столбцу (0, 0)
            for (int y = overworld.getMaxY() - 1; y >= overworld.getMinY(); y--) {
                pos.set(0, y, 0);
                BlockState state = centerChunk.getBlockState(pos);

                if (state.isAir()) continue;

                // Проверяем самый первый не-воздушный блок сверху
                if (!foundTopSolid) {
                    // Если сверху жидкость (вода, лава) или нет коллизии (трава/снег пропускаем дальше)
                    if (!state.getFluidState().isEmpty()) {
                        return -999; // Сверху вода или лава — отсеиваем сид
                    }
                    if (!state.getCollisionShape(centerChunk, pos).isEmpty() || state.is(BlockTags.LEAVES)) {
                        foundTopSolid = true;
                    }
                }

                if (state.is(BlockTags.LOGS)) {
                    foundLogY = y;
                    break;
                }
            }

            return foundTopSolid ? foundLogY : -999;
        } catch (Exception e) {
            AdvancementBorder.LOGGER.warn("[TreeCheck] Ошибка генерации чанка (0,0) для сида {}: {}", seed, e.toString());
            return -999;
        }
    }

    private static boolean checkRareBiomesFast(BiomeResolver resolver) {
        Set<ResourceKey<Biome>> missing = new HashSet<>(ScanCriteria.RARE_OVERWORLD_BIOMES);
        int maxR = ScanCriteria.BIOME_MAX_RADIUS;
        int maxRSq = maxR * maxR;
        int fastStep = 96;

        for (int x = -maxR; x <= maxR; x += fastStep) {
            for (int z = -maxR; z <= maxR; z += fastStep) {
                if (x * x + z * z > maxRSq) continue;
                Holder<Biome> biome = resolver.getNoiseBiome(x >> 2, 16, z >> 2);
                biome.unwrapKey().ifPresent(missing::remove);
                if (missing.isEmpty()) return true;
            }
        }
        return missing.isEmpty();
    }

    // Сверхлёгкий расчёт координат RandomSpread-структур БЕЗ выделения памяти в createState
    private static BlockPos findRandomSpreadFast(
            HolderLookup.RegistryLookup<net.minecraft.world.level.levelgen.structure.StructureSet> setLookup,
            BiomeResolver biomeResolver,
            Holder.Reference<Structure> structure,
            long seed,
            int maxRadiusBlocks
    ) {
        List<RandomSpreadStructurePlacement> placements = PLACEMENT_CACHE.get(structure);
        if (placements == null || placements.isEmpty()) return null;

        HolderSet<Biome> validBiomes = structure.value().biomes();
        BlockPos bestPos = null;
        double bestDistSq = (double) maxRadiusBlocks * maxRadiusBlocks;

        for (RandomSpreadStructurePlacement spread : placements) {
            int spacing = spread.spacing();
            int maxRegion = (maxRadiusBlocks / (spacing * 16)) + 1;
            for (int rx = -maxRegion; rx <= maxRegion; rx++) {
                for (int rz = -maxRegion; rz <= maxRegion; rz++) {
                    ChunkPos candidate = spread.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    BlockPos pos = candidate.getMiddleBlockPosition(64);
                    double dSq = pos.distSqr(BlockPos.ZERO);
                    if (dSq <= bestDistSq) {
                        Holder<Biome> biomeSurface = biomeResolver.getNoiseBiome(pos.getX() >> 2, 16, pos.getZ() >> 2);
                        Holder<Biome> biomeCave = biomeResolver.getNoiseBiome(pos.getX() >> 2, -8, pos.getZ() >> 2);
                        if (validBiomes.contains(biomeSurface) || validBiomes.contains(biomeCave)) {
                            bestDistSq = dSq;
                            bestPos = pos;
                        }
                    }
                }
            }
        }
        return bestPos;
    }


    private static List<BlockPos> findAllRandomSpreadFast(
            HolderLookup.RegistryLookup<net.minecraft.world.level.levelgen.structure.StructureSet> setLookup,
            BiomeResolver biomeResolver,
            Holder.Reference<Structure> structure,
            long seed,
            int maxRadiusBlocks
    ) {
        List<BlockPos> results = new ArrayList<>();
        List<RandomSpreadStructurePlacement> placements = PLACEMENT_CACHE.get(structure);
        if (placements == null || placements.isEmpty()) return results;

        HolderSet<Biome> validBiomes = structure.value().biomes();
        double maxDistSq = (double) maxRadiusBlocks * maxRadiusBlocks;

        for (RandomSpreadStructurePlacement spread : placements) {
            int spacing = spread.spacing();
            int maxRegion = (maxRadiusBlocks / (spacing * 16)) + 1;

            for (int rx = -maxRegion; rx <= maxRegion; rx++) {
                for (int rz = -maxRegion; rz <= maxRegion; rz++) {
                    ChunkPos candidate = spread.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    BlockPos pos = candidate.getMiddleBlockPosition(64);
                    if (pos.distSqr(BlockPos.ZERO) <= maxDistSq) {
                        Holder<Biome> biomeSurface = biomeResolver.getNoiseBiome(pos.getX() >> 2, 16, pos.getZ() >> 2);
                        Holder<Biome> biomeCave = biomeResolver.getNoiseBiome(pos.getX() >> 2, -8, pos.getZ() >> 2);
                        if (validBiomes.contains(biomeSurface) || validBiomes.contains(biomeCave)) {
                            results.add(pos);
                        }
                    }
                }
            }
        }
        return results;
    }



    private static BlockPos findNearestStructurePos(ChunkGeneratorStructureState structState, BiomeResolver biomeResolver, Holder.Reference<Structure> structure, int maxRadiusBlocks) {
        if (structure == null) return null;
        List<StructurePlacement> placements = structState.getPlacementsForStructure(structure);

        BlockPos bestPos = null;
        double bestDistSq = (double) maxRadiusBlocks * maxRadiusBlocks;
        for (StructurePlacement placement : placements) {
            if (placement instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> ringChunks = structState.getRingPositionsFor(rings);
                if (ringChunks != null) {
                    for (ChunkPos cp : ringChunks) {
                        BlockPos pos = cp.getMiddleBlockPosition(64);
                        double dSq = pos.distSqr(BlockPos.ZERO);
                        if (dSq <= bestDistSq) {
                            bestDistSq = dSq;
                            bestPos = pos;
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    private static boolean checkBiomes(BiomeResolver resolver, RegistryAccess registries, List<ResourceKey<Biome>> requiredList, boolean isOverworld) {
        Set<ResourceKey<Biome>> missing = new HashSet<>(requiredList);
        if (isOverworld) {
            ResourceKey<Biome> dappled = ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace("dappled_forest"));
            if (registries.lookupOrThrow(Registries.BIOME).containsKey(dappled)) {
                missing.add(dappled);
            }
        }

        int maxR = isOverworld ? ScanCriteria.BIOME_MAX_RADIUS : ScanCriteria.NETHER_BIOME_MAX_RADIUS;
        int maxRSq = maxR * maxR;
        int step = ScanCriteria.BIOME_STEP;

        for (int x = -maxR; x <= maxR; x += step) {
            for (int z = -maxR; z <= maxR; z += step) {
                if (x * x + z * z > maxRSq) continue;
                Holder<Biome> biome = resolver.getNoiseBiome(x >> 2, 16, z >> 2);
                biome.unwrapKey().ifPresent(missing::remove);
                if (missing.isEmpty()) return true;
            }
        }

        if (isOverworld && !missing.isEmpty()) {
            for (int x = -maxR; x <= maxR; x += step) {
                for (int z = -maxR; z <= maxR; z += step) {
                    if (x * x + z * z > maxRSq) continue;
                    Holder<Biome> caveBiome = resolver.getNoiseBiome(x >> 2, -8, z >> 2);
                    caveBiome.unwrapKey().ifPresent(missing::remove);
                    if (missing.isEmpty()) return true;
                }
            }
        }

        return missing.isEmpty();
    }

    private static String posToStr(BlockPos pos) {
        return pos == null ? "N/A" : pos.getX() + ", " + pos.getZ();
    }

    private static synchronized void saveResultsToFile() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();

            Path mainTarget = Path.of("found_seeds.json");
            Path mainTemp = Path.of("found_seeds.json.tmp");
            try (FileWriter writer = new FileWriter(mainTemp.toFile())) {
                gson.toJson(new ArrayList<>(FOUND_SEEDS), writer);
            }
            Files.move(mainTemp, mainTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            Path treeTarget = Path.of("found_seeds_perfect_tree.json");
            Path treeTemp = Path.of("found_seeds_perfect_tree.json.tmp");
            try (FileWriter writer = new FileWriter(treeTemp.toFile())) {
                gson.toJson(new ArrayList<>(PERFECT_TREE_SEEDS), writer);
            }
            Files.move(treeTemp, treeTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            AdvancementBorder.LOGGER.error("Не удалось сохранить файлы сидов", e);
        }
    }

    private static void broadcast(MinecraftServer server, String msg) {
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false));
    }

    private static String inspectCriticalLootReport(
            MinecraftServer server,
            ServerLevel overworld,
            ServerLevel nether,
            NoiseBasedChunkGenerator owNoiseGen,
            NoiseBasedChunkGenerator netherNoiseGen,
            RandomState owRandom,
            RandomState netherRandom,
            Map<ResourceKey<Structure>, BlockPos> foundOw,
            Map<ResourceKey<Structure>, BlockPos> foundNether,
            long seed
    ) {
        List<String> missing = new ArrayList<>();
        var reloadableRegistries = server.reloadableRegistries();
        var structureLookup = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var setLookup = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);

        BiomeResolver owResolver = owNoiseGen.getBiomeSource().createCachingResolver(owRandom);
        BiomeResolver netherResolver = netherNoiseGen.getBiomeSource().createCachingResolver(netherRandom);

        var owLootParams = new net.minecraft.world.level.storage.loot.LootParams.Builder(overworld)
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.EMPTY);
        var netherLootParams = new net.minecraft.world.level.storage.loot.LootParams.Builder(nether)
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.EMPTY);

        // 1. Все Дворцы испытаний в радиусе -> суммарно >= 2 зловещих хранилища
        var trialOpt = structureLookup.get(BuiltinStructures.TRIAL_CHAMBERS);
        if (trialOpt.isPresent()) {
            int radius = getEffectiveSearchRadius(BuiltinStructures.TRIAL_CHAMBERS, foundOw.get(BuiltinStructures.TRIAL_CHAMBERS), 1050);
            List<BlockPos> allTrials = findAllRandomSpreadFast(setLookup, owResolver, trialOpt.get(), seed, radius);
            int totalOminousVaults = 0;
            for (BlockPos pos : allTrials) {
                var res = StructureLootScanner.inspectStructure(server, overworld, owNoiseGen, owRandom, BuiltinStructures.TRIAL_CHAMBERS, pos, seed);
                if (res.isValidStructure()) {
                    totalOminousVaults += res.ominousVaults();
                }
                if (totalOminousVaults >= 2) break;
            }
            if (totalOminousVaults < 2) missing.add("Зловещие хранилища (<2)");
        }

        // 2. Древние города -> Зачарованное яблоко + отделки "Тишина" и "Хранитель"
        var cityOpt = structureLookup.get(BuiltinStructures.ANCIENT_CITY);
        if (cityOpt.isPresent()) {
            int radius = getEffectiveSearchRadius(BuiltinStructures.ANCIENT_CITY, foundOw.get(BuiltinStructures.ANCIENT_CITY), 1850);
            List<BlockPos> allCities = findAllRandomSpreadFast(setLookup, owResolver, cityOpt.get(), seed, radius);
            boolean hasApple = false;
            boolean hasSilence = false;
            boolean hasWard = false;
            for (BlockPos pos : allCities) {
                var res = StructureLootScanner.inspectStructure(server, overworld, owNoiseGen, owRandom, BuiltinStructures.ANCIENT_CITY, pos, seed);
                if (res.isValidStructure()) {
                    for (var entry : res.generatedLootTables()) {
                        var table = reloadableRegistries.getLootTable((ResourceKey) entry.lootTableKey());
                        for (var stack : table.getRandomItems(owLootParams, entry.lootSeed())) {
                            if (stack.is(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE)) hasApple = true;
                            if (stack.is(net.minecraft.world.item.Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE)) hasSilence = true;
                            if (stack.is(net.minecraft.world.item.Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE)) hasWard = true;
                        }
                    }
                }
                if (hasApple && hasSilence && hasWard) break;
            }
            if (!hasApple) missing.add("Зачар. яблоко");
            if (!hasSilence) missing.add("Отделка Тишина");
            if (!hasWard) missing.add("Отделка Хранитель");
        }

        // 3. Бастионы -> Незеритовый шаблон + отделка "Рыло"
        var bastionOpt = structureLookup.get(BuiltinStructures.BASTION_REMNANT);
        if (bastionOpt.isPresent()) {
            int radius = getEffectiveNetherRadius(BuiltinStructures.BASTION_REMNANT, foundNether.get(BuiltinStructures.BASTION_REMNANT), 2000);
            List<BlockPos> allBastions = findAllRandomSpreadFast(setLookup, netherResolver, bastionOpt.get(), seed, radius);
            boolean hasTemplate = false;
            boolean hasSnout = false;
            for (BlockPos pos : allBastions) {
                var res = StructureLootScanner.inspectStructure(server, nether, netherNoiseGen, netherRandom, BuiltinStructures.BASTION_REMNANT, pos, seed);
                if (res.isValidStructure()) {
                    for (var entry : res.generatedLootTables()) {
                        var table = reloadableRegistries.getLootTable((ResourceKey) entry.lootTableKey());
                        for (var stack : table.getRandomItems(netherLootParams, entry.lootSeed())) {
                            if (stack.is(net.minecraft.world.item.Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)) hasTemplate = true;
                            if (stack.is(net.minecraft.world.item.Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE)) hasSnout = true;
                        }
                    }
                }
                if (hasTemplate && hasSnout) break;
            }
            if (!hasTemplate) missing.add("Незеритовый шаблон");
            if (!hasSnout) missing.add("Отделка Рыло");
        }

        // 4. Крепости Незера -> отделка "Ребро"
        var fortressOpt = structureLookup.get(BuiltinStructures.FORTRESS);
        if (fortressOpt.isPresent()) {
            int radius = getEffectiveNetherRadius(BuiltinStructures.FORTRESS, foundNether.get(BuiltinStructures.FORTRESS), 2000);
            List<BlockPos> allFortresses = findAllRandomSpreadFast(setLookup, netherResolver, fortressOpt.get(), seed, radius);
            boolean hasRib = false;
            for (BlockPos pos : allFortresses) {
                var res = StructureLootScanner.inspectStructure(server, nether, netherNoiseGen, netherRandom, BuiltinStructures.FORTRESS, pos, seed);
                if (res.isValidStructure()) {
                    for (var entry : res.generatedLootTables()) {
                        var table = reloadableRegistries.getLootTable((ResourceKey) entry.lootTableKey());
                        for (var stack : table.getRandomItems(netherLootParams, entry.lootSeed())) {
                            if (stack.is(net.minecraft.world.item.Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE)) {
                                hasRib = true;
                                break;
                            }
                        }
                        if (hasRib) break;
                    }
                }
                if (hasRib) break;
            }
            if (!hasRib) missing.add("Отделка Ребро");
        }

        // 5. Лесные особняки -> реальная генерация + отделка "Векс"
        var mansionOpt = structureLookup.get(BuiltinStructures.WOODLAND_MANSION);
        if (mansionOpt.isPresent()) {
            int radius = getEffectiveSearchRadius(BuiltinStructures.WOODLAND_MANSION, foundOw.get(BuiltinStructures.WOODLAND_MANSION), 1950);
            List<BlockPos> allMansions = findAllRandomSpreadFast(setLookup, owResolver, mansionOpt.get(), seed, radius);
            boolean hasVex = false;
            for (BlockPos pos : allMansions) {
                var res = StructureLootScanner.inspectStructure(server, overworld, owNoiseGen, owRandom, BuiltinStructures.WOODLAND_MANSION, pos, seed);
                if (res.isValidStructure()) {
                    for (var entry : res.generatedLootTables()) {
                        var table = reloadableRegistries.getLootTable((ResourceKey) entry.lootTableKey());
                        for (var stack : table.getRandomItems(owLootParams, entry.lootSeed())) {
                            if (stack.is(net.minecraft.world.item.Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE)) {
                                hasVex = true;
                                break;
                            }
                        }
                        if (hasVex) break;
                    }
                }
                if (hasVex) break;
            }
            if (!hasVex) missing.add("Отделка Векс (или Особняк)");
        }

        // 6. Руины троп -> отделка "Следопыт"
        var trailRuinsOpt = structureLookup.get(BuiltinStructures.TRAIL_RUINS);
        if (trailRuinsOpt.isPresent()) {
            int radius = getEffectiveSearchRadius(BuiltinStructures.TRAIL_RUINS, foundOw.get(BuiltinStructures.TRAIL_RUINS), 1500);
            List<BlockPos> allTrailRuins = findAllRandomSpreadFast(setLookup, owResolver, trailRuinsOpt.get(), seed, radius);
            boolean hasWayfinder = false;
            for (BlockPos pos : allTrailRuins) {
                var res = StructureLootScanner.inspectStructure(server, overworld, owNoiseGen, owRandom, BuiltinStructures.TRAIL_RUINS, pos, seed);
                if (res.isValidStructure()) {
                    for (var entry : res.generatedLootTables()) {
                        var table = reloadableRegistries.getLootTable((ResourceKey) entry.lootTableKey());for (var stack : table.getRandomItems(owLootParams, entry.lootSeed())) {
                            if (stack.is(net.minecraft.world.item.Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE)) {
                                hasWayfinder = true;
                                break;
                            }
                        }
                        if (hasWayfinder) break;
                    }
                }
                if (hasWayfinder) break;
            }
            if (!hasWayfinder) missing.add("Отделка Следопыт");
        }
// 7. Тёплые подводные руины -> >= 2 яйца нюхача
        var warmRuinOpt = structureLookup.get(BuiltinStructures.OCEAN_RUIN_WARM);
        if (warmRuinOpt.isPresent()) {
            int radius = getEffectiveSearchRadius(BuiltinStructures.OCEAN_RUIN_WARM, foundOw.get(BuiltinStructures.OCEAN_RUIN_WARM), 2000);
            List<BlockPos> allRuins = findAllRandomSpreadFast(setLookup, owResolver, warmRuinOpt.get(), seed, radius);
            int snifferEggs = 0;
            int totalBrushable = 0;
            for (BlockPos pos : allRuins) {
                var res = StructureLootScanner.inspectStructure(server, overworld, owNoiseGen, owRandom, BuiltinStructures.OCEAN_RUIN_WARM, pos, seed);
                if (res.isValidStructure()) {
                    totalBrushable += res.generatedLootTables().size();
                    for (var entry : res.generatedLootTables()) {
                        var table = reloadableRegistries.getLootTable((ResourceKey) entry.lootTableKey());
                        for (var stack : table.getRandomItems(owLootParams, entry.lootSeed())) {
                            if (stack.is(net.minecraft.world.item.Items.SNIFFER_EGG)) {
                                snifferEggs += stack.getCount();
                            }
                        }
                    }
                }
            }
            if (snifferEggs < 2) {
                missing.add("Яйца нюхача (" + snifferEggs + "/2, песка: " + totalBrushable + ", руин: " + allRuins.size() + ")");
            }
        }
// 8. Города Энда -> наличие корабля с элитрами + отделка "Шпиль"
        ServerLevel endLevel = server.getLevel(ServerLevel.END);
        var endCityOpt = structureLookup.get(BuiltinStructures.END_CITY);
        if (endLevel != null && endCityOpt.isPresent() && endLevel.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator endNoiseGen) {
            RandomState endRandom = RandomState.create(server.registryAccess().lookupOrThrow(Registries.NOISE), seed, endNoiseGen.generatorSettings().value());
            BiomeResolver endResolver = endNoiseGen.getBiomeSource().createCachingResolver(endRandom);
            var endLootParams = new net.minecraft.world.level.storage.loot.LootParams.Builder(endLevel)
                    .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.EMPTY);
            List<BlockPos> allEndCities = findAllRandomSpreadFast(setLookup, endResolver, endCityOpt.get(), seed, ScanCriteria.END_CITY_MAX_RADIUS);
            boolean hasShip = false;
            boolean hasSpire = false;
            for (BlockPos pos : allEndCities) {
                var res = StructureLootScanner.inspectStructure(server, endLevel, endNoiseGen, endRandom, BuiltinStructures.END_CITY, pos, seed);
                if (res.isValidStructure()) {
                    if (res.hasEndShip()) hasShip = true;
                    for (var entry : res.generatedLootTables()) {
                        var table = reloadableRegistries.getLootTable((ResourceKey) entry.lootTableKey());
                        for (var stack : table.getRandomItems(endLootParams, entry.lootSeed())) {
                            if (stack.is(net.minecraft.world.item.Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE)) {
                                hasSpire = true;
                            }
                        }
                    }
                }
                if (hasShip && hasSpire) break;
            }
            if (!hasShip) missing.add("Корабль Энда (Элитры)");
            if (!hasSpire) missing.add("Отделка Шпиль");
        }
        return missing.isEmpty()
                ? "Лут: ИДЕАЛЬНЫЙ (всё найдено)"
                : "Лут: не хватает " + missing;
    }

    public static void testSingleSeedNow(MinecraftServer server, long testSeed) {
        ServerLevel overworld = server.overworld();
        ServerLevel nether = server.getLevel(ServerLevel.NETHER);
        RegistryAccess registries = server.registryAccess();
        buildPlacementCache(registries);

        ChunkGenerator owGen = overworld.getChunkSource().getGenerator();
        ChunkGenerator netherGen = nether.getChunkSource().getGenerator();
        if (!(owGen instanceof NoiseBasedChunkGenerator owNoiseGen) || !(netherGen instanceof NoiseBasedChunkGenerator netherNoiseGen)) {
            return;
        }

        RandomState owRandom = RandomState.create(registries.lookupOrThrow(Registries.NOISE), testSeed, owNoiseGen.generatorSettings().value());
        RandomState netherRandom = RandomState.create(registries.lookupOrThrow(Registries.NOISE), testSeed, netherNoiseGen.generatorSettings().value());

        BiomeResolver owResolver = owGen.getBiomeSource().createCachingResolver(owRandom);BiomeResolver netherResolver = netherGen.getBiomeSource().createCachingResolver(netherRandom);
        var structureLookup = registries.lookupOrThrow(Registries.STRUCTURE);
        var structureSetLookup = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        Map<ResourceKey<Structure>, BlockPos> foundOw = new HashMap<>();
        Map<ResourceKey<Structure>, BlockPos> foundNether = new HashMap<>();

        for (var key : List.of(BuiltinStructures.TRIAL_CHAMBERS, BuiltinStructures.ANCIENT_CITY, BuiltinStructures.OCEAN_RUIN_WARM)) {
            int maxDist = ScanCriteria.OVERWORLD_STRUCTURES.getOrDefault(key, 2000);
            structureLookup.get(key).ifPresent(holder -> {
                BlockPos pos = findRandomSpreadFast(structureSetLookup, owResolver, holder, testSeed, maxDist);
                if (pos != null) foundOw.put(key, pos);
            });
        }
        int bastionMaxDist = ScanCriteria.NETHER_STRUCTURES.getOrDefault(BuiltinStructures.BASTION_REMNANT, 2000);
        structureLookup.get(BuiltinStructures.BASTION_REMNANT).ifPresent(holder -> {
            BlockPos pos = findRandomSpreadFast(structureSetLookup, netherResolver, holder, testSeed, bastionMaxDist);
            if (pos != null) foundNether.put(BuiltinStructures.BASTION_REMNANT, pos);
        });
        int treeY = checkZeroStartCondition(server, overworld, owNoiseGen, owRandom, owResolver, testSeed);
        String treeInfo = (treeY != -999) ? "§aДерево на (0,0): Y=" + treeY : "§cДерева на (0,0) нет";
        String report = inspectCriticalLootReport(server, overworld, nether, owNoiseGen, netherNoiseGen, owRandom, netherRandom, foundOw, foundNether, testSeed);
        String coordsInfo = String.format(
                "Trial: [%s] | City: [%s] | Ruin: [%s] | Bastion: [%s]",
                posToStr(foundOw.get(BuiltinStructures.TRIAL_CHAMBERS)),
                posToStr(foundOw.get(BuiltinStructures.ANCIENT_CITY)),
                posToStr(foundOw.get(BuiltinStructures.OCEAN_RUIN_WARM)),
                posToStr(foundNether.get(BuiltinStructures.BASTION_REMNANT))
        );
        broadcast(server, "§e[Test Loot] Сид " + testSeed + " -> " + report + " §7| " + treeInfo);
        broadcast(server, "§7Координаты: " + coordsInfo);
        AdvancementBorder.LOGGER.info("[Test Loot] Сид {} -> {} | {} ({})", testSeed, report, treeInfo, coordsInfo);
    }
    public static void verifyExistingSeeds(MinecraftServer server) {
        try {
            Path path = Path.of("found_seeds.json");
            if (!Files.exists(path)) {
                broadcast(server, "§cФайл found_seeds.json не найден!");
                return;
            }

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            FoundSeedReport[] oldReports = gson.fromJson(Files.readString(path), FoundSeedReport[].class);
            if (oldReports == null || oldReports.length == 0) {
                broadcast(server, "§eСписок сидов в found_seeds.json пуст.");
                return;
            }

            Map<Long, FoundSeedReport> uniqueMap = new LinkedHashMap<>();
            for (FoundSeedReport r : oldReports) {
                uniqueMap.putIfAbsent(r.seed(), r);
            }

            ServerLevel overworld = server.overworld();
            ServerLevel nether = server.getLevel(ServerLevel.NETHER);
            RegistryAccess registries = server.registryAccess();
            buildPlacementCache(registries);

            if (!(overworld.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator owNoiseGen)
                    || !(nether.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator netherNoiseGen)) {
                broadcast(server, "§cГенератор мира не является NoiseBasedChunkGenerator!");
                return;
            }

            var structureLookup = registries.lookupOrThrow(Registries.STRUCTURE);
            var setLookup = registries.lookupOrThrow(Registries.STRUCTURE_SET);

            List<FoundSeedReport> allVerified = new ArrayList<>();
            List<FoundSeedReport> onlyIdeal = new ArrayList<>();
            Map<String, Integer> verifyFails = new LinkedHashMap<>();

            for (FoundSeedReport old : uniqueMap.values()) {
                long s = old.seed();
                RandomState owRandom = RandomState.create(registries.lookupOrThrow(Registries.NOISE), s, owNoiseGen.generatorSettings().value());
                RandomState netherRandom = RandomState.create(registries.lookupOrThrow(Registries.NOISE), s, netherNoiseGen.generatorSettings().value());

                BiomeResolver owResolver = owNoiseGen.getBiomeSource().createCachingResolver(owRandom);
                BiomeResolver netherResolver = netherNoiseGen.getBiomeSource().createCachingResolver(netherRandom);

                Map<ResourceKey<Structure>, BlockPos> foundOw = new HashMap<>();
                Map<ResourceKey<Structure>, BlockPos> foundNether = new HashMap<>();

                for (var key : List.of(BuiltinStructures.TRIAL_CHAMBERS, BuiltinStructures.ANCIENT_CITY, BuiltinStructures.OCEAN_RUIN_WARM)) {
                    int maxDist = ScanCriteria.OVERWORLD_STRUCTURES.getOrDefault(key, 2000);
                    structureLookup.get(key).ifPresent(holder -> {
                        BlockPos pos = findRandomSpreadFast(setLookup, owResolver, holder, s, maxDist);
                        if (pos != null) foundOw.put(key, pos);
                    });
                }

                int bastionMaxDist = ScanCriteria.NETHER_STRUCTURES.getOrDefault(BuiltinStructures.BASTION_REMNANT, 2000);
                structureLookup.get(BuiltinStructures.BASTION_REMNANT).ifPresent(holder -> {
                    BlockPos pos = findRandomSpreadFast(setLookup, netherResolver, holder, s, bastionMaxDist);
                    if (pos != null) foundNether.put(BuiltinStructures.BASTION_REMNANT, pos);
                });

                int zeroCheck = checkZeroStartCondition(server, overworld, owNoiseGen, owRandom, owResolver, s);
                boolean validZero = (zeroCheck != -999);
                String treeStatus;
                if (!validZero) {
                    treeStatus = "Вода/лава на (0,0)";
                    verifyFails.merge("Вода/лава на (0,0)", 1, Integer::sum);
                } else if (zeroCheck != -100) {
                    treeStatus = "Природное бревно на (0,0) (Y=" + zeroCheck + ")";
                } else {
                    treeStatus = "Твёрдая суша на (0,0) (дерево поставит мод)";
                }

                String lootStatus = inspectCriticalLootReport(
                        server, overworld, nether, owNoiseGen, netherNoiseGen,
                        owRandom, netherRandom, foundOw, foundNether, s
                );

                // Разбираем причины нехватки лута по отдельным пунктам
                if (!lootStatus.startsWith("Лут: ИДЕАЛЬНЫЙ")) {
                    String rawMissing = lootStatus.replace("Лут: не хватает [", "").replace("]", "");
                    for (String reason : rawMissing.split(", ")) {
                        // Группируем яйца нюхача в одну категорию без счётчика
                        String cleanReason = reason.startsWith("Яйца нюхача") ? "Яйца нюхача (<2)" : reason;
                        verifyFails.merge(cleanReason, 1, Integer::sum);
                    }
                }

                FoundSeedReport updated = new FoundSeedReport(
                        s,
                        old.strongholdPos(),
                        old.warmRuinPos(),
                        old.ancientCityPos(),
                        old.mansionPos(),
                        old.netherFortressPos(),
                        old.bastionPos(),
                        treeStatus + " | " + lootStatus
                );

                allVerified.add(updated);
                if (validZero && lootStatus.startsWith("Лут: ИДЕАЛЬНЫЙ")) {
                    onlyIdeal.add(updated);
                }
            }

            Files.writeString(Path.of("verified_all_seeds.json"), gson.toJson(allVerified));
            Files.writeString(Path.of("verified_ideal_seeds.json"), gson.toJson(onlyIdeal));

            String failSummary = verifyFails.isEmpty() ? "нет" : verifyFails.toString();
            AdvancementBorder.LOGGER.info("[Verify] Проверено: {} | Идеальных: {} | Отсев: {}", allVerified.size(), onlyIdeal.size(), failSummary);

            broadcast(server, "§a[Verify] Готово! Уникальных сидов: §e" + allVerified.size()
                    + "§a, идеальных: §b" + onlyIdeal.size()
                    + "§a. Сохранено в verified_ideal_seeds.json!");
            broadcast(server, "§e[Verify Отсев]: §7" + failSummary);
        } catch (Exception e) {
            AdvancementBorder.LOGGER.error("Ошибка при перепроверке found_seeds.json", e);
        }
    }

    private static int getEffectiveSearchRadius(ResourceKey<Structure> key, BlockPos fallbackPos, int defaultVal) {
        int base = ScanCriteria.OVERWORLD_STRUCTURES.getOrDefault(key, defaultVal);
        if (fallbackPos == null) return base;
        int dist = (int) Math.sqrt(fallbackPos.distSqr(BlockPos.ZERO)) + 50;
        return Math.max(base, dist);
    }

    private static int getEffectiveNetherRadius(ResourceKey<Structure> key, BlockPos fallbackPos, int defaultVal) {
        int base = ScanCriteria.NETHER_STRUCTURES.getOrDefault(key, defaultVal);
        if (fallbackPos == null) return base;
        int dist = (int) Math.sqrt(fallbackPos.distSqr(BlockPos.ZERO)) + 50;
        return Math.max(base, dist);
    }


    private static synchronized void loadExistingResultsFromFiles() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Path path = Path.of("found_seeds.json");
            if (Files.exists(path)) {
                FoundSeedReport[] loaded = gson.fromJson(Files.readString(path), FoundSeedReport[].class);
                if (loaded != null) {
                    Set<Long> existingSeeds = new HashSet<>();
                    for (FoundSeedReport r : FOUND_SEEDS) existingSeeds.add(r.seed());
                    for (FoundSeedReport r : loaded) {
                        if (existingSeeds.add(r.seed())) {
                            FOUND_SEEDS.add(r);
                        }
                    }
                }
            }
        } catch (Exception e) {
            AdvancementBorder.LOGGER.error("Не удалось загрузить существующий found_seeds.json", e);
        }

        try {
            Path treePath = Path.of("found_seeds_perfect_tree.json");
            if (Files.exists(treePath)) {
                FoundSeedReport[] loadedTree = gson.fromJson(Files.readString(treePath), FoundSeedReport[].class);
                if (loadedTree != null) {
                    Set<Long> existingTreeSeeds = new HashSet<>();
                    for (FoundSeedReport r : PERFECT_TREE_SEEDS) existingTreeSeeds.add(r.seed());
                    for (FoundSeedReport r : loadedTree) {
                        if (existingTreeSeeds.add(r.seed())) {
                            PERFECT_TREE_SEEDS.add(r);
                        }
                    }
                }
            }
        } catch (Exception e) {
            AdvancementBorder.LOGGER.error("Не удалось загрузить существующий found_seeds_perfect_tree.json", e);
        }
    }

}
