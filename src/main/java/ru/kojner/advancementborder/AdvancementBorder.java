package ru.kojner.advancementborder;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(AdvancementBorder.MODID)
public class AdvancementBorder {
    public static final String MODID = "advancementborder";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AdvancementBorder(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("AdvancementBorder initialized!");
    }
}
