package com.veyrion.wardrobe.skin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Checks a skin URL typed by a player before anything is sent to SkinsRestorer/MineSkin. It only looks at the text
 * (no network): it cannot tell whether the image is a real skin, SkinsRestorer reports that.
 */
public final class SkinUrlValidator {

    public enum Problem { EMPTY, TOO_LONG, MALFORMED, NOT_HTTPS, HAS_CREDENTIALS, PRIVATE_HOST, HOST_NOT_ALLOWED }

    public record Result(@Nullable String url, @Nullable Problem problem) {
        public boolean valid() {
            return problem == null;
        }
    }

    public static final int MAX_LENGTH = 512;

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final List<String> allowedHosts;

    /** @param allowedHosts host names; empty = every public host. A listed host also allows its subdomains. */
    public SkinUrlValidator(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
    }

    public Result validate(@Nullable String input) {
        if (input == null || input.isBlank()) {
            return fail(Problem.EMPTY);
        }
        String text = input.strip();
        if (text.length() > MAX_LENGTH) {
            return fail(Problem.TOO_LONG);
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            return fail(Problem.MALFORMED);
        }
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null) {
            return fail(Problem.MALFORMED);
        }
        if (!scheme.equalsIgnoreCase("https")) {
            return fail(Problem.NOT_HTTPS);
        }
        if (uri.getUserInfo() != null) {
            return fail(Problem.HAS_CREDENTIALS);
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (isPrivateHost(host)) {
            return fail(Problem.PRIVATE_HOST);
        }
        if (!allowedHosts.isEmpty() && allowedHosts.stream().noneMatch(a -> host.equals(a) || host.endsWith("." + a))) {
            return fail(Problem.HOST_NOT_ALLOWED);
        }
        return new Result(text, null);
    }

    private static Result fail(Problem problem) {
        return new Result(null, problem);
    }

    static boolean isPrivateHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || (!host.contains(".") && !host.contains(":"))) {
            return true;
        }
        if (host.contains(":")) { // an IPv6 literal: never a skin host
            return true;
        }
        if (IPV4.matcher(host).matches()) {
            String[] parts = host.split("\\.");
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168) || a >= 224 || (a == 100 && b >= 64 && b <= 127);
        }
        return false;
    }
}
