package com.veyrion.wardrobe.skin;

import com.veyrion.wardrobe.model.SkinModel;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * What identifies one processed skin: the image URL and the arm model. The same image as SLIM and as WIDE are two
 * different skins. The URL is made canonical (scheme and host in lower case, no #fragment) so "HTTPS://X.io/a.png#1"
 * and "https://x.io/a.png" share one entry; path and query keep their case.
 */
public record SkinKey(String url, SkinModel model) {

    public static SkinKey of(String url, SkinModel model) {
        return new SkinKey(canonical(url), model);
    }

    /** The text stored in the database. */
    public String id() {
        return model.name() + "|" + url;
    }

    static String canonical(String url) {
        String text = url.strip();
        try {
            URI uri = new URI(text);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return text;
            }
            StringBuilder out = new StringBuilder(uri.getScheme().toLowerCase(Locale.ROOT)).append("://")
                    .append(uri.getHost().toLowerCase(Locale.ROOT));
            if (uri.getPort() != -1) {
                out.append(':').append(uri.getPort());
            }
            if (uri.getRawPath() != null) {
                out.append(uri.getRawPath());
            }
            if (uri.getRawQuery() != null) {
                out.append('?').append(uri.getRawQuery());
            }
            return out.toString();
        } catch (URISyntaxException e) {
            return text;
        }
    }
}
