package io.github.ekkzy.evebox.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.ImageView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.ekkzy.evebox.data.WallpaperCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native bitmap/delivery regressions. Latches control ordering; no network or app preferences are used. */
@RunWith(AndroidJUnit4.class)
public final class UiImageLoaderTest {
    private Context context;
    private UiImageLoader loader;
    private final List<CountDownLatch> gates=new ArrayList<>();

    @Before public void prepare(){
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        main(()->{loader=new UiImageLoader(context);loader.setGeneration(1);loader.resume();});
    }

    @After public void close(){
        for(CountDownLatch gate:gates)gate.countDown();
        if(loader==null)return;
        main(()->loader.close());
        await("Closed workers must release their work",()->stats().running==0&&stats().queued==0);
        assertEquals(0,stats().targets);
    }

    @Test public void lateImageCannotOverwriteNewSourceInTheSameGeneration()throws Exception{
        ImageView target=image();CountDownLatch started=new CountDownLatch(1),release=gate();
        main(()->loader.request(target,"old",1,()->{started.countDown();hold(release);return bitmap(Color.RED);}));
        assertTrue(started.await(5,TimeUnit.SECONDS));
        main(()->loader.request(target,"new",1,()->bitmap(Color.GREEN)));
        await("The latest binding must publish while the old decode is still running",()->color(target)==Color.GREEN);
        release.countDown();idle();
        assertEquals("Late completion must not replace the latest image",Color.GREEN,color(target));
    }

    @Test public void returningToCachedSourceAlsoRevokesAnOlderDelivery()throws Exception{
        ImageView target=image();
        main(()->loader.request(target,"cached",1,()->bitmap(Color.GREEN)));
        await("Cache fixture is ready",()->color(target)==Color.GREEN);
        Bitmap cached=current(target);CountDownLatch started=new CountDownLatch(1),release=gate();
        main(()->loader.request(target,"slow",1,()->{started.countDown();hold(release);return bitmap(Color.RED);}));
        assertTrue(started.await(5,TimeUnit.SECONDS));
        main(()->loader.request(target,"cached",1,()->{throw new AssertionError("Cached source must not decode again");}));
        assertSame(cached,current(target));
        release.countDown();idle();
        assertSame("Cache rebinding must revoke the still-running source",cached,current(target));
    }

    @Test public void generationChangeDiscardsWaitingDecodesBeforeReturningHome()throws Exception{
        CountDownLatch release=holdBothWorkers();AtomicInteger staleDecodes=new AtomicInteger();List<ImageView> stale=new ArrayList<>();
        for(int i=0;i<12;i++){
            ImageView target=image();stale.add(target);String key="gallery-"+i;
            main(()->loader.request(target,key,1,()->{staleDecodes.incrementAndGet();return bitmap(Color.RED);}));
        }
        assertEquals("Fixture must exercise the waiting queue",12,stats().queued);
        ImageView home=image();
        main(()->{loader.setGeneration(2);loader.request(home,"home",2,()->bitmap(Color.BLUE));});
        release.countDown();
        await("Current Home work must not wait for stale gallery decodes",()->color(home)==Color.BLUE);idle();
        assertEquals("Requests without live receivers must never start decoding",0,staleDecodes.get());
        for(ImageView target:stale)assertNull(current(target));
    }

    @Test public void forgettingAnOffscreenQueuedPreviewStopsItsDecode()throws Exception{
        CountDownLatch release=holdBothWorkers();AtomicInteger decodes=new AtomicInteger();ImageView target=image();
        main(()->{loader.request(target,"offscreen",1,()->{decodes.incrementAndGet();return bitmap(Color.RED);});loader.forget(target);});
        release.countDown();idle();
        assertEquals(0,decodes.get());assertNull(current(target));
    }

    @Test public void forgettingOneReceiverKeepsTheOtherSharedImage()throws Exception{
        ImageView first=image(),second=image();AtomicInteger decodes=new AtomicInteger();
        CountDownLatch started=new CountDownLatch(1),release=gate();
        UiImageLoader.Decoder shared=()->{decodes.incrementAndGet();started.countDown();hold(release);return bitmap(Color.BLUE);};
        main(()->{loader.request(first,"shared",1,shared);loader.request(second,"shared",1,shared);});
        assertTrue(started.await(5,TimeUnit.SECONDS));main(()->loader.forget(first));release.countDown();
        await("A remaining visible receiver must still get the shared result",()->color(second)==Color.BLUE);idle();
        assertEquals(1,decodes.get());assertNull(current(first));
    }

