/* Ported from TerritoryWar (io.lightest.tw.physics / .model), same author's project.
 * Changes: package, dungeon terrain collider instead of a flat ground plane, and a
 * ragdoll built from whole limbs rather than one body per bone. */
package kr.gcb.dungeon.model;

import kr.gcb.ragdoll.compat.Location;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import kr.toxicity.model.api.mod.platform.ModItemStack;
import kr.toxicity.model.api.mod.platform.ModLocation;
import kr.toxicity.model.api.mod.platform.ModPlayer;
import kr.toxicity.model.api.platform.PlatformItemTransform;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import kr.gcb.dungeon.physics.Puppet;

/**
 * Corpses drawn by packets rather than by entities.
 *
 * <p>A ragdoll was a dozen real {@code ItemDisplay} entities, and a battlefield with ten
 * corpses on it was a hundred and twenty of them. Real entities cost in ways that have
 * nothing to do with being looked at: they sit in the world's entity list, they are written
 * to and read from chunk files, and the entity tracker walks every one of them for every
 * player, every tick, deciding who can see what. That tracker is what crashed the server.
 *
 * <p>None of that is needed to put a shape in front of somebody. BetterModel already talks
 * to the client at the packet level - it is how it draws its own models - so a limb can be
 * a spawn packet, a stream of move packets and a remove packet, with nothing on the server
 * to track, save or clean up. If the plugin dies mid-battle the corpses vanish on the next
 * relog rather than lying about forever, which is the right failure.
 *
 * <p>Sent in one bundle per tick per viewer rather than one per limb: a hundred and twenty
 * limbs is a hundred and twenty packets either way, but bundling them is one flush instead
 * of a hundred and twenty, and the tick has to be paid for once.
 */
public final class Puppets {

    /** How far a corpse is worth sending. Beyond this a viewer is not told about it. */
    private static final double RANGE = 72.0;
    private static final double RANGE_SQ = RANGE * RANGE;

    /**
     * How many packets go in one bundle.
     *
     * <p>Well under the client's own ceiling. It refuses a bundle past a few thousand and
     * disconnects rather than dropping it, so the number that matters is not "the most it
     * will take" but "a number a battle cannot climb past".
     */
    private static final int BATCH = 256;

    private static final List<Packet> LIVE = new ArrayList<>();

    private Puppets() {}

    /**
     * A limb, as packets. Null when BetterModel is missing, so the caller falls back to a
     * real display and everything keeps working without it.
     */
    public static Puppet limb(Location at, ItemStack item, Vector3f scale) {
        return limb(at, item, scale, (PlatformItemTransform) null);
    }

    /**
     * @param held whether to draw it the way a hand holds it rather than flat.
     *
     * <p>A corpse's limb is a cut-out of a skin and wants no transform at all. A javelin
     * in flight is the same glaive that was in the man's hand a moment ago and has to
     * keep the pose it had there, or the weapon changes shape as it leaves - which is a
     * fault this projectile path has already had once, over the item model, and is not
     * worth having again over the transform.
     */
    public static Puppet limb(Location at, ItemStack item, Vector3f scale, boolean held) {
        return limb(at, item, scale, held ? PlatformItemTransform.THIRDPERSON_RIGHTHAND : null);
    }

