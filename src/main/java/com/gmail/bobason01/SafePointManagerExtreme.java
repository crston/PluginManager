package com.gmail.bobason01;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.Future;

public final class SafePointManagerExtreme {
    public enum UnloadMode { GRACEFUL, EXPERIMENTAL, FORCED }

    private final Plugin manager;
    private final AdapterRegistry adapters;
    private final StateRegistry state;
    private final AsyncPool pool;
    private final double minTps;
    private final int deferPlayers;
    private final int timeoutMs;
    private final UnloadMode unloadMode;

    public SafePointManagerExtreme(
            Plugin manager,
            AdapterRegistry adapters,
            StateRegistry state,
            AsyncPool pool,
            double minTps,
            int deferPlayers,
            int timeoutMs,
            UnloadMode mode
    ) {
        this.manager = manager;
        this.adapters = adapters;
        this.state = state;
        this.pool = pool;
        this.minTps = minTps;
        this.deferPlayers = deferPlayers;
        this.timeoutMs = timeoutMs;
        this.unloadMode = mode;
    }

    private double getTps() {
        try {
            Method m = Bukkit.getServer().getClass().getMethod("getTPS");
            double[] tps = (double[]) m.invoke(Bukkit.getServer());
            return tps[0];
        } catch (Throwable t) {
            return 20.0;
        }
    }

    private boolean tpsOk() { return getTps() >= minTps; }
    private boolean shouldDefer() { return Bukkit.getOnlinePlayers().size() >= deferPlayers; }

    public Result reload(Plugin target) {
        if (target == null) return Result.fail("not-found");
        if (target.equals(manager)) return Result.fail("self");
        if (shouldDefer()) return Result.fail("defer");
        if (!tpsOk()) return Result.fail("tps-low");

        final String key = "op:" + target.getName();
        state.markBusy(key);
        state.register(key, target);

        PluginAdapter a = adapters.find(target);
        File dir = Graceful.stateDir(manager, target);

        try {
            Future<?> prep = a == null ? null : pool.submit(() -> {
                try { a.prepareExport(manager, target, dir); } catch (Exception ignored) {}
            });
            if (prep != null) prep.get();
            if (a != null) a.commitExport(manager, target, dir);

            Object prev = state.get(key);
            if (prev == null) { state.markFree(key); state.remove(key); return Result.fail("state-missing"); }

            Graceful.Result d = Graceful.disable(target, timeoutMs);
            if (!d.success) { state.markFree(key); state.remove(key); return Result.fail(d.message); }

            Graceful.Result e = Graceful.enable(target);
            if (!e.success) { state.markFree(key); state.remove(key); return Result.fail(e.message); }

            Future<?> prepImport = a == null ? null : pool.submit(() -> {
                try { a.prepareImport(manager, target, dir); } catch (Exception ignored) {}
            });
            if (prepImport != null) prepImport.get();
            if (a != null) a.commitImport(manager, target, dir);

            state.markFree(key);
            state.remove(key);
            return Result.ok("reloaded");
        } catch (Throwable t) {
            state.markFree(key);
            state.remove(key);
            return Result.fail("err:" + t.getClass().getSimpleName());
        }
    }

    public Result disable(Plugin target) {
        if (target == null) return Result.fail("not-found");
        if (target.equals(manager)) return Result.fail("self");
        if (shouldDefer()) return Result.fail("defer");
        if (!tpsOk()) return Result.fail("tps-low");

        final String key = "op:" + target.getName();
        state.markBusy(key);
        state.register(key, target);

        PluginAdapter a = adapters.find(target);
        File dir = Graceful.stateDir(manager, target);

        try {
            Future<?> prep = a == null ? null : pool.submit(() -> {
                try { a.prepareExport(manager, target, dir); } catch (Exception ignored) {}
            });
            if (prep != null) prep.get();
            if (a != null) a.commitExport(manager, target, dir);

            Object prev = state.get(key);
            if (prev == null) { state.markFree(key); state.remove(key); return Result.fail("state-missing"); }

            Graceful.Result d = Graceful.disable(target, timeoutMs);
            if (!d.success) { state.markFree(key); state.remove(key); return Result.fail(d.message); }

            state.markFree(key);
            state.remove(key);
            return Result.ok("disabled");
        } catch (Throwable t) {
            state.markFree(key);
            state.remove(key);
            return Result.fail("err:" + t.getClass().getSimpleName());
        }
    }

