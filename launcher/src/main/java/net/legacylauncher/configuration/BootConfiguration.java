package net.legacylauncher.configuration;

import com.google.gson.Gson;

import java.util.*;

public final class BootConfiguration {
    private boolean ely;
    private final Map<String, List<String>> repositories = new HashMap<>();

    public boolean isElyAllowed() {
        return ely;
    }

    public Map<String, List<String>> getRepositories() {
        return repositories;
    }

    public static BootConfiguration parse(String options) {
        Objects.requireNonNull(options, "options");
        return new Gson().fromJson(options, BootConfiguration.class);
    }
}
