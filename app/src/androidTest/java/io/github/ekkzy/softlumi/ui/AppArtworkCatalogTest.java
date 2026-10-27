package io.github.ekkzy.softlumi.ui;

import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.ekkzy.softlumi.data.AppCatalog;
import io.github.ekkzy.softlumi.data.AppEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Offline asset render evidence. Atlas entries identify supported brands, not installed applications. */
@RunWith(AndroidJUnit4.class)
public final class AppArtworkCatalogTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}

    @Test public void catalogUsesExactImmutableIdentities(){
        Set<String> keys=new HashSet<>(),packages=new HashSet<>();
        for(AppArtworkCatalog.Entry entry:AppArtworkCatalog.all()){
            assertTrue(keys.add(entry.key));assertFalse(entry.title.isEmpty());assertFalse(entry.packages.isEmpty());
            assertTrue("Artwork declares exactly one bundled source",entry.drawable==0||entry.assetPath==null);
            for(String pkg:entry.packages){assertTrue(packages.add(pkg));assertSame(entry,AppArtworkCatalog.find(pkg));assertNull(AppArtworkCatalog.find(pkg+".fake"));}
            try{entry.packages.add("test.mutable");fail("Package aliases must be immutable");}catch(UnsupportedOperationException expected){}
        }
        assertNull(AppArtworkCatalog.find("YouTube"));assertNull(AppArtworkCatalog.find(null));
        try{AppArtworkCatalog.all().clear();fail("Catalog must be immutable");}catch(UnsupportedOperationException expected){}
    }

    @Test public void nativeApplicationIconPriorityIsLimitedToExactPublisherProfiles(){
        for(AppArtworkCatalog.Entry entry:AppArtworkCatalog.all()){
            boolean iconFirst="youtube".equals(entry.key)||"sendfiles".equals(entry.key)||"vimu".equals(entry.key);
            assertEquals(entry.key+": only the reviewed native icons replace TV banners",
                    iconFirst,entry.prefersApplicationIcon());
            assertEquals(entry.key+": only the two-tone counter needs its adaptive background",
                    "sendfiles".equals(entry.key),entry.keepsAdaptiveBackground());
            assertEquals(entry.key+": only the reviewed native red play icon has a white matte",
                    "youtube".equals(entry.key),entry.restoresRedWhiteMatte());
            assertEquals(entry.key+": only the reviewed dense zero-blue gradient has a white plate",
                    "vimu".equals(entry.key),entry.restoresZeroBlueWhitePlate());
            if(iconFirst){
                assertFalse("Publisher identity still comes from the installed app",entry.hasCuratedArtwork());
                for(String pkg:entry.packages){
                    assertSame(entry,AppArtworkCatalog.find(pkg));
                    assertNull("Similar package names cannot opt into another publisher's policy",
                            AppArtworkCatalog.find(pkg+".unrelated"));
                }
            }
        }
    }

    @Test public void bundledCatalogHasDocumentedIndependentRights() throws Exception {
        String json;
        try (java.io.InputStream input = context().getAssets().open("app-logos/catalog-sources.json");
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                assertTrue("Source metadata is bounded", bytes.size() + count <= 65536);
                bytes.write(buffer, 0, count);
            }
            json = bytes.toString("UTF-8");
        }
        org.json.JSONArray sources = new org.json.JSONObject(json).getJSONArray("assets");
        Set<String> documented = new HashSet<>();
        for (int i = 0; i < sources.length(); i++) {
            org.json.JSONObject source = sources.getJSONObject(i);
            String key = source.getString("key"), rights = source.getString("license");
            assertTrue(key + ": source identity is unique", documented.add(key));
            assertTrue(key + ": bundled rights must be explicit or documented public domain",
                    rights.startsWith("CC0") || rights.startsWith("MIT") || rights.contains("PD-textlogo"));
        }
        for (AppArtworkCatalog.Entry identity : AppArtworkCatalog.all()) {
            if (!identity.hasCuratedArtwork()) {
                assertFalse(identity.key + ": native fallback is not shipped as artwork", documented.contains(identity.key));
            } else if (!"settings".equals(identity.key)) {
                assertTrue(identity.key + ": bundled resource has documented rights", documented.remove(identity.key));
            }
        }
        assertTrue("Every documented logo is actually in the offline catalog", documented.isEmpty());
    }

    @Test public void everyBundledLogoRendersOfflineAndProducesReviewAtlases() throws Exception {
        final String[] activeKey={"none"};
        Context offline=new ContextWrapper(context()){
            @Override public PackageManager getPackageManager(){throw new AssertionError(activeKey[0]+": curated artwork must not consult installed apps or the network");}
        };
        List<AppArtworkCatalog.Entry> curated=new ArrayList<>();
        for(AppArtworkCatalog.Entry item:AppArtworkCatalog.all())if(item.hasCuratedArtwork())curated.add(item);
        assertFalse("Only actually bundled licensed marks belong in the offline atlas",curated.isEmpty());
        for(int palette=0;palette<2;palette++)for(int page=0;page<(curated.size()+11)/12;page++){
            Bitmap atlas=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(atlas);
            canvas.drawColor(palette==0?0xff141416:0xffe9e8e6);
            Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setColor(palette==0?Color.WHITE:0xff202024);text.setTextSize(34);
            canvas.drawText("ПРЕДПРОСМОТР ЗНАЧКОВ · "+(page+1)+" / "+((curated.size()+11)/12),52,60,text);
            for(int index=page*12;index<Math.min(curated.size(),page*12+12);index++){
                AppArtworkCatalog.Entry entry=curated.get(index);
                activeKey[0]=entry.key;
                if(entry.drawable!=0)assertNotNull(entry.key,context().getDrawable(entry.drawable));
                Bitmap tile=AppIconRenderer.render(offline,new AppEntry(new ComponentName(entry.packages.get(0),"preview.Activity"),entry.title),384,216,palette==1);
                try{
                    assertEquals(entry.key+" corner must remain transparent",0,Color.alpha(tile.getPixel(0,0)));
                    assertTransparentLogo(entry.key,tile,palette==1);
                    int slot=index-page*12,x=54+(slot%4)*472,y=110+(slot/4)*310;
                    canvas.drawBitmap(tile,x,y,null);text.setTextSize(25);canvas.drawText(entry.title,x,y+254,text);
                }finally{tile.recycle();}
            }
            text.setTextSize(20);canvas.drawText("Каталог оформления · не список установленных приложений",52,1052,text);
            save(atlas,"app-artwork-atlas-"+(palette==0?"dark":"light")+"-"+(page+1));atlas.recycle();
        }
    }

    @Test public void bundledMarksAtPhysicalPixelTargetsProduceReviewAtlases() throws Exception {
        Context offline=new ContextWrapper(context()){
            @Override public PackageManager getPackageManager(){throw new AssertionError("Bundled mark atlas is offline");}
        };
        List<AppArtworkCatalog.Entry> entries=new ArrayList<>();
        for(AppArtworkCatalog.Entry entry:AppArtworkCatalog.all())if(entry.hasCuratedArtwork())entries.add(entry);
        for(int palette=0;palette<2;palette++){
            boolean light=palette==1;
            Bitmap atlas=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);
            try{
                Canvas canvas=new Canvas(atlas);canvas.drawColor(light?0xffe9e8e6:0xff141416);
                Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);label.setColor(light?0xff202024:Color.WHITE);label.setTextSize(26);
                canvas.drawText("1080p icon viewport 165 x 120 px · retained 198 x 144 px for focus",40,44,label);
                Paint filter=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                for(int index=0;index<entries.size();index++){
                    AppArtworkCatalog.Entry entry=entries.get(index);
                    Bitmap tile=AppIconRenderer.render(offline,new AppEntry(new ComponentName(entry.packages.get(0),
                            "preview.Activity"),entry.title),198,144,light);
                    try{
                        assertTransparentLogo(entry.key,tile,light);
                        int x=40+(index%6)*312,y=90+(index/6)*236;
                        label.setTextSize(21);canvas.drawText(entry.title,x,y+24,label);
                        // This is the same small idle draw of the retained bitmap
                        // that a 1.20-headroom ImageView uses on the 1080p UI.
                        canvas.drawBitmap(tile,new android.graphics.Rect(0,0,198,144),
                                new android.graphics.Rect(x+42,y+40,x+207,y+160),filter);
                    }finally{tile.recycle();}
                }
                label.setTextSize(20);canvas.drawText("Actual small pixels · source artwork and alpha geometry unchanged",40,1050,label);
                save(atlas,"app-artwork-physical-target-"+(light?"light":"dark"));
            }finally{atlas.recycle();}
        }
    }

    @Test public void nativeAdaptiveLayersKeepTransparentCountersAndLegacyAspect(){
        for(boolean light:new boolean[]{false,true}){
            ShapeDrawable ring=new ShapeDrawable(new OvalShape());
            ring.getPaint().setColor(0xfffa145d);ring.getPaint().setStyle(Paint.Style.STROKE);ring.getPaint().setStrokeWidth(22);
            android.graphics.drawable.InsetDrawable inset=new android.graphics.drawable.InsetDrawable(ring,.28f);
            Bitmap adaptive=AppIconRenderer.nativeCard(new AdaptiveIconDrawable(new ColorDrawable(0xff1263ac),inset),384,216,light);
            try{
                assertEquals(0,Color.alpha(adaptive.getPixel(0,0)));
                assertTransparentLogo("Adaptive ring",adaptive,light);
                int base=base(adaptive);
                assertEquals("Transparent foreground counter must remain open",base,adaptive.getPixel(192,108));
                assertEquals("Opaque adaptive background must not become a nested glyph plate",adaptive.getPixel(20,50),adaptive.getPixel(100,50));
            }finally{adaptive.recycle();}
            Bitmap patternedSource=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
            Canvas pattern=new Canvas(patternedSource);pattern.drawColor(0xff1866c4);
            Paint ringPaint=new Paint(Paint.ANTI_ALIAS_FLAG);ringPaint.setColor(Color.WHITE);ringPaint.setStyle(Paint.Style.STROKE);ringPaint.setStrokeWidth(12);
            pattern.drawCircle(64,64,34,ringPaint);
            android.graphics.drawable.BitmapDrawable background=new android.graphics.drawable.BitmapDrawable(context().getResources(),patternedSource);
            Bitmap emptyForeground=AppIconRenderer.nativeCard(new AdaptiveIconDrawable(background,new ColorDrawable(Color.TRANSPARENT)),384,216,light);
            try{
                assertTransparentLogo("Empty adaptive foreground retains background art",emptyForeground,light);
                int center=Color.red(emptyForeground.getPixel(192,108)),rowBase=Color.red(emptyForeground.getPixel(20,108));
                int glyphLeft=384,glyphRight=-1,min=255,max=0,leftRing=0,rightRing=0;
                for(int x=40;x<344;x++)if(Math.abs(Color.red(emptyForeground.getPixel(x,108))-rowBase)>12){glyphLeft=Math.min(glyphLeft,x);glyphRight=Math.max(glyphRight,x);}
                assertTrue("The patterned fallback retains a visible source",glyphRight-glyphLeft>20);
                // Exclude the glyph's own edge, not just the card edge: outside
                // base or antialiasing cannot make a solid badge pass as a ring.
                int edgeInset=Math.max(3,(glyphRight-glyphLeft+1)/20);
                for(int x=glyphLeft+edgeInset;x<=glyphRight-edgeInset;x++){
                    int value=Color.red(emptyForeground.getPixel(x,108));min=Math.min(min,value);max=Math.max(max,value);
                    if(Math.abs(value-center)>30){if(x<192)leftRing++;else if(x>192)rightRing++;}
                }
                assertTrue("The source ring retains tonal structure on its central row",max-min>30);
                assertTrue("Both ring sides must remain distinct from their center, not a solid badge",leftRing>=2&&rightRing>=2);
            }finally{emptyForeground.recycle();patternedSource.recycle();}
            android.graphics.drawable.GradientDrawable wide=new android.graphics.drawable.GradientDrawable();wide.setShape(android.graphics.drawable.GradientDrawable.OVAL);wide.setColor(Color.RED);wide.setSize(400,100);
            Bitmap legacy=AppIconRenderer.nativeCard(wide,384,216,light);
            try{
                assertTransparentLogo("Wide legacy icon",legacy,light);
                assertTrue("Wide logo retains foreground",ink(legacy,192,108,light));
                assertEquals("Wide logo must not be stretched vertically",legacy.getPixel(20,70),legacy.getPixel(192,70));
            }finally{legacy.recycle();}
        }
    }

    @Test public void everyActuallyInstalledLaunchableAppHasBoundedArtwork(){
        List<AppEntry> apps=AppCatalog.load(context());assertFalse(apps.isEmpty());
        for(boolean light:new boolean[]{false,true}){
            Integer sharedBase=null;
            for(AppEntry app:apps){
                Bitmap tile=AppIconRenderer.render(context(),app,4096,4096,light);
                try{
                    assertEquals(768,tile.getWidth());assertEquals(768,tile.getHeight());
                    assertTransparentLogo(app.packageName,tile,light);
                    if(sharedBase==null)sharedBase=base(tile);
                    assertEquals(app.packageName+" leaves the glass visible around its mark",sharedBase.intValue(),base(tile));
                }finally{tile.recycle();}
            }
        }
    }

    static int base(Bitmap image){return image.getPixel(Math.max(1,image.getWidth()/20),image.getHeight()/2);}

    static boolean ink(Bitmap image,int x,int y,boolean light){
        return Color.alpha(image.getPixel(x,y))>=250;
    }

    static void assertTransparentLogo(String name,Bitmap image,boolean light){
        assertEquals(name+" has no generated inner face",0,Color.alpha(base(image)));
        for(int y:new int[]{image.getHeight()/4,image.getHeight()/2,image.getHeight()*3/4})
            for(int x:new int[]{image.getWidth()/20,image.getWidth()*19/20})
                assertEquals(name+" leaves the host glass visible at "+x+","+y,0,Color.alpha(image.getPixel(x,y)));
        int[] pixels=new int[image.getWidth()*image.getHeight()];
        image.getPixels(pixels,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());
        int detail=0;
        for(int index=0;index<pixels.length;index++){
            if(visibleOnNeutralFace(pixels[index],light))detail++;
        }
        assertTrue(name+" must retain a contrasting glyph",detail>Math.max(12,Math.min(60,image.getWidth()*image.getHeight()/900)));
    }

    /** A translucent publisher pixel can be visible without ever being opaque. */
    public static boolean visibleOnNeutralFace(int pixel,boolean light){
        if(Color.alpha(pixel)<32)return false;
        int face=light?0xffe9e8e6:0xff141416;
        double coverage=Color.alpha(pixel)/255.0;
        double red=Color.red(pixel)*coverage+Color.red(face)*(1-coverage);
        double green=Color.green(pixel)*coverage+Color.green(face)*(1-coverage);
        double blue=Color.blue(pixel)*coverage+Color.blue(face)*(1-coverage);
        double foreground=luminance(red,green,blue),background=luminance(Color.red(face),Color.green(face),Color.blue(face));
        // This guards against an empty/invisible image; it is not a text WCAG claim.
        return (Math.max(foreground,background)+.05)/(Math.min(foreground,background)+.05)>=1.25;
    }

    private static double luminance(double red,double green,double blue){
        return .2126*linear(red)+.7152*linear(green)+.0722*linear(blue);
    }

    private static double linear(double channel){
        double value=channel/255.0;
        return value<=.04045?value/12.92:Math.pow((value+.055)/1.055,2.4);
    }

    private void save(Bitmap image,String name) throws Exception {
        String run=InstrumentationRegistry.getArguments().getString("softlumiScreenshotRun","screenshots");assertTrue(run.matches("[A-Za-z0-9_-]+"));
        File folder=new File(context().getExternalFilesDir(null),run);assertTrue(folder.isDirectory()||folder.mkdirs());
        try(FileOutputStream output=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}
    }
}
