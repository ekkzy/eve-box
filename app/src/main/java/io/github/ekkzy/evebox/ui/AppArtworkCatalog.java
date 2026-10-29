package io.github.ekkzy.evebox.ui;

import io.github.ekkzy.evebox.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact installed-package identities and independently sourced offline artwork. */
public final class AppArtworkCatalog {
    private AppArtworkCatalog() {}

    public static final class Entry {
        public final String key, title, assetPath;
        public final int drawable, brandColor;
        public final List<String> packages;

        private Entry(String key, String title, int drawable, String assetPath, String... packages) {
            this.key = key;
            this.title = title;
            this.drawable = drawable;
            this.assetPath = assetPath;
            this.brandColor = brandTint(key);
            this.packages = Collections.unmodifiableList(Arrays.asList(packages.clone()));
        }

        public boolean hasCuratedArtwork() {
            return drawable != 0 || assetPath != null;
        }

        /** These publishers put a wordmark and a separate field in their TV banner. */
        boolean prefersApplicationIcon() {
            return "youtube".equals(key) || "sendfiles".equals(key) || "vimu".equals(key);
        }

        /** Its upper arrow is a foreground counter that reveals the purple layer. */
        boolean keepsAdaptiveBackground() {
            return "sendfiles".equals(key);
        }

        /** The installed play icon has opaque red/white antialias on a white matte. */
        boolean restoresRedWhiteMatte() {
            return "youtube".equals(key);
        }

        /** Its dense native foreground has a zero-blue gradient on an opaque white plate. */
        boolean restoresZeroBlueWhitePlate() {
            return "vimu".equals(key);
        }
    }

    /** Documented brand colors for source paths published as a single white fill. */
    private static int brandTint(String key) {
        switch (key) {
            case "kinopoisk": return 0xffff5500;
            case "plex": return 0xffebaf00;
            case "prime": return 0xff2aa8ff;
            default: return 0;
        }
    }

    private static Entry entry(String key, String title, int drawable, String asset, String... packages) {
        return new Entry(key, title, drawable, asset, packages);
    }

    private static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
        entry("vk", "VK Видео", R.drawable.logo_vk, null, "com.vk.tv"),
        entry("kinopoisk", "Кинопоиск", R.drawable.logo_kinopoisk, null, "ru.kinopoisk.tv"),
        entry("youtube", "YouTube", 0, null, "com.google.android.youtube.tv"),
        entry("vimu", "Vimu Media Player", 0, null, "net.gtvbox.vimuhd", "net.gtvbox.videoplayer"),
        entry("plex", "Plex", R.drawable.logo_plex, null, "com.plexapp.android"),
        entry("sendfiles", "Send Files to TV", 0, null, "com.yablio.sendfilestotv"),
        entry("ivi", "Иви", 0, null, "ru.ivi.client"),
        entry("rutube", "RUTUBE", R.drawable.logo_rutube, null, "ru.rutube.app", "rtb.mobile.android"),
        entry("netflix", "Netflix", R.drawable.logo_netflix, null, "com.netflix.ninja"),
        entry("prime", "Prime Video", R.drawable.logo_prime, null, "com.amazon.amazonvideo.livingroom"),
        entry("disney", "Disney+", 0, null, "com.disney.disneyplus"),
        entry("max", "HBO Max / Max", R.drawable.logo_max, null, "com.wbd.stream", "com.hbo.hbonow"),
        entry("appletv", "Apple TV", R.drawable.logo_appletv, null, "com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv"),
        entry("hulu", "Hulu", 0, null, "com.hulu.livingroomplus"),
        entry("paramount", "Paramount+", 0, null, "com.cbs.ott"),
        entry("peacock", "Peacock", 0, null, "com.peacocktv.peacockandroid"),
        entry("globoplay", "Globoplay", 0, null, "com.globo.globotv"),
        entry("spotify", "Spotify", R.drawable.logo_spotify, null, "com.spotify.tv.android"),
        entry("twitch", "Twitch", R.drawable.logo_twitch, null, "tv.twitch.android.app"),
        entry("kodi", "Kodi", R.drawable.logo_kodi, null, "org.xbmc.kodi"),
        entry("vlc", "VLC", R.drawable.logo_vlc, null, "org.videolan.vlc"),
        entry("jellyfin", "Jellyfin", R.drawable.logo_jellyfin, null, "org.jellyfin.androidtv"),
        entry("emby", "Emby", R.drawable.logo_emby, null, "tv.emby.embyatv"),
        entry("stremio", "Stremio", R.drawable.logo_stremio, null, "com.stremio.one"),
        entry("youtubemusic", "YouTube Music", R.drawable.logo_youtubemusic, null, "com.google.android.youtube.tvmusic"),
        entry("googleplay", "Google Play Store", R.drawable.logo_googleplay, null, "com.android.vending"),
        entry("tivimate", "TiviMate", 0, null, "ar.tvplayer.tv"),
        entry("smarttube", "SmartTube", R.drawable.logo_smarttube, null, "org.smarttube.stable", "org.smarttube.beta", "app.smarttube.fdroid", "app.smarttube"),
        entry("okko", "Okko", 0, null, "ru.more.play"),
        entry("wink", "Wink", 0, null, "ru.rt.video.app.tv", "ru.rt.video.app.mobile"),
        entry("kion", "КИОН", 0, null, "ru.mts.mtstv"),
        entry("start", "START", 0, null, "ru.start.androidmobile"),
        entry("settings", "Настройки", R.drawable.symbol_settings, null, "com.android.tv.settings", "com.android.settings")
    ));
    private static final Map<String, Entry> BY_PACKAGE;

    static {
        Map<String, Entry> found = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (keys.contains(entry.key)) throw new IllegalStateException("Duplicate artwork key: " + entry.key);
            keys.add(entry.key);
            for (String pkg : entry.packages) {
                if (found.put(pkg, entry) != null) throw new IllegalStateException("Duplicate artwork package: " + pkg);
            }
        }
        BY_PACKAGE = Collections.unmodifiableMap(found);
    }

    public static Entry find(String packageName) {
        return BY_PACKAGE.get(packageName);
    }

    public static List<Entry> all() {
        return ENTRIES;
    }
}
