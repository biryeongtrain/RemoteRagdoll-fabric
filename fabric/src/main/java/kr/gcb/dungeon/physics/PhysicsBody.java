/* Ported from TerritoryWar (io.lightest.tw.physics / .model), same author's project.
 * Changes: package, dungeon terrain collider instead of a flat ground plane, and a
 * ragdoll built from whole limbs rather than one body per bone. */
package kr.gcb.dungeon.physics;

import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import kr.gcb.ragdoll.compat.Location;
import kr.gcb.ragdoll.compat.Vector;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One dynamic Jolt body, optionally drawn by a display entity.
 *
 * <p>Lifetime is deliberately split across two flags. {@code dead} says "stop
 * simulating me"; {@code released} says "the native body has been handed back". They
 * cannot be the same flag: a double {@code destroyBody} touches an already-freed
 * native pointer and takes the whole JVM down with an EXCEPTION_ACCESS_VIOLATION
 * inside joltjni.dll - not a Java exception, so no catch block will save you.
 */
public final class PhysicsBody {

    private final PhysicsWorld world;
    private final com.github.stephengold.joltjni.Body body;
    private final int bodyId;
    private final List<Rider> displays = new ArrayList<>();
    /** Joints this body is an end of. Each is shared with the body at the other end. */
    private final List<PhysicsJoint> joints = new ArrayList<>();

    /**
     * A display carried by this body, and where on it.
     *
     * <p>The offset exists so one body can hold more than one drawing. A corpse that has
     * not been blown apart is a single physics box with a head, a chest and four limbs
     * riding it - they have to keep their positions relative to each other, and they have
     * to swing round with it as it rolls, which a plain attachment cannot express.
     */
    private record Rider(Puppet puppet, org.joml.Vector3f offset, org.joml.Quaternionf tilt, Sway sway) {
    }

    /**
     * Where the floor was last built for this body, or null for one that does not want any.
     *
     * <p>Package private and a bare array because it is read every tick for every body: this
     * is a distance check in the middle of the physics loop, not a thing to allocate for. See
     * PhysicsWorld.keepFloor.
     */
    double[] floored;

    private final float mass;
    /** Goes to the bottom in water rather than floating - see model.Sinking. */
    boolean sinks;
    private int ticksLeft;
    private boolean dead = false;
    private boolean released = false;
    private Consumer<PhysicsBody> onRest;
    private int restingTicks = 0;

    PhysicsBody(PhysicsWorld world, com.github.stephengold.joltjni.Body body,
                int lifetimeTicks, float mass) {
        this.world = world;
        this.body = body;
        this.bodyId = body.getId();
        this.ticksLeft = lifetimeTicks;
        this.mass = mass;
    }

    /**
     * What this body weighs, for anybody working out how hard to hit it.
     *
     * <p>An impulse becomes speed by dividing by mass, so the same shove sends a head four
     * times further than a torso. Anything that wants a body to move at a particular speed
     * has to multiply by this first.
     */
    public float mass() { return mass; }

    /**
     * The native body, for the one thing an id cannot do.
     *
     * <p>Constraints are built from bodies rather than from their numbers, so the object
     * has to be kept rather than thrown away the moment it is made. It stops being valid
     * the instant the body is destroyed, which is why nothing outside this package can
     * reach it.
     */
    com.github.stephengold.joltjni.Body body() { return body; }

    void hold(PhysicsJoint joint) { joints.add(joint); }

    /** Attaches anything that can be dragged about - an entity, or a packet. */
    public PhysicsBody attach(Puppet p, kr.gcb.ragdoll.compat.Vector offset) {
        return attach(p, offset, null);
    }

    /**
     * The same, drawn turned in the body's own frame.
     *
     * <p>For anything whose picture is not square to the limb it rides. The turn is applied
     * before the body's attitude rather than after, so it stays put relative to the limb while
     * the limb rolls - which is the only way it can mean "hangs forward and down" rather than
     * "points north".
     */
    public PhysicsBody attach(Puppet p, kr.gcb.ragdoll.compat.Vector offset, org.joml.Quaternionf tilt) {
        return attach(p, offset, tilt, null);
    }

