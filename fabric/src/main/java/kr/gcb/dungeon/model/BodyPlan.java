package kr.gcb.dungeon.model;

import java.util.List;
import kr.gcb.ragdoll.compat.Vector;

/**
 * The shape of a corpse: six whole limbs and the five joints between them.
 *
 * <p>Kept apart from {@link Ragdoll} because it names nothing from BetterModel. That lets
 * the physics be exercised on a server with no BetterModel at all - see PhysicsProbe -
 * and it keeps the one table that decides what a body looks like in one place.
 *
 * <p>Six rather than the eleven this was ported from. A bone per limb segment is more
 * faithful to a skeleton and reads worse: ten joints fold up like a rubber toy, and every
 * joint between two boxes is a seam that opens into a hollow shell the first time it
 * bends. An arm here is one rigid piece, the way a player has always been drawn.
 *
 * <p>Every number below is read off BetterModel's own player model
 * ({@code plugins/BetterModel/players/steve.bbmodel}) rather than guessed, because the
 * skin slices it hands back are cut to that model and nothing else will line up with them.
 * Sixteen model units make a block, and the whole figure is 30 units - 1.875 blocks, the
 * vanilla player at its usual 0.9375 render scale.
 */
public final class BodyPlan {

    private BodyPlan() {}

    /** One model unit, a sixteenth of a block. */
    private static final double U = 1.0 / 16.0;
    /** Where the legs end and the body starts: 11.25 units. */
    private static final double LEG_TOP = 11.25 * U;
    /** Where the body ends and the head starts: 22.5 units. */
    private static final double TORSO_TOP = 22.5 * U;
    /** The top of the head: 30 units. */
    private static final double HEAD_TOP = 30 * U;
    /** One of the three slabs the body is cut into - hip, waist, chest. */
    private static final double THIRD = 3.75 * U;
    /** Half an arm or a leg, which is what a limb is cut into. */
    private static final double LIMB_HALF = 5.625 * U;
    private static final double BODY_HALF_X = 3.75 * U;
    private static final double BODY_HALF_Z = 1.875 * U;
    /** An arm is four skin pixels wide, or three on a slim skin. */
    private static final double ARM_HALF_CLASSIC = 1.875 * U;
    private static final double ARM_HALF_SLIM = 1.40625 * U;
    private static final double LEG_HALF_X = 1.875 * U;

    private static final double TORSO_Y = (LEG_TOP + TORSO_TOP) / 2;
    private static final double TORSO_HALF_Y = (TORSO_TOP - LEG_TOP) / 2;
    private static final double HEAD_Y = (TORSO_TOP + HEAD_TOP) / 2;
    private static final double HEAD_HALF = (HEAD_TOP - TORSO_TOP) / 2;
    private static final double LEG_Y = LEG_TOP / 2;

    /**
     * One drawing riding a limb: which slice of skin it is, how far along the limb it sits,
     * and how much longer than life to draw it.
     *
     * <p>The offsets tile exactly, so the seams inside a limb - an elbow, a waistline - are
     * flush and can never open, because the two pieces are bolted to the same rigid body.
     * The stretch is only for the pieces that end at one of the five real joints: a skin
     * part is a hollow box, so the first degree of bend at a shoulder opens a wedge you can
     * see into, and drawing that piece a tenth longer buries the cut end in its neighbour.
     */
    public record Piece(String slice, double along, double pivotY, double pivotX,
                        double length, int layer) {
        /** Where the drawn box starts, relative to the middle of the limb it rides. */
        public double low() { return along - length / 2; }
        public double high() { return along + length / 2; }
    }

    /**
     * Every slice BetterModel can cut a player skin into - its PlayerLimb enum, in the names
     * this uses. A piece asking for anything else silently draws nothing, which is what left
     * a corpse with no middle to its torso.
     */
    public static final List<String> SLICES = List.of(
            "head", "chest", "waist", "hip",
            "left_arm", "left_forearm", "right_arm", "right_forearm",
            "left_leg", "left_foreleg", "right_leg", "right_foreleg");

    /**
     * One rigid limb: where it sits relative to the feet, the box it collides with, what it
     * weighs, and what is drawn on it.
     *
     * <p>The masses are roughly a person's; what matters is the ratios, since a head that
     * outweighs a torso hangs the body upside down off its own neck.
     */
    public record Part(String name, double x, double y, double z,
                       double hx, double hy, double hz, float mass, List<Piece> pieces) {}

