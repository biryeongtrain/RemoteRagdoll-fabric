/* Ported from TerritoryWar (io.lightest.tw.model.Ragdoll), same author's project.
 * Changes: whole limbs instead of one physics body per bone, and the dungeon's own block
 * terrain under the corpse instead of a flat field.
 *
 * Brought to Graywater from Gazadry (kr.gcb.dungeon.model.Ragdoll) with the rest of its
 * physics. Left out here: getting up again (Rise is a stand-in), the body shapes of the
 * Wildfire gender mod, and Gazadry's own suits of armour. */
package kr.gcb.dungeon.model;

import kr.gcb.dungeon.physics.PhysicsBody;
import kr.gcb.dungeon.physics.PhysicsWorld;
import kr.gcb.dungeon.physics.Puppet;
import kr.toxicity.model.api.armor.ArmorItem;
import kr.toxicity.model.api.armor.PlayerArmor;
import kr.toxicity.model.api.skin.SkinData;
import kr.gcb.ragdoll.compat.Location;
import net.minecraft.world.item.ItemStack;
import kr.gcb.ragdoll.compat.Host;
import kr.gcb.ragdoll.compat.Vector;
import kr.toxicity.model.api.mod.platform.ModItemStack;
import kr.toxicity.model.api.platform.PlatformItemTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import kr.gcb.ragdoll.compat.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A corpse that comes apart, built out of six whole limbs.
 *
 * <p>The version this came from gave every bone its own physics body: an upper arm and a
 * forearm were two boxes on an elbow constraint, and a torso was a chest on a waist on a
 * hip. That is more faithful to a skeleton and it is not what a dungeon corpse wants - a
 * body with ten joints folds up in ways that read as a rubber toy, and every joint between
 * two boxes is a seam that opens into a hollow shell the first time it bends.
 *
 * <p>So a limb is one rigid piece here, the way a player has always been drawn: head,
 * torso, two arms, two legs. The five joints that remain are the ones anybody would name -
 * a neck, two shoulders, two hips - and an elbow simply does not bend, because on a corpse
 * seen for a few seconds nobody can tell that it did not.
 *
 * <p>BetterModel is still what makes the body recognisable. Its skin manager cuts a player
 * skin into a textured item per body part with the armour baked in; those items are what
 * hangs on these six bodies, two or three to a limb, at fixed offsets that turn with it.
 */
public final class Ragdoll {

    private Ragdoll() {}

    /** How far a corpse is worth building. Past this nobody would see it land. */
    private static final double VIEW_RANGE = 72.0;

    /** How much of the physics world corpses may fill. A body is six of these. */
    private static final int BODY_BUDGET = 2000;

    /**
     * The blow that finished somebody: how far it broke them, and which way it came from.
     *
     * <p>{@code push} points from whoever struck towards whoever was struck, already
     * scaled by how hard. A body that drops straight down where it stood reads as a unit
     * being deleted; one thrown off the end of a spear reads as a death.
     */
    public record Blow(BodyPlan.Wound wound, Vector push) {
        public static final Blow NONE = new Blow(BodyPlan.Wound.INTACT, new Vector());
    }

    /** What the dead was wearing, copied before the body is taken away. */
    /** What the body was wearing, and what it was holding when it stopped. */
    public record Kit(ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots, ItemStack hand) {
        public static final Kit NONE = new Kit(null, null, null, null, null);

        public static Kit of(LivingEntity gear) {
            if (gear == null) return NONE;
            return new Kit(gear.getItemBySlot(EquipmentSlot.HEAD).copy(), gear.getItemBySlot(EquipmentSlot.CHEST).copy(),
                    gear.getItemBySlot(EquipmentSlot.LEGS).copy(), gear.getItemBySlot(EquipmentSlot.FEET).copy(), gear.getMainHandItem().copy());
        }
    }

    /** A corpse, held on to so it can be cleared - or raised, or undressed - before its time. */
    public static final class Remains {
        private final Map<String, PhysicsBody> parts = new LinkedHashMap<>();
        private boolean spent;
        private boolean slim;
        /**
         * Everything the body was drawn from, kept so it can be drawn again.
         *
         * <p>A corpse is now a container somebody rummages in, and what comes out of it has to
         * come off it: take the coat out of a dead man's pack and the dead man should not still
         * be wearing it. Re-slicing needs the skin, the seam and the inset it was built with,
         * and none of those are anywhere else by the time anybody opens the box.
         */
        private SkinData skin;
        /** What the owner's client says their body is, or null - which is almost everybody. */
        private Object shape;
        private double seam, inset;
        private Kit kit = Kit.NONE;
        /**
         * What each limb is still carrying, to be left where that limb ends up.
         *
         * <p>The same arrangement the bone corpses have, and for the same reason: somebody
         * knocked apart does not hand their sword over at the moment they die. It goes down
         * with the arm and is lying there when the arm finally crumbles. Used for anybody
         * whose things are not already in a box - a hired companion, most obviously, who has
         * no inventory of their own to become one.
         */
        private final Map<String, java.util.List<ItemStack>> carried = new LinkedHashMap<>();
        private int whole;

        void add(String limb, PhysicsBody part) {
            if (spent) part.kill();
            else parts.put(limb, part);
        }

        /** What it is wearing and holding at the moment. */
        public Kit kit() { return kit; }

        /** How hard a bearer pulls, and how fast the body is allowed to come. */
        public static final double CARRY_GAIN = 6.0, CARRY_CAP = 14.0;

