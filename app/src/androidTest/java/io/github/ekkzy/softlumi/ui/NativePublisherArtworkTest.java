package io.github.ekkzy.softlumi.ui;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Opt-in visual evidence from local, uninstalled APK resources. No package is
 * installed, no publisher class loader is created and no publisher code runs.
 */
@RunWith(AndroidJUnit4.class)
public final class NativePublisherArtworkTest {
    private static final int WIDTH = 384, HEIGHT = 216;
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String SENDFILES = "com.yablio.sendfilestotv";
    private static final String VIMU = "net.gtvbox.vimuhd";
    private static final String IVI = "ru.ivi.client";

    @Test public void realUninstalledApplicationIconsRenderInBothPalettes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixtureRoot = fixtureRoot(context);
        StringBuilder evidence = new StringBuilder("Resource-only preview; no publisher package installed or launched.\n");
        LoadedIcon youtube = load(context, fixtureRoot, YOUTUBE, "mipmap/ic_launcher", 1, false, evidence);
        LoadedIcon sendfiles = load(context, fixtureRoot, SENDFILES, "mipmap/ic_launcher_adaptative", 3, false, evidence);
        assertFalse("The selected YouTube source is its legacy play icon", youtube.drawable instanceof AdaptiveIconDrawable);
        assertTrue("The selected Send Files source retains its two native adaptive layers",
                sendfiles.drawable instanceof AdaptiveIconDrawable);

