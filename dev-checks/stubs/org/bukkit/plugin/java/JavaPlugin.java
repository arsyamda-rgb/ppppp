package org.bukkit.plugin.java;
import java.io.File; import java.util.logging.Logger;
public abstract class JavaPlugin implements org.bukkit.plugin.Plugin {
  public void onEnable(){} public void onDisable(){}
  public boolean isEnabled(){return true;} public org.bukkit.plugin.PluginDescriptionFile getDescription(){return null;}
  public Logger getLogger(){return null;} public File getDataFolder(){return null;} public void saveDefaultConfig(){}
  public org.bukkit.configuration.file.FileConfiguration getConfig(){return null;}
  public org.bukkit.Server getServer(){return null;}
}
