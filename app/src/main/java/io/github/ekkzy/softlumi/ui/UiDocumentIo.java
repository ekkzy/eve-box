package io.github.ekkzy.softlumi.ui;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated bounded SAF work. A document provider cannot occupy catalog/weather workers. */
public final class UiDocumentIo {
    public interface Callback { void complete(boolean success,String text); }
    private static final int MAX_BYTES=65536;
    private static final class Transfer {
        final CancellationSignal signal=new CancellationSignal();
        final AtomicReference<AssetFileDescriptor> descriptor=new AtomicReference<>();
        final AtomicReference<InputStream> input=new AtomicReference<>();
        final AtomicReference<OutputStream> output=new AtomicReference<>();
        volatile boolean cancelled;Future<?> future;long deadline;Runnable timeout;Callback callback;
        void closeProvider(){
            try{signal.cancel();}catch(RuntimeException ignored){}
            try{InputStream stream=input.get();if(stream!=null)stream.close();}catch(Exception ignored){}
            try{OutputStream stream=output.get();if(stream!=null)stream.close();}catch(Exception ignored){}
            try{AssetFileDescriptor file=descriptor.get();if(file!=null)file.close();}catch(Exception ignored){}
        }
    }
    private final Context app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService watchdog=Executors.newScheduledThreadPool(1);
    private Transfer current;private boolean closed;
    public UiDocumentIo(Context context){app=context.getApplicationContext();io.allowCoreThreadTimeOut(true);}
    public void read(Uri uri,Callback callback){start(uri,null,callback);}
    public void write(Uri uri,String json,Callback callback){
        if(json==null||json.isEmpty()||json.length()>MAX_BYTES){cancel();if(!closed)callback.complete(false,"");return;}
        start(uri,json,callback);
    }
    public void cancel(){
        Transfer transfer=current;current=null;io.getQueue().clear();
        if(transfer==null)return;transfer.cancelled=true;transfer.callback=null;main.removeCallbacks(transfer.timeout);if(transfer.future!=null)transfer.future.cancel(true);
        try{watchdog.execute(transfer::closeProvider);}catch(RejectedExecutionException ignored){}
    }
    public void close(){closed=true;cancel();io.shutdownNow();watchdog.shutdown();main.removeCallbacksAndMessages(null);}
    private void start(Uri uri,String json,Callback callback){
        cancel();if(closed)return;Transfer transfer=new Transfer();transfer.callback=callback;current=transfer;
        transfer.deadline=SystemClock.elapsedRealtime()+8000;
        transfer.timeout=()->{
            if(closed||current!=transfer)return;
            transfer.cancelled=true;if(transfer.future!=null)transfer.future.cancel(true);
            complete(transfer,false,"");
            try{watchdog.execute(transfer::closeProvider);}catch(RejectedExecutionException ignored){}
        };
        main.postDelayed(transfer.timeout,8000);
        try{transfer.future=io.submit(()->run(transfer,uri,json));}
        catch(RejectedExecutionException full){main.removeCallbacks(transfer.timeout);complete(transfer,false,"");}
    }
    private void complete(Transfer transfer,boolean success,String text){
        if(closed||current!=transfer)return;current=null;
        Callback callback=transfer.callback;transfer.callback=null;
        if(callback!=null)callback.complete(success&&!transfer.cancelled,text);
    }
    private static void check(Transfer transfer,long deadline){
        transfer.signal.throwIfCanceled();if(transfer.cancelled||Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("Document transfer stopped");
    }
    private void run(Transfer transfer,Uri uri,String json){
        long deadline=transfer.deadline;boolean success=false;String value="";
        try{
            check(transfer,deadline);
            // Validate encoded size before opening "wt": providers may truncate on open.
            byte[] bytes=json==null?null:json.getBytes(StandardCharsets.UTF_8);
            if(bytes!=null&&(bytes.length>MAX_BYTES||bytes.length==0))throw new IllegalArgumentException();
            try(AssetFileDescriptor descriptor=app.getContentResolver().openAssetFileDescriptor(uri,json==null?"r":"wt",transfer.signal)){
                if(descriptor==null)throw new IllegalStateException();transfer.descriptor.set(descriptor);check(transfer,deadline);
                if(json==null){
                    if(descriptor.getLength()>MAX_BYTES)throw new IllegalArgumentException();
                    try(InputStream input=descriptor.createInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                        transfer.input.set(input);byte[] buffer=new byte[4096];int count;
                        while(true){check(transfer,deadline);count=input.read(buffer);if(count<0)break;if(output.size()+count>MAX_BYTES)throw new IllegalArgumentException();output.write(buffer,0,count);}
                        check(transfer,deadline);value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
                    }
                }else{
                    try(OutputStream output=descriptor.createOutputStream()){transfer.output.set(output);for(int offset=0;offset<bytes.length;offset+=4096){check(transfer,deadline);output.write(bytes,offset,Math.min(4096,bytes.length-offset));}check(transfer,deadline);}
                }
                check(transfer,deadline);success=true;
            }
        }catch(Exception ignored){}finally{main.removeCallbacks(transfer.timeout);transfer.input.set(null);transfer.output.set(null);transfer.descriptor.set(null);}
        boolean complete=success;String text=value;
        main.post(()->complete(transfer,complete,text));
    }
}
