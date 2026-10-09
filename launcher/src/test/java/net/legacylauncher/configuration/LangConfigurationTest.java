package net.legacylauncher.configuration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class LangConfigurationTest {

    @Test
    void translationsArePackaged() {
        List<Locale> locales = LangConfiguration.getAvailableLocales();
        assertTrue(locales.contains(Locale.US), "en_US is missing: " + locales);
        assertTrue(locales.contains(LangConfiguration.ru_RU), "ru_RU is missing: " + locales);
    }

    @Test
    void everyLocaleLoads() {
        LangConfiguration lang = new LangConfiguration();
        for (Locale locale : LangConfiguration.getAvailableLocales()) {
            lang.setLocale(locale);
            assertNotNull(lang.nget("plural"), "plural forms are missing for " + locale);
        }
    }

    @Test
    void stringsAreTranslated() {
        LangConfiguration lang = new LangConfiguration();
        assertEquals("Enter the game", lang.get("loginform.enter"));
        lang.setLocale(LangConfiguration.ru_RU);
        assertEquals("Запустить", lang.get("loginform.enter"));
    }

    @Test
    void auxiliaryFilesArePackaged() {
        for (String name : new String[]{"_ui.properties", "_contrib.json", "_proofr.properties"}) {
            assertNotNull(
                    LangConfiguration.class.getResource(LangConfiguration.LOCALE_PATH + "/" + name),
                    name + " is missing"
            );
        }
    }
}