    /**
     * The same, and moved about on the limb every tick by something that knows how.
     *
     * <p>See {@link Sway}. The offset and turn given here are where it starts; the sway writes
     * them from then on. A sway always gets a turn to write into, even when it starts square.
     */
    public PhysicsBody attach(Puppet p, kr.gcb.ragdoll.compat.Vector offset, org.joml.Quaternionf tilt, Sway sway) {
        displays.add(new Rider(p, offset == null ? new org.joml.Vector3f()
                : new org.joml.Vector3f((float) offset.getX(), (float) offset.getY(),
                        (float) offset.getZ()),
                tilt != null ? new org.joml.Quaternionf(tilt)
                        : sway != null ? new org.joml.Quaternionf() : null,
                sway));
        return this;
    }


    /**
     * Takes every drawing off the body and leaves the body itself alone.
     *
     * <p>For anything that has to change what a thing looks like without changing where it is
     * or how it is moving - a corpse somebody has just taken the coat off, say. Re-attaching
     * is how it is dressed again; the physics never notices either happened.
     */
    public void undress() {
        for (Rider rider : displays) rider.puppet().dispose();
        displays.clear();
    }

    /**
     * Called as the body is handed back, while it still knows where it ended up.
     *
     * <p>The last chance to do anything about a corpse. After this the native body is
     * freed and the displays are gone, so anything that wants to mark the spot - a puff of
     * end rod where a wounded man was carried off, say - has to happen here.
     */
    public PhysicsBody onGone(Consumer<PhysicsBody> callback) {
        this.onGone = callback;
        return this;
    }

    private Consumer<PhysicsBody> onGone;

    /** Called once the body has essentially stopped moving - used to trigger impacts. */
    /**
     * Called every physics step while the body is alive.
     *
     * <p>For anything that has to be drawn along the way rather than where it stops - the
     * thread behind an arrow, which is the whole difference between a volley and three
     * unrelated sticks arriving.
     */
    /**
     * Something to do with this body every step, added to whatever was already being done.
     *
     * <p>Added, not set. This was one slot, and two things want it - the thread of crit
     * particles a shot draws behind it, and the sweep that works out who the shot went
     * through - so whichever asked second silently switched off the first. A projectile
     * either hit people or had a trail, never both, and nothing said which.
     */
    public PhysicsBody onStep(Consumer<PhysicsBody> callback) {
        if (callback == null) return this;
        this.onStep = onStep == null ? callback : onStep.andThen(callback);
        return this;
    }

    private Consumer<PhysicsBody> onStep;

    public PhysicsBody onRest(Consumer<PhysicsBody> callback) {
        this.onRest = callback;
        return this;
    }

    public Location location() {
        RVec3 p = world.bodyInterface().getPosition(bodyId);
        return new Location(world.world(), p.xx(), p.yy(), p.zz());
    }

    /**
     * Drives this body towards a point instead of leaving it where it fell.
     *
     * <p>By velocity rather than by teleporting it. A corpse is eleven boxes on ten
     * constraints, and moving one of them outright leaves the other ten where they were -
     * the joints then have a metre of slack to take up in one step, which they do by
     * flinging the limbs. Pulling instead means the rest of the body is dragged by the
     * joints that already hold it, which is both stable and the picture wanted: a limp
     * thing over somebody's shoulder, arms and legs swinging as he walks.
     *
     * <p>The cap is what stops it being a slingshot. Without one the gain applies to the
     * whole gap, so a porter who steps through a portal or gets a chunk-load hitch hurls
     * the body he is carrying across the map.
     */
    public void haul(Location target, double gain, double cap) {
        if (dead || released || target == null) return;
        if (target.getWorld() != null && !target.getWorld().equals(world.world())) return;
        RVec3 p = world.bodyInterface().getPosition(bodyId);
        double dx = target.getX() - p.xx();
        double dy = target.getY() - p.yy();
        double dz = target.getZ() - p.zz();
        double far = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (far < 1.0e-4) return;
        double k = Math.min(far * gain, cap) / far;
        world.bodyInterface().setLinearVelocity(bodyId,
                new com.github.stephengold.joltjni.Vec3(
                        (float) (dx * k), (float) (dy * k), (float) (dz * k)));
        world.bodyInterface().activateBody(bodyId);
    }

