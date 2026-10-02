package net.frsprojects.modsync.core.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides which hosts ModSync will fetch executable content from.
 *
 * <p>A manifest is a remote server telling the client to download and run jars. Without a
 * host restriction, any server could point at any URL on the internet. The default set
 * covers the two mod platforms, GitHub releases, and whatever server the player is actually
 * joining; anything else needs the player to opt in explicitly.
 */
public final class HostAllowlist {

    /** Suffix-matched, so {@code cdn.modrinth.com} matches {@code modrinth.com}. */
    private static final List<String> DEFAULT_HOSTS = List.of(
        "modrinth.com",
        "curseforge.com",
        "forgecdn.net",
        "githubusercontent.com",
        "github.com");

    private final List<String> allowedSuffixes;
    private final List<String> extraExactHosts;
    private final List<String> exactHttpsHosts;
    private final boolean requireHttps;

    private HostAllowlist(List<String> suffixes, List<String> exact, List<String> exactHttps,
            boolean requireHttps) {
        this.allowedSuffixes = List.copyOf(suffixes);
        this.extraExactHosts = List.copyOf(exact);
        this.exactHttpsHosts = List.copyOf(exactHttps);
        this.requireHttps = requireHttps;
    }

    public static HostAllowlist defaults() {
        return new HostAllowlist(DEFAULT_HOSTS, List.of(), List.of(), true);
    }

    /**
     * The defaults plus the server the player is joining, which is trusted for the duration
     * of that connection because they chose to connect to it.
     *
     * <p>{@code requireHttps} is relaxed for that host alone: a server's own endpoint is
     * usually plain HTTP on a LAN or a bare IP where a certificate is not obtainable, and
     * every file it serves is hash-pinned by the manifest regardless of transport.
     */
    public HostAllowlist plusServer(String serverHost) {
        if (serverHost == null || serverHost.isBlank()) {
            return this;
        }
        List<String> exact = new ArrayList<>(extraExactHosts);
        exact.add(serverHost.toLowerCase(Locale.ROOT));
        return new HostAllowlist(allowedSuffixes, exact, exactHttpsHosts, requireHttps);
    }

    /**
     * The current set plus the host the manifest itself is fetched from, exact match and
     * HTTPS only.
     *
     * <p>A manifest pins every file by SHA-512 and comes from a URL the player configured, so
     * its host can serve nothing the manifest could not already point at elsewhere. That is
     * what lets a panel serve its own uploads without every player approving it by hand. Kept
     * apart from {@link #plusServer} because that one also allows plain HTTP.
     */
    public HostAllowlist plusManifestHost(String manifestUrl) {
        if (manifestUrl == null || manifestUrl.isBlank()) {
            return this;
        }
        String host;
        try {
            URI uri = new URI(manifestUrl.trim());
            if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
                return this;
            }
            host = uri.getHost();
        } catch (URISyntaxException e) {
            return this;
        }
        if (host == null || host.isBlank()) {
            return this;
        }
        List<String> exactHttps = new ArrayList<>(exactHttpsHosts);
        exactHttps.add(host.toLowerCase(Locale.ROOT));
        return new HostAllowlist(allowedSuffixes, extraExactHosts, exactHttps, requireHttps);
    }

    /** The defaults plus hosts the player has explicitly approved. */
    public HostAllowlist plusUserApproved(List<String> hosts) {
        List<String> suffixes = new ArrayList<>(allowedSuffixes);
        for (String h : hosts) {
            if (h != null && !h.isBlank()) {
                suffixes.add(h.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new HostAllowlist(suffixes, extraExactHosts, exactHttpsHosts, requireHttps);
    }

    /**
     * @throws SandboxException if the URL is malformed, uses an unsupported scheme, or
     *     points at a host outside the allowlist
     */
    public URI check(String url) throws SandboxException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new SandboxException("Malformed download URL: '" + url + "'");
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new SandboxException("Download URL has no scheme: '" + url + "'");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new SandboxException(
                "Only http and https downloads are supported, got '" + scheme + "'");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SandboxException("Download URL has no host: '" + url + "'");
        }
        host = host.toLowerCase(Locale.ROOT);

        boolean exact = extraExactHosts.contains(host);
        boolean manifestHost = exactHttpsHosts.contains(host);
        if (!exact && !manifestHost && !matchesSuffix(host)) {
            throw new SandboxException(
                "Refusing to download from '" + host + "': it is not an approved host. "
                    + "Approve it in the ModSync settings if you trust it.");
        }
        // The joined server is exempt: its files are hash-pinned and it is often plain HTTP.
        // The manifest's host is not: it is trusted only over TLS, whatever requireHttps says.
        if ((requireHttps || manifestHost) && !exact && !scheme.equals("https")) {
            throw new SandboxException(
                "Refusing a plain-http download from '" + host + "'");
        }
        return uri;
    }

    public boolean isAllowed(String url) {
        try {
            check(url);
            return true;
        } catch (SandboxException e) {
            return false;
        }
    }

    private boolean matchesSuffix(String host) {
        for (String suffix : allowedSuffixes) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }
}
