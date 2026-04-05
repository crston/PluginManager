package com.gmail.bobason01;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public final class PluginManagerCommand implements TabExecutor {
    private final PluginManager core;
    private final String prefix = ChatColor.GRAY + "[" + ChatColor.AQUA + "PluginManager" + ChatColor.GRAY + "] " + ChatColor.RESET;

    private volatile List<String> cachedJarBaseNames = Collections.emptyList();
    private volatile Map<String, String> cachedJarIdByBase = Collections.emptyMap();
    private volatile long lastScan = 0L;
    private static final long CACHE_DURATION_MS = 5000L;

    public PluginManagerCommand(PluginManager core) {
        this.core = core;
    }

    private void collectDependents(Plugin target, Set<Plugin> result) {
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p.equals(target) || result.contains(p)) continue;
            List<String> deps = p.getDescription().getDepend();
            List<String> softDeps = p.getDescription().getSoftDepend();
            boolean depends = false;
            if (deps != null && deps.contains(target.getName())) depends = true;
            if (softDeps != null && softDeps.contains(target.getName())) depends = true;
            if (depends) {
                result.add(p);
                collectDependents(p, result);
            }
        }
    }

    private File getPluginJar(String pluginName) {
        refreshJarCacheIfNeeded();
        File dir = core.pluginsDir();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files != null) {
            for (File f : files) {
                String id = readPluginIdFromJar(f);
                if (pluginName.equalsIgnoreCase(id)) return f;
            }
        }
        return new File(dir, pluginName + ".jar");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pluginmanager.use")) {
            sender.sendMessage(prefix + ChatColor.RED + "no permission");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }

        final String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            final String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "all";
            List<Plugin> list = Arrays.asList(Bukkit.getPluginManager().getPlugins());
            if (mode.equals("enabled")) list = list.stream().filter(Plugin::isEnabled).collect(Collectors.toList());
            if (mode.equals("disabled")) list = list.stream().filter(p -> !p.isEnabled()).collect(Collectors.toList());
            list.sort(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER));
            String s = list.stream()
                    .map(p -> (p.isEnabled() ? ChatColor.GREEN : ChatColor.RED) + p.getName() + ChatColor.RESET)
                    .collect(Collectors.joining(ChatColor.GRAY + ", " + ChatColor.RESET));
            sender.sendMessage(prefix + "Plugins: " + s);
            return true;
        }

        if (args.length < 2) {
            help(sender, label);
            return true;
        }

        final String nameArg = join(args, 1);

        if (sub.equals("load")) {
            refreshJarCacheIfNeeded();

            File jar = new File(core.pluginsDir(), nameArg.endsWith(".jar") ? nameArg : nameArg + ".jar");
            if (!jar.exists()) {
                sender.sendMessage(prefix + ChatColor.RED + "jar not found: " + jar.getName());
                return true;
            }

            String base = baseName(jar.getName()).toLowerCase(Locale.ROOT);
            String idName = cachedJarIdByBase.get(base);
            if (idName == null) {
                idName = readPluginIdFromJar(jar);
            }
            if (idName != null) {
                Plugin already = Bukkit.getPluginManager().getPlugin(idName);
                if (already != null) {
                    sender.sendMessage(prefix + ChatColor.RED + "already loaded identifier: " + idName);
                    return true;
                }
            }

            Bukkit.getScheduler().runTask(core, () -> {
                try {
                    Plugin loaded = Bukkit.getPluginManager().loadPlugin(jar);
                    if (loaded != null) {
                        boolean needsVaultReload = false;
                        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
                        if (vault != null && vault.isEnabled() && !loaded.getName().equalsIgnoreCase("Vault")) {
                            String ln = loaded.getName().toLowerCase(Locale.ROOT);
                            if (ln.contains("cmi") || ln.contains("economy") || ln.contains("essentials")) {
                                needsVaultReload = true;
                            }
                        }

                        if (needsVaultReload) {
                            Bukkit.getPluginManager().disablePlugin(vault);
                            PluginUnloader.unload(vault);
                        }

                        Bukkit.getPluginManager().enablePlugin(loaded);
                        trySyncCommands();
                        sender.sendMessage(prefix + ChatColor.GREEN + "loaded " + loaded.getName());

                        if (needsVaultReload) {
                            File vaultJar = getPluginJar("Vault");
                            try {
                                Plugin reloadedVault = Bukkit.getPluginManager().loadPlugin(vaultJar);
                                if (reloadedVault != null) Bukkit.getPluginManager().enablePlugin(reloadedVault);
                                sender.sendMessage(prefix + ChatColor.GREEN + "auto-reloaded Vault to prevent conflicts.");
                            } catch (Throwable ignored) {}
                        }
                    } else {
                        sender.sendMessage(prefix + ChatColor.RED + "load failed: " + nameArg);
                    }
                } catch (Throwable t) {
                    sender.sendMessage(prefix + ChatColor.RED + "exception: " + t.getMessage());
                    t.printStackTrace();
                } finally {
                    invalidateJarCache();
                }
            });
            return true;
        }

        Plugin target = core.findPlugin(nameArg);
        if (target == null) {
            sender.sendMessage(prefix + ChatColor.RED + "not found: " + nameArg);
            return true;
        }

        final String pluginName = target.getDescription().getName();

        Bukkit.getScheduler().runTask(core, () -> {
            boolean ok = false;
            String msg = "";

            try {
                switch (sub) {
                    case "enable": {
                        boolean needsVaultReload = false;
                        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
                        if (vault != null && vault.isEnabled() && !target.getName().equalsIgnoreCase("Vault")) {
                            String ln = target.getName().toLowerCase(Locale.ROOT);
                            if (ln.contains("cmi") || ln.contains("economy") || ln.contains("essentials")) {
                                needsVaultReload = true;
                            }
                        }
                        if (needsVaultReload) {
                            Bukkit.getPluginManager().disablePlugin(vault);
                            PluginUnloader.unload(vault);
                        }

                        Bukkit.getPluginManager().enablePlugin(target);
                        ok = target.isEnabled();
                        msg = ok ? "enabled" : "enable-failed";

                        if (needsVaultReload) {
                            File vaultJar = getPluginJar("Vault");
                            try {
                                Plugin reloadedVault = Bukkit.getPluginManager().loadPlugin(vaultJar);
                                if (reloadedVault != null) Bukkit.getPluginManager().enablePlugin(reloadedVault);
                            } catch (Throwable ignored) {}
                        }
                        break;
                    }
                    case "disable": {
                        Bukkit.getPluginManager().disablePlugin(target);
                        ok = !target.isEnabled();
                        msg = ok ? "disabled" : "disable-failed";
                        break;
                    }
                    case "unload": {
                        ok = PluginUnloader.unload(target);
                        msg = ok ? "unloaded" : "unload-failed";
                        break;
                    }
                    case "reload": {
                        Set<Plugin> deps = new LinkedHashSet<>();
                        collectDependents(target, deps);

                        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
                        if (vault != null && !target.getName().equalsIgnoreCase("Vault")) {
                            String lower = target.getName().toLowerCase(Locale.ROOT);
                            if (lower.contains("cmi") || lower.contains("economy") || lower.contains("essentials")) {
                                deps.add(vault);
                            }
                        }

                        List<Plugin> depList = new ArrayList<>(deps);
                        Collections.reverse(depList);

                        for (Plugin p : depList) Bukkit.getPluginManager().disablePlugin(p);
                        Bukkit.getPluginManager().disablePlugin(target);

                        for (Plugin p : depList) PluginUnloader.unload(p);
                        boolean uok = PluginUnloader.unload(target);

                        if (uok) {
                            File jar = getPluginJar(target.getName());
                            try {
                                Plugin reloaded = Bukkit.getPluginManager().loadPlugin(jar);
                                if (reloaded != null) {
                                    Bukkit.getPluginManager().enablePlugin(reloaded);
                                    ok = reloaded.isEnabled();

                                    Collections.reverse(depList);
                                    for (Plugin p : depList) {
                                        File depJar = getPluginJar(p.getName());
                                        try {
                                            Plugin depReloaded = Bukkit.getPluginManager().loadPlugin(depJar);
                                            if (depReloaded != null) Bukkit.getPluginManager().enablePlugin(depReloaded);
                                        } catch (Throwable ignored) {}
                                    }
                                    trySyncCommands();
                                } else {
                                    ok = false;
                                }
                            } catch (Throwable ignored) {
                                ok = false;
                            }
                        } else {
                            ok = false;
                        }
                        msg = ok ? "reloaded" : "reload-failed";
                        break;
                    }
                    default:
                        help(sender, label);
                        return;
                }
            } finally {
                invalidateJarCache();
            }

            sender.sendMessage(prefix + (ok ? ChatColor.GREEN : ChatColor.RED) + msg + ChatColor.GRAY + " (" + pluginName + ")");
        });

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("enable", "disable", "unload", "reload", "list", "load").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String a = args[0].toLowerCase(Locale.ROOT);
            String q = args[1].toLowerCase(Locale.ROOT);

            if (a.equals("list")) {
                return Arrays.asList("all", "enabled", "disabled").stream()
                        .filter(s -> s.startsWith(q))
                        .collect(Collectors.toList());
            }
            if (a.equals("enable")) {
                return filterPluginsByEnabled(q, false);
            }
            if (a.equals("disable") || a.equals("unload") || a.equals("reload")) {
                return filterPluginsByEnabled(q, true);
            }
            if (a.equals("load")) {
                return filterUnloadableJars(q);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filterPluginsByEnabled(String q, boolean enabled) {
        return Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .filter(p -> p.isEnabled() == enabled)
                .map(Plugin::getName)
                .map(String::toLowerCase)
                .filter(n -> n.contains(q))
                .sorted()
                .limit(50)
                .collect(Collectors.toList());
    }

    private List<String> filterUnloadableJars(String q) {
        refreshJarCacheIfNeeded();

        Set<String> loadedIds = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(p -> p.getDescription().getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        return cachedJarBaseNames.stream()
                .filter(base -> {
                    String id = cachedJarIdByBase.get(base);
                    return id == null || !loadedIds.contains(id.toLowerCase(Locale.ROOT));
                })
                .filter(base -> base.contains(q))
                .sorted()
                .limit(50)
                .collect(Collectors.toList());
    }

    private void refreshJarCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastScan < CACHE_DURATION_MS) return;

        File dir = core.pluginsDir();
        File[] jars = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) {
            cachedJarBaseNames = Collections.emptyList();
            cachedJarIdByBase = Collections.emptyMap();
            lastScan = now;
            return;
        }

        List<String> baseNames = new ArrayList<>(jars.length);
        Map<String, String> idByBase = new HashMap<>(jars.length * 2);

        for (File f : jars) {
            String base = baseName(f.getName()).toLowerCase(Locale.ROOT);
            baseNames.add(base);

            String id = readPluginIdFromJar(f);
            if (id != null) idByBase.put(base, id);
        }

        cachedJarBaseNames = baseNames;
        cachedJarIdByBase = idByBase;
        lastScan = now;
    }

    private void invalidateJarCache() {
        lastScan = 0L;
    }

    private String readPluginIdFromJar(File jar) {
        try {
            PluginDescriptionFile desc = core.getPluginLoader().getPluginDescription(jar);
            return desc == null ? null : desc.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String baseName(String fileName) {
        String n = fileName;
        int i = n.lastIndexOf('.');
        return (i > 0) ? n.substring(0, i) : n;
    }

    private void help(CommandSender s, String l) {
        s.sendMessage(prefix + ChatColor.AQUA + "/" + l + " list [all|enabled|disabled]");
        s.sendMessage(prefix + ChatColor.AQUA + "/" + l + " enable <plugin>");
        s.sendMessage(prefix + ChatColor.AQUA + "/" + l + " disable <plugin>");
        s.sendMessage(prefix + ChatColor.AQUA + "/" + l + " unload <plugin>");
        s.sendMessage(prefix + ChatColor.AQUA + "/" + l + " reload <plugin>");
        s.sendMessage(prefix + ChatColor.AQUA + "/" + l + " load <jarname>");
    }

    private static String join(String[] a, int i) {
        StringBuilder b = new StringBuilder();
        for (int k = i; k < a.length; k++) { if (k > i) b.append(' '); b.append(a[k]); }
        return b.toString();
    }

    private void trySyncCommands() {
        try {
            java.lang.reflect.Method m = Bukkit.getServer().getClass().getMethod("syncCommands");
            m.invoke(Bukkit.getServer());
            Bukkit.getLogger().info("[PluginManager] Commands synced.");
        } catch (NoSuchMethodException e) {
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[PluginManager] syncCommands failed: " + t.getMessage());
        }
    }
}