        /**
         * Drags this body towards a point and keeps it there - a corpse over a shoulder.
         *
         * <p>The torso alone is pulled. Everything else is held on by the joints that already
         * hold it, so the arms and the legs swing off it as the bearer walks and catch on the
         * doorframes - which is the whole of the picture, and is free: it is what a ragdoll
         * does when you move one box of it.
         *
         * <p>Marked driven while it is carried, so nothing else in the game takes a turn at
         * moving it. {@code Shoves} steps over driven limbs, which is what stops a second
         * player walking into the body on your back and kicking it off you.
         */
        public void haul(kr.gcb.ragdoll.compat.Location target) {
            if (spent || target == null) return;
            for (PhysicsBody part : parts.values()) if (part != null) part.driven(true);
            PhysicsBody torso = parts.get("torso");
            if (torso == null) torso = parts.values().stream().findFirst().orElse(null);
            if (torso != null) torso.haul(target, CARRY_GAIN, CARRY_CAP);
        }

        /** Lets go. Whatever it was doing, it falls from here. */
        public void release() {
            for (PhysicsBody part : parts.values()) if (part != null) part.driven(false);
        }

        /** Where the body is, which is where its middle is. */
        public kr.gcb.ragdoll.compat.Location middle() {
            PhysicsBody torso = parts.get("torso");
            if (torso == null) torso = parts.values().stream().findFirst().orElse(null);
            return torso == null ? null : torso.location();
        }

        /**
         * Picks the whole thing up and puts it down somewhere else, shape and all.
         *
         * <p>For a waystone, and only for a waystone. {@link #haul} moves a body by pulling it
         * and cannot cross two hundred blocks; this moves every limb by the same offset in one
         * step, which keeps the crumple it is in and costs the swing it was mid-way through.
         */
        public int shift(net.minecraft.server.level.ServerLevel world, kr.gcb.ragdoll.compat.Vector by) {
            if (spent || world == null || by == null) return 0;
            Map<String, double[]> laid = pose();
            for (double[] limb : laid.values()) {
                limb[0] += by.getX(); limb[1] += by.getY(); limb[2] += by.getZ();
            }
            return pose(world, laid);
        }

        /**
         * Where every limb of this body is, and which way up.
         *
         * <p>So a corpse can be written to a file and come back lying the way it fell rather
         * than the way a fresh one is laid out. The difference is not decoration: a body
         * crumpled against the wall it was thrown at is most of what a player remembers about
         * where somebody died, and standing it up neatly on restart quietly erases that.
         *
         * <p>Eleven boxes, each a position and a rotation. Read straight off Jolt, which is
         * the only thing that actually knows.
         */
        public Map<String, double[]> pose() {
            Map<String, double[]> laid = new LinkedHashMap<>();
            for (Map.Entry<String, PhysicsBody> part : parts.entrySet()) {
                PhysicsBody body = part.getValue();
                if (body == null) continue;
                kr.gcb.ragdoll.compat.Location at = body.location();
                org.joml.Quaternionf turn = body.attitude();
                if (at == null || turn == null) continue;
                laid.put(part.getKey(), new double[]{
                        at.getX(), at.getY(), at.getZ(), turn.x, turn.y, turn.z, turn.w});
            }
            return laid;
        }

        /**
         * And putting one back exactly where it was.
         *
         * <p>Placed rather than driven: {@code placeAt} sets the position outright and zeroes
         * the velocity, which is what a body that has already finished falling wants. A limb
         * the file does not mention is left where the fresh lay-out put it.
         */
        public int pose(net.minecraft.server.level.ServerLevel world, Map<String, double[]> laid) {
            if (world == null || laid == null) return 0;
            int placed = 0;
            for (Map.Entry<String, PhysicsBody> part : parts.entrySet()) {
                double[] was = laid.get(part.getKey());
                if (was == null || was.length < 7 || part.getValue() == null) continue;
                part.getValue().placeAt(new kr.gcb.ragdoll.compat.Location(world, was[0], was[1], was[2]),
                        new org.joml.Quaternionf((float) was[3], (float) was[4],
                                (float) was[5], (float) was[6]));
                placed++;
            }
            return placed;
        }

        /** Gives one limb something to leave behind when it goes. */
        public void leave(String limb, ItemStack item) {
            if (item == null || item.isEmpty()) return;
            carried.computeIfAbsent(limb, key -> new java.util.ArrayList<>()).add(item.copy());
        }

        /** One limb has finished; whatever it was carrying is lying where it stopped. */
        private void shed(Host plugin, String limb, PhysicsBody body) {
            java.util.List<ItemStack> pile = carried.remove(limb);
            if (pile == null || pile.isEmpty() || spent) return;
            Location where = body.location();
            if (where == null || where.getWorld() == null) return;
            for (ItemStack item : pile) net.minecraft.world.Containers.dropItemStack(where.getWorld(), where.getX(), where.getY(), where.getZ(), item);
        }

        /**
         * Anything left over once the last limb has gone, dropped where that one was.
         *
         * <p>Counted down rather than checked, because a body is asked to shed while it is
         * being taken apart and has not finished admitting it is dead.
         */
        private void sweep(Location where) {
            if (--whole > 0) return;
            if (spent || where == null || where.getWorld() == null) { carried.clear(); return; }
            for (var pile : carried.values()) {
                for (ItemStack item : pile) net.minecraft.world.Containers.dropItemStack(where.getWorld(), where.getX(), where.getY(), where.getZ(), item);
            }
            carried.clear();
        }

