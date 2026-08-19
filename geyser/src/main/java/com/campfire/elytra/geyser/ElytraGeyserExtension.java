package com.campfire.elytra.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.cloudburstmc.math.vector.Vector3f;

/**
 * Intercept ArmorStand (elytra mount) spawn → set SEAT_OFFSET + rotation
 * để Bedrock player thấy mình nằm ngang khi cưỡi mount.
 */
public class ElytraGeyserExtension implements Extension {

    @Subscribe
    public void onPostInit(GeyserPostInitializeEvent event) {
        logger().info("[CampfireElytra] Geyser extension loaded.");
    }

    @Subscribe
    public void onSpawnEntity(ServerSpawnEntityEvent event) {
        GeyserEntity entity = event.entity();
        if (entity == null) return;

        // Chỉ xử lý ArmorStand
        String type = entity.definition() != null ? entity.definition().toString() : "";
        if (!type.contains("armor_stand")) return;

        // Set seat data để player nằm xuống khi cưỡi
        event.preSpawnConsumer(e -> {
            // Ẩn entity (scale cực nhỏ)
            e.override(GeyserEntityDataTypes.SCALE, 0.001f);
            // Seat offset: đẩy player xuống để nằm
            e.override(GeyserEntityDataTypes.SEAT_OFFSET, Vector3f.from(0f, -0.8f, 0f));
            // Lock rotation theo vehicle
            e.override(GeyserEntityDataTypes.ROTATION_LOCKED_TO_VEHICLE, true);
            // Rotate rider 90 độ → nằm ngang
            e.override(GeyserEntityDataTypes.ROTATE_RIDER_DEGREES, 90f);
            // Không có hitbox
            e.override(GeyserEntityDataTypes.HITBOXES, java.util.List.of());

            logger().info("[CampfireElytra] Applied elytra mount data to " + e.entityUuid());
        });
    }
}