    /**
     * Turns this body towards an attitude instead of leaving it where it fell.
     *
     * <p>By angular velocity, for the same reason {@link #haul} moves by linear velocity: a
     * body set to a rotation outright takes its joints with it in one step, and a joint with
     * a whole turn of slack to take up answers by flinging whatever is on the other end.
     */
    public void turnTo(org.joml.Quaternionf attitude, double gain, double cap) {
        if (dead || released || attitude == null) return;
        Quat now = world.bodyInterface().getRotation(bodyId);
        org.joml.Quaternionf current = new org.joml.Quaternionf(now.getX(), now.getY(), now.getZ(), now.getW());
        org.joml.Quaternionf delta = new org.joml.Quaternionf(attitude)
                .mul(current.conjugate(new org.joml.Quaternionf())).normalize();
        // A quaternion and its negative are the same attitude reached the long way round;
        // turning by the long one is slower and looks like the limb is winding up.
        if (delta.w < 0) delta.set(-delta.x, -delta.y, -delta.z, -delta.w);
        org.joml.AxisAngle4f turn = new org.joml.AxisAngle4f().set(delta);
        if (turn.angle < 1.0e-4) return;
        double speed = Math.min(turn.angle * gain, cap);
        world.bodyInterface().setAngularVelocity(bodyId, new com.github.stephengold.joltjni.Vec3(
                (float) (turn.x * speed), (float) (turn.y * speed), (float) (turn.z * speed)));
        world.bodyInterface().activateBody(bodyId);
    }

    /**
     * Puts this body exactly here, facing exactly this way, and stops it dead.
     *
     * <p>Only for the last part of standing up, and only because every limb is moved in the
     * same step: a corpse driven purely by velocity arrives near its pose and stays a few
     * degrees off it, because gravity, the floor and five joints all get a say afterwards.
     * Blending the last of the way by hand is what makes the difference between a body that
     * is standing and one that is nearly standing - and it is safe here because the pose
     * being blended to is the rest pose the joints were built in, so nothing is strained.
     */
    /**
     * Turns a body where it stands, without touching where it is going.
     *
     * <p>{@link #placeAt} cannot be used for this: it stops the body dead, which is right for
     * pinning a pose and wrong for a corpse that has just been thrown - the throw would be
     * thrown away with it.
     */
    public void face(org.joml.Quaternionf attitude) {
        if (dead || released) return;
        RVec3 at = world.bodyInterface().getPosition(bodyId);
        world.bodyInterface().setPositionAndRotation(bodyId, at,
                new Quat(attitude.x, attitude.y, attitude.z, attitude.w),
                com.github.stephengold.joltjni.enumerate.EActivation.Activate);
    }

    public void placeAt(Location where, org.joml.Quaternionf attitude) {
        if (dead || released) return;
        world.bodyInterface().setPositionAndRotation(bodyId,
                new RVec3(where.getX(), where.getY(), where.getZ()),
                new Quat(attitude.x, attitude.y, attitude.z, attitude.w),
                com.github.stephengold.joltjni.enumerate.EActivation.DontActivate);
        world.bodyInterface().setLinearAndAngularVelocity(bodyId,
                new com.github.stephengold.joltjni.Vec3(0, 0, 0),
                new com.github.stephengold.joltjni.Vec3(0, 0, 0));
    }

    /**
     * Kicks this body: an impulse at a point on it, so it spins as well as travels.
     *
     * <p>At the point rather than at the middle, because that is the difference between a
     * body that slides away from a boot and one that turns over. Jolt works out the spin
     * from where the blow landed relative to the centre of mass.
     */
    public void shove(Vector impulse, Location where) {
        if (dead || released || impulse == null) return;
        var push = new com.github.stephengold.joltjni.Vec3(
                (float) impulse.getX(), (float) impulse.getY(), (float) impulse.getZ());
        if (where == null) world.bodyInterface().addImpulse(bodyId, push);
        else world.bodyInterface().addImpulse(bodyId, push,
                new RVec3(where.getX(), where.getY(), where.getZ()));
        world.bodyInterface().activateBody(bodyId);
    }

    /** Which way this body is currently facing, for anything that has to check the pose. */
    public org.joml.Quaternionf attitude() {
        Quat r = world.bodyInterface().getRotation(bodyId);
        return new org.joml.Quaternionf(r.getX(), r.getY(), r.getZ(), r.getW());
    }

    /** Keeps everything drawn on this body out of one person's sight, or gives it back. */
    public void blind(net.minecraft.server.level.ServerPlayer viewer, boolean hidden) {
        for (Rider rider : displays) rider.puppet().blind(viewer, hidden);
    }

