package io.github.ekkzy.evebox.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** SoundPool stream acceptance is tested; this makes no claim about the audible output route. */
@RunWith(AndroidJUnit4.class)
public final class UiSoundsLifecycleTest {
    private UiSounds sounds;
    @Before public void prepare(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        main(()->{sounds=new UiSounds(context);sounds.configure(true);sounds.resume();});ready();
    }
    @After public void close(){if(sounds!=null)main(()->sounds.close());}

    @Test public void rapidMoveCuesAreDroppedWhileSelectAndBackStayImmediate(){
        main(()->{
            long requests=sounds.getPlayRequestCount(),started=sounds.getStartedPlayCount();
            sounds.move();sounds.move();sounds.move();sounds.select();sounds.back();
            assertEquals(requests+5,sounds.getPlayRequestCount());
            assertEquals("One move plus immediate SELECT and BACK must start",started+3,sounds.getStartedPlayCount());
        });
        SystemClock.sleep(80);
        main(()->{long started=sounds.getStartedPlayCount();sounds.move();assertEquals(started+1,sounds.getStartedPlayCount());});
    }

    @Test public void pauseAndCloseDropCuesAndResumeStartsWithNoRetainedThrottle(){
        main(()->{
            sounds.move();sounds.pause();long started=sounds.getStartedPlayCount();
            sounds.move();sounds.select();assertEquals(started,sounds.getStartedPlayCount());assertFalse(sounds.isReady());
            sounds.resume();
        });
        ready();
        main(()->{
            long started=sounds.getStartedPlayCount();sounds.move();assertEquals(started+1,sounds.getStartedPlayCount());
            sounds.close();sounds.resume();sounds.select();assertEquals(started+1,sounds.getStartedPlayCount());assertFalse(sounds.isReady());
        });
    }

    private void ready(){
        AtomicBoolean ready=new AtomicBoolean();long deadline=SystemClock.uptimeMillis()+5000;
        while(!ready.get()&&SystemClock.uptimeMillis()<deadline){main(()->ready.set(sounds.isReady()));if(!ready.get())SystemClock.sleep(10);}
        assertTrue("Native SoundPool must load the three bounded cues",ready.get());
    }
    private static void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
}