    @Test public void pauseResumeRestartsOnlyCurrentWorkAndRejectsTheOldResult()throws Exception{
        ImageView target=image();AtomicInteger decodes=new AtomicInteger();CountDownLatch started=new CountDownLatch(1),release=gate();
        main(()->loader.request(target,"resumed",1,()->{
            if(decodes.incrementAndGet()==1){started.countDown();hold(release);return bitmap(Color.RED);}
            return bitmap(Color.BLUE);
        }));
        assertTrue(started.await(5,TimeUnit.SECONDS));
        main(()->{loader.pause();loader.resume();});
        await("Resume must service its current receiver",()->color(target)==Color.BLUE);
        release.countDown();idle();
        assertEquals("The cancelled pre-pause completion must not replace resumed work",Color.BLUE,color(target));
        assertEquals("Resume must not duplicate its active decode",2,decodes.get());
    }

    @Test public void closeReleasesReceiversAndRejectsLateResults()throws Exception{
        ImageView target=image();CountDownLatch started=new CountDownLatch(1),release=gate();AtomicInteger decodes=new AtomicInteger();
        main(()->loader.request(target,"closing",1,()->{decodes.incrementAndGet();started.countDown();hold(release);return bitmap(Color.RED);}));
        assertTrue(started.await(5,TimeUnit.SECONDS));
        main(()->{loader.close();loader.resume();loader.request(target,"after-close",1,()->{decodes.incrementAndGet();return bitmap(Color.BLUE);});});
        assertEquals(0,stats().jobs);assertEquals(0,stats().targets);
        release.countDown();await("Closed decode is released",()->stats().running==0);
        assertNull(current(target));assertEquals(1,decodes.get());assertEquals(0,stats().cacheBytes);
    }

    @Test public void forgettingRunningTransfersReleasesBothWorkersWithoutWaitingForTheirTimeout()throws Exception{
        ImageView first=image(),second=image(),latest=image();
        CountDownLatch started=new CountDownLatch(2),cancelled=new CountDownLatch(2);
        AtomicInteger cancellationsOnMain=new AtomicInteger();
        CountDownLatch firstRelease=gate(),secondRelease=gate();
        main(()->{
            loader.requestCancellable(first,"blocked-first",1,work->cancelledTransfer(work,firstRelease,started,cancelled,cancellationsOnMain));
            loader.requestCancellable(second,"blocked-second",1,work->cancelledTransfer(work,secondRelease,started,cancelled,cancellationsOnMain));
        });
        assertTrue(started.await(5,TimeUnit.SECONDS));
        main(()->{loader.forget(first);loader.forget(second);loader.request(latest,"latest",1,()->bitmap(Color.BLUE));});
        assertTrue("Each abandoned transfer must receive cancellation",cancelled.await(5,TimeUnit.SECONDS));
        await("Visible work must use the released workers",()->color(latest)==Color.BLUE);
        assertEquals("Provider cancellation must not block the UI thread",0,cancellationsOnMain.get());
        assertNull(current(first));assertNull(current(second));idle();
    }

    @Test public void emptyPosterRevokesAnOldBindingButStaleCallsCannotClearTheCurrentImage(){
        ImageView target=image();
        main(()->loader.request(target,"initial",1,()->bitmap(Color.GREEN)));
        await("Initial image is ready",()->color(target)==Color.GREEN);
        main(()->loader.poster(target,"",100,100,1));
        assertNull("An empty replacement source must clear the old poster",current(target));
        main(()->{loader.setGeneration(2);loader.request(target,"current",2,()->bitmap(Color.BLUE));});
        await("Current image is ready",()->color(target)==Color.BLUE);
        main(()->{loader.poster(target,null,100,100,1);loader.logo(target,"",100,100,1,false);});
        assertEquals("A stale generation must never revoke the current binding",Color.BLUE,color(target));
    }