    /** Shakes this body awake. Jolt sleeps anything at rest and will not stir it by itself. */
    public void wake() {
        if (dead || released) return;
        world.bodyInterface().activateBody(bodyId);
    }

    /** Whether this body has settled enough to hand back over. */
    public boolean resting() { return !dead && !released && !world.bodyInterface().isActive(bodyId); }

    public boolean isDead() { return dead; }

    public boolean sinks() { return sinks; }

    /**
     * The water's say, once a tick before the step: lift that undoes gravity and a little more
     * the deeper the body is, so it rises and then rides the surface; a heavy one gets only part
     * of it and goes down slowly. Both are dragged, so nothing shoots up or spins forever.
     *
     * @param under how far below the surface this body's middle is, in blocks
     */
    void buoy(double under, float gravity) {
        if (dead || released) return;
        float dt = 1f / 20f;
        var bi = world.bodyInterface();
        var v = bi.getLinearVelocity(bodyId);
        var w = bi.getAngularVelocity(bodyId);
        double lift;
        if (sinks) {
            lift = -gravity * 0.75;
        } else {
            // Half under at the surface: full lift below, none a quarter block above.
            double share = Math.max(0, Math.min(1, (under + 0.25) / 0.6));
            lift = -gravity * share * 1.35;
        }
        float drag = sinks ? 0.82f : 0.86f;
        bi.setLinearVelocity(bodyId, new com.github.stephengold.joltjni.Vec3(
                v.getX() * drag, (float) ((v.getY() + lift * dt) * drag), v.getZ() * drag));
        bi.setAngularVelocity(bodyId, new com.github.stephengold.joltjni.Vec3(
                w.getX() * 0.9f, w.getY() * 0.9f, w.getZ() * 0.9f));
        if (!sinks) bi.activateBody(bodyId);
    }

    /**
     * Whether something else is driving this body right now.
     *
     * <p>A body being pulled upright by {@link kr.gcb.dungeon.model.Rise} is having its velocity
     * written every tick; a kick that adds to that is not a kick, it is a fight between two
     * hands on the same wheel, and it looks like one. Reported from play: a skeleton getting up
     * out of a heap could be shoved about by punching it.
     */
    public boolean driven() { return driven; }

    public void driven(boolean driven) { this.driven = driven; }

    private boolean driven;

    public void kill() { dead = true; }

    void tick() {
        if (dead) return;

        RVec3 p = world.bodyInterface().getPosition(bodyId);
        Quat r = world.bodyInterface().getRotation(bodyId);

        if (onStep != null) onStep.accept(this);

        Quaternionf spin = new Quaternionf(r.getX(), r.getY(), r.getZ(), r.getW());
        for (Rider rider : displays) {
            if (!rider.puppet().alive()) continue;
            // Soft things first: they move on the limb before the limb's own turn is applied.
            if (rider.sway() != null) rider.sway().step(p.xx(), p.yy(), p.zz(), spin, rider.offset(), rider.tilt());
            // The offset turns with the body. A display's own translation is applied after
            // its rotation rather than through it, so leaving the offset there would keep
            // a head hanging squarely above a torso however far the torso had rolled over.
            org.joml.Vector3f at = spin.transform(new org.joml.Vector3f(rider.offset()));
            // The rider's own turn goes inside the body's, so it is a turn relative to the
            // limb rather than to the world.
            Quaternionf drawn = rider.tilt() == null ? spin
                    : new Quaternionf(spin).mul(rider.tilt());
            rider.puppet().place(new Location(world.world(),
                    p.xx() + at.x, p.yy() + at.y, p.zz() + at.z), drawn);
        }

        if (onRest != null && !world.bodyInterface().isActive(bodyId)) {
            if (++restingTicks >= 2) {
                Consumer<PhysicsBody> cb = onRest;
                onRest = null;
                cb.accept(this);
            }
        } else {
            restingTicks = 0;
        }

        sink();

        if (ticksLeft > 0 && --ticksLeft == 0) dead = true;
        if (p.yy() < world.world().getMinY() - 8) dead = true;   // fell out of the world
    }

