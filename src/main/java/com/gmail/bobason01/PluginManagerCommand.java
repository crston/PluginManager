package com.gmail.bobason01;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.stream.Collectors;

public class PluginManagerCommand implements TabExecutor {
    private final String prefix = ChatColor.GRAY + "[" + ChatColor.AQUA + "PluginManager" + ChatColor.GRAY + "] " + ChatColor.RESET;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pluginmanager.use")) {
            sender.sendMessage(prefix + ChatColor.RED + "You do not have permission");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            handleList(sender, args);
            return true;
        }

        if (sub.equals("confirm")) {
            if (args.length < 3) {
                sender.sendMessage(prefix + ChatColor.RED + "Usage: /" + label + " confirm <disable|reload> <plugin>");
                return true;
            }
            String action = args[1].toLowerCase(Locale.ROOT);
            String name = joinRest(args, 2);
            Plugin target = PluginManager.getInstance().findPlugin(name);
            if (target == null) {
                sender.sendMessage(prefix + ChatColor.RED + "Plugin not found: " + name);
                return true;
            }
            PluginManager.PendingAction pa = PluginManager.getInstance().getPending(target.getName());
            if (pa == null || !pa.action.equals(action)) {
                sender.sendMessage(prefix + ChatColor.RED + "No pending confirm for " + target.getName());
                return true;
            }
            PluginManager.getInstance().clearPending(target.getName());

            PluginManager.ActionResult r;
            if (action.equals("disable")) {
                r = PluginManager.getInstance().doDisable(target);
            } else if (action.equals("reload")) {
                r = PluginManager.getInstance().doReload(target);
            } else {
                sender.sendMessage(prefix + ChatColor.RED + "Unknown confirm action");
                return true;
            }
            sender.sendMessage(prefix + (r.success ? ChatColor.GREEN : ChatColor.RED) + r.message + ChatColor.GRAY + " (" + target.getName() + ")");
            return true;
        }

        if (args.length < 2) {
            sendHelp(sender, label);
            return true;
        }

        String name = joinRest(args, 1);
        Plugin target = PluginManager.getInstance().findPlugin(name);
        if (target == null) {
            sender.sendMessage(prefix + ChatColor.RED + "Plugin not found: " + name);
            return true;
        }

        if (sub.equals("enable")) {
            runMainThread(() -> {
                PluginManager.ActionResult r = PluginManager.getInstance().enablePluginSafe(target);
                sender.sendMessage(prefix + (r.success ? ChatColor.GREEN : ChatColor.RED) + r.message + ChatColor.GRAY + " (" + target.getName() + ")");
            });
            return true;
        } else if (sub.equals("disable")) {
            runMainThread(() -> {
                PluginManager.CommandFeedback fb = new PluginManager.CommandFeedback();
                PluginManager.ActionResult r = PluginManager.getInstance().disablePluginSafe(target, fb);
                if (!r.success && fb.reason != null && !fb.reason.isEmpty()) {
                    TextComponent msg = new TextComponent(prefix + ChatColor.RED + r.message + " ");
                    TextComponent click = new TextComponent(ChatColor.YELLOW + "[Click here to confirm]");
                    click.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/pluginmanager confirm disable " + target.getName()));
                    msg.addExtra(click);
                    sender.spigot().sendMessage(msg);
                } else {
                    sender.sendMessage(prefix + (r.success ? ChatColor.GREEN : ChatColor.RED) + r.message +
                            (fb.reason.isEmpty() ? "" : " [" + fb.reason + "]") + ChatColor.GRAY + " (" + target.getName() + ")");
                }
            });
            return true;
        } else if (sub.equals("reload")) {
            runMainThread(() -> {
                PluginManager.CommandFeedback fb = new PluginManager.CommandFeedback();
                PluginManager.ActionResult r = PluginManager.getInstance().reloadPluginSafe(target, fb);
                if (!r.success && fb.reason != null && !fb.reason.isEmpty()) {
                    TextComponent msg = new TextComponent(prefix + ChatColor.RED + r.message + " ");
                    TextComponent click = new TextComponent(ChatColor.YELLOW + "[Click here to confirm]");
                    click.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/pluginmanager confirm reload " + target.getName()));
                    msg.addExtra(click);
                    sender.spigot().sendMessage(msg);
                } else {
                    sender.sendMessage(prefix + (r.success ? ChatColor.GREEN : ChatColor.RED) + r.message +
                            (fb.reason.isEmpty() ? "" : " [" + fb.reason + "]") + ChatColor.GRAY + " (" + target.getName() + ")");
                }
            });
            return true;
        } else {
            sendHelp(sender, label);
            return true;
        }
    }

    private void handleList(CommandSender sender, String[] args) {
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "all";
        List<Plugin> plugins = Arrays.asList(Bukkit.getPluginManager().getPlugins());
        if (mode.equals("enabled")) {
            plugins = plugins.stream().filter(Plugin::isEnabled).collect(Collectors.toList());
        } else if (mode.equals("disabled")) {
            plugins = plugins.stream().filter(p -> !p.isEnabled()).collect(Collectors.toList());
        }
        plugins.sort(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER));
        String joined = plugins.stream()
                .map(p -> (p.isEnabled() ? ChatColor.GREEN : ChatColor.RED) + p.getName() + ChatColor.RESET)
                .collect(Collectors.joining(ChatColor.GRAY + ", " + ChatColor.RESET));
        sender.sendMessage(prefix + "Plugins: " + joined);
    }

    private void runMainThread(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(PluginManager.getInstance(), r);
    }

    private String joinRest(String[] arr, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < arr.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(prefix + ChatColor.AQUA + "/" + label + " enable <plugin>");
        sender.sendMessage(prefix + ChatColor.AQUA + "/" + label + " disable <plugin>");
        sender.sendMessage(prefix + ChatColor.AQUA + "/" + label + " reload <plugin>");
        sender.sendMessage(prefix + ChatColor.AQUA + "/" + label + " confirm <disable|reload> <plugin>");
        sender.sendMessage(prefix + ChatColor.AQUA + "/" + label + " list [all|enabled|disabled]");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("enable", "disable", "reload", "confirm", "list").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String action = args[0].toLowerCase(Locale.ROOT);
            String q = args[1].toLowerCase(Locale.ROOT);

            if (action.equals("list")) {
                return Arrays.asList("all", "enabled", "disabled").stream()
                        .filter(s -> s.startsWith(q))
                        .collect(Collectors.toList());
            }
            if (action.equals("enable")) {
                return Arrays.stream(Bukkit.getPluginManager().getPlugins())
                        .filter(p -> !p.isEnabled())
                        .map(Plugin::getName)
                        .filter(n -> n.toLowerCase(Locale.ROOT).contains(q))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .collect(Collectors.toList());
            }
            if (action.equals("disable") || action.equals("reload")) {
                return Arrays.stream(Bukkit.getPluginManager().getPlugins())
                        .filter(Plugin::isEnabled)
                        .map(Plugin::getName)
                        .filter(n -> n.toLowerCase(Locale.ROOT).contains(q))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .collect(Collectors.toList());
            }
            if (action.equals("confirm")) {
                return Arrays.asList("disable", "reload").stream()
                        .filter(s -> s.startsWith(q))
                        .collect(Collectors.toList());
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("confirm")) {
            String q = args[2].toLowerCase(Locale.ROOT);
            return Arrays.stream(Bukkit.getPluginManager().getPlugins())
                    .map(Plugin::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).contains(q))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
