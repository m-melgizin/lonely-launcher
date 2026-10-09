package net.legacylauncher.configuration;

import com.google.gson.Gson;

import java.util.*;

public final class BootConfiguration {
    private boolean stats, ely;
    private final Map<String, List<String>> repositories = new HashMap<>();
    private final Map<String, String> feedback = new HashMap<>();

    public boolean isStatsAllowed() {
        return stats;
    }

    public boolean isElyAllowed() {
        return ely;
    }

    public Map<String, List<String>> getRepositories() {
        return repositories;
    }

    public Map<String, String> getFeedback() {
        return feedback;
    }

    public static BootConfiguration parse(String options) {
        Objects.requireNonNull(options, "options");
        return new Gson().fromJson(options, BootConfiguration.class);
    }
}
