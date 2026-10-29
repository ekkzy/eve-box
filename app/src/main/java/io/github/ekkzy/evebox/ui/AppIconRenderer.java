package io.github.ekkzy.evebox.ui;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Looper;
import android.util.DisplayMetrics;

import io.github.ekkzy.evebox.data.AppEntry;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;

/**
 * Worker-owned artwork compositor. A transparent bitmap contains only the installed
 * app identity; the View owns the glass face, label and focus treatment.
 *
 * Source decoding, pixel inspection and output are bounded independently. No source
 * bitmap is retained here; UiImageLoader owns the returned image and cache.
 */
public final class AppIconRenderer {
    public static final int MAX_ICON_SIZE = 768;
    public static final String CACHE_VERSION = "geometry-rgba-v6";
    private static final int SOURCE_LIMIT = 768;
    private static final int SOURCE_DIMENSION_LIMIT = 8192;

    private AppIconRenderer() {}

    public static boolean hasKnownStyle(String packageName) {
        return AppArtworkCatalog.find(packageName) != null;
    }

    public static Bitmap render(Context context, AppEntry entry, int width, int height) {
        return render(context, entry, width, height, false);
    }

    public static Bitmap render(Context context, AppEntry entry, int width, int height, boolean light) {
        if (entry == null) throw new IllegalArgumentException("An app identity is required");
        return compose(context, entry, entry.packageName, width, height, light, false);
    }

    public static Bitmap renderLogo(Context context, String packageName, int width, int height, boolean light) {
        return compose(context, null, packageName, width, height, light, true);
    }

    private static Bitmap compose(Context context, AppEntry app, String packageName,
            int width, int height, boolean light, boolean compact) {
        requireWorker();
        Bitmap output = output(width, height);
        Bitmap source = null;
        Bitmap mark = null;
        try {
            AppArtworkCatalog.Entry identity = AppArtworkCatalog.find(packageName);
            if (identity != null && identity.hasCuratedArtwork()) source = readBundled(context, identity);
            if (source != null) {
                // This independent Eve Box helper defines the documented treatment
                // of the licensed bundled marks. Native installed icons bypass it.
                mark = MonochromeArtwork.colored(source, identity.key, identity.brandColor, light);
                draw(output, mark, compact);
            }
            if ((mark == null || alphaBounds(mark).isEmpty())
                    && packageName != null && !packageName.isEmpty()) {
                PackageManager packages = context.getPackageManager();
                Drawable installed = installedArtwork(packages, app, packageName,
                        identity != null && identity.prefersApplicationIcon());
                if (identity != null && identity.restoresZeroBlueWhitePlate()) {
                    // An unknown native foreground must keep the old banner path,
                    // rather than turn an unverified white plate into app identity.
                    if (!(installed instanceof AdaptiveIconDrawable)
                            || !drawInstalled(output, installed, light, compact, identity)) {
                        drawInstalled(output, installedArtwork(packages, app, packageName, false), light, compact, null);
                    }
                } else {
                    drawInstalled(output, installed, light, compact, identity);
                }
            }
            return output;
        } catch (RuntimeException | Error failure) {
            output.recycle();
            throw failure;
        } finally {
            if (mark != null) mark.recycle();
            if (source != null) source.recycle();
        }
    }

    private static Bitmap output(int width, int height) {
        int boundedWidth = Math.max(1, Math.min(MAX_ICON_SIZE, width));
        int boundedHeight = Math.max(1, Math.min(MAX_ICON_SIZE, height));
        return Bitmap.createBitmap(boundedWidth, boundedHeight, Bitmap.Config.ARGB_8888);
    }

