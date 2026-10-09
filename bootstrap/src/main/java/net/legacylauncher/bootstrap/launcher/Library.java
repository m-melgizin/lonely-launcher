package net.legacylauncher.bootstrap.launcher;

import com.google.gson.annotations.Expose;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.legacylauncher.bootstrap.json.ToStringBuildable;
import net.legacylauncher.bootstrap.util.Sha256Sign;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

@Slf4j
public final class Library extends ToStringBuildable {
    /**
     * Libraries are packed into the release jar under this resource path (see bootstrap/build.gradle.kts).
     */
    private static final String EMBEDDED_PREFIX = "/launcher-libraries/";

    @Getter
    private String name, checksum;

    @Getter
    private int javaVersion;

    public Path getFile(Path folder) {
        return Objects.requireNonNull(folder, "folder").resolve(getPath());
    }

    /**
     * Makes sure the library exists in the folder, extracting it from the release jar if needed.
     */
    public void prepare(Path folder) throws IOException {
        Path file = getFile(folder);
        if (Files.isRegularFile(file) && (checksum == null || checksum.equalsIgnoreCase(Sha256Sign.calc(file)))) {
            return;
        }
        try (InputStream in = Library.class.getResourceAsStream(EMBEDDED_PREFIX + getPath())) {
            if (in == null) {
                throw new FileNotFoundException("library " + name + " is missing in " + file.toAbsolutePath() + " and is not embedded");
            }
            log.info("Extracting library {} to {}", name, file);
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                if (checksum != null) {
                    String actual = Sha256Sign.calc(temp);
                    if (!checksum.equalsIgnoreCase(actual)) {
                        throw new IOException("embedded library " + name + " has checksum " + actual + ", expected " + checksum);
                    }
                }
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private String getFilename() {
        final String[] parts = getParts();
        if (parts.length == 4) {
            return String.format(java.util.Locale.ROOT, "%s-%s-%s.jar", parts[1], parts[2], parts[3]);
        } else {
            return String.format(java.util.Locale.ROOT, "%s-%s.jar", parts[1], parts[2]);
        }
    }

    private String getBaseDir() {
        final String[] parts = getParts();
        return String.format(java.util.Locale.ROOT, "%s/%s/%s", StringUtils.replaceChars(parts[0], '.', '/'), parts[1], parts[2]);
    }

    public String getPath() {
        return String.format(java.util.Locale.ROOT, "%s/%s", getBaseDir(), getFilename());
    }

    @Expose
    private String[] parts;

    private String[] getParts() {
        if (parts == null) {
            parts = StringUtils.split(Objects.requireNonNull(name, "name"), ":", 4);
        }
        return parts;
    }

    protected ToStringBuilder toStringBuilder() {
        return super.toStringBuilder()
                .append("name", name)
                .append("checksum", checksum);
    }
}
