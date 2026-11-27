package org.bukkit.plugin;
public interface PluginManager { Plugin getPlugin(String name); void registerEvents(org.bukkit.event.Listener l, Plugin p); void disablePlugin(Plugin p); }