        /**
         * Draws the body wearing something else, without disturbing it.
         *
         * <p>The physics is not touched: the limbs keep their positions, their velocities and
         * their joints, and only the pictures hung on them are replaced. So a corpse whose
         * boots have just been taken settles exactly where it was going to settle anyway.
         *
         * <p>Returns false when there is nothing to re-draw - a body already taken away, or one
         * whose skin never arrived, which is also the state it is in on a server with no
         * BetterModel at all.
         */
        public boolean wear(Host plugin, Kit worn) {
            if (spent || skin == null || parts.isEmpty()) return false;
            kit = worn == null ? Kit.NONE : worn;
            PlayerArmor armour = armourOf(kit);
            List<BodyPlan.Part> skeleton = BodyPlan.skeleton(slim);
            for (BodyPlan.Part part : skeleton) {
                PhysicsBody body = parts.get(part.name());
                if (body == null || body.isDead()) continue;
                body.undress();
                dress(plugin, on(body), part, skin, armour, inset, seam, shape);
            }
            hang(plugin, parts.get("right_arm"), skeleton, kit.hand());
            don(plugin, parts.get("head"), kit.helmet());
            return true;
        }

        public boolean standing() { return !spent && !parts.isEmpty(); }

        /**
         * Where the head is right now, or null while there is not one.
         *
         * <p>For anything that wants to look out of it. The body is assembled asynchronously,
         * so for the first moments after a corpse is dropped there is no head to be at.
         */
        public Location head() {
            PhysicsBody head = parts.get("head");
            if (head == null || head.isDead()) return null;
            Location at = head.location();
            // Which way it is looking, so a camera on it can look that way too. The face is
            // the bone's own +Z, which is Minecraft's own convention: yaw nought faces south.
            //
            // This was flipped to -Z once, on the strength of a camera that showed the floor
            // while the body lay on its back - and the axis had nothing to do with it. The
            // rotation was never reaching the client at all (see Fallen: a passenger owns their
            // own view), so the camera was simply still pointing wherever the player had been
            // looking. Once it did reach the client, -Z was backwards. Two bugs, one symptom.
            org.joml.Vector3f facing = head.attitude().transform(new org.joml.Vector3f(0, 0, 1));
            Vector look = new Vector(facing.x, facing.y, facing.z);
            if (look.lengthSquared() > 1.0e-6) at.setDirection(look);
            return at;
        }

        /**
         * How far the head is tipped over sideways, in degrees: the one turn {@link #head} cannot
         * carry, because a Minecraft camera has a yaw and a pitch and nothing else.
         *
         * <p>Measured against the head looking the same way with its crown as high as it can be:
         * that camera's up is worked out from the face alone, and the roll is the angle from it to
         * the head's real up, about the line of sight. NaN while the face points straight up or
         * down, where "as high as it can be" has no answer - the caller keeps what it had.
         */
        public double roll() {
            PhysicsBody head = parts.get("head");
            if (head == null || head.isDead()) return Double.NaN;
            org.joml.Quaternionf q = head.attitude();
            org.joml.Vector3f face = q.transform(new org.joml.Vector3f(0, 0, 1));
            org.joml.Vector3f up = q.transform(new org.joml.Vector3f(0, 1, 0));
            org.joml.Vector3f right = new org.joml.Vector3f(0, 1, 0).cross(face);
            if (right.lengthSquared() < 1.0e-4f) return Double.NaN;
            right.normalize();
            org.joml.Vector3f level = new org.joml.Vector3f(face).cross(right).normalize();
            double sin = new org.joml.Vector3f(level).cross(up).dot(face);
            double cos = level.dot(up);
            return Math.toDegrees(Math.atan2(sin, cos));
        }

        /**
         * Gets the body back on its feet where it lies, facing the way its head is pointing.
         *
         * <p>Returns false when there is nothing left to raise. The callback fires once it is
         * up, with the spot and bearing it finished at, and the corpse is cleared then - so
         * whatever takes over has somewhere exact to appear and nothing is on screen twice.
         */
        public boolean rise(Host plugin, int ticks, java.util.function.Consumer<Location> then) {
            return rise(plugin, ticks, 10, then);
        }

        /** The same, standing at attention for {@code hold} ticks before it is handed over. */
        public boolean rise(Host plugin, int ticks, int hold, java.util.function.Consumer<Location> then) {
            PhysicsBody torso = parts.get("torso");
            if (spent || torso == null || torso.isDead()) return false;
            Location middle = torso.location();
            // Which way the body is lying, measured along its own length rather than from the
            // torso to the head: a crumpled body has its head almost over its chest, and that
            // gave no direction at all, so every corpse got up facing due south.
            PhysicsBody head = parts.get("head");
            PhysicsBody rightLeg = parts.get("right_leg"), leftLeg = parts.get("left_leg");
            Vector feet = null;
            if (rightLeg != null && !rightLeg.isDead() && leftLeg != null && !leftLeg.isDead()) {
                feet = rightLeg.location().toVector().add(leftLeg.location().toVector()).multiply(0.5);
            }
            Vector facing = new Vector(0, 0, 1);
            if (head != null && !head.isDead() && feet != null) {
                facing = head.location().toVector().subtract(feet).setY(0);
            }
            if (facing.lengthSquared() < 1.0e-3 && head != null && !head.isDead()) {
                facing = head.location().toVector().subtract(middle.toVector()).setY(0);
            }
            if (facing.lengthSquared() < 1.0e-3) facing = new Vector(0, 0, 1);
            float yaw = (float) Math.toDegrees(Math.atan2(-facing.getX(), facing.getZ()));
            Location footing = ground(middle);
            spent = true;               // no other hand may clear it while it is getting up
            Rise.start(plugin, new LinkedHashMap<>(parts), slim, footing, yaw, ticks, hold, spot -> {
                parts.clear();
                if (then != null) then.accept(spot);
            });
            return true;
        }