    /**
     * Walks a body down through water it is lying on top of.
     *
     * <p>The physics ground is one flat plane at the map's own level - see
     * PhysicsWorld.ensureGround, which explains why - and a lake is water filling the space
     * under it. So a body that comes to rest over a lake rests on the plane, and the plane
     * is the surface: the dead of a battle fought at a ford lay on the water like a raft.
     *
     * <p>Jolt cannot be told about the hole, because the hole is not in the plane. So the
     * body is put down by hand instead: once it has stopped moving, and while there is
     * still water under it, it is set a little lower each few ticks until it is on the bed.
     * It is asleep by then, and a sleeping body stays where it is put - nothing pulls it
     * back up and nothing has to be simulated to hold it down.
     */
    private void sink() {
        // Only after it has finished falling. A body still tumbling is Jolt's to move, and
        // two things moving one body is a body that jitters.
        if (world.bodyInterface().isActive(bodyId)) { sinkTick = 0; return; }
        if ((++sinkTick & 3) != 0) return;              // every fourth tick is smooth enough
        RVec3 p = world.bodyInterface().getPosition(bodyId);
        var w = world.world();
        int bx = (int) Math.floor(p.xx()), by = (int) Math.floor(p.yy()),
                bz = (int) Math.floor(p.zz());
        // Never load a chunk to drown a corpse. If nobody is there to see it, it can float.
        if (!w.hasChunk(bx >> 4, bz >> 4)) return;
        // Looked for a little way down, not at one spot.
        //
        // Two separate offsets defeated the single lookup. A corpse over a lake rests on
        // the ground plane and the plane's top face is the land's level, so the water is
        // already below it; and what is asked about is the middle of the body, which sits
        // half a limb higher again. Between them the block under the body was air both
        // times, the answer was "no water here", and nothing ever sank.
        //
        // So it looks down until it finds water or something solid, whichever comes first.
        // Solid first means it is lying on the ground and has arrived.
        boolean wet = false;
        for (int dy = 0; dy <= REACH; dy++) {
            var below = new net.minecraft.core.BlockPos(bx, by - dy, bz);
            var found = w.getBlockState(below);
            if (found.is(net.minecraft.world.level.block.Blocks.WATER)) { wet = true; break; }
            if (found.isSolid()) break;
        }
        if (!wet) return;
        int next = (int) Math.floor(p.yy() - SINK_STEP);
        if (next < w.getMinY()) return;
        // On the bed: the next step down would put it inside something.
        if (w.getBlockState(new net.minecraft.core.BlockPos(bx, next, bz)).isSolid()) return;
        world.bodyInterface().setPosition(bodyId,
                new RVec3(p.xx(), p.yy() - SINK_STEP, p.zz()),
                com.github.stephengold.joltjni.enumerate.EActivation.DontActivate);
    }

    /** How far a body settles into water each time it is put down. */
    private static final double SINK_STEP = 0.2;

    /** How far under a body to look for the water it is lying on top of. */
    private static final int REACH = 3;

    private int sinkTick;

    /** Hands the native body back. Guarded so it can only ever happen once. */
    void release() {
        if (released) return;
        // Where it was, before anything is freed: whatever was resting on it has to be told.
        RVec3 last = world.bodyInterface().getPosition(bodyId);
        Location grave = new Location(world.world(), last.xx(), last.yy(), last.zz());
        released = true;
        if (onGone != null) {
            Consumer<PhysicsBody> cb = onGone;
            onGone = null;
            // Before removeBody, so location() still answers. Guarded because a decoration
            // that throws must not leave a native body un-freed.
            try { cb.accept(this); } catch (RuntimeException ignored) { }
        }
        // Joints first, and the order is not negotiable: a constraint still in the system
        // when one of its bodies is destroyed is solved against freed memory the next step,
        // which is a native crash with nothing in the log to say where it came from.
        for (PhysicsJoint joint : joints) joint.drop();
        joints.clear();
        for (Rider rider : displays) rider.puppet().dispose();
        displays.clear();
        world.removeBody(bodyId);
        // Jolt puts a body that has stopped moving to sleep, and taking away what it was
        // sleeping on does not wake it: a corpse cleared from under a heap left the rest of
        // the heap hanging in the air exactly where it had been lying.
        world.wakeNear(grave, WAKE_REACH);
    }

    /** How far around a vanished body to go looking for things that were resting on it. */
    private static final double WAKE_REACH = 2.0;
}
