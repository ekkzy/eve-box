package io.github.ekkzy.softlumi.ui;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import io.github.ekkzy.softlumi.data.AppEntry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Main-thread bindings, one bounded decode queue and a shared bitmap cache. Identical requests share a decode. */
public final class UiImageLoader {
    private static final String PHOTO_FORMAT_VERSION="argb8888-v3-cover";
    interface Decoder { Bitmap decode() throws Exception; }
    interface CancellableDecoder { Bitmap decode(Work work) throws Exception; }
    private static final class Target {
        final ImageView view; final int generation;
        Target(ImageView view,int generation){this.view=view;this.generation=generation;}
    }
    private static final class Job {
        final String key; final CancellableDecoder decoder; final List<Target> targets=new ArrayList<>();
        Work work; int attempts;
        Job(String key,CancellableDecoder decoder){this.key=key;this.decoder=decoder;}
    }
    static final class Work implements Runnable {
        final UiImageLoader owner; final Job job;
        final int generation;
        private final AtomicReference<Runnable> cancellation=new AtomicReference<>();
        private Thread runner;
        volatile boolean cancelled;
        Work(UiImageLoader owner,Job job){this.owner=owner;this.job=job;generation=owner.generation;}
        @Override public void run(){
            synchronized(this){if(cancelled)return;runner=Thread.currentThread();}
            try{owner.decode(this);}finally{synchronized(this){runner=null;}cancellation.set(null);}
        }
        /** Provider cancellation/stream close may block; it always runs outside the UI thread. */
        void onCancel(Runnable action){
            cancellation.set(action);
            if(cancelled)owner.abort(cancellation.getAndSet(null));
        }
        void clearCancellation(Runnable action){cancellation.compareAndSet(action,null);}
        void cancel(){
            synchronized(this){cancelled=true;if(runner!=null)runner.interrupt();}
            owner.abort(cancellation.getAndSet(null));
        }
    }
    /** Read-only counters; source URIs and receiver identities never leave the loader. */
    public static final class Diagnostics {
        public final int jobs,targets,queued,running,cacheBytes,cacheHits,decodeStarts,deliveries,cancelled;
        private Diagnostics(int jobs,int targets,int queued,int running,int cacheBytes,int cacheHits,int decodeStarts,int deliveries,int cancelled){
            this.jobs=jobs;this.targets=targets;this.queued=queued;this.running=running;this.cacheBytes=cacheBytes;
            this.cacheHits=cacheHits;this.decodeStarts=decodeStarts;this.deliveries=deliveries;this.cancelled=cancelled;
        }
    }
    private final Context app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(2,2,20,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24),task->
            new Thread(()->{Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run();},"eve-box-artwork"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timeouts=Executors.newScheduledThreadPool(1);
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(12*1024*1024){
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
    };
    private volatile boolean active,closed;
    private volatile int generation;
    private boolean retryPending;
    private int cacheHits,deliveries,cancelled;
    private final AtomicInteger running=new AtomicInteger(),decodeStarts=new AtomicInteger();

    public UiImageLoader(Context context){app=context.getApplicationContext();io.allowCoreThreadTimeOut(true);}
    public void setGeneration(int generation){
        this.generation=generation;
        Iterator<Job> iterator=jobs.values().iterator();
        while(iterator.hasNext()){
            Job job=iterator.next();job.targets.removeIf(target->target.generation!=generation);
            if(job.targets.isEmpty()){cancel(job);iterator.remove();}
        }
    }
    public void resume(){if(closed)return;active=true;drain();}
    public void pause(){
        active=false;main.removeCallbacks(retry);retryPending=false;
        for(Job job:jobs.values())cancel(job);
    }
    public void trim(){cache.evictAll();}
    public void close(){
        if(closed)return;
        closed=true;pause();for(Job job:jobs.values())job.targets.clear();jobs.clear();cache.evictAll();io.shutdownNow();timeouts.shutdown();
        // Posted deliveries must still run their closed guard and release unpublished bitmaps.
    }
    /** Called on the UI thread; worker/queue counts are observations, not a synchronization barrier. */
    public Diagnostics diagnostics(){
        int targets=0;for(Job job:jobs.values())targets+=job.targets.size();
        return new Diagnostics(jobs.size(),targets,io.getQueue().size(),running.get(),cache.size(),cacheHits,decodeStarts.get(),deliveries,cancelled);
    }

    public void wallpaper(ImageView target,int resource,int width,int height,int generation){
        int w=Math.max(1,Math.min(1920,width)),h=Math.max(1,Math.min(1080,height));
        // Preserve photo/gradient precision before glass blur and color filters.
        // The retained cache remains bounded by actual allocated bytes.
        Bitmap.Config format=Bitmap.Config.ARGB_8888;
        request(target,"wallpaper:"+PHOTO_FORMAT_VERSION+":"+resource+":"+w+"x"+h,generation,()->{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeResource(app.getResources(),resource,bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;
            options.inSampleSize=wallpaperSampleSize(bounds.outWidth,bounds.outHeight,w,h);options.inPreferredConfig=format;
            return cropPoster(BitmapFactory.decodeResource(app.getResources(),resource,options),w,h);
        });
    }
    public void icon(ImageView target,AppEntry entry,int width,int height,int generation,boolean light){
        int w=Math.max(1,Math.min(AppIconRenderer.MAX_ICON_SIZE,width)),h=Math.max(1,Math.min(AppIconRenderer.MAX_ICON_SIZE,height));
        request(target,"icon:"+AppIconRenderer.CACHE_VERSION+":"+(light?"light":"dark")+":"+entry.id+":"+entry.artworkVersion+":"+w+"x"+h,generation,()->AppIconRenderer.render(app,entry,w,h,light));
    }
    /** Offline compact provider mark. The caller owns any surrounding badge surface. */
    public void logo(ImageView target,String packageName,int width,int height,int generation,boolean light){
        logo(target,packageName,"",width,height,generation,light);
    }
    public void logo(ImageView target,String packageName,String artworkVersion,int width,int height,int generation,boolean light){
        if(closed||generation!=this.generation)return;
        // A recycled badge may change provider without changing screen generation.
        // Remove old deliveries before binding its new exact package identity.
        forget(target);
        if(packageName==null||packageName.isEmpty())return;
        int w=Math.max(1,Math.min(AppIconRenderer.MAX_ICON_SIZE,width)),h=Math.max(1,Math.min(AppIconRenderer.MAX_ICON_SIZE,height));
        request(target,"logo:"+AppIconRenderer.CACHE_VERSION+":"+(light?"light":"dark")+":"+packageName+":"+artworkVersion+":"+w+"x"+h,
                generation,()->AppIconRenderer.renderLogo(app,packageName,w,h,light));
    }
    public void poster(ImageView target,String uri,int width,int height,int generation){
        if(closed||generation!=this.generation)return;
        if(uri==null||uri.isEmpty()){forget(target);return;}
        int w=Math.max(1,Math.min(960,width)),h=Math.max(1,Math.min(540,height));
        requestCancellable(target,"poster:"+PHOTO_FORMAT_VERSION+":"+uri+":"+w+"x"+h,generation,work->readPoster(uri,w,h,work));
    }
    /** A far-offscreen poster stops retaining pixels and pending delivery targets. */
    public void forget(ImageView target){
        removeReceivers(target,null);
        if(target.getDrawable()!=null)target.setImageDrawable(null);
        if(target.getTag() instanceof View)((View)target.getTag()).setVisibility(View.VISIBLE);
    }
    private void removeReceivers(ImageView target,String keepKey){
        Iterator<Job> iterator=jobs.values().iterator();
        while(iterator.hasNext()){
            Job job=iterator.next();if(job.key.equals(keepKey))continue;
            job.targets.removeIf(waiter->waiter.view==target);
            if(job.targets.isEmpty()){cancel(job);iterator.remove();}
        }
    }
    // Package visibility lets the native tests control completion order without network or timing guesses.
    void request(ImageView target,String key,int generation,Decoder decoder){
        requestCancellable(target,key,generation,work->decoder.decode());
    }
    void requestCancellable(ImageView target,String key,int generation,CancellableDecoder decoder){
        if(closed||generation!=this.generation)return;
        removeReceivers(target,key);
        Bitmap hit=cache.get(key);if(hit!=null){cacheHits++;apply(target,hit);return;}
        Job job=jobs.get(key);
        if(job==null){job=new Job(key,decoder);jobs.put(key,job);}
        job.targets.removeIf(waiter->waiter.generation!=this.generation||waiter.view==target);
        job.targets.add(new Target(target,generation));drain();
    }
    private final Runnable retry=()->{retryPending=false;drain();};
    private void drain(){
        if(!active||closed)return;
        for(Job job:new ArrayList<>(jobs.values())){
            if(job.work!=null||job.targets.isEmpty())continue;
            Work work=new Work(this,job);job.work=work;
            try{io.execute(work);}
            catch(RejectedExecutionException rejected){
                job.work=null;
                if(!retryPending){retryPending=true;main.postDelayed(retry,100);}break;
            }
        }
    }
    private void cancel(Job job){
        Work work=job.work;if(work==null)return;
        work.cancel();io.remove(work);job.work=null;cancelled++;
    }
    private void abort(Runnable action){
        if(action!=null)try{timeouts.execute(action);}catch(RejectedExecutionException stopped){}
    }
    private void decode(Work work){
        // Invalidating a generation may interrupt a running worker before the
        // UI finishes removing the rest of its old queued jobs. Reject them
        // here too, before a released worker can enter an obsolete decoder.
        if(work.cancelled||work.generation!=generation||!active||closed)return;
        running.incrementAndGet();decodeStarts.incrementAndGet();
        Bitmap bitmap=null;
        try{
            bitmap=work.job.decoder.decode(work);
            // Finish crop/resize before upload. Only requested visible/nearby images reach here.
            if(bitmap!=null&&!work.cancelled&&active&&!closed)bitmap.prepareToDraw();
        }catch(Exception ignored){if(bitmap!=null)bitmap.recycle();bitmap=null;}
        finally{running.decrementAndGet();}
        if(work.cancelled||closed){if(bitmap!=null)bitmap.recycle();return;}
        Bitmap result=bitmap;
        main.post(()->{
            Job job=work.job;
            if(closed||work.cancelled||jobs.get(job.key)!=job||job.work!=work){if(result!=null)result.recycle();return;}
            job.work=null;
            if(result!=null){
                cache.put(job.key,result);
                if(active)for(Target target:job.targets)if(target.generation==generation)apply(target.view,result);
                jobs.remove(job.key);
            }else if(active&&++job.attempts>=2)jobs.remove(job.key);
            if(active)drain();
        });
    }
    private void apply(ImageView target,Bitmap bitmap){
        target.setImageBitmap(bitmap);
        deliveries++;
        if(target.getTag() instanceof View)((View)target.getTag()).setVisibility(View.GONE);
    }

    private Bitmap readPoster(String uri,int width,int height,Work work)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+7000;
        CancellationSignal cancellation=new CancellationSignal();
        AtomicReference<InputStream> streamRef=new AtomicReference<>();
        AtomicReference<HttpURLConnection> connectionRef=new AtomicReference<>();
        Runnable cancel=()->{
            try{cancellation.cancel();}catch(RuntimeException ignored){}
            InputStream input=streamRef.get();if(input!=null)try{input.close();}catch(Exception ignored){}
            HttpURLConnection connection=connectionRef.get();if(connection!=null)try{connection.disconnect();}catch(RuntimeException ignored){}
        };
        work.onCancel(cancel);
        ScheduledFuture<?> timeout;
        try{timeout=timeouts.schedule(cancel,7,TimeUnit.SECONDS);}catch(RejectedExecutionException stopped){work.clearCancellation(cancel);return null;}
        AssetFileDescriptor descriptor=null;HttpURLConnection connection=null;
        try{
            if(work.cancelled||!active||closed)return null;
            Uri source=Uri.parse(uri);InputStream opened;
            if("content".equals(source.getScheme())||"android.resource".equals(source.getScheme())){
                descriptor=app.getContentResolver().openAssetFileDescriptor(source,"r",cancellation);
                if(descriptor==null)return null;opened=descriptor.createInputStream();
            }else if("https".equals(source.getScheme())){
                connection=(HttpURLConnection)new URL(uri).openConnection();connectionRef.set(connection);
                connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setInstanceFollowRedirects(false);
                if(connection.getResponseCode()!=200)return null;opened=connection.getInputStream();
            }else return null;
            if(opened==null)return null;streamRef.set(opened);
            byte[] bytes;
            try(InputStream input=opened;ByteArrayOutputStream output=new ByteArrayOutputStream()){
                cancellation.throwIfCanceled();
                if(!active||closed||Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>deadline)return null;
                byte[] buffer=new byte[8192];int count;
                while(true){
                    cancellation.throwIfCanceled();
                    if(!active||closed||Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>deadline)return null;
                    count=input.read(buffer);if(count==-1)break;
                    if(!active||closed||Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>deadline||output.size()+count>4*1024*1024)return null;
                    output.write(buffer,0,count);
                }bytes=output.toByteArray();
            }
            cancellation.throwIfCanceled();if(!active||closed)return null;
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            options.inSampleSize=posterSampleSize(bounds.outWidth,bounds.outHeight,width,height);
            return cropPoster(BitmapFactory.decodeByteArray(bytes,0,bytes.length,options),width,height);
        }finally{
            timeout.cancel(false);work.clearCancellation(cancel);streamRef.set(null);
            if(descriptor!=null)try{descriptor.close();}catch(Exception ignored){}
            if(connection!=null)connection.disconnect();
        }
    }
    static int wallpaperSampleSize(int sourceWidth,int sourceHeight,int width,int height){
        if(sourceWidth<1||sourceHeight<1||width<1||height<1)throw new IllegalArgumentException("Positive image dimensions required");
        int sample=1;
        // Decode enough real pixels to cover the preview, then crop/resize once.
        while(sourceWidth/(sample*2L)>=width&&sourceHeight/(sample*2L)>=height)sample*=2;
        // Even extreme source aspect ratios cannot allocate a decode beyond this rectangle.
        while((sourceWidth+(long)sample-1)/sample>1920||(sourceHeight+(long)sample-1)/sample>1080)sample*=2;
        return sample;
    }
    static int posterSampleSize(int sourceWidth,int sourceHeight,int width,int height){
        // Area/sample² underestimates a very thin image: decoded dimensions
        // round up to at least one pixel. Apply the actual rectangle budget.
        return wallpaperSampleSize(sourceWidth,sourceHeight,width,height);
    }
    private static Bitmap cropPoster(Bitmap source,int width,int height){
        if(source==null)return null;
        int sw=source.getWidth(),sh=source.getHeight(),cw=sw,ch=sh;
        if((long)sw*height>(long)sh*width)cw=Math.max(1,Math.min(sw,Math.round(sh*(width/(float)height))));
        else ch=Math.max(1,Math.min(sh,Math.round(sw*(height/(float)width))));
        Bitmap cropped=Bitmap.createBitmap(source,(sw-cw)/2,(sh-ch)/2,cw,ch);
        if(cropped!=source)source.recycle();
        return photoBound(cropped,width,height);
    }
    private static Bitmap photoBound(Bitmap source,int width,int height){
        return photoBound(source,width,height,Bitmap.Config.ARGB_8888);
    }
    private static Bitmap photoBound(Bitmap source,int width,int height,Bitmap.Config format){
        if(source==null)return null;
        float scale=Math.min(1f,Math.min(width/(float)source.getWidth(),height/(float)source.getHeight()));
        Bitmap bitmap=source;
        if(scale<1){bitmap=Bitmap.createScaledBitmap(source,Math.max(1,Math.round(source.getWidth()*scale)),Math.max(1,Math.round(source.getHeight()*scale)),true);if(bitmap!=source)source.recycle();}
        if(bitmap.getConfig()!=format){Bitmap rgb=bitmap.copy(format,false);if(rgb!=null){bitmap.recycle();bitmap=rgb;}}
        return bitmap;
    }
}
