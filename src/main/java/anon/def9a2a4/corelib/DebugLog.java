package anon.def9a2a4.corelib;

import org.bukkit.plugin.Plugin;

/**
 * Opt-in verbose logging for DefCoreLib and the plugins built on it. Off by default; turned on with
 * {@code debug: true} in DefCoreLib's config.yml or live with {@code /defcorelib debug on}. Lines log at
 * INFO with a {@code [debug]} prefix, so they land in {@code logs/latest.log} where a server owner can
 * share them — java.util.logging's FINE never reaches that file on a stock Paper server.
 */
public final class DebugLog {
    private static volatile boolean enabled;

    private DebugLog() {}

    public static boolean enabled() {
        return enabled;
    }

    static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Logs {@code message} to {@code plugin}'s logger when debug logging is on. */
    public static void log(Plugin plugin, String message) {
        if (enabled) plugin.getLogger().info("[debug] " + message);
    }
}