        /** The top of whatever is under the body, so it stands on the floor and not in it. */
        private static Location ground(Location from) {
            var world = from.getWorld();
            int x = from.getBlockX(), z = from.getBlockZ();
            for (int y = from.getBlockY() + 1; y > world.getMinY(); y--) {
                BlockPos under = new BlockPos(x, y - 1, z);
                if (!world.getBlockState(under).getCollisionShape(world, under).isEmpty()) {
                    return new Location(world, from.getX(), y, from.getZ());
                }
            }
            return from.clone();
        }

        /**
         * Hides the head from one person - the one whose camera is inside it.
         *
         * <p>The rest of the body stays: a headless corpse seen from the neck up is exactly
         * what a first person view of your own corpse should look like, and everybody else
         * still sees a whole one.
         */
        public void hideHead(net.minecraft.server.level.ServerPlayer from, boolean hidden) {
            PhysicsBody head = parts.get("head");
            if (head != null && !head.isDead()) head.blind(from, hidden);
        }

        /** Takes the body away now. Safe to call twice, and before it exists. */
        public void dispel() {
            spent = true;
            for (PhysicsBody part : parts.values()) part.kill();
            parts.clear();
        }
    }

    /**
     * Drops a corpse wearing the given skin. Returns a handle that fills in later, or null
     * when there is no way to build one.
     *
     * <p>The skin lookup is asynchronous and the body is assembled when it returns, so the
     * handle comes back empty - the alternative is blocking the server on a texture fetch.
     */
    public static Remains spawn(Host plugin, PhysicsWorld phys, Location at, String skinUrl,
                                boolean slim, int lifetimeTicks, Kit kit, Blow blow) {
        return spawn(plugin, phys, at, skinUrl, slim, lifetimeTicks, kit, blow,
                plugin.getConfig().getDouble("corpse.seam-pixels", 0.0));
    }

    /**
     * The same, prising every seam inside a limb apart by a fraction of a skin pixel.
     *
     * <p>A knob rather than a constant because the answer is a thing you can only see. The
     * pieces are laid out to meet exactly, and exactly is what makes two faces share a plane
     * and flicker; if they in fact overlap by half a pixel this opens that up, and if they do
     * not it opens a slit instead. One number, tried in the game, settles which.
     */
    public static Remains spawn(Host plugin, PhysicsWorld phys, Location at, String skinUrl,
                                boolean slim, int lifetimeTicks, Kit kit, Blow blow,
                                double seamPixels) {
        return spawn(plugin, phys, at, skinUrl, slim, lifetimeTicks, kit, blow, seamPixels, null);
    }

    /**
     * The same, for a body whose owner is somebody with a client rather than a skin URL.
     *
     * <p>Only one thing needs the difference: a player may have told the Wildfire Female Gender
     * mod what their body is, and a corpse of theirs should be that body. Nothing else here
     * cares who they were - the skin is a URL and the armour is a kit, and a hired companion
     * comes down the same road with neither a UUID nor any settings to find under one.
     *
     * @param owner whose corpse this is, or null for anybody without a client
     */
    public static Remains spawn(Host plugin, PhysicsWorld phys, Location at, String skinUrl,
                                boolean slim, int lifetimeTicks, Kit kit, Blow blow,
                                double seamPixels, java.util.UUID owner) {
        if (phys == null) { refused(plugin, "no physics in this world", at); return null; }
        if (skinUrl == null || skinUrl.isBlank()) { refused(plugin, "no skin url", at); return null; }
        if (nobodyWatching(at)) { refused(plugin, "nobody within " + VIEW_RANGE, at); return null; }
        if (phys.trackedCount() > BODY_BUDGET) {
            refused(plugin, "over budget (" + phys.trackedCount() + "/" + BODY_BUDGET + ")", at);
            return null;
        }
        // The floor it is going to land on has to exist before it does.
        phys.ensureTerrain(at);

        Remains remains = new Remains();
        remains.slim = slim;
        try {
            var profile = kr.toxicity.model.api.profile.ModelProfile.of(
                    new kr.toxicity.model.api.profile.ModelProfileInfo(
                            java.util.UUID.randomUUID(), "corpse"),
                    // The third field is slim. Left false, a three pixel arm is drawn on a
                    // four pixel model and the unused column of the skin shows as a black
                    // stripe down the outside of it.
                    new kr.toxicity.model.api.profile.ModelProfileSkin(
                            java.net.URI.create(skinUrl), null, slim, null));
            kr.toxicity.model.api.BetterModel.platform().skinManager()
                    .complete(profile.asUncompleted())
                    .thenAccept(skin -> at.getWorld().getServer().execute(
                            () -> build(plugin, phys, at, skin, kit == null ? Kit.NONE : kit,
                                    lifetimeTicks, blow == null ? Blow.NONE : blow, remains, slim,
                                    seamPixels, owner)))
                    .exceptionally(t -> null);
            return remains;
        } catch (Throwable t) {
            refused(plugin, "skin lookup failed: " + t, at);
            return null;
        }
    }

