/* Ported from TerritoryWar (io.lightest.tw.physics / .model), same author's project.
 * Changes: package, dungeon terrain collider instead of a flat ground plane, and a
 * ragdoll built from whole limbs rather than one body per bone. */
package kr.gcb.dungeon.physics;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.JobSystemThreadPool;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.TempAllocatorImpl;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import kr.gcb.ragdoll.compat.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import kr.gcb.ragdoll.compat.Host;
import kr.gcb.ragdoll.compat.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Jolt physics system bolted onto one Bukkit world.
 *
 * <p>Only what this game needs: box shapes, a static collider for the ground plane
 * and for wall tiles, and dynamic bodies for projectiles and debris. Jolt and
 * Minecraft share a coordinate system (Y-up, right-handed), so positions pass
 * straight through with no conversion.
 */
public final class PhysicsWorld {

    private static final int LAYER_STATIC = 0;
    private static final int LAYER_DYNAMIC = 1;
    /**
     * Corpse limbs, which collide with the ground, with other corpses, and never with the
     * rest of their own body.
     *
     * <p>That last exclusion is a requirement rather than a preference. The eleven boxes
     * of a body overlap where they join: an upper arm sits inside the shoulder it hangs
     * off, a thigh inside the hip. Let those pairs touch and Jolt opens the first step by
     * shoving each of them apart at whatever speed it takes to separate them, and the
     * corpse detonates the instant it appears.
     *
     * <p>The whole layer used to be excluded, which bought that safety at the price of
     * bodies that lay through one another: a line that broke in one place left a heap
     * occupying a single man's worth of ground. Jolt draws the distinction itself, so the
     * exclusion is now drawn where it belongs - a collision group per corpse, every pair
     * within a group disabled, and different groups left to pile up as they should.
     */
    private static final int LAYER_LIMB = 2;

    /**
     * Numbers a corpse by, so its own pieces know each other and nobody else's do.
     *
     * <p>Wraps, and may wrap onto a corpse still lying on the field. Two of them would
     * pass through each other rather than stack - one pair of bodies out of billions,
     * behaving the way every pair behaved until now.
     */
    private int nextCorpse = 1;

    /**
     * How many limbs of one corpse the exclusion table can hold.
     *
     * <p>Every limb of a body needs its own sub-group number, so this is a ceiling on how
     * detailed a corpse may be - far above the eleven a person breaks into or the score a
     * horse does. A body with more pieces than this spills into the next group id, which
     * is why {@link #corpseGroup} hands them out in blocks rather than one at a time.
     */
    private static final int LIMBS_PER_CORPSE = 64;

    /** Ids reserved per corpse, so a large one can spill without landing on the next. */
    private static final int GROUP_STRIDE = 16;

    /**
     * A fresh number for one corpse. Every limb of it is given the same one.
     *
     * <p>Kept per body rather than shared, so corpses do collide with one another. Making
     * them all ignore each other was tried and undone: it cured the heap and cost the thing
     * worth having, which is that a body flung into a row of the dead scatters them. The
     * heap is the price of that and it is a price worth paying - sixty men killed on one
     * square are allowed to lie on each other.
     */
    public int corpseGroup() {
        nextCorpse += GROUP_STRIDE;
        if (nextCorpse >= Integer.MAX_VALUE - GROUP_STRIDE) nextCorpse = 1;
        return nextCorpse;
    }

    /**
     * The rule that a corpse does not fight itself.
     *
     * <p>One sub-group, disabled against itself, which is all that is needed: Jolt only
     * consults this table when two bodies carry the same group id, so disabling the single
     * pair excludes every limb of a corpse from every other limb of that same corpse and
     * says nothing whatever about anybody else's.
     */
    private com.github.stephengold.joltjni.GroupFilterTableRef ownBody;

    /**
     * The rule that a corpse does not fight itself.
     *
     * <p>Every pair of sub-groups disabled, and each limb of a body given a different
     * one. Jolt consults this table only when two bodies carry the same group id, so a
     * blanket exclusion inside the table says "no limb of a corpse touches any other limb
     * of that corpse" and says nothing whatever about anybody else's.
     *
     * <p>It has to be pairs of <em>different</em> numbers. The table is the triangle above
     * the diagonal - n(n-1)/2 bits, nothing on the diagonal at all - so a table built for
     * one sub-group is zero bytes long, and disabling that group against itself writes to
     * the first byte of an empty array. That is not an exception; it is a write through a
     * dangling pointer inside joltjni.dll, and it took the server down twice.
     */
    private void excludeOwnBody(com.github.stephengold.joltjni.GroupFilterTable table) {
        for (int a = 0; a < LIMBS_PER_CORPSE; a++) {
            for (int b = a + 1; b < LIMBS_PER_CORPSE; b++) {
                table.disableCollision(a, b);
            }
        }
    }
    private static final int NUM_OBJ_LAYERS = 3;
    private static final int NUM_BP_LAYERS = 2;

    /**
     * How many bodies Jolt is allowed at once, per world.
     *
     * <p>Raised fourfold when corpses went from lasting eight seconds to thirty. A body
     * that has come apart is up to eleven of these, so a heavy engagement can put a couple
     * of thousand on the field and the old ceiling would have started refusing them
     * silently - a soldier simply not leaving a corpse, which reads as the ragdoll being
     * broken rather than as a limit being hit.
     *
     * <p>These are allocation sizes rather than a running cost: Jolt reserves the arrays
     * up front and simulates only what is actually in them, so the price of the headroom
     * is memory rather than frame time. The pairs and contacts have to keep pace with the
     * body count or the broad phase runs out of room to record collisions between them.
     */
    // 65536 (was 16384) so the terrain can have 40000 of them: see TERRAIN_BUDGET.
    private static final int MAX_BODIES = 65536;
    private static final int MAX_BODY_PAIRS = 16384;
    private static final int MAX_CONTACTS = 8192;

    /** Scratch space Jolt borrows each step. Generous on purpose: see the constructor. */
    private static final int TEMP_ARENA = 64 * 1024 * 1024;

    /** Minecraft gravity feels right here; real -9.81 looks like everything is floating. */
    private static final float GRAVITY = -22f;

