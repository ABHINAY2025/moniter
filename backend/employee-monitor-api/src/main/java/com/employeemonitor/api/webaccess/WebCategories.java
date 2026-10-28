package com.employeemonitor.api.webaccess;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in website categories mapped to representative domains. Category rules are expanded into these domains when
 * building a device's effective rule set, so category blocking enforces client-side the same way domain rules do.
 * This is a starter set; it can be grown or replaced by a managed feed without touching the policy model.
 */
public final class WebCategories {

    private WebCategories() {
    }

    private static final Map<String, List<String>> CATEGORIES = Map.of(
            "STREAMING", List.of("youtube.com", "netflix.com", "primevideo.com", "hotstar.com", "disneyplus.com",
                    "twitch.tv", "hulu.com", "spotify.com"),
            "SOCIAL", List.of("facebook.com", "instagram.com", "twitter.com", "x.com", "tiktok.com", "snapchat.com",
                    "reddit.com", "pinterest.com"),
            "GAMING", List.of("steampowered.com", "epicgames.com", "roblox.com", "ea.com", "xbox.com"),
            "FILE_SHARING", List.of("thepiratebay.org", "1337x.to", "mega.nz", "mediafire.com", "rarbg.to"),
            "ADULT", List.of()   // intentionally empty in the shipped set; populate via a managed feed
    );

    public static Set<String> names() {
        return CATEGORIES.keySet();
    }

    public static boolean exists(String category) {
        return category != null && CATEGORIES.containsKey(category.trim().toUpperCase());
    }

    public static List<String> domainsOf(String category) {
        if (category == null) {
            return List.of();
        }
        return CATEGORIES.getOrDefault(category.trim().toUpperCase(), List.of());
    }
}
