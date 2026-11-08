package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Tells you, at the top of the home screen, when the remote's buttons stop getting through: HomeMode's remote watch saw
 * them arrive on the voice channel, as when its push-to-talk mic button is held (stuck, or pressed by a cushion).
 * Restarting the TV's Bluetooth didn't help there (the remote comes back still "talking"), so it only says what to check.
 * It runs in :home, beside the home screen: shown at once if the home screen is up, else when it comes back. Only adb's
 * shell may send this (the manifest asks for DUMP, which apps can't have), only while Home Settings' "Stuck remote
 * warning" is on, and at most once every 5 minutes.
 */
public class RemoteFix extends BroadcastReceiver {
    private static final long COOLDOWN = 5 * 60_000L;
    private static long lastAt;
    /** The message waiting for the home screen (null: none). */
    private static volatile String pending;
    /** The home screen's: shows `pending` now if it's up. */
    static volatile Runnable onStuck;

    @Override public void onReceive(Context context, Intent intent) {
        Prefs.reload();
        long now = SystemClock.elapsedRealtime();
        if (!Prefs.remoteFix() || (lastAt != 0 && now - lastAt < COOLDOWN)) return;
        lastAt = now;
        AppLog.w("stuck remote: its buttons arrive as voice (a stuck mic button?)");
        pending = Ui.tr("The remote's buttons aren't getting through: its mic button may be stuck. Press it firmly a few times.");
        Runnable show = onStuck;
        if (show != null) show.run();
    }

    /** The waiting message, once (the home screen shows it). */
    static String take() {
        String p = pending;
        pending = null;
        return p;
    }
}
