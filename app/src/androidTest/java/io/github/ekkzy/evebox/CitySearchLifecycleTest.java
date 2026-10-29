package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WeatherRepository;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Reproduces leaving the city screen while its first request is still running. */
@RunWith(AndroidJUnit4.class)
public final class CitySearchLifecycleTest {
    @Test public void returningToCityScreenRestartsCancelledSearch() throws Exception { exercise(false); }
    @Test public void returningAfterImmediateSubmitRestartsCancelledSearch() throws Exception { exercise(true); }

    @Test public void reopeningRetainedCityDuringBackFadeRestartsCancelledSearch() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.evebox.debug",context.getPackageName());
        SharedPreferences store=context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE);
        Object previousWeather=store.getAll().get("weather_enabled");
        Object previousMotion=store.getAll().get("reduced_motion");
        assertTrue(store.edit().putBoolean("weather_enabled",false).putBoolean("reduced_motion",false).commit());
        CountDownLatch started=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        AtomicInteger requests=new AtomicInteger();
        WeatherRepository.Transport transport=url->{
            assertTrue("Only city search belongs to this fixture",url.contains("geocoding-api.open-meteo.com"));
            assertTrue("Retained input keeps the same query",url.contains("name=Testville"));
            if(requests.incrementAndGet()==1){
                started.countDown();
                try { if(!new CountDownLatch(1).await(10,TimeUnit.SECONDS)) throw new AssertionError("First request was never cancelled"); }
                catch(InterruptedException expected){cancelled.countDown();throw new InterruptedIOException("fixture cancelled");}
            }
            return "{\"results\":[{\"id\":123,\"name\":\"Testville\",\"latitude\":55,\"longitude\":37,\"country\":\"Fixture\"}]}";
        };
        WeatherRepository.Cache cache=new WeatherRepository.Cache(){
            public WeatherRepository.Weather read(String key){return null;}
            public void write(String key,WeatherRepository.Weather value){}
        };
        WeatherRepository repository=new WeatherRepository(transport,cache,new WeatherRepository.JsonDecoder(),System::currentTimeMillis);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            scene.onActivity(activity->assertTrue("Retained-root regression requires actual system animation frames",ValueAnimator.areAnimatorsEnabled()));
            awaitState(scene,"Initial catalog and Home settle",activity->{
                Future<?> load=(Future<?>)get(activity,"loadFuture");
                return (Boolean)get(activity,"catalogLoaded") && load!=null && load.isDone()
                        && !(Boolean)get(activity,"renderDirty") && !motion(activity).isRunning();
            },10000);
            scene.onActivity(activity->{
                try{field("weatherRepository").set(activity,repository);}
                catch(Exception failure){throw new AssertionError(failure);}
                push(activity,"WEATHER");
            });
            awaitSettled(scene,"WEATHER");
            scene.onActivity(activity->assertTrue(tag(activity,"weather-city").performClick()));
            awaitSettled(scene,"CITY");
            AtomicReference<View> originalRoot=new AtomicReference<>(),originalInput=new AtomicReference<>();
            scene.onActivity(activity->{
                originalRoot.set((View)get(activity,"root"));
                EditText input=(EditText)tag(activity,"city-query");originalInput.set(input);
                input.setText("Testville");
            });
            assertTrue("Initial query started before leaving CITY",started.await(4,TimeUnit.SECONDS));
            CountDownLatch reopened=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
            scene.onActivity(activity->{
                activity.onBackPressed();
                assertEquals("WEATHER",get(activity,"screen").toString());
                // Arm in the same dispatch as Back: an onActivity/idle wait here
                // could let the fade complete and silently exercise a new root.
                long deadline=SystemClock.uptimeMillis()+3000;
                Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback(){
                    @Override public void doFrame(long time){
                        try{
                            View city=originalRoot.get();
                            assertTrue("CITY remains attached during its outgoing fade",city.isAttachedToWindow());
                            float alpha=city.getAlpha();
                            if(!(motion(activity).isRunning() && alpha>0 && alpha<.9999f)){
                                assertTrue("An actual outgoing fade frame must arrive",SystemClock.uptimeMillis()<deadline);
                                Choreographer.getInstance().postFrameCallback(this);return;
                            }
                            assertTrue("Reopen through the real WEATHER city action",tag(activity,"weather-city").performClick());
                            assertEquals("CITY",get(activity,"screen").toString());
                            assertSame("Reopen must restore the retained root",city,get(activity,"root"));
                            assertSame("Restoring must preserve the native query field",originalInput.get(),tag(activity,"city-query"));
                            assertEquals("Testville",((EditText)originalInput.get()).getText().toString());
                            assertEquals("Fade is retargeted without resetting its current alpha",alpha,city.getAlpha(),.0001f);
                            assertTrue("Unfinished query remains pending until the replacement result",(Boolean)get(activity,"citySearchPending"));
                        }catch(Throwable problem){failure.set(problem);}
                        reopened.countDown();
                    }
                });
            });
            assertTrue("Bounded retained-root reversal",reopened.await(4,TimeUnit.SECONDS));
            if(failure.get()!=null)throw new AssertionError("Retained CITY reversal",failure.get());
            assertTrue("Back cancels the abandoned transport",cancelled.await(4,TimeUnit.SECONDS));
            awaitState(scene,"Restored CITY publishes its replacement result without new input",activity->
                    requests.get()==2 && !(Boolean)get(activity,"citySearchPending")
                    && ((View)get(activity,"root")).findViewWithTag("city-result-0")!=null,5000);
            scene.onActivity(activity->{
                assertSame("Replacement result belongs to the retained CITY root",originalRoot.get(),get(activity,"root"));
                assertTrue(tag(activity,"city-result-0").getContentDescription().toString().contains("Testville"));
                assertEquals("Successful result clears Searching", "",((TextView)get(activity,"cityStatus")).getText().toString());
                assertEquals("Exactly one replacement request",2,requests.get());
            });
        }finally{
            SharedPreferences.Editor restore=store.edit();
            if(previousWeather instanceof Boolean)restore.putBoolean("weather_enabled",(Boolean)previousWeather);else restore.remove("weather_enabled");
            if(previousMotion instanceof Boolean)restore.putBoolean("reduced_motion",(Boolean)previousMotion);else restore.remove("reduced_motion");
            assertTrue(restore.commit());
        }
    }

    private void exercise(boolean immediate) throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.evebox.debug",context.getPackageName());
        SharedPreferences store=context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE);
        Object previous=store.getAll().get("weather_enabled");
        assertTrue(store.edit().putBoolean("weather_enabled",false).commit());
        CountDownLatch started=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        AtomicInteger requests=new AtomicInteger();
        WeatherRepository.Transport transport=url->{
            assertTrue("Only city search belongs to this fixture",url.contains("geocoding-api.open-meteo.com"));
            if(requests.incrementAndGet()==1){
                started.countDown();
                try { if(!new CountDownLatch(1).await(10,TimeUnit.SECONDS)) throw new AssertionError("First request was never cancelled"); }
                catch(InterruptedException expected){cancelled.countDown();throw new InterruptedIOException("fixture cancelled");}
            }
            return "{\"results\":[{\"id\":123,\"name\":\"Testville\",\"latitude\":55,\"longitude\":37,\"country\":\"Fixture\"}]}";
        };
        WeatherRepository.Cache cache=new WeatherRepository.Cache(){
            public WeatherRepository.Weather read(String key){return null;}
            public void write(String key,WeatherRepository.Weather value){}
        };
        WeatherRepository repository=new WeatherRepository(transport,cache,new WeatherRepository.JsonDecoder(),System::currentTimeMillis);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            scene.onActivity(activity->{
                try{
                    field("weatherRepository").set(activity,repository);
                    Field screen=field("screen");Object city=null;
                    for(Object item:screen.getType().getEnumConstants())if(item.toString().equals("CITY"))city=item;
                    Method push=MainActivity.class.getDeclaredMethod("push",screen.getType());push.setAccessible(true);push.invoke(activity,city);
                    field("cityQuery").set(activity,"Testville");
                    Method schedule=MainActivity.class.getDeclaredMethod(immediate?"performCitySearch":"scheduleCitySearch");schedule.setAccessible(true);schedule.invoke(activity);
                }catch(Exception failure){throw new AssertionError(failure);}
            });
            assertTrue("Initial query started",started.await(4,TimeUnit.SECONDS));
            scene.moveToState(Lifecycle.State.CREATED);
            assertTrue("Pause interrupted the old request",cancelled.await(4,TimeUnit.SECONDS));
            scene.moveToState(Lifecycle.State.RESUMED);
            long deadline=SystemClock.uptimeMillis()+5000;AtomicReference<Boolean> completed=new AtomicReference<>(false);
            while(SystemClock.uptimeMillis()<deadline){
                scene.onActivity(activity->{
                    try{completed.set(requests.get()==2 && !field("citySearchPending").getBoolean(activity)
                            && activity.getWindow().getDecorView().findViewWithTag("city-result-0")!=null);}
                    catch(Exception failure){throw new AssertionError(failure);}
                });
                if(completed.get())break;SystemClock.sleep(30);
            }
            assertTrue("Return must publish the replacement result without further text input",completed.get());
        }finally{
            SharedPreferences.Editor restore=store.edit();
            if(previous instanceof Boolean)restore.putBoolean("weather_enabled",(Boolean)previous);else restore.remove("weather_enabled");
            assertTrue(restore.commit());
        }
    }
    private static void awaitSettled(ActivityScenario<MainActivity> scene,String screen){
        awaitState(scene,screen+" settles before the outgoing regression",activity->
                screen.equals(get(activity,"screen").toString()) && ((View)get(activity,"root")).isLaidOut()
                && !(Boolean)get(activity,"renderDirty") && !motion(activity).isRunning(),6000);
    }
    private static void awaitState(ActivityScenario<MainActivity> scene,String message,Predicate<MainActivity> ready,long timeout){
        long deadline=SystemClock.uptimeMillis()+timeout;AtomicReference<Boolean> complete=new AtomicReference<>(false);
        do{
            scene.onActivity(activity->complete.set(ready.test(activity)));
            if(complete.get())return;SystemClock.sleep(25);
        }while(SystemClock.uptimeMillis()<deadline);
        fail(message);
    }
    private static View tag(MainActivity activity,String name){
        View result=((View)get(activity,"root")).findViewWithTag(name);assertNotNull("Current route target "+name,result);return result;
    }
    private static void push(MainActivity activity,String name){
        try{
            Class<?> type=field("screen").getType();Object requested=null;
            for(Object value:type.getEnumConstants())if(name.equals(value.toString()))requested=value;
            assertNotNull(name,requested);
            Method push=MainActivity.class.getDeclaredMethod("push",type);push.setAccessible(true);push.invoke(activity,requested);
        }catch(Exception failure){throw new AssertionError(failure);}
    }
    private static UiMotion motion(MainActivity activity){return (UiMotion)get(activity,"uiMotion");}
    private static Object get(MainActivity activity,String name){
        try{return field(name).get(activity);}catch(Exception failure){throw new AssertionError(failure);}
    }
    private static Field field(String name)throws Exception{Field result=MainActivity.class.getDeclaredField(name);result.setAccessible(true);return result;}
}
