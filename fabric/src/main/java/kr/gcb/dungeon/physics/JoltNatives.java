/* Ported from TerritoryWar (io.lightest.tw.physics / .model), same author's project.
 * Changes: package, dungeon terrain collider instead of a flat ground plane, and a
 * ragdoll built from whole limbs rather than one body per bone. */
package kr.gcb.dungeon.physics;

import com.github.stephengold.joltjni.Jolt;
import kr.gcb.ragdoll.compat.Host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unpacks the Jolt native library out of the plugin jar and initialises it.
 *
 * <p>Failure is never fatal: if the library will not load, {@link #isAvailable}
 * returns false and the server runs with physics switched off rather than refusing
 * to start.
 */
public final class JoltNatives {

    private JoltNatives() {}

    private static boolean attempted = false;
    private static boolean available = false;

    public static synchronized boolean isAvailable(Host plugin) {
        if (attempted) return available;
        attempted = true;
        try {
            loadAndInit(plugin);
            available = true;
            plugin.getLogger().info("Jolt " + Jolt.versionString() + " ready");
        } catch (Throwable t) {
            available = false;
            plugin.getLogger().warn("Jolt native failed to load - physics disabled, the rest of the game still runs", t);
        }
        return available;
    }

    private static void loadAndInit(Host plugin) throws IOException {
        String resource = resourcePath();
        String fileName = resource.substring(resource.lastIndexOf('/') + 1);

        Path dir = plugin.getDataFolder().resolve("natives");
        Files.createDirectories(dir);
        // One file per platform: a server folder moved between machines must not load the other one's library.
        Path target = dir.resolve(resource.substring(0, resource.indexOf("/com/")).replace('/', '-') + "-" + fileName);

        try (InputStream in = JoltNatives.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("native missing from jar: " + resource);
            byte[] bytes = in.readAllBytes();
            // Windows will not let us overwrite a DLL that is already loaded, so only
            // rewrite when the size differs - that also makes restarts cheap.
            if (!Files.exists(target) || Files.size(target) != bytes.length) {
                Files.write(target, bytes);
            }
        }
        System.load(target.toAbsolutePath().toString());

        // This exact order is required; skipping any of it kills the native on the
        // first PhysicsSystem construction.
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultAssertCallback();
        Jolt.installDefaultTraceCallback();
        Jolt.newFactory();
        Jolt.registerTypes();
    }

    /**
     * Where this platform's library sits inside the jar (the layout jolt-jni's own artifacts use).
     *
     * <p>Windows and Linux on x86-64 each have a second, faster build ("avx2" / "fma") that only runs
     * on processors with those instructions. The plain build runs everywhere, so that is the default;
     * {@code -Dremoteragdoll.jolt.variant=avx2} (Windows) or {@code =fma} (Linux) picks the other.
     */
    private static String resourcePath() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        String variant = System.getProperty("remoteragdoll.jolt.variant", "").toLowerCase(java.util.Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean arm32 = arch.equals("arm") || arch.startsWith("armv7");
        if (os.contains("win")) {
            if (x64) return "windows/x86-64" + (variant.equals("avx2") ? "-avx2" : "") + "/com/github/stephengold/joltjni.dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            if (x64) return "osx/x86-64/com/github/stephengold/libjoltjni.dylib";
            if (arm64) return "osx/aarch64/com/github/stephengold/libjoltjni.dylib";
        } else if (os.contains("linux")) {
            if (x64) return "linux/x86-64" + (variant.equals("fma") ? "-fma" : "") + "/com/github/stephengold/libjoltjni.so";
            if (arm64) return "linux/aarch64/com/github/stephengold/libjoltjni.so";
            if (arm32) return "linux/armhf/com/github/stephengold/libjoltjni.so";
        }
        throw new UnsupportedOperationException("no Jolt native for " + os + " / " + arch);
    }

}
