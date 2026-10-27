package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Uses the existing security fixture; no startActivity reaches Android. */
@RunWith(AndroidJUnit4.class)
public final class PublishedContentIntentsTest {
    private static final String OWNER = "io.github.ekkzy.softlumi.fixture.alpha";
    private static final ComponentName TARGET = new ComponentName(OWNER, "io.github.ekkzy.softlumi.fixture.FixtureActivity");
    private Context context;
    @Before public void requireSecurityFixture() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Install the existing alpha fixture", context.getPackageManager().getActivityInfo(TARGET, 0).exported);
    }
    @Test public void mainAndViewRemainDifferentEvidenceLevels() {
        String main = new Intent(Intent.ACTION_MAIN).setComponent(TARGET).toUri(Intent.URI_INTENT_SCHEME);
        String view = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/content/1"))
                .setComponent(TARGET).toUri(Intent.URI_INTENT_SCHEME);
        assertEquals(PublishedContentIntents.TargetKind.APP_ONLY, PublishedContentIntents.classify(context, OWNER, main));
        assertEquals(PublishedContentIntents.TargetKind.VIEW, PublishedContentIntents.classify(context, OWNER, view));
        Context noLaunch = new ContextWrapper(context) {
            @Override public void startActivity(Intent intent) { fail("A MAIN shortcut must not replace the content action"); }
        };
        assertFalse(PublishedContentIntents.launchView(noLaunch, OWNER, main));
    }
    @Test public void oversizedSerializationIsRejectedRatherThanTruncated() {
        String raw = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/" + "a".repeat(17000)))
                .setComponent(TARGET).toUri(Intent.URI_INTENT_SCHEME);
        assertNull(PublishedContentIntents.resolve(context, OWNER, raw));
    }
}
