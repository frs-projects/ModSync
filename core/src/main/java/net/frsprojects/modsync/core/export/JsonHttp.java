package net.frsprojects.modsync.core.export;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.frsprojects.modsync.core.security.HostAllowlist;
import net.frsprojects.modsync.core.security.SandboxException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * A minimal JSON-in/JSON-out POST client for the export lookups.
 *
 * <p>{@code Downloader} cannot be reused here: its {@code HttpClient} is private and every one
 * of its paths streams to a file and verifies a hash, which is the opposite of a small API
 * call. Its client configuration is worth copying, though, so the timeouts match.
 *
 * <p>Every URL still goes through {@link HostAllowlist}. These endpoints are ours rather than
 * a server's, but routing them through the same gate means there is exactly one answer to
 * "where may ModSync talk to?".
 */
public final class JsonHttp implements AutoCloseable {

    /** Enough for a few thousand version records; past this something is wrong. */
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    private final HttpClient http;
    private final HostAllowlist allowlist;
    private final String userAgent;

    public JsonHttp(HostAllowlist allowlist, String userAgent) {
        this.allowlist = allowlist;
        this.userAgent = userAgent;
        this.http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    }

    public JsonElement post(String url, JsonElement body, Map<String, String> headers)
            throws IOException {
        URI uri;
        try {
            uri = allowlist.check(url);
        } catch (SandboxException e) {
            throw new IOException("Refusing to call " + url + ": " + e.getMessage(), e);
        }

        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        headers.forEach(req::header);

        HttpResponse<byte[]> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + uri.getHost(), e);
        }

        if (res.statusCode() / 100 != 2) {
            throw new IOException(uri.getHost() + " answered HTTP " + res.statusCode()
                + describe(res.statusCode()));
        }
        byte[] raw = res.body();
        if (raw.length > MAX_RESPONSE_BYTES) {
            throw new IOException(uri.getHost() + " returned " + raw.length
                + " bytes, more than ModSync will parse");
        }
        try {
            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            throw new IOException(uri.getHost() + " returned a response that is not JSON", e);
        }
    }

    /** Turns the codes an admin will actually hit into something actionable. */
    private static String describe(int status) {
        switch (status) {
            case 401:
            case 403:
                return " (rejected the API key — check curseForgeApiKey in modsync.json)";
            case 429:
                return " (rate limited — wait a minute and export again)";
            default:
                return "";
        }
    }

    @Override
    public void close() {
        // HttpClient.close() is Java 21+ and :core targets 17, matching Downloader.
    }
}