    /**
     * Fit alpha geometry into a fixed envelope. Thin glyphs and opaque glyphs with
     * the same bounds have the same size; pixel density cannot enlarge a logo.
     */
    private static void draw(Bitmap output, Bitmap mark, boolean compact) {
        Rect source = alphaBounds(mark);
        if (source.isEmpty()) return;
        float width = output.getWidth(), height = output.getHeight();
        if (!compact) {
            width = Math.min(width, height * 16f / 9f);
            height = width * 9f / 16f;
        }
        float aspect = source.width() / (float) source.height();
        float maxWidth = compact ? width * .88f : width * (aspect >= 2f ? .74f : .64f);
        float maxHeight = compact ? height * .88f : height * (aspect >= 2f ? .48f : .56f);
        float scale = Math.min(maxWidth / source.width(), maxHeight / source.height());
        int fittedWidth = Math.max(1, Math.round(source.width() * scale));
        int fittedHeight = Math.max(1, Math.round(source.height() * scale));
        int left = (output.getWidth() - fittedWidth) / 2;
        int top = (output.getHeight() - fittedHeight) / 2;
        Paint filter = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap reduced = null;
        try {
            // Bilinear filtering alone samples just a few source pixels. Large
            // 768px-to-TV-glyph reductions used to discard most edge coverage.
            // Halve first, averaging the premultiplied RGBA coverage, so the last
            // filtered draw never minifies by more than two in either dimension.
            Bitmap current = mark;
            Rect crop = source;
            while (crop.width() > fittedWidth * 2 || crop.height() > fittedHeight * 2) {
                checkCancelled();
                int nextWidth = Math.max(fittedWidth, (crop.width() + 1) / 2);
                int nextHeight = Math.max(fittedHeight, (crop.height() + 1) / 2);
                Bitmap next = Bitmap.createBitmap(nextWidth, nextHeight, Bitmap.Config.ARGB_8888);
                try {
                    new Canvas(next).drawBitmap(current, crop, new Rect(0, 0, nextWidth, nextHeight), filter);
                } catch (RuntimeException | Error failure) {
                    next.recycle();
                    throw failure;
                }
                if (reduced != null) reduced.recycle();
                reduced = next;
                current = next;
                crop = new Rect(0, 0, nextWidth, nextHeight);
            }
            new Canvas(output).drawBitmap(current, crop,
                    new Rect(left, top, left + fittedWidth, top + fittedHeight), filter);
        } finally {
            if (reduced != null) reduced.recycle();
        }
    }

