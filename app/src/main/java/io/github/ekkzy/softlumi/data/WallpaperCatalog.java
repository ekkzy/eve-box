package io.github.ekkzy.softlumi.data;

import android.content.Context;
import io.github.ekkzy.softlumi.R;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Stable launcher wallpaper identities and presentation metadata. IDs are persisted in settings/backups. */
public final class WallpaperCatalog {
    public static final int NIGHT_LANDSCAPE = 0;
    public static final int LIGHT_RIBBON = 1;
    public static final int GRAPHITE = 2;
    public static final int LIGHT_DASHBOARD = 3;
    public static final int MOONLIT_COAST = 4;
    public static final int MISTY_FOREST = 5;
    public static final int STARLIT_DUNES = 6;
    public static final int TWILIGHT_FJORD = 7;
    public static final int MOUNTAIN_PEAKS = 8;
    public static final int COAST = 9;
    public static final int FOREST_FROM_ABOVE = 10;
    public static final int SAND_DUNES = 11;
    public static final int OBSIDIAN_FOLDS = 12;
    public static final int PEARL_FOLDS = 13;
    public static final int AMBER_GALLERY = 14;
    public static final int IVORY_CURVES = 15;
    public static final int COPPER_ARCS = 16;
    public static final int OPAL_RIBBONS = 17;
    public static final int CONTENT_AMBIENT = 18;
    public static final int CALM_GRAPHITE_MIST = 19;
    public static final int CALM_PEARL_MIST = 20;
    public static final int CALM_INK_BLUE = 21;
    public static final int CALM_SILVER_BLUE = 22;
    public static final int CALM_SMOKE_SAGE = 23;
    public static final int CALM_FOG_GREEN = 24;
    public static final int CALM_MUTED_MAUVE = 25;
    public static final int CALM_DUSTY_ROSE = 26;
    public static final int CALM_WARM_CHARCOAL = 27;
    public static final int CALM_IVORY_LINEN = 28;
    public static final int DEFAULT_ID = NIGHT_LANDSCAPE;
    public static final int MAX_ID = 38;
    public static final int COUNT = MAX_ID + 1;

    public static final class Entry {
        public final int id;
        /** Empty for procedural entries. Kept for provenance and catalog inspection. */
        public final String drawableName;
        public final String titleRu;
        public final String titleEn;
        public final boolean light;
        public final boolean vector;

        private Entry(int id, String drawableName, String titleRu, String titleEn, boolean light) {
            this.id = id;
            this.drawableName = drawableName;
            this.titleRu = titleRu;
            this.titleEn = titleEn;
            this.light = light;
            this.vector = drawableName.isEmpty();
        }

        public String title(boolean russian) { return russian ? titleRu : titleEn; }
    }

