package com.gmail.bobason01;

import org.bukkit.plugin.Plugin;
import java.io.File;

public interface PluginAdapter {
    boolean supports(Plugin plugin);
    default void prepareExport(Plugin manager, Plugin plugin, File dir) throws Exception {}
    default void commitExport(Plugin manager, Plugin plugin, File dir) throws Exception {}
    default void prepareImport(Plugin manager, Plugin plugin, File dir) throws Exception {}
    default void commitImport(Plugin manager, Plugin plugin, File dir) throws Exception {}
}
