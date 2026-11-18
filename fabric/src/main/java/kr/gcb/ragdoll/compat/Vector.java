package kr.gcb.ragdoll.compat;

import net.minecraft.world.phys.Vec3;

/**
 * A small mutable 3-vector with the handful of operations the ragdoll code was written against
 * (it came from a Bukkit plugin; Minecraft's own {@link Vec3} is immutable, and rewriting every
 * accumulation in the physics around that would change code that is tuned and working).
 */
public final class Vector implements Cloneable {
    private double x, y, z;

    public Vector() {}

    public Vector(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

    public static Vector of(Vec3 v) { return new Vector(v.x, v.y, v.z); }

    public Vec3 vec3() { return new Vec3(x, y, z); }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public Vector setX(double v) { x = v; return this; }
    public Vector setY(double v) { y = v; return this; }
    public Vector setZ(double v) { z = v; return this; }

    public Vector add(Vector o) { x += o.x; y += o.y; z += o.z; return this; }
    public Vector subtract(Vector o) { x -= o.x; y -= o.y; z -= o.z; return this; }
    public Vector multiply(double m) { x *= m; y *= m; z *= m; return this; }
    public Vector multiply(Vector o) { x *= o.x; y *= o.y; z *= o.z; return this; }
    public Vector zero() { x = y = z = 0; return this; }

    public double dot(Vector o) { return x * o.x + y * o.y + z * o.z; }
    public double lengthSquared() { return x * x + y * y + z * z; }
    public double length() { return Math.sqrt(lengthSquared()); }
    public double distanceSquared(Vector o) { double a = x - o.x, b = y - o.y, c = z - o.z; return a * a + b * b + c * c; }
    public double distance(Vector o) { return Math.sqrt(distanceSquared(o)); }

    public Vector normalize() {
        double l = length();
        if (l > 0) { x /= l; y /= l; z /= l; }
        return this;
    }

    public Vector crossProduct(Vector o) {
        double nx = y * o.z - o.y * z, ny = z * o.x - o.z * x, nz = x * o.y - o.x * y;
        x = nx; y = ny; z = nz;
        return this;
    }

    public Vector getCrossProduct(Vector o) { return clone().crossProduct(o); }

    public Location toLocation(net.minecraft.server.level.ServerLevel world) { return new Location(world, x, y, z); }

    @Override public Vector clone() { return new Vector(x, y, z); }

    @Override public String toString() { return x + "," + y + "," + z; }
}
