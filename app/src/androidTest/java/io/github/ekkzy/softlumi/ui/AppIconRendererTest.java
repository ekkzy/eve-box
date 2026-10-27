package io.github.ekkzy.softlumi.ui;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.widget.ImageView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.ekkzy.softlumi.MainActivity;
import io.github.ekkzy.softlumi.data.AppEntry;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs on the instrumentation worker; no launcher UI or remote logo fetch. */
@RunWith(AndroidJUnit4.class)
public class AppIconRendererTest {
    private static Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}

    @Test public void licensedColorMarksPreserveTheirIndependentMaskAlphaAndCounters() throws Exception {
        for(String pkg:new String[]{"com.vk.tv","ru.kinopoisk.tv",
                "com.plexapp.android","ru.rutube.app","com.netflix.ninja","org.smarttube.stable"}){
            AppArtworkCatalog.Entry entry=AppArtworkCatalog.find(pkg);
            Bitmap source;
            if(entry.drawable!=0){
                Drawable art=context().getDrawable(entry.drawable);assertNotNull(art);
                int width=320,height=Math.max(1,Math.round(width*art.getIntrinsicHeight()/(float)art.getIntrinsicWidth()));
                source=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);art.setBounds(0,0,width,height);art.draw(new Canvas(source));
            }else try(InputStream input=context().getAssets().open(entry.assetPath)){source=BitmapFactory.decodeStream(input);}
            assertNotNull(entry.key,source);
            Bitmap original=source.copy(Bitmap.Config.ARGB_8888,false),mask=MonochromeArtwork.mask(source,entry.key);
            try{
                for(boolean light:new boolean[]{false,true}){
                    Bitmap colored=MonochromeArtwork.colored(source,entry.key,entry.brandColor,light);
                    try{
                        int saturated=0;
                        for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++){
                            int pixel=colored.getPixel(x,y);
                            assertEquals(entry.key+" must preserve source-mask alpha at "+x+","+y,
                                    Color.alpha(mask.getPixel(x,y)),Color.alpha(pixel));
                            if(Color.alpha(pixel)>200&&Math.max(Color.red(pixel),Math.max(Color.green(pixel),Color.blue(pixel)))
                                    -Math.min(Color.red(pixel),Math.min(Color.green(pixel),Color.blue(pixel)))>40)saturated++;
                        }
                        assertTrue(entry.key+" must regain actual brand color",saturated>30);
                        assertTrue("Color restoration must never mutate publisher pixels",source.sameAs(original));
                    }finally{colored.recycle();}
                }
            }finally{source.recycle();original.recycle();mask.recycle();}
        }
    }

    @Test public void compactProviderBadgeUsesSameOfflineBrandWithoutAnInnerFace(){
        Context offline=new ContextWrapper(context()){
            @Override public PackageManager getPackageManager(){throw new AssertionError("Known provider badges must remain offline");}
        };
        for(boolean light:new boolean[]{false,true}){
            Bitmap badge=AppIconRenderer.renderLogo(offline,"ru.kinopoisk.tv",96,48,light);
            Bitmap card=AppIconRenderer.render(offline,new AppEntry(new ComponentName("ru.kinopoisk.tv","preview.Activity"),"Renamed"),96,48,light);
            try{
                Rect badgeInk=alphaBounds(badge),cardInk=alphaBounds(card);
                assertTrue("Compact badge must fill its viewport more than an app-card mark",badgeInk.height()>cardInk.height()+5);
                assertEquals("Badge does not paint another card",0,Color.alpha(badge.getPixel(0,0)));
                int color=badge.getPixel(badgeInk.centerX(),badgeInk.centerY());
                boolean orange=false;
                for(int y=badgeInk.top;y<badgeInk.bottom&&!orange;y++)for(int x=badgeInk.left;x<badgeInk.right;x++){
                    color=badge.getPixel(x,y);
                    if(Color.alpha(color)>250&&Color.red(color)>230&&Color.green(color)>60&&Color.green(color)<110&&Color.blue(color)<20){orange=true;break;}
                }
                assertTrue("Badge retains the same documented orange brand tint",orange);
            }finally{badge.recycle();card.recycle();}
        }
    }

    @Test public void denseNativeForegroundKeepsRealColorBandsAndTransparentCounter(){
        Bitmap source=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(source);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xff1a73e8);canvas.drawRoundRect(new RectF(16,16,112,112),10,10,paint);
        paint.setColor(0xff9aa0a6);canvas.drawRect(16,48,112,64,paint);
        paint.setColor(0xffea4335);canvas.drawRect(16,80,112,96,paint);
        for(int y=60;y<68;y++)for(int x=60;x<68;x++)source.setPixel(x,y,Color.TRANSPARENT);
        try{
            for(boolean light:new boolean[]{false,true}){
                BitmapDrawable legacySource=new BitmapDrawable(context().getResources(),source);
                BitmapDrawable adaptiveSource=new BitmapDrawable(context().getResources(),source);
                Bitmap legacy=AppIconRenderer.nativeCard(legacySource,384,216,light);
                Bitmap adaptive=AppIconRenderer.nativeCard(new AdaptiveIconDrawable(new ColorDrawable(0xff00ff00),adaptiveSource),384,216,light);
                try{
                    assertTrue("A dense real foreground must not be color-keyed as a plate",legacy.sameAs(adaptive));
                    Rect bounds=alphaBounds(adaptive);assertFalse(bounds.isEmpty());
                    assertNativeColor(adaptive,bounds,.25f,.15f,0xff1a73e8);
                    assertNativeColor(adaptive,bounds,.25f,.40f,0xff9aa0a6);
                    assertNativeColor(adaptive,bounds,.25f,.75f,0xffea4335);
                    assertEquals("Its original transparent counter remains open",0,Color.alpha(adaptive.getPixel(192,108)));
                    assertEquals("No new generic card surface",0,Color.alpha(adaptive.getPixel(0,0)));
                }finally{legacy.recycle();adaptive.recycle();}
            }
        }finally{source.recycle();}
    }

    private static void assertNativeColor(Bitmap artwork,Rect bounds,float x,float y,int expected){
        int actual=artwork.getPixel(bounds.left+Math.round((bounds.width()-1)*x),bounds.top+Math.round((bounds.height()-1)*y));
        assertEquals("Native opaque region must not acquire cut-through bands",255,Color.alpha(actual));
        assertEquals("Native red channel",Color.red(expected),Color.red(actual),2);
        assertEquals("Native green channel",Color.green(expected),Color.green(actual),2);
        assertEquals("Native blue channel",Color.blue(expected),Color.blue(actual),2);
    }

    @Test public void isolatedSingleToneNativeGlyphContrastsInBothPalettes(){
        for(int tone:new int[]{0xff101010,0xfff0f0f0}){
            Bitmap source=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(source);Paint paint=new Paint();paint.setColor(tone);
            canvas.drawRect(24,24,104,40,paint);canvas.drawRect(56,24,72,112,paint);
            Bitmap dark=AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(),source),384,216,false);
            Bitmap light=AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(),source),384,216,true);
            try{
                int opaque=0;
                for(int y=0;y<dark.getHeight();y++)for(int x=0;x<dark.getWidth();x++){
                    int darkPixel=dark.getPixel(x,y),lightPixel=light.getPixel(x,y);
                    if(Color.alpha(darkPixel)!=Color.alpha(lightPixel))fail("Palette tint changed isolated-glyph alpha");
                    if(Color.alpha(darkPixel)!=255)continue;
                    opaque++;
                    assertEquals("Dark face gets a light isolated glyph",0x00f4f4f4,darkPixel&0x00ffffff);
                    assertEquals("Light face gets a dark isolated glyph",0x00242424,lightPixel&0x00ffffff);
                }
                assertTrue("The real isolated glyph must remain visible",opaque>40);
            }finally{source.recycle();dark.recycle();light.recycle();}
        }
    }

    @Test public void translucentNativeTonesKeepTheirAlphaAndRemainVisible(){
        Bitmap source=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        for(int y=24;y<104;y++)for(int x=24;x<104;x++)
            source.setPixel(x,y,x<64?0x98ffffff:0xa0202020);
        Bitmap original=source.copy(Bitmap.Config.ARGB_8888,false);
        Bitmap dark=AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(),source),384,216,false);
        Bitmap light=AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(),source),384,216,true);
        try{
            assertTrue("Multitone native gray is preserved across palettes",dark.sameAs(light));
            Rect bounds=alphaBounds(dark);
            int white=dark.getPixel(bounds.left+bounds.width()/4,bounds.centerY());
            int gray=dark.getPixel(bounds.left+bounds.width()*3/4,bounds.centerY());
            assertEquals("White source coverage is not made opaque",0x98,Color.alpha(white));
            assertEquals("Dark source coverage is not made opaque",0xa0,Color.alpha(gray));
            assertEquals(255,Color.red(white));assertEquals(32,Color.red(gray),2);
            AppArtworkCatalogTest.assertTransparentLogo("Translucent native tones on dark",dark,false);
            AppArtworkCatalogTest.assertTransparentLogo("Translucent native tones on light",light,true);
            assertTrue("The borrowed translucent input is immutable",source.sameAs(original));
        }finally{dark.recycle();light.recycle();original.recycle();source.recycle();}
    }

    @Test public void fixtureSystemInfoDrawableRetainsVisibleNativeArtwork(){
        // Both launcher fixture APKs declare this platform resource as banner/icon.
        // Its native alpha is intentionally not required to contain opaque pixels.
        for(boolean light:new boolean[]{false,true}){
            Drawable icon=context().getDrawable(android.R.drawable.ic_menu_info_details);
            assertNotNull(icon);
            Bitmap rendered=AppIconRenderer.nativeCard(icon,768,768,light);
            try{AppArtworkCatalogTest.assertTransparentLogo("Platform fixture info icon",rendered,light);}
            finally{rendered.recycle();}
        }
    }

    @Test public void imageLoaderSeparatesPaletteEntriesForOwnSettingsGlyph(){
        AppEntry app=new AppEntry(new ComponentName("com.android.tv.settings","preview.Activity"),"Own settings glyph");
        Bitmap expectedDark=AppIconRenderer.render(context(),app,192,108,false);
        Bitmap expectedLight=AppIconRenderer.render(context(),app,192,108,true);
        UiImageLoader[] loader={null};ImageView[] target={null};
        Bitmap dark=null,light=null;
        try{
            assertFalse("The own achromatic source must actually depend on palette",expectedDark.sameAs(expectedLight));
            for(int y=0;y<108;y++)for(int x=0;x<192;x++)
                assertEquals("Palette changes RGB, never the settings silhouette",Color.alpha(expectedDark.getPixel(x,y)),Color.alpha(expectedLight.getPixel(x,y)));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                loader[0]=new UiImageLoader(context());target[0]=new ImageView(context());
                loader[0].setGeneration(7);loader[0].resume();
            });
            dark=awaitLoadedIcon(loader[0],target[0],app,false,expectedDark);
            light=awaitLoadedIcon(loader[0],target[0],app,true,expectedLight);
            assertNotSame("Light palette cannot reuse the dark cache bitmap",dark,light);
            Bitmap returnedDark=awaitLoadedIcon(loader[0],target[0],app,false,expectedDark);
            assertSame("Returning to dark uses the correct existing cache entry",dark,returnedDark);
        }finally{
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                if(target[0]!=null)target[0].setImageDrawable(null);
                if(loader[0]!=null)loader[0].close();
            });
            expectedDark.recycle();expectedLight.recycle();
            if(dark!=null&&!dark.isRecycled())dark.recycle();
            if(light!=null&&light!=dark&&!light.isRecycled())light.recycle();
        }
    }

    private static Bitmap awaitLoadedIcon(UiImageLoader loader,ImageView target,AppEntry app,boolean light,Bitmap expected){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            target.setImageDrawable(null);loader.icon(target,app,192,108,7,light);
        });
        AtomicReference<Bitmap> ready=new AtomicReference<>();
        long deadline=SystemClock.elapsedRealtime()+5000;
        while(ready.get()==null&&SystemClock.elapsedRealtime()<deadline){
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                if(target.getDrawable() instanceof BitmapDrawable){
                    Bitmap bitmap=((BitmapDrawable)target.getDrawable()).getBitmap();
                    if(bitmap!=null&&!bitmap.isRecycled()&&bitmap.sameAs(expected))ready.set(bitmap);
                }
            });
            if(ready.get()==null)SystemClock.sleep(20);
        }
        assertNotNull("Image worker must deliver the requested "+(light?"light":"dark")+" settings bitmap",ready.get());
        return ready.get();
    }

    @Test public void unlicensedPublisherIdentitiesUseNativeFallbackWithoutBundledResources() {
        Context nativeOnly = new ContextWrapper(context()) {
            @Override public AssetManager getAssets() {
                throw new AssertionError("Native identity must not read a copied publisher asset");
            }
            // Context.getDrawable is final and delegates to getResources().
            // PackageManager still loads installed artwork through the base context.
            @Override public Resources getResources() {
                throw new AssertionError("Native identity must not read a bundled brand drawable");
            }
        };
        for (String pkg : new String[]{"com.google.android.youtube.tv", "com.disney.disneyplus",
                "ar.tvplayer.tv", "net.gtvbox.vimuhd", "com.yablio.sendfilestotv", "ru.ivi.client",
                "ru.more.play", "ru.rt.video.app.tv", "ru.mts.mtstv", "ru.start.androidmobile"}) {
            AppArtworkCatalog.Entry identity = AppArtworkCatalog.find(pkg);
            assertNotNull(pkg, identity);
            assertFalse(pkg + ": no bundled publisher asset without applicable rights", identity.hasCuratedArtwork());
            assertEquals(pkg, 0, identity.drawable);
            assertNull(pkg, identity.assetPath);
            for (boolean light : new boolean[]{false, true}) {
                Bitmap card = AppIconRenderer.render(nativeOnly, new AppEntry(new ComponentName(
                        pkg, "not.installed.PreviewActivity"), "User label"), 384, 216, light);
                try {
                    // The package may be absent on the test emulator. This checks
                    // the native/generic contract, not the real service's TV logo.
                    AppArtworkCatalogTest.assertTransparentLogo(pkg + " native or generic identity", card, light);
                } finally { card.recycle(); }
            }
        }
    }

    @Test public void selectedPublisherPoliciesUseInstalledApplicationIconInsteadOfTvBanner() throws Exception {
        // softlumi deliberately supplies distinct icon and banner resources. Use
        // the real PackageManager, so this regression does not need publisher APKs.
        PackageManager packages=context().getPackageManager();
        String fixturePackage=context().getPackageName();
        AppEntry fixture=new AppEntry(new ComponentName(context(),MainActivity.class),"Local label");
        for(boolean light:new boolean[]{false,true}){
            Bitmap icon=AppIconRenderer.nativeCard(packages.getApplicationIcon(fixturePackage),384,216,light);
            Bitmap banner=AppIconRenderer.nativeCard(packages.getActivityBanner(fixture.componentName),384,216,light);
            try{
                assertFalse("The fixture must distinguish native icon from TV banner",icon.sameAs(banner));
                for(String pkg:new String[]{"com.google.android.youtube.tv","com.yablio.sendfilestotv","net.gtvbox.vimuhd"}){
                    boolean iconFirst=AppArtworkCatalog.find(pkg).prefersApplicationIcon();
                    for(AppEntry launcher:new AppEntry[]{fixture,null}){
                        Drawable selected=AppIconRenderer.installedArtwork(packages,launcher,fixturePackage,iconFirst);
                        Bitmap rendered=AppIconRenderer.nativeCard(selected,384,216,light);
                        try{
                            assertTrue(pkg+": launcher and provider identity use the installed application icon",
                                    icon.sameAs(rendered));
                        }finally{rendered.recycle();}
                    }
                }
                Bitmap unknown=AppIconRenderer.nativeCard(AppIconRenderer.installedArtwork(
                        packages,fixture,fixturePackage,false),384,216,light);
                try{assertTrue("Other packages retain their real TV banner",banner.sameAs(unknown));}
                finally{unknown.recycle();}
            }finally{icon.recycle();banner.recycle();}
        }
        String missingPackage="test.softlumi.missing.publisher";
        AppEntry missing=new AppEntry(new ComponentName(missingPackage,missingPackage+".Activity"),"Missing app");
        assertNotNull("A disappeared launcher entry still receives the platform fallback",
                AppIconRenderer.installedArtwork(packages,missing,missingPackage,true));
        assertNull("An unavailable provider is not given a fabricated logo",
                AppIconRenderer.installedArtwork(packages,null,missingPackage,true));
    }

    @Test public void twoToneNativeIdentityKeepsTheAdaptiveLayerRevealedByItsCounter(){
        Bitmap foreground=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        for(int y=0;y<64;y++)for(int x=0;x<128;x++)foreground.setPixel(x,y,0xff00dae2);
        for(int y=24;y<48;y++)for(int x=44;x<84;x++)foreground.setPixel(x,y,Color.TRANSPARENT);
        for(int y=88;y<112;y++)for(int x=56;x<72;x++)foreground.setPixel(x,y,0xff00dae2);
        Bitmap original=foreground.copy(Bitmap.Config.ARGB_8888,false);
        Bitmap[] cards=new Bitmap[2];
        try{
            for(int palette=0;palette<2;palette++){
                AdaptiveIconDrawable icon=new AdaptiveIconDrawable(new ColorDrawable(0xff212355),
                        new BitmapDrawable(context().getResources(),foreground));
                Bitmap card=AppIconRenderer.nativeCard(icon,384,216,palette==1,
                        AppArtworkCatalog.find("com.yablio.sendfilestotv"));
                cards[palette]=card;
                Rect bounds=alphaBounds(card);
                assertNativeColor(card,bounds,.5f,.2f,0xff212355);
                assertNativeColor(card,bounds,.15f,.25f,0xff00dae2);
                assertNativeColor(card,bounds,.5f,.85f,0xff00dae2);
                assertTrue("The complete native badge stays inside the common glyph envelope",
                        bounds.height()<=Math.round(216*.56f)+2&&bounds.width()<=Math.round(216*.56f)+2);
                AppArtworkCatalogTest.assertTransparentLogo("Complete two-tone native badge",card,palette==1);
            }
            assertTrue("Both native color layers are retained across host palettes",cards[0].sameAs(cards[1]));
            assertTrue("The publisher foreground is borrowed without mutation",foreground.sameAs(original));
        }finally{
            for(Bitmap card:cards)if(card!=null)card.recycle();
            foreground.recycle();original.recycle();
        }
    }

    @Test public void geometricFitDoesNotInflateASparseGlyphWithTheSameBounds() {
        Bitmap dense = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Bitmap sparse = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        for (int y = 16; y < 112; y++) for (int x = 16; x < 112; x++) {
            dense.setPixel(x, y, 0xff1278d6);
            if (x < 20 || x >= 108 || y < 20 || y >= 108) sparse.setPixel(x, y, 0xff1278d6);
        }
        Bitmap filled = AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(), dense), 384, 216, false);
        Bitmap outline = AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(), sparse), 384, 216, false);
        try {
            assertEquals("Equal source geometry has an equal TV focus envelope", alphaBounds(filled), alphaBounds(outline));
            assertEquals("A transparent counter is never filled", 0, Color.alpha(outline.getPixel(192, 108)));
            assertEquals("The dense source remains opaque", 255, Color.alpha(filled.getPixel(192, 108)));
        } finally { filled.recycle(); outline.recycle(); dense.recycle(); sparse.recycle(); }
    }

    @Test public void largeMinificationAveragesSubpixelColorDetailInsteadOfAliasingIt(){
        Bitmap stripes=Bitmap.createBitmap(768,768,Bitmap.Config.ARGB_8888);
        int[] row=new int[768];
        for(int y=0;y<768;y++){
            java.util.Arrays.fill(row,(y&1)==0?Color.RED:Color.BLUE);
            stripes.setPixels(row,0,row.length,0,y,row.length,1);
        }
        Bitmap original=stripes.copy(Bitmap.Config.ARGB_8888,false);
        Bitmap card=AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(),stripes),128,72,false);
        try{
            Rect bounds=alphaBounds(card);
            assertFalse(bounds.isEmpty());
            for(int y=bounds.top+2;y<bounds.bottom-2;y++)for(int x=bounds.left+2;x<bounds.right-2;x++){
                int pixel=card.getPixel(x,y);
                assertEquals("Opaque subpixel detail does not acquire transparent bands",255,Color.alpha(pixel));
                assertEquals("Equal red/blue rows become their stable area average",128,Color.red(pixel),2);
                assertEquals(0,Color.green(pixel));
                assertEquals("A large one-pass bilinear reduction must not pick individual blue rows",128,Color.blue(pixel),2);
            }
            assertTrue("Minification never changes the borrowed high-resolution source",stripes.sameAs(original));
        }finally{card.recycle();original.recycle();stripes.recycle();}
    }

    @Test public void nativeRedWhiteMatteRecoversSmoothOuterCoverageWithoutCuttingItsWhiteCounter(){
        Bitmap source=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.WHITE);
        Canvas canvas=new Canvas(source);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.RED);canvas.drawRoundRect(new RectF(22.3f,37.2f,137.7f,122.8f),19.4f,19.4f,paint);
        paint.setColor(Color.WHITE);canvas.drawRect(69,64,91,96,paint);
        Bitmap original=source.copy(Bitmap.Config.ARGB_8888,false);
        Bitmap generic=AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(),source),198,144,false);
        Bitmap corrected=AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(),source),198,144,false,
                AppArtworkCatalog.find("com.google.android.youtube.tv"));
        try{
            Rect bounds=alphaBounds(corrected);
            int partial=0,genericPink=0,correctedPink=0;
            for(int y=bounds.top;y<bounds.bottom;y++)for(int x=bounds.left;x<bounds.right;x++){
                // The central white counter and its own red/white boundary are
                // publisher artwork. Only the outside rim is a removable matte.
                if(x>bounds.left+bounds.width()/4&&x<bounds.right-bounds.width()/4
                        &&y>bounds.top+bounds.height()/4&&y<bounds.bottom-bounds.height()/4)continue;
                int pixel=corrected.getPixel(x,y),before=generic.getPixel(x,y);
                if(Color.alpha(before)>20&&Color.red(before)>240&&Color.green(before)>12)genericPink++;
                if(Color.alpha(pixel)>20&&Color.red(pixel)>240&&Color.green(pixel)>12)correctedPink++;
                if(Color.alpha(pixel)>0&&Color.alpha(pixel)<255)partial++;
            }
            assertTrue("The controlled source must reproduce the visible white-matte fringe",genericPink>5);
            assertEquals("Exterior red coverage has no opaque white RGB contamination",0,correctedPink);
            assertTrue("Removing matte restores antialias coverage instead of eroding it to a hard mask",partial>15);
            assertEquals("The enclosed white publisher counter stays fully opaque",Color.WHITE,corrected.getPixel(99,72));
            assertTrue("The general native field contract remains separate from the exact red-play policy",!generic.sameAs(corrected));
            assertTrue("Publisher source pixels remain borrowed and immutable",source.sameAs(original));
        }finally{generic.recycle();corrected.recycle();original.recycle();source.recycle();}
    }

    @Test public void redMatteRestorationDoesNotRecolorAnUnknownPublisherCore() {
        Bitmap source=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.WHITE);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(0xffff0033);
        new Canvas(source).drawRoundRect(new RectF(22.3f,37.2f,137.7f,122.8f),19.4f,19.4f,paint);
        Bitmap card=AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(),source),198,144,false,
                AppArtworkCatalog.find("com.google.android.youtube.tv"));
        try{
            int visible=0;
            for(int y=0;y<card.getHeight();y++)for(int x=0;x<card.getWidth();x++){
                int pixel=card.getPixel(x,y);
                if(Color.alpha(pixel)<=20)continue;
                visible++;
                assertTrue("An unknown red/pink core must not acquire a fabricated pure-red contour",Color.blue(pixel)>=44);
            }
            assertTrue("The changed native publisher shape remains visible",visible>100);
        }finally{card.recycle();source.recycle();}
    }

    @Test public void nativeZeroBlueWhitePlateRecoversGradientAndKnownCoverage() {
        Bitmap mark=syntheticNativeGradient(false,0),foreground=syntheticNativeGradient(true,0);
        Bitmap original=foreground.copy(Bitmap.Config.ARGB_8888,false);
        try{
            for(int[] size:new int[][]{{198,144},{396,288}}){
                Bitmap expected=AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(),mark),size[0],size[1],false);
                try{
                    for(boolean light:new boolean[]{false,true}){
                        AdaptiveIconDrawable nativeIcon=new AdaptiveIconDrawable(new ColorDrawable(0xff183221),
                                new BitmapDrawable(context().getResources(),foreground));
                        Bitmap actual=AppIconRenderer.nativeCard(nativeIcon,size[0],size[1],light,
                                AppArtworkCatalog.find("net.gtvbox.vimuhd"));
                        try{
                            assertEquals("Recovering an opaque white plate keeps the known shape envelope",alphaBounds(expected),alphaBounds(actual));
                            int maxAlphaDifference=0,maxPremultipliedDifference=0,partial=0;
                            for(int y=0;y<actual.getHeight();y++)for(int x=0;x<actual.getWidth();x++){
                                int a=actual.getPixel(x,y),e=expected.getPixel(x,y);
                                maxAlphaDifference=Math.max(maxAlphaDifference,Math.abs(Color.alpha(a)-Color.alpha(e)));
                                for(int shift=16;shift>=0;shift-=8){
                                    int ap=(((a>>>shift)&255)*Color.alpha(a)+127)/255;
                                    int ep=(((e>>>shift)&255)*Color.alpha(e)+127)/255;
                                    maxPremultipliedDifference=Math.max(maxPremultipliedDifference,Math.abs(ap-ep));
                                }
                                if(Color.alpha(a)>0&&Color.alpha(a)<255)partial++;
                                if(Color.alpha(a)>8)assertEquals("The known gradient cannot gain white or green matte contamination",0,Color.blue(a));
                            }
                            assertTrue("White compositing and recovery retain the known soft coverage",maxAlphaDifference<=2);
                            assertTrue("The original transparent gradient is the independent color/coverage oracle",maxPremultipliedDifference<=2);
                            assertTrue("Recovery must preserve partial coverage instead of cutting a hard outline",partial>30);
                            Rect bounds=alphaBounds(actual);
                            assertTrue("The source gradient direction remains visible",Color.green(actual.getPixel(bounds.centerX(),bounds.top+6))
                                    <Color.green(actual.getPixel(bounds.centerX(),bounds.bottom-7)));
                        }finally{actual.recycle();}
                    }
                }finally{expected.recycle();}
            }
            assertTrue("The installed foreground bitmap remains borrowed and immutable",foreground.sameAs(original));
        }finally{original.recycle();foreground.recycle();mark.recycle();}
    }

    @Test public void nativeZeroBlueWhitePlateKeepsUnknownColorOrNonWhitePerimeterRaw() {
        for(boolean changedPerimeter:new boolean[]{false,true}){
            Bitmap foreground=syntheticNativeGradient(true,changedPerimeter?0:48);
            if(changedPerimeter)foreground.setPixel(100,24,0xffff5500);
            Bitmap original=foreground.copy(Bitmap.Config.ARGB_8888,false);
            AdaptiveIconDrawable icon=new AdaptiveIconDrawable(new ColorDrawable(0xff183221),
                    new BitmapDrawable(context().getResources(),foreground));
            Bitmap raw=AppIconRenderer.nativeCard(icon,198,144,false);
            Bitmap guarded=AppIconRenderer.nativeCard(icon,198,144,false,AppArtworkCatalog.find("net.gtvbox.vimuhd"));
            try{
                assertTrue("An unknown blue-bearing core or nonwhite full perimeter must remain untouched",raw.sameAs(guarded));
                assertTrue("Guard failure cannot change publisher source pixels",foreground.sameAs(original));
            }finally{guarded.recycle();raw.recycle();original.recycle();foreground.recycle();}
        }
    }

    private static Bitmap syntheticNativeGradient(boolean whitePlate,int blue){
        Bitmap mark=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        for(int y=72;y<184;y++)for(int x=56;x<200;x++){
            int distance=Math.min(Math.min(x-56,199-x),Math.min(y-72,183-y));
            int alpha=distance<3?(distance+1)*64:255;
            mark.setPixel(x,y,Color.argb(alpha,220+(y-72)*30/111,24+(y-72)*136/111,blue));
        }
        if(!whitePlate)return mark;
        Bitmap plate=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(plate);Paint white=new Paint();white.setColor(Color.WHITE);
        canvas.drawRect(24,24,232,232,white);canvas.drawBitmap(mark,0,0,null);mark.recycle();
        return plate;
    }

    @Test public void flatFieldRemovalPreservesAnEnclosedRegionWithTheSameColor() {
        Bitmap source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        source.eraseColor(0xffbbbbbb);
        for (int y = 24; y < 104; y++) for (int x = 24; x < 104; x++) source.setPixel(x, y, 0xff2040d0);
        for (int y = 54; y < 74; y++) for (int x = 54; x < 74; x++) source.setPixel(x, y, 0xffbbbbbb);
        Bitmap original = source.copy(Bitmap.Config.ARGB_8888, false);
        try {
            for (boolean light : new boolean[]{false, true}) {
                Bitmap card = AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(), source), 384, 216, light);
                try {
                    assertEquals("Only the connected outer field is removed", 0, Color.alpha(card.getPixel(20, 108)));
                    assertEquals("Interior gray plane must remain a real RGBA pixel", 0xffbbbbbb, card.getPixel(192, 108));
                    assertTrue("Borrowed publisher pixels are immutable", source.sameAs(original));
                } finally { card.recycle(); }
            }
        } finally { original.recycle(); source.recycle(); }
    }

    @Test public void gradientPerimeterAndMultitoneGrayArtworkAreKeptIntact() {
        Bitmap source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            int tone = x < 42 ? 60 : x < 84 ? 130 : 205;
            source.setPixel(x, y, Color.rgb(tone, tone, tone));
        }
        Bitmap dark = AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(), source), 384, 216, false);
        Bitmap light = AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(), source), 384, 216, true);
        try {
            assertTrue("Native gray structure is not an isolated tintable glyph", dark.sameAs(light));
            Rect occupied = alphaBounds(dark);
            assertNativeColor(dark, occupied, .15f, .5f, Color.rgb(60, 60, 60));
            assertNativeColor(dark, occupied, .5f, .5f, Color.rgb(130, 130, 130));
            assertNativeColor(dark, occupied, .85f, .5f, Color.rgb(205, 205, 205));
        } finally { dark.recycle(); light.recycle(); source.recycle(); }
    }

    @Test public void mixedNativeWordmarkRetainsItsFieldOnlyWhenThePaletteNeedsIt() {
        for (boolean darkField : new boolean[]{false, true}) {
            int field = darkField ? 0xff101010 : Color.WHITE;
            int lettering = darkField ? Color.WHITE : Color.BLACK;
            Bitmap source = Bitmap.createBitmap(256, 96, Bitmap.Config.ARGB_8888);
            source.eraseColor(field);
            Canvas canvas = new Canvas(source);
            Paint paint = new Paint();
            paint.setColor(0xffed2030);
            canvas.drawRect(16, 16, 80, 80, paint);
            paint.setColor(lettering);
            // Synthetic block letters: no third-party logo or installed app is assumed.
            canvas.drawRect(100, 28, 108, 68, paint);
            canvas.drawRect(120, 28, 128, 68, paint);
            canvas.drawRect(108, 44, 120, 52, paint);
            canvas.drawRect(144, 28, 152, 68, paint);
            canvas.drawRect(152, 28, 176, 36, paint);
            canvas.drawRect(152, 44, 170, 52, paint);
            Bitmap original = source.copy(Bitmap.Config.ARGB_8888, false);
            Bitmap retained = AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(), source), 384, 216, darkField);
            Bitmap separated = AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(), source), 384, 216, !darkField);
            try {
                Rect bounds = alphaBounds(retained);
                assertNativeColor(retained, bounds, .9f, .25f, field);
                assertNativeColor(retained, bounds, .15f, .5f, 0xffed2030);
                assertNativeColor(retained, bounds, .44f, .5f, lettering);
                assertEquals("Keeping the publisher field does not paint a generated host face", 0, Color.alpha(retained.getPixel(0, 0)));
                Rect stripped = alphaBounds(separated);
                assertEquals("Readable mixed artwork still permits removal of the outer field", 0,
                        Color.alpha(separated.getPixel(stripped.right - 1, stripped.top)));
                assertFalse("The palette selects the intact field when its external lettering needs contrast", retained.sameAs(separated));
                assertTrue("Publisher RGB and alpha remain immutable in both paths", source.sameAs(original));
            } finally { retained.recycle(); separated.recycle(); original.recycle(); source.recycle(); }
        }
    }

    @Test public void enclosedLightOrDarkCounterDoesNotForceAnOuterPublisherField() {
        for (boolean light : new boolean[]{false, true}) {
            int field = light ? Color.WHITE : Color.BLACK;
            Bitmap source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            source.eraseColor(field);
            for (int y = 24; y < 104; y++) for (int x = 24; x < 104; x++) source.setPixel(x, y, 0xffed2030);
            for (int y = 54; y < 74; y++) for (int x = 54; x < 74; x++) source.setPixel(x, y, field);
            Bitmap rendered = AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(), source), 384, 216, light);
            try {
                assertEquals("The connected outer field is still removable around a colored mark", 0, Color.alpha(rendered.getPixel(20, 108)));
                assertEquals("The enclosed source counter retains its exact RGBA", field, rendered.getPixel(192, 108));
            } finally { rendered.recycle(); source.recycle(); }
        }
    }

    @Test public void oversizedOutputAndCancelledWorkHaveExplicitBounds() {
        Bitmap source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        source.eraseColor(0xff13865c);
        Bitmap card = AppIconRenderer.nativeCard(new BitmapDrawable(context().getResources(), source), 20000, 20000, false);
        try {
            assertEquals(AppIconRenderer.MAX_ICON_SIZE, card.getWidth());
            assertEquals(AppIconRenderer.MAX_ICON_SIZE, card.getHeight());
        } finally { card.recycle(); source.recycle(); }
        Thread.currentThread().interrupt();
        try {
            AppIconRenderer.nativeCard(new ColorDrawable(Color.RED), 384, 216, false);
            fail("Cancelled request must not produce a bitmap or silently become fallback artwork");
        } catch (java.util.concurrent.CancellationException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void rendererRejectsPixelWorkOnTheMainThread() {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { AppIconRenderer.nativeCard(new ColorDrawable(Color.RED), 384, 216, false); }
            catch (Throwable error) { failure.set(error); }
        });
        assertTrue("UI must hand artwork to its image worker", failure.get() instanceof IllegalStateException);
    }

    private static Rect alphaBounds(Bitmap source){
        int width=source.getWidth(),height=source.getHeight(),left=width,top=height,right=0,bottom=0;
        int[] row=new int[width];
        for(int y=0;y<height;y++){
            source.getPixels(row,0,width,0,y,width,1);
            for(int x=0;x<width;x++)if(Color.alpha(row[x])>10){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+1);bottom=Math.max(bottom,y+1);}
        }
        return right>left?new Rect(left,top,right,bottom):new Rect();
    }

    @Test public void unknownInstalledBannerAndMissingBannerKeepNativeFallbacks(){
        Context source=new ContextWrapper(context()){
            @Override public AssetManager getAssets(){throw new AssertionError("Unknown package must not load a curated brand asset");}
        };
        for(boolean light:new boolean[]{false,true})for(ComponentName app:new ComponentName[]{new ComponentName(context(),MainActivity.class),
                new ComponentName("test.unknown.missing","test.unknown.missing.Activity")}){
            Bitmap first=AppIconRenderer.render(source,new AppEntry(app,"YouTube"),384,216,light);
            Bitmap renamed=AppIconRenderer.render(source,new AppEntry(app,"Another local label"),384,216,light);
            try{
                assertTrue("Local labels cannot substitute a known brand",first.sameAs(renamed));
                assertEquals(384,first.getWidth());assertEquals(216,first.getHeight());
                assertEquals(0,Color.alpha(first.getPixel(0,0)));
                assertEquals("Native fallback adds no generated inner face",0,Color.alpha(AppArtworkCatalogTest.base(first)));
                AppArtworkCatalogTest.assertTransparentLogo(app.flattenToShortString(),first,light);
            }finally{first.recycle();renamed.recycle();}
        }
    }

    @Test public void knownStylesUseExactPackagesAndRejectLabelLikeAliases(){
        for(String pkg:new String[]{"com.vk.tv","ru.kinopoisk.tv","com.google.android.youtube.tv",
                "net.gtvbox.vimuhd","com.plexapp.android","com.yablio.sendfilestotv","ru.ivi.client","ru.rutube.app"}){
            assertTrue(pkg,AppIconRenderer.hasKnownStyle(pkg));
            assertFalse(pkg+".fake",AppIconRenderer.hasKnownStyle(pkg+".fake"));
        }
        assertFalse(AppIconRenderer.hasKnownStyle("YouTube"));
        assertFalse(AppIconRenderer.hasKnownStyle(null));
    }

}
