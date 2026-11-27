package com.veyrion.wardrobe.skin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;

/**
 * Downloads the image once (HTTPS only, at most {@code maxBytes}) and checks the PNG header. This is only a guard
 * so a player gets a clear message early; SkinsRestorer/MineSkin still do the real processing.
 * <p>
 * Safety: redirects are followed by hand (at most 3) and every hop is checked again; a host that resolves to a
 * private, loopback or link-local address is refused. A DNS answer can still change between this check and the
 * download (DNS rebinding); the answer is only ever parsed for a header and never shown or stored, so the risk is
 * a blind request from the server, not a leak.
 */
public final class HttpSkinImageProbe implements SkinImageProbe {

    private static final int MAX_REDIRECTS = 3;

    private final HttpClient client;
    private final int maxBytes;
    private final Duration requestTimeout;
    private final boolean testMode;

    /** @param requestTimeout how long one download may take, in total */
    public HttpSkinImageProbe(int maxBytes, Duration requestTimeout) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build(),
                maxBytes, requestTimeout, false);
    }

    /** Package-private for the checks: test mode allows http and loopback so a local server can be used. */
    HttpSkinImageProbe(HttpClient client, int maxBytes, Duration requestTimeout, boolean testMode) {
        this.client = client;
        this.maxBytes = maxBytes;
        this.requestTimeout = requestTimeout;
        this.testMode = testMode;
    }

    @Override
    public void probe(String url) throws SkinException {
        URI uri = parse(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requireAllowed(uri);
            HttpResponse<InputStream> response = get(uri);
            int code = response.statusCode();
            if (code >= 300 && code < 400) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new SkinException(SkinStatus.IMAGE_UNREACHABLE, "redirect without a Location");
                }
                uri = uri.resolve(location);
                continue;
            }
            if (code != 200) {
                closeQuietly(response.body());
                throw new SkinException(SkinStatus.IMAGE_UNREACHABLE, "the server answered " + code);
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maxBytes) {
                closeQuietly(response.body());
                throw new SkinException(SkinStatus.IMAGE_TOO_LARGE, declared + " bytes");
            }
            byte[] body = readLimited(response.body());
            switch (PngHeader.check(body)) {
                case SKIN -> { return; }
                case NOT_PNG -> throw new SkinException(SkinStatus.IMAGE_NOT_A_SKIN, "not a PNG file");
                case WRONG_SIZE -> throw new SkinException(SkinStatus.IMAGE_NOT_A_SKIN, "size is not 64x64 or 64x32");
            }
        }
        throw new SkinException(SkinStatus.IMAGE_UNREACHABLE, "too many redirects");
    }

    private static URI parse(String url) {
        try {
            return new URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new SkinException(SkinStatus.URL_MALFORMED, url);
        }
    }

    private void requireAllowed(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !(scheme.equals("https") || (testMode && scheme.equals("http")))) {
            throw new SkinException(SkinStatus.URL_NOT_HTTPS, String.valueOf(uri));
        }
        if (uri.getUserInfo() != null) {
            throw new SkinException(SkinStatus.URL_HAS_CREDENTIALS, String.valueOf(uri));
        }
        if (testMode) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublic(address)) {
                    throw new SkinException(SkinStatus.URL_PRIVATE_HOST, uri.getHost() + " resolves to " + address.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw new SkinException(SkinStatus.IMAGE_UNREACHABLE, "unknown host " + uri.getHost());
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return !(first == 100 && second >= 64 && second <= 127) && first < 224; // carrier-grade NAT, reserved
        }
        return (b[0] & 0xFE) != 0xFC; // IPv6 unique local fc00::/7
    }

    private HttpResponse<InputStream> get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout).header("User-Agent", "VeyrionWardrobe")
                .header("Accept", "image/png").GET().build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new SkinException(SkinStatus.TIMEOUT, "the image server was too slow");
        } catch (IOException e) {
            throw new SkinException(SkinStatus.IMAGE_UNREACHABLE, String.valueOf(e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException(SkinStatus.SHUTTING_DOWN, "interrupted");
        }
    }

    private byte[] readLimited(InputStream in) {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > maxBytes) {
                    throw new SkinException(SkinStatus.IMAGE_TOO_LARGE, "more than " + maxBytes + " bytes");
                }
            }
            return out.toByteArray();
        } catch (HttpTimeoutException e) {
            throw new SkinException(SkinStatus.TIMEOUT, "the image server was too slow");
        } catch (IOException e) {
            throw new SkinException(SkinStatus.IMAGE_UNREACHABLE, String.valueOf(e.getMessage()), e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
