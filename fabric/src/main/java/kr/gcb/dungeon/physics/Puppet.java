/* Ported from TerritoryWar (io.lightest.tw.physics / .model), same author's project.
 * Changes: package, dungeon terrain collider instead of a flat ground plane, and a
 * ragdoll built from whole limbs rather than one body per bone. */
package kr.gcb.dungeon.physics;

import kr.gcb.ragdoll.compat.Location;
import org.joml.Quaternionf;

/**
 * Something a physics body drags around, without the physics knowing what it is.
 *
 * <p>A corpse used to be twelve real {@code ItemDisplay} entities. Real entities are the
 * server's problem in every way that matters: they take a slot in the world's entity list,
 * they are saved and loaded with chunks, and above all the entity tracker walks them for
 * every player every tick, working out who can see what and building a packet per change.
 * A busy battlefield with a dozen corpses on it is a hundred and fifty entities that exist
 * only to be looked at - and the tracker is the code that took the server down.
 *
 * <p>The other way to put a shape in front of somebody is to send them the packets for one
 * and never create anything. Nothing to track, nothing to save, nothing to tidy up if the
 * plugin dies. That is what a packet puppet is, and this interface is what lets the physics
 * drive either without caring which it has.
 */
public interface Puppet {

    /** Put it here, turned like this. Called once per body per physics tick. */
    void place(Location at, Quaternionf spin);

    /** It is finished with; take it off the screen. */
    void dispose();

    /** Whether it is still worth moving. */
    default boolean alive() { return true; }

    /**
     * Keeps this out of one person's sight, or gives it back.
     *
     * <p>Per viewer, because that is the only kind of hiding worth having here: the camera
     * inside a corpse's head has to not see the head, and everybody else has to keep seeing it.
     */
    default void blind(net.minecraft.server.level.ServerPlayer viewer, boolean hidden) { }

    /**
     * Shows it to nobody except the people this says yes to.
     *
     * <p>The other way round from {@link #blind}, and the difference is who a newcomer is. A
     * blind list is a list of the people who must not see a thing, so somebody who logs in
     * halfway through is not on it and is shown it - which is right for a corpse's own head and
     * wrong for anything that is only for certain clients. This is asked afresh of every viewer
     * on every pass, so a client that gains the right to see something gets it on the next tick
     * without anybody having to notice they arrived.
     *
     * <p>Null, the default, is everybody.
     */
    default void onlyFor(java.util.function.Predicate<net.minecraft.server.level.ServerPlayer> who) { }
}