    private static Bitmap readBundled(Context context, AppArtworkCatalog.Entry entry) {
        try {
            checkCancelled();
            if (entry.drawable != 0) {
                Drawable drawable = context.getDrawable(entry.drawable);
                return drawable == null ? null : rasterize(drawable);
            }
            if (entry.assetPath == null) return null;
            BitmapFactory.Options dimensions = new BitmapFactory.Options();
            dimensions.inJustDecodeBounds = true;
            try (InputStream input = context.getAssets().open(entry.assetPath)) {
                BitmapFactory.decodeStream(input, null, dimensions);
            }
            if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0
                    || dimensions.outWidth > SOURCE_DIMENSION_LIMIT
                    || dimensions.outHeight > SOURCE_DIMENSION_LIMIT) return null;
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inScaled = false;
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decode.inSampleSize = 1;
            while (dimensions.outWidth / decode.inSampleSize > SOURCE_LIMIT
                    || dimensions.outHeight / decode.inSampleSize > SOURCE_LIMIT) decode.inSampleSize *= 2;
            checkCancelled();
            try (InputStream input = context.getAssets().open(entry.assetPath)) {
                return BitmapFactory.decodeStream(input, null, decode);
            }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    static Drawable installedArtwork(PackageManager packages, AppEntry app, String packageName,
            boolean applicationIconFirst) {
        if (packageName == null || packageName.isEmpty()) return null;
        if (applicationIconFirst) {
            try {
                ApplicationInfo application = packages.getApplicationInfo(packageName, 0);
                Drawable icon = resourceArtwork(packages, application, application.icon);
                if (icon != null) return icon;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                // The fallback below still handles missing native resources.
            }
            try {
                Drawable icon = packages.getApplicationIcon(packageName);
                if (icon != null) return icon;
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                // An unavailable app icon still permits the normal banner/activity fallback.
            }
        }
        if (app != null) {
            try {
                ActivityInfo activity = packages.getActivityInfo(app.componentName, 0);
                int bannerId = activity.banner != 0 ? activity.banner : activity.applicationInfo.banner;
                Drawable banner = resourceArtwork(packages, activity.applicationInfo, bannerId);
                if (banner != null) return banner;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                // Fall back to the platform resolver if the explicit resource is unavailable.
            }
            try {
                Drawable banner = packages.getActivityBanner(app.componentName);
                if (banner != null) return banner;
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                // The activity may have disappeared between catalog and artwork loads.
            }
        }
        try {
            ApplicationInfo application = packages.getApplicationInfo(packageName, 0);
            Drawable banner = resourceArtwork(packages, application, application.banner);
            if (banner != null) return banner;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // An icon can still be available when there is no TV banner.
        }
        try {
            Drawable banner = packages.getApplicationBanner(packageName);
            if (banner != null) return banner;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // An icon can still be available when there is no TV banner.
        }
        try {
            if (app == null) {
                ApplicationInfo application = packages.getApplicationInfo(packageName, 0);
                Drawable icon = resourceArtwork(packages, application, application.icon);
                if (icon != null) return icon;
            } else {
                ActivityInfo activity = packages.getActivityInfo(app.componentName, 0);
                Drawable icon = resourceArtwork(packages, activity.applicationInfo, activity.getIconResource());
                if (icon != null) return icon;
            }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // Keep the platform's generic icon/empty-provider contract below.
        }
        try {
            return app == null ? packages.getApplicationIcon(packageName)
                    : packages.getActivityIcon(app.componentName);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // A missing provider logo stays empty. A missing launcher entry has the
            // platform's generic app identity, never a fabricated brand wordmark.
            return app == null ? null : packages.getDefaultActivityIcon();
        }
    }

    /** Prefer the publisher's dense bitmap or vector instead of the current TV density variant. */
    static Drawable resourceArtwork(PackageManager packages, ApplicationInfo application, int resourceId)
            throws PackageManager.NameNotFoundException {
        checkCancelled();
        if (resourceId == 0) return null;
        return packages.getResourcesForApplication(application).getDrawableForDensity(
                resourceId, DisplayMetrics.DENSITY_XXXHIGH, null);
    }

    private static Bitmap rasterize(Drawable drawable) {
        checkCancelled();
        int intrinsicWidth = drawable.getIntrinsicWidth(), intrinsicHeight = drawable.getIntrinsicHeight();
        float aspect = intrinsicWidth > 0 && intrinsicHeight > 0
                ? intrinsicWidth / (float) intrinsicHeight : 1f;
        int longest = SOURCE_LIMIT;
        Drawable content = drawable;
        for (int depth = 0; depth < 8 && content instanceof InsetDrawable; depth++) {
            content = ((InsetDrawable) content).getDrawable();
        }
        if (content instanceof BitmapDrawable) {
            Bitmap borrowed = ((BitmapDrawable) content).getBitmap();
            if (borrowed == null || borrowed.isRecycled()) return null;
            longest = Math.min(SOURCE_LIMIT, Math.max(borrowed.getWidth(), borrowed.getHeight()));
        }
        int width = aspect >= 1f ? longest : Math.max(1, Math.round(longest * aspect));
        int height = aspect >= 1f ? Math.max(1, Math.round(longest / aspect)) : longest;
        Bitmap raster = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Rect previousBounds = new Rect(drawable.getBounds());
        try {
            drawable.setBounds(0, 0, width, height);
            drawable.draw(new Canvas(raster));
            return raster;
        } catch (RuntimeException | Error failure) {
            raster.recycle();
            throw failure;
        } finally {
            drawable.setBounds(previousBounds);
        }
    }

    private static boolean drawInstalled(Bitmap output, Drawable icon, boolean light, boolean compact,
            AppArtworkCatalog.Entry identity) {
        if (icon == null) return false;
        Bitmap raster = null;
        Bitmap cropped = null;
        Bitmap separated = null;
        Bitmap adjusted = null;
        try {
            if (identity != null && identity.restoresZeroBlueWhitePlate()
                    && icon instanceof AdaptiveIconDrawable) {
                Drawable foreground = ((AdaptiveIconDrawable) icon).getForeground();
                if (foreground != null) raster = rasterize(foreground);
                if (raster == null) return false;
                Rect plate = alphaBounds(raster);
                if (plate.isEmpty()) return false;
                cropped = Bitmap.createBitmap(raster, plate.left, plate.top, plate.width(), plate.height());
                if (cropped == raster) cropped = raster.copy(Bitmap.Config.ARGB_8888, false);
                if (cropped == null) return false;
                separated = removeConnectedFlatField(cropped, false, true);
                if (separated == null) return false;
                draw(output, separated, compact);
                return true;
            }
            // A publisher may use a transparent foreground counter to expose an
            // essential color from its background. Preserve that native composite.
            boolean foregroundLayer = icon instanceof AdaptiveIconDrawable
                    && (identity == null || !identity.keepsAdaptiveBackground());
            Drawable artwork = foregroundLayer ? ((AdaptiveIconDrawable) icon).getForeground() : icon;
            if (artwork != null) raster = rasterize(artwork);
            if (raster == null || alphaBounds(raster).isEmpty()) {
                if (raster != null) raster.recycle();
                raster = null;
                if (!foregroundLayer) return false;
                foregroundLayer = false;
                Drawable background = ((AdaptiveIconDrawable) icon).getBackground();
                if (background != null) raster = rasterize(background);
            }
            if (raster == null) return false;
            // Adaptive foreground is already a publisher layer. Only an opaque,
            // uniform *complete perimeter* supports legacy field removal.
            if (!foregroundLayer) separated = removeConnectedFlatField(raster,
                    identity != null && identity.restoresRedWhiteMatte());
            Bitmap mark = separated == null ? raster : separated;
            if (isIsolatedAchromaticGlyph(mark)) {
                adjusted = tintGlyph(mark, light ? 0x00242424 : 0x00f4f4f4);
                mark = adjusted;
            } else if (separated != null && needsOriginalField(mark, light)) {
                // Mixed artwork cannot be tinted as a whole. Keep the publisher's
                // original field when removal would hide an exposed wordmark.
                mark = raster;
            }
            draw(output, mark, compact);
            return true;
        } finally {
            if (adjusted != null) adjusted.recycle();
            if (separated != null) separated.recycle();
            if (cropped != null) cropped.recycle();
            if (raster != null) raster.recycle();
        }
    }

    /**
     * Removes only near-identical opaque field pixels reachable from the perimeter.
     * Internal regions and pre-existing transparent counters are never filled or
     * color-keyed. A gradient/photo/colored perimeter stays intact.
     */
    private static Bitmap removeConnectedFlatField(Bitmap image, boolean restoreRedWhiteMatte) {
        return removeConnectedFlatField(image, restoreRedWhiteMatte, false);
    }

    private static Bitmap removeConnectedFlatField(Bitmap image, boolean restoreRedWhiteMatte,
            boolean restoreZeroBlueWhiteMatte) {
        int width = image.getWidth(), height = image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixels(pixels, 0, width, 0, 0, width, height);
        int background = pixels[0];
        if (Color.alpha(background) < 250) return null;
        if (restoreZeroBlueWhiteMatte && (background != Color.WHITE || !hasZeroBlueSourceCore(pixels))) return null;
        for (int x = 0; x < width; x++) {
            if (restoreZeroBlueWhiteMatte
                    ? pixels[x] != Color.WHITE || pixels[(height - 1) * width + x] != Color.WHITE
                    : !sameField(pixels[x], background) || !sameField(pixels[(height - 1) * width + x], background)) return null;
        }
        for (int y = 0; y < height; y++) {
            if (restoreZeroBlueWhiteMatte
                    ? pixels[y * width] != Color.WHITE || pixels[y * width + width - 1] != Color.WHITE
                    : !sameField(pixels[y * width], background) || !sameField(pixels[y * width + width - 1], background)) return null;
        }
        int[] frontier = new int[pixels.length];
        boolean[] visited = new boolean[pixels.length];
        // Retain even one level of coverage for the reviewed white-matte sources.
        boolean exactWhiteMatte = (restoreRedWhiteMatte || restoreZeroBlueWhiteMatte) && background == Color.WHITE;
        int read = 0, write = 0;
        for (int x = 0; x < width; x++) {
            write = enqueue(x, background, pixels, visited, frontier, write, exactWhiteMatte);
            write = enqueue((height - 1) * width + x, background, pixels, visited, frontier, write, exactWhiteMatte);
        }
        for (int y = 1; y + 1 < height; y++) {
            write = enqueue(y * width, background, pixels, visited, frontier, write, exactWhiteMatte);
            write = enqueue(y * width + width - 1, background, pixels, visited, frontier, write, exactWhiteMatte);
        }
        while (read < write) {
            if ((read & 1023) == 0) checkCancelled();
            int index = frontier[read++], x = index % width, y = index / width;
            pixels[index] = 0;
            if (x > 0) write = enqueue(index - 1, background, pixels, visited, frontier, write, exactWhiteMatte);
            if (x + 1 < width) write = enqueue(index + 1, background, pixels, visited, frontier, write, exactWhiteMatte);
            if (y > 0) write = enqueue(index - width, background, pixels, visited, frontier, write, exactWhiteMatte);
            if (y + 1 < height) write = enqueue(index + width, background, pixels, visited, frontier, write, exactWhiteMatte);
        }
        if (write == pixels.length) return null; // A flat icon is not evidence of an empty app identity.
        if (restoreZeroBlueWhiteMatte || exactWhiteMatte && hasRedWhiteSourceCore(pixels)) {
            restoreWhiteMatteEdge(pixels, visited, frontier, write, width, height, restoreZeroBlueWhiteMatte);
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    /** Unknown publisher colors must not inherit the reviewed pure-red matte model. */
    private static boolean hasRedWhiteSourceCore(int[] pixels) {
        int visible = 0, redCore = 0;
        for (int i = 0; i < pixels.length; i++) {
            if ((i & 1023) == 0) checkCancelled();
            int pixel = pixels[i];
            if (Color.alpha(pixel) == 0) continue;
            visible++;
            if (Color.alpha(pixel) != 255 || Color.red(pixel) != 255
                    || Math.abs(Color.green(pixel) - Color.blue(pixel)) > 1) return false;
            if (pixel == Color.RED) redCore++;
        }
        return redCore >= Math.max(16, visible / 10);
    }

    private static boolean hasZeroBlueSourceCore(int[] pixels) {
        int colored = 0, core = 0;
        for (int i = 0; i < pixels.length; i++) {
            if ((i & 1023) == 0) checkCancelled();
            int pixel = pixels[i], red = Color.red(pixel), green = Color.green(pixel), blue = Color.blue(pixel);
            if (Color.alpha(pixel) != 255 || red < blue || green < blue) return false;
            if (pixel == Color.WHITE) continue;
            colored++;
            if (blue == 0) {
                if (red < 192 || green >= red) return false;
                core++;
            }
        }
        return core >= Math.max(16, colored * 3 / 4);
    }

    /**
     * Recover only exterior-connected antialias, never an enclosed white region.
     * C = a*F + (1-a)*white gives a = 1-green/255 for the pure-red play, and
     * a = 1-blue/255 for the reviewed zero-blue gradient. The latter also recovers
     * the original red/green channels. No covered pixel is eroded or moved;
     * an unverified publisher/color field bypasses this path.
     */
    private static void restoreWhiteMatteEdge(int[] pixels, boolean[] visited,
            int[] frontier, int fieldCount, int width, int height, boolean zeroBlue) {
        int read = 0, write = fieldCount;
        while (read < write) {
            if ((read & 1023) == 0) checkCancelled();
            int index = frontier[read++], x = index % width, y = index / width;
            if (x > 0) write = enqueueWhiteMatte(index - 1, pixels, visited, frontier, write, zeroBlue);
            if (x + 1 < width) write = enqueueWhiteMatte(index + 1, pixels, visited, frontier, write, zeroBlue);
            if (y > 0) write = enqueueWhiteMatte(index - width, pixels, visited, frontier, write, zeroBlue);
            if (y + 1 < height) write = enqueueWhiteMatte(index + width, pixels, visited, frontier, write, zeroBlue);
        }
        for (int i = fieldCount; i < write; i++) {
            int index = frontier[i], pixel = pixels[index];
            int matte = zeroBlue ? Color.blue(pixel) : (Color.green(pixel) + Color.blue(pixel) + 1) / 2;
            int alpha = 255 - matte;
            int red = zeroBlue ? Math.round((Color.red(pixel) - matte) * 255f / alpha) : 255;
            int green = zeroBlue ? Math.round((Color.green(pixel) - matte) * 255f / alpha) : 0;
            pixels[index] = Color.argb(alpha, red, green, 0);
        }
    }

    private static int enqueueWhiteMatte(int index, int[] pixels, boolean[] visited,
            int[] frontier, int write, boolean zeroBlue) {
        int pixel = pixels[index], green = Color.green(pixel), blue = Color.blue(pixel);
        boolean matches = zeroBlue ? blue > 0 && blue < 255 && Color.red(pixel) >= blue && green >= blue
                : Color.red(pixel) == 255 && green > 0 && green < 255 && Math.abs(green - blue) <= 1;
        if (!visited[index] && Color.alpha(pixel) == 255 && matches) {
            visited[index] = true;
            frontier[write++] = index;
        }
        return write;
    }

    private static int enqueue(int index, int background, int[] pixels, boolean[] visited,
            int[] frontier, int write, boolean exactWhiteMatte) {
        if (!visited[index] && (exactWhiteMatte ? pixels[index] == Color.WHITE : sameField(pixels[index], background))) {
            visited[index] = true;
            frontier[write++] = index;
        }
        return write;
    }

    private static boolean sameField(int pixel, int reference) {
        return Color.alpha(pixel) >= 250
                && Math.abs(Color.red(pixel) - Color.red(reference)) <= 2
                && Math.abs(Color.green(pixel) - Color.green(reference)) <= 2
                && Math.abs(Color.blue(pixel) - Color.blue(reference)) <= 2;
    }

    /**
     * An exposed near-black/near-white component of a mixed mark may disappear on
     * the corresponding neutral face. Interior counters are surrounded by the
     * publisher's colored geometry and do not require an outer field. This only
     * chooses between two intact rasters; it never recolors or changes coverage.
     */
    private static boolean needsOriginalField(Bitmap separated, boolean light) {
        int width = separated.getWidth(), height = separated.getHeight();
        int[] pixels = new int[width * height];
        separated.getPixels(pixels, 0, width, 0, 0, width, height);
        int solid = 0;
        for (int pixel : pixels) if (Color.alpha(pixel) >= 220) solid++;
        int significant = Math.max(6, solid / 100);
        boolean[] visited = new boolean[pixels.length];
        int[] frontier = new int[pixels.length];
        int[] neighbors = new int[4];
        int endangered = 0;
        for (int seed = 0; seed < pixels.length; seed++) {
            if ((seed & 1023) == 0) checkCancelled();
            if (visited[seed] || !neutralPixel(pixels[seed])) continue;
            int read = 0, write = 1, componentRisk = 0;
            boolean exposed = false;
            frontier[0] = seed;
            visited[seed] = true;
            while (read < write) {
                if ((read & 1023) == 0) checkCancelled();
                int index = frontier[read++], x = index % width, y = index / width;
                int pixel = pixels[index];
                int tone = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
                if (Color.alpha(pixel) >= 220 && (light ? tone >= 224 : tone <= 64)) componentRisk++;
                neighbors[0] = x > 0 ? index - 1 : -1;
                neighbors[1] = x + 1 < width ? index + 1 : -1;
                neighbors[2] = y > 0 ? index - width : -1;
                neighbors[3] = y + 1 < height ? index + width : -1;
                for (int neighbor : neighbors) {
                    if (neighbor < 0 || Color.alpha(pixels[neighbor]) == 0) {
                        exposed = true;
                    } else if (!visited[neighbor] && neutralPixel(pixels[neighbor])) {
                        visited[neighbor] = true;
                        frontier[write++] = neighbor;
                    }
                }
            }
            if (exposed) endangered += componentRisk;
            if (endangered >= significant) return true;
        }
        return false;
    }

    private static boolean neutralPixel(int pixel) {
        int red = Color.red(pixel), green = Color.green(pixel), blue = Color.blue(pixel);
        return Color.alpha(pixel) != 0
                && Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) <= 8;
    }

    private static boolean isIsolatedAchromaticGlyph(Bitmap image) {
        int[] row = new int[image.getWidth()];
        boolean transparent = false, visible = false;
        int darkest = 255, brightest = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            checkCancelled();
            image.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int pixel : row) {
                if (Color.alpha(pixel) == 0) { transparent = true; continue; }
                int red = Color.red(pixel), green = Color.green(pixel), blue = Color.blue(pixel);
                int minimum = Math.min(red, Math.min(green, blue)), maximum = Math.max(red, Math.max(green, blue));
                if (maximum - minimum > 6) return false;
                if (Color.alpha(pixel) >= 220) {
                    visible = true;
                    int tone = (red + green + blue) / 3;
                    darkest = Math.min(darkest, tone);
                    brightest = Math.max(brightest, tone);
                    if (brightest - darkest > 12) return false;
                }
            }
        }
        return transparent && visible;
    }

