package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WatchNextRepository;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Run this class alone on an owned emulator, not with TvSmokeTest.
 *
 * Host contract: snapshot the DEBUG package's runtime permission and flags; revoke/clear request
 * flags BEFORE instrumentation; restore the original permission/flags AFTER instrumentation exits,
 * even on test failure. Revoking our own UID here can kill the test process, so this class never
 * invokes pm revoke/grant, changes global settings, inserts provider rows or touches release data.
 * Raw launcher preferences are snapshotted before construction and restored in finally.
 * Physical devices skip before any preference write, Activity launch or permission action.
 */
@RunWith(AndroidJUnit4.class)
public final class WatchNextPermissionTest {
    private static final String DEBUG_PACKAGE="io.github.ekkzy.evebox.debug";
    private Context context;
    private UiDevice device;

    @Test public void coldActivityRequiresExplicitDpadGrantAndDeniedRepositoryIsNotEmpty() throws Exception {
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // The only device command in this class is a read-only guard, before all mutations.
        Assume.assumeTrue("Emulator-only: no physical device UI or data may be changed",
                "1".equals(device.executeShellCommand("getprop ro.kernel.qemu").trim()));
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Run only against the isolated debug application",DEBUG_PACKAGE,context.getPackageName());
        assertTrue("Debuggable application required",(context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0);
        assertTrue("The owned TV emulator must define READ_TV_LISTINGS",WatchNextRepository.isReadPermissionAvailable(context));
        assertNotNull("A real TvProvider is required; absence must not pass as permission coverage",
                context.getPackageManager().resolveContentProvider(TvContract.AUTHORITY,0));
        assertFalse("Host must revoke debug READ_TV_LISTINGS before instrumentation and restore it afterward",
                WatchNextRepository.hasReadPermission(context));

        SharedPreferences store=context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE);
        Map<String,Object> original=copyPreferences(store);
        try {
            LauncherPrefs preferences=new LauncherPrefs(context);
            preferences.setLanguage("en");preferences.setWeatherEnabled(false);
            preferences.setReducedMotion(true);preferences.setSounds(false);
            preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);preferences.setBackground(0);
            preferences.setFavorites(Collections.emptyList());preferences.setWatchNextVisible(true);

            WatchNextRepository.Result denied=new WatchNextRepository().load(context);
            assertEquals("Denied real provider access must not silently become EMPTY",
                    WatchNextRepository.Status.DENIED,denied.status);
            assertTrue(denied.items.isEmpty());

            Intent cold=new Intent(context,MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(cold)) {
                await("Cold Activity Home",()->screen(scene).equals("HOME"),6000);
                // Observe more than a single frame: repo/layout completion must not trigger a prompt.
                long quietUntil=SystemClock.uptimeMillis()+1800;
                while(SystemClock.uptimeMillis()<quietUntil){
                    assertFalse("Cold Home must not request permission automatically",permissionPromptVisible());
                    assertEquals("HOME",screen(scene));SystemClock.sleep(100);
                }
                assertFalse(WatchNextRepository.hasReadPermission(context));
                assertOneFocus(scene);capture("watch-permission-cold-no-prompt");

                // No programmatic requestFocus: navigate to the explicit action with the remote.
                device.pressDPadDown();
                for(int boundary=0;boundary<5;boundary++)device.pressDPadLeft();
                awaitFocus(scene,"shortcut-apps");
                boolean large=context.getResources().getConfiguration().fontScale>=1.2f;
                String[] shortcuts={"shortcut-wifi","shortcut-bluetooth","shortcut-wallpapers","shortcut-launcher"};
                for(String tag:shortcuts){device.pressKeyCode(large?KeyEvent.KEYCODE_DPAD_DOWN:KeyEvent.KEYCODE_DPAD_RIGHT);awaitFocus(scene,tag);}
                device.pressDPadCenter();awaitFocus(scene,"settings-appearance");
                for(String tag:new String[]{"settings-wallpapers","settings-weather","settings-sounds","settings-sound-test","settings-advanced"}){
                    device.pressDPadDown();awaitFocus(scene,tag);
                }
                device.pressDPadCenter();awaitFocus(scene,"advanced-language");
                for(String tag:new String[]{"advanced-favorites","advanced-manage-apps","advanced-watch-next","advanced-watch-permission"}){
                    device.pressDPadDown();awaitFocus(scene,tag);
                }
                assertFalse("Opening Advanced must not itself request permission",permissionPromptVisible());
                assertDescriptionContains(scene,"advanced-watch-permission","Allow");
                capture("watch-permission-explicit-action");

                device.pressDPadCenter();
                await("Real PermissionController grant action",()->allowButton().exists(),6000);
                UiObject allow=allowButton();String controller=allow.getPackageName();
                assertTrue("Grant UI must belong to Android PermissionController: "+controller,
                        controller.endsWith("permissioncontroller")||controller.endsWith("packageinstaller"));
                ApplicationInfo owner=context.getPackageManager().getApplicationInfo(controller,0);
                assertTrue("Grant UI must be system-owned",(owner.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0);
                capture("watch-permission-real-controller");
                focusSystemAllowWithDpad(allow);device.pressDPadCenter();
                await("READ_TV_LISTINGS granted by real controller",()->WatchNextRepository.hasReadPermission(context),6000);
                awaitFocus(scene,"advanced-watch-permission");
                await("Allowed status rendered",()->descriptionContains(scene,"advanced-watch-permission","Allowed"),4000);
                assertFalse("Grant dialog dismissed",permissionPromptVisible());assertOneFocus(scene);
                capture("watch-permission-granted");

                // EMPTY is valid AFTER grant when this emulator has no user/provider history.
                WatchNextRepository.Result accessible=new WatchNextRepository().load(context);
                assertNotEquals("Grant must remove the explicit permission denial",WatchNextRepository.Status.DENIED,accessible.status);
                assertNotEquals("No provider exception should be hidden by this scenario",WatchNextRepository.Status.ERROR,accessible.status);
                Bundle evidence=new Bundle();
                evidence.putString("stream","\nWATCH_PERMISSION emulator=true initialDenied=true realController="+controller
                        +" granted=true allowedLabel=true providerBefore="+denied.status+" providerAfter="+accessible.status
                        +" permissionRestore=HOST_REQUIRED\n");
                InstrumentationRegistry.getInstrumentation().sendStatus(2,evidence);
            }
        }finally{
            restorePreferences(store,original);
            assertEquals("Original raw debug preferences must be restored",original,copyPreferences(store));
        }
    }