    /**
     * Where BetterModel's own player skeleton puts each bone, in blocks.
     *
     * <p>Measured with {@code /dungeon skindump}, which reads them off the renderer rather
     * than off the model file. They matter because <em>a bone pivot is not the middle of the
     * bone</em>: the three slabs of the torso are drawn above their pivots and every limb
     * segment below its own, by as much as 0.18 of a block. Placing a slice at the middle of
     * its box therefore shoves the torso up and the arms and legs down until they overlap.
     */
    private static final double HIP_PIVOT = 11.25 * U, WAIST_PIVOT = 15 * U, CHEST_PIVOT = 18.75 * U;
    private static final double HEAD_PIVOT = 22.5 * U;
    private static final double ARM_PIVOT = 22.0 * U, FOREARM_PIVOT = 16.5 * U;
    private static final double LEG_PIVOT = 11.25 * U, FORELEG_PIVOT = 5.625 * U;
    /** Arms hang a little inboard of the middle of their own box, on either skin. */
    private static final double ARM_PIVOT_X = 5.0 * U;

    /**
     * The six limbs, sized for a classic or a slim skin.
     *
     * <p>Slim is not cosmetic here: its arms are three pixels wide instead of four, and a
     * three pixel arm drawn on a four pixel body shows the empty column of the skin down
     * its side as a black stripe.
     */
    public static List<Part> skeleton(boolean slim) {
        double armHalf = slim ? ARM_HALF_SLIM : ARM_HALF_CLASSIC;
        double armX = BODY_HALF_X + armHalf;
        double armPivotX = armX - ARM_PIVOT_X;   // the pivot sits this far towards the body
        return List.of(
                new Part("head", 0, HEAD_Y, 0, HEAD_HALF, HEAD_HALF, HEAD_HALF, 5f,
                        List.of(piece("head", 0, HEAD_PIVOT - HEAD_Y, 0, HEAD_HALF * 2, FLUSH))),
                new Part("torso", 0, TORSO_Y, 0, BODY_HALF_X, TORSO_HALF_Y, BODY_HALF_Z, 24f,
                        List.of(piece("chest", THIRD, CHEST_PIVOT - TORSO_Y, 0, THIRD, IN2),
                                piece("waist", 0, WAIST_PIVOT - TORSO_Y, 0, THIRD, FLUSH),
                                piece("hip", -THIRD, HIP_PIVOT - TORSO_Y, 0, THIRD, IN2))),
                new Part("right_arm", -armX, TORSO_Y, 0, armHalf, TORSO_HALF_Y, BODY_HALF_Z, 6f,
                        List.of(piece("right_arm", LIMB_HALF / 2, ARM_PIVOT - TORSO_Y, armPivotX, LIMB_HALF, IN1),
                                piece("right_forearm", -LIMB_HALF / 2, FOREARM_PIVOT - TORSO_Y, armPivotX, LIMB_HALF, IN3))),
                new Part("left_arm", armX, TORSO_Y, 0, armHalf, TORSO_HALF_Y, BODY_HALF_Z, 6f,
                        List.of(piece("left_arm", LIMB_HALF / 2, ARM_PIVOT - TORSO_Y, -armPivotX, LIMB_HALF, IN3),
                                piece("left_forearm", -LIMB_HALF / 2, FOREARM_PIVOT - TORSO_Y, -armPivotX, LIMB_HALF, IN1))),
                new Part("right_leg", -LEG_HALF_X, LEG_Y, 0, LEG_HALF_X, LEG_TOP / 2, BODY_HALF_Z, 10f,
                        List.of(piece("right_leg", LIMB_HALF / 2, LEG_PIVOT - LEG_Y, 0, LIMB_HALF, IN1),
                                piece("right_foreleg", -LIMB_HALF / 2, FORELEG_PIVOT - LEG_Y, 0, LIMB_HALF, IN3))),
                new Part("left_leg", LEG_HALF_X, LEG_Y, 0, LEG_HALF_X, LEG_TOP / 2, BODY_HALF_Z, 10f,
                        List.of(piece("left_leg", LIMB_HALF / 2, LEG_PIVOT - LEG_Y, 0, LIMB_HALF, IN3),
                                piece("left_foreleg", -LIMB_HALF / 2, FORELEG_PIVOT - LEG_Y, 0, LIMB_HALF, IN1))));
    }

    /**
     * How each piece is pulled in from the sides, and why the sides are the right place.
     *
     * <p>The flicker at the seams is two faces sharing a plane. The first attempt grew every
     * piece a little to break that, and it made things worse rather than better: growing a
     * slab along the limb leaves the horizontal faces where they were - they are interior,
     * back to back, and nobody can see them anyway - while the four <em>side</em> faces, which
     * until then merely met edge to edge on a shared plane, started to overlap on it. That is
     * the case that flickers, and now it had area.
     *
     * <p>So the heights stay exact and the sides come in instead, by a different distance on
     * each piece that could share a plane with another. Neighbours no longer have coplanar
     * sides at all.
     *
     * <p>A layer number rather than a distance, and the distance it stands for is a fraction
     * of the limb's own width (see corpse.side-inset-percent). A fraction rather than blocks
     * because a corpse may one day be built at the size the person was: a fixed number of
     * blocks would be a whisker on a giant and a visible chamfer on a child, while a
     * percentage is the same whisker on both.
     */
    private static final int FLUSH = 0, IN1 = 1, IN2 = 2, IN3 = 3;

