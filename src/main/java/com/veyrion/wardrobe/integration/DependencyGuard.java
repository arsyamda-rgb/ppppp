package com.veyrion.wardrobe.integration;

import com.veyrion.character.api.VeyrionCharacterAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.skinsrestorer.api.SkinsRestorerProvider;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Checks, while VeyrionWardrobe enables, that all three dependencies are present, in a supported version and
 * actually usable. Returns the problems (empty = fine); the plugin then disables itself with that list.
 * Nothing is replaced or emulated when a dependency is missing.
 */
public final class DependencyGuard {

    private DependencyGuard() {}

    public static List<String> check(PluginManager plugins) {
        List<String> problems = new ArrayList<>(DependencyRules.problems(name -> {
            Plugin plugin = plugins.getPlugin(name);
            return plugin == null ? null : new DependencyRules.Installed(plugin.isEnabled(), plugin.getDescription().getVersion());
        }));
        boolean characterFine = problems.stream().noneMatch(p -> p.startsWith(DependencyRules.CHARACTER));
        boolean skinsFine = problems.stream().noneMatch(p -> p.startsWith(DependencyRules.SKINS_RESTORER));
        if (characterFine) {
            checkCharacterApi(problems);
        }
        if (skinsFine) {
            checkSkinsRestorerApi(problems);
        }
        return problems;
    }

    private static void checkCharacterApi(List<String> problems) {
        try {
            VeyrionCharacterAPI api = VeyrionCharacterAPI.find().orElse(null);
            if (api == null) {
                problems.add("VeyrionCharacter is running but its API is not registered.");
                return;
            }
            if (api.apiVersion() != VeyrionCharacterAPI.API_VERSION) {
                problems.add("VeyrionCharacter API version " + api.apiVersion() + " does not match the one this plugin was built for ("
                        + VeyrionCharacterAPI.API_VERSION + ").");
            }
            // An older VeyrionCharacter jar has the same API version but lacks the Wardrobe additions.
            requireMethod(problems, "previews");
            requireMethod(problems, "has", UUID.class, double.class);
            requireMethod(problems, "withdraw", UUID.class, double.class);
        } catch (LinkageError e) {
            problems.add("VeyrionCharacter is too old (" + e + "). Install the build that has has/withdraw/previews.");
        }
    }

    private static void requireMethod(List<String> problems, String name, Class<?>... parameters) {
        try {
            VeyrionCharacterAPI.class.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            problems.add("VeyrionCharacter is too old: its API has no " + name + "(). Install the build that has has/withdraw/previews.");
        }
    }

    private static void checkSkinsRestorerApi(List<String> problems) {
        try {
            SkinsRestorerProvider.get();
        } catch (IllegalStateException e) {
            problems.add("SkinsRestorer is enabled but its API is not available (" + e.getMessage() + ").");
        } catch (LinkageError e) {
            problems.add("SkinsRestorer's API does not match this plugin (" + e + ").");
        }
    }
}
