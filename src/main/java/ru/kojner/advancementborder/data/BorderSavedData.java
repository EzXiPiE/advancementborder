package ru.kojner.advancementborder.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import ru.kojner.advancementborder.AdvancementBorder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BorderSavedData extends SavedData {
    private boolean challengeActive;
    private int currentRadius;
    private final Set<Identifier> completedAdvancements;
    private boolean starterTreeGenerated;

    public static final Codec<BorderSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("challengeActive", false).forGetter(d -> d.challengeActive),
            Codec.INT.optionalFieldOf("currentRadius", 0).forGetter(d -> d.currentRadius),
            Identifier.CODEC.listOf().optionalFieldOf("completedAdvancements", List.of())
                    .forGetter(d -> new ArrayList<>(d.completedAdvancements)),
            Codec.BOOL.optionalFieldOf("starterTreeGenerated", false).forGetter(d -> d.starterTreeGenerated)
    ).apply(instance, BorderSavedData::new));

    public static final SavedDataType<BorderSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(AdvancementBorder.MODID, "border_data"),
            BorderSavedData::new,
            CODEC
    );

    public BorderSavedData() {
        this(false, 0, List.of(), false);
    }

    public BorderSavedData(boolean challengeActive, int currentRadius, List<Identifier> completedList, boolean starterTreeGenerated) {
        this.challengeActive = challengeActive;
        this.currentRadius = currentRadius;
        this.completedAdvancements = new HashSet<>(completedList);
        this.starterTreeGenerated = starterTreeGenerated;
    }

    public static BorderSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public boolean isChallengeActive() {
        return challengeActive;
    }

    public void setChallengeActive(boolean active) {
        this.challengeActive = active;
        setDirty();
    }

    public int getCurrentRadius() {
        return currentRadius;
    }

    public double getBorderDiameter() {
        return 1.0 + (this.currentRadius * 2.0);
    }

    public void addRadius(int amount) {
        this.currentRadius += amount;
        setDirty();
    }

    public boolean markAdvancementCompleted(Identifier advancementId) {
        if (this.completedAdvancements.add(advancementId)) {
            setDirty();
            return true;
        }
        return false;
    }

    public int getCompletedCount() {
        return this.completedAdvancements.size();
    }

    public boolean isStarterTreeGenerated() {
        return starterTreeGenerated;
    }

    public void setStarterTreeGenerated(boolean generated) {
        this.starterTreeGenerated = generated;
        setDirty();
    }
}