        File folder = outputFolder(context);
        Bitmap[][] cards = new Bitmap[2][2];
        try {
            LoadedIcon[] sources = {youtube, sendfiles};
            String[] names = {"youtube", "sendfiles"};
            for (int app = 0; app < sources.length; app++) {
                AppArtworkCatalog.Entry identity = AppArtworkCatalog.find(sources[app].packageName);
                assertNotNull(identity);
                assertTrue("This exact profile selects application icon ahead of a wordmark banner",
                        identity.prefersApplicationIcon());
                assertFalse("Publisher pixels are not shipped as a softlumi asset", identity.hasCuratedArtwork());
                for (int palette = 0; palette < 2; palette++) {
                    boolean light = palette == 1;
                    Bitmap card = AppIconRenderer.nativeCard(sources[app].drawable, WIDTH, HEIGHT, light, identity);
                    cards[app][palette] = card;
                    assertEquals(WIDTH, card.getWidth());
                    assertEquals(HEIGHT, card.getHeight());
                    AppArtworkCatalogTest.assertTransparentLogo(sources[app].packageName, card, light);
                    if (app == 0) assertYouTubePlay(card);
                    else assertCompleteTwoToneIdentity(card);
                    save(card, new File(folder, "native-publisher-" + names[app] + "-"
                            + (light ? "light" : "dark") + ".png"));
                }
                assertTrue("Host palette must not recolor either native publisher identity",
                        cards[app][0].sameAs(cards[app][1]));
            }
            Bitmap sheet = contactSheet(cards, "YouTube " + youtube.version, "Send Files " + sendfiles.version);
            try { save(sheet, new File(folder, "native-publisher-contact-sheet.png")); }
            finally { sheet.recycle(); }
            saveTargetPixelPreviews(folder, youtube, sendfiles);
            saveEvidence(evidence, new File(folder, "native-publisher-sources.txt"));
        } finally {
            for (Bitmap[] app : cards) for (Bitmap card : app) if (card != null) card.recycle();
        }
    }

    @Test public void realUninstalledVimuAndIviNativeArtworkRenderAtTargetPixels() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixtureRoot = fixtureRoot(context);
        StringBuilder evidence = new StringBuilder("Resource-only native artwork preview; no publisher code loaded.\n");
        LoadedIcon vimu = load(context, fixtureRoot, VIMU, "mipmap/ic_launcher", 2, false, evidence);
        LoadedIcon vimuBanner = load(context, fixtureRoot, VIMU, "drawable/banner", 2, true, evidence);
        LoadedIcon ivi = load(context, fixtureRoot, IVI, "drawable/banner_tv_launcher", 2, true, evidence);
        assertTrue("Vimu selects its native adaptive foreground", vimu.drawable instanceof AdaptiveIconDrawable);
        Drawable denseForeground = ((AdaptiveIconDrawable) vimu.drawable).getForeground();
        assertTrue("Its original dense foreground remains a publisher bitmap", denseForeground instanceof BitmapDrawable);
        assertEquals("The selected native foreground must be the 432px xxxhdpi resource",
                432, ((BitmapDrawable) denseForeground).getBitmap().getWidth());
        assertTrue("The old Vimu banner remains available for a guarded fallback", vimuBanner.drawable instanceof BitmapDrawable);
        Bitmap vimuBitmap = ((BitmapDrawable) vimuBanner.drawable).getBitmap();
        assertEquals("Android density scaling must preserve the 320:180 publisher aspect",
                vimuBitmap.getWidth() * 9, vimuBitmap.getHeight() * 16);
        assertTrue("The installed Ivi TV banner remains a native vector", ivi.drawable instanceof VectorDrawable);
        File folder = outputFolder(context);
        LoadedIcon[] sources = {vimu, ivi};
        String[] names = {"vimu", "ivi"};
        for (int[] size : new int[][]{{165,120},{198,144},{330,240},{396,288}}) {
            Bitmap[][] cards = new Bitmap[2][2];
            try {
                for (int app = 0; app < sources.length; app++) {
                    AppArtworkCatalog.Entry identity = AppArtworkCatalog.find(sources[app].packageName);
                    assertNotNull(identity);
                    assertEquals("Only Vimu selects the reviewed dense foreground instead of its matte banner",
                            app == 0, identity.prefersApplicationIcon());
                    assertFalse("No publisher asset is added to softlumi", identity.hasCuratedArtwork());
                    for (int palette = 0; palette < 2; palette++) {
                        Bitmap card = AppIconRenderer.nativeCard(sources[app].drawable, size[0], size[1], palette == 1, identity);
                        cards[app][palette] = card;
                        AppArtworkCatalogTest.assertTransparentLogo(sources[app].packageName, card, palette == 1);
                        Rect bounds = inkBounds(card);
                        assertFalse("The real publisher identity must remain visible", bounds.isEmpty());
                        assertTrue("A banner must keep its wide original aspect", bounds.width() > bounds.height());
                        int brandPixels = 0;
                        for (int y = bounds.top; y < bounds.bottom; y++) for (int x = bounds.left; x < bounds.right; x++) {
                            int pixel = card.getPixel(x, y);
                            if (Color.alpha(pixel) >= 240 && Color.red(pixel) > 180
                                    && Color.green(pixel) < 180 && Color.blue(pixel) < 180) brandPixels++;
                        }
                        assertTrue("Its original warm brand colors must survive", brandPixels > 80);
                        if (app == 0) {
                            int partial = 0;
                            for (int y = bounds.top; y < bounds.bottom; y++) for (int x = bounds.left; x < bounds.right; x++) {
                                int pixel = card.getPixel(x, y);
                                if (Color.alpha(pixel) > 8) assertEquals("Recovered native Vimu gradient cannot keep white/green matte blue", 0, Color.blue(pixel));
                                if (Color.alpha(pixel) > 0 && Color.alpha(pixel) < 255) partial++;
                            }
                            assertTrue("The dense native gradient retains a soft transparent contour", partial > 20);
                        }
                        save(card, new File(folder, "native-publisher-" + names[app] + "-" + size[0] + "x" + size[1]
                                + "-" + (palette == 1 ? "light" : "dark") + ".png"));
                    }
                }
                Bitmap sheet = contactSheet(cards, "Vimu " + vimu.version, "Ivi " + ivi.version);
                try { save(sheet, new File(folder, "native-vimu-ivi-" + size[0] + "x" + size[1] + "-contact-sheet.png")); }
                finally { sheet.recycle(); }
                saveVimuSourceComparison(folder, size, vimuBanner, cards[0]);
            } finally {
                for (Bitmap[] app : cards) for (Bitmap card : app) if (card != null) card.recycle();
            }
        }
        saveEvidence(evidence, new File(folder, "native-vimu-ivi-sources.txt"));
    }

    private static void saveVimuSourceComparison(File folder, int[] size, LoadedIcon banner, Bitmap[] foregroundCards) throws Exception {
        Bitmap[][] comparison = new Bitmap[2][2];
        comparison[1] = foregroundCards; // Borrow the already rendered production source.
        try {
            for (int palette = 0; palette < 2; palette++) {
                comparison[0][palette] = AppIconRenderer.nativeCard(banner.drawable, size[0], size[1], palette == 1);
                save(comparison[0][palette], new File(folder, "native-vimu-banner-" + size[0] + "x" + size[1]
                        + "-" + (palette == 1 ? "light" : "dark") + ".png"));
            }
            Bitmap sheet = contactSheet(comparison, "Vimu banner", "Vimu dense foreground");
            try { save(sheet, new File(folder, "native-vimu-source-" + size[0] + "x" + size[1] + "-contact-sheet.png")); }
            finally { sheet.recycle(); }
        } finally {
            for (Bitmap card : comparison[0]) if (card != null) card.recycle();
        }
    }

    private static File fixtureRoot(Context context) throws Exception {
        assumeTrue("Local publisher resources require explicit opt-in", Boolean.parseBoolean(
                InstrumentationRegistry.getArguments().getString("softlumiNativePublisherFixtures", "false")));
        assertEquals("Private fixtures belong only on the own debug emulator",
                "io.github.ekkzy.softlumi.debug", context.getPackageName());
        assertEquals("This resource preview is scoped to the own API 34 emulator", 34, Build.VERSION.SDK_INT);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Never inspect these fixtures on a physical device", "1",
                device.executeShellCommand("getprop ro.kernel.qemu").trim());

        File external = context.getExternalFilesDir(null);
        assertNotNull("The debug app needs external-files storage for local fixtures", external);
        File fixtureRoot = new File(external, "publisher-fixtures");
        assertTrue("Push the exact publisher base/split APK files into " + fixtureRoot, fixtureRoot.isDirectory());
        return fixtureRoot;
    }

    private static File outputFolder(Context context) {
        String run = InstrumentationRegistry.getArguments().getString("softlumiScreenshotRun", "screenshots");
        assertTrue("Screenshot run is a directory name", run.matches("[A-Za-z0-9_-]+"));
        File folder = new File(context.getExternalFilesDir(null), run);
        assertTrue("Create the resource-preview output directory", folder.isDirectory() || folder.mkdirs());
        return folder;
    }

    private static LoadedIcon load(Context context, File fixtureRoot, String expectedPackage,
            String expectedResourceName, int splitCount, boolean banner, StringBuilder evidence) throws Exception {
        PackageManager packages = context.getPackageManager();
        try {
            packages.getApplicationInfo(expectedPackage, 0);
            fail("Publisher package must remain uninstalled: " + expectedPackage);
        } catch (PackageManager.NameNotFoundException expected) {
            // Only resource archives below are read; there is no installation step.
        }
        File base = fixture(fixtureRoot, expectedPackage + ".apk");
        PackageInfo archive = packages.getPackageArchiveInfo(base.getAbsolutePath(), 0);
        assertNotNull("PackageManager must parse the local base archive: " + base.getName(), archive);
        assertEquals("A similarly named archive cannot substitute publisher identity", expectedPackage, archive.packageName);
        ApplicationInfo info = archive.applicationInfo;
        assertNotNull("Archive must declare an application resource owner", info);
        assertEquals(expectedPackage, info.packageName);
        assertTrue("Application must declare its own icon resource", info.icon != 0);
        assertNotEquals("The application icon is distinct from the text-bearing TV banner", info.banner, info.icon);
        info.sourceDir = base.getAbsolutePath();
        info.publicSourceDir = info.sourceDir;
        String[] splits = new String[splitCount];
        for (int i = 0; i < splitCount; i++) splits[i] = fixture(fixtureRoot,
                expectedPackage + "-" + (i + 1) + ".apk").getAbsolutePath();
        info.splitSourceDirs = splits;
        info.splitPublicSourceDirs = splits.clone();

        // This API opens AssetManager/Resources for the declared archives. It
        // does not create a package Context, load a DEX file or start an Activity.
        Resources resources = packages.getResourcesForApplication(info);
        int resourceId = banner ? info.banner : info.icon;
        assertTrue("The selected application resource must be declared", resourceId != 0);
        assertEquals(expectedPackage + ":" + expectedResourceName, resources.getResourceName(resourceId));
        Drawable icon = AppIconRenderer.resourceArtwork(packages, info, resourceId);
        assertNotNull("Android must inflate the publisher drawable from resource archives", icon);
        if (YOUTUBE.equals(expectedPackage)) {
            assertTrue("YouTube source remains the publisher bitmap", icon instanceof BitmapDrawable);
            assertEquals("The renderer must choose the 640dpi 320px source, not the TV's 160px variant",
                    320, ((BitmapDrawable) icon).getBitmap().getWidth());
        }
        if (VIMU.equals(expectedPackage) && banner) {
            BitmapFactory.Options rawBounds = new BitmapFactory.Options();
            rawBounds.inJustDecodeBounds = true;
            rawBounds.inScaled = false;
            try (InputStream stream = resources.openRawResource(resourceId)) {
                BitmapFactory.decodeStream(stream, null, rawBounds);
            }
            assertEquals("The source archive has a 320px native banner, independent of display density",
                    320, rawBounds.outWidth);
            assertEquals(180, rawBounds.outHeight);
        }
        evidence.append(expectedPackage).append(" version ").append(archive.versionName)
                .append("; resource ").append(resources.getResourceName(resourceId))
                .append("; drawable ").append(icon.getClass().getSimpleName())
                .append("; base ").append(base.getName()).append("; splits ").append(splitCount).append('\n');
        if (icon instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) icon).getBitmap();
            evidence.append("  Android bitmap ").append(bitmap.getWidth()).append('x').append(bitmap.getHeight())
                    .append("; bitmap density ").append(bitmap.getDensity()).append('\n');
        }
        return new LoadedIcon(expectedPackage, archive.versionName, icon);
    }

    private static File fixture(File root, String name) throws Exception {
        File file = new File(root, name);
        assertEquals("Fixture stays in the explicitly named local folder", root.getCanonicalFile(),
                file.getCanonicalFile().getParentFile());
        assertTrue("Missing resource fixture: " + name, file.isFile() && file.canRead());
        assertTrue("Fixture archive size must be bounded: " + name, file.length() > 0 && file.length() <= 128L * 1024 * 1024);
        return file;
    }

    private static void assertYouTubePlay(Bitmap card) {
        Rect bounds = inkBounds(card);
        assertFalse("Play glyph remains visible after removing its white outer field", bounds.isEmpty());
        float aspect = bounds.width() / (float) bounds.height();
        assertTrue("The chosen source is a standalone wide play mark, without square plate or wordmark",
                aspect > 1.32f && aspect < 1.60f);
        assertTrue("The play mark fits the common height envelope", bounds.height() <= glyphMaxHeight(card) + 2);
        assertColor(card, bounds, .15f, .5f, 0xffff0000, "The actual red play body remains intact");
        assertColor(card, bounds, .5f, .5f, Color.WHITE, "The enclosed white play triangle is retained");
        assertTrue("A substantial publisher red body survives", countNear(card, 0xffff0000) > Math.max(100, card.getWidth() * card.getHeight() / 100));
        assertTrue("The source's white triangle is retained, rather than cut out", countNear(card, Color.WHITE) > Math.max(20, card.getWidth() * card.getHeight() / 500));
        int redCoverage = 0, contaminatedOuterPixels = 0;
        for (int y = bounds.top; y < bounds.bottom; y++) for (int x = bounds.left; x < bounds.right; x++) {
            if (x > bounds.left + bounds.width() / 4 && x < bounds.right - bounds.width() / 4
                    && y > bounds.top + bounds.height() / 4 && y < bounds.bottom - bounds.height() / 4) continue;
            int pixel = card.getPixel(x, y);
            if (Color.alpha(pixel) > 8 && Color.alpha(pixel) < 250 && Color.red(pixel) > 240) redCoverage++;
            if (Color.alpha(pixel) > 20 && Color.red(pixel) > 240 && Color.green(pixel) > 12) contaminatedOuterPixels++;
        }
        assertTrue("The native red contour keeps smooth partial coverage", redCoverage > 15);
        assertEquals("The real publisher play edge must no longer contain the white matte fringe", 0, contaminatedOuterPixels);
    }

    private static void assertCompleteTwoToneIdentity(Bitmap card) {
        Rect bounds = inkBounds(card);
        assertFalse(bounds.isEmpty());
        assertTrue("Full adaptive geometry is not stretched into a wide banner", Math.abs(bounds.width() - bounds.height()) <= 2);
        assertTrue("The complete native icon shares the common height envelope", bounds.height() <= glyphMaxHeight(card) + 2);
        assertColor(card, bounds, .25f, .25f, 0xff00dae2, "The original cyan upper region is retained");
        assertColor(card, bounds, .5f, .345f, 0xff212355, "The upper arrow counter exposes its actual purple background");
        assertColor(card, bounds, .47f, .75f, 0xff00dae2, "The complete lower cyan arrow remains visible");
        assertColor(card, bounds, .72f, .72f, 0xff212355, "The lower field keeps its original purple");
        int substantial = Math.max(100, card.getWidth() * card.getHeight() / 180);
        assertTrue("Both original color layers survive", countNear(card, 0xff00dae2) > substantial && countNear(card, 0xff212355) > substantial);
    }

    private static int glyphMaxHeight(Bitmap card) {
        return Math.round(Math.min(card.getHeight(), card.getWidth() * 9f / 16f) * .56f);
    }

    private static void saveTargetPixelPreviews(File folder, LoadedIcon youtube, LoadedIcon sendfiles) throws Exception {
        LoadedIcon[] sources = {youtube, sendfiles};
        String[] names = {"youtube", "sendfiles"};
        // Physical 1080p and 4K viewports, each with and without the 1.20 focus headroom.
        for (int[] size : new int[][]{{165,120},{198,144},{330,240},{396,288}}) {
            Bitmap[][] cards = new Bitmap[2][2];
            try {
                for (int app = 0; app < sources.length; app++) for (int palette = 0; palette < 2; palette++) {
                    Bitmap card = AppIconRenderer.nativeCard(sources[app].drawable, size[0], size[1], palette == 1,
                            AppArtworkCatalog.find(sources[app].packageName));
                    cards[app][palette] = card;
                    AppArtworkCatalogTest.assertTransparentLogo(sources[app].packageName, card, palette == 1);
                    if (app == 0) assertYouTubePlay(card);
                    else assertCompleteTwoToneIdentity(card);
                    save(card, new File(folder, "native-publisher-" + names[app] + "-" + size[0] + "x" + size[1]
                            + "-" + (palette == 1 ? "light" : "dark") + ".png"));
                }
                Bitmap sheet = contactSheet(cards, "YouTube " + youtube.version, "SFTTV " + sendfiles.version);
                try { save(sheet, new File(folder, "native-publisher-" + size[0] + "x" + size[1] + "-contact-sheet.png")); }
                finally { sheet.recycle(); }
            } finally {
                for (Bitmap[] app : cards) for (Bitmap card : app) if (card != null) card.recycle();
            }
        }
    }

    private static void assertColor(Bitmap image, Rect bounds, float x, float y, int expected, String message) {
        int actual = image.getPixel(bounds.left + Math.round((bounds.width() - 1) * x),
                bounds.top + Math.round((bounds.height() - 1) * y));
        assertTrue(message + ": alpha", Color.alpha(actual) >= 245);
        assertEquals(message + ": red", Color.red(expected), Color.red(actual), 8);
        assertEquals(message + ": green", Color.green(expected), Color.green(actual), 8);
        assertEquals(message + ": blue", Color.blue(expected), Color.blue(actual), 8);
    }

    private static int countNear(Bitmap image, int expected) {
        int[] row = new int[image.getWidth()];
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            image.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int actual : row) if (Color.alpha(actual) >= 245
                    && Math.abs(Color.red(actual) - Color.red(expected)) <= 8
                    && Math.abs(Color.green(actual) - Color.green(expected)) <= 8
                    && Math.abs(Color.blue(actual) - Color.blue(expected)) <= 8) count++;
        }
        return count;
    }

    private static Rect inkBounds(Bitmap image) {
        int left = image.getWidth(), top = image.getHeight(), right = -1, bottom = -1;
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int x = 0; x < row.length; x++) if (Color.alpha(row[x]) > 8) {
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
        }
        return right < left ? new Rect() : new Rect(left, top, right + 1, bottom + 1);
    }

    private static Bitmap contactSheet(Bitmap[][] cards, String firstLabel, String secondLabel) {
        int width = cards[0][0].getWidth(), height = cards[0][0].getHeight();
        int cellWidth = width + 96, sheetHeight = height * 2 + 256;
        Bitmap sheet = Bitmap.createBitmap(cellWidth * 2, sheetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sheet);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int palette = 0; palette < 2; palette++) {
            int offset = palette * cellWidth;
            paint.setColor(palette == 0 ? 0xff141416 : 0xffe9e8e6);
            canvas.drawRect(offset, 0, offset + cellWidth, sheetHeight, paint);
            paint.setColor(palette == 0 ? Color.WHITE : 0xff202024);
            paint.setTextSize(width < 240 ? 18 : 24);
            canvas.drawText(palette == 0 ? "Dark · resource preview" : "Light · resource preview", offset + 24, 38, paint);
            paint.setTextSize(width < 240 ? 18 : 22);
            canvas.drawText(firstLabel, offset + 24, 94, paint);
            canvas.drawBitmap(cards[0][palette], offset + 48, 112, null);
            canvas.drawText(secondLabel, offset + 24, height + 184, paint);
            canvas.drawBitmap(cards[1][palette], offset + 48, height + 202, null);
            paint.setTextSize(18);
            canvas.drawText(width < 240 ? "Resources only" : "APK resources only · no installation or launch",
                    offset + 24, sheetHeight - 20, paint);
        }
        return sheet;
    }

    private static void save(Bitmap image, File file) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue("Save the actual renderer output: " + file.getName(), image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }

    private static void saveEvidence(StringBuilder evidence, File file) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(evidence.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class LoadedIcon {
        final String packageName, version;
        final Drawable drawable;
        LoadedIcon(String packageName, String version, Drawable drawable) {
            this.packageName = packageName; this.version = version; this.drawable = drawable;
        }
    }
}
