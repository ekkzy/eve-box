package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class WallpaperCatalogTest {
    @Test public void stableOriginalIdsAndAssetsRemainUnchanged() {
        String[] names = {"background_night", "", "", "", "background_moon_coast", "background_mist_forest",
                "background_starlit_dunes", "background_twilight_fjord", "background_alpine_day", "background_coast_day", "background_forest_day", "background_dunes_day"};
        for (int id = 0; id < names.length; id++) {
            WallpaperCatalog.Entry entry = WallpaperCatalog.get(id);
            assertEquals(id, entry.id); assertEquals(names[id], entry.drawableName);
            assertEquals(id == 1 || id == 3 || id >= 8, entry.light);
            assertEquals(id >= 1 && id <= 3, entry.vector);
        }
    }
    @Test public void generatedAbstractAssetsHaveFixedNamesOrderAndAutoPalette() {
        String[] names = {"background_obsidian_folds", "background_pearl_folds", "background_amber_gallery",
                "background_ivory_curves", "background_copper_arcs", "background_opal_ribbons"};
        String[] ru = {"Обсидиановые складки", "Жемчужные складки", "Янтарная галерея", "Изгибы слоновой кости", "Медные дуги", "Опаловые ленты"};
        String[] en = {"Obsidian folds", "Pearl folds", "Amber gallery", "Ivory curves", "Copper arcs", "Opal ribbons"};
        for (int offset = 0; offset < names.length; offset++) {
            int id = 12 + offset; WallpaperCatalog.Entry entry = WallpaperCatalog.get(id);
            assertEquals(names[offset], entry.drawableName); assertFalse(entry.vector);
            assertEquals(offset % 2 == 1, WallpaperCatalog.isLight(id));
            assertEquals(ru[offset], WallpaperCatalog.title(id, true));
            assertEquals(en[offset], WallpaperCatalog.title(id, false));
        }
        assertEquals(12, WallpaperCatalog.OBSIDIAN_FOLDS); assertEquals(17, WallpaperCatalog.OPAL_RIBBONS);
    }
    @Test public void unknownIdsFallbackRatherThanSelectingAnotherWallpaper() {
        for (int id : new int[]{-1, 39, 48, 49, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertFalse(WallpaperCatalog.isValid(id));
            assertEquals(WallpaperCatalog.DEFAULT_ID, WallpaperCatalog.normalize(id));
            assertSame(WallpaperCatalog.get(0), WallpaperCatalog.get(id));
        }
    }
    @Test public void catalogIsImmutableCompleteAndHasUniquePackagedNames() {
        assertEquals(39, WallpaperCatalog.COUNT); assertEquals(38, WallpaperCatalog.MAX_ID);
        assertEquals(WallpaperCatalog.COUNT, WallpaperCatalog.all().size());
        Set<String> packagedNames = new HashSet<>();
        for (int id = 0; id < WallpaperCatalog.COUNT; id++) {
            WallpaperCatalog.Entry entry = WallpaperCatalog.all().get(id);
            assertEquals(id, entry.id); assertTrue(WallpaperCatalog.isValid(entry.id));
            assertFalse(entry.titleRu.isEmpty()); assertFalse(entry.titleEn.isEmpty());
            if (!entry.vector) assertTrue("Unique asset " + entry.drawableName, packagedNames.add(entry.drawableName));
        }
        try { WallpaperCatalog.all().clear(); fail("Catalog must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }
    @Test public void automaticContentBackdropRemainsSelectable() {
        WallpaperCatalog.Entry automatic = WallpaperCatalog.get(WallpaperCatalog.CONTENT_AMBIENT);
        assertTrue(automatic.vector); assertTrue(automatic.light); assertEquals("", automatic.drawableName);
        assertEquals("Оттенки контента", automatic.titleRu);
        assertEquals("Content ambience", automatic.titleEn);
    }
    @Test public void galleryHidesRetiredPlainBackgroundsButPreservesTheirIdentities() {
        Set<Integer> visible = new HashSet<>();
        for (WallpaperCatalog.Entry entry : WallpaperCatalog.selectable()) assertTrue(visible.add(entry.id));
        assertEquals(20, visible.size());
        assertTrue(visible.contains(2)); assertFalse(visible.contains(3));
        assertFalse(visible.contains(1));
        for (int id = 19; id <= 28; id++) {
            assertEquals(id % 2 == 1, visible.contains(id)); assertEquals(id, WallpaperCatalog.get(id).id);
        }
        for (int id = 29; id <= 38; id++) {
            assertEquals(id % 2 == 1, visible.contains(id)); assertFalse(WallpaperCatalog.get(id).vector);
        }
    }
    @Test public void tenCalmBackgroundsAppendAfterAutomaticWithoutChangingItsId() {
        String[] names = {"graphite_mist", "pearl_mist", "ink_blue", "silver_blue", "smoke_sage",
                "fog_green", "muted_mauve", "dusty_rose", "warm_charcoal", "ivory_linen"};
        String[] ru = {"Графитовая дымка", "Жемчужная дымка", "Чернильная дымка", "Серебристая дымка", "Дымчатый шалфей",
                "Туманная зелень", "Приглушённый лиловый", "Пыльная роза", "Тёплый графит", "Слоновая кость"};
        String[] en = {"Graphite mist", "Pearl mist", "Ink blue haze", "Silver blue haze", "Smoked sage",
                "Fog green", "Muted mauve", "Dusty rose", "Warm charcoal", "Ivory linen"};
        int light = 0, dark = 0;
        for (int offset = 0; offset < names.length; offset++) {
            WallpaperCatalog.Entry entry = WallpaperCatalog.get(19 + offset);
            assertEquals(19 + offset, entry.id);
            assertEquals("background_calm_" + names[offset], entry.drawableName);
            assertEquals(ru[offset], entry.titleRu); assertEquals(en[offset], entry.titleEn);
            assertFalse(entry.vector); assertEquals(offset % 2 == 1, entry.light);
            if (entry.light) light++; else dark++;
        }
        assertEquals(5, light); assertEquals(5, dark);
        assertEquals(18, WallpaperCatalog.CONTENT_AMBIENT);
        assertEquals(19, WallpaperCatalog.CALM_GRAPHITE_MIST);
        assertEquals(28, WallpaperCatalog.CALM_IVORY_LINEN);
    }
}
