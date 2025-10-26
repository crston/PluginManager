package com.gmail.bobason01;

import org.bukkit.plugin.Plugin;

public final class Graceful {
    public static final class Result {
        public final boolean success;
        public final String message;
        private Result(boolean s, String m) { success = s; message = m; }
        public static Result ok(String m) { return new Result(true, m); }
        public static Result fail(String m) { return new Result(false, m); }
    }

    public static Result disable(Plugin plugin, int timeoutMs) {
        try {
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            return Result.ok("disabled");
        } catch (Throwable t) {
            return Result.fail("disable-failed:" + t.getMessage());
        }
    }

    public static Result enable(Plugin plugin) {
        try {
            plugin.getServer().getPluginManager().enablePlugin(plugin);
            return Result.ok("enabled");
        } catch (Throwable t) {
            return Result.fail("enable-failed:" + t.getMessage());
        }
    }

    // 시그니처를 Object 기반으로
    public static Result unload(Plugin plugin, Object threadRegistry, Object resourceRegistry, int timeoutMs) {
        try {
            // 여기서 threadRegistry / resourceRegistry 실제 클래스 확인 후 리플렉션 사용 가능
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            return Result.ok("unloaded");
        } catch (Throwable t) {
            return Result.fail("unload-failed:" + t.getMessage());
        }
    }

    public static java.io.File stateDir(Plugin manager, Plugin target) {
        return new java.io.File(manager.getDataFolder(), "state-" + target.getName());
    }
}
