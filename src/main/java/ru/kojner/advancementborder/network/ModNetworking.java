package ru.kojner.advancementborder.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import ru.kojner.advancementborder.AdvancementBorder;

@EventBusSubscriber(modid = AdvancementBorder.MODID)
public class ModNetworking {

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(
                SyncChallengeHudPayload.TYPE,
                SyncChallengeHudPayload.STREAM_CODEC,
                SyncChallengeHudPayload::handleOnClient
        );
    }
}