    /** The same, shown the way an item display shows things in that context (in a hand, on a head ...). */
    public static Puppet limb(Location at, ItemStack item, Vector3f scale, PlatformItemTransform held) {
        if (!Corpses.available()) return null;
        try {
            return new Packet(at, item, scale, held);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Sends everything that moved this tick.
     *
     * <p>Called once from the physics tick, after every body has been stepped, so a limb
     * that moved three times in one tick is still sent once.
     */
    public static void flush(MinecraftServer server) {
        if (LIVE.isEmpty()) return;
        // One pass, not two lists and a removeAll.
        //
        // removeAll on an ArrayList is a scan of the removals for every element it keeps,
        // which was nothing at all while this list held corpses: a dozen limbs that live
        // half a minute and die together. Then the projectiles moved onto this road, and
        // they are the opposite - dozens created and destroyed every second - so the list
        // now churns constantly and the cost went quadratic in the number of dead ones.
        // It showed as corpses appearing late, because the flush that draws them was
        // spending its tick here first.
        LIVE.removeIf(p -> p.dead);
        if (LIVE.isEmpty()) return;

        // One bundle per world, because a viewer only ever needs what is in front of them
        // and the worlds do not share coordinates.
        for (ServerLevel w : server.getAllLevels()) {
            List<ServerPlayer> watchers = w.players();
            if (watchers.isEmpty()) continue;
            for (ServerPlayer viewer : watchers) {
                var seat = ModPlayer.of(viewer.connection);
                var bundler = kr.toxicity.model.api.BetterModel.nms().createBundler(BATCH);
                Location eye = Location.of(viewer);
                for (Packet p : LIVE) {
                    if (p.at == null || !w.equals(p.at.getWorld())) continue;
                    var only = p.only;
                    if (p.blind.contains(viewer.getUUID())
                            || (only != null && !only.test(viewer))) {
                        p.hideFrom(viewer, bundler);
                    } else if (eye.distanceSquared(p.at) > RANGE_SQ) {
                        p.hideFrom(viewer, bundler);
                    } else {
                        p.showTo(viewer, bundler);
                    }
                    // Sent in batches, because a bundle is not a bag you can put anything
                    // in: the client refuses one past a few thousand packets and drops the
                    // connection saying so. A battle leaves dozens of corpses of a dozen
                    // pieces apiece, each wanting a move and a transform, and one bundle
                    // for all of it disconnected everybody watching.
                    if (bundler.size() >= BATCH) {
                        bundler.send(seat);
                        bundler = kr.toxicity.model.api.BetterModel.nms().createBundler(BATCH);
                    }
                }
                if (bundler.isNotEmpty()) bundler.send(seat);
            }
        }
        // After every viewer, not during. See Packet.dirty.
        for (Packet p : LIVE) p.settle();
    }

    /**
     * How long a resting corpse may go unmentioned before it is said again.
     *
     * <p>Half a second. Two was the first figure and it was too long to watch: after a
     * pack reload the bodies on the ground sat wrong for long enough to read as broken
     * rather than as a hiccup.
     *
     * <p>Affordable because the limbs do not all speak at once - see the phase each one is
     * born with. Spread out, this costs a tenth of the corpses on the field per tick
     * whatever the interval is; bunched, it would be every limb of every body in the same
     * tick, four times a second, which is the shape of a stall.
     */
    private static final int REFRESH = 10;

    /**
     * Forgets that this player has seen any of it.
     *
     * <p>For the moment a client throws its rendering away and builds it again - which
     * here is a resource pack reload, and this game reloads the pack every time it starts.
     * The packets that made these corpses were sent once, and after a reload the client is
     * holding something between the pose it had and nothing at all. Since it is not the
     * client's fault and it cannot ask, it is simply told everything again.
     */
    public static void repaint(ServerPlayer viewer) {
        java.util.UUID id = viewer.getUUID();
        for (Packet p : LIVE) p.forget(id);
    }

    /** Everything still on screen goes, whatever state it was in. */
    public static void clear() {
        // BetterModel may already be going down, and its executors reject work once it is.
        // Failing to take a corpse back is a corpse that vanishes on the viewer's next
        // relog, which is not worth an exception on the way out.
        try {
            for (Packet p : List.copyOf(LIVE)) p.dispose();
        } catch (Throwable ignored) {
            // nothing left to do about it
        }
        LIVE.clear();
    }

    /**
     * The pair of bundles a transform wants.
     *
     * <p>The second half was passed as null, which reads as optional and is not: BetterModel
     * casts it on the way in, so every transform threw and no limb was ever told where it
     * was. The corpse existed, on eleven physics bodies, and the client had been sent a
     * spawn and nothing since - so what anybody saw was the sword landing on empty grass.
     */
    private static kr.toxicity.model.api.nms.AnimationBundler animation(
            kr.toxicity.model.api.nms.PacketBundler bundler) {
        return new kr.toxicity.model.api.nms.AnimationBundler(bundler,
                kr.toxicity.model.api.BetterModel.nms().createModAnimationBuilder(16));
    }

    /** One limb: a display that exists only as packets somebody has been sent. */
    private static final class Packet implements Puppet {

        private final kr.toxicity.model.api.nms.ModelDisplay display;
        private final java.util.Set<java.util.UUID> seenBy = new java.util.HashSet<>();
        private Location at;
        private Quaternionf spin = new Quaternionf();
        private Location sent;
        private Quaternionf sentSpin;
        private boolean dead;
        /**
         * Whether this pose still has to go out this tick, to anybody.
         *
         * <p>It used to be worked out per viewer against one shared "last sent", inside a
         * loop that runs the viewers on the outside. So the first player in the list got
         * the update, sent was written, and every player after them was told the corpse had
         * not moved. One person on the server never noticed; two people saw two different
         * fights, and the second one's dead lay wherever they had last happened to be
         * looking.
         */
        private boolean dirty = true;
        /**
         * Ticks since the pose was last put on the wire, for anybody.
         *
         * <p>Started somewhere at random inside the interval rather than at zero, so a
         * hundred limbs that died together do not all come due on the same tick for the
         * rest of their time on the ground.
         */
        private int quiet = java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(REFRESH);

        Packet(Location where, ItemStack item, Vector3f scale, PlatformItemTransform held) {
            this.at = where.clone();
            var platform = ModLocation.of(where.getWorld(), where.getX(), where.getY(), where.getZ());
            this.display = kr.toxicity.model.api.BetterModel.nms().create(platform, d -> {
                d.item(ModItemStack.of(item));
                if (held != null) d.display(held);
                d.brightness(15, 15);
                d.viewRange((float) (RANGE / 16.0));
                d.moveDuration(1);          // glide between ticks rather than lurching
            });
            LIVE.add(this);
            this.scale = new Vector3f(scale);
        }

        private final Vector3f scale;

        @Override
        public void place(Location where, Quaternionf turn) {
            if (dead) return;
            at = where.clone();
            spin = new Quaternionf(turn);
            if (moved()) dirty = true;
        }

        /**
         * Whether this is worth a packet.
         *
         * <p>A corpse rolls for a second or two and then lies there for half a minute, and
         * the physics goes on handing it the same position twenty times a second the whole
         * time. Without this every limb of every corpse on the field is two packets a tick
         * for as long as it lasts - which is most of the traffic, all of it saying nothing.
         */
        private boolean moved() {
            if (sent == null || sentSpin == null) return true;
            if (sent.distanceSquared(at) > 1.0e-4) return true;
            return Math.abs(sentSpin.x - spin.x) > 1.0e-3
                    || Math.abs(sentSpin.y - spin.y) > 1.0e-3
                    || Math.abs(sentSpin.z - spin.z) > 1.0e-3
                    || Math.abs(sentSpin.w - spin.w) > 1.0e-3;
        }

        @Override
        public void dispose() {
            if (dead) return;
            dead = true;
            if (seenBy.isEmpty()) return;
            var bundler = kr.toxicity.model.api.BetterModel.nms().createBundler(4);
            display.remove(bundler);
            for (java.util.UUID id : seenBy) {
                ServerPlayer viewer = at == null || at.getWorld() == null ? null : at.getWorld().getServer().getPlayerList().getPlayer(id);
                if (viewer != null) {
                    bundler.send(ModPlayer.of(viewer.connection));
                }
            }
            seenBy.clear();
        }

        @Override
        public boolean alive() { return !dead; }

        /** Spawns it for somebody who has not seen it, or moves it for somebody who has. */
        boolean showTo(ServerPlayer viewer, kr.toxicity.model.api.nms.PacketBundler bundler) {
            var where = ModLocation.of(at.getWorld(), at.getX(), at.getY(), at.getZ());
            if (seenBy.add(viewer.getUUID())) {
                // With its data, not bare. A spawn packet on its own creates an item
                // display holding nothing - the client is told a thing exists and never
                // told what it looks like - so the corpse was there and invisible.
                display.spawnWithEntityData(bundler);
                var first = display.createTransformer();
                first.transform(1, new Vector3f(), scale, spin, animation(bundler));
                first.sendTransformation(bundler);
                sent = at.clone();
                sentSpin = new Quaternionf(spin);
                return true;
            }
            if (!dirty) return false;
            display.teleport(where, bundler);
            var t = display.createTransformer();
            t.transform(1, new Vector3f(), scale, spin, animation(bundler));
            t.sendTransformation(bundler);
            return true;
        }

        /**
         * Done once the whole pass is over, rather than by whoever was served first.
         *
         * <p>Also the slow re-statement. A resting corpse is told to the client once and
         * then never mentioned again, and a client that rebuilds its renderer - a resource
         * pack reload is the common one, and this game reloads the pack on every restart -
         * comes back holding whatever it had, which is a limb at the wrong angle in the
         * wrong place. Nothing on the server ever corrects it, because as far as the
         * server is concerned that limb has not moved in thirty seconds.
         *
         * <p>So every so often it is said again. One transform per limb per two seconds is
         * nothing beside what the same limbs cost while they were still falling, and it
         * heals every way a client can lose track rather than the one way we can detect.
         */
        void settle() {
            if (dirty) {
                sent = at.clone();
                sentSpin = new Quaternionf(spin);
                dirty = false;
                quiet = 0;
            } else if (++quiet >= REFRESH) {
                dirty = true;
            }
        }

        /** Forgets that one viewer ever saw this, so the next pass builds it again. */
        void forget(java.util.UUID viewer) {
            seenBy.remove(viewer);
        }

        /** Whoever must not see this at all, however close they are standing. */
        final java.util.Set<java.util.UUID> blind = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public void blind(ServerPlayer viewer, boolean hidden) {
            if (hidden) blind.add(viewer.getUUID());
            else blind.remove(viewer.getUUID());
        }

        /** Or, the other way round: only these people, asked again every pass. */
        volatile java.util.function.Predicate<ServerPlayer> only;

        @Override
        public void onlyFor(java.util.function.Predicate<ServerPlayer> who) {
            only = who;
        }

        /** Takes it off the screen of somebody who has walked away from it. */
        boolean hideFrom(ServerPlayer viewer, kr.toxicity.model.api.nms.PacketBundler bundler) {
            if (!seenBy.remove(viewer.getUUID())) return false;
            display.remove(bundler);
            // Whoever is left still has the old position; the next spawn re-states it.
            sent = null;
            sentSpin = null;
            return true;
        }
    }
}
