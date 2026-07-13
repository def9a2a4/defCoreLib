package anon.def9a2a4.corelib;

import java.util.Set;

public record WorldFilter(boolean isAllowlist, Set<String> worlds) {

    public static WorldFilter allowlist(String... worlds) {
        return new WorldFilter(true, Set.of(worlds));
    }

    public static WorldFilter blocklist(String... worlds) {
        return new WorldFilter(false, Set.of(worlds));
    }

    public static WorldFilter allowlist(java.util.Collection<String> worlds) {
        return new WorldFilter(true, Set.copyOf(worlds));
    }

    public static WorldFilter blocklist(java.util.Collection<String> worlds) {
        return new WorldFilter(false, Set.copyOf(worlds));
    }

    public boolean isEnabled(String worldName) {
        boolean inList = worlds.contains(worldName);
        return isAllowlist ? inList : !inList;
    }
}
