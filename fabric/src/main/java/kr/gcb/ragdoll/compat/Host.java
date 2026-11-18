package kr.gcb.ragdoll.compat;

import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;

/**
 * What the ragdoll code needs from whatever is hosting it: somewhere to log, somewhere to unpack
 * the native library, and a few numbers from a settings file. (In the Bukkit original this was the plugin.)
 */
public final class Host {
    private final Logger logger;
    private final Path dataFolder;
    private final Properties settings;

    public Host(Logger logger, Path dataFolder, Properties settings) {
        this.logger = logger;
        this.dataFolder = dataFolder;
        this.settings = settings;
    }

    private volatile java.util.concurrent.Executor mainThread = Runnable::run;

    /** Says how to get back onto the server thread (skins arrive on another one). */
    public void mainThread(java.util.concurrent.Executor server) { mainThread = server; }

    public void runOnMain(Runnable task) { mainThread.execute(task); }

    public Logger getLogger() { return logger; }

    public Path getDataFolder() { return dataFolder; }

    public Host getConfig() { return this; }

    public double getDouble(String key, double fallback) {
        try { return Double.parseDouble(settings.getProperty(key, "").trim()); } catch (RuntimeException e) { return fallback; }
    }

    public int getInt(String key, int fallback) {
        try { return Integer.parseInt(settings.getProperty(key, "").trim()); } catch (RuntimeException e) { return fallback; }
    }

    public boolean getBoolean(String key, boolean fallback) {
        String v = settings.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }
}