    /** Lays the six limbs out where the body stood, hands them to the physics, joins them. */
    private static void build(Host plugin, PhysicsWorld phys, Location at, SkinData skin,
                              Kit kit, int lifetimeTicks, Blow blow, Remains remains, boolean slim,
                              double seamPixels, java.util.UUID owner) {
        if (skin == null) { refused(plugin, "skin never arrived", at); return; }
        PlayerArmor armour = armourOf(kit);
        // Looked up once rather than per re-dress: a corpse should be the body its owner had
        // when they fell, not whatever they have changed it to since.
        remains.shape = null;
        // How far each inset layer pulls a piece in, as a share of the limb's own width.
        double inset = Math.max(0, plugin.getConfig().getDouble("corpse.side-inset-percent", 0.1)) / 100.0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // How the body as a whole is moving at the moment it stops being alive: tumbling
        // about a mostly horizontal axis, so it goes over rather than spinning on the spot.
        Vector along = blow.push().lengthSquared() > 1.0e-4
                ? blow.push().clone().normalize()
                : new Vector(rng.nextDouble(-1, 1), 0, rng.nextDouble(-1, 1));
        Vector spin = new Vector(-along.getZ(), 0, along.getX())
                .multiply(rng.nextDouble(TUMBLE_MIN, TUMBLE_MAX));
        spin.setY(rng.nextDouble(-1.5, 1.5));
        Vector drift = new Vector(0, rng.nextDouble(0.6, 2.0), 0)
                .add(blow.push()).add(sideways(rng));
        double scatter = switch (blow.wound()) {
            case INTACT -> 0.0;
            case LIMBS -> 2.4;
            case CHUNKS -> 4.0;
            case SHATTERED -> 6.0;
        };

        // Which way the body was facing. Everything below is written in the model's own
        // frame - arms at ±x, face at +z - so the yaw has to be applied to every offset, every
        // socket and every joint axis, and to the pieces themselves. Without it a corpse always
        // lay the same way round however its owner had been standing.
        org.joml.Quaternionf attitude = new org.joml.Quaternionf()
                .rotateY((float) Math.toRadians(-at.getYaw()));

        Map<String, PhysicsBody> made = new LinkedHashMap<>();
        List<BodyPlan.Part> skeleton = BodyPlan.skeleton(slim);
        int corpse = phys.corpseGroup();
        int index = 0, drawn = 0, missing = 0;
        for (BodyPlan.Part part : skeleton) {
            Location seat = at.clone().add(turn(attitude, new Vector(part.x(), part.y(), part.z())));
            Vector arm = turn(attitude, new Vector(part.x(), part.y() - BodyPlan.PIVOT_Y, part.z()));
            // Exactly the velocity this piece had while the body was still one rigid
            // thing: v = V + w x r. Anything else and the joints open their first step
            // already violated, which looks like a corpse shuddering as it lands.
            Vector kick = drift.clone().add(spin.getCrossProduct(arm));
            if (scatter > 0 && arm.lengthSquared() > 1.0e-4) {
                kick.add(arm.clone().normalize().multiply(scatter * rng.nextDouble(0.6, 1.4)));
            }
            PhysicsBody body = phys.createLimb(seat,
                    new Vector(part.hx(), part.hy(), part.hz()), part.mass(),
                    kick, spin, lifetimeTicks, corpse, index++);
            if (body == null) break;                    // out of bodies; leave the rest
            body.face(attitude);

            drawn += dress(plugin, on(body), part, skin, armour, inset, seamPixels, remains.shape);
            // What this limb is carrying falls out of it as it crumbles, where it crumbled.
            String limb = part.name();
            remains.whole++;
            body.onGone(gone -> {
                remains.shed(plugin, limb, gone);
                remains.sweep(gone.location());
            });
            remains.add(part.name(), body);
            made.put(part.name(), body);
        }
        remains.skin = skin;
        remains.seam = seamPixels;
        remains.inset = inset;
        remains.kit = kit;

        // Whatever was in its hand, still in it.
        hang(plugin, made.get("right_arm"), skeleton, kit.hand());
        don(plugin, made.get("head"), kit.helmet());

        int joined = 0;
        for (BodyPlan.Joint joint : BodyPlan.joints(slim)) {
            if (blow.wound().ordinal() >= joint.breaksAt().ordinal()) continue;
            PhysicsBody parent = made.get(joint.parent());
            PhysicsBody child = made.get(joint.child());
            if (parent == null || child == null) continue;
            Location socket = at.clone().add(turn(attitude, new Vector(joint.x(), joint.y(), joint.z())));
            if (phys.joinSwing(parent, child, socket,
                    turn(attitude, joint.twistParent()), turn(attitude, joint.planeParent()),
                    turn(attitude, joint.twistChild()), turn(attitude, joint.planeChild()),
                    joint.across(), joint.along(), joint.twist()) != null) {
                joined++;
            }
        }
        if (!reported) {
            reported = true;
            plugin.getLogger().info("RAGDOLL_READY limbs=" + made.size() + "/" + skeleton.size() + (slim ? " slim" : " classic")
                    + " pieces=" + drawn + " joints=" + joined + " missing=" + missing + " seam=" + seamPixels + " inset=" + (inset * 100) + "%"
                    + " terrain=" + phys.terrainCount());
        }
    }

