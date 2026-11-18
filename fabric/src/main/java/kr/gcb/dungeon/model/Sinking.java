package kr.gcb.dungeon.model;

import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import kr.gcb.dungeon.physics.PhysicsWorld;
import net.minecraft.world.item.ItemStack;

/**
 * Whether a body goes to the bottom or floats (the user): a dead thing in water floats, unless it
 * is wearing a whole suit of iron or heavier, or was carrying a pack full enough to drag it down.
 */
public final class Sinking {

    /** How many of a player's thirty-six pack slots have to be full before the pack sinks them. */
    public static final int FULL_PACK = 27;

    private Sinking() {}

    /** Iron, gold, diamond or netherite - chain and leather float. */
    static boolean metal(ItemStack piece) {
        if (piece == null || piece.isEmpty()) return false;
        String name = BuiltInRegistries.ITEM.getKey(piece.getItem()).getPath().toUpperCase(java.util.Locale.ROOT);
        return name.startsWith("IRON_") || name.startsWith("GOLDEN_")
                || name.startsWith("DIAMOND_") || name.startsWith("NETHERITE_");
    }

    /** A whole suit of metal: all four pieces. */
    public static boolean plated(LivingEntity worn) {
        if (worn == null) return false;
        return metal(worn.getItemBySlot(EquipmentSlot.HEAD)) && metal(worn.getItemBySlot(EquipmentSlot.CHEST))
                && metal(worn.getItemBySlot(EquipmentSlot.LEGS)) && metal(worn.getItemBySlot(EquipmentSlot.FEET));
    }

    public static boolean plated(ItemStack... pieces) {
        if (pieces == null || pieces.length < 4) return false;
        for (int at = 0; at < 4; at++) if (!metal(pieces[at])) return false;
        return true;
    }

    /** A pack with {@link #FULL_PACK} or more of its slots taken. */
    public static boolean laden(Player who) {
        int full = 0;
        // The 27 slots above the hotbar: the main inventory is hotbar 0-8, then storage 9-35.
        for (int slot = 9; slot < 36; slot++) if (!who.getInventory().getItem(slot).isEmpty()) full++;
        return full >= FULL_PACK;
    }

    public static boolean heavy(LivingEntity who) {
        if (who == null) return false;
        if (plated(who)) return true;
        return who instanceof Player human && laden(human);
    }

    /** Lays a body out with every limb made meanwhile marked as sinking or not. */
    public static <T> T laying(PhysicsWorld physics, boolean heavy, Supplier<T> make) {
        if (physics == null) return make.get();
        physics.heavy(heavy);
        try {
            return make.get();
        } finally {
            physics.heavy(false);
        }
    }

    /** Whether this block is somewhere a body is in the water. */
    public static boolean wet(net.minecraft.server.level.ServerLevel world, net.minecraft.core.BlockPos at) {
        // Water itself, and everything that is always or currently full of it (kelp, seagrass,
        // bubble columns, waterlogged blocks) - all of those report a water fluid state.
        return world.getFluidState(at).is(net.minecraft.tags.FluidTags.WATER);
    }
}
