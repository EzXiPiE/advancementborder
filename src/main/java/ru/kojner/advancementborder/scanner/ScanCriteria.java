package ru.kojner.advancementborder.scanner;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScanCriteria {

    // Шаг сетки проверки биомов на поверхности
    public static final int BIOME_STEP = 64;

    // ТОП-6 самых редких биомов для мгновенного отсева (шаг 96 блоков)
    public static final List<ResourceKey<Biome>> RARE_OVERWORLD_BIOMES = List.of(
            Biomes.MUSHROOM_FIELDS,
            Biomes.PALE_GARDEN,
            Biomes.BADLANDS,
            Biomes.MANGROVE_SWAMP,
            Biomes.JUNGLE,
            Biomes.WARM_OCEAN
    );

    // Обязательные биомы Верхнего мира (все ключевые для "Время приключений" + редкие для мобов/растений)
    public static final List<ResourceKey<Biome>> REQUIRED_OVERWORLD_BIOMES = List.of(
            Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.SNOWY_PLAINS, Biomes.ICE_SPIKES,
            Biomes.DESERT, Biomes.SWAMP, Biomes.MANGROVE_SWAMP,
            Biomes.FOREST, Biomes.FLOWER_FOREST, Biomes.BIRCH_FOREST, Biomes.OLD_GROWTH_BIRCH_FOREST,
            Biomes.DARK_FOREST, Biomes.PALE_GARDEN,
            Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA, Biomes.TAIGA, Biomes.SNOWY_TAIGA,
            Biomes.SAVANNA, Biomes.SAVANNA_PLATEAU, Biomes.WINDSWEPT_SAVANNA,
            Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_GRAVELLY_HILLS, Biomes.WINDSWEPT_FOREST,
            Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE,
            Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS,
            Biomes.MEADOW, Biomes.CHERRY_GROVE, Biomes.GROVE, Biomes.SNOWY_SLOPES,
            Biomes.JAGGED_PEAKS, Biomes.FROZEN_PEAKS, Biomes.STONY_PEAKS,
            Biomes.RIVER, Biomes.FROZEN_RIVER,
            Biomes.BEACH, Biomes.SNOWY_BEACH, Biomes.STONY_SHORE,
            Biomes.WARM_OCEAN, Biomes.LUKEWARM_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN,
            Biomes.OCEAN, Biomes.DEEP_OCEAN, Biomes.COLD_OCEAN, Biomes.DEEP_COLD_OCEAN,
            Biomes.FROZEN_OCEAN, Biomes.DEEP_FROZEN_OCEAN,
            Biomes.MUSHROOM_FIELDS,
            Biomes.DRIPSTONE_CAVES, Biomes.LUSH_CAVES, Biomes.DEEP_DARK
    );

    // Все 5 биомов Нижнего мира (для ачивки "Горячий тур")
    public static final List<ResourceKey<Biome>> REQUIRED_NETHER_BIOMES = List.of(
            Biomes.NETHER_WASTES,
            Biomes.SOUL_SAND_VALLEY,
            Biomes.CRIMSON_FOREST,
            Biomes.WARPED_FOREST,
            Biomes.BASALT_DELTAS
    );

    // Кольца доступности структур Верхнего мира (макс. радиус 2000)
    public static final Map<ResourceKey<Structure>, Integer> OVERWORLD_STRUCTURES = new LinkedHashMap<>() {{
        // Кольцо 1: Старт (деревня проверяется отдельно по тегу всех деревень)
        put(BuiltinStructures.VILLAGE_PLAINS, 450);
        put(BuiltinStructures.RUINED_PORTAL_STANDARD, 450);

        // Кольцо 2: Середина игры
        put(BuiltinStructures.PILLAGER_OUTPOST, 1000);
        put(BuiltinStructures.TRIAL_CHAMBERS, 1050);
        put(BuiltinStructures.OCEAN_MONUMENT, 1100);
        put(BuiltinStructures.TRAIL_RUINS, 1500);

        // Кольцо 3: Поздняя игра (в пределах 2000 блоков)
        put(BuiltinStructures.ANCIENT_CITY, 1850);
        put(BuiltinStructures.STRONGHOLD, 1850);
        put(BuiltinStructures.WOODLAND_MANSION, 1950);
        put(BuiltinStructures.OCEAN_RUIN_WARM, 2000);
    }};

    public static final int BIOME_MAX_RADIUS = 2000;
    public static final int NETHER_BIOME_MAX_RADIUS = 2000;
    public static final int END_CITY_MAX_RADIUS = 2000;

    // Структуры Нижнего мира (граница 1:1, до 2000 блоков)
    public static final Map<ResourceKey<Structure>, Integer> NETHER_STRUCTURES = new LinkedHashMap<>() {{
        put(BuiltinStructures.FORTRESS, 2000);
        put(BuiltinStructures.BASTION_REMNANT, 2000);
    }};
}
