package net.legacylauncher.util;

import net.legacylauncher.configuration.BuildConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class HelpLinksTest {

    @Test
    void upstreamLinksAreHiddenWhenDisabled() {
        assumeFalse(BuildConfig.HELP_LINKS_ENABLED);
        assertFalse(HelpLinks.isAllowed("https://wiki.llaun.ch/update:java"));
        assertFalse(HelpLinks.isAllowed("https://llaun.ch/l10n"));
        assertFalse(HelpLinks.isAllowed("https://docs.legacylauncher.ru/faq/custom-java"));
        assertFalse(HelpLinks.isAllowed("http://tlaun.ch/wiki/en:privacy:diagnostic"));
    }

    @Test
    void otherLinksAreAllowed() {
        assertTrue(HelpLinks.isAllowed(null));
        assertTrue(HelpLinks.isAllowed("https://www.howtogeek.com/135976/"));
        assertTrue(HelpLinks.isAllowed("https://t.me/mclonelycraft"));
        assertTrue(HelpLinks.isAllowed("https://notllaun.ch/"));
        assertTrue(HelpLinks.isAllowed("not a url"));
    }
}
