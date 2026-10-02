package net.frsprojects.modsync.core.sync;

import net.frsprojects.modsync.core.manifest.ManifestCodec;
import net.frsprojects.modsync.core.manifest.ManifestException;
import net.frsprojects.modsync.core.manifest.SyncManifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Fetches and parses a manifest.
 *
 * <p>The URL is one the player configured, so any http or https host is acceptable here; the
 * files the manifest points at still go through the download allowlist. The body is capped
 * before parsing, so a misbehaving host cannot make the client buffer an unbounded response.
 */
public final class ManifestFetcher {

    /** Far above any real manifest: 10,000 entries at a generous 1.5 KiB each is ~15 MiB. */
    static final int MAX_BYTES = 32 * 1024 * 1024;

    private final HttpClient http;
    private final String userAgent;

    public ManifestFetcher(String userAgent) {
        this.userAgent = userAgent;
        this.http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /**
     * @throws IOException if the URL is unusable, the host does not answer {@code 200}, or the
     *     body is not a valid manifest; the message is meant to be shown to the player
     */
    public SyncManifest fetch(String url) throws IOException {
        URI uri = checkUrl(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching the manifest", e);
        }

        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("The manifest host answered HTTP " + response.statusCode()
                    + " for " + url);
            }
            String json = readCapped(body);
            try {
                return ManifestCodec.parse(json);
            } catch (ManifestException e) {
                throw new IOException("The manifest at " + url + " is invalid: "
                    + e.getMessage(), e);
            }
        }
    }

    static URI checkUrl(String url) throws IOException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IOException("Manifest URL is not a valid URL: '" + url + "'");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http"))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IOException("Manifest URL must be an absolute http(s) URL: '" + url + "'");
        }
        return uri;
    }

    private static String readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int read;
        while ((read = in.read(buf)) != -1) {
            if (out.size() + read > MAX_BYTES) {
                throw new IOException("Manifest is larger than " + (MAX_BYTES >> 20) + " MiB");
            }
            out.write(buf, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