    private final Host plugin;
    private final ServerLevel world;
    private final PhysicsSystem system;
    private final BodyInterface bodies;
    /** Allocator and job pool are per-world and long lived; making them per tick is a leak. */
    private final TempAllocatorImpl temp;
    private final JobSystemThreadPool jobs;

    /**
     * The three collision filters, kept alive for as long as the system that reads them.
     *
     * <p>PhysicsSystem.init stores pointers to these rather than copying them, and it reads
     * them on every step for the rest of the world\'s life. They were local variables in the
     * constructor, so the moment it returned nothing in Java referred to them any more and
     * they were free to be collected - at which point jolt-jni frees the native tables
     * underneath and Jolt goes on asking freed memory which layers collide. Whether that
     * answers yes or no depends on what the allocator has since put there, which is a bug
     * that waits for a garbage collection and then makes things fall through the floor.
     */
    private final ObjectLayerPairFilterTable objFilter;
    private final BroadPhaseLayerInterfaceTable bpMap;
    private final ObjectVsBroadPhaseLayerFilterTable bpFilter;

    private final List<PhysicsBody> tracked = new ArrayList<>();
    private final Map<Long, BoxShape> shapeCache = new HashMap<>();

    public PhysicsWorld(Host plugin, ServerLevel world) {
        this.plugin = plugin;
        this.world = world;

        // Built here rather than in a static block, with the rest of the native tables.
        //
        // A static initialiser runs when the class is first touched, and the class is
        // first touched on the way to constructing one of these - which is earlier than
        // Jolt's allocator, factory and type registry exist. Calling into the library
        // before those are up is not an exception, it is an access violation in
        // joltjni.dll that takes the JVM with it, and it did.
        var mine = new com.github.stephengold.joltjni.GroupFilterTable(LIMBS_PER_CORPSE);
        excludeOwnBody(mine);
        this.ownBody = mine.toRef();

        this.objFilter = new ObjectLayerPairFilterTable(NUM_OBJ_LAYERS);
        objFilter.enableCollision(LAYER_DYNAMIC, LAYER_DYNAMIC);
        objFilter.enableCollision(LAYER_DYNAMIC, LAYER_STATIC);
        objFilter.enableCollision(LAYER_STATIC, LAYER_DYNAMIC);
        // Ground only, in both directions, and deliberately nothing else.
        objFilter.enableCollision(LAYER_LIMB, LAYER_STATIC);
        objFilter.enableCollision(LAYER_STATIC, LAYER_LIMB);
        // Corpses stack. Which pieces are allowed to touch which is settled per body by
        // the collision group above, not here - this only says the layer is not exempt.
        objFilter.enableCollision(LAYER_LIMB, LAYER_LIMB);

        this.bpMap = new BroadPhaseLayerInterfaceTable(NUM_OBJ_LAYERS, NUM_BP_LAYERS);
        bpMap.mapObjectToBroadPhaseLayer(LAYER_STATIC, 0);
        bpMap.mapObjectToBroadPhaseLayer(LAYER_DYNAMIC, 1);
        bpMap.mapObjectToBroadPhaseLayer(LAYER_LIMB, 1);

        // Object layers first, broad phase layers second, in that order.
        //
        // Which is the opposite of what the shape of the call suggests - the broad phase
        // interface is the first argument, so the count beside it looks like it belongs to
        // it. It does not: the table is built by walking every object layer, and the count
        // it walks is this one. Passing the broad phase count there filled rows 0 and 1 and
        // left row 2 empty, so corpse limbs collided with nothing at all and fell through
        // the world - and there was no way to notice until then, because every count in
        // this constructor was 2 until a third object layer existed.
        this.bpFilter = new ObjectVsBroadPhaseLayerFilterTable(
                bpMap, NUM_OBJ_LAYERS, objFilter, NUM_BP_LAYERS);

        this.system = new PhysicsSystem();
        this.system.init(MAX_BODIES, 0, MAX_BODY_PAIRS, MAX_CONTACTS, bpMap, bpFilter, objFilter);
        this.system.setGravity(0f, GRAVITY, 0f);
        this.bodies = system.getBodyInterface();

        // Sized against the limits above, and it has to be.
        //
        // Jolt borrows from here during every update for the broad phase's pair list and
        // the contact constraint buffers, and both of those are sized from maxBodyPairs
        // and maxContactConstraints. Raising those two without raising this is how the
        // server died: the allocator ran out mid-step, and TempAllocatorImpl does not fail
        // softly - it walks off the end of its own arena, which takes the JVM with it and
        // leaves no Java exception and no crash dump behind. The four numbers move
        // together or not at all.
        this.temp = new TempAllocatorImpl(TEMP_ARENA);
        this.jobs = new JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 2);
    }

    public ServerLevel world() { return world; }
    public BodyInterface bodyInterface() { return bodies; }
    public Host plugin() { return plugin; }

    /**
     * Static collision for the blocks a corpse is about to land among.
     *
     * <p>TerritoryWar could put its whole world under one enormous flat box: a field is a
     * plane and nothing is ever under it. A dungeon is the opposite - three levels of
     * rooms stacked with rock between them, walls on every side and stairs between the
     * floors - so the collider has to be the blocks themselves, or a body would rest on
     * the level below through the floor it actually landed on.
     *
     * <p>Built a chunk section at a time and kept. Sections are stable and shared, so the
     * second corpse in a room pays nothing, and a dungeon never changes shape so nothing
     * ever has to be invalidated.
     */
    public void ensureTerrain(Location around) {
        if (around == null || around.getWorld() != world) return;
        int bx = around.getBlockX(), by = around.getBlockY(), bz = around.getBlockZ();
        for (int sx = (bx - REACH) >> 4; sx <= (bx + REACH) >> 4; sx++)
            for (int sy = (by - REACH) >> 4; sy <= (by + REACH) >> 4; sy++)
                for (int sz = (bz - REACH) >> 4; sz <= (bz + REACH) >> 4; sz++)
                    addSection(sx, sy, sz);
    }

    /** How far around a corpse the terrain is guaranteed, in blocks. */
    private static final int REACH = 10;

    /**
     * How far a body may travel before the floor is built ahead of it again.
     *
     * <p>Half the reach, so there is always at least this much solid ground in front of
     * wherever it is going.
     */
    private static final double FOLLOW = REACH / 2.0;

    /** How many times the floor has had to be extended under something moving. */
    private int follows;

    /**
     * How many times a live limb has been found in a place with no floor built.
     *
     * <p>Zero is the invariant. Anything else is the fault that has now been reported twice by
     * somebody playing - once as corpses "sinking", once as one arriving on the floor below -
     * and both times the report described where it ended up rather than what had gone wrong.
     * A number that counts the cause rather than the symptom is the difference between the
     * next occurrence taking ten minutes and taking an afternoon.
     */
    private int adrift;

    /**
     * Keeps ground under a body that has gone somewhere.
     *
     * <p>This is the half that was missing, and it is the whole of why a corpse ended up a
     * floor below. {@link #ensureTerrain} was called once, where the body was <em>spawned</em>,
     * and never again - so the world was solid for ten blocks around the spot somebody died
     * on and empty everywhere else. A body is not still at that spot: it is kicked as it is
     * made ({@code drift}, {@code spin}, and up to six blocks a second of {@code scatter} for
     * one that came apart), it slides, and it goes down stairs. The moment it crossed that
     * ten block line there was nothing under it at all, and a dungeon is rooms stacked with
     * rock between them - so it did not stop until the level below.
     *
     * <p>Cheap enough to ask every tick because it almost always answers immediately: the
     * distance check is three subtractions, and even when it does fire, every section it asks
     * for is already built and returns on a map lookup.
     */
    private void keepFloor(PhysicsBody b) {
        Location at = b.location();
        if (at == null || at.getWorld() != world) return;
        double[] had = b.floored;
        if (had != null) {
            double dx = at.getX() - had[0], dy = at.getY() - had[1], dz = at.getZ() - had[2];
            if (dx * dx + dy * dy + dz * dz < FOLLOW * FOLLOW) return;
            follows++;
        }
        b.floored = new double[]{at.getX(), at.getY(), at.getZ()};
        ensureTerrain(at);
    }

    /**
     * How many static boxes the terrain is allowed. Well under the body ceiling, which the
     * corpses themselves also draw on.
     */
    // 40000, was 7000. With a full server the bones lying round every party held 170-odd
    // sections between them - all 7000 boxes - and every one of those was somebody's floor, so
    // nothing could be given back and a player's corpse spawned over a floor that could not be
    // built (the user: 사망시 시체가 지하로 빠져버리는 버그). Static boxes cost Jolt memory, not
    // step time, and the body ceiling was raised with this.
    private static final int TERRAIN_BUDGET = 40000;

    /**
     * Which 16-cubes have been built, and what each one cost.
     *
     * <p>It used to be a plain set of keys, which meant a section could be built and never
     * unbuilt: the budget only ever went one way, and once it was gone the warning fired and
     * every corpse after that fell through the world. That is not a hypothetical - it is in
     * three of this server's own logs, one per session, and it is why ragdolls occasionally
     * sank into the floor.
     *
     * <p>Keeping the body ids per section is what makes the other direction possible. A
     * dungeon is larger than any budget; what matters is that there is floor where somebody
     * actually is, and the far side of the map can be given back.
     */
    private final java.util.Map<Long, int[]> sections = new java.util.HashMap<>();

    /** Where each built section is, in blocks, so the furthest from here can be found. */
    private final java.util.Map<Long, int[]> sectionAt = new java.util.HashMap<>();

    /** The section being built right now, so its boxes know where to be recorded. */
    private java.util.List<Integer> building;

    private int terrainBodies;
    private boolean terrainFull;
    private int evictions;

    /**
     * One 16-cube of the world, as static boxes.
     *
     * <p>Only blocks with air against them: the inside of a wall is a dozen blocks nothing
     * can ever touch, and paying for them would spend the whole budget on rock. Runs of
     * them along X are merged into one box, which is what turns a room floor from two
     * hundred bodies into a dozen.
     */
    private void addSection(int sx, int sy, int sz) {
        long key = (((long) sx & 0x3FFFFF) << 42) | (((long) sy & 0xFFFFF) << 22) | ((long) sz & 0x3FFFFF);
        if (sections.containsKey(key)) return;
        int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
        if (y0 + 15 < world.getMinY() || y0 > (world.getMaxY() + 1)) {
            sections.put(key, NOTHING);
            return;
        }
        // Loaded rather than skipped. Marking a section done and then skipping it because
        // its chunk happened to be out was how a corpse dropped from the console fell
        // through the floor: the section was never retried, so there was no floor.
        world.getChunk(sx, sz);
        // Room for it first. A dungeon is bigger than any budget, so the question is never
        // "can another section be afforded" but "which one can be done without" - and the
        // answer is the one furthest from the place being asked about.
        //
        // Until there is room, not once. One eviction used to be all it tried, and the section
        // it gave back was as often as not a slab of solid rock that had cost nothing - so the
        // build ran over anyway, was thrown away, and the next body to ask did the same thing:
        // a hundred and six warnings in one session, each of them a floor that was never built.
        //
        // And not again straight away once it has come up empty. With every built section under
        // some body or other (bones lying all over the map), each ask scanned every section
        // against every body and then built and threw away a whole section, for each of the
        // dozens of sections a moving body asks for, every tick: the server spent its ticks here
        // (the user: 섭렉지리는데). A second later something may have moved or gone.
        int now = world.getServer().getTickCount();
        if (terrainBodies + SECTION_WORST > TERRAIN_BUDGET && now < spentUntil) return;
        double[][] spots = null;
        while (terrainBodies + SECTION_WORST > TERRAIN_BUDGET) {
            if (spots == null) spots = spots();
            if (!evict(x0 + 8, y0 + 8, z0 + 8, spots)) break;
        }
        sections.put(key, NOTHING);
        sectionAt.put(key, new int[]{x0 + 8, y0 + 8, z0 + 8});
        building = new java.util.ArrayList<>();
        try {
            for (int[] box : blocks(x0, y0, z0)) {
                addTerrainBox(box[0], box[1], box[2], box[3], box[4], box[5]);
                if (terrainFull) return;
            }
        } finally {
            if (terrainFull) {
                // Cut short by the budget. Half a floor is worse than none: a body falls
                // through the missing half and the section is marked done, so it is never
                // tried again and the hole is permanent. Give back what this one managed
                // and forget it, so the next call - which will evict something first -
                // builds it whole.
                for (int at = 0; at < building.size(); at++) removeBody(building.get(at));
                terrainBodies = Math.max(0, terrainBodies - building.size());
                sections.remove(key);
                sectionAt.remove(key);
                spentUntil = world.getServer().getTickCount() + 20;
            } else {
                int[] owned = new int[building.size()];
                for (int at = 0; at < owned.length; at++) owned[at] = building.get(at);
                sections.put(key, owned);
            }
            building = null;
        }
    }

    private static final int[] NOTHING = new int[0];

    /**
     * The exposed blocks of one 16-cube, as few boxes as they will go into.
     *
     * <p>Rectangles in each layer first - as far along x as the run goes, then as many rows of
     * z as have exactly that run - and then a rectangle that sits on an identical one in the
     * layer below is the same box made one taller. The volume is the same solid it always was;
     * only the count changes. It used to be one box per run along x, which made a sixteen by
     * sixteen floor sixteen boxes and a wall running along z one box per block of it - and that
     * count is what ran the budget dry.
     *
     * @return each box as {fromX, fromY, fromZ, toX, toY, toZ}, inclusive block coordinates
     */
    java.util.List<int[]> blocks(int x0, int y0, int z0) {
        java.util.List<int[]> done = new java.util.ArrayList<>();
        // Rectangles still open from the layer below, by their footprint.
        java.util.Map<Long, int[]> open = new java.util.HashMap<>();
        int bottom = Math.max(y0, world.getMinY());
        int top = Math.min(y0 + 15, (world.getMaxY() + 1) - 1);
        for (int y = bottom; y <= top; y++) {
            boolean[][] want = new boolean[16][16];
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) want[dz][dx] = exposed(x0 + dx, y, z0 + dz);
            }
            java.util.Map<Long, int[]> next = new java.util.HashMap<>();
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    if (!want[dz][dx]) continue;
                    int wide = 1;
                    while (dx + wide < 16 && want[dz][dx + wide]) wide++;
                    int deep = 1;
                    grow:
                    while (dz + deep < 16) {
                        for (int at = dx; at < dx + wide; at++) if (!want[dz + deep][at]) break grow;
                        deep++;
                    }
                    for (int rz = dz; rz < dz + deep; rz++) {
                        for (int rx = dx; rx < dx + wide; rx++) want[rz][rx] = false;
                    }
                    long footprint = ((long) dx << 24) | ((long) dz << 16) | ((long) wide << 8) | deep;
                    int[] below = open.remove(footprint);
                    if (below != null) {
                        below[4] = y;                      // the same box, one taller
                        next.put(footprint, below);
                    } else {
                        next.put(footprint, new int[]{x0 + dx, y, z0 + dz,
                                x0 + dx + wide - 1, y, z0 + dz + deep - 1});
                    }
                }
            }
            // Whatever did not carry on upward is finished.
            done.addAll(open.values());
            open = next;
        }
        done.addAll(open.values());
        return done;
    }

    /**
     * How much of the budget one section could possibly want.
     *
     * <p>A guess, and deliberately a generous one: it decides when to make room rather than
     * how much room to make, so being wrong upward costs an eviction that was not needed yet.
     * Being wrong downward is the old bug.
     */
    private static final int SECTION_WORST = 400;

    /**
     * How close to something alive a section has to be before it may not be given back.
     *
     * <p>A section is sixteen blocks across and terrain is guaranteed for {@link #REACH}
     * around a body, so anything within a section's width of that could be holding it up.
     * Generous on purpose: being wrong here does not cost a wasted eviction, it drops
     * somebody through the world.
     */
    private static final double KEEP = REACH + 16 + 8;

    /**
     * How far this section is from the nearest thing that would miss it.
     *
     * <p>The place being built for, and every body currently in the air or on the ground.
     * The old version asked only the first of those, which is why it could free the floor a
     * corpse was lying on to pay for the floor under a corpse across the map.
     */
    private double lonely(int[] where, int wx, int wy, int wz, double[][] spots) {
        double least = Math.abs(where[0] - wx) + Math.abs(where[1] - wy) + Math.abs(where[2] - wz);
        for (double[] at : spots) {
            double away = Math.abs(where[0] - at[0])
                    + Math.abs(where[1] - at[1])
                    + Math.abs(where[2] - at[2]);
            if (away < least) least = away;
        }
        return least;
    }

    /**
     * Where every body is, read once for a whole round of evicting: asked per section, it was
     * three native calls per body per section - hundreds of thousands a tick with a few hundred
     * bones lying about.
     */
    private double[][] spots() {
        List<double[]> out = new ArrayList<>(tracked.size());
        for (PhysicsBody b : tracked) {
            Location at = b.location();
            if (at == null || at.getWorld() != world) continue;
            out.add(new double[]{at.getX(), at.getY(), at.getZ()});
        }
        return out.toArray(new double[0][]);
    }

    /** Until when the budget is known to be spent: no section is attempted before then. */
    private int spentUntil;

    /**
     * Gives back the terrain furthest from here, to pay for terrain here.
     *
     * <p>Whole sections. Half a room's floor is worse than none of it: a body can fall through
     * the gap, and nothing in the log would say why.
     *
     * <p>They are forgotten as well as freed, so walking back into that room rebuilds it. That
     * is the half the old code could not do at all - it marked a section done for the lifetime
     * of the world, which is why the budget only ever ran one way.
     */
    private boolean evict(int wx, int wy, int wz, double[][] spots) {
        long want = 0;
        double furthest = -1;
        for (var entry : sectionAt.entrySet()) {
            int[] where = entry.getValue();
            double away = lonely(where, wx, wy, wz, spots);
            // Somebody is standing on it. Freeing this one is not making room, it is taking
            // the floor out from under a body that is currently resting on it - which is the
            // very thing the budget exists to prevent.
            if (away < KEEP) continue;
            if (away > furthest) { furthest = away; want = entry.getKey(); }
        }
        // Nothing safe to give back: every section built is one something is standing on.
        // Then the budget really is spent and the warning is the honest answer.
        if (furthest < 0) return false;
        int[] freed = sections.remove(want);
        sectionAt.remove(want);
        if (freed == null) return true;
        for (int body : freed) removeBody(body);
        terrainBodies = Math.max(0, terrainBodies - freed.length);
        // Retractable now. It used to be a one-way latch, so a server that recovered went on
        // saying it was broken.
        terrainFull = false;
        evictions++;
        return true;
    }

    /**
     * Whether the floor has actually been built where this is.
     *
     * <p>The invariant the corpses depend on and nothing was ever able to ask about: a live
     * limb should always be inside a section that has been built. When it is not, it is in
     * empty space with nothing to land on, and what happens next is that it arrives on the
     * floor below - which is the bug this exists to catch rather than to describe.
     */
    public boolean terrainBuiltAt(Location where) {
        if (where == null || where.getWorld() != world) return false;
        long key = (((long) (where.getBlockX() >> 4) & 0x3FFFFF) << 42)
                | (((long) (where.getBlockY() >> 4) & 0xFFFFF) << 22)
                | ((long) (where.getBlockZ() >> 4) & 0x3FFFFF);
        return sections.containsKey(key);
    }

    /**
     * Whether the merged boxes are exactly the exposed blocks, around one spot.
     *
     * <p>Block by block: every exposed block inside exactly one box, nothing else inside any.
     * A merge that swallowed a doorway would be a wall across it, and one that dropped a row
     * would be a hole in a floor - both silent, both found by somebody's corpse. Also says what
     * the same sections cost the old way, one box per run along x, because that ratio is the
     * whole point of the merge.
     */
    public String meshCheck(Location around) {
        if (around == null || around.getWorld() != world) return "FAIL(nowhere)";
        int bx = around.getBlockX(), by = around.getBlockY(), bz = around.getBlockZ();
        int runs = 0, boxes = 0, wrong = 0, counted = 0;
        for (int sx = (bx - REACH) >> 4; sx <= (bx + REACH) >> 4; sx++)
            for (int sy = (by - REACH) >> 4; sy <= (by + REACH) >> 4; sy++)
                for (int sz = (bz - REACH) >> 4; sz <= (bz + REACH) >> 4; sz++) {
                    int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
                    if (y0 + 15 < world.getMinY() || y0 > (world.getMaxY() + 1)) continue;
                    world.getChunk(sx, sz);
                    counted++;
                    int[][][] cover = new int[16][16][16];
                    java.util.List<int[]> made = blocks(x0, y0, z0);
                    boxes += made.size();
                    for (int[] box : made) {
                        for (int x = box[0]; x <= box[3]; x++)
                            for (int y = box[1]; y <= box[4]; y++)
                                for (int z = box[2]; z <= box[5]; z++) {
                                    if (x < x0 || x > x0 + 15 || y < y0 || y > y0 + 15 || z < z0 || z > z0 + 15) {
                                        wrong++;
                                        continue;
                                    }
                                    cover[x - x0][y - y0][z - z0]++;
                                }
                    }
                    for (int y = Math.max(y0, world.getMinY()); y <= Math.min(y0 + 15, (world.getMaxY() + 1) - 1); y++)
                        for (int z = z0; z <= z0 + 15; z++) {
                            boolean inRun = false;
                            for (int x = x0; x <= x0 + 15; x++) {
                                boolean want = exposed(x, y, z);
                                if (want && !inRun) runs++;
                                inRun = want;
                                if (cover[x - x0][y - y0][z - z0] != (want ? 1 : 0)) wrong++;
                            }
                        }
                }
        return (wrong == 0 && boxes > 0 ? "ok" : "FAIL") + "(boxes " + runs + "->" + boxes
                + ",sections=" + counted + (wrong > 0 ? ",wrong=" + wrong : "") + ")";
    }

    /** What the terrain is costing, for the probe and for anybody wondering. */
    public String terrain() {
        return "TERRAIN boxes=" + terrainBodies + "/" + TERRAIN_BUDGET
                + " sections=" + sections.size() + " evicted=" + evictions
                + " followed=" + follows + " adrift=" + adrift
                + (terrainFull ? " FULL" : "");
    }

    /** Whether a block has nothing to collide with (air, grass, water ...). */
    private boolean passable(int x, int y, int z) {
        BlockPos at = new BlockPos(x, y, z);
        return world.getBlockState(at).getCollisionShape(world, at).isEmpty();
    }

    /** A solid block with somewhere for a limb to be next to it. */
    private boolean exposed(int x, int y, int z) {
        if (passable(x, y, z)) return false;
        for (int[] step : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            int nx = x + step[0], ny = y + step[1], nz = z + step[2];
            if (ny < world.getMinY() || ny >= (world.getMaxY() + 1)) return true;
            if (passable(nx, ny, nz)) return true;
        }
        return false;
    }

    private void addTerrainBox(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        if (terrainBodies >= TERRAIN_BUDGET) {
            if (!terrainFull) {
                terrainFull = true;
                // With what it is full of. Every section that is left is one something is lying
                // within reach of, so the count of those is the number that says whether the
                // budget is too small or something is holding on to floor it should not.
                plugin.getLogger().warn("Jolt: terrain budget spent at " + TERRAIN_BUDGET
                        + " boxes (sections=" + sectionAt.size() + " bodies=" + tracked.size()
                        + "); corpses further out will fall through the floor");
            }
            return;
        }
        double halfX = (toX - fromX + 1) / 2.0, halfY = (toY - fromY + 1) / 2.0, halfZ = (toZ - fromZ + 1) / 2.0;
        Location centre = new Location(world, fromX + halfX, fromY + halfY, fromZ + halfZ);
        int made = addStatic(centre, new Vector(halfX, halfY, halfZ));
        if (made == -1) return;
        terrainBodies++;
        if (building != null) building.add(made);
    }

    /** How much of the dungeon is currently collidable, for the audit. */
    public int terrainCount() { return terrainBodies; }

    /** A static box, used for wall tiles so projectiles actually stop at them. */
    public int addStatic(Location center, Vector halfExtent) {
        BoxShape shape = box(halfExtent.getX(), halfExtent.getY(), halfExtent.getZ());
        BodyCreationSettings bcs = new BodyCreationSettings(shape,
                new RVec3(center.getX(), center.getY(), center.getZ()), new Quat(),
                EMotionType.Static, LAYER_STATIC);
        var body = bodies.createBody(bcs);
        if (body == null) {
            plugin.getLogger().warn("Jolt: static body creation failed - probably at the "
                    + MAX_BODIES + " body limit (tracked " + tracked.size() + ")");
            return -1;
        }
        bodies.addBody(body, EActivation.DontActivate);
        return body.getId();
    }

    public void removeBody(int bodyId) {
        if (bodyId == -1) return;      // cInvalidBodyId; removing it would be a silent no-op
        bodies.removeBody(bodyId);
        bodies.destroyBody(bodyId);
    }

    /**
     * Creates a dynamic box. Returns null when Jolt is out of bodies - it signals that
     * by returning null rather than throwing, so the caller must check.
     */
    public PhysicsBody createBody(Location at, Vector halfExtent, float mass,
                                  Vector initialVelocity, int lifetimeTicks) {
        return createBody(at, halfExtent, mass, initialVelocity, null, lifetimeTicks);
    }

    /**
     * The same, given a spin as well as a shove.
     *
     * <p>Linear velocity alone cannot tip anything over: a box thrown sideways stays in the
     * attitude it was created in until something hits it, and a corpse that lands in the
     * attitude it died in is a corpse standing up. Rotation has to be asked for.
     */
    public PhysicsBody createBody(Location at, Vector halfExtent, float mass,
                                  Vector initialVelocity, Vector spin, int lifetimeTicks) {
        return make(at, halfExtent, mass, initialVelocity, spin, lifetimeTicks,
                LAYER_DYNAMIC, EMotionQuality.LinearCast, 0.35f);
    }

    /**
     * One piece of a body, on the layer that lets it overlap its neighbours.
     *
     * <p>Discrete rather than swept: a limb is a slow heavy thing that falls a couple of
     * blocks and stops, not a musket ball, and continuous collision on eleven boxes per
     * corpse is a price paid against a problem that does not arise. The extra angular
     * damping is what stops a jointed body from windmilling, given the solver has a chain
     * of ten constraints to satisfy in two velocity steps a tick.
     */
    /** A projectile that stops dead where it hits, rather than skipping off it. */
    public PhysicsBody createBolt(Location at, Vector halfExtent, float mass,
                                  Vector initialVelocity, int lifetimeTicks) {
        return createBolt(at, halfExtent, mass, initialVelocity, lifetimeTicks, false);
    }

    /**
     * The same, optionally weightless.
     *
     * <p>For the things that are levelled rather than lobbed - a crossbow, a musket. The
     * arc is not aesthetic: a shot that falls has to be aimed above what it is shot at,
     * and the whole point of a machine bow is that it does not. Taking the weight off is
     * the honest way to say that, rather than firing it hard enough that the fall does not
     * show, which is the same lie told with a bigger number.
     */
    public PhysicsBody createBolt(Location at, Vector halfExtent, float mass,
                                  Vector initialVelocity, int lifetimeTicks,
                                  boolean weightless) {
        return make(at, halfExtent, mass, initialVelocity, null, lifetimeTicks,
                LAYER_DYNAMIC, EMotionQuality.LinearCast, 0.05f, 0.0f, 0, 0, weightless);
    }

    /**
     * A limb of a corpse, told which corpse and which piece of it.
     *
     * <p>The piece number becomes its sub-group, and has to differ from every other piece
     * of the same body: two limbs sharing one would be asked about a pair the table does
     * not contain. A body past {@link #LIMBS_PER_CORPSE} pieces rolls into the next id in
     * its reserved block, which costs it nothing but the exclusion between its sixty-fourth
     * limb and its sixty-fifth.
     */
    public PhysicsBody createLimb(Location at, Vector halfExtent, float mass,
                                  Vector initialVelocity, Vector spin, int lifetimeTicks,
                                  int corpse, int piece) {
        int block = Math.min(GROUP_STRIDE - 1, piece / LIMBS_PER_CORPSE);
        PhysicsBody made = make(at, halfExtent, mass, initialVelocity, spin, lifetimeTicks,
                LAYER_LIMB, EMotionQuality.Discrete, 0.55f, 0.15f,
                corpse + block, piece % LIMBS_PER_CORPSE);
        // Limbs drag their floor along behind them; arrows and debris do not. A projectile
        // crosses a room in a second and would have the world building terrain the whole way
        // for something that is about to stop existing.
        if (made != null) made.floored = new double[]{at.getX(), at.getY(), at.getZ()};
        return made;
    }

    /** A limb belonging to nothing else, which therefore collides with everything. */
    public PhysicsBody createLimb(Location at, Vector halfExtent, float mass,
                                  Vector initialVelocity, Vector spin, int lifetimeTicks) {
        return createLimb(at, halfExtent, mass, initialVelocity, spin, lifetimeTicks,
                corpseGroup(), 0);
    }

    private PhysicsBody make(Location at, Vector halfExtent, float mass,
                             Vector initialVelocity, Vector spin, int lifetimeTicks,
                             int layer, EMotionQuality quality, float angularDamping) {
        return make(at, halfExtent, mass, initialVelocity, spin, lifetimeTicks, layer,
                quality, angularDamping, 0.15f);
    }

    /**
     * The same, told how much of its speed a surface gives back.
     *
     * <p>Fifteen percent is right for rubble and wrong for a bolt: an arrow that hits the
     * ground and hops is a thing nobody has ever seen. A sling stone skipping is a stone
     * skipping, so that one keeps it.
     */
    private PhysicsBody make(Location at, Vector halfExtent, float mass,
                             Vector initialVelocity, Vector spin, int lifetimeTicks,
                             int layer, EMotionQuality quality, float angularDamping,
                             float restitution) {
        return make(at, halfExtent, mass, initialVelocity, spin, lifetimeTicks, layer,
                quality, angularDamping, restitution, 0, 0);
    }

    private PhysicsBody make(Location at, Vector halfExtent, float mass,
                             Vector initialVelocity, Vector spin, int lifetimeTicks,
                             int layer, EMotionQuality quality, float angularDamping,
                             float restitution, int corpse, int piece) {
        return make(at, halfExtent, mass, initialVelocity, spin, lifetimeTicks, layer,
                quality, angularDamping, restitution, corpse, piece, false);
    }

    private PhysicsBody make(Location at, Vector halfExtent, float mass,
                             Vector initialVelocity, Vector spin, int lifetimeTicks,
                             int layer, EMotionQuality quality, float angularDamping,
                             float restitution, int corpse, int piece, boolean weightless) {
        BoxShape shape = box(halfExtent.getX(), halfExtent.getY(), halfExtent.getZ());
        BodyCreationSettings bcs = new BodyCreationSettings(shape,
                new RVec3(at.getX(), at.getY(), at.getZ()), new Quat(),
                EMotionType.Dynamic, layer);

        if (spin != null) {
            bcs.setAngularVelocity(new com.github.stephengold.joltjni.Vec3(
                    (float) spin.getX(), (float) spin.getY(), (float) spin.getZ()));
        }

        MassProperties mp = new MassProperties();
        mp.setMass(mass);
        bcs.setMassPropertiesOverride(mp);
        bcs.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);

        // Nought means "not part of a body", and Jolt reads a group of nought as a real
        // group - so every limb that was not told which corpse it belonged to would have
        // shared one, and no corpse would ever stack on another.
        if (corpse != 0) {
            bcs.setCollisionGroup(new com.github.stephengold.joltjni.CollisionGroup(
                    ownBody, corpse, piece));
        }
        bcs.setRestitution(restitution);
        if (weightless) bcs.setGravityFactor(0f);
        bcs.setFriction(0.6f);
        bcs.setLinearDamping(0.10f);
        bcs.setAngularDamping(angularDamping);

        var body = bodies.createBody(bcs);
        if (body == null) {
            plugin.getLogger().warn("Jolt: body creation failed - probably at the "
                    + MAX_BODIES + " body limit (tracked " + tracked.size() + ")");
            return null;
        }
        bodies.addBody(body, EActivation.Activate);
        // Small fast objects skip straight through thin geometry in one step without this.
        bodies.setMotionQuality(body.getId(), quality);
        if (initialVelocity != null) {
            bodies.setLinearVelocity(body.getId(), new Vec3(
                    (float) initialVelocity.getX(),
                    (float) initialVelocity.getY(),
                    (float) initialVelocity.getZ()));
        }

        PhysicsBody pb = new PhysicsBody(this, body, lifetimeTicks, mass);
        pb.sinks = heavy;
        tracked.add(pb);
        return pb;
    }

    /**
     * Hangs one body off another at a point, free to swing within a cone.
     *
     * <p>A ball joint with a limit, which is every joint a person has that matters at this
     * distance. Given in world space because the bodies are created unrotated in the pose
     * the corpse died in, so that pose is the rest position and each cone is measured off
     * it - a shoulder that can go almost anywhere, a knee that cannot fold far backwards, a
     * neck that barely moves.
     *
     * <p>Twist is not limited. A cone cannot express it, and the constraint that can wants
     * six more numbers per joint for something nobody will make out on a body lying in the
     * grass thirty blocks away.
     */
    public PhysicsJoint join(PhysicsBody parent, PhysicsBody child, Location socket,
                             Vector axis, double coneDegrees) {
        if (parent == null || child == null) return null;
        try {
            com.github.stephengold.joltjni.ConeConstraintSettings s =
                    new com.github.stephengold.joltjni.ConeConstraintSettings();
            s.setSpace(com.github.stephengold.joltjni.enumerate.EConstraintSpace.WorldSpace);
            s.setPoint1(new RVec3(socket.getX(), socket.getY(), socket.getZ()));
            s.setPoint2(new RVec3(socket.getX(), socket.getY(), socket.getZ()));
            s.setTwistAxis1(new Vec3((float) axis.getX(), (float) axis.getY(),
                    (float) axis.getZ()));
            s.setTwistAxis2(new Vec3((float) axis.getX(), (float) axis.getY(),
                    (float) axis.getZ()));
            s.setHalfConeAngle((float) Math.toRadians(coneDegrees));

            com.github.stephengold.joltjni.TwoBodyConstraint c =
                    s.create(parent.body(), child.body());
            if (c == null) return null;
            system.addConstraint(c);
            PhysicsJoint joint = new PhysicsJoint(this, c);
            parent.hold(joint);
            child.hold(joint);
            return joint;
        } catch (Throwable t) {
            plugin.getLogger().warn("Jolt: could not build a joint - " + t);
            return null;
        }
    }

    /**
     * A shoulder or a hip: loose one way, nearly rigid the other, and barely twisting.
     *
     * <p>A cone cannot say this. A cone is symmetric, so the only way to stop an arm
     * swinging into the chest with one is to stop it swinging at all - and an arm that
     * cannot swing forward is a plank nailed to a torso. This says the two directions
     * separately: {@code across} limits the swing in the plane that contains the body
     * (inwards and outwards), {@code along} the swing at right angles to it (forwards and
     * back), and the parent's frame is leaned outwards so that the limb starts at the inner
     * end of its range rather than in the middle of it.
     *
     * <p>Falls back to the caller's cone by returning null, so a frame this cannot build
     * leaves a joint that is merely cruder rather than a corpse with a loose arm.
     */
    public PhysicsJoint joinSwing(PhysicsBody parent, PhysicsBody child, Location socket,
                                  Vector twistParent, Vector planeParent,
                                  Vector twistChild, Vector planeChild,
                                  double acrossDegrees, double alongDegrees, double twistDegrees) {
        if (parent == null || child == null) return null;
        try {
            var settings = new com.github.stephengold.joltjni.SwingTwistConstraintSettings();
            settings.setSpace(com.github.stephengold.joltjni.enumerate.EConstraintSpace.WorldSpace);
            settings.setPosition1(new RVec3(socket.getX(), socket.getY(), socket.getZ()));
            settings.setPosition2(new RVec3(socket.getX(), socket.getY(), socket.getZ()));
            settings.setTwistAxis1(vec(twistParent));
            settings.setTwistAxis2(vec(twistChild));
            settings.setPlaneAxis1(vec(planeParent));
            settings.setPlaneAxis2(vec(planeChild));
            settings.setPlaneHalfConeAngle((float) Math.toRadians(acrossDegrees));
            settings.setNormalHalfConeAngle((float) Math.toRadians(alongDegrees));
            settings.setTwistMinAngle((float) -Math.toRadians(twistDegrees));
            settings.setTwistMaxAngle((float) Math.toRadians(twistDegrees));
            com.github.stephengold.joltjni.TwoBodyConstraint constraint =
                    settings.create(parent.body(), child.body());
            if (constraint == null) return null;
            system.addConstraint(constraint);
            PhysicsJoint joint = new PhysicsJoint(this, constraint);
            parent.hold(joint);
            child.hold(joint);
            return joint;
        } catch (Throwable t) {
            plugin.getLogger().warn("Jolt: could not build a swing joint - " + t);
            return null;
        }
    }

    private static Vec3 vec(Vector v) {
        return new Vec3((float) v.getX(), (float) v.getY(), (float) v.getZ());
    }

    /** Called by {@link PhysicsJoint} as it is dropped, and by nothing else. */
    void drop(com.github.stephengold.joltjni.TwoBodyConstraint constraint) {
        try {
            system.removeConstraint(constraint);
        } catch (Throwable ignored) {
            // Already gone, which is the outcome that was wanted anyway.
        }
    }

    /** Whether bodies made now go to the bottom of water. See model.Sinking.laying. */
    private boolean heavy;

    public void heavy(boolean heavy) { this.heavy = heavy; }

    /** Water pushes up on whatever is in it (the user: corpses float). */
    private void float_() {
        for (PhysicsBody b : tracked) {
            if (b.isDead()) continue;
            Location at = b.location();
            if (!world.hasChunk(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            BlockPos in = at.blockPos();
            if (!kr.gcb.dungeon.model.Sinking.wet(world, in)) {
                // Riding the surface: the middle a little above the water still feels it.
                if (!kr.gcb.dungeon.model.Sinking.wet(world, in.below()) || b.sinks()) continue;
                b.buoy((in.getY()) - at.getY(), GRAVITY);
                continue;
            }
            if (b.sinks() && b.resting()) continue;
            // Up to the surface: how deep the middle is.
            BlockPos top = in;
            int climb = 0;
            while (climb < 24 && kr.gcb.dungeon.model.Sinking.wet(world, top.above())) {
                top = top.above();
                climb++;
            }
            b.buoy(top.getY() + 1 - at.getY(), GRAVITY);
        }
    }

    /** Whether a failing body has already been complained about. Once is enough. */
    private boolean stepReported;

    /** Steps the simulation and pushes every body's transform onto its display. */
    public void tick() {
        if (tracked.isEmpty()) return;      // Jolt sleeps idle bodies, so an empty world costs nothing

        // Floor first, then fall. The other way round leaves a one tick hole: the step happens,
        // the body arrives somewhere nothing has been built, and only afterwards is the ground
        // laid under where it now is - so anything moving quickly gets one free tick of falling
        // through a floor that was about to exist. That is what was left of corpses sinking
        // after the terrain learnt to follow them, and it is the same fault one tick smaller.
        //
        // Nothing extra is paid for it. This is the same pass that ran below, moved above, and
        // for a body that has not travelled it is three subtractions and a return.
        for (PhysicsBody b : tracked) {
            if (b.floored != null && !b.isDead()) keepFloor(b);
        }

        float_();
        system.update(1f / 20f, 2, temp, jobs);

        List<PhysicsBody> finished = null;
        for (PhysicsBody b : List.copyOf(tracked)) {
            // Guarded, because everything after this in the loop depends on it returning.
            //
            // A body's step callback is arbitrary code - it draws particles, it moves
            // displays - and one that threw took the whole loop with it: every body after
            // it in the list went unsimulated for that tick, and the flush at the bottom
            // never ran, so no corpse limb moved either. An arrow asking for a malformed
            // particle froze every ragdoll on the field for as long as it was in the air.
            try {
                b.tick();
            } catch (Throwable broken) {
                if (!stepReported) {
                    stepReported = true;
                    plugin.getLogger().warn("physics step failed", broken);
                }
                b.kill();
            }
            if (b.isDead()) {
                if (finished == null) finished = new ArrayList<>();
                finished.add(b);
            } else if (b.floored != null) {
                // Ground under where the step actually landed it, and then said out loud when
                // there is none - which should never happen, because it was built before the
                // step as well.
                keepFloor(b);
                if (!terrainBuiltAt(b.location())) adrift++;
            }
        }
        // Collect first, release after: mutating the list mid-iteration is how you end up
        // destroying the same body twice, and a double destroyBody takes the JVM with it.
        // Everything that moved goes out in one bundle per viewer. Done here rather than
        // per limb: a corpse is twelve pieces and a battlefield is a dozen corpses, and
        // sending each one as it moves is a hundred and fifty flushes for one tick's work.
        kr.gcb.dungeon.model.Puppets.flush(world.getServer());

        if (finished != null) {
            for (PhysicsBody b : finished) {
                b.release();
                tracked.remove(b);
            }
        }
    }

    public void clearAll() {
        for (PhysicsBody b : List.copyOf(tracked)) b.release();
        tracked.clear();
        // Deliberately keeps the PhysicsSystem and its allocator alive; tearing those down
        // makes every later call fail silently.
    }

    public int trackedCount() { return tracked.size(); }

    /**
     * Every live body within reach of a point, nearest first.
     *
     * <p>A plain scan. The alternative is asking Jolt's broad phase, which is the right tool
     * for thousands of bodies and more machinery than a handful of corpses is worth; a few
     * hundred distance checks a tick costs nothing measurable.
     */
    /**
     * Wakes everything resting near a point.
     *
     * <p>Called when a body is taken away. Jolt sleeps bodies that have come to rest and does
     * not wake them when whatever they were resting on is removed, so without this a corpse
     * cleared from the bottom of a heap leaves the rest of it floating.
     */
    public void wakeNear(Location around, double radius) {
        for (PhysicsBody body : near(around, radius)) body.wake();
    }

    public List<PhysicsBody> near(Location around, double radius) {
        List<PhysicsBody> found = new ArrayList<>();
        if (around == null || around.getWorld() != world) return found;
        double limit = radius * radius;
        for (PhysicsBody body : tracked) {
            if (body.isDead()) continue;
            if (body.location().distanceSquared(around) <= limit) found.add(body);
        }
        found.sort(java.util.Comparator.comparingDouble(b -> b.location().distanceSquared(around)));
        return found;
    }


    private BoxShape box(double hx, double hy, double hz) {
        long key = (Math.round(hx * 100) << 42) ^ (Math.round(hy * 100) << 21) ^ Math.round(hz * 100);
        return shapeCache.computeIfAbsent(key,
                ignored -> new BoxShape(new Vec3((float) hx, (float) hy, (float) hz), 0.01f));
    }
}
