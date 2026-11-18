package kr.gcb.dungeon.model;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import kr.gcb.dungeon.physics.PhysicsBody;
import kr.gcb.dungeon.physics.PhysicsWorld;
import kr.gcb.ragdoll.compat.Location;
import net.minecraft.server.level.ServerPlayer;
import kr.gcb.ragdoll.compat.Vector;

/**
 * Lets the living push the dead about.
 *
 * <p>A corpse here is not an entity - it is six physics bodies and a set of packets - so
 * nothing collides with it by itself and no attack can ever land on it. Both ways of
 * touching one are arranged by hand: a body walked into is shouldered aside by how far the
 * walker actually moved, and a body swung at is kicked along the line somebody was looking.
 *
 * <p>Everything here asks for a <em>speed</em> and multiplies by the limb's own mass to get
 * there. An impulse divided by mass is what a body ends up travelling at, and a corpse's
 * parts run from five units to twenty four - so a flat impulse sends a head four times
 * further than a torso, which looks like the head being kicked off rather than the man being
 * kicked over.
 *
 * <p>Knows nothing about BetterModel, so a mob will be able to do all of this later on.
 */
public final class Shoves {

    private Shoves() {}

    /** How far from the line of sight a limb can be and still count as struck. */
    private static final double AIM = 0.7;
    /** How far a kick reaches, in blocks. A boot's worth past arm's length. */
    private static final double REACH = 4.0;
    /** How fast a kicked limb leaves, in blocks a second. */
    private static final double KICK_SPEED = 9.0;
    /** And how much of that is upwards, so a body lifts and turns rather than skidding. */
    private static final double LIFT = 0.4;
    /** How fast a shouldered limb leaves, per block the walker moved in that tick. */
    private static final double BARGE_SPEED = 12.0;
    /** Nothing under this counts as walking. A player standing still may stand in a corpse. */
    private static final double STIRRING = 0.01;
    /** How far from a walker a limb is close enough to be in the way. */
    private static final double ELBOW_ROOM = 1.3;

    /**
     * A kick aimed where somebody is looking. Returns what it hit, or null.
     *
     * <p>Sampled along the line rather than swept: the target is a handful of boxes the size
     * of a limb and a sample every quarter block cannot miss one.
     */
    public static PhysicsBody kick(PhysicsWorld physics, LivingEntity who, double strength) {
        if (physics == null || who == null) return null;
        Location eye = Location.of(who);
        if (eye == null) return null;
        eye.setY(who.getEyeY());
        Vector look = eye.getDirection().normalize();
        for (double along = 0.5; along <= REACH; along += 0.25) {
            Location at = eye.clone().add(look.clone().multiply(along));
            List<PhysicsBody> found = physics.near(at, AIM).stream()
                    .filter(body -> !body.driven()).toList();
            if (found.isEmpty()) continue;
            PhysicsBody hit = found.getFirst();
            Vector blow = look.clone().multiply(strength * KICK_SPEED);
            blow.setY(blow.getY() + strength * KICK_SPEED * LIFT);
            // At the point of contact rather than the middle of the limb: a boot under a
            // shoulder turns a body over, and that is most of what a kick looks like.
            hit.shove(blow.multiply(hit.mass()), at);
            return hit;
        }
        return null;
    }

    /**
     * Shoulders a body out of the way of anything walking through it.
     *
     * <p>The travel has to be measured by the caller from one position to the next.
     * {@code Player.getVelocity()} is not it: for a walking player the server has never set a
     * velocity and the value it hands back is stale or zero, which is why nothing moved.
     */
    public static void barge(PhysicsWorld physics, Entity walker, Vector travel) {
        if (physics == null || walker == null || travel == null) return;
        double speed = travel.clone().setY(0).length();
        if (speed < STIRRING) return;
        Location feet = Location.of(walker);
        if (feet == null) return;
        for (PhysicsBody limb : physics.near(feet.clone().add(0, 0.9, 0), ELBOW_ROOM)) {
            if (limb.driven()) continue;                 // it is getting up; leave it to it
            Location where = limb.location();
            Vector away = where.toVector().subtract(feet.toVector()).setY(0);
            if (away.lengthSquared() < 1.0e-4) away = travel.clone().setY(0);
            if (away.lengthSquared() < 1.0e-4) continue;
            away.normalize().multiply(speed * BARGE_SPEED);
            away.setY(speed * BARGE_SPEED * 0.25);
            limb.shove(away.multiply(limb.mass()), where);
        }
    }

    /** Whether this player is in a state where shoving corpses about makes sense. */
    public static boolean handsOn(ServerPlayer player) {
        return !player.isSpectator();
    }
}
