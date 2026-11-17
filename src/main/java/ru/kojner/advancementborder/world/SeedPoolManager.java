package ru.kojner.advancementborder.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import ru.kojner.advancementborder.AdvancementBorder;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class SeedPoolManager {
    private static final List<Long> SEEDS = new ArrayList<>();
    private static boolean loaded = false;
    private static Long lastPickedSeed = null;

    public static void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        SEEDS.clear();

        try (InputStream stream = SeedPoolManager.class.getResourceAsStream("/data/advancementborder/seeds.json")) {
            if (stream == null) {
                AdvancementBorder.LOGGER.warn("Файл seeds.json не найден в ресурсах мода!");
                return;
            }
            JsonElement root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (root.isJsonArray()) {
                JsonArray array = root.getAsJsonArray();
                for (JsonElement el : array) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                        SEEDS.add(el.getAsLong());
                    } else if (el.isJsonObject() && el.getAsJsonObject().has("seed")) {
                        SEEDS.add(el.getAsJsonObject().get("seed").getAsLong());
                    }
                }
            }
            AdvancementBorder.LOGGER.info("Загружено проходимых сидов из базы: {}", SEEDS.size());
        } catch (Exception e) {
            AdvancementBorder.LOGGER.error("Ошибка чтения seeds.json", e);
        }
    }

    public static long pickNextSeed() {
        loadIfNeeded();
        if (SEEDS.isEmpty()) {
            return ThreadLocalRandom.current().nextLong();
        }
        if (SEEDS.size() == 1) {
            lastPickedSeed = SEEDS.getFirst();
            return lastPickedSeed;
        }
        long candidate;
        do {
            candidate = SEEDS.get(ThreadLocalRandom.current().nextInt(SEEDS.size()));
        } while (lastPickedSeed != null && candidate == lastPickedSeed);

        lastPickedSeed = candidate;
        return candidate;
    }
}
