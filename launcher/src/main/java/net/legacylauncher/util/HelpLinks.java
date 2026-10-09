package net.legacylauncher.util;

import net.legacylauncher.configuration.BuildConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Feature toggle for links to upstream help pages (wiki, docs, etc.), see {@code feature.helpLinks}.
 */
public final class HelpLinks {
    private static final List<String> UPSTREAM_HOSTS = Arrays.asList(
            "llaun.ch",
            "tlaun.ch",
            "legacylauncher.ru",
            "legacylauncher.net",
            "lln4.ru"
    );

    /**
     * @return false if the link leads to an upstream help page and such links are disabled
     */
    public static boolean isAllowed(String url) {
        if (BuildConfig.HELP_LINKS_ENABLED || url == null) {
            return true;
        }
        String host;
        try {
            host = new URI(url.trim()).getHost();
        } catch (URISyntaxException e) {
            return true;
        }
        if (host == null) {
            return true;
        }
        host = host.toLowerCase(Locale.ROOT);
        for (String upstream : UPSTREAM_HOSTS) {
            if (host.equals(upstream) || host.endsWith("." + upstream)) {
                return false;
            }
        }
        return true;
    }

    private HelpLinks() {
    }
}