    /**
     * Hangs the pictures of one limb on the body that is being that limb.
     *
     * <p>Read off the body rather than handed a place: the body knows where it is and which way
     * it is facing, which is the same answer when it has just been made and the right one when
     * it has been lying on a floor for ten minutes. That is what lets a corpse be re-dressed.
     *
     * @return how many pieces went on, for the one line that says whether a body came out whole
     */
    private static int dress(Host plugin, kr.gcb.dungeon.physics.Mount body,
                             BodyPlan.Part part, SkinData skin,
                             PlayerArmor armour, double inset, double seamPixels,
                             Object shape) {
        Location seat = body.seat();
        org.joml.Quaternionf attitude = body.attitude();
        int drawn = 0;
        {
            // Where the chest slab hangs, which is also where the breasts hang - they are baked
            // against that same bone pivot. Picked up in the loop rather than looked up again,
            // so there is one answer rather than two that can drift apart.
            double chestPivot = Double.NaN;
            for (BodyPlan.Piece piece : part.pieces()) {
                if (piece.slice().equals("chest")) chestPivot = piece.pivotY();
                Slice cut = itemFor(skin, armour, piece.slice());
                if (cut == null) { complain(plugin, piece.slice()); continue; }
                // At the bone pivot, not the middle of the drawing: BetterModel bakes each
                // slice's geometry relative to its pivot, so a display put anywhere else
                // moves the limb by however far those two are apart.
                //
                // A skin pixel is a sixteenth of a block at the model's own 0.9375 scale.
                double gap = Math.signum(piece.along()) * seamPixels * (0.9375 / 16.0);
                Vector offset = new Vector(piece.pivotX(), piece.pivotY() + gap, 0);
                // Placed turned, attached untuned: the rider's offset is in the body's own
                // frame and is turned again by the body's attitude every tick.
                Location where = seat.clone().add(turn(attitude, offset));
                // Sides only: the height has to stay exact or the slabs of a torso stop
                // meeting, and the sides are where the flicker lives. A fraction of the limb
                // rather than a fixed distance, so it stays a whisker at any body size.
                float side = (float) (1 - piece.layer() * inset);
                org.joml.Vector3f scale = new org.joml.Vector3f(
                        cut.scale().x * side, cut.scale().y, cut.scale().z * side);
                // Packets first: a corpse that is only ever looked at does not need to be
                // twelve entities for the tracker to walk every tick for every player.
                Puppet shown = Puppets.limb(where, cut.item(), scale);
                if (shown == null) { complain(plugin, piece.slice()); continue; }
                body.hang(shown, offset, null);
                drawn++;
            }
            if (!Double.isNaN(chestPivot)) {
                drawn += bust(body, skin, armour, shape, seat, attitude, chestPivot);
            }
        }
        return drawn;
    }

    /**
     * Dresses anything that will hold still, by the same arithmetic a corpse is dressed by.
     *
     * <p>The only door {@link kr.gcb.dungeon.model.Statue} needs, and deliberately the only
     * one: a standing body that was assembled by its own loop would drift from the falling one
     * and stop being worth looking at.
     */
    public static int drape(Host plugin, kr.gcb.dungeon.physics.Mount mount, BodyPlan.Part part,
                            SkinData skin, PlayerArmor armour, double inset, double seamPixels,
                            Object shape) {
        return dress(plugin, mount, part, skin, armour, inset, seamPixels, shape);
    }

    /**
     * Fetches and bakes one skin, off the main thread, and hands it back on it.
     *
     * <p>Lifted out of {@link #spawn} because a standing body needs exactly the same thing and
     * the alternative is a second copy of a profile dance that has already been got wrong once
     * - the slim flag in particular, which if it is dropped draws a three pixel arm on a four
     * pixel model and shows the unused column of the skin as a black stripe.
     */
    public static void skinFor(Host plugin, String skinUrl, boolean slim,
                               java.util.function.Consumer<SkinData> then) {
        if (skinUrl == null || skinUrl.isBlank()) { then.accept(null); return; }
        try {
            var profile = kr.toxicity.model.api.profile.ModelProfile.of(
                    new kr.toxicity.model.api.profile.ModelProfileInfo(
                            java.util.UUID.randomUUID(), "corpse"),
                    new kr.toxicity.model.api.profile.ModelProfileSkin(
                            java.net.URI.create(skinUrl), null, slim, null));
            kr.toxicity.model.api.BetterModel.platform().skinManager()
                    .complete(profile.asUncompleted())
                    .thenAccept(skin -> plugin.runOnMain(() -> then.accept(skin)))
                    .exceptionally(broken -> {
                        plugin.runOnMain(() -> then.accept(null));
                        return null;
                    });
        } catch (Throwable broken) {
            then.accept(null);
        }
    }

    /**
     * The figure alone, on something that is not a corpse (LiveBust: a living body's chest bone).
     * The seat given is the chest bone's own pivot, so nothing is added to lift it there.
     */
    public static int bustOn(kr.gcb.dungeon.physics.Mount mount, SkinData skin, Kit kit, Object shape,
                             Location seat, org.joml.Quaternionf attitude) {
        return bust(mount, skin, armourOf(kit), shape, seat, attitude, 0.0);
    }

    /** The armour a kit comes to, for anything outside this file that has to draw one. */
    public static PlayerArmor armourFor(Kit kit) { return armourOf(kit); }

    /**
     * Two more slices, for a body whose owner said it has them.
     *
     * <p>Hung from inside {@link #dress} rather than beside it so that re-dressing a corpse -
     * which is how armour is taken off one - takes these with it: {@code undress} drops
     * everything attached and this puts back what belongs. Done any other way, stripping a body
     * would leave them behind and stripping it twice would leave two.
     *
     * <p>Packets only, never a real display. A real one cannot be hidden from one person and
     * shown to another without an entity per viewer, so the fallback here is to draw nothing -
     * which is the right failure. Nobody is owed this drawing, and showing it to somebody who
     * did not install anything to see it is worse than not showing it at all.
     */
    private static int bust(kr.gcb.dungeon.physics.Mount body, SkinData skin, PlayerArmor armour,
                            Object shape, Location seat,
                            org.joml.Quaternionf attitude, double chestPivot) {
        return 0;
    }

