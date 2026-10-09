package net.legacylauncher.bootstrap.update;

import com.github.zafarkhaja.semver.Version;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.legacylauncher.bootstrap.task.Task;
import net.legacylauncher.bootstrap.util.BootstrapUserAgent;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds the latest release of the launcher on GitHub.
 * <p>
 * Every release is expected to carry {@link #ASSET_NAME} and, optionally, {@code ASSET_NAME + ".sha256"}.
 */
@Slf4j
public final class GitHubReleases {
    public static final String ASSET_NAME = "LonelyLauncher.jar";

    private static final int CONNECT_TIMEOUT = 5_000, READ_TIMEOUT = 10_000;

    public static Task<Optional<Release>> fetchLatest(String repository) {
        return new Task<Optional<Release>>("checkUpdate") {
            @Override
            protected Optional<Release> execute() {
                updateProgress(-1.);
                try {
                    return Optional.of(requestLatest(repository));
                } catch (Exception e) {
                    log.warn("Couldn't check for updates in {}", repository, e);
                    return Optional.empty();
                }
            }
        };
    }

    static Release requestLatest(String repository) throws IOException {
        URL url = new URL("https://api.github.com/repos/" + repository + "/releases/latest");
        log.info("Checking for updates: {}", url);
        JsonObject release = JsonParser.parseString(request(url, "application/vnd.github+json")).getAsJsonObject();
        return parseRelease(release);
    }

    static Release parseRelease(JsonObject release) throws IOException {
        String tag = release.get("tag_name").getAsString();
        Version version;
        try {
            version = Version.parse(tag.startsWith("v") ? tag.substring(1) : tag);
        } catch (RuntimeException e) {
            throw new IOException("release tag is not a version: " + tag, e);
        }
        String page = release.get("html_url").getAsString();

        JsonObject jar = null, checksumFile = null;
        JsonArray assets = release.getAsJsonArray("assets");
        if (assets != null) {
            for (JsonElement element : assets) {
                JsonObject asset = element.getAsJsonObject();
                String name = asset.get("name").getAsString();
                if (ASSET_NAME.equals(name)) {
                    jar = asset;
                } else if ((ASSET_NAME + ".sha256").equals(name)) {
                    checksumFile = asset;
                }
            }
        }
        if (jar == null) {
            throw new IOException("release " + tag + " has no " + ASSET_NAME);
        }

        URL download = new URL(jar.get("browser_download_url").getAsString());
        String sha256 = null;
        JsonElement digest = jar.get("digest");
        if (digest != null && !digest.isJsonNull() && digest.getAsString().startsWith("sha256:")) {
            sha256 = digest.getAsString().substring("sha256:".length());
        } else if (checksumFile != null) {
            String content = request(new URL(checksumFile.get("browser_download_url").getAsString()), "*/*");
            sha256 = content.trim().split("\\s+")[0];
        }
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("release " + tag + " has no valid SHA-256 for " + ASSET_NAME + ": " + sha256);
        }

        return new Release(version, page, download, sha256.toLowerCase(Locale.ROOT));
    }

    private static String request(URL url, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("Accept", accept);
        BootstrapUserAgent.set(connection);
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + code + " from " + url);
        }
        try (InputStream in = connection.getInputStream()) {
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        }
    }

    @Value
    public static class Release {
        Version version;
        String pageUrl;
        URL downloadUrl;
        String sha256;
    }

    private GitHubReleases() {
    }
}
