package net.frsprojects.modsync.core.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostAllowlistTest {

    private final HostAllowlist defaults = HostAllowlist.defaults();

    @Test
    void allowsTheModPlatformsAndTheirCdns() {
        assertTrue(defaults.isAllowed("https://cdn.modrinth.com/data/x/y.jar"));
        assertTrue(defaults.isAllowed("https://modrinth.com/x.jar"));
        assertTrue(defaults.isAllowed("https://mediafilez.forgecdn.net/files/1/2/x.jar"));
        assertTrue(defaults.isAllowed("https://github.com/o/r/releases/download/v1/x.jar"));
    }

    @Test
    void refusesUnknownHosts() {
        assertFalse(defaults.isAllowed("https://evil.example.com/payload.jar"));
    }

    /** Suffix matching must not be fooled by a host that merely ends with the same text. */
    @Test
    void refusesLookalikeHosts() {
        assertFalse(defaults.isAllowed("https://modrinth.com.evil.example/x.jar"));
        assertFalse(defaults.isAllowed("https://notmodrinth.com/x.jar"));
        assertFalse(defaults.isAllowed("https://evilmodrinth.com/x.jar"));
    }

    @Test
    void refusesPlainHttpFromAllowlistedHosts() {
        assertFalse(defaults.isAllowed("http://cdn.modrinth.com/x.jar"));
    }

    @Test
    void refusesNonHttpSchemes() {
        assertThrows(SandboxException.class, () -> defaults.check("file:///etc/passwd"));
        assertThrows(SandboxException.class, () -> defaults.check("ftp://host/x.jar"));
        assertThrows(SandboxException.class, () -> defaults.check("jar:file:/x.jar!/"));
    }

    @Test
    void refusesMalformedUrls() {
        assertThrows(SandboxException.class, () -> defaults.check("not a url"));
        assertThrows(SandboxException.class, () -> defaults.check("https://"));
        assertThrows(SandboxException.class, () -> defaults.check("/relative/path.jar"));
    }

    /**
     * The joined server is trusted because the player chose to connect to it, and is
     * allowed over plain HTTP because its files are hash-pinned by the manifest anyway.
     */
    @Test
    void theJoinedServerIsTrustedIncludingOverPlainHttp() {
        HostAllowlist withServer = defaults.plusServer("mc.example.net");
        assertTrue(withServer.isAllowed("http://mc.example.net:25566/modsync/file/abc"));
        assertTrue(withServer.isAllowed("https://mc.example.net:25566/modsync/file/abc"));
        // Trusting one server must not trust its neighbours.
        assertFalse(withServer.isAllowed("http://other.example.net/x.jar"));
        // Nor does an exact-host grant become a suffix grant.
        assertFalse(withServer.isAllowed("http://evil.mc.example.net/x.jar"));
    }

    @Test
    void userApprovedHostsAreHonoured() {
        HostAllowlist extended = defaults.plusUserApproved(List.of("my-cdn.example"));
        assertTrue(extended.isAllowed("https://files.my-cdn.example/x.jar"));
        assertFalse(extended.isAllowed("https://other.example/x.jar"));
    }

    @Test
    void hostMatchingIsCaseInsensitive() {
        assertTrue(defaults.isAllowed("https://CDN.MODRINTH.COM/x.jar"));
    }

    @Test
    void theErrorSaysWhatToDoAboutIt() {
        SandboxException e = assertThrows(SandboxException.class,
            () -> defaults.check("https://evil.example.com/x.jar"));
        assertTrue(e.getMessage().contains("evil.example.com"));
        assertTrue(e.getMessage().contains("ModSync settings"));
    }
}
