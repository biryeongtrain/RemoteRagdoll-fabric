package kr.gcb.dungeon.model;

import kr.gcb.dungeon.physics.PhysicsWorld;
import kr.gcb.ragdoll.compat.Location;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import kr.gcb.ragdoll.compat.Host;
import kr.gcb.ragdoll.compat.Skins;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import kr.gcb.ragdoll.compat.Vector;

/**
 * The only door to the ragdoll, and the reason it is a soft dependency.
 *
 * <p>{@link Ragdoll} names BetterModel types in its own signatures, so a server without
 * BetterModel must never load that class at all - one touch and the JVM throws
 * NoClassDefFoundError somewhere far from the cause. The guard works because Java resolves
 * a class at the first call that actually runs: {@link #available()} names nothing from
 * BetterModel, and every method that does is only ever reached through it.
 */
public final class Corpses {

    private Corpses() {}

    public static boolean available() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("bettermodel");
    }

    /**
     * Drops a corpse of a player, wearing their own skin and kit.
     *
     * <p>Whether the skin is slim is read off the profile rather than assumed. A slim skin
     * has three pixel arms, and drawing one on the four pixel model leaves the unused
     * column of the texture showing as a black stripe down each arm.
     */
    public static boolean drop(Host plugin, PhysicsWorld physics, Location at, ServerPlayer who,
                               int lifetimeTicks, double damage, double maxHealth, Vector push) {
        return drop(plugin, physics, at, who, lifetimeTicks, damage, maxHealth, push,
                plugin.getConfig().getDouble("corpse.seam-pixels", 0.0));
    }

    /** The same, with the seams inside each limb prised apart by that many skin pixels. */
    public static boolean drop(Host plugin, PhysicsWorld physics, Location at, ServerPlayer who,
                               int lifetimeTicks, double damage, double maxHealth, Vector push,
                               double seamPixels) {
        if (!available() || physics == null || at == null || who == null) return false;
        Skins.Skin textures = Skins.of(who);
        if (textures == null) return false;
        boolean slim = textures.slim();
        return build(plugin, physics, at, textures.url(), slim, lifetimeTicks,
                who, damage, maxHealth, push, seamPixels);
    }

    /**
     * The same for anything else that wears a player skin - a mob, a summoned thing - given
     * the skin and which model it was cut for.
     */
    public static boolean drop(Host plugin, PhysicsWorld physics, Location at, String skinUrl,
                               boolean slim, int lifetimeTicks, LivingEntity was, double damage,
                               double maxHealth, Vector push) {
        if (!available() || physics == null || at == null) return false;
        return build(plugin, physics, at, skinUrl, slim, lifetimeTicks, was, damage, maxHealth, push,
                plugin.getConfig().getDouble("corpse.seam-pixels", 0.0));
    }

    /**
     * Puts the last body this dropped back on its feet, if it is still lying there.
     *
     * <p>Handed back as an Object so nothing outside has to name a BetterModel-bearing type;
     * the caller only ever passes it back here.
     */
    public static boolean rise(Host plugin, Object handle, int ticks,
                               java.util.function.Consumer<kr.gcb.ragdoll.compat.Location> then) {
        return rise(plugin, handle, ticks, 10, then);
    }

    /** The same, standing at attention for {@code hold} ticks before handing the spot back. */
    public static boolean rise(Host plugin, Object handle, int ticks, int hold,
                               java.util.function.Consumer<kr.gcb.ragdoll.compat.Location> then) {
        if (!available() || !(handle instanceof Ragdoll.Remains remains)) return false;
        return remains.rise(plugin, ticks, hold, then);
    }

    /** Where a dropped body's head has got to, or null. Handles are opaque; this reads one. */
    public static Location head(Object handle) {
        if (!available() || !(handle instanceof Ragdoll.Remains remains)) return null;
        return remains.head();
    }

    /** How far a dropped body's head is tipped sideways, in degrees; NaN when that has no answer. */
    public static double roll(Object handle) {
        if (!available() || !(handle instanceof Ragdoll.Remains remains)) return Double.NaN;
        return remains.roll();
    }

    /** Hides a dropped body's head from one player, or gives it back. */
    public static void hideHead(Object handle, net.minecraft.server.level.ServerPlayer from, boolean hidden) {
        if (!available() || !(handle instanceof Ragdoll.Remains remains)) return;
        remains.hideHead(from, hidden);
    }

    /** Separated so the class below is only resolved once BetterModel is known to be there. */
    private static boolean build(Host plugin, PhysicsWorld physics, Location at, String skinUrl,
                                 boolean slim, int lifetimeTicks, LivingEntity was, double damage,
                                 double maxHealth, Vector push, double seamPixels) {
        Ragdoll.Kit kit = was == null ? Ragdoll.Kit.NONE : Ragdoll.Kit.of(was);
        Ragdoll.Blow blow = new Ragdoll.Blow(BodyPlan.Wound.of(damage, maxHealth),
                push == null ? new Vector() : push);
        return Ragdoll.spawn(plugin, physics, at, skinUrl, slim, lifetimeTicks, kit, blow, seamPixels) != null;
    }

    /** The same, handing back the corpse so it can be raised or cleared later. */
    /**
     * Draws a body wearing this instead, without moving it.
     *
     * <p>What makes the corpse a container rather than a picture: take the coat out of the box
     * and the body stops wearing the coat, in the same tick, without so much as a twitch of the
     * physics. Null for a piece means nothing there.
     *
     * <p>Returns false when it could not be done - no BetterModel, a body already gone, or a
     * skin that never arrived - so the caller can fall back to whatever it did before.
     */
    public static boolean redress(Host plugin, Object handle, ItemStack helmet, ItemStack chest,
                                  ItemStack legs, ItemStack boots, ItemStack hand) {
        if (!available() || !(handle instanceof Ragdoll.Remains remains)) return false;
        return remains.wear(plugin, new Ragdoll.Kit(helmet, chest, legs, boots, hand));
    }

    /**
     * A body wearing a face nobody is behind.
     *
     * <p>{@link #dropAndKeep} builds a corpse from a live player, which is the only shape that
     * was ever needed while the only things that died were players. A hired companion has a
     * face out of the library and no client at all - see {@code town.Skins} - so the skin
     * arrives as a plain URL and everything else is the same.
     */
    public static Object dropWearing(Host plugin, PhysicsWorld physics, Location at,
                                     String skinUrl, boolean slim, int lifetimeTicks,
                                     Ragdoll.Kit kit, double damage, double maxHealth, Vector push,
                                     double seamPixels) {
        return dropWearing(plugin, physics, at, skinUrl, slim, lifetimeTicks, kit,
                damage, maxHealth, push, seamPixels, null);
    }

    /**
     * The same, for a body whose owner is known.
     *
     * <p>Which matters for one thing and it is not the skin: {@code Bust} keeps its settings by
     * player, and a body cut without an owner is a body those settings cannot be found for. A
     * corpse carried into the town used to arrive as somebody else's shape.
     */
    public static Object dropWearing(Host plugin, PhysicsWorld physics, Location at,
                                     String skinUrl, boolean slim, int lifetimeTicks,
                                     Ragdoll.Kit kit, double damage, double maxHealth, Vector push,
                                     double seamPixels, java.util.UUID owner) {
        if (!available() || physics == null || at == null || skinUrl == null) return null;
        Ragdoll.Blow blow = new Ragdoll.Blow(BodyPlan.Wound.of(damage, maxHealth),
                push == null ? new Vector() : push);
        return Ragdoll.spawn(plugin, physics, at, skinUrl, slim, lifetimeTicks,
                kit == null ? Ragdoll.Kit.NONE : kit, blow, seamPixels, owner);
    }

    /** Puts something into a corpse's hands, to be dropped where that hand ends up. */
    public static void leaveOn(Object handle, String limb, net.minecraft.world.item.ItemStack item) {
        if (available() && handle instanceof Ragdoll.Remains remains) remains.leave(limb, item);
    }

    /** Drags a body towards a point, for whoever is carrying it. */
    public static void haul(Object handle, kr.gcb.ragdoll.compat.Location target) {
        if (available() && handle instanceof Ragdoll.Remains remains) remains.haul(target);
    }

    /** Lets go of one. */
    public static void release(Object handle) {
        if (available() && handle instanceof Ragdoll.Remains remains) remains.release();
    }

    /** Where one actually is, as opposed to where it was put down. */
    public static Location middle(Object handle) {
        return available() && handle instanceof Ragdoll.Remains remains ? remains.middle() : null;
    }

    /** Moves a whole body by an offset, keeping the shape it is in. */
    public static int shift(Object handle, net.minecraft.server.level.ServerLevel world, Vector by) {
        return available() && handle instanceof Ragdoll.Remains remains
                ? remains.shift(world, by) : 0;
    }

    /** Takes a body away now, wherever it has got to. Safe on null and on one already gone. */
    public static void dispel(Object handle) {
        if (available() && handle instanceof Ragdoll.Remains remains) remains.dispel();
    }

    public static Object dropAndKeep(Host plugin, PhysicsWorld physics, Location at, ServerPlayer who,
                                     int lifetimeTicks, double damage, double maxHealth, Vector push,
                                     double seamPixels) {
        if (!available() || physics == null || at == null || who == null) return null;
        Skins.Skin textures = Skins.of(who);
        if (textures == null) return null;
        boolean slim = textures.slim();
        Ragdoll.Kit kit = Ragdoll.Kit.of(who);
        Ragdoll.Blow blow = new Ragdoll.Blow(BodyPlan.Wound.of(damage, maxHealth),
                push == null ? new Vector() : push);
        return Ragdoll.spawn(plugin, physics, at, textures.url(), slim,
                lifetimeTicks, kit, blow, seamPixels, who.getUUID());
    }
}