    public Result enable(Plugin target) {
        if (target == null) return Result.fail("not-found");

        final String key = "op:" + target.getName();
        state.markBusy(key);
        state.register(key, target);

        PluginAdapter a = adapters.find(target);
        File dir = Graceful.stateDir(manager, target);

        try {
            Graceful.Result e = Graceful.enable(target);
            if (!e.success) { state.markFree(key); state.remove(key); return Result.fail(e.message); }

            Future<?> prepImport = a == null ? null : pool.submit(() -> {
                try { a.prepareImport(manager, target, dir); } catch (Exception ignored) {}
            });
            if (prepImport != null) prepImport.get();
            if (a != null) a.commitImport(manager, target, dir);

            state.markFree(key);
            state.remove(key);
            return Result.ok("enabled");
        } catch (Throwable t) {
            state.markFree(key);
            state.remove(key);
            return Result.fail("err:" + t.getClass().getSimpleName());
        }
    }

    public Result unload(Plugin target) {
        if (target == null) return Result.fail("not-found");
        if (target.equals(manager)) return Result.fail("self");
        if (shouldDefer()) return Result.fail("defer");
        if (!tpsOk()) return Result.fail("tps-low");

        final String key = "op:" + target.getName();
        state.markBusy(key);
        state.register(key, target);

        PluginAdapter a = adapters.find(target);
        File dir = Graceful.stateDir(manager, target);

        try {
            Future<?> prep = a == null ? null : pool.submit(() -> {
                try { a.prepareExport(manager, target, dir); } catch (Exception ignored) {}
            });
            if (prep != null) prep.get();
            if (a != null) a.commitExport(manager, target, dir);

            Object prev = state.get(key);
            if (prev == null) { state.markFree(key); state.remove(key); return Result.fail("state-missing"); }

            Graceful.Result r;
            if (unloadMode == UnloadMode.GRACEFUL) {
                r = Graceful.unload(target,
                        tryGetThreadRegistry(manager),
                        tryGetResourceRegistry(manager),
                        timeoutMs);
            } else if (unloadMode == UnloadMode.EXPERIMENTAL) {
                r = ExperimentalPaperUnloader.deepUnload(
                        target,
                        (tryGetThreadRegistry(manager) instanceof ThreadRegistry)
                                ? (ThreadRegistry) tryGetThreadRegistry(manager) : null,
                        (tryGetResourceRegistry(manager) instanceof ResourceRegistry)
                                ? (ResourceRegistry) tryGetResourceRegistry(manager) : null,
                        timeoutMs);
            } else {
                boolean ok = PluginUnloader.unload(target);
                r = ok ? Graceful.Result.ok("forced-unloaded") : Graceful.Result.fail("forced-failed");
            }

            if (!r.success) { state.markFree(key); state.remove(key); return Result.fail(r.message); }

            state.markFree(key);
            state.remove(key);
            return Result.ok("unloaded");
        } catch (Throwable t) {
            state.markFree(key);
            state.remove(key);
            return Result.fail("err:" + t.getClass().getSimpleName());
        }
    }

    private Object tryGetThreadRegistry(Plugin manager) {
        try {
            Method m = manager.getClass().getMethod("getThreadRegistry");
            return m.invoke(manager);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object tryGetResourceRegistry(Plugin manager) {
        try {
            Method m = manager.getClass().getMethod("getResourceRegistry");
            return m.invoke(manager);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        private Result(boolean s, String m) { success = s; message = m; }
        public static Result ok(String m) { return new Result(true, m); }
        public static Result fail(String m) { return new Result(false, m); }
    }
}
