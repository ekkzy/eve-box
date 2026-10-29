package io.github.ekkzy.evebox.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;

/** A separately installed test app; it is never included in the launcher APK. */
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("ТЕСТОВОЕ ПРИЛОЖЕНИЕ\n\n" + getPackageName() + "\n\nBack — вернуться в Eve Box");
        label.setTextSize(26); label.setGravity(Gravity.CENTER);
        label.setTextColor(Color.WHITE); label.setBackgroundColor(0xff142337);
        label.setContentDescription("Eve Box test fixture");
        setContentView(label);
    }
}