    @Test public void artworkDecodeUsesAndroidBackgroundPriority(){
        ImageView target=image();AtomicInteger priority=new AtomicInteger(Integer.MIN_VALUE);
        main(()->loader.request(target,"thread-priority",1,()->{
            priority.set(android.os.Process.getThreadPriority(android.os.Process.myTid()));return bitmap(Color.BLUE);
        }));
        await("Worker decoded the fixture",()->color(target)==Color.BLUE);
        assertEquals("Pixel work must yield CPU scheduling priority to input/rendering",
                android.os.Process.THREAD_PRIORITY_BACKGROUND,priority.get());
    }

    private static Bitmap cancelledTransfer(UiImageLoader.Work work,CountDownLatch release,CountDownLatch started,
            CountDownLatch cancelled,AtomicInteger cancellationsOnMain)throws Exception{
        work.onCancel(()->{
            if(Looper.myLooper()==Looper.getMainLooper())cancellationsOnMain.incrementAndGet();
            cancelled.countDown();release.countDown();
        });
        started.countDown();
        long deadline=SystemClock.elapsedRealtime()+10000;
        while(release.getCount()>0&&SystemClock.elapsedRealtime()<deadline){
            try{release.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){ /* Model blocking provider I/O. */ }
        }
        if(release.getCount()>0)throw new IllegalStateException("Transfer fixture timed out");
        return bitmap(Color.RED);
    }

    @Test public void wallpaperPreviewRetainsItsRequestedPixelSize(){
        ImageView target=image();int resource=WallpaperCatalog.resource(context,WallpaperCatalog.NIGHT_LANDSCAPE);
        for(int[] size:new int[][]{{369,204},{738,408}}){
            main(()->{loader.forget(target);loader.wallpaper(target,resource,size[0],size[1],1);});
            await("Wallpaper preview decode is ready",()->current(target)!=null);
            Bitmap result=current(target);
            assertEquals("Preview must not enlarge an undersampled bitmap",size[0],result.getWidth());
            assertEquals(size[1],result.getHeight());
            assertTrue(result.getAllocationByteCount()<=1920*1080*4);
        }
        idle();
    }

    private CountDownLatch holdBothWorkers()throws Exception{
        CountDownLatch started=new CountDownLatch(2),release=gate();ImageView first=image(),second=image();
        main(()->{
            loader.request(first,"busy-first",1,()->{started.countDown();hold(release);return bitmap(Color.GRAY);});
            loader.request(second,"busy-second",1,()->{started.countDown();hold(release);return bitmap(Color.GRAY);});
        });
        assertTrue("Both workers must be held before filling the queue",started.await(5,TimeUnit.SECONDS));
        return release;
    }
    private CountDownLatch gate(){CountDownLatch gate=new CountDownLatch(1);gates.add(gate);return gate;}
    private static void hold(CountDownLatch release)throws InterruptedException{
        if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Controlled decode fixture timed out");
    }
    private ImageView image(){AtomicReference<ImageView> result=new AtomicReference<>();main(()->result.set(new ImageView(context)));return result.get();}
    private static Bitmap bitmap(int color){Bitmap result=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);result.eraseColor(color);return result;}
    private static Bitmap current(ImageView target){
        AtomicReference<Bitmap> result=new AtomicReference<>();main(()->{if(target.getDrawable() instanceof BitmapDrawable)result.set(((BitmapDrawable)target.getDrawable()).getBitmap());});return result.get();
    }
    private static int color(ImageView target){Bitmap result=current(target);return result==null?Color.TRANSPARENT:result.getPixel(0,0);}
    private UiImageLoader.Diagnostics stats(){AtomicReference<UiImageLoader.Diagnostics> result=new AtomicReference<>();main(()->result.set(loader.diagnostics()));return result.get();}
    private void idle(){await("All requested work has completed",()->{UiImageLoader.Diagnostics state=stats();return state.jobs==0&&state.running==0&&state.queued==0;});}
    private static void await(String message,BooleanSupplier condition){
        long deadline=SystemClock.elapsedRealtime()+5000;
        while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(10);
        assertTrue(message,condition.getAsBoolean());
    }
    private static void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
}