    private UiObject allowButton(){return device.findObject(new UiSelector().resourceIdMatches(
            ".*:id/(permission_allow_button|permission_allow_foreground_only_button)"));}
    private boolean permissionPromptVisible(){
        String owner=device.getCurrentPackageName();
        return allowButton().exists()||device.findObject(new UiSelector().resourceIdMatches(".*:id/permission_deny_button")).exists()
                ||owner!=null&&(owner.endsWith("permissioncontroller")||owner.endsWith("packageinstaller"));
    }
    private void focusSystemAllowWithDpad(UiObject allow)throws Exception {
        for(int i=0;i<8&&!allow.isFocused();i++){device.pressDPadUp();SystemClock.sleep(100);}
        for(int i=0;i<8&&!allow.isFocused();i++){device.pressDPadDown();SystemClock.sleep(100);}
        assertTrue("Real grant button must be reachable with D-pad",allow.isFocused());
    }
    private String screen(ActivityScenario<MainActivity> scene){AtomicReference<String> value=new AtomicReference<>("");scene.onActivity(a->value.set(a.currentScreenForTest()));return value.get();}
    private boolean focused(ActivityScenario<MainActivity> scene,String tag){
        AtomicReference<Boolean> value=new AtomicReference<>(false);
        scene.onActivity(a->{View view=a.getWindow().getDecorView().findViewWithTag(tag);value.set(view!=null&&view.isFocused()&&view.isShown());});return value.get();
    }
    private void awaitFocus(ActivityScenario<MainActivity> scene,String tag){await("Focus "+tag,()->focused(scene,tag),4000);assertOneFocus(scene);}
    private boolean descriptionContains(ActivityScenario<MainActivity> scene,String tag,String expected){
        AtomicReference<Boolean> value=new AtomicReference<>(false);scene.onActivity(a->{View view=a.getWindow().getDecorView().findViewWithTag(tag);value.set(view!=null&&view.getContentDescription()!=null&&view.getContentDescription().toString().contains(expected));});return value.get();
    }
    private void assertDescriptionContains(ActivityScenario<MainActivity> scene,String tag,String text){assertTrue(tag+" description must contain "+text,descriptionContains(scene,tag,text));}
    private void assertOneFocus(ActivityScenario<MainActivity> scene){scene.onActivity(a->assertEquals("Exactly one focused app View",1,countFocused(a.getWindow().getDecorView())));}
    private static int countFocused(View view){int count=view.isFocused()?1:0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=countFocused(((ViewGroup)view).getChildAt(i));return count;}
    private static void await(String message,BooleanSupplier condition,long timeout){long end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(condition.getAsBoolean())return;SystemClock.sleep(100);}fail(message+" timed out");}
    private void capture(String name){File folder=new File(context.getExternalFilesDir(null),"watch-permission-test");assertTrue(folder.isDirectory()||folder.mkdirs());assertTrue(device.takeScreenshot(new File(folder,name+".png")));}
    private static Map<String,Object> copyPreferences(SharedPreferences preferences){Map<String,Object> copy=new HashMap<>();for(Map.Entry<String,?> entry:preferences.getAll().entrySet()){Object value=entry.getValue();copy.put(entry.getKey(),value instanceof Set?new HashSet<>((Set<?>)value):value);}return copy;}
    @SuppressWarnings("unchecked") private static void restorePreferences(SharedPreferences preferences,Map<String,Object> values){
        SharedPreferences.Editor editor=preferences.edit().clear();
        for(Map.Entry<String,Object> entry:values.entrySet()){String key=entry.getKey();Object value=entry.getValue();
            if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);
            else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,new HashSet<>((Set<String>)value));
            else throw new AssertionError("Unsupported SharedPreferences value type for "+key);
        }assertTrue("Restoring debug preferences",editor.commit());
    }
}
