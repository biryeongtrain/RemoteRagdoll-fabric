package kr.gcb.dungeon.model;

import java.util.Map;
import java.util.function.Consumer;

import kr.gcb.ragdoll.compat.Location;
import kr.gcb.ragdoll.compat.Host;

import kr.gcb.dungeon.physics.PhysicsBody;

/**
 * Stand-in for Gazadry's Rise, which stands a corpse back up limb by limb. Nothing on Graywater island gets up again, so
 * a body asked to rise is simply taken away and whoever asked is told where it lay.
 */
final class Rise {
    private Rise() {
    }

    static void start(Host plugin, Map<String, PhysicsBody> parts, boolean slim, Location footing, float yaw, int ticks, int hold, Consumer<Location> then) {
        for (PhysicsBody part : parts.values()) if (part != null && !part.isDead()) part.kill();
        if (then != null) then.accept(footing);
    }
}
