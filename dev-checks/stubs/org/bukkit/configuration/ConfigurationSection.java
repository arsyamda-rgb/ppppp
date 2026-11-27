package org.bukkit.configuration;
public interface ConfigurationSection { int getInt(String p,int d); double getDouble(String p,double d); boolean getBoolean(String p,boolean d); java.util.List<String> getStringList(String p); }
