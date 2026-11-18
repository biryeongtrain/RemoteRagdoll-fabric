/* Ported from TerritoryWar (io.lightest.tw.physics / .model), same author's project.
 * Changes: package, dungeon terrain collider instead of a flat ground plane, and a
 * ragdoll built from whole limbs rather than one body per bone. */
package kr.gcb.dungeon.physics;

import com.github.stephengold.joltjni.TwoBodyConstraint;

/**
 * One joint holding two bodies together.
 *
 * <p>Exists mostly to be dropped exactly once. A constraint keeps raw pointers to both of
 * the bodies it joins, so destroying either while it is still in the system leaves Jolt
 * solving against freed memory - and the two bodies of a corpse limb are released
 * independently, one after the other, as their lifetimes run out. Both ends hold a
 * reference to this and both call {@link #drop()}; the first one wins and the second is a
 * no-op, which is the same discipline {@code PhysicsBody.released} already uses for the
 * bodies themselves and for the same reason.
 */
public final class PhysicsJoint {

    private final PhysicsWorld world;
    private final TwoBodyConstraint constraint;
    private boolean dropped = false;

    PhysicsJoint(PhysicsWorld world, TwoBodyConstraint constraint) {
        this.world = world;
        this.constraint = constraint;
    }

    /** Takes the joint out of the simulation. Safe to call from either end, any number of times. */
    void drop() {
        if (dropped) return;
        dropped = true;
        world.drop(constraint);
    }
}
