package kr.gcb.ragdoll.api;

import kr.gcb.ragdoll.RemoteRagdollFabric;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The whole of what another mod needs: drop a body, find out where it has got to, take it away.
 *
 * <p>Handles are opaque. Every method is safe to call when ragdolls are off (BetterModel or the Jolt
 * native missing) - {@link #drop} then returns null and the caller falls back to whatever it did before.
 * Server thread only.
 */
public final class Ragdolls {

    private Ragdolls() {}

    /** Whether a body can be made at all on this server. */
    public static boolean ready() { return RemoteRagdollFabric.apiReady(); }

    /**
     * Drops a body that looks like {@code whose} (skin and armour as worn right now) with its feet at {@code at}.
     *
     * @param push  speed it is thrown at, blocks a second; null for none
     * @param ticks how long it lies there before it is taken away by itself
     * @return a handle, or null if it could not be made
     */
    public static Object drop(ServerPlayer whose, ServerLevel level, Vec3 at, float yaw, Vec3 push, int ticks) {
        return RemoteRagdollFabric.apiDrop(whose, level, at, yaw, push, ticks);
    }

    /**
     * The same, optionally torn apart: with {@code torn} no joint holds and the six parts scatter.
     */
    public static Object drop(ServerPlayer whose, ServerLevel level, Vec3 at, float yaw, Vec3 push, int ticks, boolean torn) {
        return RemoteRagdollFabric.apiDrop(whose, level, at, yaw, push, ticks, torn);
    }

    /** Where the body is now (its middle), or null once it is gone. */
    public static Vec3 middle(Object handle) { return RemoteRagdollFabric.apiMiddle(handle); }

    /** Takes the body away now. Safe on null and on one already gone. */
    public static void remove(Object handle) { RemoteRagdollFabric.apiRemove(handle); }
}
