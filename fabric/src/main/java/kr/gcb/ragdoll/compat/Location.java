package kr.gcb.ragdoll.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/** A mutable point in a world, facing some way. The counterpart of {@link Vector}; see there for why it exists. */
public final class Location implements Cloneable {
    private ServerLevel world;
    private double x, y, z;
    private float yaw, pitch;

    public Location(ServerLevel world, double x, double y, double z) { this(world, x, y, z, 0f, 0f); }

    public Location(ServerLevel world, double x, double y, double z, float yaw, float pitch) {
        this.world = world; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
    }

    /** Where an entity stands (its feet), facing the way it faces. Null if it is not in a server world. */
    public static Location of(Entity e) {
        return e.level() instanceof ServerLevel level ? new Location(level, e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot()) : null;
    }

    public ServerLevel getWorld() { return world; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public void setX(double v) { x = v; }
    public void setY(double v) { y = v; }
    public void setZ(double v) { z = v; }
    public void setYaw(float v) { yaw = v; }
    public void setPitch(float v) { pitch = v; }
    public int getBlockX() { return Mth.floor(x); }
    public int getBlockY() { return Mth.floor(y); }
    public int getBlockZ() { return Mth.floor(z); }
    public BlockPos blockPos() { return BlockPos.containing(x, y, z); }

    public Location add(Vector v) { x += v.getX(); y += v.getY(); z += v.getZ(); return this; }
    public Location add(double dx, double dy, double dz) { x += dx; y += dy; z += dz; return this; }
    public Location subtract(Vector v) { x -= v.getX(); y -= v.getY(); z -= v.getZ(); return this; }
    public Location subtract(double dx, double dy, double dz) { x -= dx; y -= dy; z -= dz; return this; }

    public Vector toVector() { return new Vector(x, y, z); }

    /** The unit vector this location looks along. */
    public Vector getDirection() {
        double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch), xz = Math.cos(rp);
        return new Vector(-xz * Math.sin(ry), -Math.sin(rp), xz * Math.cos(ry));
    }

    public Location setDirection(Vector v) {
        double dx = v.getX(), dz = v.getZ();
        if (dx == 0 && dz == 0) { pitch = v.getY() > 0 ? -90 : 90; return this; }
        yaw = (float) Math.toDegrees((Math.atan2(-dx, dz) + Math.PI * 2) % (Math.PI * 2));
        pitch = (float) Math.toDegrees(Math.atan(-v.getY() / Math.sqrt(dx * dx + dz * dz)));
        return this;
    }

    public double distanceSquared(Location o) { double a = x - o.x, b = y - o.y, c = z - o.z; return a * a + b * b + c * c; }
    public double distance(Location o) { return Math.sqrt(distanceSquared(o)); }

    @Override public Location clone() { return new Location(world, x, y, z, yaw, pitch); }

    @Override public String toString() { return (world == null ? "?" : world.dimension().identifier()) + " " + x + "," + y + "," + z; }
}