    /** A piece drawn at its bone pivot and occupying its own slab of the limb. */
    private static Piece piece(String slice, double along, double pivotY, double pivotX,
                               double length, int layer) {
        return new Piece(slice, along, pivotY, pivotX, length, layer);
    }

    /**
     * One joint: which way the limb hangs, how far it may swing each way, and the wound
     * that finally tears it.
     *
     * <p>A cone was not enough. A cone is symmetric, so the forty degrees that let an arm
     * swing forward also let it swing the same distance inwards - straight into the chest,
     * which is what it did. This says the two directions apart: {@code across} is the swing
     * in the plane that contains the body and {@code along} the swing at right angles to
     * it, and {@code lean} tilts the parent's frame outwards so the limb rests at the inner
     * end of that range instead of the middle of it. An arm can then swing freely forwards
     * and back, hardly at all inwards, and a little way out.
     */
    public record Joint(String parent, String child, double x, double y, double z,
                        Vector rest, Vector away, double lean,
                        double across, double along, double twist, Wound breaksAt) {
        /** The child's own frame: it hangs along rest, with away across it. */
        public Vector twistChild() { return rest.clone(); }
        public Vector planeChild() { return away.clone(); }
        /** The parent's frame, leaned towards the side the limb is allowed to move. */
        public Vector twistParent() { return turn(rest, away, lean); }
        public Vector planeParent() { return turn(away, rest.clone().multiply(-1), lean); }

        private static Vector turn(Vector from, Vector towards, double degrees) {
            double radians = Math.toRadians(degrees);
            return from.clone().multiply(Math.cos(radians))
                    .add(towards.clone().multiply(Math.sin(radians))).normalize();
        }
    }

    private static final Vector DOWN = new Vector(0, -1, 0);
    private static final Vector UP = new Vector(0, 1, 0);
    private static final Vector FORWARD = new Vector(0, 0, 1);

    /**
     * The five joints, at the seams of the model: the neck, two shoulders, two hips.
     *
     * <p>The shoulders lean ten degrees out and allow twelve across, so an arm has two
     * degrees of inward travel - a fortieth of a block at the wrist, which is less than a
     * skin pixel - and twenty-two outwards. Fifty-five degrees along leaves it swinging
     * freely fore and aft, which is the part anybody reads as limp.
     */
    public static List<Joint> joints(boolean slim) {
        double shoulder = BODY_HALF_X + (slim ? ARM_HALF_SLIM : ARM_HALF_CLASSIC) / 2;
        Vector right = new Vector(-1, 0, 0), left = new Vector(1, 0, 0);
        return List.of(
                new Joint("torso", "head", 0, TORSO_TOP, 0,
                        UP, FORWARD, 0, 16, 16, 25, Wound.CHUNKS),
                new Joint("torso", "right_arm", -shoulder, TORSO_TOP - THIRD / 2, 0,
                        // The shoulder was the tightest joint on the body: twelve degrees out
                        // to the side, which is less than a person can do standing at attention,
                        // so arms stayed pinned to the ribs however the body landed.
                        //
                        // Opened, but opened *outwards*. The limbs of one corpse do not collide
                        // with each other - they overlap at every joint, and letting those pairs
                        // touch makes the body detonate on its first step - so a cone that is
                        // symmetrical lets the arm swing straight through the chest and sit
                        // inside it. Reported from play: "팔이 몸통에 낀다". Leaning the cone
                        // out by a third of its own width is what a shoulder does anyway: a
                        // long way out, and almost nothing in.
                        // along is the swing in the plane of the body: a hundred and seventy
                        // of it is a hand over the head, which is where an arm goes when
                        // somebody falls backwards down a stair.
                        DOWN, right, 32, 42, 170, 40, Wound.LIMBS),
                new Joint("torso", "left_arm", shoulder, TORSO_TOP - THIRD / 2, 0,
                        DOWN, left, 32, 42, 170, 40, Wound.LIMBS),
                // Hips: a little more room to the side than they had, and leaned out like the
                // shoulders so the leg swings away from the other one rather than through it.
                new Joint("torso", "right_leg", -LEG_HALF_X, LEG_TOP, 0,
                        DOWN, right, 16, 26, 75, 25, Wound.SHATTERED),
                new Joint("torso", "left_leg", LEG_HALF_X, LEG_TOP, 0,
                        DOWN, left, 16, 26, 75, 25, Wound.SHATTERED));
    }

