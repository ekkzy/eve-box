package io.github.ekkzy.evebox.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Redrawing or changing a procedural scene must not inherit the previous paint alpha. */
@RunWith(AndroidJUnit4.class)
public final class SceneBackgroundTest {
    @Test public void everySceneKeepsItsPixelsAcrossRepeatedDrawsAndThemeChanges() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.evebox.debug",context.getPackageName());
        assertEquals("Owned emulator only","1",UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand("getprop ro.kernel.qemu").trim());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            SceneBackground reused=new SceneBackground(context);reused.layout(0,0,640,360);
            for(int theme=0;theme<4;theme++)for(int background=0;background<4;background++){
                SceneBackground fresh=new SceneBackground(context);fresh.layout(0,0,640,360);fresh.setScene(theme,background);
                Bitmap expected=render(fresh);
                try{
                    reused.setScene(theme,background);
                    for(int repeat=0;repeat<3;repeat++){
                        Bitmap actual=render(reused);
                        try{assertTrue("Stable pixels including alpha: theme="+theme+", background="+background+", repeat="+repeat,
                                expected.sameAs(actual));}
                        finally{actual.recycle();}
                    }
                }finally{expected.recycle();}
            }
        });
    }

    private static Bitmap render(SceneBackground scene){
        Bitmap bitmap=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);
        scene.draw(new Canvas(bitmap));return bitmap;
    }
}