    /** A model's sixteen units are five eighths of a block on a living head, and the body is drawn at fifteen sixteenths. */
    private static final float HAT_SCALE = 0.625f * 0.9375f;

    /**
     * Whether something worn on the head is drawn as its own item model rather than as a helmet's texture: it names a model
     * and is equippable with no armour texture. (Graywater's headgear: caps, masks, goggles, helmets.)
     */
    static boolean ownModel(ItemStack worn) {
        if (worn == null || worn.isEmpty() || !worn.has(DataComponents.ITEM_MODEL)) return false;
        var fit = worn.get(DataComponents.EQUIPPABLE);
        return fit != null && fit.assetId().isEmpty();
    }

    /**
     * Whatever was on its head, still on it: the same display trick as the hand, sized and placed as the game draws an item
     * in the head slot, so it rolls and comes off with the head.
     */
    private static void don(Host plugin, PhysicsBody head, ItemStack worn) {
        if (head == null || head.isDead() || !ownModel(worn)) return;
        Location at = head.location();
        if (at == null || at.getWorld() == null) return;
        Puppet hat = Puppets.limb(at, worn.copy(), new org.joml.Vector3f(HAT_SCALE), PlatformItemTransform.HEAD);
        if (hat != null) head.attach(hat, new Vector(0, 0, 0));
    }

    /**
     * Whatever was in its hand, still in it.
     *
     * <p>The same trick the modelled bodies use: a display hung on the arm at the far end of
     * it, so it swings and lands with the arm rather than being dropped on the floor beside it.
     * Nothing is hung when the hand is empty, which is also how a weapon somebody has taken off
     * the corpse stops being drawn on it.
     */
    private static void hang(Host plugin, PhysicsBody arm, List<BodyPlan.Part> skeleton, ItemStack held0) {
        if (arm != null && held0 != null && !held0.isEmpty()) {
            Location at = arm.location();
            org.joml.Quaternionf attitude = arm.attitude();
            BodyPlan.Part limb = null;
            for (BodyPlan.Part part : skeleton) if (part.name().equals("right_arm")) limb = part;
            double drop = limb == null ? 0.35 : limb.hy();
            Vector grip = turn(attitude, new Vector(0, -drop, 0));
            Location fist = at.clone().add(grip);
            ItemStack held = held0.copy();
            // Turned to lie along the arm. The hand transform is written for a hand bone and
            // this is hung on the arm box itself, so the item comes out across the wrist rather
            // than in line with it - "손에 든 막대 돌아가있네". All three angles are settings
            // because the right numbers are a matter of looking at it, and the one that does
            // most of the work turned out to be the yaw. Degrees; a fresh corpse picks them up
            // without a restart.
            float yaw = (float) Math.toRadians(plugin.getConfig().getDouble("corpse.hand-yaw", -90));
            float pitch = (float) Math.toRadians(plugin.getConfig().getDouble("corpse.hand-pitch", 0));
            float roll = (float) Math.toRadians(plugin.getConfig().getDouble("corpse.hand-roll", 0));
            Puppet inHand = Puppets.limb(fist, held, new org.joml.Vector3f(1f), PlatformItemTransform.THIRDPERSON_RIGHTHAND);
            if (inHand != null) {
                arm.attach(inHand, new Vector(0, -drop, 0), new org.joml.Quaternionf().rotateY(yaw).rotateX(pitch).rotateZ(roll));
            }
        }
    }



    private static final double TUMBLE_MIN = 1.5;
    private static final double TUMBLE_MAX = 3.5;

    private static boolean reported;

    private static final java.util.Set<String> complained = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Says so, once, when a piece of the plan has no slice behind it.
     *
     * <p>This is the failure that produced a corpse with the middle of its torso missing
     * and said nothing at all about it: the switch below was ported from a version with no
     * waist, so the waist resolved to null and was quietly skipped. A hole in a body is not
     * something to find out about from a screenshot.
     */
    private static void complain(Host plugin, String slice) {
        if (complained.add(slice)) {
            plugin.getLogger().warn("RAGDOLL_NO_SLICE " + slice
                    + " - BetterModel gave nothing for that piece, so the body has a hole there");
        }
    }

    /** One of the model's own vectors, turned into the world by the body's bearing. */
    private static Vector turn(org.joml.Quaternionf attitude, Vector own) {
        org.joml.Vector3f spun = attitude.transform(new org.joml.Vector3f(
                (float) own.getX(), (float) own.getY(), (float) own.getZ()));
        return new Vector(spun.x, spun.y, spun.z);
    }

    private static Vector sideways(ThreadLocalRandom rng) {
        return new Vector(rng.nextDouble(-0.6, 0.6), 0, rng.nextDouble(-0.6, 0.6));
    }

    /**
     * A physics body, seen as something drawings hang on.
     *
     * <p>Here rather than on {@code PhysicsBody} itself because the dependency would point the
     * wrong way: the physics does not need to know that anything draws.
     */
    static kr.gcb.dungeon.physics.Mount on(PhysicsBody body) {
        return new kr.gcb.dungeon.physics.Mount() {
            @Override public Location seat() { return body.location(); }
            @Override public org.joml.Quaternionf attitude() { return body.attitude(); }
            @Override public void hang(Puppet puppet, Vector offset, org.joml.Quaternionf tilt) {
                body.attach(puppet, offset, tilt);
            }
            @Override public void hang(Puppet puppet, Vector offset, org.joml.Quaternionf tilt,
                                       kr.gcb.dungeon.physics.Sway sway) {
                body.attach(puppet, offset, tilt, sway);
            }
        };
    }