    /** How badly the killing blow broke the body. */
    /**
     * Where one slice of a person rides, in terms any body can answer.
     *
     * <p>For putting a person's armour on something that is not a person. BetterModel will cut
     * armour into these twelve slices for anybody - see {@link Bare} - but a skeleton is bones
     * out of a model and knows nothing about waists and forearms, so something has to say which
     * bone a chestplate belongs on and how far up it.
     *
     * <p>A fraction of the limb rather than a height. The numbers below are measured on a
     * player and a skeleton is not a player: same height, thinner limbs, and the two models are
     * not even in the same units. A fraction survives all of that, and is exact wherever two
     * bodies are built to the same proportions - which every humanoid in the game is.
     *
     * @param limb  the name {@code BonePlan.partOf} reduces bones to
     * @param along how far up that limb's own height the slice's pivot sits, 0 at its base
     */
    public record Ride(String limb, double along) {}

    /**
     * Which bone each armour-bearing slice rides, and where on it.
     *
     * <p>Worked out from the measurements above rather than written down again: the pivots are
     * absolute heights on a standing body and each limb's own span is already here, so a
     * fraction is a subtraction. Move a limb and this moves with it.
     */
    public static Ride ride(String slice) {
        return switch (slice) {
            case "head" -> new Ride("head", along(HEAD_PIVOT, TORSO_TOP, HEAD_TOP));
            case "chest" -> new Ride("torso", along(CHEST_PIVOT, LEG_TOP, TORSO_TOP));
            case "waist" -> new Ride("torso", along(WAIST_PIVOT, LEG_TOP, TORSO_TOP));
            case "hip" -> new Ride("torso", along(HIP_PIVOT, LEG_TOP, TORSO_TOP));
            case "left_arm" -> new Ride("left_arm", along(ARM_PIVOT, LEG_TOP, TORSO_TOP));
            case "right_arm" -> new Ride("right_arm", along(ARM_PIVOT, LEG_TOP, TORSO_TOP));
            case "left_forearm" -> new Ride("left_arm", along(FOREARM_PIVOT, LEG_TOP, TORSO_TOP));
            case "right_forearm" -> new Ride("right_arm", along(FOREARM_PIVOT, LEG_TOP, TORSO_TOP));
            case "left_leg" -> new Ride("left_leg", along(LEG_PIVOT, 0, LEG_TOP));
            case "right_leg" -> new Ride("right_leg", along(LEG_PIVOT, 0, LEG_TOP));
            case "left_foreleg" -> new Ride("left_leg", along(FORELEG_PIVOT, 0, LEG_TOP));
            case "right_foreleg" -> new Ride("right_leg", along(FORELEG_PIVOT, 0, LEG_TOP));
            default -> null;
        };
    }

    private static double along(double pivot, double bottom, double top) {
        return (pivot - bottom) / (top - bottom);
    }

    /**
     * How tall one of a person's limbs is, for anything comparing a person to something else.
     *
     * <p>Armour is cut to these numbers. A model built to any other size needs it stretched to
     * fit, and the only honest way to know by how much is to have both figures.
     */
    public static double span(String limb) {
        return switch (limb) {
            case "head" -> HEAD_TOP - TORSO_TOP;
            case "torso", "left_arm", "right_arm" -> TORSO_TOP - LEG_TOP;
            case "left_leg", "right_leg" -> LEG_TOP;
            default -> 0;
        };
    }

    /** Every slice that armour is drawn on, in the order a body is dressed. */
    public static java.util.List<String> dressed() {
        return java.util.List.of("head", "chest", "waist", "hip",
                "left_arm", "right_arm", "left_leg", "right_leg",
                "left_foreleg", "right_foreleg");
    }

    public enum Wound {
        /** Every joint holds. The body folds up as it lands, which is what a body does. */
        INTACT,
        /** The shoulders go. The arms come off; everything else still hangs together. */
        LIMBS,
        /** The neck goes too: a head, a torso with its legs, and two loose arms. */
        CHUNKS,
        /** Nothing holds. Six parts and no joints between them. */
        SHATTERED;

        /** Which of these a blow of {@code damage} against {@code maxHealth} produces. */
        public static Wound of(double damage, double maxHealth) {
            if (maxHealth <= 0) return INTACT;
            double share = damage / maxHealth;
            if (share < 0.35) return INTACT;
            if (share < 0.65) return LIMBS;
            if (share < 1.20) return CHUNKS;
            return SHATTERED;
        }
    }

    /** About where a standing body's weight is, which is what it tumbles around. */
    public static final double PIVOT_Y = TORSO_Y;

    /** How tall the finished corpse stands, for anything that needs to make room for it. */
    public static final double HEIGHT = HEAD_TOP;

    public static Part part(List<Part> skeleton, String name) {
        for (Part part : skeleton) if (part.name().equals(name)) return part;
        return null;
    }

}
