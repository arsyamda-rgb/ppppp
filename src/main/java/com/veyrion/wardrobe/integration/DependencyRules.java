package com.veyrion.wardrobe.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The version rules for the three hard dependencies, with no Bukkit in it so they can be checked on their own.
 * Versions: ItemsAdder 4.x (tested with 4.0.3), SkinsRestorer 15.x (API verified against 15.12.6).
 */
public final class DependencyRules {

    public record Installed(boolean enabled, String version) {}

    public static final String CHARACTER = "VeyrionCharacter";
    public static final String ITEMS_ADDER = "ItemsAdder";
    public static final String SKINS_RESTORER = "SkinsRestorer";

    private static final Pattern VERSION = Pattern.compile("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    private DependencyRules() {}

    public record Version(int major, int minor, int patch) {}

    /** @return the version from "4.0.3", "15.12.6-SNAPSHOT" ..., or null if it does not start with a number */
    public static @Nullable Version parse(@Nullable String version) {
        if (version == null) {
            return null;
        }
        Matcher m = VERSION.matcher(version.strip());
        if (!m.find()) {
            return null;
        }
        try {
            return new Version(Integer.parseInt(m.group(1)), m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
        } catch (NumberFormatException e) { // absurdly long number
            return null;
        }
    }

    /** @param lookup plugin name to its state, or null when the plugin is not installed */
    public static List<String> problems(Function<String, @Nullable Installed> lookup) {
        List<String> problems = new ArrayList<>();
        check(problems, lookup.apply(CHARACTER), CHARACTER, 0, Integer.MAX_VALUE, "any");
        check(problems, lookup.apply(ITEMS_ADDER), ITEMS_ADDER, 4, Integer.MAX_VALUE, "4.x or newer (tested with 4.0.3)");
        check(problems, lookup.apply(SKINS_RESTORER), SKINS_RESTORER, 15, 15, "15.x (API verified with 15.12.6)");
        return problems;
    }

    private static void check(List<String> problems, @Nullable Installed plugin, String name, int minMajor,
                              int maxMajor, String wanted) {
        if (plugin == null) {
            problems.add(name + " is not installed. VeyrionWardrobe needs it.");
            return;
        }
        if (!plugin.enabled()) {
            problems.add(name + " is installed but did not enable. Fix its own error first (see the log above).");
            return;
        }
        if (minMajor == 0) {
            return; // no version rule; the API is checked separately
        }
        Version version = parse(plugin.version());
        if (version == null) {
            problems.add(name + " has a version this plugin cannot read (\"" + plugin.version() + "\"); needs " + wanted + ".");
        } else if (version.major() < minMajor || version.major() > maxMajor) {
            problems.add(name + " " + plugin.version() + " is not supported; needs " + wanted + ".");
        }
    }
}