    /** One slice of skin, with the scale BetterModel means it to be drawn at. */
    private record Slice(ItemStack item, org.joml.Vector3f scale) {}

    /**
     * The slice of skin for one piece.
     *
     * <p>Every name {@link BodyPlan} uses has to appear here. It is worth being loud about
     * a miss: the body was ported from a version that had no waist, this switch came with
     * it, and a corpse then went out with the middle of its torso simply absent - no error,
     * no warning, just a hole where a slab of the man should be.
     */
    private static Slice itemFor(SkinData skin, PlayerArmor armour, String part) {
        try {
            var transformed = switch (part) {
                case "head" -> skin.head(armour);
                case "chest" -> skin.chest(armour);
                // Gazadry's BetterModel fork also cuts "right_breast" / "left_breast" here (SkinData.rightBreast/leftBreast).
                // Upstream BetterModel has no such parts, and nothing in this copy asks for them (bust() draws nothing).
                case "waist" -> skin.waist(armour);
                case "hip" -> skin.hip(armour);
                case "left_arm" -> skin.leftArm(armour);
                case "left_forearm" -> skin.leftForeArm();
                case "right_arm" -> skin.rightArm(armour);
                case "right_forearm" -> skin.rightForeArm();
                case "left_leg" -> skin.leftLeg(armour);
                case "left_foreleg" -> skin.leftForeLeg(armour);
                case "right_leg" -> skin.rightLeg(armour);
                case "right_foreleg" -> skin.rightForeLeg(armour);
                default -> null;
            };
            if (transformed == null || transformed.isAir()) return null;
            var wrapped = transformed.itemStack();
            if (wrapped instanceof ModItemStack mod) {
                return new Slice(mod.source(), new org.joml.Vector3f(transformed.scale()));
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Turns a kit into the armour BetterModel draws over the skin.
     *
     * <p>A corpse in its underwear beside the men it fell in with reads as somebody else
     * entirely. Leather keeps its dye, which is how anybody tells them apart.
     */
    private static PlayerArmor armourOf(Kit kit) {
        PlayerArmor none = PlayerArmor.EMPTY;
        if (kit == null) return none;
        // Headgear that is a model of its own is hung on the head as that model (don), not baked on as a helmet.
        ArmorItem helmet = ownModel(kit.helmet()) ? null : armourPiece(kit.helmet());
        ArmorItem chest = armourPiece(kit.chest());
        ArmorItem legs = armourPiece(kit.legs());
        ArmorItem boots = armourPiece(kit.boots());
        if (helmet == null && chest == null && legs == null && boots == null) return none;
        return new PlayerArmor() {
            @Override public ArmorItem helmet() { return helmet != null ? helmet : none.helmet(); }
            @Override public ArmorItem chestplate() { return chest != null ? chest : none.chestplate(); }
            @Override public ArmorItem leggings() { return legs != null ? legs : none.leggings(); }
            @Override public ArmorItem boots() { return boots != null ? boots : none.boots(); }
        };
    }

    /**
     * One worn piece, in the terms BetterModel bakes into a skin slice.
     *
     * <p>It wants the two halves of a trim separately and names them the other way round
     * from Bukkit: its "trim" is the pattern and its "palette" is the material.
     */
    private static ArmorItem armourPiece(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        // A garment with a texture of its own (Graywater's jackets, vests, trousers, boots) is baked from the armour set of
        // the same name under BetterModel/armors, which make_pack.py writes beside the pack.
        String name = BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
        String material =
                  name.startsWith("leather_") ? "leather"
                : name.startsWith("chainmail_") ? "chainmail"
                : name.startsWith("iron_") ? "iron"
                : name.startsWith("golden_") ? "gold"
                : name.startsWith("diamond_") ? "diamond"
                : name.startsWith("netherite_") ? "netherite"
                : null;
        if (material == null) return null;
        int tint = -1;
        var dyed = item.get(DataComponents.DYED_COLOR);
        if (dyed != null) tint = dyed.rgb() & 0xFFFFFF;
        String pattern = null, palette = null;
        var trim = item.get(DataComponents.TRIM);
        if (trim != null) {
            pattern = trim.pattern().unwrapKey().map(k -> k.identifier().getPath()).orElse(null);
            palette = trim.material().unwrapKey().map(k -> k.identifier().getPath()).orElse(null);
            if (pattern == null || palette == null) { pattern = null; palette = null; }
        }
        return new ArmorItem(tint, material, pattern, palette);
    }

    private static long saidAt;

    private static void refused(Host plugin, String why, Location at) {
        long now = System.currentTimeMillis();
        if (now - saidAt < 5000) return;
        saidAt = now;
        plugin.getLogger().info("RAGDOLL_REFUSED " + why + " at " + at.getBlockX() + ","
                + at.getBlockY() + "," + at.getBlockZ());
    }

    private static boolean nobodyWatching(Location at) {
        for (net.minecraft.server.level.ServerPlayer viewer : at.getWorld().players()) {
            if (viewer.distanceToSqr(at.getX(), at.getY(), at.getZ()) <= VIEW_RANGE * VIEW_RANGE) return false;
        }
        return true;
    }
}
