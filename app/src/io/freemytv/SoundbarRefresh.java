package io.freemytv;

import android.app.Activity;
import android.os.Bundle;

/**
 * "Soundbar refresh": the soundbar on ARC, sound to the TV's speakers and back, which makes the TV ask it to come on
 * even while it thinks the soundbar is on (Soundbar.cycle). No screen: for an app that maps the remote's buttons
 * (a BRAVIA button in tvQuickActions) to an activity.
 */
public final class SoundbarRefresh extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Soundbar.available(this)) Soundbar.cycle(this);
        finish();   // Theme.NoDisplay: before onResume
    }
}
