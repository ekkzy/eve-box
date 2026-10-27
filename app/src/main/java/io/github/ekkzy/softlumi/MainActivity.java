package io.github.ekkzy.softlumi;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.ekkzy.softlumi.data.AppCatalog;
import io.github.ekkzy.softlumi.data.AppEntry;
import io.github.ekkzy.softlumi.data.ChannelRepository;
import io.github.ekkzy.softlumi.data.ChannelProbeSession;
import io.github.ekkzy.softlumi.data.RecommendationsSession;
import io.github.ekkzy.softlumi.data.RecommendationSources;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.data.WatchNextRepository;
import io.github.ekkzy.softlumi.data.WeatherRepository;
import io.github.ekkzy.softlumi.data.WallpaperCatalog;
import io.github.ekkzy.softlumi.data.DayNightPolicy;
import io.github.ekkzy.softlumi.core.WeatherPolicy;
import io.github.ekkzy.softlumi.ui.SceneBackground;
import io.github.ekkzy.softlumi.ui.SymbolView;
import io.github.ekkzy.softlumi.ui.UiImageLoader;
import io.github.ekkzy.softlumi.ui.UiDocumentIo;
import io.github.ekkzy.softlumi.ui.UiMotion;
import io.github.ekkzy.softlumi.ui.UiSounds;
import io.github.ekkzy.softlumi.ui.UiTypography;
import io.github.ekkzy.softlumi.ui.TvScrollView;
import io.github.ekkzy.softlumi.ui.TvHorizontalScrollView;
import io.github.ekkzy.softlumi.ui.ContentBackdrop;
import io.github.ekkzy.softlumi.ui.ArtworkSheenDrawable;
import io.github.ekkzy.softlumi.ui.SurfaceShadowView;
import io.github.ekkzy.softlumi.ui.GlassBackdrop;
import io.github.ekkzy.softlumi.ui.GlassScene;
import io.github.ekkzy.softlumi.ui.ThreeDayForecastView;
import io.github.ekkzy.softlumi.ui.CompactWeatherView;
import io.github.ekkzy.softlumi.ui.AmbientTextShadow;
import io.github.ekkzy.softlumi.ui.SharedRowsSheet;
import io.github.ekkzy.softlumi.data.SettingsBackup;
import io.github.ekkzy.softlumi.remote.RemoteButtonPreferences;
import io.github.ekkzy.softlumi.remote.RemoteButtonService;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.IdentityHashMap;

