package kr.gcb.ragdoll;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import kr.gcb.dungeon.model.Corpses;
import kr.gcb.dungeon.model.Puppets;
import kr.gcb.dungeon.model.Shoves;
import kr.gcb.dungeon.physics.JoltNatives;
import kr.gcb.dungeon.physics.PhysicsWorld;
import kr.gcb.ragdoll.compat.Host;
import kr.gcb.ragdoll.compat.Location;
import kr.gcb.ragdoll.compat.Skins;
import kr.gcb.ragdoll.compat.Vector;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Fabric side of RemoteRagdoll-BetterModel: a dead player leaves a body in their own skin, six pieces on
 * five joints, simulated by Jolt and drawn from BetterModel's skin slices with packets only.
 *
 * <p>This class is the port of the Bukkit plugin class ({@code BetterModelRagdoll}); the ragdoll itself is
 * {@code kr.gcb.dungeon}, carried over with its Bukkit types swapped for Minecraft's own.
 */
public final class RemoteRagdollFabric implements ModInitializer {
    public static final String MOD_ID = "remoteragdoll";
    public static final Logger LOGGER = LoggerFactory.getLogger("RemoteRagdoll");

    /** The skin a body gets when the player has none of their own (offline mode): the classic Steve. */
    private static final String PLAIN_SKIN =
            "http://textures.minecraft.net/texture/31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb";

    private static RemoteRagdollFabric instance;

    private final Map<ServerLevel, PhysicsWorld> worlds = new HashMap<>();
    /** The last hit each player took: which way it pushed, how hard it was, and on which tick. */
    private final Map<UUID, Blow> blows = new HashMap<>();
    /** Where each player stood last tick, for walking into bodies. */
    private final Map<UUID, Vec3> lastSeen = new HashMap<>();
    private Host host;
    private boolean ready;

    private record Blow(Vector push, double damage, int tick) {}

