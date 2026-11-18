package kr.gcb.dungeon.physics;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Something that moves a drawing about on the limb it rides, a tick at a time.
 *
 * <p>A rider is otherwise nailed to its limb: one offset, one turn, fixed when it was hung. That
 * is right for a forearm and wrong for anything soft. This is asked once a tick, before the
 * drawing is placed, where the limb now is and which way it faces - and it answers by writing
 * the drawing's offset and turn in place.
 *
 * <p>Written into rather than returned so that nothing is allocated per rider per tick: there
 * are a lot of corpses and this runs in the middle of the physics loop.
 */
public interface Sway {

    /**
     * One tick.
     *
     * @param x where the limb is now, in the world
     * @param attitude which way it faces now
     * @param offset the drawing's offset in the limb's frame, to be written
     * @param tilt the drawing's turn in the limb's frame, to be written
     */
    void step(double x, double y, double z, Quaternionf attitude, Vector3f offset, Quaternionf tilt);
}