/** TV home composed from native focusable Views. All provider and bitmap work is off the UI thread. */
public final class MainActivity extends Activity {
    private static final int WIDTH = 1280, HEIGHT = 720;
    private static final int CARD_RADIUS=20;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean observingScrollMotion;
    private final ContentObserver scrollMotionObserver=new ContentObserver(main){
        @Override public void onChange(boolean selfChange){if(resumed)applyScrollMotionPolicy(renderHost,allowsScrollMotion());}
    };
    private final ExecutorService repositoryIo = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ThreadPoolExecutor cityIo=new java.util.concurrent.ThreadPoolExecutor(1,1,15,
            java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1),task->new Thread(()->{
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);task.run();
            },"softlumi-city"));
    private UiImageLoader imageLoader;
    private final android.view.ViewTreeObserver.OnScrollChangedListener imageVisibilityListener=this::loadVisibleIcons;
    private UiMotion uiMotion;
    private UiSounds uiSounds;
    private UiTypography typography;
    private GlassBackdrop glassBackdrop;
    private GlassScene glassScene;
    private UiMotion.Transition pendingTransition=UiMotion.Transition.SCREEN_ENTER_FORWARD;
    private UiDocumentIo documentIo;private int documentGeneration;
    private final Map<String, Integer> stableIds = new HashMap<>();
    private final Map<String, View> targets = new LinkedHashMap<>();
    private final Map<View,String> focusKeys = new IdentityHashMap<>();
    private final Map<View,Integer> idleTints=new IdentityHashMap<>();
    private final Map<ImageView,AppEntry> pendingIcons = new LinkedHashMap<>();
    private final Map<ImageView,AppEntry> iconBindings=new LinkedHashMap<>();
    private final Map<ImageView,PosterBinding> posterBindings=new LinkedHashMap<>();
    private final java.util.Set<ImageView> pendingPosters=Collections.newSetFromMap(new IdentityHashMap<>());
    private TvHorizontalScrollView watchRail;
    private TvScrollView homeScroll;
    private int homeScrollOffset, galleryScrollOffset;
    private GalleryBindings galleryBindings;
    private String lastUpperFocus="";
    private final Map<ImageView,String> providerLogos=new LinkedHashMap<>();
    private final java.util.Set<ImageView> pendingLogos=Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<View> watchTiles=new ArrayList<>();
    private final WatchRowState[] watchRows={new WatchRowState()};
    private WatchRowState activeWatchState;
    private boolean restoringWatchRow;
    private boolean restoringPageFocus;
    private ImageView wallpaperView, ambientPosterView;
    private FrameLayout windowHost;
    private FrameLayout layeredHome;
    private SharedRowsSheet rowsSheet;
    private SharedRowsSheet.Spec pageRowsSpec;
    private boolean renderedNight;
    private String backdropSignature="", ambientPosterUri="";
    private HomeBindings homeBindings;
    private UiPage retainedPage;
    private final ArrayDeque<Route> backStack = new ArrayDeque<>();
    private final ArrayList<TextView> liveClock = new ArrayList<>();
    private final List<View> catalogGrid=new ArrayList<>();private int catalogColumns;
    private final Map<String, AppEntry> appsById = new LinkedHashMap<>();
    private List<AppEntry> catalog = Collections.emptyList();
    private List<WatchNextRepository.Entry> continuation = Collections.emptyList();
    private LauncherPrefs preferences;
    private LauncherPrefs.Settings prefs;
    private WeatherRepository weatherRepository;
    private WeatherRepository.WeatherResult weather;
    private String watchMessage = "";
    private WatchNextRepository.Status watchStatus=WatchNextRepository.Status.EMPTY;
    private boolean catalogLoaded,watchLoaded,fullRefreshPending;
    private volatile boolean resumed;
    private volatile boolean destroyed;
    private FrameLayout renderHost,root, designRoot, board;
    private Screen renderedScreen=Screen.HOME;
    private Screen screen = Screen.HOME;
    private String requestedFocus = "", lastHomeFocus = "", lastSettingsShortcut = "shortcut-launcher";
    private String pendingRestoredFocus="",lastRenderedDate="";
    private boolean lastRenderedWeatherStale,lastRenderedWeatherAvailable;
    private long nextWeatherHour=Long.MAX_VALUE,nextWeatherRequestAt=Long.MAX_VALUE;
    private volatile int renderGeneration;
    private int loadGeneration, cityGeneration;
    private List<String> reorderIds;
    private String movingId;
    private Route reorderReturn;
    private boolean dropArmed;
    private String cityQuery = "";
    private String cityMessage = "";
    private List<WeatherRepository.City> cityResults = Collections.emptyList();
    private Runnable pendingSearch;
    private TextView cityStatus;
    private LinearLayout cityList;
    private Palette palette;
    private Future<?> loadFuture, cityFuture;
    private TextView detailHeading, detailDescription;
    private boolean renderDirty,keyHeld,manageHiddenOnly;
    private boolean dispatchingDirection;
    private long lastInputAt;
    private int lastKeyCode;
    private String renderedPrefsSignature="",managedAppId="";
    private String renderedDataSignature="";
    private LinearLayout settingsRows;
    private int settingsWidth;
    private int homeFavoritesTop=382;
    private SettingsBackup.Preview backupPreview;
    private String pendingExportJson="",backupError="";
    private boolean watchPermissionBlocked;
    private RecommendationsSession recommendationsSession;
    private RecommendationsSession.Snapshot recommendations;
    private String homeSource="";
    private String pendingSourceEntry;
    private int focusInputGeneration;
    private int homeContentGeneration;
    private final Map<String,WatchRowState> sourceRows=new LinkedHashMap<>();
    private ChannelProbeSession channelProbeSession;
    private ChannelProbeSession.Result channelProbeResult;
    private ChannelRepository.Programs probeInspection;
    private boolean channelProbeLoading;
    private String channelProbeMessage="";
    private int probeProgramIndex;
    private int renderedWatchPermissionState=-1;
    private static final int EXPORT_SETTINGS=402,IMPORT_SETTINGS=403;
    private static final int REQUEST_WATCH_PERMISSION=404;

    private enum Screen { HOME, DRAWER, SETTINGS, APPEARANCE, CARD_FORMAT, WALLPAPER_DIM, DAY_NIGHT, DAY_START, NIGHT_START, GALLERY, WEATHER, CITY, ADVANCED, LANGUAGE, FAVORITES, ALL_APPS, MANAGE_APPS, APP_OPTIONS, RENAME_APP, BACKUP, BACKUP_PREVIEW, REMOTE, REMOTE_TARGETS, ABOUT, SOURCES, CHANNEL_PROBE, CHANNEL_PROBE_ITEM }
    private RemoteButtonPreferences remotePreferences;
    private RemoteButtonPreferences.Button remoteEditingButton=RemoteButtonPreferences.Button.NETFLIX;
    private static final class Route {
        final Screen screen; final String focus;
        Route(Screen screen, String focus) { this.screen = screen; this.focus = focus; }
    }
    private static final class PosterBinding {
        final String uri;final int width,height;
        PosterBinding(String uri,int width,int height){this.uri=uri;this.width=width;this.height=height;}
    }
    private static final class WallpaperPreview {
        final int resource,width,height;boolean loading;
        WallpaperPreview(int resource,int width,int height){this.resource=resource;this.width=width;this.height=height;}
    }
    private static final class GalleryBindings {
        TvScrollView scroll;
        final Map<ImageView,WallpaperPreview> images=new LinkedHashMap<>();
        final Map<Integer,TextView> captions=new LinkedHashMap<>();
    }
    private static final class WatchRowState {String id="";int index,scrollX,anchor=32;}
    /** Presentation only: the original provider record remains the launch authority. */
    private static final class HomeCard {
        final String id,title,genre,posterUri,packageName;final int progressPercent;final boolean hasPlaybackProgress;
        final WatchNextRepository.Entry continuation;final ChannelRepository.Channel channel;final ChannelRepository.Program program;
        HomeCard(WatchNextRepository.Entry entry){id=entry.id;title=entry.title;genre="";posterUri=entry.posterUri;packageName=entry.packageName;progressPercent=entry.progressPercent;hasPlaybackProgress=entry.hasPlaybackProgress;continuation=entry;channel=null;program=null;}
        HomeCard(ChannelRepository.Channel source,ChannelRepository.Program entry){id="program:"+entry.key;title=entry.title;genre=entry.genre;posterUri=entry.posterUri;packageName=entry.packageName;progressPercent=0;hasPlaybackProgress=false;continuation=null;channel=source;program=entry;}
    }
    private static final class WeatherStatusBinding {
        final FrameLayout view;final int width;final boolean ambient;
        WeatherStatusBinding(FrameLayout view,int width,boolean ambient){this.view=view;this.width=width;this.ambient=ambient;}
    }
    /** Updates to provider data own their small region, never the app shelf. */
    private static final class HomeBindings {
        FrameLayout content,watchHost,tabsHost,forecastCard;ThreeDayForecastView forecast;CompactWeatherView topWeather;
        int viewportWidth,cardWidth,cardHeight,gap;boolean poster;
        String catalog,watch,weather,date,cards,tabs;WatchNextRepository.Status status;
        final List<WeatherStatusBinding> weatherStatus=new ArrayList<>();
    }
    /** One outgoing page; a direct Home return may also reuse its detached, GPU-free tree. */
    private static final class UiPage {
        final Screen screen;final String prefsSignature,focus,dataSignature;
        final FrameLayout root,board,designRoot;
        final SharedRowsSheet.Spec rowsSpec;
        final Map<String,View> targets;final Map<View,String> focusKeys;final Map<View,Integer> idleTints;
        final Map<ImageView,AppEntry> icons;final Map<ImageView,PosterBinding> posters;
        final List<TextView> clocks;final List<View> grid;
        final LinearLayout settingsRows,cityList;final TextView cityStatus,detailHeading,detailDescription;
        final int catalogColumns,settingsWidth,homeFavoritesTop,permissionState;
        final TvHorizontalScrollView watchRail;final List<View> watchTiles;final WatchRowState watchState;
        final TvScrollView homeScroll;final Map<ImageView,String> logos;
        final HomeBindings home;final GalleryBindings gallery;
        final long nextWeatherHour;final String date;final boolean weatherStale,weatherAvailable;
        UiPage(MainActivity a){
            a.captureWatchRow();
            screen=a.renderedScreen;prefsSignature=a.renderedPrefsSignature;focus=a.focusKey();dataSignature=a.renderedDataSignature;
            root=a.root;board=a.board;designRoot=a.designRoot;rowsSpec=a.pageRowsSpec;
            targets=new LinkedHashMap<>(a.targets);focusKeys=new IdentityHashMap<>(a.focusKeys);idleTints=new IdentityHashMap<>(a.idleTints);
            icons=new LinkedHashMap<>(a.iconBindings);posters=new LinkedHashMap<>(a.posterBindings);clocks=new ArrayList<>(a.liveClock);grid=new ArrayList<>(a.catalogGrid);
            settingsRows=a.settingsRows;cityList=a.cityList;cityStatus=a.cityStatus;detailHeading=a.detailHeading;detailDescription=a.detailDescription;
            catalogColumns=a.catalogColumns;settingsWidth=a.settingsWidth;homeFavoritesTop=a.homeFavoritesTop;permissionState=a.renderedWatchPermissionState;
            nextWeatherHour=a.nextWeatherHour;date=a.lastRenderedDate;weatherStale=a.lastRenderedWeatherStale;weatherAvailable=a.lastRenderedWeatherAvailable;
            watchRail=a.watchRail;watchTiles=new ArrayList<>(a.watchTiles);watchState=a.activeWatchState;homeScroll=a.homeScroll;logos=new LinkedHashMap<>(a.providerLogos);
            home=a.homeBindings;gallery=a.galleryBindings;
        }
    }
    private static final class Palette {
        final boolean light; final int text, muted, surface, border, focus, focusText,ambientText,ambientMuted,ambientInactive;
        Palette(LauncherPrefs.Settings settings) {
            light = settings.paletteMode.equals("light")||settings.paletteMode.equals("auto")&&WallpaperCatalog.isLight(settings.background,DayNightPolicy.isNight(settings,System.currentTimeMillis()));
            text = light ? 0xff181818 : 0xfff5f5f5;
            muted = light ? 0xff5c5c5c : 0xffc4c4c4;
            surface = light ? 0xe6ffffff : 0xe0222222;
            border = light ? 0x30000000 : 0x40ffffff;
            focus = 0xfff5f5f5;
            focusText = 0xff181818;
            boolean ambientLight=WallpaperCatalog.isLight(settings.background,DayNightPolicy.isNight(settings,System.currentTimeMillis()))&&settings.wallpaperDim<40;
            ambientText=ambientLight?0xff181818:0xfff5f5f5;ambientMuted=ambientLight?0xff424242:0xffe0e0e0;
            ambientInactive=ambientLight?0xff66666e:0xff96969e;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        preferences = new LauncherPrefs(this);
        cityIo.allowCoreThreadTimeOut(true);
        remotePreferences = new RemoteButtonPreferences(this);
        imageLoader=new UiImageLoader(this);documentIo=new UiDocumentIo(this);uiMotion=new UiMotion(this,WIDTH);uiSounds=new UiSounds(this);typography=UiTypography.get(this);glassBackdrop=new GlassBackdrop();
        prefs = preferences.read();
        uiMotion.configure(true,prefs.reducedMotion);uiSounds.configure(prefs.sounds);
        weatherRepository = new WeatherRepository(getApplicationContext());
        recommendationsSession=new RecommendationsSession(this,snapshot->{
            if(destroyed)return;recommendations=snapshot;
            if(resumed&&prefs.recommendationsEnabled&&(screen==Screen.HOME||screen==Screen.SOURCES))scheduleDataRender();
        });
        if (state != null) {
            for(int i=0;i<watchRows.length;i++){WatchRowState row=watchRows[i];row.id=state.getString("watch-row-"+i+"-id","");row.index=Math.max(0,state.getInt("watch-row-"+i+"-index",0));row.scrollX=Math.max(0,state.getInt("watch-row-"+i+"-offset",0));row.anchor=state.getInt("watch-row-"+i+"-anchor",32);}
            try { screen = Screen.valueOf(state.getString("screen", "HOME")); } catch (IllegalArgumentException ignored) { screen = Screen.HOME; }
            requestedFocus = state.getString("focus", "");pendingExportJson=state.getString("pending-export","");
            requestedFocus=restoredSourceFocus(requestedFocus);
            pendingRestoredFocus=requestedFocus;
            cityQuery=state.getString("city-query","");homeScrollOffset=state.getInt("home-scroll",0);
            lastUpperFocus=restoredSourceFocus(state.getString("home-upper-origin",""));
            homeSource=sourcePackage(state.getString("home-source",""));
            for(String source:prefs.recommendationPackages){Bundle saved=state.getBundle("source-row:"+source);
                if(saved==null)for(String key:state.keySet())if(key.startsWith("source-row:")&&source.equals(sourcePackage(key.substring(11)))){saved=state.getBundle(key);break;}
                if(saved==null)continue;WatchRowState row=new WatchRowState();row.id=saved.getString("id","");row.index=Math.max(0,saved.getInt("index"));row.scrollX=Math.max(0,saved.getInt("offset"));row.anchor=saved.getInt("anchor",32);sourceRows.put(source,row);}
            String settingsOrigin=state.getString("home-settings-origin","");
            if(isHomeSettingsShortcut(settingsOrigin))lastSettingsShortcut=settingsOrigin;
            watchPermissionBlocked=state.getBoolean("watch-permission-blocked",false);
            managedAppId=state.getString("managed-app","");manageHiddenOnly=state.getBoolean("hidden-only",false);
            try{remoteEditingButton=RemoteButtonPreferences.Button.valueOf(state.getString("remote-button","NETFLIX"));}catch(IllegalArgumentException ignored){remoteEditingButton=RemoteButtonPreferences.Button.NETFLIX;}
            boolean restoreBackup=screen==Screen.BACKUP_PREVIEW;
            if(restoreBackup){screen=Screen.BACKUP;requestedFocus="backup-import";pendingRestoredFocus=requestedFocus;}
            ArrayList<String> routeScreens=state.getStringArrayList("route-screens"),routeFocus=state.getStringArrayList("route-focus");
            if(routeScreens!=null&&routeFocus!=null)for(int i=0;i<Math.min(routeScreens.size(),routeFocus.size());i++){
                try{backStack.addLast(new Route(Screen.valueOf(routeScreens.get(i)),restoredSourceFocus(routeFocus.get(i))));}catch(IllegalArgumentException ignored){}
            }
            if(restoreBackup&&!backStack.isEmpty()&&backStack.peekLast().screen==Screen.BACKUP)backStack.pollLast();
            if (screen != Screen.HOME && backStack.isEmpty()) backStack.add(new Route(Screen.HOME, ""));
        }
        render();
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        prefs = preferences.read();
        uiMotion.configure(true,prefs.reducedMotion);uiSounds.configure(prefs.sounds);uiMotion.resume();uiSounds.resume();glassBackdrop.resume();
        resumeScrollMotionPolicy();
        int permissionState=watchPermissionState();if(permissionState==2)watchPermissionBlocked=false;
        if(permissionState!=2)recommendations=null;
        imageLoader.resume();
        if(rowsSheet!=null)rowsSheet.resume();
        if(screen==Screen.REMOTE||screen==Screen.REMOTE_TARGETS||renderDirty||root!=null&&root.getVisibility()!=View.VISIBLE||permissionState!=renderedWatchPermissionState||!settingsSignature(prefs).equals(renderedPrefsSignature))renderPreservingFocus();
        else if(board!=null)board.post(()->{loadVisibleIcons();if(getCurrentFocus()==null){View target=targets.get(defaultFocusKey());if(target!=null)target.requestFocus();}});
        syncRecommendations();recommendationsSession.resume();
        refreshData();
        if(screen==Screen.CITY&&citySearchPending)scheduleCitySearch();
        main.removeCallbacks(clockTick); main.post(clockTick);
    }
    @Override protected void onPause() {
        pendingSourceEntry=null;
        resumed = false;keyHeld=false;loadGeneration++;cityGeneration++;main.removeCallbacks(clockTick);main.removeCallbacks(uiRender);main.removeCallbacks(dataRender);
        if(channelProbeLoading)renderDirty=true;
        if(channelProbeSession!=null)channelProbeSession.cancel();channelProbeLoading=false;
        recommendationsSession.pause();
        pauseScrollMotionPolicy();
        if(loadFuture!=null)loadFuture.cancel(true);if(cityFuture!=null)cityFuture.cancel(true);cityIo.purge();
        documentGeneration++;documentIo.cancel();imageLoader.pause();releaseHomeLayer(layeredHome);uiMotion.pause();uiSounds.pause();if(rowsSheet!=null)rowsSheet.pause();glassBackdrop.pause();
        if(renderHost!=null)for(int i=renderHost.getChildCount()-1;i>=0;i--)if(renderHost.getChildAt(i)!=root)renderHost.removeViewAt(i);
        retainedPage=null;
        if (pendingSearch != null) main.removeCallbacks(pendingSearch);
        super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; loadGeneration++; cityGeneration++;
        if(recommendationsSession!=null)recommendationsSession.close();
        if(channelProbeSession!=null)channelProbeSession.close();channelProbeResult=null;probeInspection=null;
        pauseScrollMotionPolicy();
        if(renderHost!=null&&renderHost.getViewTreeObserver().isAlive())renderHost.getViewTreeObserver().removeOnScrollChangedListener(imageVisibilityListener);
        main.removeCallbacksAndMessages(null); repositoryIo.shutdownNow();cityIo.shutdownNow();imageLoader.close();documentIo.close();releaseHomeLayer(layeredHome);uiMotion.close();retainedPage=null;uiSounds.close();if(rowsSheet!=null)rowsSheet.close();glassBackdrop.close();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        captureWatchRow();out.putInt("home-scroll",homeScrollOffset);out.putString("home-settings-origin",lastSettingsShortcut);
        out.putString("home-upper-origin",lastUpperFocus);out.putString("home-source",homeSource);
        for(Map.Entry<String,WatchRowState> item:sourceRows.entrySet()){WatchRowState row=item.getValue();Bundle saved=new Bundle();saved.putString("id",row.id);saved.putInt("index",row.index);saved.putInt("offset",row.scrollX);saved.putInt("anchor",row.anchor);out.putBundle("source-row:"+item.getKey(),saved);}
        for(int i=0;i<watchRows.length;i++){WatchRowState row=watchRows[i];out.putString("watch-row-"+i+"-id",row.id);out.putInt("watch-row-"+i+"-index",row.index);out.putInt("watch-row-"+i+"-offset",row.scrollX);out.putInt("watch-row-"+i+"-anchor",row.anchor);}
        out.putString("screen",reorderReturn==null?screen.name():reorderReturn.screen.name());
        out.putString("focus",reorderReturn==null?focusKey():reorderReturn.focus);
        out.putString("city-query",cityQuery);out.putString("pending-export",pendingExportJson);
        out.putBoolean("watch-permission-blocked",watchPermissionBlocked);
        out.putString("managed-app",managedAppId);out.putBoolean("hidden-only",manageHiddenOnly);
        out.putString("remote-button",remoteEditingButton.name());
        ArrayList<String> routeScreens=new ArrayList<>(),routeFocus=new ArrayList<>();
        for(Route route:backStack){routeScreens.add(route.screen.name());routeFocus.add(route.focus);}
        out.putStringArrayList("route-screens",routeScreens);out.putStringArrayList("route-focus",routeFocus);
        super.onSaveInstanceState(out);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        pendingSourceEntry=null;
        if(channelProbeSession!=null)channelProbeSession.cancel();channelProbeLoading=false;channelProbeResult=null;probeInspection=null;
        documentGeneration++;documentIo.cancel();
        boolean keepHome=screen==Screen.HOME&&renderedScreen==Screen.HOME&&root!=null&&movingId==null
                &&renderedPrefsSignature.equals(settingsSignature(preferences.read()))
                &&renderedWatchPermissionState==watchPermissionState();
        reorderIds = null; movingId = null;reorderReturn=null;pendingRestoredFocus="";backStack.clear(); screen = Screen.HOME;
        if(homeScroll!=null)homeScroll.scrollTo(0,0);homeScrollOffset=0;
        requestedFocus = lastHomeFocus.startsWith("shortcut-")?defaultFavoriteFocus():lastHomeFocus;
        if(keepHome){
            View desired=targets.get(requestedFocus);if(desired==null)desired=targets.get(defaultFocusKey());
            if(desired!=null)desired.requestFocus();
            if(homeScroll!=null)homeScroll.scrollTo(0,0);
        }else render();
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // UI_HIDDEN is a normal app switch, not memory pressure. Keep bounded
        // decoded icons for the next HOME instead of forcing every tile to reload.
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL
                || level >= TRIM_MEMORY_BACKGROUND) {
            retainedPage=null;
            if(!resumed){
                // A stopped Home has no visible pixels to preserve under real pressure.
                // Drop View references too: evicting the LRU alone cannot release these bitmaps.
                clearStoppedImages(renderHost);clearStoppedImages(glassScene);
                backdropSignature="";renderedPrefsSignature="";renderDirty=true;
            }
            imageLoader.trim();
            if(glassBackdrop!=null)glassBackdrop.clear();
        }
    }
    private void clearStoppedImages(View view){
        if(view instanceof ImageView)imageLoader.forget((ImageView)view);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)clearStoppedImages(group.getChildAt(i));}
    }

    private void refreshData() { refreshData(false,false); }
    private void refreshData(boolean forceWeather) { refreshData(forceWeather,canRefreshWeatherOnly()); }
    private boolean canRefreshWeatherOnly(){return !fullRefreshPending&&catalogLoaded&&(!prefs.watchNextVisible||watchLoaded);}
    private void refreshData(boolean forceWeather,boolean weatherOnly) {
        int token = ++loadGeneration;
        LauncherPrefs.Settings snapshot = preferences.read();
        nextWeatherRequestAt=Long.MAX_VALUE;
        if(loadFuture!=null)loadFuture.cancel(true);
        // Loaded flags describe the last published catalog, not a pending resume refresh.
        // Keep full scope until both catalog callbacks have actually reached the UI thread.
        fullRefreshPending=!weatherOnly;
        loadFuture=repositoryIo.submit(() -> {
            if(!weatherOnly){
            List<AppEntry> loaded;
            try { loaded = AppCatalog.load(getApplicationContext()); } catch (RuntimeException e) { loaded = Collections.emptyList(); }
            List<AppEntry> result = loaded;
            main.post(() -> {
                if (destroyed || token != loadGeneration) return;
                boolean changed=!catalogLoaded||!catalogSignature(catalog).equals(catalogSignature(result));
                String previousPrefs=settingsSignature(prefs);
                catalog = result; catalogLoaded = true; appsById.clear();
                for (AppEntry app : result) appsById.put(app.id, app);
                if(movingId!=null&&!appsById.containsKey(movingId)){
                    reorderIds=null;movingId=null;
                    if(reorderReturn!=null){screen=reorderReturn.screen;requestedFocus=reorderReturn.focus;reorderReturn=null;}
                }
                preferences.migrateFavoriteComponents(result);
                prefs = preferences.read(); if(changed||!previousPrefs.equals(settingsSignature(prefs)))scheduleDataRender();
            });
            if(Thread.currentThread().isInterrupted())return;
            WatchNextRepository.Result watched = snapshot.watchNextVisible?new WatchNextRepository().load(getApplicationContext()):null;
            main.post(() -> {
                if (destroyed || token != loadGeneration) return;
                fullRefreshPending=false;
                if(watched==null)return;
                boolean changed=!watchLoaded||watchStatus!=watched.status||!watchSignature(continuation).equals(watchSignature(watched.items));
                continuation = watched.items;
                watchLoaded=true;
                watchStatus=watched.status;
                watchMessage = watched.message == null ? "" : watched.message;
                if (changed&&(screen==Screen.HOME||prefs.background==WallpaperCatalog.CONTENT_AMBIENT&&(screen==Screen.DRAWER||screen==Screen.GALLERY)))scheduleDataRender();
            });
            }
            if(Thread.currentThread().isInterrupted())return;
            WeatherRepository.WeatherResult forecast = weatherRepository.loadCurrent(snapshot, forceWeather);
            main.post(() -> {
                if (destroyed || token != loadGeneration) return;
                String previousSettings=settingsSignature(prefs);prefs=preferences.read();
                boolean settingsChanged=!previousSettings.equals(settingsSignature(prefs));
                boolean changed=!weatherSignature(weather).equals(weatherSignature(forecast));weather = forecast;
                nextWeatherRequestAt=forecast.retryAtMillis>0?forecast.retryAtMillis:Long.MAX_VALUE;
                if (settingsChanged||changed&&(screen == Screen.HOME || screen == Screen.DRAWER || screen == Screen.WEATHER)) scheduleDataRender();
            });
        });
    }

    private String settingsSignature(LauncherPrefs.Settings value){
        if(value==null)return "";
        // Presentation identity must work even when a legitimate local configuration
        // exceeds the portable backup limit. Length-prefixed parts are unambiguous.
        StringBuilder key=new StringBuilder(512);
        for(Object part:new Object[]{value.cardFormat,value.background,value.language,value.paletteMode,value.wallpaperDim,
                value.wallpaperPeriod,value.dayStartMinutes,value.nightStartMinutes,DayNightPolicy.isNight(value,System.currentTimeMillis()),
                value.weatherEnabled,value.cityName,value.cityLatitude,value.cityLongitude,value.cityId,value.cityTimezone,value.units,
                value.reducedMotion,value.sounds,value.favoriteIds,value.hiddenPackages,value.customLabels,value.watchNextVisible,
                value.recommendationsEnabled,value.recommendationPackages,value.recommendationSourceOrder})appendSettingsPart(key,part);
        return key.toString();
    }
    private static void appendSettingsPart(StringBuilder key,Object value){
        if(value instanceof Map<?,?>){
            Map<?,?> map=(Map<?,?>)value;List<String> keys=new ArrayList<>();for(Object item:map.keySet())keys.add((String)item);Collections.sort(keys);
            key.append('M').append(keys.size()).append(':');for(String item:keys){appendSettingsPart(key,item);appendSettingsPart(key,map.get(item));}
        }else if(value instanceof java.util.Collection<?>){
            java.util.Collection<?> items=(java.util.Collection<?>)value;
            if(value instanceof java.util.Set<?>){List<String> sorted=new ArrayList<>();for(Object item:items)sorted.add((String)item);Collections.sort(sorted);items=sorted;}
            key.append('L').append(items.size()).append(':');for(Object item:items)appendSettingsPart(key,item);
        }else{String text=String.valueOf(value);key.append(text.length()).append(':').append(text);}
    }
    private static String catalogSignature(List<AppEntry> apps){StringBuilder value=new StringBuilder();for(AppEntry app:apps)value.append(app.id).append('\0').append(app.label).append('\0').append(app.artworkVersion).append('\n');return value.toString();}
    private static String watchSignature(List<WatchNextRepository.Entry> items){StringBuilder value=new StringBuilder();for(WatchNextRepository.Entry item:items)value.append(item.id).append('\0').append(item.title).append('\0').append(item.description).append('\0').append(item.posterUri).append('\0').append(item.intentUri).append('\0').append(item.packageName).append('\0').append(item.progressPercent).append('\0').append(item.hasPlaybackProgress).append('\0').append(item.kind).append('\n');return value.toString();}
    private static String weatherSignature(WeatherRepository.WeatherResult result){
        if(result==null)return "";StringBuilder value=new StringBuilder(result.status.name()).append(result.failure).append(result.httpStatus);WeatherRepository.Weather w=result.weather;
        if(w!=null){value.append(w.provider).append(w.temperature).append(w.windSpeed).append(w.weatherCode).append(w.fetchedAtMillis).append(w.observedAtMillis).append(w.units).append(w.isDay).append(w.timezone).append(w.sunriseMillis).append(w.sunsetMillis).append(w.cityTimezoneKnown).append(w.forecastTimeBasis).append(w.forecastTimezone);for(WeatherRepository.Hour hour:w.hours)value.append(hour.timeMillis).append(':').append(hour.temp).append(':').append(hour.weatherCode).append(':').append(hour.isDay);
            for(WeatherRepository.Day day:w.days)value.append('|').append(day.date).append(':').append(day.startMillis).append(':').append(day.endMillis).append(':').append(day.weatherCode)
                    .append(':').append(day.minTemperature).append(':').append(day.maxTemperature).append(':').append(day.precipitationMm).append(':').append(day.precipitationProbabilityMax)
                    .append(':').append(day.windSpeedMax).append(':').append(day.windGustsMax).append(':').append(day.uvIndexMax).append(':').append(day.sunriseMillis).append(':').append(day.sunsetMillis)
                    .append(':').append(day.aggregated).append(':').append(day.partialDay).append(':').append(day.coverageStartMillis).append(':').append(day.coverageEndMillis);
        }
        return value.toString();
    }

    private void renderPreservingFocus() {renderDirty=true;if(!resumed)return;main.removeCallbacks(uiRender);main.postDelayed(uiRender,16);}
    private final Runnable uiRender=()->{if(!resumed||destroyed)return;requestedFocus=pendingRestoredFocus.isEmpty()?focusKey():pendingRestoredFocus;if(!refreshHomeDataInPlace())render();};
    private final Runnable dataRender=new Runnable(){@Override public void run(){
        if(!resumed||destroyed)return;
        if(keyHeld||movingId!=null||uiMotion.isRunning()||rowsSheet!=null&&rowsSheet.isAnimating()||hasActiveScroll(renderHost)||android.os.SystemClock.uptimeMillis()-lastInputAt<80){main.postDelayed(this,80);return;}
        requestedFocus=pendingRestoredFocus.isEmpty()?focusKey():pendingRestoredFocus;if(!refreshHomeDataInPlace())render();
    }};
    private void scheduleDataRender(){renderDirty=true;main.removeCallbacks(dataRender);if(resumed)main.postDelayed(dataRender,80);}
    private static boolean hasActiveScroll(View view){
        if(view instanceof TvScrollView&&((TvScrollView)view).isScrollAnimating())return true;
        if(view instanceof TvHorizontalScrollView&&((TvHorizontalScrollView)view).isScrollAnimating())return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasActiveScroll(((ViewGroup)view).getChildAt(i)))return true;
        return false;
    }
    private String focusKey() {
        if(restoringPageFocus)return requestedFocus==null?"":requestedFocus;
        View focused = getCurrentFocus();
        if (focused != null && focusKeys.containsKey(focused)) return focusKeys.get(focused);
        return requestedFocus == null ? "" : requestedFocus;
    }
    private void push(Screen next) {
        pendingSourceEntry=null;
        documentGeneration++;documentIo.cancel();
        pendingRestoredFocus="";
        if (screen == Screen.HOME) lastHomeFocus = focusKey();
        backStack.addLast(new Route(screen, focusKey()));
        pendingTransition=next==Screen.DRAWER?UiMotion.Transition.DRAWER_ENTER:UiMotion.Transition.SCREEN_ENTER_FORWARD;
        screen = next; requestedFocus = ""; render();
    }
    private void goBack() {
        pendingSourceEntry=null;
        documentGeneration++;documentIo.cancel();
        if(screen==Screen.CHANNEL_PROBE){if(channelProbeSession!=null)channelProbeSession.cancel();channelProbeLoading=false;channelProbeResult=null;probeInspection=null;}
        if (movingId != null) { cancelReorder(); return; }
        if (screen == Screen.HOME) {
            if(homeScroll!=null&&(homeScroll.getScrollY()>0||homeScroll.isScrollAnimating())){
                View favorite=targets.get(lastUpperFocus);if(favorite==null)favorite=targets.get(defaultFavoriteFocus());if(favorite!=null)favorite.requestFocus();
                homeScroll.smoothScrollToPosition(0);
            }
            return;
        }
        if (screen == Screen.CITY) {
            cityGeneration++; if (pendingSearch != null) main.removeCallbacks(pendingSearch);
            if(cityFuture!=null){cityFuture.cancel(true);cityFuture=null;}
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(board.getWindowToken(), 0);
        }
        Route previous = backStack.pollLast();
        screen = previous == null ? Screen.HOME : previous.screen;
        requestedFocus = previous == null ? lastHomeFocus : previous.focus;
        pendingTransition=UiMotion.Transition.SCREEN_ENTER_BACK;render();
    }
    @Override public void onBackPressed() { if(movingId!=null||screen!=Screen.HOME)uiSounds.back();goBack(); }

    private void render() {
        if (destroyed) return;
        captureWatchRow();
        prefs=preferences.read();syncRecommendations();homeContentGeneration++;
        if(homeScroll!=null)homeScrollOffset=homeScroll.getScrollY();
        if(galleryBindings!=null)galleryScrollOffset=galleryBindings.scroll.getScrollY();
        if(screen!=Screen.REMOTE&&screen!=Screen.REMOTE_TARGETS&&pendingTransition!=null&&retainedPage!=null&&retainedPage.screen==screen
                &&retainedPage.prefsSignature.equals(settingsSignature(preferences.read()))
                &&(retainedPage.root.getParent()==renderHost||canRestoreDetachedHome(retainedPage))){restoreRetainedPage(pendingTransition);return;}
        int generation = ++renderGeneration;
        boolean remoteTargetsReady = screen != Screen.REMOTE_TARGETS || catalogLoaded;
        restoringPageFocus=true;
        UiMotion.Transition entering=pendingTransition;pendingTransition=null;
        FrameLayout outgoingRoot=root,outgoingBoard=board;Screen outgoingScreen=renderedScreen;UiPage obsoletePage=retainedPage;
        boolean retainOutgoing=entering!=null&&outgoingRoot!=null&&resumed;
        UiPage outgoingPage=retainOutgoing?new UiPage(this):null;
        main.removeCallbacks(uiRender);main.removeCallbacks(dataRender);renderDirty=false;
        imageLoader.setGeneration(generation);
        prefs = preferences.read(); syncRecommendations(); palette = new Palette(prefs);renderedNight=DayNightPolicy.isNight(prefs,System.currentTimeMillis());settingsRows=null;uiMotion.configure(true,prefs.reducedMotion);uiSounds.configure(prefs.sounds);
        targets.clear(); focusKeys.clear();idleTints.clear();catalogGrid.clear();pendingIcons.clear();iconBindings.clear();posterBindings.clear();pendingPosters.clear();watchTiles.clear();watchRail=null;homeScroll=null;homeBindings=null;galleryBindings=null;providerLogos.clear();pendingLogos.clear();activeWatchState=null;liveClock.clear();nextWeatherHour=Long.MAX_VALUE;cityList = null; cityStatus = null;detailHeading=null;detailDescription=null;
        if(renderHost==null){
            windowHost=new FrameLayout(this);glassScene=new GlassScene(this);
            windowHost.addView(glassScene,new FrameLayout.LayoutParams(-1,-1));
            rowsSheet=new SharedRowsSheet(this,glassBackdrop,glassScene);windowHost.addView(rowsSheet,new FrameLayout.LayoutParams(-1,-1));
            renderHost=new FrameLayout(this);windowHost.addView(renderHost,new FrameLayout.LayoutParams(-1,-1));setContentView(windowHost);
            renderHost.getViewTreeObserver().addOnScrollChangedListener(imageVisibilityListener);
        }
        ensureBackdrop(generation);
        if(!retainOutgoing){releaseHomeLayer(layeredHome);renderHost.removeAllViews();}
        retainedPage=outgoingPage;
        if(retainOutgoing&&obsoletePage!=null&&obsoletePage.root!=outgoingRoot&&obsoletePage.root.getParent()==renderHost)uiMotion.crossfade(obsoletePage.root,false,()->cleanupOutgoing(obsoletePage.root));
        root = new FrameLayout(this); root.setClipChildren(false); root.setClipToPadding(false);
        // Layout may draw before an after-layout post runs. Never expose an
        // opaque incoming page before its alpha channel has been initialized.
        if(entering!=null)root.setVisibility(View.INVISIBLE);
        designRoot=new FrameLayout(this);designRoot.setClipChildren(false);designRoot.setClipToPadding(false);
        FrameLayout.LayoutParams design = new FrameLayout.LayoutParams(WIDTH, HEIGHT, Gravity.CENTER);
        root.addView(designRoot, design);
        pageRowsSpec=null;
        board = new FrameLayout(this); board.setClipChildren(false); board.setClipToPadding(false);designRoot.addView(board,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout scaledDesign=designRoot;
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            float scale = Math.min((r-l)/(float)WIDTH, (b-t)/(float)HEIGHT);
            scaledDesign.setScaleX(scale); scaledDesign.setScaleY(scale);
        });
        if(retainOutgoing)renderHost.addView(root,0,new FrameLayout.LayoutParams(-1,-1));else renderHost.addView(root,new FrameLayout.LayoutParams(-1,-1));
        if(retainOutgoing){outgoingRoot.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);outgoingRoot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);outgoingRoot.clearFocus();}
        switch (screen) {
            case HOME: home(); break;
            case DRAWER: drawer(); break;
            case SETTINGS: settings(); break;
            case APPEARANCE: appearance(); break;
            case CARD_FORMAT: cardFormats(); break;
            case WALLPAPER_DIM: wallpaperDim(); break;
            case GALLERY: wallpaperGallery();break;
            case WEATHER: weatherSettings(); break;
            case CITY: citySearch(); break;
            case ADVANCED: advanced(); break;
            case SOURCES: recommendationSources(); break;
            case CHANNEL_PROBE: channelProbe(); break;
            case CHANNEL_PROBE_ITEM: channelProbeItem(); break;
            case LANGUAGE: languages(); break;
            case FAVORITES: applications(true); break;
            case ALL_APPS: applications(false); break;
            case MANAGE_APPS:applications(false);break;
            case APP_OPTIONS:appOptions();break;
            case RENAME_APP:renameApp();break;
            case BACKUP:backupSettings();break;
            case BACKUP_PREVIEW:backupPreview();break;
            case DAY_NIGHT:dayNightSettings();break;
            case DAY_START:schedulePicker(true);break;
            case NIGHT_START:schedulePicker(false);break;
            case REMOTE: remoteInfo(); break;
            case REMOTE_TARGETS: remoteTargets(); break;
            case ABOUT: about(); break;
        }
        lastRenderedDate=formatDate();lastRenderedWeatherStale=isWeatherStale();lastRenderedWeatherAvailable=weatherAvailable();renderedPrefsSignature=settingsSignature(prefs);renderedDataSignature=pageDataSignature();renderedWatchPermissionState=watchPermissionState();renderedScreen=screen;
        afterLayout(board,() -> {
            if (destroyed || !resumed || generation != renderGeneration) return;
            rowsSheet.present(pageRowsSpec,entering!=null&&!prefs.reducedMotion);
            if(entering!=null){
                FrameLayout incoming=root;
                if(screen==Screen.HOME)cacheHomeForTransition(incoming);else if(screen!=Screen.GALLERY)uiMotion.transitionSpatial(board,entering);
                uiMotion.crossfade(incoming,true,()->releaseHomeLayer(incoming));incoming.setVisibility(View.VISIBLE);
            }
            restoreWatchRow();
            if(homeScroll!=null)homeScroll.scrollTo(0,homeScrollOffset);
            if(galleryBindings!=null)galleryBindings.scroll.scrollTo(0,galleryScrollOffset);
            wireFocus();
            String wanted=pendingRestoredFocus.isEmpty()?requestedFocus:pendingRestoredFocus;
            View desired = targets.get(wanted);
            if(desired==null&&wanted.startsWith("watch-")&&watchRail!=null)desired=selectedWatchTile();
            // A late catalog callback does not populate this already-built loading picker.
            if(desired!=null||remoteTargetsReady&&focusDataReady(wanted))pendingRestoredFocus="";
            if (desired == null && screen == Screen.HOME) desired = targets.get(lastHomeFocus);
            if (desired == null) desired=targets.get(defaultFocusKey());
            // Catalog data can arrive before this older tree's first layout. Its
            // loading dock still has favorite-add, not the newly resolved app ID.
            if(desired==null&&screen==Screen.HOME)desired=targets.get("favorite-add");
            if (desired == null && !targets.isEmpty()) desired = targets.values().iterator().next();
            restoringPageFocus=false;
            if (desired != null){desired.requestFocus();if(outgoingRoot!=null)snapFocus(desired);}
            completePendingSourceEntry();
            loadVisibleIcons();
            if(retainOutgoing){
                if(outgoingScreen==Screen.HOME)cacheHomeForTransition(outgoingRoot);else if(outgoingScreen!=Screen.GALLERY)uiMotion.transitionSpatial(outgoingBoard,exitTransition(outgoingScreen,entering));
                uiMotion.crossfade(outgoingRoot,false,()->cleanupOutgoing(outgoingRoot));
            }
        });
    }
    private String pageDataSignature(){return catalogLoaded+":"+watchLoaded+":"+watchStatus+":"+watchPermissionState()+":"+watchPermissionBlocked+"|"+catalogSignature(catalog)+watchSignature(continuation)+weatherSignature(weather)+homeSource+recommendationsSignature();}
    private static UiMotion.Transition exitTransition(Screen previous,UiMotion.Transition entering){return previous==Screen.DRAWER?UiMotion.Transition.DRAWER_EXIT:entering==UiMotion.Transition.SCREEN_ENTER_BACK?UiMotion.Transition.SCREEN_EXIT_BACK:UiMotion.Transition.SCREEN_EXIT_FORWARD;}
    private void cleanupOutgoing(FrameLayout previous){
        if(previous==root)return;
        boolean keepHome=resumed&&!destroyed&&retainedPage!=null&&retainedPage.root==previous
                &&retainedPage.screen==Screen.HOME&&(screen==Screen.GALLERY||screen==Screen.SETTINGS)
                &&!hasActiveScroll(previous);
        releaseHomeLayer(previous);
        if(previous.getParent()==renderHost)renderHost.removeView(previous);
        if(retainedPage!=null&&retainedPage.root==previous&&!keepHome)retainedPage=null;
    }
    private boolean canRestoreDetachedHome(UiPage page){
        return page.screen==Screen.HOME&&page.root.getParent()==null
                &&(renderedScreen==Screen.GALLERY||renderedScreen==Screen.SETTINGS)
                &&page.dataSignature.equals(pageDataSignature())&&page.permissionState==watchPermissionState()
                &&page.date.equals(formatDate())&&System.currentTimeMillis()<page.nextWeatherHour
                &&(weather==null||weather.weather==null||weather.weather.cityTimezoneKnown)
                &&page.weatherStale==isWeatherStale()&&page.weatherAvailable==weatherAvailable();
    }
    private void cacheHomeForTransition(FrameLayout target){
        if(layeredHome==target)return;
        releaseHomeLayer(layeredHome);
        // At most one transient surface, bounded to the verified 4K window.
        // Child invalidations still repaint it; the live backdrop is outside it.
        if(target==null||target.getWidth()<=0||target.getHeight()<=0||(long)target.getWidth()*target.getHeight()>3840L*2160)return;
        layeredHome=target;target.setLayerType(View.LAYER_TYPE_HARDWARE,null);
    }
    private void releaseHomeLayer(FrameLayout target){
        if(target==null)return;
        if(target.getLayerType()!=View.LAYER_TYPE_NONE)target.setLayerType(View.LAYER_TYPE_NONE,null);
        if(layeredHome==target)layeredHome=null;
    }
    private View focusPresentation(View view){if("shortcut-weather".equals(view.getTag())){View action=view.findViewWithTag("weather-action");if(action!=null)return action;}return view;}
    private void snapFocus(View view){View shown=focusPresentation(view);UiMotion.Style style=motionStyle(focusKeys.getOrDefault(shown,""));uiMotion.snapFocus(shown,true,style,focusScale(shown,style));}
    private void restoreRetainedPage(UiMotion.Transition entering){
        UiPage returning=retainedPage,outgoing=new UiPage(this);int generation=++renderGeneration;boolean pendingData=renderDirty;
        boolean detached=returning.root.getParent()==null;
        pendingTransition=null;main.removeCallbacks(uiRender);main.removeCallbacks(dataRender);imageLoader.setGeneration(generation);
        root=returning.root;board=returning.board;designRoot=returning.designRoot;pageRowsSpec=returning.rowsSpec;
        homeScroll=returning.homeScroll;homeBindings=returning.home;galleryBindings=returning.gallery;if(galleryBindings!=null)for(WallpaperPreview preview:galleryBindings.images.values())preview.loading=false;providerLogos.clear();providerLogos.putAll(returning.logos);pendingLogos.clear();for(ImageView view:providerLogos.keySet())if(view.getDrawable()==null)pendingLogos.add(view);
        watchRail=returning.watchRail;watchTiles.clear();watchTiles.addAll(returning.watchTiles);activeWatchState=returning.watchState;restoringWatchRow=false;
        targets.clear();targets.putAll(returning.targets);focusKeys.clear();focusKeys.putAll(returning.focusKeys);idleTints.clear();idleTints.putAll(returning.idleTints);
        iconBindings.clear();iconBindings.putAll(returning.icons);pendingIcons.clear();for(Map.Entry<ImageView,AppEntry> icon:returning.icons.entrySet())if(icon.getKey().getDrawable()==null)pendingIcons.put(icon.getKey(),icon.getValue());posterBindings.clear();posterBindings.putAll(returning.posters);pendingPosters.clear();pendingPosters.addAll(posterBindings.keySet());
        liveClock.clear();liveClock.addAll(returning.clocks);catalogGrid.clear();catalogGrid.addAll(returning.grid);catalogColumns=returning.catalogColumns;
        settingsRows=returning.settingsRows;settingsWidth=returning.settingsWidth;cityList=returning.cityList;cityStatus=returning.cityStatus;detailHeading=returning.detailHeading;detailDescription=returning.detailDescription;homeFavoritesTop=returning.homeFavoritesTop;
        nextWeatherHour=returning.nextWeatherHour;lastRenderedDate=returning.date;lastRenderedWeatherStale=returning.weatherStale;lastRenderedWeatherAvailable=returning.weatherAvailable;renderedPrefsSignature=returning.prefsSignature;renderedDataSignature=returning.dataSignature;renderedWatchPermissionState=returning.permissionState;renderedScreen=screen;
        retainedPage=outgoing;renderDirty=pendingData||!returning.dataSignature.equals(pageDataSignature());
        outgoing.root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);outgoing.root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);outgoing.root.clearFocus();
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        Runnable present=()->{
            if(destroyed||!resumed||generation!=renderGeneration)return;
            applyScrollMotionPolicy(root,allowsScrollMotion());
            String time=DateFormat.getTimeInstance(DateFormat.SHORT,isRussian()?new Locale("ru"):Locale.UK).format(new Date());
            for(TextView clock:liveClock)clock.setText(time);
            rowsSheet.present(pageRowsSpec,!prefs.reducedMotion);
            boolean returningAbove=renderHost.indexOfChild(root)>renderHost.indexOfChild(outgoing.root);
            if(screen==Screen.HOME)cacheHomeForTransition(root);else if(screen!=Screen.GALLERY)uiMotion.transitionSpatial(board,entering);
            if(outgoing.screen==Screen.HOME)cacheHomeForTransition(outgoing.root);else if(outgoing.screen!=Screen.GALLERY)uiMotion.transitionSpatial(outgoing.board,exitTransition(outgoing.screen,entering));
            if(returningAbove){uiMotion.crossfade(root,false);uiMotion.crossfade(root,true,()->{cleanupOutgoing(outgoing.root);releaseHomeLayer(returning.root);});}else{uiMotion.crossfade(root,true,()->releaseHomeLayer(returning.root));uiMotion.crossfade(outgoing.root,false,()->cleanupOutgoing(outgoing.root));}
            root.setVisibility(View.VISIBLE);
            String wanted=requestedFocus.isEmpty()?returning.focus:requestedFocus;View desired=targets.get(wanted);if(desired==null)desired=targets.get(defaultFocusKey());if(desired!=null){desired.requestFocus();if(detached)snapFocus(desired);}
            // Restored bindings were queued once above. Re-adding a target after
            // starting its request would hide that in-flight work from far-offscreen cancellation.
            loadVisibleIcons();
            ensureBackdrop(generation);
            if(screen==Screen.CITY&&citySearchPending)scheduleCitySearch();
            if(renderDirty)scheduleDataRender();
        };
        if(detached){
            // Detach resets UiMotion's base alpha. Hide before reattaching and
            // start presentation only after a new native layout has completed.
            root.setVisibility(View.INVISIBLE);
            root.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){@Override public void onLayoutChange(View view,int l,int t,int r,int b,int ol,int ot,int or,int ob){view.removeOnLayoutChangeListener(this);view.post(present);}});
            root.forceLayout();renderHost.addView(root,0,new FrameLayout.LayoutParams(-1,-1));root.requestLayout();
        }else present.run();
    }
    private static void afterLayout(View view,Runnable action){if(view.isLaidOut()&&view.getWidth()>0&&view.getHeight()>0){view.post(action);return;}view.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){@Override public void onLayoutChange(View target,int l,int t,int r,int b,int ol,int ot,int or,int ob){if(r>l&&b>t){target.removeOnLayoutChangeListener(this);target.post(action);}}});}
    private boolean focusDataReady(String key){
        if(key.startsWith("remote-target:"))return catalogLoaded;
        if(key.startsWith("favorite:")||key.startsWith("app-"))return catalogLoaded;
        if(key.startsWith("watch-program:"))return recommendations!=null&&!recommendations.activeLoading&&!recommendations.discoveryLoading;
        if(key.startsWith("watch-"))return watchLoaded;
        return true;
    }

    private int wallpaperResource(int index){return WallpaperCatalog.resource(this,index,renderedNight);}
    /** One live backdrop belongs to the window; page transitions never fade or decode it again. */
    private void ensureBackdrop(int generation){
        String poster="";
        if(prefs.background==WallpaperCatalog.CONTENT_AMBIENT){List<HomeCard> entries=visibleHomeCards();if(!entries.isEmpty())poster=entries.get(0).posterUri;}
        String signature=prefs.background+":"+renderedNight+":"+palette.light+":"+prefs.wallpaperDim+":"+(prefs.background==WallpaperCatalog.CONTENT_AMBIENT&&screen==Screen.DRAWER)+":"+poster;
        if(!signature.equals(backdropSignature)){
            if(wallpaperView!=null)imageLoader.forget(wallpaperView);
            if(ambientPosterView!=null)imageLoader.forget(ambientPosterView);
            glassScene.removeAllViews();wallpaperView=null;ambientPosterView=null;ambientPosterUri=poster;
            if(prefs.background==WallpaperCatalog.CONTENT_AMBIENT){
                ContentBackdrop background=new ContentBackdrop(this,!renderedNight,screen==Screen.DRAWER);
                glassScene.addView(background,new FrameLayout.LayoutParams(-1,-1));ambientPosterView=background.posterView();
            }else{
                SceneBackground background=new SceneBackground(this);background.setScene(WallpaperCatalog.isLight(prefs.background,renderedNight)?1:2,prefs.background);
                glassScene.addView(background,new FrameLayout.LayoutParams(-1,-1));
                if(!WallpaperCatalog.isVector(WallpaperCatalog.effectiveId(prefs.background,renderedNight)))addWallpaper(glassScene,background,generation);
            }
            if(prefs.wallpaperDim>0){View dim=new View(this);dim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);dim.setBackgroundColor(Math.round(prefs.wallpaperDim/100f*255)<<24);glassScene.addView(dim,new FrameLayout.LayoutParams(-1,-1));}
            backdropSignature=signature;
        }
        if(wallpaperView!=null&&wallpaperView.getDrawable()==null)requestWallpaper(wallpaperView,generation);
        if(ambientPosterView!=null&&ambientPosterView.getDrawable()==null&&!ambientPosterUri.isEmpty())
            imageLoader.poster(ambientPosterView,ambientPosterUri,960,540,generation);
    }
    private void addWallpaper(FrameLayout parent,SceneBackground fallback,int generation){
        ImageView photo=new ImageView(this);photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setTag(fallback);
        photo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(photo,new FrameLayout.LayoutParams(-1,-1));
        wallpaperView=photo;requestWallpaper(photo,generation);
    }
    private void requestWallpaper(ImageView photo,int generation){
        int width=Math.min(1920,getResources().getDisplayMetrics().widthPixels),height=Math.min(1080,getResources().getDisplayMetrics().heightPixels);
        imageLoader.wallpaper(photo,wallpaperResource(prefs.background),width,height,generation);
    }
    private void home() {
        if(largeText()){accessibleHome();return;}
        homeBindings=new HomeBindings();
        FrameLayout outer=board;
        TvScrollView scroll=new TvScrollView(this);homeScroll=scroll;scroll.setTag("home-scroll");
        clipScroll(scroll);scroll.setPadding(0,0,0,0);scroll.setPageHeight(HEIGHT);scroll.setFillViewport(false);
        place(outer,scroll,0,0,WIDTH,HEIGHT);
        FrameLayout content=new FrameLayout(this);content.setClipChildren(false);content.setClipToPadding(false);
        homeBindings.content=content;
        content.setMinimumHeight(HEIGHT*2);scroll.addView(content,new ScrollView.LayoutParams(WIDTH,HEIGHT*2));board=content;
        content.setTag("home-unified");
        homeComposition();
        homeShortcuts();
        recordHomeData();
        scroll.setOnScrollChangeListener((view,x,y,oldX,oldY)->{if(homeScroll==scroll)homeScrollOffset=y;});
        board=outer;
    }
    private void homeComposition(){
        List<AppEntry> favorites=favoriteApps();
        int headerOffset=posterCards()?0:24;
        clock(54,28+headerOffset,234,98,72);
        panel(board,298,55+headerOffset,1,56,0,palette.light?0x30000000:0x30ffffff);
        Locale dateLocale=isRussian()?new Locale("ru"):Locale.ENGLISH;
        TextView weekday=text(board,new SimpleDateFormat("EEEE",dateLocale).format(new Date()).toUpperCase(dateLocale),322,48+headerOffset,460,34,21,palette.ambientText,true);weekday.setSingleLine(true);AmbientTextShadow.apply(weekday,headerTextShadowEnabled());
        TextView date=text(board,new SimpleDateFormat(isRussian()?"d MMMM":"MMMM d",dateLocale).format(new Date()).toUpperCase(dateLocale),322,86+headerOffset,460,28,16,palette.ambientMuted,false);date.setSingleLine(true);AmbientTextShadow.apply(date,headerTextShadowEnabled());
        CompactWeatherView top=new CompactWeatherView(this);top.setTag("weather-status");
        place(board,top,824,40+headerOffset,CompactWeatherView.DESIGN_WIDTH,112);homeBindings.topWeather=top;
        bindTopWeather();
        if(prefs.recommendationsEnabled){FrameLayout tabs=new FrameLayout(this);tabs.setClipChildren(false);tabs.setClipToPadding(false);place(board,tabs,38,posterCards()?158:214,1192,52);homeBindings.tabsHost=tabs;buildHomeTabs(tabs);}
        else if(prefs.watchNextVisible)text(board,tr("Продолжить просмотр","Continue watching"),60,posterCards()?170:226,950,40,26,palette.ambientText,true).setSingleLine(true);
        if(posterCards())watchShelf(20,200,1240,160,280,24,true);
        else watchShelf(20,274,1240,576,196,24,false);
        panel(board,52,532,1176,132,28,palette.surface).setTag("home-dock");
        for(int i=0;i<favorites.size();i++){int[] position=favoritePosition(i);appTile(favorites.get(i),i,position[0],position[1],126,96,false);}
        if(favorites.isEmpty())action("favorite-add",tr(catalogLoaded?"Выбрать избранные приложения":"Загружаем приложения…",catalogLoaded?"Choose favorite applications":"Loading applications…"),"apps",68,556,1144,80,()->push(Screen.FAVORITES));
        if(movingId!=null){
            panel(board,244,12,792,48,16,palette.focus);
            text(board,tr("Перемещение: стрелки · OK сохранить · Назад отменить","Move: arrows · OK save · Back cancel"),258,18,768,34,20,palette.focusText,true);
        }
        TextView hint=text(board,tr("↓  Настройки и погода","↓  Settings and weather"),440,682,400,24,14,palette.ambientMuted,false);hint.setGravity(Gravity.CENTER);
    }
    private void bindTopWeather(){
        if(homeBindings==null||homeBindings.topWeather==null)return;
        CompactWeatherView top=homeBindings.topWeather;
        top.setVisibility(prefs.weatherEnabled?View.VISIBLE:View.GONE);
        top.setTextShadowEnabled(headerTextShadowEnabled());
        int height=top.bind(weather,prefs.weatherEnabled,prefs.cityName,isRussian(),palette.ambientText,palette.ambientMuted,palette.text,palette.muted,textPixels(20)/20f);
        ViewGroup.LayoutParams size=top.getLayoutParams();size.height=height;top.setLayoutParams(size);
        for(View surface:top.glassSurfaces())if(surface.getBackground()==null){surface.setBackground(glassBackdrop.drawable(surface,glassScene,12,palette.light,false));clipRounded(surface,12);}
    }

    private void accessibleHome(){
        homeBindings=new HomeBindings();
        FrameLayout outer=board;ScrollView scroll=new TvScrollView(this);clipScroll(scroll);scroll.setPadding(20,20,20,20);place(outer,scroll,16,8,1248,704);FrameLayout content=new FrameLayout(this);content.setClipChildren(false);content.setClipToPadding(false);scroll.addView(content,new ScrollView.LayoutParams(1208,1600));board=content;
        homeBindings.content=content;
        int clockHeight=textHeight(64,1)+8;clock(32,12,1000,clockHeight,64);
        int dateTop=12+clockHeight+12,dateHeight=Math.max(68,textHeight(26,1)+8);TextView date=text(board,formatDate(),32,dateTop,1120,dateHeight,26,palette.ambientMuted,false);date.setSingleLine(true);AmbientTextShadow.apply(date,headerTextShadowEnabled());
        int cursor=dateTop+dateHeight+16;
        if(prefs.weatherEnabled&&movingId==null)cursor+=compactWeather(board,32,cursor,1120,true)+28;
        if(movingId!=null){int noteHeight=textHeight(22,2)+12;text(board,tr("Перемещение: стрелки · OK сохранить · Назад отменить","Move: arrows · OK save · Back cancel"),32,cursor,1118,noteHeight,22,palette.ambientText,true);cursor+=noteHeight+20;}
        homeFavoritesTop=Math.max(382,cursor);List<AppEntry> favorites=favoriteApps();for(int i=0;i<favorites.size();i++)appTile(favorites.get(i),i,32+(i%4)*286,homeFavoritesTop+(i/4)*184,260,160,true);
        if(favorites.isEmpty())action("favorite-add",tr("Выбрать избранные приложения","Choose favorite applications"),"apps",32,homeFavoritesTop,1118,110,()->push(Screen.FAVORITES));
        int appsTop=homeFavoritesTop+386;action("all-apps",tr("Все приложения","All applications"),"apps",32,appsTop,1118,100,()->push(Screen.ALL_APPS));
        int end=appsTop+124;
        FrameLayout tabs=new FrameLayout(this);tabs.setClipChildren(false);place(board,tabs,8,end,1192,Math.max(62,textHeight(21,1)+20));homeBindings.tabsHost=tabs;buildHomeTabs(tabs);
        int cardsTop=end+Math.max(74,textHeight(21,1)+32);watchShelf(0,cardsTop,1182,900,Math.max(260,textHeight(19,4)+100),36,false);end=cardsTop+Math.max(304,textHeight(19,4)+144);
        ViewGroup.LayoutParams contentSize=content.getLayoutParams();contentSize.height=end+40;content.setLayoutParams(contentSize);
        recordHomeData();
        board=outer;
    }

    private void sectionTitle(String title,int x,int y) { text(board,title,x,y,900,Math.max(42,Math.round(textPixels(27)*1.3f)),27,palette.ambientText,true); }
    private void homeHint(int y) { text(board,tr("↓  Настройки и погода","↓  Settings and weather"),64,y,700,Math.max(30,Math.round(textPixels(18)*1.3f)),18,palette.ambientMuted,false); }
    private List<AppEntry> favoriteApps() {
        List<String> ids = reorderIds == null ? prefs.favoriteIds : reorderIds;
        List<AppEntry> result = new ArrayList<>();
        for(String id:ids) { AppEntry app=appsById.get(id); if(app!=null&&!prefs.hiddenPackages.contains(app.packageName)) result.add(app); }
        return result;
    }

    private void appTile(AppEntry app,int index,int x,int y,int width,int height,boolean label) {
        String key="favorite:"+app.id;
        FrameLayout tile = focusBox(key,x,y,width,height,false,()->launchApp(app));
        tile.setTag("favorite-"+index);
        tile.setContentDescription(displayName(app)+tr(", избранное, удерживайте OK для перемещения",", favorite, hold OK to reorder"));
        int imageHeight=height;
        int inset=8,topInset=7;
        TextView fallback=text(tile,app.label.isEmpty()?"?":new String(Character.toChars(app.label.codePointAt(0))),inset,topInset,width-inset*2,imageHeight-topInset*2,Math.min(38,imageHeight*.5f),palette.muted,true);fallback.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        clipRounded(icon,12);
        icon.setTag(fallback);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        place(tile,icon,inset,topInset,width-inset*2,imageHeight-topInset*2); loadIcon(icon,app);
        tile.setOnLongClickListener(v->{ uiSounds.select();beginReorder(app.id); return true; });
    }

    private void launchApp(AppEntry app) {
        if(movingId!=null) { saveReorder(); return; }
        lastHomeFocus=focusKey();
        if(!AppCatalog.launch(this,app.id)) toast(tr("Приложение недоступно","Application unavailable"));
    }

    private List<WatchNextRepository.Entry> visibleContinuation(){
        List<WatchNextRepository.Entry> items=new ArrayList<>();
        if(prefs.watchNextVisible)for(WatchNextRepository.Entry entry:continuation){
            if(prefs.hiddenPackages.contains(entry.packageName))continue;
            items.add(entry);
        }return items;
    }
    private void syncRecommendations(){
        if(recommendationsSession==null)return;
        homeSource=sourcePackage(homeSource);
        if(!prefs.recommendationsEnabled||!prefs.recommendationPackages.contains(homeSource)||prefs.hiddenPackages.contains(homeSource))homeSource="";
        if(homeSource.isEmpty()&&!prefs.watchNextVisible&&prefs.recommendationsEnabled){for(String key:homeSources())if(!key.isEmpty()){homeSource=key;break;}}
        sourceRows.keySet().retainAll(prefs.recommendationPackages);
        List<String> selected=new ArrayList<>();for(String key:homeSources())if(!key.isEmpty())selected.add(key);
        recommendationsSession.configure(prefs.recommendationsEnabled,selected,screen==Screen.HOME&&!homeSource.isEmpty()?homeSource:null);
    }
    private static String sourcePackage(String key){if(key==null)return "";String[] parts=key.split("\\|",-1);return parts.length==4?parts[1]:key;}
    private static String restoredSourceFocus(String key){return key==null?"":key.startsWith("source:")?"source:"+sourcePackage(key.substring(7)):key;}
    private List<RecommendationSources.Source> availableRecommendationSources(){
        return recommendations==null||recommendations.discovery==null?Collections.emptyList():RecommendationSources.group(recommendations.discovery);
    }
    private RecommendationSources.Source selectedSource(String key){
        for(RecommendationSources.Source source:availableRecommendationSources())if(source.packageName.equals(key))return source;
        return null;
    }
    private List<String> homeSources(){
        List<String> result=new ArrayList<>();if(prefs.watchNextVisible)result.add("");
        if(prefs.recommendationsEnabled){
            for(String pkg:prefs.recommendationSourceOrder)if(prefs.recommendationPackages.contains(pkg)&&!prefs.hiddenPackages.contains(pkg)&&!result.contains(pkg))result.add(pkg);
            for(String pkg:prefs.recommendationPackages)if(!result.contains(pkg)&&!prefs.hiddenPackages.contains(pkg))result.add(pkg);
        }return result;
    }
    private String sourceLabel(String key){if(key.isEmpty())return tr("Продолжить","Continue");RecommendationSources.Source source=selectedSource(key);return source==null?providerName(key):source.appLabel;}
    private List<HomeCard> visibleHomeCards(){
        List<HomeCard> result=new ArrayList<>();
        if(homeSource.isEmpty()){for(WatchNextRepository.Entry entry:visibleContinuation())result.add(new HomeCard(entry));return result;}
        if(!prefs.recommendationsEnabled||recommendations==null||selectedSource(homeSource)==null)return result;
        RecommendationsSession.AppPrograms programs=recommendations.programsByPackage.get(homeSource);
        if(programs!=null)for(ChannelRepository.Program entry:programs.items){ChannelRepository.Channel channel=programs.channelFor(entry);if(channel!=null)result.add(new HomeCard(channel,entry));}
        return result;
    }
    private String recommendationsSignature(){return recommendations==null?"none":Long.toString(recommendations.revision);}
    private String homeCardsSignature(){
        if(homeSource.isEmpty())return "continue:"+prefs.watchNextVisible+":"+watchStatus+watchSignature(continuation);
        StringBuilder value=new StringBuilder(homeSource).append(':').append(watchPermissionState());
        if(recommendations==null)return value.append(":waiting").toString();
        value.append(recommendations.activeLoading).append(recommendations.activeFailure).append(recommendations.discoveryLoading).append(recommendations.discoveryFailure).append(selectedSource(homeSource)!=null);
        RecommendationsSession.AppPrograms programs=recommendations.programsByPackage.get(homeSource);
        if(programs!=null){value.append(programs.status).append(programs.completeness);for(ChannelRepository.Program item:programs.items){
            ChannelRepository.Channel channel=programs.channelFor(item);if(channel==null)continue;
            value.append(channel.key).append(':').append(channel.versionCode).append('\0').append(item.key).append('\0').append(item.title).append('\0').append(item.genre).append('\0').append(item.posterUri).append('\0').append(item.intentUri).append('\n');}}
        return value.toString();
    }
    private String homeTabsSignature(){StringBuilder value=new StringBuilder(homeSource);for(String key:homeSources())value.append('|').append(key).append(':').append(sourceLabel(key));return value.toString();}
    private void buildHomeTabs(FrameLayout host){
        TvHorizontalScrollView scroll=new TvHorizontalScrollView(this);scroll.setTag("source-tabs");clipScrollViewport(scroll);scroll.setPadding(8,4,8,4);place(host,scroll,0,0,1070,52);
        FrameLayout content=new FrameLayout(this);content.setClipChildren(false);scroll.addView(content,new android.widget.HorizontalScrollView.LayoutParams(1,44));
        int captionSize=largeText()?21:26;
        FrameLayout previous=board;board=content;int x=0;Paint measure=new Paint();measure.setTypeface(typography.semibold);measure.setTextSize(textPixels(captionSize));
        for(String source:homeSources()){
            String label=sourceLabel(source);int width=Math.min(220,Math.max(112,(int)Math.ceil(measure.measureText(label))+32));
            FrameLayout tab=focusBox("source:"+source,x,0,width,44,false,()->selectHomeSource(source,false));
            tab.setBackground(null);tab.setForeground(null);tab.setSelected(source.equals(homeSource));tab.setContentDescription(label);
            TextView caption=text(tab,label,14,0,width-28,40,captionSize,source.equals(homeSource)?palette.ambientText:palette.ambientInactive,source.equals(homeSource));caption.setSingleLine(true);
            View underline=panel(tab,14,41,width-28,2,1,palette.ambientText);underline.setTag("source-focus-underline");underline.setVisibility(tab.hasFocus()?View.VISIBLE:View.INVISIBLE);
            View.OnFocusChangeListener previousFocus=tab.getOnFocusChangeListener();
            tab.setOnFocusChangeListener((view,focused)->{if(previousFocus!=null)previousFocus.onFocusChange(view,focused);underline.setVisibility(focused?View.VISIBLE:View.INVISIBLE);});
            x+=width+12;
        }
        ViewGroup.LayoutParams size=content.getLayoutParams();size.width=Math.max(1,x);content.setLayoutParams(size);board=host;
        actionIcon("sources-open","sliders",tr("Источники рекомендаций","Recommendation sources"),1110,4,64,44,()->push(Screen.SOURCES));board=previous;
    }
    private void selectHomeSource(String key,boolean enterCards){
        if(!homeSources().contains(key))return;
        pendingSourceEntry=enterCards?key:null;
        captureWatchRow();boolean changed=!homeSource.equals(key);homeSource=key;syncRecommendations();
        if(changed){if(!refreshHomeDataInPlace())render();}
        FrameLayout page=root;int contentGeneration=homeContentGeneration,inputGeneration=focusInputGeneration;
        afterLayout(board,()->{
            if(root!=page||screen!=Screen.HOME||!homeSource.equals(key)||contentGeneration!=homeContentGeneration||inputGeneration!=focusInputGeneration)return;
            if(enterCards){completePendingSourceEntry();return;}
            View target=targets.get("source:"+key);if(target==null)target=targets.get(defaultFavoriteFocus());if(target!=null)target.requestFocus();
        });
    }
    /** A Down press survives loading, but never a later user input or leaving Home. */
    private void completePendingSourceEntry(){
        if(pendingSourceEntry==null||!pendingSourceEntry.equals(homeSource)||screen!=Screen.HOME||!resumed)return;
        View card=selectedWatchTile();
        if(card!=null){pendingSourceEntry=null;card.requestFocus();return;}
        boolean waiting=!homeSource.isEmpty()&&(recommendations==null||!homeSource.equals(recommendations.activePackage)||recommendations.activeLoading||recommendations.discoveryLoading);
        View target=waiting?targets.get("source:"+homeSource):shelfFocusTarget();
        if(!waiting){pendingSourceEntry=null;if(target==null)target=targets.get(defaultFavoriteFocus());}
        if(target!=null)target.requestFocus();
    }
    private void wireSourceFocus(){
        if(screen!=Screen.HOME||homeBindings==null||homeBindings.tabsHost==null)return;
        List<View> tabs=new ArrayList<>();for(String source:homeSources()){View tab=targets.get("source:"+source);if(tab!=null)tabs.add(tab);}
        View chooser=targets.get("sources-open");if(chooser!=null)tabs.add(chooser);
        View selected=shelfFocusTarget(),favorite=targets.get(defaultFavoriteFocus()),active=targets.get("source:"+homeSource);if(active==null)active=chooser;
        View upper=largeText()?targets.get("all-apps"):null;
        for(int i=0;i<tabs.size();i++){View tab=tabs.get(i);tab.setNextFocusLeftId(tabs.get(Math.max(0,i-1)).getId());tab.setNextFocusRightId(tabs.get(Math.min(tabs.size()-1,i+1)).getId());tab.setNextFocusUpId(upper!=null?upper.getId():tab.getId());tab.setNextFocusDownId(selected!=null?selected.getId():!largeText()&&favorite!=null?favorite.getId():tab.getId());}
        if(active!=null){
            for(View card:watchTiles)card.setNextFocusUpId(active.getId());
            if(!largeText()){for(Map.Entry<String,View> item:targets.entrySet())if(item.getKey().startsWith("favorite:")||item.getKey().equals("favorite-add"))item.getValue().setNextFocusUpId(selected!=null?selected.getId():active.getId());}
            if(upper!=null)upper.setNextFocusDownId(active.getId());
        }
        View refresh=targets.get("recommendations-refresh"),sources=targets.get("recommendations-sources");
        if(refresh!=null&&sources!=null){refresh.setNextFocusLeftId(refresh.getId());refresh.setNextFocusRightId(sources.getId());sources.setNextFocusLeftId(refresh.getId());sources.setNextFocusRightId(sources.getId());for(View action:new View[]{refresh,sources}){if(active!=null)action.setNextFocusUpId(active.getId());action.setNextFocusDownId(!largeText()&&favorite!=null?favorite.getId():action.getId());}}
    }
    private View shelfFocusTarget(){View card=selectedWatchTile();return card!=null?card:targets.get("recommendations-refresh");}
    private void emptyRecommendations(int x,int y,int width,int height){
        String message;
        if(recommendations==null||recommendations.activeLoading||recommendations.discoveryLoading)message=tr("Загружаем рекомендации…","Loading recommendations…");
        else if(recommendations.activeFailure!=RecommendationsSession.Failure.NONE||recommendations.discoveryFailure!=RecommendationsSession.Failure.NONE)message=tr("Не удалось обновить рекомендации. Попробуй ещё раз.","Could not refresh recommendations. Try again.");
        else if(watchPermissionState()!=2)message=tr("Для рекомендаций нужен доступ к TV-каналам. Открой настройки источников.","Recommendations need TV channel access. Open source settings.");
        else if(selectedSource(homeSource)==null)message=tr("Рекомендации приложения сейчас недоступны. Обнови список или выбери другое приложение.","Recommendations from this app are unavailable. Refresh or choose another app.");
        else{RecommendationsSession.AppPrograms programs=recommendations.programsByPackage.get(homeSource);message=programs!=null&&programs.status!=ChannelRepository.Status.EMPTY&&programs.status!=ChannelRepository.Status.READY?tr("Не удалось прочитать рекомендации этого приложения","Could not read this app's recommendations"):tr("Приложение пока не опубликовало доступных карточек","The app has not published available cards yet");}
        text(board,message,x+12,y+24,width-24,92,23,palette.ambientMuted,false).setMaxLines(3);
        action("recommendations-refresh",tr("Обновить","Refresh"),"tv",x+12,y+140,250,64,()->{recommendationsSession.refreshDiscovery();recommendationsSession.refreshActive();});
        action("recommendations-sources",tr("Выбрать источники","Choose sources"),"apps",x+282,y+140,330,64,()->push(Screen.SOURCES));
    }
    private void recommendationSources(){
        beginRows(tr("Рекомендации","Recommendations"),tr("Включи нужные приложения. Их доступные рекомендации появятся на главном экране и будут обновляться вместе.","Enable the apps you want. Their available recommendations appear together on Home and update automatically."));
        row("sources-enabled","tv",tr("Рекомендации на главном","Recommendations on Home"),onOff(prefs.recommendationsEnabled),0,()->{preferences.setRecommendationsEnabled(!prefs.recommendationsEnabled);prefs=preferences.read();syncRecommendations();renderPreservingFocus();});
        if(!prefs.recommendationsEnabled){rowNote(tr("Включи рекомендации, чтобы выбрать приложения. Избранное и личное продолжение просмотра сохранятся.","Enable recommendations to choose apps. Favorites and personal continue watching are kept."));return;}
        row("sources-refresh","tv",tr("Обновить список","Refresh list"),recommendations!=null&&recommendations.discoveryLoading?tr("Поиск…","Searching…"):"",1,()->recommendationsSession.refreshDiscovery());
        int index=2;
        if(watchPermissionState()!=2)row("sources-permission","settings",tr("Доступ к TV-каналам","TV channel access"),watchPermissionLabel(),index++,this::requestWatchPermission);
        ChannelRepository.Discovery discovery=recommendations==null?null:recommendations.discovery;
        if(discovery==null)rowNote(recommendations!=null&&recommendations.discoveryFailure!=RecommendationsSession.Failure.NONE?tr("Не удалось прочитать рекомендации. Обнови список.","Could not read recommendations. Refresh the list."):tr("Ищем приложения с рекомендациями…","Finding apps with recommendations…"));
        else{
            if(discovery.selectableChannels.isEmpty())rowNote(discovery.status==ChannelRepository.Status.EMPTY?tr("Пока нет приложений с доступными рекомендациями. Открой приложение и вернись сюда.","No apps with available recommendations yet. Open an app, then return here."):tr("На этом устройстве рекомендации сейчас недоступны","Recommendations are currently unavailable on this device"));
            if(discovery.completeness!=ChannelRepository.Completeness.FULL)rowNote(tr("Показан доступный фрагмент списка","Showing the available part of the list"));
        }
        java.util.LinkedHashSet<String> packages=new java.util.LinkedHashSet<>();
        for(RecommendationSources.Source source:availableRecommendationSources())if(!prefs.hiddenPackages.contains(source.packageName))packages.add(source.packageName);
        for(String pkg:prefs.recommendationPackages)if(!prefs.hiddenPackages.contains(pkg))packages.add(pkg);
        for(String pkg:packages){
            boolean selected=prefs.recommendationPackages.contains(pkg);
            String value=onOff(selected)+(selectedSource(pkg)==null?tr(" · Пока недоступно"," · Currently unavailable"):"");
            row("app:"+pkg,"tv",sourceLabel(pkg),value,index++,()->toggleRecommendationApp(pkg)).setSelected(selected);
        }
        rowNote(tr("До 6 приложений. Новые рекомендации включённых приложений добавляются автоматически. Выбор действует только в softlumi.","Up to 6 apps. New recommendations from enabled apps are included automatically. The selection applies only to softlumi."));
    }
    private void toggleRecommendationApp(String packageName){
        List<String> selected=new ArrayList<>(prefs.recommendationPackages),order=new ArrayList<>(prefs.recommendationSourceOrder);
        if(selected.contains(packageName))selected.remove(packageName);
        else{if(selected.size()>=LauncherPrefs.MAX_RECOMMENDATION_PACKAGES){toast(tr("Можно выбрать до 6 приложений","Choose up to 6 apps"));return;}selected.add(packageName);if(!order.contains(packageName))order.add(packageName);}
        preferences.setRecommendations(true,selected,order);prefs=preferences.read();syncRecommendations();renderPreservingFocus();
    }
    private void watchShelf(int x,int y,int viewportWidth,int cardWidth,int cardHeight,int gap,boolean poster){
        if(homeBindings!=null&&screen==Screen.HOME){
            HomeBindings binding=homeBindings;FrameLayout parent=board;
            FrameLayout host=new FrameLayout(this);host.setClipChildren(false);host.setClipToPadding(false);
            int py=Math.max(20,(int)Math.ceil(cardHeight*.03f)+10);place(parent,host,x,y,viewportWidth,cardHeight+py*2);
            binding.watchHost=host;binding.viewportWidth=viewportWidth;binding.cardWidth=cardWidth;binding.cardHeight=cardHeight;binding.gap=gap;binding.poster=poster;
            board=host;buildWatchShelf(0,0,viewportWidth,cardWidth,cardHeight,gap,poster);board=parent;
        }else buildWatchShelf(x,y,viewportWidth,cardWidth,cardHeight,gap,poster);
    }
    private void buildWatchShelf(int x,int y,int viewportWidth,int cardWidth,int cardHeight,int gap,boolean poster){
        List<HomeCard> entries=visibleHomeCards();
        if(entries.isEmpty()){if(homeSource.isEmpty()){if(prefs.watchNextVisible)emptyWatch(x+24,y+12,viewportWidth-48,cardHeight);}else emptyRecommendations(x+24,y+12,viewportWidth-48,cardHeight);return;}
        WatchRowState state=homeSource.isEmpty()?watchRows[0]:sourceRows.computeIfAbsent(homeSource,key->new WatchRowState());activeWatchState=state;restoringWatchRow=true;
        int selectedIndex=Math.max(0,Math.min(state.index,entries.size()-1));
        for(int i=0;i<entries.size();i++)if(entries.get(i).id.equals(state.id)){selectedIndex=i;break;}
        state.index=selectedIndex;state.id=entries.get(selectedIndex).id;
        TvHorizontalScrollView scroll=new TvHorizontalScrollView(this);watchRail=scroll;scroll.setTag("watch-rail");scroll.setFillViewport(false);clipScrollViewport(scroll);
        int px=Math.max(32,(int)Math.ceil(cardWidth*.03f)+10),py=Math.max(20,(int)Math.ceil(cardHeight*.03f)+10);
        scroll.setPadding(px,py,px,py);place(board,scroll,x,y,viewportWidth,cardHeight+py*2);
        FrameLayout content=new FrameLayout(this);content.setClipChildren(false);content.setClipToPadding(false);
        boolean editorial=screen==Screen.HOME&&!largeText()&&poster;
        int total=0;for(int i=0;i<entries.size();i++)total+=cardWidth+(i==0?0:gap);
        scroll.addView(content,new android.widget.HorizontalScrollView.LayoutParams(total,cardHeight));
        FrameLayout outer=board;board=content;int left=0;
        for(int i=0;i<entries.size();i++){
            HomeCard entry=entries.get(i);int width=cardWidth;
            if(editorial)homeContentCard(entry,left,0,width,cardHeight);else watchCard(entry,left,0,width,cardHeight,poster);
            View tile=targets.get("watch-"+entry.id);watchTiles.add(tile);View.OnFocusChangeListener previous=tile.getOnFocusChangeListener();final int index=i;
            tile.setOnFocusChangeListener((view,focused)->{if(previous!=null)previous.onFocusChange(view,focused);if(focused&&watchRail==scroll&&!restoringWatchRow){state.id=entry.id;state.index=index;captureWatchRow();wireFocus();}});
            left+=width+gap;
        }board=outer;
        if(state.id.isEmpty()||!targets.containsKey("watch-"+state.id)){state.index=Math.min(state.index,entries.size()-1);state.id=entries.get(state.index).id;}
        // The window's OnScrollChangedListener performs one visibility pass after scrolling.
        scroll.setOnScrollChangeListener((view,sx,sy,ox,oy)->{if(watchRail==scroll)captureWatchRow();});
    }
    private View selectedWatchTile(){
        if(activeWatchState==null)return watchTiles.isEmpty()?null:watchTiles.get(0);
        View selected=targets.get("watch-"+activeWatchState.id);return selected!=null?selected:watchTiles.isEmpty()?null:watchTiles.get(Math.min(activeWatchState.index,watchTiles.size()-1));
    }
    private void captureWatchRow(){
        if(restoringWatchRow||watchRail==null||activeWatchState==null||watchRail.getChildCount()==0)return;
        activeWatchState.scrollX=watchRail.getScrollX();View selected=selectedWatchTile();
        if(selected!=null&&selected.getWidth()>0)activeWatchState.anchor=watchRail.getChildAt(0).getLeft()+selected.getLeft()-watchRail.getScrollX();
    }
    private void restoreWatchRow(){
        if(watchRail==null||activeWatchState==null)return;View selected=selectedWatchTile();int offset=activeWatchState.scrollX;
        if(selected!=null)offset=watchRail.getChildAt(0).getLeft()+selected.getLeft()-activeWatchState.anchor;
        watchRail.scrollTo(Math.max(0,offset),0);restoringWatchRow=false;captureWatchRow();
    }
    private void emptyWatch(int x,int y,int width,int height) {
        String message;
        switch(watchStatus){
            case DENIED:message=watchPermissionState()==1?
                    tr("Разрешите доступ к продолжению просмотра: Настройки → Дополнительно.","Allow continue-watching access in Settings → Advanced."):
                    tr("Устройство не предоставляет доступ к продолжению просмотра","This device does not share items to continue watching");break;
            case UNAVAILABLE:message=tr("Продолжение просмотра на этом устройстве недоступно","Continue watching is unavailable on this device");break;
            case ERROR:message=tr("Не удалось загрузить продолжение просмотра","Unable to load items to continue watching");break;
            default:message=tr("Пока нет доступного продолжения просмотра","No available items to continue watching");
        }
        text(board,message,x+12,y+28,width-24,Math.min(height-28,textHeight(23,largeText()?3:2)),23,palette.ambientMuted,false).setMaxLines(largeText()?3:2);
    }
    private void watchCard(WatchNextRepository.Entry entry,int x,int y,int width,int height,boolean poster) {
        watchCard(new HomeCard(entry),x,y,width,height,poster);
    }
    private void watchCard(HomeCard entry,int x,int y,int width,int height,boolean poster) {
        if(screen==Screen.HOME&&posterCards()&&!largeText()&&poster){homeContentCard(entry,x,y,width,height);return;}
        boolean spacious=screen==Screen.HOME&&!largeText()&&!poster;
        FrameLayout card=focusBox("watch-"+entry.id,x,y,width,height,true,()->{
            if(!(entry.continuation!=null?WatchNextRepository.safeLaunch(this,entry.continuation):new ChannelRepository().launch(this,entry.channel,entry.program)))toast(tr("Не удалось открыть видео","Unable to open this video"));
        });
        card.setContentDescription(entry.title+(entry.continuation!=null?tr(", продолжить просмотр",", continue watching"):tr(", рекомендация",", recommendation"))+" · "+providerName(entry.packageName));
        ImageView art=new ImageView(this); art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(palette.surface);clipRounded(art,poster?CARD_RADIUS:14);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int artWidth=poster?width:Math.min(width/2,spacious?280:222);
        place(card,art,poster?0:8,poster?0:8,poster?width:artWidth-16,poster?height:height-16);
        SymbolView noArt=new SymbolView(this,"tv",palette.muted);noArt.setAlpha(.4f);noArt.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        place(card,noArt,Math.max(12,(artWidth-64)/2),Math.max(12,(height-(poster?150:64))/2),64,54);art.setTag(noArt);loadPoster(art,entry.posterUri,artWidth,height);
        if(poster) {
            View shade=new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xf0080808}));
            shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            place(card,shade,0,height/3,width,height-height/3);
        }
        int tx=poster?22:artWidth+18, ty=poster?height-99:spacious?66:50;
        providerBadge(card,entry.packageName,tx,poster?height-138:spacious?32:16,width-tx-18,spacious);
        int titleHeight=poster?56:height>130?58:50;
        TextView title=text(card,entry.title,tx,ty,width-tx-16,titleHeight,Math.min(19,height*.17f),poster?Color.WHITE:palette.text,true);title.setMaxLines(2);
        boolean reflow=largeText()&&!poster;
        if(reflow){titleHeight=title.getLineHeight()*2+4;FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)title.getLayoutParams();params.height=titleHeight;title.setLayoutParams(params);}
        if(!poster && height>130 && !entry.genre.isEmpty()){
            TextView genre=text(card,entry.genre,tx,ty+(reflow?titleHeight+6:60),width-tx-14,25,16,palette.muted,false);genre.setSingleLine(true);bindFocusedGenre(card,genre);
            if(reflow){FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)genre.getLayoutParams();params.height=genre.getLineHeight()+4;genre.setLayoutParams(params);}
        }
        int px=poster?20:tx, pw=width-px-22;
        if(entry.hasPlaybackProgress){panel(card,px,height-23,pw,4,2,poster?0x66ffffff:palette.light?0x30000000:0x60ffffff);
        if(entry.progressPercent>0)panel(card,px,height-23,Math.max(1,Math.round(pw*Math.max(0,Math.min(100,entry.progressPercent))/100f)),4,2,poster||!palette.light?Color.WHITE:0xff424242);}
    }

    /** Shared content shape: artwork above an external title/provider footer, never fake progress. */
    private void homeContentCard(WatchNextRepository.Entry entry,int x,int y,int width,int height){homeContentCard(new HomeCard(entry),x,y,width,height);}
    private void homeContentCard(HomeCard entry,int x,int y,int width,int height){
        FrameLayout card=focusBox("watch-"+entry.id,x,y,width,height,true,()->{
            boolean launched=entry.continuation!=null?WatchNextRepository.safeLaunch(this,entry.continuation):new ChannelRepository().launch(this,entry.channel,entry.program);
            if(!launched){toast(tr("Не удалось открыть видео","Unable to open this video"));if(entry.program!=null)recommendationsSession.refreshActive();}
        });
        card.setContentDescription(entry.title+(entry.continuation!=null?tr(", продолжить просмотр",", continue watching"):tr(", рекомендация",", recommendation"))+" · "+providerName(entry.packageName));
        final int artHeight=height-68;
        card.setBackground(null);card.setClipToOutline(false);
        card.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View target,Outline outline){outline.setRoundRect(0,0,target.getWidth(),artHeight,16);}});
        ((ArtworkSheenDrawable)card.getForeground()).setArtworkHeight(artHeight);
        ImageView art=new ImageView(this);art.setScaleType(ImageView.ScaleType.CENTER_CROP);art.setBackgroundColor(palette.surface);
        clipRounded(art,16);art.setTransitionName("watch-art");art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);place(card,art,0,0,width,artHeight);
        SymbolView missing=new SymbolView(this,"tv",palette.muted);missing.setAlpha(.4f);missing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        place(card,missing,(width-48)/2,(artHeight-48)/2,48,48);art.setTag(missing);loadPoster(art,entry.posterUri,width,artHeight);
        if(entry.hasPlaybackProgress){
            panel(card,12,artHeight-14,width-24,3,2,0x66ffffff).setTag("watch-progress-track");
            if(entry.progressPercent>0)panel(card,12,artHeight-14,Math.max(1,Math.round((width-24)*entry.progressPercent/100f)),3,2,Color.WHITE).setTag("watch-progress-fill");
        }
        FrameLayout footer=new FrameLayout(this);footer.setTag("watch-footer");place(card,footer,0,artHeight,width,height-artHeight);
        TextView title=text(footer,entry.title,6,9,width-50,27,18,palette.ambientText,true);title.setTag("watch-title");title.setSingleLine(true);
        if(!entry.genre.isEmpty()){TextView genre=text(footer,entry.genre,6,38,width-12,24,14,palette.ambientMuted,false);genre.setSingleLine(true);bindFocusedGenre(card,genre);}
        FrameLayout badge=new FrameLayout(this);badge.setTag("provider:"+entry.packageName);badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);place(footer,badge,width-37,10,31,27);
        ImageView logo=new ImageView(this);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);place(badge,logo,1,1,29,25);providerLogos.put(logo,entry.packageName);pendingLogos.add(logo);
    }
    private void bindFocusedGenre(View card,TextView genre){
        genre.setTag("watch-genre");genre.setVisibility(card.hasFocus()?View.VISIBLE:View.INVISIBLE);
        View.OnFocusChangeListener previous=card.getOnFocusChangeListener();
        card.setOnFocusChangeListener((view,focused)->{if(previous!=null)previous.onFocusChange(view,focused);genre.setVisibility(focused?View.VISIBLE:View.INVISIBLE);});
    }
    private String providerName(String packageName){
        for(AppEntry app:catalog)if(app.packageName.equals(packageName))return app.label;
        return packageName;
    }
    private void providerBadge(FrameLayout card,String packageName,int left,int top,int width,boolean iconOnly){
        FrameLayout badge=new FrameLayout(this);badge.setTag("provider:"+packageName);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        place(card,badge,left,top,iconOnly?29:width,28);
        ImageView logo=new ImageView(this);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);place(badge,logo,0,iconOnly?2:1,iconOnly?29:42,iconOnly?24:26);
        if(!iconOnly)text(badge,providerName(packageName),52,0,width-52,28,15,palette.muted,true).setSingleLine(true);
        providerLogos.put(logo,packageName);pendingLogos.add(logo);
    }
    private void homeShortcuts(){
        text(board,tr("↑  Главный экран","↑  Home"),60,HEIGHT+42,900,28,16,palette.ambientMuted,false);
        text(board,tr("Настройки","Settings"),60,HEIGHT+84,900,42,30,palette.ambientText,true);
        String[] keys={"apps","wifi","bluetooth","wallpapers","launcher","tv"};
        String[] symbols={"apps-outline","wifi-outline","bluetooth-outline","wallpaper-outline","sliders","tv-outline"};
        String[] labels={tr("Приложения","Applications"),"Wi-Fi","Bluetooth",tr("Обои","Wallpapers"),tr("Настройки SL","SL settings"),tr("Настройки ТВ","TV settings")};
        Runnable[] actions={()->push(Screen.ALL_APPS),()->systemSettings(Settings.ACTION_WIFI_SETTINGS),()->systemSettings(Settings.ACTION_BLUETOOTH_SETTINGS),()->push(Screen.GALLERY),()->push(Screen.SETTINGS),()->systemSettings(Settings.ACTION_SETTINGS)};
        for(int i=0;i<keys.length;i++){
            FrameLayout button=focusBox("shortcut-"+keys[i],52+i*198,HEIGHT+148,186,114,false,actions[i]);
            SymbolView symbol=new SymbolView(this,symbols[i],palette.text);place(button,symbol,77,18,32,32);
            TextView caption=text(button,labels[i],8,60,170,36,18,palette.text,false);caption.setGravity(Gravity.CENTER);caption.setSingleLine(true);
            button.setContentDescription(labels[i]);
        }
        FrameLayout weatherCard=new FrameLayout(this);weatherCard.setTag("home-forecast-card");
        weatherCard.setBackground(glassBackdrop.drawable(weatherCard,glassScene,26,palette.light,true));clipRounded(weatherCard,26);
        place(board,weatherCard,52,HEIGHT+298,1176,380);
        ThreeDayForecastView forecast=new ThreeDayForecastView(this);place(weatherCard,forecast,22,18,1132,1);
        homeBindings.forecastCard=weatherCard;homeBindings.forecast=forecast;
        updateHomeForecast();
    }

    private void recordHomeData(){
        if(homeBindings==null)return;
        homeBindings.catalog=catalogSignature(catalog);homeBindings.watch=watchSignature(continuation);
        homeBindings.status=watchStatus;homeBindings.weather=weatherSignature(weather);homeBindings.date=formatDate();homeBindings.cards=homeCardsSignature();homeBindings.tabs=homeTabsSignature();
    }
    private void updateHomeForecast(){
        if(homeBindings==null||homeBindings.forecast==null)return;
        long now=System.currentTimeMillis();nextWeatherHour=Long.MAX_VALUE;
        if(weatherAvailable()){
            for(WeatherRepository.Day day:weather.weather.forecastDays(now))if(day.endMillis>now)nextWeatherHour=Math.min(nextWeatherHour,day.endMillis);
            for(WeatherRepository.Hour hour:weather.weather.upcomingHours(now))nextWeatherHour=Math.min(nextWeatherHour,hour.timeMillis);
        }
        int height=homeBindings.forecast.bind(weather,prefs.weatherEnabled,prefs.cityName,isRussian(),palette.text,palette.muted,textPixels(20)/20f);
        ViewGroup.LayoutParams content=homeBindings.forecast.getLayoutParams();content.height=height;homeBindings.forecast.setLayoutParams(content);
        ViewGroup.LayoutParams card=homeBindings.forecastCard.getLayoutParams();card.height=height+36;homeBindings.forecastCard.setLayoutParams(card);
        bindTopWeather();
    }
    private boolean refreshHomeDataInPlace(){
        HomeBindings binding=homeBindings;
        if(restoringPageFocus||screen!=Screen.HOME||renderedScreen!=Screen.HOME||binding==null||root==null||root.getVisibility()!=View.VISIBLE
                ||!renderedPrefsSignature.equals(settingsSignature(prefs))
                ||renderedWatchPermissionState!=watchPermissionState()
                ||!binding.catalog.equals(catalogSignature(catalog))||!binding.date.equals(formatDate()))return false;
        boolean watchChanged=!binding.cards.equals(homeCardsSignature());
        boolean tabsChanged=!binding.tabs.equals(homeTabsSignature());
        if(watchChanged||tabsChanged)homeContentGeneration++;
        int contentGeneration=homeContentGeneration,inputGeneration=focusInputGeneration;String contentSource=homeSource;
        String wanted=focusKey();
        // Removing a focused tab can make Android focus a card in the outgoing row.
        // Freeze its saved selection before either region is detached.
        if(watchChanged){captureWatchRow();restoringWatchRow=true;}
        if(tabsChanged&&binding.tabsHost!=null){removeRegionBindings(binding.tabsHost);binding.tabsHost.removeAllViews();buildHomeTabs(binding.tabsHost);}
        if(watchChanged&&binding.watchHost!=null){
            removeRegionBindings(binding.watchHost);binding.watchHost.removeAllViews();
            watchTiles.clear();watchRail=null;activeWatchState=null;
            FrameLayout parent=board;board=binding.watchHost;
            buildWatchShelf(0,0,binding.viewportWidth,binding.cardWidth,binding.cardHeight,binding.gap,binding.poster);board=parent;
        }
        if(!binding.weather.equals(weatherSignature(weather))||System.currentTimeMillis()>=nextWeatherHour||isWeatherStale()!=lastRenderedWeatherStale
                ||weatherAvailable()!=lastRenderedWeatherAvailable){
            for(WeatherStatusBinding status:binding.weatherStatus){removeRegionBindings(status.view);status.view.removeAllViews();fillCompactWeather(status.view,status.width,status.ambient);}
            updateHomeForecast();
        }
        ensureBackdrop(renderGeneration);recordHomeData();renderDirty=false;
        lastRenderedWeatherStale=isWeatherStale();lastRenderedWeatherAvailable=weatherAvailable();renderedDataSignature=pageDataSignature();
        int generation=renderGeneration;
        afterLayout(board,()->{
            if(destroyed||!resumed||generation!=renderGeneration||homeBindings!=binding||screen!=Screen.HOME||contentGeneration!=homeContentGeneration||!contentSource.equals(homeSource))return;
            if(watchChanged){restoreWatchRow();if(watchRail==null)restoringWatchRow=false;}wireFocus();
            if(getCurrentFocus()==null||inputGeneration==focusInputGeneration&&((wanted.startsWith("watch-")||wanted.startsWith("recommendations-"))&&watchChanged||wanted.startsWith("source:")&&tabsChanged)){
                View desired=targets.get(wanted);if(desired==null&&(wanted.startsWith("watch-")||wanted.startsWith("recommendations-")))desired=shelfFocusTarget();
                if(desired==null&&wanted.startsWith("source:"))desired=targets.get("source:"+homeSource);
                if(desired==null)desired=targets.get(defaultFavoriteFocus());if(desired!=null)desired.requestFocus();
            }
            completePendingSourceEntry();
            loadVisibleIcons();
        });
        return true;
    }
    private void removeRegionBindings(View region){
        java.util.Iterator<Map.Entry<String,View>> it=targets.entrySet().iterator();
        while(it.hasNext()){Map.Entry<String,View> entry=it.next();if(isInside(entry.getValue(),region)){focusKeys.remove(entry.getValue());it.remove();}}
        idleTints.keySet().removeIf(view->isInside(view,region));
        for(ImageView view:new ArrayList<>(posterBindings.keySet()))if(isInside(view,region)){imageLoader.forget(view);posterBindings.remove(view);pendingPosters.remove(view);}
        for(ImageView view:new ArrayList<>(providerLogos.keySet()))if(isInside(view,region)){imageLoader.forget(view);providerLogos.remove(view);pendingLogos.remove(view);}
    }
    private static boolean isInside(View view,View region){
        for(View current=view;current!=null;){if(current==region)return true;android.view.ViewParent parent=current.getParent();current=parent instanceof View?(View)parent:null;}return false;
    }

    private void drawer() {
        if(largeText()){accessibleDrawer();return;}
        List<AppEntry> favorites=favoriteApps();
        panel(board,34,146,1212,136,28,palette.surface);
        for(int i=0;i<favorites.size();i++)appTile(favorites.get(i),i,60+i*148,168,126,94,false);
        sectionTitle(tr("Настройки","Settings"),54,306);
        String[] keys={"apps","wifi","bluetooth","theme","launcher","tv"};
        String[] symbols={"apps","wifi","bluetooth","theme","settings","tv"};
        String[] labels={tr("Приложения","Applications"),"Wi-Fi","Bluetooth",tr("Оформление","Appearance"),tr("Настройки SL","SL settings"),tr("Настройки ТВ","TV settings")};
        Runnable[] actions={()->push(Screen.ALL_APPS),()->systemSettings(Settings.ACTION_WIFI_SETTINGS),
                ()->systemSettings(Settings.ACTION_BLUETOOTH_SETTINGS),()->push(Screen.APPEARANCE),
                ()->push(Screen.SETTINGS),()->systemSettings(Settings.ACTION_SETTINGS)};
        for(int i=0;i<6;i++) {
            FrameLayout button=focusBox("shortcut-"+keys[i],48+i*198,362,190,116,false,actions[i]);
            SymbolView symbol=new SymbolView(this,symbols[i],palette.text); place(button,symbol,79,17,32,32);
            TextView caption=text(button,labels[i],12,50,166,58,19,palette.text,false); caption.setGravity(Gravity.CENTER); caption.setMaxLines(2);
            button.setContentDescription(labels[i].replace('\n',' '));
        }
        weatherStrip(board,54,504,1172,true);
    }

    private void accessibleDrawer(){
        beginRows(tr("Настройки","Settings"),"");
        row("shortcut-apps","apps",tr("Приложения","Applications"),"",0,()->push(Screen.ALL_APPS));row("shortcut-wifi","wifi","Wi-Fi","",1,()->systemSettings(Settings.ACTION_WIFI_SETTINGS));row("shortcut-bluetooth","bluetooth","Bluetooth","",2,()->systemSettings(Settings.ACTION_BLUETOOTH_SETTINGS));row("shortcut-theme","theme",tr("Оформление","Appearance"),"",3,()->push(Screen.APPEARANCE));row("shortcut-launcher","settings",tr("Настройки SL","SL settings"),"",4,()->push(Screen.SETTINGS));row("shortcut-tv","tv",tr("Настройки ТВ","TV settings"),"",5,()->systemSettings(Settings.ACTION_SETTINGS));
        if(prefs.weatherEnabled){
            FrameLayout weatherContent=new FrameLayout(this);register(weatherContent,"shortcut-weather");configureFocus(weatherContent,false);
            int height=weatherStrip(weatherContent,20,12,settingsWidth-40,false);settingsRows.addView(weatherContent,new LinearLayout.LayoutParams(-1,height+24));
            weatherContent.setContentDescription(tr("Погода. ","Weather. ")+weatherLine()+tr(". Настроить прогноз",". Weather settings"));
            weatherContent.setOnClickListener(v->{uiSounds.select();push(Screen.WEATHER);});
        }
    }

    private boolean largeText(){return getResources().getConfiguration().fontScale>=1.2f;}
    private float textPixels(float size){return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,size,getResources().getDisplayMetrics())/getResources().getDisplayMetrics().density;}
    private int textHeight(float size,int lines){Paint paint=new Paint();paint.setTypeface(textFace(size,false));paint.setTextSize(textPixels(size));Paint.FontMetricsInt metrics=paint.getFontMetricsInt();return (metrics.descent-metrics.ascent)*Math.max(1,lines)+(metrics.bottom-metrics.descent)+(metrics.ascent-metrics.top)+4;}
    private int textWidth(String value,float size){Paint paint=new Paint();paint.setTypeface(textFace(size,false));paint.setTextSize(textPixels(size));return (int)Math.ceil(paint.measureText(value));}
    private Typeface textFace(float size,boolean medium){return size>=32?(medium?typography.displaySemibold:typography.displayMedium):(medium?typography.medium:typography.regular);}
    private void settings() {
        beginRows(tr("Настройки SL","SL settings"),tr("Оформление, погода и привычные действия пульта.","Appearance, weather and familiar remote actions."));
        row("settings-appearance","theme",tr("Оформление","Appearance"),cardFormatName(),0,()->push(Screen.APPEARANCE));
        row("settings-wallpapers","image",tr("Обои","Wallpapers"),backgroundName(prefs.background),1,()->push(Screen.GALLERY));
        row("settings-sources","tv",tr("Рекомендации приложений","App recommendations"),onOff(prefs.recommendationsEnabled),2,()->push(Screen.SOURCES));
        row("settings-weather","weather",tr("Погода","Weather"),prefs.weatherEnabled?prefs.cityName:tr("Выкл.","Off"),1,()->push(Screen.WEATHER));
        row("settings-sounds","sound",tr("Звуки навигации","Navigation sounds"),onOff(prefs.sounds),2,()->{preferences.setSounds(!prefs.sounds);renderPreservingFocus();});
        row("settings-sound-test","sound",tr("Проверить звук","Test sound"),"",3,()->{
            android.util.Log.i("SoftlumiSound",uiSounds.diagnostics());
            if(!prefs.sounds)toast(tr("Включите звуки навигации","Enable navigation sounds"));
            else if(!uiSounds.isReady())toast(tr("Звук загружается. Нажмите ещё раз.","Sound is loading. Press again."));
        });
        row("settings-advanced","more",tr("Дополнительно","Advanced"),"",4,()->push(Screen.ADVANCED));
    }
    private void pageTitle(String title,String description) {
        if(largeText()){
            int backHeight=textHeight(22,1)+8;
            if(screen==Screen.SETTINGS)text(board,"softlumi",48,20,1184,backHeight,22,palette.ambientMuted,false);else actionLink("page-back",tr("‹ Назад","‹ Back"),48,20,1184,backHeight,this::goBack);
            TextView heading=text(board,title.replace('\n',' '),48,96,1184,textHeight(38,1),38,palette.ambientText,true);heading.setSingleLine(true);return;
        }
        if(screen!=Screen.SETTINGS) actionLink("page-back",screen==Screen.REMOTE_TARGETS?tr("‹ Кнопки пульта","‹ Remote buttons"):screen==Screen.CARD_FORMAT?tr("‹ Оформление","‹ Appearance"):tr("‹ Настройки SL","‹ SL settings"),70,144,392,38,this::goBack);
        else text(board,"softlumi",70,150,360,34,22,palette.ambientMuted,false);
        float titleSize=title.length()>22?43:!title.contains("\n")&&title.length()>12?48:58;
        TextView heading=text(board,title,70,202,400,205,titleSize,palette.ambientText,true);
        if(title.contains("\n"))heading.setMaxLines(3);
        else{heading.setMaxLines(1);heading.setAutoSizeTextTypeUniformWithConfiguration(30,Math.max(30,Math.round(textPixels(titleSize))),1,TypedValue.COMPLEX_UNIT_PX);}
        int helpHeadingHeight=Math.max(84,textHeight(28,2)),helpTop=422+helpHeadingHeight+16;
        detailHeading=text(board,"",70,422,365,helpHeadingHeight,28,palette.ambientText,true);detailHeading.setMaxLines(2);
        detailDescription=text(board,description,70,helpTop,365,658-helpTop,21,palette.ambientMuted,false);detailDescription.setMaxLines(4);
    }
    private int largePageContentTop(){return Math.max(180,96+textHeight(38,1)+20);}
    private void beginRows(String title,String description){
        pageTitle(title,description);int x=largeText()?48:482,y=largeText()?largePageContentTop():146,w=largeText()?1184:744,h=672-y;
        int horizontalPadding=largeText()?40:32;
        // All list pages share one material outside their fading content roots.
        pageRowsSpec=new SharedRowsSheet.Spec(x,y,w,h,28,palette.light);ScrollView scroll=new TvScrollView(this);clipScroll(scroll);scroll.setPadding(horizontalPadding,20,horizontalPadding,20);place(board,scroll,x+8,y+8,w-16,h-16);
        settingsRows=new LinearLayout(this);settingsRows.setOrientation(LinearLayout.VERTICAL);settingsRows.setClipChildren(false);settingsRows.setClipToPadding(false);scroll.addView(settingsRows,new ScrollView.LayoutParams(-1,-2));settingsWidth=w-16-horizontalPadding*2;
    }
    private void clipScroll(ScrollView scroll){
        clipScrollViewport(scroll);
    }
    private void clipScrollViewport(ViewGroup scroll){
        applyScrollMotionPolicy(scroll,allowsScrollMotion());
        scroll.setClipChildren(false);scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);scroll.setHorizontalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setPadding(Math.max(20,scroll.getPaddingLeft()),Math.max(20,scroll.getPaddingTop()),Math.max(20,scroll.getPaddingRight()),Math.max(20,scroll.getPaddingBottom()));
        // Focus scrolling may place the child's border in the padding. Clip the outer viewport,
        // not that padding, so the border stays complete and content cannot cover page headings.
        scroll.setClipBounds(new android.graphics.Rect(0,0,0,0));
        scroll.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->view.setClipBounds(new android.graphics.Rect(0,0,r-l,b-t)));
    }
    private boolean allowsScrollMotion(){
        if(prefs.reducedMotion)return false;
        // Settings observers may run before ValueAnimator's cached scale updates.
        // Use the observed setting directly, matching UiMotion's invalid-value fallback.
        try{float scale=Settings.Global.getFloat(getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1);return !Float.isFinite(scale)||scale<0||scale>0;}
        catch(RuntimeException unavailable){return ValueAnimator.areAnimatorsEnabled();}
    }
    private static void applyScrollMotionPolicy(View view,boolean allowed){
        if(view instanceof TvScrollView)((TvScrollView)view).setMotionAllowed(allowed);
        if(view instanceof TvHorizontalScrollView)((TvHorizontalScrollView)view).setMotionAllowed(allowed);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)applyScrollMotionPolicy(((ViewGroup)view).getChildAt(i),allowed);
    }
    private void resumeScrollMotionPolicy(){
        if(!observingScrollMotion)try{getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,scrollMotionObserver);observingScrollMotion=true;}
        catch(RuntimeException unavailable){/* New pages and each resume still read platform policy. */}
        applyScrollMotionPolicy(renderHost,allowsScrollMotion());
    }
    private void pauseScrollMotionPolicy(){
        if(observingScrollMotion){try{getContentResolver().unregisterContentObserver(scrollMotionObserver);}catch(RuntimeException ignored){}observingScrollMotion=false;}
        applyScrollMotionPolicy(renderHost,false);
    }
    private void sheet(int x,int y,int width,int height) { panel(board,x,y,width,height,28,palette.surface); }
    private FrameLayout row(String key,String symbol,String title,String value,int index,Runnable click) {
        int width=settingsRows==null?696:settingsWidth,height=Math.max(largeText()?112:92,textHeight(26,2)+24);
        FrameLayout button=focusBox(key,506,170+index*84,width,height,false,click);
        if(settingsRows!=null){board.removeView(button);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,height);lp.bottomMargin=12;settingsRows.addView(button,lp);}
        button.setBackground(round(18,Color.TRANSPARENT,0,0));SymbolView icon=new SymbolView(this,symbol,palette.text);place(button,icon,22,(height-34)/2,34,34);
        int valueWidth=value.isEmpty()?0:Math.max(56,Math.min(largeText()?360:200,textWidth(value,20)+24));
        TextView label=text(button,title,84,12,width-130-valueWidth,height-24,26,palette.text,true);label.setTypeface(typography.semibold);label.setMaxLines(2);
        if(!value.isEmpty()){TextView current=text(button,value,width-valueWidth-48,12,valueWidth-10,height-24,20,palette.muted,false);current.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);current.setMaxLines(2);}
        text(button,"›",width-42,(height-44)/2,32,44,35,palette.muted,false).setGravity(Gravity.CENTER);button.setContentDescription(title+(value.isEmpty()?"":", "+value));
        View.OnFocusChangeListener original=button.getOnFocusChangeListener();button.setOnFocusChangeListener((v,focused)->{if(original!=null)original.onFocusChange(v,focused);if(focused&&detailHeading!=null&&detailDescription!=null){detailHeading.setText(title);detailDescription.setText(helpFor(key));}});return button;
    }
    private void rowNote(String value){if(settingsRows==null)return;TextView note=new TextView(this);note.setText(value);note.setTypeface(typography.regular);note.setTextColor(palette.muted);note.setTextSize(TypedValue.COMPLEX_UNIT_PX,textPixels(18));note.setPadding(20,12,20,20);note.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);settingsRows.addView(note,new LinearLayout.LayoutParams(-1,-2));}
    private String helpFor(String key){
        switch(key){
            case "settings-appearance":return tr("Палитра, обои и движение интерфейса.","Palette, wallpapers and interface motion.");
            case "settings-wallpapers":case "appearance-background":return tr("Готовые обои: пейзажи и спокойные абстракции. Нажмите OK, чтобы применить.","Built-in wallpapers: landscapes and calm abstracts. Press OK to apply.");
            case "settings-weather":return tr("Город, единицы температуры и показ погоды.","City, temperature units and weather visibility.");
            case "settings-sounds":return tr("Звуки выбора и навигации внутри лаунчера.","Selection and navigation sounds inside the launcher.");
            case "settings-sound-test":return tr("Нажмите OK: должен прозвучать короткий звук выбора.","Press OK to hear the short selection sound.");
            case "appearance-period":case "period-auto":return tr("Свет меняется по восходу и закату выбранного города, даже без сети и при выключенной погоде. Если город не выбран, работает расписание по времени ТВ.","Lighting follows sunrise and sunset in your chosen city, offline and even with weather hidden. Without a city, the TV time schedule is used.");
            case "period-schedule":case "period-day-start":case "period-night-start":return tr("Задай начало дня и ночи. Используется часовой пояс выбранного города, если он известен; иначе — время ТВ.","Choose when day and night begin. Uses the chosen city's timezone when known, otherwise the TV timezone.");
            case "period-day":case "period-night":return tr("Закрепить одно освещение для всех парных обоев. Автоматическую смену можно вернуть в любой момент.","Keep one lighting variant for all paired wallpapers. Automatic switching can be restored at any time.");
            case "weather-city":return tr("Выберите город для прогноза погоды.","Choose a city for the weather forecast.");
            case "weather-units":return tr("Авто использует регион системы. Также доступны °C и °F.","Auto uses the system region. You can also choose °C or °F.");
            case "weather-enabled":return tr("Город и прогноз на главном экране и в нижней панели.","City and forecast on Home and in the lower panel.");
            case "advanced-favorites":return tr("До восьми приложений. Удерживайте OK для изменения порядка.","Up to eight applications. Hold OK to reorder.");
            case "advanced-language":return tr("Язык интерфейса softlumi.","softlumi interface language.");
            case "advanced-remote":return tr("Выбор приложений для Netflix и Prime Video на пульте Xiaomi.","Choose applications for the Xiaomi remote's Netflix and Prime Video buttons.");
            case "remote-enabled":return tr("Для пульта Xiaomi Mi Box S 3. Требуется доступ в Android. HOME, питание и сон не меняются.","For the Xiaomi Mi Box S 3 remote. Requires Android access. Home, power and sleep stay unchanged.");
            case "remote-access":return RemoteButtonService.isLegacyRemapperEnabled(this)?tr("Выключи кнопки FreeMyTV и включи «Кнопки пульта softlumi» в Android.","Disable FreeMyTV button remapping, then enable softlumi Remote buttons in Android."):tr("Включи «Кнопки пульта softlumi» в специальных возможностях Android.","Enable softlumi Remote buttons in Android Accessibility settings.");
            case "remote-netflix":case "remote-prime":return tr("Выбери приложение или стандартное действие. Если приложение недоступно, кнопка работает как прежде.","Choose an app or the original action. If the app is unavailable, the button keeps its original action.");
            case "advanced-watch-permission":return tr("Разрешите softlumi читать системные записи продолжения просмотра. Если Android больше не показывает запрос, откройте разрешения приложения.","Allow softlumi to read system continue-watching entries. If Android no longer shows a prompt, open the app's permissions.");
            default:return "";
        }
    }

    private void appearance() {
        beginRows(tr("Оформление","Appearance"),tr("Формат карточек, обои, контраст и движение интерфейса.","Card format, wallpapers, contrast and interface motion."));
        row("appearance-cards","tv",tr("Формат карточек","Card format"),cardFormatName(),0,()->push(Screen.CARD_FORMAT));
        row("appearance-background","image",tr("Обои","Wallpapers"),backgroundName(prefs.background),1,()->push(Screen.GALLERY));
        row("appearance-palette","theme",tr("Палитра","Palette"),prefs.paletteMode.equals("auto")?tr("Авто","Auto"):prefs.paletteMode.equals("light")?tr("Светлая","Light"):tr("Тёмная","Dark"),2,()->{preferences.setPaletteMode(prefs.paletteMode.equals("auto")?"light":prefs.paletteMode.equals("light")?"dark":"auto");renderPreservingFocus();});
        row("appearance-dim","image",tr("Затемнение фона","Wallpaper dim"),prefs.wallpaperDim+"%",3,()->push(Screen.WALLPAPER_DIM));
        row("appearance-period","weather",tr("День и ночь","Day and night"),periodName(prefs.wallpaperPeriod),4,()->push(Screen.DAY_NIGHT));
        row("appearance-motion","theme",tr("Уменьшение анимации","Reduce motion"),onOff(prefs.reducedMotion),5,()->{preferences.setReducedMotion(!prefs.reducedMotion);renderPreservingFocus();});
    }
    private String periodName(String period){
        if("day".equals(period))return tr("Всегда день","Always day");
        if("night".equals(period))return tr("Всегда ночь","Always night");
        if("schedule".equals(period))return tr("По расписанию","Schedule");
        return tr("По солнцу","Sunrise and sunset");
    }
    private String scheduleTime(int minutes){return String.format(Locale.ROOT,"%02d:%02d",minutes/60,minutes%60);}
    private void dayNightSettings(){
        beginRows(tr("День и ночь","Day and night"),tr("Каждый набор содержит дневной и ночной фон. Солнце рассчитывается для выбранного города без сети; без города работает расписание по времени ТВ.","Every set includes day and night. Solar time uses your chosen city offline; without a city the TV schedule is used."));
        String[] modes={"auto","schedule","day","night"};
        for(int i=0;i<modes.length;i++){final String mode=modes[i];row("period-"+mode,"weather",periodName(mode),mode.equals(prefs.wallpaperPeriod)?"✓":"",i,()->{preferences.setWallpaperPeriod(mode);renderPreservingFocus();}).setSelected(mode.equals(prefs.wallpaperPeriod));}
        row("period-day-start","weather",tr("Начало дня","Day starts"),scheduleTime(prefs.dayStartMinutes),4,()->push(Screen.DAY_START));
        row("period-night-start","weather",tr("Начало ночи","Night starts"),scheduleTime(prefs.nightStartMinutes),5,()->push(Screen.NIGHT_START));
    }
    private void schedulePicker(boolean day){
        beginRows(day?tr("Начало дня","Day starts"):tr("Начало ночи","Night starts"),tr("Шаг 30 минут; сохранённое точное время тоже доступно.","30 minute steps, with your exact saved time also available."));
        int selected=day?prefs.dayStartMinutes:prefs.nightStartMinutes;
        List<Integer> choices=new ArrayList<>();
        for(int minutes=0;minutes<1440;minutes+=30)choices.add(minutes);
        if(selected%30!=0){choices.add(selected);Collections.sort(choices);}
        int index=0;
        for(int value:choices){
            if(value==(day?prefs.nightStartMinutes:prefs.dayStartMinutes))continue;
            row("schedule-"+value,"weather",scheduleTime(value),selected==value?"✓":"",index++,()->{preferences.setDayNightSchedule(day?value:prefs.dayStartMinutes,day?prefs.nightStartMinutes:value);goBack();}).setSelected(selected==value);
        }
    }
    private void cardFormats(){
        beginRows(tr("Формат карточек","Card format"),tr("Меняется только полка контента. Часы, погода и избранные приложения остаются на своих местах.","Only the content shelf changes. Clock, weather and favorite apps stay in place."));
        row("cards-landscape","tv",tr("Горизонтальные","Horizontal"),posterCards()?"":"✓",0,()->selectCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE)).setSelected(!posterCards());
        row("cards-poster","tv",tr("Постеры","Posters"),posterCards()?"✓":"",1,()->selectCardFormat(LauncherPrefs.CARD_FORMAT_POSTER)).setSelected(posterCards());
    }
    private void selectCardFormat(String format){
        if(!prefs.cardFormat.equals(format)){preferences.setCardFormat(format);prefs=preferences.read();}
        goBack();
    }
    private void wallpaperDim(){
        pageTitle(tr("Затемнение фона","Wallpaper dim"),tr("Выбери уровень затемнения.","Choose a dimming level."));
        for(int i=0;i<=12;i++){
            final int value=i*5;int x=60+(i%7)*166,y=240+(i/7)*128;
            FrameLayout choice=focusBox("dim-"+value,x,y,146,100,false,()->{preferences.setWallpaperDim(value);requestedFocus="dim-"+value;render();});
            TextView label=text(choice,value+"%"+(prefs.wallpaperDim==value?"  ✓":""),10,20,126,60,26,palette.text,true);label.setGravity(Gravity.CENTER);
            choice.setSelected(prefs.wallpaperDim==value);choice.setContentDescription(value+"%"+(prefs.wallpaperDim==value?tr(", выбрано",", selected"):""));
        }
    }
    private String backgroundName(int i){return WallpaperCatalog.pairTitle(i,isRussian());}
    private void wallpaperGallery(){
        galleryBindings=new GalleryBindings();GalleryBindings bindings=galleryBindings;
        int headingY=largeText()?96:84,headingHeight=Math.max(70,textHeight(38,1));
        actionLink("page-back",tr("‹ Назад","‹ Back"),48,20,1184,Math.max(48,textHeight(22,1)+8),this::goBack);
        text(board,tr("Обои · день и ночь","Wallpapers · day and night"),48,headingY,1184,headingHeight,38,palette.ambientText,true).setSingleLine(true);
        int top=Math.max(170,headingY+headingHeight+20);
        TvScrollView scroll=new TvScrollView(this);bindings.scroll=scroll;clipScroll(scroll);scroll.setPadding(20,20,20,20);place(board,scroll,40,top,1200,688-top);
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);rows.setClipChildren(false);rows.setClipToPadding(false);scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));
        int captionHeight=Math.max(58,textHeight(21,2)+12),columns=largeText()?3:4,width=(1160-columns*28)/columns,height=captionHeight+(largeText()?172:156);
        List<WallpaperCatalog.Entry> entries=new ArrayList<>();
        entries.addAll(WallpaperCatalog.selectable());
        for(int start=0;start<entries.size();start+=columns){
            LinearLayout line=new LinearLayout(this);line.setClipChildren(false);line.setClipToPadding(false);rows.addView(line,new LinearLayout.LayoutParams(-1,height+28));
            for(int c=0;c<columns&&start+c<entries.size();c++){
                final int index=entries.get(start+c).id;FrameLayout tile=new FrameLayout(this);register(tile,"background-"+index);configureFocus(tile,true);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(width,height);lp.setMargins(14,12,14,16);line.addView(tile,lp);
                int imageHeight=height-captionHeight-12;
                int half=(width-20)/2;
                addPairPreview(tile,index,false,8,8,half,imageHeight-8,bindings);
                addPairPreview(tile,index,true,12+half,8,width-half-20,imageHeight-8,bindings);
                TextView caption=text(tile,"",16,height-captionHeight-6,width-32,captionHeight,21,palette.text,true);caption.setMaxLines(2);bindings.captions.put(index,caption);
                updateWallpaperSelection(index);
                tile.setOnClickListener(v->{uiSounds.select();selectWallpaper(index);});
            }
        }
    }
    private void addPairPreview(FrameLayout tile,int id,boolean night,int x,int y,int width,int height,GalleryBindings bindings){
        int effective=WallpaperCatalog.effectiveId(id,night);
        if(id==WallpaperCatalog.CONTENT_AMBIENT){ContentBackdrop preview=new ContentBackdrop(this,!night,false);clipRounded(preview,12);place(tile,preview,x,y,width,height);return;}
        if(WallpaperCatalog.isVector(effective)){SceneBackground preview=new SceneBackground(this);preview.setScene(night?2:1,effective);clipRounded(preview,12);place(tile,preview,x,y,width,height);return;}
        ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.CENTER_CROP);clipRounded(preview,12);place(tile,preview,x,y,width,height);
        float scale=designScale();WallpaperPreview item=new WallpaperPreview(WallpaperCatalog.resource(this,id,night),Math.round(width*scale),Math.round(height*scale));
        bindings.images.put(preview,item);
    }
    private void updateWallpaperSelection(int id){
        if(galleryBindings==null)return;TextView caption=galleryBindings.captions.get(id);View tile=targets.get("background-"+id);
        boolean selected=WallpaperCatalog.pairId(prefs.background)==id;if(caption!=null)caption.setText((selected?"✓ ":"")+backgroundName(id));
        if(tile!=null){tile.setSelected(selected);tile.setContentDescription(backgroundName(id)+(selected?tr(", выбрано",", selected"):""));}
    }
    private void selectWallpaper(int id){
        if(WallpaperCatalog.pairId(prefs.background)==id)return;
        int old=WallpaperCatalog.pairId(prefs.background);preferences.setBackground(id);LauncherPrefs.Settings updated=preferences.read();Palette next=new Palette(updated);
        requestedFocus="background-"+id;
        if(next.light!=palette.light||next.ambientText!=palette.ambientText||next.ambientMuted!=palette.ambientMuted){render();return;}
        prefs=updated;renderedPrefsSignature=settingsSignature(prefs);
        updateWallpaperSelection(old);updateWallpaperSelection(id);ensureBackdrop(renderGeneration);
    }
    private void loadVisibleWallpapers(){
        if(galleryBindings==null)return;
        android.graphics.Rect window=new android.graphics.Rect(),bounds=new android.graphics.Rect();int[] point=new int[2];
        boolean visible=galleryBindings.scroll.getGlobalVisibleRect(window);float scale=designScale();
        window.inset(0,-Math.round(242*scale));
        for(Map.Entry<ImageView,WallpaperPreview> entry:galleryBindings.images.entrySet()){
            ImageView view=entry.getKey();WallpaperPreview preview=entry.getValue();view.getLocationOnScreen(point);
            bounds.set(point[0],point[1],point[0]+Math.round(view.getWidth()*scale),point[1]+Math.round(view.getHeight()*scale));
            boolean near=visible&&view.isShown()&&android.graphics.Rect.intersects(window,bounds);
            if(!near){if(preview.loading||view.getDrawable()!=null)imageLoader.forget(view);preview.loading=false;continue;}
            if(view.getDrawable()==null&&!preview.loading){preview.loading=true;imageLoader.wallpaper(view,preview.resource,preview.width,preview.height,renderGeneration);}
        }
    }
    private void weatherSettings() {
        beginRows(tr("Погода","Weather"),tr("Город выбирается вручную. Автоматическое определение местоположения не используется.","Choose a city manually. Location is not detected automatically."));
        row("weather-enabled","weather",tr("Показывать погоду","Show weather"),onOff(prefs.weatherEnabled),0,()->{preferences.setWeatherEnabled(!prefs.weatherEnabled);weather=null;renderPreservingFocus();refreshData();});
        row("weather-city","language",tr("Город","City"),prefs.cityName.isEmpty()?tr("Выбрать","Choose"):prefs.cityName,1,()->push(Screen.CITY));
        row("weather-units","theme",tr("Температура","Temperature"),prefs.units.equals("auto")?tr("Авто","Auto"):prefs.units.equals("fahrenheit")?"°F":"°C",2,()->{preferences.setUnits(prefs.units.equals("auto")?"celsius":prefs.units.equals("celsius")?"fahrenheit":"auto");weather=null;renderPreservingFocus();refreshData();});
        row("weather-refresh","weather",tr("Обновить погоду","Refresh weather"),"",3,()->refreshData(true));
        rowNote(weatherStatus());
        WeatherRepository.Provider provider=weatherAvailable()?weather.weather.provider:WeatherRepository.Provider.OPEN_METEO;
        row("weather-attribution","info",provider.displayName+" ↗","",4,()->openUrl(provider.attributionUrl));
    }

    private void citySearch() {
        pageTitle(tr("Город","City"),tr("Введите название и выберите место из результатов поиска.","Enter a name and choose a place from the search results."));
        int sheetTop=largeText()?largePageContentTop():118;
        int cx=largeText()?72:506,cy=largeText()?sheetTop+22:142,cw=largeText()?1136:696;sheet(largeText()?48:482,sheetTop,largeText()?1184:744,672-sheetTop);
        EditText input=new EditText(this); input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX,textPixels(26));input.setTypeface(typography.regular);input.setTextColor(palette.text);input.setHintTextColor(palette.muted);
        input.setHint(tr("Название города","City name"));input.setText(cityQuery);input.setPadding(22,8,22,8);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});
        input.setBackground(round(16,palette.surface,palette.border,2));
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        register(input,"city-query");place(board,input,cx,cy,cw,largeText()?88:72);
        input.setContentDescription(tr("Поиск города","Search city"));
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){cityQuery=s.toString();scheduleCitySearch();}
            public void afterTextChanged(Editable e){}
        });
        input.setOnEditorActionListener((v,action,event)->{performCitySearch();return true;});
        cityStatus=text(board,cityMessage,cx+4,cy+(largeText()?96:84),cw-12,largeText()?70:50,18,palette.muted,false);
        ScrollView scroller=new TvScrollView(this);scroller.setFillViewport(false);clipScroll(scroller);scroller.setPadding(36,20,36,20);
        int attributionHeight=textHeight(12,1),attributionTop=666-attributionHeight,resultsTop=cy+(largeText()?178:138);
        place(board,scroller,cx,resultsTop,cw,attributionTop-resultsTop-12);
        cityList=new LinearLayout(this);cityList.setOrientation(LinearLayout.VERTICAL);cityList.setClipChildren(false);cityList.setClipToPadding(false);scroller.addView(cityList,new ScrollView.LayoutParams(-1,-2));
        text(board,tr("Города: GeoNames · Open-Meteo","Cities: GeoNames · Open-Meteo"),cx+8,attributionTop,cw-16,attributionHeight,12,palette.muted,false).setSingleLine(true);
        updateCityResults();
        if(!cityQuery.isEmpty() && cityResults.isEmpty())scheduleCitySearch();
    }
    private boolean citySearchPending;
    private void scheduleCitySearch() {
        cityGeneration++;
        if(cityFuture!=null){cityFuture.cancel(true);cityFuture=null;}
        if(pendingSearch!=null)main.removeCallbacks(pendingSearch);
        citySearchPending=cityQuery.trim().length()>=2;
        if(cityQuery.trim().length()<2){cityResults=Collections.emptyList();cityMessage=tr("Введите не менее двух букв","Enter at least two letters");updateCityResults();return;}
        cityResults=Collections.emptyList();cityMessage=tr("Поиск…","Searching…");updateCityResults();
        pendingSearch=this::performCitySearch;main.postDelayed(pendingSearch,450);
    }
    private void performCitySearch() {
        if(destroyed||!resumed||screen!=Screen.CITY)return;
        if(pendingSearch!=null)main.removeCallbacks(pendingSearch);
        String query=cityQuery.trim();if(query.length()<2){citySearchPending=false;return;}
        citySearchPending=true;
        int token=++cityGeneration;String language=isRussian()?"ru":"en";
        if(cityFuture!=null)cityFuture.cancel(true);
        cityIo.purge();
        try{cityFuture=cityIo.submit(()->{
            WeatherRepository.CityResult result=weatherRepository.searchCities(query,language);
            main.post(()->{
                if(destroyed||!resumed||screen!=Screen.CITY||token!=cityGeneration)return;
                citySearchPending=false;cityFuture=null;
                cityResults=result.items;
                switch(result.status){
                    case UNAVAILABLE:cityMessage=tr("Поиск недоступен. Проверьте подключение.","Search unavailable. Check your connection.");break;
                    case ERROR:cityMessage=tr("Не удалось получить список городов","Unable to load cities");break;
                    default:cityMessage=cityResults.isEmpty()?tr("Ничего не найдено","No results"):"";
                }
                updateCityResults();
            });
        });}catch(java.util.concurrent.RejectedExecutionException unavailable){
            citySearchPending=false;cityMessage=tr("Поиск недоступен. Попробуйте ещё раз.","Search unavailable. Try again.");updateCityResults();
        }
    }
    private void updateCityResults() {
        if(cityList==null||cityStatus==null)return;
        String focused=focusKey();boolean restoreResult=focused.startsWith("city:");
        cityStatus.setText(cityMessage==null?"":cityMessage);removeRegionBindings(cityList);cityList.removeAllViews();
        for(int i=0;i<cityResults.size();i++){
            WeatherRepository.City city=cityResults.get(i);
            FrameLayout button=new FrameLayout(this);register(button,"city:"+city.id);button.setTag("city-result-"+i);configureFocus(button,false);
            int height=Math.max(72,textHeight(22,2)+20);LinearLayout.LayoutParams rowSize=new LinearLayout.LayoutParams(-1,height);rowSize.bottomMargin=12;cityList.addView(button,rowSize);
            String name=city.displayName();
            text(button,name,18,10,(largeText()?1136:696)-108,height-20,22,palette.text,false).setMaxLines(2);
            button.setContentDescription(name);
            button.setOnClickListener(v->{
                uiSounds.select();
                preferences.setCity(city.name,city.latitude,city.longitude,city.id,city.timezone);
                preferences.setWeatherEnabled(true);weather=null;goBack();refreshData();
            });
        }
        cityList.post(()->{
            wireFocus();if(restoreResult){View desired=targets.get(focused);if(desired==null)desired=targets.get("city-query");if(desired!=null)desired.requestFocus();}
        });
    }

    private void advanced() {
        beginRows(tr("Дополнительно","Advanced"),tr("Приложения, резервная копия и сведения о лаунчере.","Applications, settings backup and launcher information."));
        row("advanced-language","language",tr("Язык","Language"),prefs.language.equals("ru")?"Русский":prefs.language.equals("en")?"English":tr("Системный","System"),0,()->push(Screen.LANGUAGE));
        row("advanced-favorites","star",tr("Избранные приложения","Favorite applications"),prefs.favoriteIds.size()+" / 8",1,()->push(Screen.FAVORITES));
        row("advanced-manage-apps","apps",tr("Управление приложениями","Manage applications"),"",2,()->push(Screen.MANAGE_APPS));
        row("advanced-watch-next","tv",tr("Продолжить просмотр","Continue watching"),onOff(prefs.watchNextVisible),3,()->{preferences.setWatchNextVisible(!prefs.watchNextVisible);renderPreservingFocus();refreshData();});
        row("advanced-watch-permission","tv",tr("Доступ к продолжению","Continue watching access"),watchPermissionLabel(),4,this::requestWatchPermission)
                .setContentDescription(tr("Доступ к системным записям продолжения просмотра, ","Read access to system continue-watching entries, ")+watchPermissionLabel());
        rowNote(tr("Доступ позволяет читать системные записи продолжения просмотра. Если Android больше не спрашивает разрешение, используйте «Разрешения Android».","Access allows reading system continue-watching entries. If Android no longer asks for permission, use Android permissions."));
        row("advanced-watch-app-settings","settings",tr("Разрешения Android","Android permissions"),"",5,this::watchAppSettings);
        row("advanced-backup","image",tr("Резервная копия настроек","Settings backup"),"",6,()->push(Screen.BACKUP));
        row("advanced-remote","tv",tr("Кнопки пульта","Remote buttons"),"",7,()->push(Screen.REMOTE));
        row("advanced-about","info",tr("О приложении","About"),"",8,()->push(Screen.ABOUT));
        if(BuildConfig.CHANNEL_PROBE_ENABLED)row("advanced-channel-probe","tv",tr("Проверка TV-каналов","TV channel check"),tr("Локальная диагностика","Local diagnostics"),9,()->push(Screen.CHANNEL_PROBE));
    }
    private void channelProbe(){
        beginRows(tr("Проверка TV-каналов","TV channel check"),tr("Сначала только данные. Открытие карточки — отдельное действие.","Data first. Opening a card is a separate action."));
        if(!BuildConfig.CHANNEL_PROBE_ENABLED){rowNote(tr("Диагностика выключена","Diagnostics are off"));return;}
        row("probe-run","tv",channelProbeLoading?tr("Проверка…","Checking…"):tr("Прочитать опубликованные каналы","Read published channels"),"UID "+android.os.Process.myUid(),0,this::startChannelProbe);
        if(!channelProbeMessage.isEmpty())rowNote(channelProbeMessage);
        ChannelProbeSession.Result result=channelProbeResult;if(result==null)return;
        rowNote("Result: "+result.outcome+" · "+result.elapsedMillis+" ms");
        ChannelRepository.Discovery discovery=result.discovery;
        if(discovery==null)return;
        rowNote("Channels: "+discovery.status+" · "+discovery.completeness+" · scanned="+discovery.stats.scanned+" · selectable="+discovery.selectableChannels.size());
        int index=1;
        for(int i=0;i<result.programs.size();i++){
            ChannelRepository.Programs programs=result.programs.get(i);if(programs.channel==null)continue;
            String label=programs.channel.appLabel+tr(" · полка "," · shelf ")+(i+1);
            String detail=programs.status+" / "+programs.completeness+" · "+programs.stats.scanned+" → "+programs.items.size();
            FrameLayout button=row("probe-shelf-"+i,"tv",label,detail,index++,()->{if(programs.items.isEmpty())return;probeInspection=programs;probeProgramIndex=0;push(Screen.CHANNEL_PROBE_ITEM);});
            button.setContentDescription(label+" · "+detail);
        }
        for(ChannelRepository.SourceSummary source:discovery.sources){
            String detail="channels="+source.scannedChannelCount+" · selectable="+source.selectableChannelCount+" · hidden="+source.hiddenChannelCount+" · version="+source.versionCode;
            row("probe-source-"+source.packageName,"info",source.appLabel,detail,index++,()->startChannelProbe(source.packageName)).setContentDescription(source.packageName+" · "+detail);
        }
    }
    private void startChannelProbe(){
        startChannelProbe("");
    }
    private void startChannelProbe(String sourcePackage){
        if(!BuildConfig.CHANNEL_PROBE_ENABLED||channelProbeLoading)return;
        if(channelProbeSession==null)channelProbeSession=new ChannelProbeSession(this);
        channelProbeResult=null;probeInspection=null;channelProbeMessage="";
        ChannelProbeSession.Listener listener=result->{
            if(destroyed||!resumed||screen!=Screen.CHANNEL_PROBE)return;
            channelProbeLoading=false;channelProbeResult=result;renderPreservingFocus();
        };
        channelProbeLoading=sourcePackage.isEmpty()?channelProbeSession.start(listener):channelProbeSession.startSource(sourcePackage,listener);
        if(!channelProbeLoading)channelProbeMessage=tr("Предыдущий запрос ещё завершается. Можно вернуться назад.","The previous request is still finishing. Back remains available.");
        renderPreservingFocus();
    }
    private void channelProbeItem(){
        if(!BuildConfig.CHANNEL_PROBE_ENABLED||probeInspection==null||probeInspection.items.isEmpty()){beginRows(tr("Проверка карточки","Card check"),"");rowNote(tr("Повтори чтение источника","Read the source again"));return;}
        int count=Math.min(3,probeInspection.items.size());probeProgramIndex=Math.max(0,Math.min(probeProgramIndex,count-1));
        ChannelRepository.Program program=probeInspection.items.get(probeProgramIndex);
        actionLink("page-back",tr("‹ Назад","‹ Back"),64,42,150,48,this::goBack);
        text(board,probeInspection.channel.appLabel+" · "+(probeProgramIndex+1)+" / "+count,64,104,1120,46,30,palette.ambientText,true);
        ImageView art=new ImageView(this);art.setScaleType(ImageView.ScaleType.CENTER_CROP);art.setBackgroundColor(palette.surface);clipRounded(art,20);place(board,art,64,172,680,290);loadPoster(art,program.posterUri,680,290);
        text(board,program.title,64,478,680,72,28,palette.ambientText,true).setMaxLines(2);
        text(board,program.description,64,562,680,70,18,palette.ambientMuted,false).setMaxLines(2);
        action("probe-open",tr("Открыть в приложении","Open in application"),"tv",802,188,398,96,()->{if(!new ChannelRepository().launch(this,probeInspection.channel,program))toast(tr("Ссылка недоступна","Link unavailable"));});
        action("probe-next",tr("Следующая карточка","Next card"),"apps",802,310,398,96,()->{probeProgramIndex=(probeProgramIndex+1)%count;renderPreservingFocus();});
        action("probe-finish",tr("Назад к результату","Back to result"),"back",802,432,398,96,this::goBack);
    }
    private int watchPermissionState(){return !WatchNextRepository.isReadPermissionAvailable(this)?0:WatchNextRepository.hasReadPermission(this)?2:1;}
    private String watchPermissionLabel(){
        int state=watchPermissionState();return state==0?tr("Недоступно","Unavailable"):state==2?tr("Разрешён","Allowed"):watchPermissionBlocked?tr("Настройки Android","Android settings"):tr("Разрешить","Allow");
    }
    private void requestWatchPermission(){
        int state=watchPermissionState();
        if(state==0){toast(tr("На этом устройстве системное разрешение недоступно","The system permission is unavailable on this device"));return;}
        if(state==2){refreshData();toast(tr("Доступ уже разрешён","Access is already allowed"));return;}
        if(watchPermissionBlocked){watchAppSettings();return;}
        try{requestPermissions(new String[]{WatchNextRepository.READ_TV_LISTINGS_PERMISSION},REQUEST_WATCH_PERMISSION);}
        catch(RuntimeException unavailable){watchPermissionBlocked=true;renderPreservingFocus();toast(tr("Откройте «Разрешения Android», чтобы проверить доступ","Open Android permissions to check access"));}
    }
    private void watchAppSettings(){
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
        catch(ActivityNotFoundException|SecurityException unavailable){systemSettings(Settings.ACTION_SETTINGS);}
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode!=REQUEST_WATCH_PERMISSION)return;
        boolean allowed=watchPermissionState()==2;
        if(!allowed){recommendations=null;recommendationsSession.pause();if(resumed)recommendationsSession.resume();}
        watchPermissionBlocked=!allowed&&WatchNextRepository.isReadPermissionAvailable(this)&&!shouldShowRequestPermissionRationale(WatchNextRepository.READ_TV_LISTINGS_PERMISSION);
        renderPreservingFocus();refreshData();
        if(!allowed)toast(tr("Нет доступа к продолжению просмотра. Его можно разрешить в настройках Android.","No access to continue-watching entries. You can allow it in Android settings."));
    }
    private void languages() {
        beginRows(tr("Язык","Language"),tr("Язык интерфейса softlumi.","softlumi interface language."));String[] codes={"system","ru","en"};String[] names={tr("Системный","System"),"Русский","English"};
        for(int i=0;i<codes.length;i++){String code=codes[i];row("language-"+code,"language",names[i],prefs.language.equals(code)?"✓":"",i,()->{preferences.setLanguage(code);renderPreservingFocus();});}
    }

    private String displayName(AppEntry app){String custom=prefs.customLabels.get(app.packageName);return custom==null||custom.isEmpty()?app.label:custom;}
    private void applications(boolean editing) {
        boolean manage=screen==Screen.MANAGE_APPS;
        int headingY=largeText()?96:30,headingHeight=Math.max(70,textHeight(36,1));
        text(board,manage?tr("Управление приложениями","Manage applications"):editing?tr("Избранные приложения","Favorite applications"):tr("Приложения","Applications"),48,headingY,largeText()?1184:1040,headingHeight,36,palette.ambientText,true).setSingleLine(true);
        actionLink("apps-back",tr("Назад","Back"),largeText()?48:1110,largeText()?20:44,largeText()?1184:130,Math.max(44,textHeight(22,1)+8),this::goBack);
        int noteY=largeText()?headingY+headingHeight+12:104,noteHeight=largeText()?textHeight(21,2)+12:42,top=noteY+noteHeight+20;
        if(manage){int filterHeight=Math.max(72,textHeight(24,2)+20);action("manage-hidden","apps",null,48,noteY,1168,filterHeight,()->{manageHiddenOnly=!manageHiddenOnly;renderPreservingFocus();});FrameLayout button=(FrameLayout)targets.get("manage-hidden");((TextView)button.getChildAt(0)).setText(manageHiddenOnly?tr("Скрытые приложения · показать все","Hidden applications · show all"):tr("Все приложения · показать скрытые","All applications · show hidden"));button.setContentDescription(manageHiddenOnly?tr("Скрытые приложения. Показать все","Hidden applications. Show all"):tr("Все приложения. Показать скрытые","All applications. Show hidden"));top=noteY+filterHeight+20;}
        else text(board,editing?tr("OK добавить или убрать · до восьми · удерживайте OK для порядка","OK add or remove · up to eight · hold OK to reorder"):tr("OK открыть · удерживайте OK, чтобы добавить в избранное","OK open · hold OK to add to favorites"),48,noteY,1160,noteHeight,21,palette.ambientMuted,false).setMaxLines(largeText()?2:1);
        int missing=0;if(editing&&catalogLoaded)for(String id:prefs.favoriteIds)if(!appsById.containsKey(id))missing++;
        if(missing>0){int recoveryHeight=Math.max(72,textHeight(24,2)+20);action("favorites-remove-missing",tr("Убрать недоступные приложения (","Remove unavailable applications (")+missing+")","star",48,top,1168,recoveryHeight,()->{List<String> installed=new ArrayList<>();for(String id:prefs.favoriteIds)if(appsById.containsKey(id))installed.add(id);preferences.setFavorites(installed);renderPreservingFocus();});top+=recoveryHeight+20;}
        List<AppEntry> visible=new ArrayList<>();for(AppEntry app:catalog){boolean hidden=prefs.hiddenPackages.contains(app.packageName);if(manage?(!manageHiddenOnly||hidden):!hidden)visible.add(app);}
        if(visible.isEmpty()){text(board,tr(!catalogLoaded?"Загрузка приложений…":manage&&manageHiddenOnly?"Скрытых приложений нет":"Доступных приложений нет",!catalogLoaded?"Loading applications…":manage&&manageHiddenOnly?"No hidden applications":"No available applications"),64,top+42,1100,94,28,palette.ambientText,false);return;}
        ScrollView scroller=new TvScrollView(this);clipScroll(scroller);scroller.setPadding(20,20,20,20);place(board,scroller,36,top,1208,686-top);
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);rows.setClipChildren(false);rows.setClipToPadding(false);scroller.addView(rows,new ScrollView.LayoutParams(-1,-2));int columns=largeText()?5:6,cellWidth=(1168-columns*24)/columns,cellHeight=136;
        for(int start=0;start<visible.size();start+=columns){LinearLayout line=new LinearLayout(this);line.setClipChildren(false);line.setClipToPadding(false);rows.addView(line,new LinearLayout.LayoutParams(-1,cellHeight+28));for(int c=0;c<columns&&start+c<visible.size();c++){
            AppEntry app=visible.get(start+c);boolean favorite=prefs.favoriteIds.contains(app.id),hidden=prefs.hiddenPackages.contains(app.packageName);FrameLayout button=new FrameLayout(this);register(button,"app-"+app.id);configureFocus(button,false);catalogGrid.add(button);catalogColumns=columns;LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(cellWidth,cellHeight);cell.setMargins(12,14,12,14);line.addView(button,cell);
            ImageView icon=new ImageView(this);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);clipRounded(icon,14);place(button,icon,16,14,cellWidth-32,108);loadIcon(icon,app);
            int favoritePosition=prefs.favoriteIds.indexOf(app.id)+1;
            if(editing&&favorite||manage&&hidden){TextView state=text(button,manage&&hidden?"⊘":String.valueOf(favoritePosition),8,6,28,28,15,palette.text,true);state.setGravity(Gravity.CENTER);}
            button.setSelected(editing&&favorite);button.setContentDescription(displayName(app)+(hidden?tr(", скрыто",", hidden"):favorite?tr(", в избранном",", favorite"):"")+(editing&&favorite?tr(", позиция ",", position ")+favoritePosition:""));
            button.setOnClickListener(v->{uiSounds.select();if(manage){managedAppId=app.id;push(Screen.APP_OPTIONS);}else if(editing)toggleFavorite(app);else launchApp(app);});button.setOnLongClickListener(v->{if(manage)return false;uiSounds.select();if(editing&&prefs.favoriteIds.contains(app.id))beginReorder(app.id);else toggleFavorite(app);return true;});
        }}
    }
    private void appOptions(){
        AppEntry app=appsById.get(managedAppId);beginRows(app==null?tr("Приложение","Application"):displayName(app),tr("Изменения действуют только внутри softlumi.","Changes apply only inside softlumi."));
        if(app==null){rowNote(tr("Приложение недоступно","Application unavailable"));return;}
        row("app-visibility","apps",prefs.hiddenPackages.contains(app.packageName)?tr("Показать приложение","Show application"):tr("Скрыть приложение","Hide application"),"",0,()->{preferences.setPackageHidden(app.packageName,!prefs.hiddenPackages.contains(app.packageName));renderPreservingFocus();});
        row("app-rename","language",tr("Название в лаунчере","Launcher name"),displayName(app),1,()->push(Screen.RENAME_APP));
        row("app-reset-name","back",tr("Вернуть исходное название","Restore original name"),"",2,()->{preferences.setCustomLabel(app.packageName,"");renderPreservingFocus();});rowNote(app.packageName);
    }
    private void renameApp(){
        AppEntry app=appsById.get(managedAppId);actionLink("page-back",tr("‹ Назад","‹ Back"),48,20,1184,Math.max(50,textHeight(22,1)+8),this::goBack);text(board,tr("Название приложения","Application name"),48,112,1184,Math.max(90,textHeight(38,1)),38,palette.ambientText,true).setSingleLine(true);if(app==null)return;
        EditText input=new EditText(this);input.setText(displayName(app));input.setTypeface(typography.regular);input.setSingleLine(true);input.setTextColor(palette.text);input.setTextSize(TypedValue.COMPLEX_UNIT_PX,textPixels(28));input.setPadding(24,12,24,12);input.setBackground(round(16,palette.surface,palette.border,2));input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});register(input,"app-name-input");place(board,input,48,244,1184,Math.max(96,textHeight(28,1)+24));
        action("app-name-save",tr("Сохранить","Save"),"star",48,382,1184,86,()->{preferences.setCustomLabel(app.packageName,input.getText().toString().trim());((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);goBack();});
    }
    private void backupSettings(){
        beginRows(tr("Резервная копия","Settings backup"),tr("Сохранение и восстановление настроек через выбранный файл.","Save and restore settings using a chosen file."));
        row("backup-export","image",tr("Экспорт настроек","Export settings"),"",0,()->{try{pendingExportJson=SettingsBackup.exportJson(preferences.read());Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"softlumi-settings.json");startActivityForResult(intent,EXPORT_SETTINGS);}catch(IllegalArgumentException|ActivityNotFoundException|SecurityException e){toast(tr("Не удалось подготовить файл настроек","Unable to prepare settings file"));}});
        row("backup-import","apps",tr("Импорт настроек","Import settings"),"",1,()->pickDocument("application/json",IMPORT_SETTINGS));rowNote(tr("Перед применением появится проверенное описание изменений. История просмотра, кэш и разрешения устройства в файл не включаются.","You will review validated changes before applying them. Viewing history, caches and device permissions are not included."));if(!backupError.isEmpty())rowNote(backupError);
    }
    private void backupPreview(){
        beginRows(tr("Проверка копии","Review backup"),tr("Текущие настройки изменятся только после нажатия «Применить».","Current settings change only after you choose Apply."));
        if(backupPreview==null){rowNote(tr("Выберите файл повторно","Choose the file again"));return;}
        if(!backupPreview.valid){rowNote(tr("Файл не прошёл проверку. Настройки не изменены.","The file did not pass validation. Settings were not changed."));for(String error:backupPreview.errors)rowNote(error);return;}
        LauncherPrefs.Settings imported=backupPreview.settings;
        rowNote(tr("День и ночь: ","Day and night: ")+periodName(imported.wallpaperPeriod)+"\n"
                +tr("Расписание: день с ","Schedule: day from ")+scheduleTime(imported.dayStartMinutes)
                +tr(", ночь с ",", night from ")+scheduleTime(imported.nightStartMinutes)+"\n");
        rowNote(tr("Рекомендации: ","Recommendations: ")+onOff(imported.recommendationsEnabled)+tr(". После импорта полки приложений нужно выбрать заново на этом устройстве.",". After import, select app shelves again on this device."));
        rowNote(tr("Формат карточек: ","Card format: ")+cardFormatName(imported.cardFormat)+"\n"+tr("Фон: ","Wallpaper: ")+backgroundName(imported.background)+"\n"+tr("Палитра: ","Palette: ")+imported.paletteMode+" · "+tr("затемнение ","dim ")+imported.wallpaperDim+"%\n"+tr("Язык: ","Language: ")+imported.language+"\n"+tr("Погода: ","Weather: ")+onOff(imported.weatherEnabled)+(imported.cityName.isEmpty()?"":" · "+imported.cityName)+" · "+imported.units+"\n"+tr("Звуки: ","Sounds: ")+onOff(imported.sounds)+"\n"+tr("Уменьшение анимации: ","Reduce motion: ")+onOff(imported.reducedMotion)+"\n"+tr("Продолжить просмотр: ","Continue watching: ")+onOff(imported.watchNextVisible));
        rowNote(tr("Избранное: ","Favorites: ")+backupPreview.favoriteCount+tr(" · скрытых приложений: "," · hidden applications: ")+backupPreview.hiddenCount+tr(" · названий: "," · custom names: ")+backupPreview.labelCount);
        if(backupPreview.resetFieldCount>0)rowNote(tr("Отсутствующие поля получат значения по умолчанию: ","Missing fields will use defaults: ")+backupPreview.resetFieldCount);
        rowNote(tr("Доступ к файлам, системные разрешения и настройки пульта не импортируются.","File access, system permissions and remote button settings are not imported."));
        row("backup-apply","star",tr("Применить настройки","Apply settings"),"",0,()->{if(SettingsBackup.apply(preferences,backupPreview)){backupPreview=null;weather=null;prefs=preferences.read();goBack();refreshData();toast(tr("Настройки восстановлены","Settings restored"));}});
    }
    private void pickDocument(String type,int request){try{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,request);}catch(ActivityNotFoundException|SecurityException e){toast(tr("Выбор файла недоступен","File picker unavailable"));}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();if(!"content".equals(uri.getScheme())){toast(tr("Выберите документ через системный выбор файлов","Choose a document using the system file picker"));return;}
        final int token=++documentGeneration;
        if(request==EXPORT_SETTINGS){
            String json;try{json=pendingExportJson.isEmpty()?SettingsBackup.exportJson(preferences.read()):pendingExportJson;}catch(IllegalArgumentException oversized){toast(tr("Настройки превышают лимит 64 КиБ","Settings exceed the 64 KiB limit"));return;}pendingExportJson="";
            documentIo.write(uri,json,(success,text)->{if(destroyed||token!=documentGeneration||screen!=Screen.BACKUP)return;backupError=success?tr("Копия сохранена","Backup saved"):tr("Не удалось сохранить копию","Unable to save backup");renderPreservingFocus();});
        }
        if(request==IMPORT_SETTINGS){
            documentIo.read(uri,(success,text)->{
                if(destroyed||token!=documentGeneration||screen!=Screen.BACKUP)return;
                if(!success){backupError=tr("Не удалось прочитать копию. Допустимый размер — до 64 КиБ.","Unable to read backup. Maximum size is 64 KiB.");renderPreservingFocus();return;}
                backupError="";backupPreview=SettingsBackup.preview(text);backStack.addLast(new Route(Screen.BACKUP,"backup-import"));screen=Screen.BACKUP_PREVIEW;requestedFocus="page-back";renderPreservingFocus();
            });
        }
    }

    private void toggleFavorite(AppEntry app) {
        String focused=focusKey();
        if(prefs.favoriteIds.contains(app.id))preferences.removeFavorite(app.id);
        else if(prefs.favoriteIds.size()>=8){toast(tr("В избранном может быть до восьми приложений","Choose up to eight favorite applications"));return;}
        else preferences.addFavorite(app.id);
        requestedFocus=focused;render();
    }

    private void remoteInfo() {
        beginRows(tr("Кнопки пульта","Remote buttons"),tr("Приложения для кнопок Netflix и Prime Video. Назначения действуют на этом устройстве.","Applications for the Netflix and Prime Video buttons. These choices apply to this device."));
        RemoteButtonPreferences.Snapshot selected=remotePreferences.read();
        // Large-text pages omit the left detail column; keep setup guidance in their list.
        if(largeText())rowNote(helpFor("remote-enabled")+"\n"+helpFor("remote-access"));
        row("remote-enabled","tv",tr("Переназначение кнопок","Button remapping"),onOff(selected.enabled),0,()->{remotePreferences.setEnabled(!remotePreferences.read().enabled);renderPreservingFocus();});
        row("remote-access","settings",tr("Доступ к кнопкам","Button access"),remoteServiceStatus(),1,()->{if(!RemoteButtonService.openSettings(this))toast(tr("Специальные возможности Android недоступны","Android Accessibility settings are unavailable"));});
        row("remote-netflix","apps","Netflix",remoteTargetLabel(selected.netflixPackage),2,()->openRemoteTargets(RemoteButtonPreferences.Button.NETFLIX));
        row("remote-prime","apps","Prime Video",remoteTargetLabel(selected.primePackage),3,()->openRemoteTargets(RemoteButtonPreferences.Button.PRIME));
    }
    private String remoteServiceStatus(){
        if(!RemoteButtonService.supportsHost())return tr("Профиль Xiaomi недоступен","Xiaomi profile unavailable");
        if(RemoteButtonService.isLegacyRemapperEnabled(this))return tr("Включена служба FreeMyTV","FreeMyTV service enabled");
        if(!RemoteButtonService.isEnabled(this))return tr("Нужно включить в Android","Enable in Android");
        return RemoteButtonService.isConnected()?tr("Подключено","Connected"):tr("Ожидание подключения","Waiting for connection");
    }
    private void openRemoteTargets(RemoteButtonPreferences.Button button){remoteEditingButton=button;push(Screen.REMOTE_TARGETS);}
    private String remoteTargetLabel(String packageName){
        if(packageName.isEmpty())return tr("Стандартное действие","Original action");
        for(AppEntry app:catalog)if(app.packageName.equals(packageName))return displayName(app);
        String label=packageName.equals(RemoteButtonPreferences.DEFAULT_NETFLIX_PACKAGE)?tr("Кинопоиск","Kinopoisk"):packageName.equals(RemoteButtonPreferences.DEFAULT_PRIME_PACKAGE)?"VK Видео":packageName;
        return label+(catalogLoaded?tr(" · не установлено"," · not installed"):"");
    }
    private void remoteTargets(){
        String title=remoteEditingButton==RemoteButtonPreferences.Button.NETFLIX?"Netflix":"Prime Video";
        beginRows(title,tr("Выбери приложение для этой кнопки. Движение фокуса не меняет назначение.","Choose an application for this button. Moving focus does not change the assignment."));
        String selected=remotePreferences.read().packageFor(remoteEditingButton);
        row("remote-target-original","back",tr("Стандартное действие","Original action"),selected.isEmpty()?"✓":"",0,()->selectRemoteTarget("")).setSelected(selected.isEmpty());
        java.util.Set<String> added=new java.util.HashSet<>();int index=1;
        for(AppEntry app:catalog){if(!added.add(app.packageName))continue;String pkg=app.packageName;
            row("remote-target:"+pkg,"apps",displayName(app),selected.equals(pkg)?"✓":"",index++,()->selectRemoteTarget(pkg)).setSelected(selected.equals(pkg));
        }
        if(!catalogLoaded)rowNote(tr("Загружаем приложения…","Loading applications…"));
        else if(index==1)rowNote(tr("Нет доступных приложений","No available applications"));
        if(!selected.isEmpty()&&!added.contains(selected))rowNote(remoteTargetLabel(selected));
    }
    private void selectRemoteTarget(String packageName){if(remotePreferences.setPackage(remoteEditingButton,packageName))goBack();}
    private void about() {
        beginRows(tr("О приложении","About"),"softlumi");rowNote("softlumi  "+appVersion());
        rowNote(tr("Самостоятельный Android TV лаунчер.\nБез телеметрии. Избранное и настройки хранятся на устройстве.\n\nПогода: Open-Meteo и MET Norway (CC BY 4.0). Города: GeoNames. Продолжение просмотра: доступные системные записи.\n\nШрифт: Inter Variable (SIL OFL 1.1).","An independent Android TV launcher.\nNo telemetry. Favorites and settings stay on the device.\n\nWeather: Open-Meteo and MET Norway (CC BY 4.0). Cities: GeoNames. Continue watching: available system entries.\n\nFont: Inter Variable (SIL OFL 1.1)."));
        row("about-weather","info","Open-Meteo ↗","",0,()->openUrl("https://open-meteo.com/"));row("about-met","info","MET Norway ↗","",1,()->openUrl("https://api.met.no/"));
    }
    private String tr(String ru,String en){return isRussian()?ru:en;}
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){return "";}}
    private String defaultFocusKey(){
        switch(screen){
            case HOME:return favoriteApps().isEmpty()?"favorite-add":"favorite:"+favoriteApps().get(0).id;
            case DRAWER:return "shortcut-apps";
            case SETTINGS:return "settings-appearance";
            case APPEARANCE:return "appearance-cards";
            case CARD_FORMAT:return posterCards()?"cards-poster":"cards-landscape";
            case WALLPAPER_DIM:return "dim-"+(Math.round(prefs.wallpaperDim/5f)*5);
            case GALLERY:return "background-"+WallpaperCatalog.pairId(prefs.background);
            case APP_OPTIONS:return "app-visibility";
            case RENAME_APP:return "app-name-input";
            case BACKUP:return "backup-export";
            case BACKUP_PREVIEW:return backupPreview!=null&&backupPreview.valid?"backup-apply":"page-back";
            case DAY_NIGHT:return "period-"+prefs.wallpaperPeriod;
            case DAY_START:return "schedule-"+prefs.dayStartMinutes;
            case NIGHT_START:return "schedule-"+prefs.nightStartMinutes;
            case MANAGE_APPS:return "manage-hidden";
            case WEATHER:return "weather-enabled";
            case CITY:return "city-query";
            case ADVANCED:return "advanced-language";
            case SOURCES:return "sources-enabled";
            case CHANNEL_PROBE:return "probe-run";
            case CHANNEL_PROBE_ITEM:return "probe-open";
            case LANGUAGE:return "language-"+prefs.language;
            case REMOTE:return "remote-enabled";
            case REMOTE_TARGETS:String remoteTarget=remotePreferences.read().packageFor(remoteEditingButton);return targets.containsKey("remote-target:"+remoteTarget)?"remote-target:"+remoteTarget:"remote-target-original";
            case ABOUT:return "about-weather";
            default:for(String key:targets.keySet())if(key.startsWith("app-"))return key;return "apps-back";
        }
    }
    private boolean isRussian(){return prefs.language.equals("ru")||prefs.language.equals("system")&&Locale.getDefault().getLanguage().equals("ru");}
    private String onOff(boolean value){return value?tr("Вкл.","On"):tr("Выкл.","Off");}
    private String defaultFavoriteFocus(){List<AppEntry> apps=favoriteApps();return apps.isEmpty()?"favorite-add":"favorite:"+apps.get(0).id;}
    private boolean posterCards(){return LauncherPrefs.CARD_FORMAT_POSTER.equals(prefs.cardFormat);}
    private String cardFormatName(){return cardFormatName(prefs.cardFormat);}
    private String cardFormatName(String format){return LauncherPrefs.CARD_FORMAT_POSTER.equals(format)?tr("Постеры","Posters"):tr("Горизонтальные","Horizontal");}
    private String formatDate(){return new SimpleDateFormat(isRussian()?"EEEE, d MMMM":"EEEE, MMMM d",isRussian()?new Locale("ru"):Locale.ENGLISH).format(new Date());}
    private boolean headerTextShadowEnabled(){return palette.light||Color.luminance(palette.ambientText)<.5f;}
    private void clock(int x,int y,int width,int height,float size){TextView view=text(board,DateFormat.getTimeInstance(DateFormat.SHORT,isRussian()?new Locale("ru"):Locale.UK).format(new Date()),x,y,width,Math.max(height,textHeight(size,1)),size,palette.ambientText,false);view.setTypeface(screen==Screen.HOME&&!largeText()?typography.clockRegular:typography.displayRegular);view.setTag("clock");view.setLetterSpacing(-.01f);view.setFontFeatureSettings("'tnum'");view.setSingleLine(true);if(screen==Screen.HOME)AmbientTextShadow.apply(view,headerTextShadowEnabled());liveClock.add(view);}
    private final Runnable clockTick=new Runnable(){@Override public void run(){
        if(!resumed||destroyed)return;
        String time=DateFormat.getTimeInstance(DateFormat.SHORT,isRussian()?new Locale("ru"):Locale.UK).format(new Date());
        for(TextView view:liveClock)view.setText(time);
        if(movingId==null&&DayNightPolicy.isNight(prefs,System.currentTimeMillis())!=renderedNight){renderPreservingFocus();main.postDelayed(this,60_000-(System.currentTimeMillis()%60_000));return;}
        boolean weatherScreen=screen==Screen.HOME||screen==Screen.DRAWER||screen==Screen.WEATHER;
        if(movingId==null&&weatherScreen
                &&(!formatDate().equals(lastRenderedDate)||System.currentTimeMillis()>=nextWeatherHour
                ||isWeatherStale()!=lastRenderedWeatherStale||weatherAvailable()!=lastRenderedWeatherAvailable))scheduleDataRender();
        if(weatherScreen&&prefs.weatherEnabled&&prefs.hasCity()&&System.currentTimeMillis()>=nextWeatherRequestAt)refreshData(false,canRefreshWeatherOnly());
        main.postDelayed(this,60_000-(System.currentTimeMillis()%60_000));
    }};

    /** Plain status typography on the scene; weather is information, never an extra D-pad stop. */
    private int compactWeather(FrameLayout parent,int x,int y,int width,boolean ambient){
        if(!prefs.weatherEnabled)return 0;
        int mainHeight=Math.max(textHeight(30,1),textHeight(21,1)),metaHeight=textHeight(14,1),height=mainHeight+metaHeight+6;
        FrameLayout status=new FrameLayout(this);status.setTag("weather-status");status.setClipChildren(true);status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);place(parent,status,x,y,width,height);
        fillCompactWeather(status,width,ambient);
        if(screen==Screen.HOME&&homeBindings!=null)homeBindings.weatherStatus.add(new WeatherStatusBinding(status,width,ambient));
        return height;
    }
    private void fillCompactWeather(FrameLayout status,int width,boolean ambient){
        int ink=ambient?palette.ambientText:palette.text,muted=ambient?palette.ambientMuted:palette.muted;
        int mainHeight=Math.max(textHeight(30,1),textHeight(21,1)),metaHeight=textHeight(14,1);
        status.setContentDescription(weatherLine()+" · "+weatherStatus());
        int temperatureWidth=textWidth(weatherTemperature(),30)+8,iconSize=30,cityWidth=Math.max(48,width-temperatureWidth-iconSize-24);
        text(status,prefs.cityName.isEmpty()?tr("Выберите город","Choose a city"):prefs.cityName,0,0,cityWidth,mainHeight,21,ink,true).setSingleLine(true);
        TextView temperature=text(status,weatherTemperature(),width-temperatureWidth-iconSize-12,0,temperatureWidth,mainHeight,30,ink,false);temperature.setSingleLine(true);temperature.setFontFeatureSettings("'tnum'");temperature.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        SymbolView symbol=new SymbolView(this,currentWeatherSymbol(),ink);symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);place(status,symbol,width-iconSize,(mainHeight-iconSize)/2,iconSize,iconSize);
        text(status,(weatherAvailable()?weatherCondition()+" · ":"")+weatherStatus(),0,mainHeight+2,width,metaHeight,14,muted,false).setSingleLine(true);
        if(screen==Screen.HOME&&ambient)for(int i=0;i<status.getChildCount();i++)if(status.getChildAt(i) instanceof TextView)AmbientTextShadow.apply((TextView)status.getChildAt(i),headerTextShadowEnabled());
    }
    private int weatherStrip(FrameLayout parent,int x,int y,int width,boolean ambient){
        if(!prefs.weatherEnabled)return 0;int ink=ambient?palette.ambientText:palette.text,muted=ambient?palette.ambientMuted:palette.muted;
        FrameLayout strip=new FrameLayout(this);strip.setTag("weather-forecast");place(parent,strip,x,y,width,1);int top=compactWeather(strip,0,0,width,ambient)+10;
        List<WeatherRepository.Hour> hours=new ArrayList<>();long now=System.currentTimeMillis();
        if(weatherAvailable())for(WeatherRepository.Hour hour:weather.weather.hours)if(hour.timeMillis>=now){hours.add(hour);nextWeatherHour=Math.min(nextWeatherHour,hour.timeMillis);if(hours.size()==6)break;}
        int columns=largeText()?3:6,cell=width/columns,timeHeight=textHeight(16,1),temperatureHeight=textHeight(22,1),rowHeight=timeHeight+temperatureHeight+8;
        for(int i=0;i<hours.size();i++){
            WeatherRepository.Hour hour=hours.get(i);int hx=(i%columns)*cell,hy=top+(i/columns)*rowHeight;
            text(strip,cityTime(hour.timeMillis),hx,hy,cell-12,timeHeight,16,muted,false).setSingleLine(true);
            String hourlySymbol=hour.isDay==null&&(hour.weatherCode==0||hour.weatherCode==1)?"clear-outline":weatherSymbol(hour.weatherCode,!Boolean.FALSE.equals(hour.isDay));
            SymbolView symbol=new SymbolView(this,hourlySymbol,ink);symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);place(strip,symbol,hx,hy+timeHeight+3,24,24);
            text(strip,(hour.temp>0?"+":"")+Math.round(hour.temp)+(weather.weather.units.equals("fahrenheit")?"°F":"°C"),hx+34,hy+timeHeight,cell-46,temperatureHeight,22,ink,false).setSingleLine(true);
        }
        top+=((hours.size()+columns-1)/columns)*rowHeight;
        if(weatherAvailable()){
            String sun="";if(weather.weather.sunriseMillis>0)sun=tr("Восход ","Sunrise ")+cityTime(weather.weather.sunriseMillis);if(weather.weather.sunsetMillis>0)sun+=(sun.isEmpty()?"":"   ·   ")+tr("Закат ","Sunset ")+cityTime(weather.weather.sunsetMillis);
            if(!sun.isEmpty()){text(strip,sun,0,top,width,textHeight(16,1),16,muted,false).setSingleLine(true);top+=textHeight(16,1)+4;}
        }
        ViewGroup.LayoutParams size=strip.getLayoutParams();size.height=top+4;strip.setLayoutParams(size);return top+4;
    }
    private String cityTime(long millis){
        SimpleDateFormat format=new SimpleDateFormat("HH:mm",Locale.ROOT);
        boolean known=weather!=null&&weather.weather!=null&&weather.weather.cityTimezoneKnown;
        format.setTimeZone(TimeZone.getTimeZone(known?weather.weather.timezone:"UTC"));
        return format.format(new Date(millis))+(known?"":" UTC");
    }
    private String weatherTemperature(){
        if(!prefs.weatherEnabled)return "—";
        if(!weatherAvailable())return "—";
        double temperature=weather.weather.temperature;
        return (temperature>0?"+":"")+Math.round(temperature)+(weather.weather.units.equals("fahrenheit")?"°F":"°C");
    }
    private String weatherLine(){
        if(!prefs.weatherEnabled)return "";
        return weatherTemperature()+"   "+(prefs.cityName.isEmpty()?tr("Выберите город","Choose a city"):prefs.cityName)+" · "+weatherCondition()+staleMarker();
    }
    private String staleMarker(){
        return isWeatherStale()?
                tr(" · сохранено "," · cached ")+cityTimestamp(weather.weather.fetchedAtMillis):"";
    }
    private String weatherCondition(){
        if(!prefs.weatherEnabled)return tr("Погода скрыта","Weather hidden");
        if(!weatherAvailable())return prefs.cityName.isEmpty()?tr("Выберите город","Choose a city"):tr("Данные недоступны","Data unavailable");
        int code=weather.weather.weatherCode;
        if(code==0)return tr("Ясно","Clear");if(code<=3)return tr("Облачно","Cloudy");
        if(code==45||code==48)return tr("Туман","Fog");if(code>=71&&code<=77||code==85||code==86)return tr("Снег","Snow");
        if(code>=95)return tr("Гроза","Thunderstorm");return tr("Дождь","Rain");
    }
    private boolean weatherAvailable(){return weather!=null&&weather.weather!=null&&WeatherRepository.cacheAge(weather.weather,System.currentTimeMillis())!=WeatherPolicy.CacheAge.EXPIRED;}
    private boolean isWeatherStale(){return weatherAvailable()&&(weather.status==WeatherRepository.Status.STALE||WeatherRepository.cacheAge(weather.weather,System.currentTimeMillis())==WeatherPolicy.CacheAge.STALE);}
    private String currentWeatherSymbol(){return !weatherAvailable()?"weather":weatherSymbol(weather.weather.weatherCode,weather.weather.isDay);}
    private static String weatherSymbol(int code,boolean day){
        if(code==0)return day?"sun":"moon";
        if(code<=3)return "weather";
        if(code==45||code==48)return "fog";
        if(code>=71&&code<=77||code==85||code==86)return "snow";
        if(code>=95)return "storm";
        return "rain";
    }
    private String weatherStatus(){
        if(!prefs.weatherEnabled)return tr("Погода выключена","Weather is off");
        if(prefs.cityName.isEmpty())return tr("Выберите город в настройках","Choose a city in settings");
        if(weather==null)return tr("Загрузка погоды…","Loading weather…");
        if(!weatherAvailable()){
            switch(weather.failure){
                case TIMEOUT:return tr("Сервис погоды не ответил. Повторим запрос автоматически.","Weather service timed out. Retrying automatically.");
                case RATE_LIMITED:return tr("Сервис погоды временно ограничил запросы. Повторим позже.","Weather service rate limit reached. Retrying later.");
                case HTTP:case INVALID_RESPONSE:return tr("Сервис погоды временно недоступен. Повторим запрос.","Weather service is temporarily unavailable. Retrying.");
                case NETWORK:return tr("Нет соединения с сервисом погоды. Проверьте интернет.","Cannot connect to the weather service. Check the internet connection.");
                default:return tr("Не удалось получить погоду. Нажмите «Обновить погоду».","Unable to load weather. Select Refresh weather.");
            }
        }
        return isWeatherStale()?tr("Сохранено ","Cached ")+cityTimestamp(weather.weather.fetchedAtMillis):tr("Обновлено ","Updated ")+cityTime(weather.weather.fetchedAtMillis);
    }
    private String cityTimestamp(long millis){
        SimpleDateFormat format=new SimpleDateFormat("dd.MM HH:mm",Locale.ROOT);
        boolean known=weather!=null&&weather.weather!=null&&weather.weather.cityTimezoneKnown;
        format.setTimeZone(TimeZone.getTimeZone(known?weather.weather.timezone:"UTC"));
        return format.format(new Date(millis))+(known?"":" UTC");
    }

    private void beginReorder(String id){
        if(!prefs.favoriteIds.contains(id))return;
        reorderIds=new ArrayList<>(prefs.favoriteIds);movingId=id;dropArmed=false;
        reorderReturn=screen==Screen.HOME?null:new Route(screen,focusKey());screen=Screen.HOME;
        requestedFocus="favorite:"+id;render();
        board.announceForAccessibility(tr("Режим перемещения. Стрелки меняют порядок, OK сохраняет, Назад отменяет.","Reorder mode. Arrows move, OK saves, Back cancels."));
    }
    private void moveReorder(int offset){
        int from=reorderIds.indexOf(movingId),to=Math.max(0,Math.min(reorderIds.size()-1,from+offset));
        if(from==to)return;
        reorderIds.remove(from);reorderIds.add(to,movingId);requestedFocus="favorite:"+movingId;
        List<AppEntry> visible=favoriteApps();for(int i=0;i<visible.size();i++){View tile=targets.get("favorite:"+visible.get(i).id);if(tile==null)continue;int[] point=favoritePosition(i);FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)tile.getLayoutParams();lp.leftMargin=point[0];lp.topMargin=point[1];tile.setLayoutParams(lp);tile.setTag("favorite-"+i);}board.post(this::wireFocus);
    }
    private int[] favoritePosition(int i){return largeText()?new int[]{32+(i%4)*286,homeFavoritesTop+(i/4)*184}:new int[]{68+i*146,550};}
    private void saveReorder(){
        if(reorderIds==null)return;
        String id=movingId;preferences.setFavorites(reorderIds);reorderIds=null;movingId=null;
        finishReorder(id);
    }
    private void finishReorder(String id){
        if(reorderReturn!=null){screen=reorderReturn.screen;requestedFocus=reorderReturn.focus;reorderReturn=null;}
        else requestedFocus="favorite:"+id;
        render();
    }
    private void cancelReorder(){
        String id=movingId;reorderIds=null;movingId=null;
        finishReorder(id);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int key=event.getKeyCode();boolean down=event.getAction()==KeyEvent.ACTION_DOWN;lastInputAt=android.os.SystemClock.uptimeMillis();keyHeld=down;lastKeyCode=key;
        boolean activate=key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER;
        View pressed=getCurrentFocus();if(pressed!=null)pressed=focusPresentation(pressed);
        if(activate&&pressed!=null)uiMotion.pressed(pressed,down);
        if(activate&&!down&&(event.getFlags()&KeyEvent.FLAG_CANCELED)!=0){if(pressed!=null)pressed.setPressed(false);dropArmed=false;return true;}
        if(down&&event.getRepeatCount()==0){focusInputGeneration++;pendingRestoredFocus="";pendingSourceEntry=null;}
        if(movingId!=null){
            if(key==KeyEvent.KEYCODE_BACK){if(!down){uiSounds.back();cancelReorder();}return true;}
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){
                if(down&&event.getRepeatCount()==0)dropArmed=true;
                if(!down&&dropArmed){uiSounds.select();saveReorder();}return true;
            }
            int offset=key==KeyEvent.KEYCODE_DPAD_LEFT?-1:key==KeyEvent.KEYCODE_DPAD_RIGHT?1:
                    key==KeyEvent.KEYCODE_DPAD_UP?-(largeText()?4:1):key==KeyEvent.KEYCODE_DPAD_DOWN?(largeText()?4:1):0;
            if(offset!=0){if(down){int before=reorderIds.indexOf(movingId);moveReorder(offset);if(before!=reorderIds.indexOf(movingId))uiSounds.move();}return true;}
        }
        View focused=getCurrentFocus();
        if(focused!=null&&down){
            int cardIndex=watchTiles.indexOf(focused);
            if(screen==Screen.HOME&&cardIndex>=0&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)){
                // HorizontalScrollView may reject offscreen targets or fall through at its edge.
                // The content row owns horizontal input, including repeated keys and both ends.
                int next=cardIndex+(key==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);
                if(next>=0&&next<watchTiles.size()){
                    boolean previousDispatch=dispatchingDirection;dispatchingDirection=true;
                    try{watchTiles.get(next).requestFocus();}finally{dispatchingDirection=previousDispatch;}
                }
                return true;
            }
            if(screen==Screen.HOME&&focusKey().startsWith("source:")&&key==KeyEvent.KEYCODE_DPAD_DOWN){
                if(event.getRepeatCount()==0)selectHomeSource(focusKey().substring(7),true);return true;
            }
            if(screen==Screen.HOME&&homeScroll!=null&&homeScroll.getScrollY()>0
                    &&key==KeyEvent.KEYCODE_DPAD_UP&&focused.getNextFocusUpId()==focused.getId()){
                homeScroll.smoothScrollToPosition(0);return true;
            }
            if(focused instanceof EditText&&(screen==Screen.CITY||screen==Screen.RENAME_APP)
                    &&(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)){
                // Single-line EditText consumes vertical arrows as editing keys. Let the explicit
                // TV focus graph handle these two directions; horizontal cursor/IME keys stay native.
                wireFocus();
                int targetId=key==KeyEvent.KEYCODE_DPAD_UP?focused.getNextFocusUpId():focused.getNextFocusDownId();
                View next=board.findViewById(targetId);
                if(next!=null&&next!=focused&&next.isShown()&&next.isEnabled()&&next.requestFocus()){
                    uiSounds.move();
                    ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focused.getWindowToken(),0);
                    return true;
                }
            }
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT&&focused.getNextFocusRightId()==focused.getId()
                    &&(screen==Screen.SETTINGS||screen==Screen.ADVANCED||screen==Screen.WEATHER||screen==Screen.LANGUAGE||screen==Screen.APPEARANCE)
                    &&!"page-back".equals(focusKey())){if(event.getRepeatCount()==0)focused.performClick();return true;}
            if(screen==Screen.HOME&&largeText()&&key==KeyEvent.KEYCODE_DPAD_DOWN&&focused.getNextFocusDownId()==focused.getId()){
                uiSounds.move();
                lastHomeFocus=focusKey();
                push(Screen.DRAWER);requestedFocus="shortcut-apps";
                board.post(()->{View first=targets.get("shortcut-apps");if(first!=null)first.requestFocus();});return true;
            }
            if(screen==Screen.DRAWER&&key==KeyEvent.KEYCODE_DPAD_UP&&focused.getNextFocusUpId()==focused.getId()){
                uiSounds.back();
                goBack();return true;
            }
        }
        boolean previousDispatch=dispatchingDirection;dispatchingDirection=down&&isDirection(key);
        try{return super.dispatchKeyEvent(event);}finally{dispatchingDirection=previousDispatch;}
    }

    private void systemSettings(String action){
        try{Intent intent=new Intent(action);startActivity(intent);}
        catch(ActivityNotFoundException|SecurityException e){
            if(!Settings.ACTION_SETTINGS.equals(action)){systemSettings(Settings.ACTION_SETTINGS);return;}
            toast(tr("Системные настройки недоступны","System settings unavailable"));
        }
    }
    private void openUrl(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException|SecurityException e){toast(tr("На устройстве нет браузера","No browser is available"));}}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}

    private FrameLayout action(String key,String title,String symbol,int x,int y,int width,int height,Runnable click){
        FrameLayout button=focusBox(key,x,y,width,height,false,click);
        if(symbol!=null&&!symbol.isEmpty()){
            SymbolView icon=new SymbolView(this,symbol,palette.text);place(button,icon,20,(height-30)/2,30,30);
            text(button,title,70,10,width-88,height-20,24,palette.text,true).setTypeface(typography.semibold);
        }else text(button,title,18,10,width-36,height-20,24,palette.text,true).setTypeface(typography.semibold);
        button.setContentDescription(title);return button;
    }
    private void actionIcon(String key,String symbol,String label,int x,int y,int width,int height,Runnable click){
        FrameLayout button=focusBox(key,x,y,width,height,false,click);
        SymbolView icon=new SymbolView(this,symbol,palette.text);place(button,icon,(width-30)/2,(height-30)/2,30,30);button.setContentDescription(label);
    }
    private void actionLink(String key,String title,int x,int y,int width,int height,Runnable click){
        float labelSize=Math.min(22,height*.6f);height=Math.max(height,textHeight(labelSize,1)+12);
        boolean back=key.equals("page-back")||key.equals("apps-back");
        if(back){Paint measure=new Paint();measure.setTypeface(typography.semibold);measure.setTextSize(textPixels(labelSize));width=Math.min(width,Math.max(130,(int)Math.ceil(measure.measureText(title))+50));}
        FrameLayout button=focusBox(key,x,y,width,height,false,click);
        button.setBackground(round(12,Color.TRANSPARENT,0,0));
        TextView label=text(button,title,back?24:12,4,width-(back?48:24),height-8,labelSize,palette.ambientMuted,true);
        label.setTypeface(typography.semibold);if(back)label.setGravity(Gravity.CENTER);
        button.setContentDescription(title);
    }
    private FrameLayout focusBox(String key,int x,int y,int width,int height,boolean poster,Runnable click){
        FrameLayout button=new FrameLayout(this);button.setClipChildren(false);button.setClipToPadding(false);
        register(button,key);configureFocus(button,poster);place(board,button,x,y,width,height);
        button.setOnClickListener(v->{
            if(isBackControl(key))uiSounds.back();else uiSounds.select();
            click.run();
        });return button;
    }
    private void configureFocus(FrameLayout view,boolean poster){
        view.setDefaultFocusHighlightEnabled(false);
        String key=focusKeys.getOrDefault(view,"");UiMotion.Style style=motionStyle(key);int radius=screen==Screen.HOME&&posterCards()&&!largeText()&&key.startsWith("watch-")?16:CARD_RADIUS;
        if("shortcut-weather".equals(key))return;
        clipRounded(view,radius);view.setClipChildren(true);view.setClipToPadding(true);
        boolean nested=settingsRows!=null||key.startsWith("city:");GlassScene source=glassScene;Palette colors=palette;
        boolean artwork=poster||style==UiMotion.Style.SHELF||style==UiMotion.Style.GRID;
        // Translucent controls must not claim an opaque native shadow caster:
        // its interior cutout can otherwise show through the highlighted face.
        if(!artwork)view.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View target,Outline outline){outline.setRoundRect(0,0,target.getWidth(),target.getHeight(),radius);outline.setAlpha(0f);}});
        ArtworkSheenDrawable optics=artwork?new ArtworkSheenDrawable(radius,colors.light,poster,true):ArtworkSheenDrawable.control(radius,colors.light);
        if(artwork){view.setElevation(poster?10f:6f);if(android.os.Build.VERSION.SDK_INT>=28){view.setOutlineAmbientShadowColor(colors.light?0x60000000:0xc0000000);view.setOutlineSpotShadowColor(colors.light?0xa0000000:0xff000000);}}
        // A neutral app face keeps the mark legible. The shared dock carries the live
        // glass; an extra optical frame around each logo creates nested cards.
        boolean appFace=style==UiMotion.Style.SHELF||style==UiMotion.Style.GRID&&key.startsWith("app-");
        // Bare Home content must not create a glass surface: it owns attach/pre-draw observers
        // even if its background is immediately removed by the caller.
        boolean bareHome=key.startsWith("source:")||screen==Screen.HOME&&posterCards()&&!largeText()&&key.startsWith("watch-");
        android.graphics.drawable.Drawable idleSurface=bareHome?null:appFace?round(radius,colors.light?0xfff2f2f4:0xff18181b,0,0):nested?round(radius,Color.TRANSPARENT,0,0):glassBackdrop.drawable(view,source,radius,colors.light,false);
        view.setBackground(idleSurface);view.setForeground(optics);view.setPadding(0,0,0,0);
        view.setOnFocusChangeListener((v,focused)->{
            if(!focused&&!isInside(v,root))return;
            uiMotion.focus(v,focused,style,focusScale(v,style));
            if(focused&&dispatchingDirection)uiSounds.move();
            if(focused&&screen==Screen.HOME&&!restoringPageFocus){
                lastHomeFocus=focusKey();
                if(key.startsWith("favorite:")||key.equals("favorite-add"))lastUpperFocus=key;
                if(isHomeSettingsShortcut(key)){lastSettingsShortcut=key;wireForecastFocus();}
            }
        });
    }
    private static UiMotion.Style motionStyle(String key){if(key.startsWith("favorite:"))return UiMotion.Style.SHELF;if(key.startsWith("watch-"))return UiMotion.Style.CARD;if(key.startsWith("background-")||key.startsWith("app-")&&key.indexOf('/')>=0)return UiMotion.Style.GRID;if(key.startsWith("shortcut-"))return UiMotion.Style.DRAWER;return UiMotion.Style.CONTROL;}
    private static float focusScale(View view,UiMotion.Style style){
        switch(style){
            case SHELF:return 1.20f;
            case CARD:return 1.05f;
            case DRAWER:return 1.07f;
            case GRID:return 1.06f;
            default:ViewGroup.LayoutParams size=view.getLayoutParams();return size!=null&&size.width>0&&size.width<=64&&size.height>0&&size.height<=64?1.10f:1.04f;
        }
    }
    private static boolean isDirection(int key){return key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT;}
    private static boolean isBackControl(String key){return key.equals("page-back")||key.equals("apps-back")||key.equals("remote-close");}
    private void tintChildren(ViewGroup parent,boolean focused,Palette colors,Map<View,Integer> childIdleTints){
        for(int i=0;i<parent.getChildCount();i++){
            View child=parent.getChildAt(i);
            if(!childIdleTints.containsKey(child))childIdleTints.put(child,idleTints.getOrDefault(child,child instanceof TextView?((TextView)child).getCurrentTextColor():colors.text));
            int color=focused?colors.focusText:childIdleTints.get(child);
            if(child instanceof TextView)((TextView)child).setTextColor(color);
            else if(child instanceof SymbolView)((SymbolView)child).setColor(color);
        }
    }
    private void register(View view,String key){
        Integer id=stableIds.get(key);if(id==null){id=View.generateViewId();stableIds.put(key,id);}
        view.setId(id);view.setTag(key);view.setFocusable(true);view.setFocusableInTouchMode(true);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);view.setSoundEffectsEnabled(false);
        if(!(view instanceof EditText))view.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");
            }
        });
        targets.put(key,view);
        focusKeys.put(view,key);
    }
    private TextView text(FrameLayout parent,String value,int x,int y,int width,int height,float size,int color,boolean medium){
        height=Math.max(height,textHeight(size,1));
        TextView label=new TextView(this);label.setText(value==null?"":value);label.setTextColor(color);
        label.getPaint().setSubpixelText(true);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX,textPixels(size));
        label.setTypeface(textFace(size,medium));label.setLetterSpacing(size>=32?.005f:0);
        label.setGravity(Gravity.CENTER_VERTICAL);label.setIncludeFontPadding(true);label.setPadding(0,2,0,2);label.setMaxLines(height>=textHeight(size,2)?2:1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setImportantForAccessibility(parent==board?View.IMPORTANT_FOR_ACCESSIBILITY_YES:View.IMPORTANT_FOR_ACCESSIBILITY_NO);label.setClipBounds(new android.graphics.Rect(0,0,width,height));label.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->view.setClipBounds(new android.graphics.Rect(0,0,r-l,b-t)));idleTints.put(label,color);place(parent,label,x,y,width,height);return label;
    }
    private View panel(FrameLayout parent,int x,int y,int width,int height,int radius,int color){
        return panel(parent,x,y,width,height,radius,color,width>=220&&height>=80);
    }
    private View panel(FrameLayout parent,int x,int y,int width,int height,int radius,int color,boolean glass){
        if(glass){SurfaceShadowView shadow=new SurfaceShadowView(this,radius,14f,palette.light?0x14:0x24);int halo=shadow.halo();place(parent,shadow,x-halo,y-halo+4,width+halo*2,height+halo*2);}
        View view=new View(this);view.setBackground(glass?glassBackdrop.drawable(view,glassScene,radius,palette.light,true):round(radius,color,0,0));clipRounded(view,radius);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);place(parent,view,x,y,width,height);return view;
    }
    private static void clipRounded(View view,int radius){view.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View target,Outline outline){outline.setRoundRect(0,0,target.getWidth(),target.getHeight(),radius);}});view.setClipToOutline(true);}
    private float designScale(){return designRoot==null?1:designRoot.getScaleX();}
    private static GradientDrawable round(int radius,int fill,int border,int stroke){GradientDrawable drawable=new GradientDrawable();drawable.setColor(fill);drawable.setCornerRadius(radius);if(stroke>0)drawable.setStroke(stroke,border);return drawable;}
    private static void place(FrameLayout parent,View child,int x,int y,int width,int height){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.max(1,width),Math.max(1,height));p.leftMargin=x;p.topMargin=y;parent.addView(child,p);}

    /** Explicit nearest-neighbor graph. Stable object keys survive palette and provider updates. */
    private void wireFocus(){
        if(destroyed||board==null)return;
        if((screen==Screen.ALL_APPS||screen==Screen.FAVORITES||screen==Screen.MANAGE_APPS)&&!catalogGrid.isEmpty()){wireCatalogFocus();return;}
        List<View> views=new ArrayList<>(targets.values());List<View> outside=new ArrayList<>(views);outside.removeAll(watchTiles);View selected=selectedWatchTile();List<View> candidates=new ArrayList<>(outside);if(selected!=null)candidates.add(selected);
        Map<View,float[]> centers=new IdentityHashMap<>();
        int[] position=new int[2];float sx=designScale(),sy=designScale();
        int[] railPosition=new int[2];if(watchRail!=null)watchRail.getLocationOnScreen(railPosition);
        for(View view:views){view.getLocationOnScreen(position);float cx=position[0]+view.getWidth()*view.getScaleX()*sx/2f;if(watchTiles.contains(view)&&watchRail!=null)cx=Math.max(railPosition[0]+32*sx,Math.min(railPosition[0]+(watchRail.getWidth()-32)*sx,cx));centers.put(view,new float[]{cx,position[1]+view.getHeight()*view.getScaleY()*sy/2f});}
        for(View view:views){
            int watchIndex=watchTiles.indexOf(view);
            if(watchIndex>=0){view.setNextFocusLeftId(watchIndex==0?view.getId():watchTiles.get(watchIndex-1).getId());view.setNextFocusRightId(watchIndex+1==watchTiles.size()?view.getId():watchTiles.get(watchIndex+1).getId());view.setNextFocusUpId(neighbor(view,outside,centers,0,-1));view.setNextFocusDownId(neighbor(view,outside,centers,0,1));}
            else{view.setNextFocusLeftId(neighbor(view,candidates,centers,-1,0));view.setNextFocusRightId(neighbor(view,candidates,centers,1,0));view.setNextFocusUpId(neighbor(view,candidates,centers,0,-1));view.setNextFocusDownId(neighbor(view,candidates,centers,0,1));
                if(selected!=null&&watchRail!=null){float[] point=centers.get(view),row=centers.get(selected);boolean overlaps=point[0]>=railPosition[0]&&point[0]<=railPosition[0]+watchRail.getWidth()*sx;
                    if(overlaps){View up=board.findViewById(view.getNextFocusUpId()),down=board.findViewById(view.getNextFocusDownId());if(row[1]<point[1]-2&&(up==view||centers.get(up)[1]<row[1]))view.setNextFocusUpId(selected.getId());if(row[1]>point[1]+2&&(down==view||centers.get(down)[1]>row[1]))view.setNextFocusDownId(selected.getId());}}
            }
        }
        wireForecastFocus();wireSourceFocus();
    }
    private static boolean isHomeSettingsShortcut(String key){
        return "shortcut-apps".equals(key)||"shortcut-wifi".equals(key)||"shortcut-bluetooth".equals(key)
                ||"shortcut-wallpapers".equals(key)||"shortcut-launcher".equals(key)||"shortcut-tv".equals(key);
    }
    private void wireForecastFocus(){
        if(screen!=Screen.HOME||largeText())return;
        List<View> favorites=new ArrayList<>();
        for(Map.Entry<String,View> entry:targets.entrySet())if(entry.getKey().startsWith("favorite:")||entry.getKey().equals("favorite-add"))favorites.add(entry.getValue());
        View destination=targets.get(lastSettingsShortcut);if(destination==null)destination=targets.get("shortcut-launcher");
        for(int i=0;i<favorites.size();i++){
            View favorite=favorites.get(i);
            favorite.setNextFocusLeftId(favorites.get(Math.max(0,i-1)).getId());
            favorite.setNextFocusRightId(favorites.get(Math.min(favorites.size()-1,i+1)).getId());
            if(destination!=null)favorite.setNextFocusDownId(destination.getId());
        }
        View upper=targets.get(lastUpperFocus);if(upper==null)upper=targets.get(defaultFavoriteFocus());if(upper==null&&!favorites.isEmpty())upper=favorites.get(0);
        for(Map.Entry<String,View> entry:targets.entrySet())if(isHomeSettingsShortcut(entry.getKey())){
            View shortcut=entry.getValue();shortcut.setNextFocusDownId(shortcut.getId());
            if(upper!=null)shortcut.setNextFocusUpId(upper.getId());
        }
    }
    private void wireCatalogFocus(){
        View back=targets.get("apps-back"),filter=targets.get("manage-hidden");View header=filter==null?back:filter;
        for(int i=0;i<catalogGrid.size();i++){View cell=catalogGrid.get(i);cell.setNextFocusLeftId(i%catalogColumns==0?cell.getId():catalogGrid.get(i-1).getId());cell.setNextFocusRightId(i%catalogColumns==catalogColumns-1||i+1==catalogGrid.size()?cell.getId():catalogGrid.get(i+1).getId());cell.setNextFocusUpId(i<catalogColumns?header.getId():catalogGrid.get(i-catalogColumns).getId());cell.setNextFocusDownId(i+catalogColumns<catalogGrid.size()?catalogGrid.get(i+catalogColumns).getId():cell.getId());}
        if(back!=null){back.setNextFocusDownId(header==back?catalogGrid.get(0).getId():header.getId());back.setNextFocusUpId(back.getId());back.setNextFocusLeftId(back.getId());back.setNextFocusRightId(back.getId());}
        if(filter!=null){filter.setNextFocusDownId(catalogGrid.get(0).getId());filter.setNextFocusUpId(back.getId());filter.setNextFocusLeftId(filter.getId());filter.setNextFocusRightId(filter.getId());}
        View recovery=targets.get("favorites-remove-missing");if(recovery!=null){recovery.setNextFocusUpId(back.getId());recovery.setNextFocusDownId(catalogGrid.get(0).getId());back.setNextFocusDownId(recovery.getId());for(int i=0;i<Math.min(catalogColumns,catalogGrid.size());i++)catalogGrid.get(i).setNextFocusUpId(recovery.getId());}
    }
    private int neighbor(View from,List<View> choices,Map<View,float[]> centers,int dx,int dy){
        float[] origin=centers.get(from);float cx=origin[0],cy=origin[1];
        float best=Float.MAX_VALUE;View chosen=from;
        for(View candidate:choices){
            if(candidate==from||!candidate.isShown()||!candidate.isEnabled())continue;
            float[] position=centers.get(candidate);
            float ox=position[0]-cx,oy=position[1]-cy;
            float primary=dx==0?oy*dy:ox*dx,perpendicular=Math.abs(dx==0?ox:oy);
            if(primary<=2)continue;
            if(screen==Screen.HOME&&!watchTiles.contains(from)&&perpendicular>Math.max(60,primary*1.5f))continue;
            float score=primary+perpendicular*2.7f;
            if(score<best){best=score;chosen=candidate;}
        }return chosen.getId();
    }

    private void loadIcon(ImageView target,AppEntry app){pendingIcons.put(target,app);iconBindings.put(target,app);}
    private void loadVisibleIcons(){
        if(destroyed||!resumed||root==null)return;
        loadVisibleWallpapers();
        float scale=designScale();
        android.graphics.Rect visible=new android.graphics.Rect();
        for(Map.Entry<ImageView,AppEntry> entry:new ArrayList<>(pendingIcons.entrySet())){
            ImageView target=entry.getKey();
            if(!target.isShown()||!target.getGlobalVisibleRect(visible)||visible.width()<4||visible.height()<4)continue;
            int width=Math.max(1,(int)Math.ceil(target.getWidth()*scale*1.20f));
            int height=Math.max(1,(int)Math.ceil(target.getHeight()*scale*1.20f));
            imageLoader.icon(target,entry.getValue(),width,height,renderGeneration,palette.light);pendingIcons.remove(target);
        }
        for(ImageView target:new ArrayList<>(pendingLogos)){
            if(!target.isShown()||!target.getGlobalVisibleRect(visible)||target.getWidth()==0)continue;
            String packageName=providerLogos.get(target),artworkVersion="";
            for(AppEntry app:catalog)if(app.packageName.equals(packageName)){artworkVersion=app.artworkVersion;break;}
            imageLoader.logo(target,packageName,artworkVersion,Math.round(target.getWidth()*scale),Math.round(target.getHeight()*scale),renderGeneration,palette.light);pendingLogos.remove(target);
        }
        int[] point=new int[2];android.graphics.Rect viewport=new android.graphics.Rect(),window=new android.graphics.Rect(),bounds=new android.graphics.Rect();
        // All bound content posters belong to this one rail. Its visible window is
        // identical for every card; avoid a full ancestor/visibility walk per poster.
        boolean railVisible=watchRail!=null&&watchRail.getGlobalVisibleRect(viewport);
        if(railVisible){window.set(viewport);int margin=Math.round(watchRail.getWidth()*scale);window.inset(-margin,0);}
        for(Map.Entry<ImageView,PosterBinding> entry:posterBindings.entrySet()){
            ImageView target=entry.getKey();
            boolean near,onScreen;
            if(watchRail==null)onScreen=near=target.isShown()&&target.getGlobalVisibleRect(bounds);
            else if(!railVisible)onScreen=near=false;
            else{target.getLocationOnScreen(point);bounds.set(point[0],point[1],point[0]+Math.round(target.getWidth()*scale*1.06f),point[1]+Math.round(target.getHeight()*scale*1.06f));near=android.graphics.Rect.intersects(window,bounds);onScreen=android.graphics.Rect.intersects(viewport,bounds);}
            if(!near){if(target.getDrawable()!=null||!pendingPosters.contains(target)){imageLoader.forget(target);pendingPosters.add(target);}setPosterPlaceholderVisible(target,false);continue;}
            if(target.getDrawable()!=null){pendingPosters.remove(target);setPosterPlaceholderVisible(target,false);continue;}
            if(pendingPosters.remove(target)){PosterBinding binding=entry.getValue();imageLoader.poster(target,binding.uri,Math.round(binding.width*scale),Math.round(binding.height*scale),renderGeneration);}
            // Keep image prefetch independent from presentation. Inline cache hits
            // and empty-URI forget() may change the placeholder during poster().
            boolean placeholderVisible=onScreen&&target.getDrawable()==null&&target.getGlobalVisibleRect(visible)
                    &&(watchRail==null||android.graphics.Rect.intersects(viewport,visible));
            setPosterPlaceholderVisible(target,placeholderVisible);
        }
    }
    private void loadPoster(ImageView target,String uri,int width,int height){
        // Newly created cards start hidden until their first visibility pass.
        // A laid-out retained Home already has the current placeholder state.
        if(!target.isLaidOut())setPosterPlaceholderVisible(target,false);
        posterBindings.put(target,new PosterBinding(uri==null?"":uri,width,height));
        pendingPosters.add(target);
    }
    private static void setPosterPlaceholderVisible(ImageView target,boolean visible){
        if(!(target.getTag() instanceof View))return;
        View placeholder=(View)target.getTag();
        if(visible){if(placeholder.getVisibility()!=View.VISIBLE)placeholder.setVisibility(View.VISIBLE);}
        else if(placeholder.getVisibility()==View.VISIBLE)placeholder.setVisibility(View.INVISIBLE);
    }
    /** Read-only instrumentation hook; semantic tags are also exposed on each focusable View. */
    public String currentScreenForTest(){return screen.name();}
}
