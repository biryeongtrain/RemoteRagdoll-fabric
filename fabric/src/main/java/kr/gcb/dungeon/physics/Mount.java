package kr.gcb.dungeon.physics;

import kr.gcb.ragdoll.compat.Location;
import kr.gcb.ragdoll.compat.Vector;
import org.joml.Quaternionf;

/**
 * Something a limb's drawings can be hung on, whether or not it is being simulated.
 *
 * <p>Pulled out of {@link PhysicsBody} for one reason: a body that stands still is the only way
 * to look carefully at how a corpse is put together. A ragdoll lands however it lands, and
 * comparing a detail of it against anything - the same body on a client running a mod, or the
 * same body ten minutes ago - means comparing two things in different poses, which is not
 * comparing.
 *
 * <p>The point of the interface rather than a second copy of the placement code is that the
 * standing one has to be assembled by <em>exactly</em> the same arithmetic as the falling one.
 * A test body built by its own loop drifts from the real one, and then it is a test of the test
 * body. Here there is one loop - {@code Ragdoll.dress} - and two things it can hang from.
 */
public interface Mount {

    /** Where this limb is. */
    Location seat();

    /** Which way it is facing, as the drawings will be turned. */
    Quaternionf attitude();

    /**
     * Hangs a drawing this far from the seat, turned this much inside the limb's own frame.
     *
     * @param tilt an extra turn relative to the limb, or null for square to it
     */
    void hang(Puppet puppet, Vector offset, Quaternionf tilt);

    /**
     * The same, moved about on the limb every tick by a {@link Sway}.
     *
     * <p>Something that holds still has nothing to sway with, so by default this is just the
     * plain hang - which is what a standing test body wants: the resting pose, exactly.
     */
    default void hang(Puppet puppet, Vector offset, Quaternionf tilt, Sway sway) {
        hang(puppet, offset, tilt);
    }

}