    private static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
            new Entry(NIGHT_LANDSCAPE, "background_night", "Ночной пейзаж", "Night landscape", false),
            new Entry(LIGHT_RIBBON, "", "Светлая лента", "Light ribbon", true),
            new Entry(GRAPHITE, "", "Графит", "Graphite", false),
            new Entry(LIGHT_DASHBOARD, "", "Светлая панель", "Light dashboard", true),
            new Entry(MOONLIT_COAST, "background_moon_coast", "Лунный берег", "Moonlit coast", false),
            new Entry(MISTY_FOREST, "background_mist_forest", "Туманный лес", "Misty forest", false),
            new Entry(STARLIT_DUNES, "background_starlit_dunes", "Звёздные дюны", "Starlit dunes", false),
            new Entry(TWILIGHT_FJORD, "background_twilight_fjord", "Сумеречный фьорд", "Twilight fjord", false),
            new Entry(MOUNTAIN_PEAKS, "background_alpine_day", "Горные вершины", "Mountain peaks", true),
            new Entry(COAST, "background_coast_day", "Побережье", "Coast", true),
            new Entry(FOREST_FROM_ABOVE, "background_forest_day", "Лес с высоты", "Forest from above", true),
            new Entry(SAND_DUNES, "background_dunes_day", "Песчаные дюны", "Sand dunes", true),
            new Entry(OBSIDIAN_FOLDS, "background_obsidian_folds", "Обсидиановые складки", "Obsidian folds", false),
            new Entry(PEARL_FOLDS, "background_pearl_folds", "Жемчужные складки", "Pearl folds", true),
            new Entry(AMBER_GALLERY, "background_amber_gallery", "Янтарная галерея", "Amber gallery", false),
            new Entry(IVORY_CURVES, "background_ivory_curves", "Изгибы слоновой кости", "Ivory curves", true),
            new Entry(COPPER_ARCS, "background_copper_arcs", "Медные дуги", "Copper arcs", false),
            new Entry(OPAL_RIBBONS, "background_opal_ribbons", "Опаловые ленты", "Opal ribbons", true),
            new Entry(CONTENT_AMBIENT, "", "Оттенки контента", "Content ambience", true),
            new Entry(CALM_GRAPHITE_MIST, "background_calm_graphite_mist", "Графитовая дымка", "Graphite mist", false),
            new Entry(CALM_PEARL_MIST, "background_calm_pearl_mist", "Жемчужная дымка", "Pearl mist", true),
            new Entry(CALM_INK_BLUE, "background_calm_ink_blue", "Чернильная дымка", "Ink blue haze", false),
            new Entry(CALM_SILVER_BLUE, "background_calm_silver_blue", "Серебристая дымка", "Silver blue haze", true),
            new Entry(CALM_SMOKE_SAGE, "background_calm_smoke_sage", "Дымчатый шалфей", "Smoked sage", false),
            new Entry(CALM_FOG_GREEN, "background_calm_fog_green", "Туманная зелень", "Fog green", true),
            new Entry(CALM_MUTED_MAUVE, "background_calm_muted_mauve", "Приглушённый лиловый", "Muted mauve", false),
            new Entry(CALM_DUSTY_ROSE, "background_calm_dusty_rose", "Пыльная роза", "Dusty rose", true),
            new Entry(CALM_WARM_CHARCOAL, "background_calm_warm_charcoal", "Тёплый графит", "Warm charcoal", false),
            new Entry(CALM_IVORY_LINEN, "background_calm_ivory_linen", "Слоновая кость", "Ivory linen", true),
            new Entry(29, "background_amber_arch", "Янтарная арка", "Amber arch", false),
            new Entry(30, "background_ivory_vault", "Своды слоновой кости", "Ivory vault", true),
            new Entry(31, "background_bronze_passage", "Бронзовый проход", "Bronze passage", false),
            new Entry(32, "background_porcelain_waves", "Фарфоровые волны", "Porcelain waves", true),
            new Entry(33, "background_copper_alcove", "Медная ниша", "Copper alcove", false),
            new Entry(34, "background_linen_arcade", "Льняная аркада", "Linen arcade", true),
            new Entry(35, "background_obsidian_curve", "Обсидиановый изгиб", "Obsidian curve", false),
            new Entry(36, "background_pearl_atrium", "Жемчужный атриум", "Pearl atrium", true),
            new Entry(37, "background_honey_gallery", "Медовая галерея", "Honey gallery", false),
            new Entry(38, "background_alabaster_folds", "Алебастровые складки", "Alabaster folds", true)
    ));

    private static final int[] RESOURCES={
            R.drawable.background_night,
            0,
            0,
            0,
            R.drawable.background_moon_coast,
            R.drawable.background_mist_forest,
            R.drawable.background_starlit_dunes,
            R.drawable.background_twilight_fjord,
            R.drawable.background_alpine_day,
            R.drawable.background_coast_day,
            R.drawable.background_forest_day,
            R.drawable.background_dunes_day,
            R.drawable.background_obsidian_folds,
            R.drawable.background_pearl_folds,
            R.drawable.background_amber_gallery,
            R.drawable.background_ivory_curves,
            R.drawable.background_copper_arcs,
            R.drawable.background_opal_ribbons,
            0,
            R.drawable.background_calm_graphite_mist,
            R.drawable.background_calm_pearl_mist,
            R.drawable.background_calm_ink_blue,
            R.drawable.background_calm_silver_blue,
            R.drawable.background_calm_smoke_sage,
            R.drawable.background_calm_fog_green,
            R.drawable.background_calm_muted_mauve,
            R.drawable.background_calm_dusty_rose,
            R.drawable.background_calm_warm_charcoal,
            R.drawable.background_calm_ivory_linen,
            R.drawable.background_amber_arch,
            R.drawable.background_ivory_vault,
            R.drawable.background_bronze_passage,
            R.drawable.background_porcelain_waves,
            R.drawable.background_copper_alcove,
            R.drawable.background_linen_arcade,
            R.drawable.background_obsidian_curve,
            R.drawable.background_pearl_atrium,
            R.drawable.background_honey_gallery,
            R.drawable.background_alabaster_folds
    };

    private WallpaperCatalog() {}

    public static List<Entry> all() { return ENTRIES; }
    // Gallery chooses a pair, not an individual time-of-day image. Legacy IDs remain readable.
    public static List<Entry> selectable() {
        java.util.ArrayList<Entry> visible=new java.util.ArrayList<>();
        for(int id:new int[]{0,2,4,5,6,7,12,14,16,18,19,21,23,25,27,29,31,33,35,37}) visible.add(get(id));
        return Collections.unmodifiableList(visible);
    }
    private static final int[][] PAIRS={{0,8},{2,3},{4,9},{5,10},{6,11},{12,13},{14,15},{16,17},{19,20},{21,22},{23,24},{25,26},{27,28},{29,30},{31,32},{33,34},{35,36},{37,38}};
    public static int pairId(int id){
        id=normalize(id);if(id==1)return 2;
        for(int[] pair:PAIRS)if(id==pair[0]||id==pair[1])return pair[0];
        return id;
    }
    public static int effectiveId(int id,boolean night){
        id=pairId(id);if(id==7)return night?7:8;
        for(int[] pair:PAIRS)if(id==pair[0])return night?pair[0]:pair[1];
        return id;
    }
    public static boolean isLight(int id,boolean night){return id==CONTENT_AMBIENT?!night:isLight(effectiveId(id,night));}
    public static String pairTitle(int id,boolean russian){
        int pair=pairId(id);
        if(pair==0)return russian?"Горное озеро":"Mountain lake";
        if(pair==2)return russian?"Спокойный фон":"Calm backdrop";
        if(pair==4)return russian?"Морской берег":"Seashore";
        if(pair==6)return russian?"Песчаные дюны":"Sand dunes";
        if(pair==7)return russian?"Фьорд":"Fjord";
        return title(pair,russian);
    }
    public static boolean isValid(int id) { return id >= DEFAULT_ID && id <= MAX_ID; }
    public static int normalize(int id) { return isValid(id) ? id : DEFAULT_ID; }
    public static Entry get(int id) { return ENTRIES.get(normalize(id)); }
    public static boolean isLight(int id) { return get(id).light; }
    public static boolean isVector(int id) { return get(id).vector; }
    public static String title(int id, boolean russian) { return get(id).title(russian); }


    /** Compile-time resource binding: missing packaged artwork fails the build. */
    public static int resource(Context context,int id){
        if(context==null)throw new IllegalArgumentException("Context is required");
        return RESOURCES[normalize(id)];
    }

    /** A phase-specific resource shares its persisted pair ID. */
    public static int resource(Context context,int id,boolean night){
        if(context==null)throw new IllegalArgumentException("Context is required");
        if(pairId(id)==TWILIGHT_FJORD&&!night)return R.drawable.background_fjord_day;
        return resource(context,effectiveId(id,night));
    }
}
