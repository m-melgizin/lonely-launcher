package net.legacylauncher.bootstrap.update;

import com.github.zafarkhaja.semver.Version;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class GitHubReleasesTest {
    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static JsonObject release(String tag, String assets) {
        return JsonParser.parseString("{" +
                "\"tag_name\": \"" + tag + "\"," +
                "\"html_url\": \"https://github.com/owner/repo/releases/tag/" + tag + "\"," +
                "\"assets\": [" + assets + "]" +
                "}").getAsJsonObject();
    }

    private static String jarAsset(String digest) {
        return "{" +
                "\"name\": \"LonelyLauncher.jar\"," +
                "\"browser_download_url\": \"https://github.com/owner/repo/releases/download/v1.2.3/LonelyLauncher.jar\"" +
                (digest == null ? "" : ", \"digest\": \"" + digest + "\"") +
                "}";
    }

    @Test
    void parsesRelease() throws IOException {
        GitHubReleases.Release release = GitHubReleases.parseRelease(release("v1.2.3", jarAsset("sha256:" + SHA256.toUpperCase())));
        assertEquals(Version.parse("1.2.3"), release.getVersion());
        assertEquals(SHA256, release.getSha256());
        assertEquals("https://github.com/owner/repo/releases/download/v1.2.3/LonelyLauncher.jar", release.getDownloadUrl().toString());
    }

    @Test
    void newerReleaseIsHigherThanBrandedVersion() throws IOException {
        GitHubReleases.Release release = GitHubReleases.parseRelease(release("v1.2.4", jarAsset("sha256:" + SHA256)));
        assertTrue(release.getVersion().isHigherThan(Version.parse("1.2.3+lonely")));
        assertFalse(Version.parse("1.2.4").isHigherThan(Version.parse("1.2.4+lonely")));
    }

    @Test
    void failsWithoutJar() {
        assertThrows(IOException.class, () -> GitHubReleases.parseRelease(release("v1.2.3", "")));
    }

    @Test
    void failsWithoutChecksum() {
        assertThrows(IOException.class, () -> GitHubReleases.parseRelease(release("v1.2.3", jarAsset(null))));
    }

    @Test
    void failsOnBadTag() {
        assertThrows(IOException.class, () -> GitHubReleases.parseRelease(release("latest", jarAsset("sha256:" + SHA256))));
    }
}