    private static Bitmap tintGlyph(Bitmap source, int rgb) {
        Bitmap result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        int[] row = new int[source.getWidth()];
        try {
            for (int y = 0; y < source.getHeight(); y++) {
                checkCancelled();
                source.getPixels(row, 0, row.length, 0, y, row.length, 1);
                for (int x = 0; x < row.length; x++) row[x] = Color.alpha(row[x]) == 0 ? 0 : (row[x] & 0xff000000) | rgb;
                result.setPixels(row, 0, row.length, 0, y, row.length, 1);
            }
            return result;
        } catch (RuntimeException | Error failure) {
            result.recycle();
            throw failure;
        }
    }

    private static Rect alphaBounds(Bitmap image) {
        int left = image.getWidth(), top = image.getHeight(), right = -1, bottom = -1;
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            checkCancelled();
            image.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int x = 0; x < row.length; x++) if (Color.alpha(row[x]) > 0) {
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
        }
        return right < left ? new Rect() : new Rect(left, top, right + 1, bottom + 1);
    }

    static Bitmap nativeCard(Drawable icon, int width, int height) {
        return nativeCard(icon, width, height, false);
    }

    static Bitmap nativeCard(Drawable icon, int width, int height, boolean light) {
        return nativeCard(icon, width, height, light, null);
    }

    static Bitmap nativeCard(Drawable icon, int width, int height, boolean light,
            AppArtworkCatalog.Entry identity) {
        requireWorker();
        Bitmap result = output(width, height);
        try {
            if (!drawInstalled(result, icon, light, false, identity)) {
                // Resource-only callers have no PackageManager banner to retry;
                // preserve unknown native colors and geometry unchanged.
                drawInstalled(result, icon, light, false, null);
            }
            return result;
        } catch (RuntimeException | Error failure) {
            result.recycle();
            throw failure;
        }
    }

    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Artwork decoding belongs to the image worker");
        }
        checkCancelled();
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Artwork request cancelled");
    }
}
