package com.veyrion.wardrobe.skin;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;

/** Lets SkinCheck (another package) reach the few package-private members it needs. Dev checks only; not in the jar. */
public final class SkinCheckAccess {
    private SkinCheckAccess() {}

    public static boolean isPublic(InetAddress address) {
        return HttpSkinImageProbe.isPublic(address);
    }

    public static long touchIntervalMs() {
        return SkinCache.TOUCH_INTERVAL_MS;
    }

    /** A probe that allows http and loopback, for a local test server. */
    public static SkinImageProbe localProbe(HttpClient client, int maxBytes, Duration timeout) {
        return new HttpSkinImageProbe(client, maxBytes, timeout, true);
    }
}