    @Override
    public void onInitialize() {
        instance = this;
        Path dir = FabricLoader.getInstance().getConfigDir();
        host = new Host(LOGGER, dir.resolve(MOD_ID), settings(dir.resolve(MOD_ID + ".properties")));

        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLevelEvents.UNLOAD.register((server, level) -> {
            PhysicsWorld physics = worlds.remove(level);
            if (physics != null) physics.clearAll();
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register(this::onHit);
        ServerLivingEntityEvents.AFTER_DEATH.register(this::onDeath);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            blows.remove(handler.player.getUUID());
            lastSeen.remove(handler.player.getUUID());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(
                Commands.literal("ragdoll").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(c -> dropOf(c, c.getSource().getPlayerOrException()))
                        .then(Commands.literal("clear").executes(this::clear))
                        .then(Commands.literal("at")
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(c -> dropAt(c, c.getSource().getLevel()))
                                                        .then(Commands.argument("world", DimensionArgument.dimension())
                                                                .executes(c -> dropAt(c, DimensionArgument.getDimension(c, "world"))))))))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(c -> dropOf(c, EntityArgument.getPlayer(c, "player"))))));
    }

    /** config/remoteragdoll.properties, written from the defaults in the jar the first time. */
    private static Properties settings(Path file) {
        Properties settings = new Properties();
        try {
            if (!Files.exists(file)) {
                try (InputStream defaults = RemoteRagdollFabric.class.getResourceAsStream("/remoteragdoll-default.properties")) {
                    if (defaults != null) {
                        Files.createDirectories(file.getParent());
                        Files.copy(defaults, file);
                    }
                }
            }
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    settings.load(in);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read {} - using the defaults", file, e);
        }
        return settings;
    }

    private void start(MinecraftServer server) {
        host.mainThread(server);
        if (!Corpses.available()) {
            LOGGER.warn("BetterModel is not installed - ragdolls are off");
            return;
        }
        if (!JoltNatives.isAvailable(host)) return;   // logged there
        ready = true;
        LOGGER.info("Ragdolls on (Jolt physics, BetterModel skin slices)");
    }

    private void stop() {
        for (PhysicsWorld physics : worlds.values()) physics.clearAll();
        worlds.clear();
        if (ready) Puppets.clear();
        blows.clear();
        lastSeen.clear();
        ready = false;
    }

    private void tick(MinecraftServer server) {
        if (!ready) return;
        // Walking into a body nudges it. Bukkit had a move event; here it is each player's travel since last tick.
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Vec3 now = p.position(), was = lastSeen.put(p.getUUID(), now);
            if (was == null || !(p.level() instanceof ServerLevel level)) continue;
            PhysicsWorld physics = worlds.get(level);
            if (physics == null || physics.trackedCount() == 0 || !Shoves.handsOn(p)) continue;
            Vec3 travel = now.subtract(was);
            // A teleport is not a shove.
            if (travel.lengthSqr() < 1.0e-6 || travel.lengthSqr() > 4.0) continue;
            Shoves.barge(physics, p, Vector.of(travel));
        }
        for (PhysicsWorld physics : worlds.values()) physics.tick();
    }

    private PhysicsWorld physics(ServerLevel world) {
        if (!ready || world == null) return null;
        return worlds.computeIfAbsent(world, w -> new PhysicsWorld(host, w));
    }

    /** Lays a body of {@code p} at {@code at}. False if it could not be made (the log says why). */
    private boolean lay(ServerPlayer p, Location at, double damage, double whole, Vector push) {
        PhysicsWorld physics = physics(at.getWorld());
        if (physics == null) return false;
        int ticks = Math.max(1, host.getInt("lifetime-seconds", 120)) * 20;
        try {
            if (Skins.of(p) != null) return Corpses.drop(host, physics, at, p, ticks, damage, whole, push);
            return Corpses.drop(host, physics, at, PLAIN_SKIN, false, ticks, p, damage, whole, push);
        } catch (Throwable t) {
            LOGGER.warn("Could not make a ragdoll", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ events

    private void onHit(LivingEntity entity, DamageSource source, float base, float taken, boolean blocked) {
        if (!ready || !(entity instanceof ServerPlayer p)) return;
        int now = p.level().getServer().getTickCount();
        Entity by = source.getDirectEntity();
        Vector away = by == null ? new Vector() : by instanceof Projectile shot
                ? Vector.of(shot.getDeltaMovement()).setY(0)
                : Vector.of(p.position()).subtract(Vector.of(by.position())).setY(0);
        if (away.lengthSquared() < 1.0e-4) {
            // Nothing to be pushed by (a fall, fire): only how hard it was is remembered.
            blows.put(p.getUUID(), new Blow(new Vector(), taken, now));
            return;
        }
        double whole = p.getMaxHealth();
        double speed = Math.min(4.0, 1.2 + taken / whole * 2.0);
        blows.put(p.getUUID(), new Blow(away.normalize().multiply(speed), taken, now));
    }

    private void onDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof ServerPlayer p)) return;
        Blow last = blows.remove(p.getUUID());
        if (!ready || !host.getBoolean("on-death", true)) return;
        int now = p.level().getServer().getTickCount();
        boolean fresh = last != null && now - last.tick() <= 20;
        Vector push = fresh ? last.push() : new Vector();
        double whole = p.getMaxHealth();
        double damage = fresh ? last.damage() : 0;
        // A shove that hard (an explosion, a charge) tears the body however little health it took.
        if (push.lengthSquared() > 36) damage = Math.max(damage, whole * 0.7);
        Location at = Location.of(p);
        if (at == null || !lay(p, at, damage, whole, push)) return;
        if (host.getBoolean("hide-dying-player", true)) {
            // The vanilla death animation would lie on top of the body. Other clients are told the player is
            // gone; respawning sends them a fresh one.
            ClientboundRemoveEntitiesPacket gone = new ClientboundRemoveEntitiesPacket(p.getId());
            for (ServerPlayer other : p.level().getServer().getPlayerList().getPlayers()) {
                if (other != p && other.level() == p.level()) other.connection.send(gone);
            }
        }
    }

    /** Called by the mixin when a player swings their arm. */
    public static void onSwing(ServerPlayer player) {
        RemoteRagdollFabric mod = instance;
        if (mod == null || !mod.ready || player == null) return;
        double kick = mod.host.getDouble("kick-strength", 1.0);
        PhysicsWorld physics = player.level() instanceof ServerLevel level ? mod.worlds.get(level) : null;
        if (physics == null || kick <= 0 || physics.trackedCount() == 0 || !Shoves.handsOn(player)) return;
        Shoves.kick(physics, player, kick);
    }

    /** Called by the mixin when a client reports the resource pack loaded. */
    public static void onPackLoaded(ServerPlayer player) {
        RemoteRagdollFabric mod = instance;
        if (mod != null && mod.ready && player != null) Puppets.repaint(player);
    }

    // ------------------------------------------------------------------ /ragdoll

    private boolean off(CommandContext<CommandSourceStack> c) {
        if (ready) return false;
        c.getSource().sendFailure(Component.literal("Ragdolls are off (BetterModel or the Jolt native is missing - see the log)."));
        return true;
    }

    private int clear(CommandContext<CommandSourceStack> c) {
        if (off(c)) return 0;
        int n = 0;
        for (PhysicsWorld physics : worlds.values()) {
            n += physics.trackedCount();
            physics.clearAll();
        }
        int cleared = n;
        c.getSource().sendSuccess(() -> Component.literal("Cleared " + cleared + " physics bodies."), false);
        return n;
    }

    private int dropAt(CommandContext<CommandSourceStack> c, ServerLevel world) {
        if (off(c)) return 0;
        Location where = new Location(world, DoubleArgumentType.getDouble(c, "x"), DoubleArgumentType.getDouble(c, "y"), DoubleArgumentType.getDouble(c, "z"));
        int ticks = Math.max(1, host.getInt("lifetime-seconds", 120)) * 20;
        boolean made;
        try {
            made = Corpses.drop(host, physics(world), where, PLAIN_SKIN, false, ticks, null, 0, 20, new Vector());
        } catch (Throwable t) {
            LOGGER.warn("Could not make a ragdoll", t);
            made = false;
        }
        if (made) c.getSource().sendSuccess(() -> Component.literal("Dropped a ragdoll at " + where.getBlockX() + " " + where.getBlockY() + " " + where.getBlockZ() + "."), false);
        else c.getSource().sendFailure(Component.literal("Could not make a ragdoll there (see the log)."));
        return made ? 1 : 0;
    }

    private int dropOf(CommandContext<CommandSourceStack> c, ServerPlayer whose) {
        if (off(c)) return 0;
        ServerPlayer self = c.getSource().getPlayer();
        Location at = Location.of(self != null ? self : whose);
        if (at == null) return 0;
        Vector ahead = at.getDirection().setY(0);
        if (ahead.lengthSquared() < 1.0e-4) ahead = new Vector(0, 0, 1);
        ahead.normalize();
        Location where = at.add(ahead.clone().multiply(2.0));
        boolean made = lay(whose, where, 0, whose.getMaxHealth(), ahead.multiply(1.5));
        if (made) c.getSource().sendSuccess(() -> Component.literal("Dropped a ragdoll of " + whose.getGameProfile().name() + "."), false);
        else c.getSource().sendFailure(Component.literal("Could not make a ragdoll here (see the log)."));
        return made ? 1 : 0;
    }
}
