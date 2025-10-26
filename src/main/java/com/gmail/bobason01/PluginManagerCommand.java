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

    // 파일 / 식별자 캐시
    private volatile List<String> cachedJarBaseNames = Collections.emptyList();             // 확장자 없는 jar 파일명(lower)
    private volatile Map<String, String> cachedJarIdByBase = Collections.emptyMap();        // baseName -> plugin.yml name
    private volatile long lastScan = 0L;
    private static final long CACHE_DURATION_MS = 5000L;

    public PluginManagerCommand(PluginManager core) {
        this.core = core;
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

        // load 는 플러그인 인스턴스가 없어야 함
        if (sub.equals("load")) {
            refreshJarCacheIfNeeded();

            File jar = new File(core.pluginsDir(), nameArg.endsWith(".jar") ? nameArg : nameArg + ".jar");
            if (!jar.exists()) {
                sender.sendMessage(prefix + ChatColor.RED + "jar not found: " + jar.getName());
                return true;
            }

            // 식별자 사전 점검 (이미 로드된 동일 식별자면 실패)
            String base = baseName(jar.getName()).toLowerCase(Locale.ROOT);
            String idName = cachedJarIdByBase.get(base);
            if (idName == null) {
                idName = readPluginIdFromJar(jar); // 캐시 미스 시 한 번 더 시도
            }
            if (idName != null) {
                Plugin already = Bukkit.getPluginManager().getPlugin(idName);
                if (already != null) {
                    sender.sendMessage(prefix + ChatColor.RED + "already loaded identifier: " + idName);
                    return true;
                }
            }

            try {
                Plugin loaded = Bukkit.getPluginManager().loadPlugin(jar);
                if (loaded != null) {
                    Bukkit.getPluginManager().enablePlugin(loaded);
                    trySyncCommands();
                    sender.sendMessage(prefix + ChatColor.GREEN + "loaded " + loaded.getName());
                } else {
                    sender.sendMessage(prefix + ChatColor.RED + "load failed: " + nameArg);
                }
            } catch (Throwable t) {
                sender.sendMessage(prefix + ChatColor.RED + "exception: " + t.getMessage());
                t.printStackTrace();
            }
            return true;
        }

        // load 이외의 서브커맨드: 대상 플러그인 탐색
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
                        Bukkit.getPluginManager().enablePlugin(target);
                        ok = target.isEnabled();
                        msg = ok ? "enabled" : "enable-failed";
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
                        // disable → unload → load → enable
                        Bukkit.getPluginManager().disablePlugin(target);
                        boolean uok = PluginUnloader.unload(target);
                        if (uok) {
                            File jar = new File(core.pluginsDir(), target.getName() + ".jar");
                            try {
                                Plugin reloaded = Bukkit.getPluginManager().loadPlugin(jar);
                                if (reloaded != null) {
                                    Bukkit.getPluginManager().enablePlugin(reloaded);
                                    trySyncCommands();
                                    ok = reloaded.isEnabled();
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
                // 탭 자동완성 품질을 위해 캐시 갱신
                if (sub.equals("unload") || sub.equals("reload") || sub.equals("load") || sub.equals("enable") || sub.equals("disable")) {
                    invalidateJarCache();
                }
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
                // 꺼져 있는 플러그인만
                return filterPluginsByEnabled(q, false);
            }
            if (a.equals("disable") || a.equals("unload") || a.equals("reload")) {
                // 켜져 있는 플러그인만
                return filterPluginsByEnabled(q, true);
            }
            if (a.equals("load")) {
                // 아직 로드되지 않은 식별자의 jar 만
                return filterUnloadableJars(q);
            }
        }
        return Collections.emptyList();
    }

    /* ===================== 내부 유틸 ===================== */

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

        // 현재 로드된 식별자 집합
        Set<String> loadedIds = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(p -> p.getDescription().getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        // jar 베이스명 후보 중, 식별자가 미로드 상태인 것만 노출
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
        lastScan = 0L; // 다음 요청에서 다시 스캔
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
            // Paper / Purpur 계열은 이 메서드 제공
            java.lang.reflect.Method m = Bukkit.getServer().getClass().getMethod("syncCommands");
            m.invoke(Bukkit.getServer());
            Bukkit.getLogger().info("[PluginManager] Commands synced.");
        } catch (NoSuchMethodException e) {
            // Spigot 계열이면 없음 > 무시
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[PluginManager] syncCommands failed: " + t.getMessage());
        }
    }
}
