package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A soundbar on the ARC port that stayed off when the TV came on (Sony BRAVIA). The TV asks every CEC device whether
 * it's on, about once a minute and right after it wakes; its answer is in `dumpsys hdmi_control`, which we read through
 * the TV's own ADB. When the soundbar says it's in standby, Sony's own Bravia Sync service (it holds the HDMI_CEC
 * permission no other app gets) sends <System Audio Mode Request>, the message that wakes an ARC amplifier: the same
 * one the TV sends when its audio system comes back. Only just after the TV comes on (12 s after the screen, once more
 * 40 s after if it's still off). And on every press of Home, whatever it says (asked for: the soundbar's own state
 * lags a minute behind, and a soundbar that's on just answers it's on). The TV's "system audio" flag can't tell
 * whether it's on: Sony turns it off by itself when the soundbar goes to standby.
 */
final class Soundbar {
    private Soundbar() {}

    private static final String SONY_SYNC = "com.sony.dtv.braviasyncservice";
    private static final int MSG_CHANGE_PEER_AUDIO_MODE = 17;   // BraviaSyncServiceConstants; arg1 1: system audio on
    private static final long AFTER_WAKE = 12_000, RETRY = 40_000, PRESS_GAP = 2_000, SETTLE = 10_000;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // The audio system's entry (logical address 5, type 5) and its CEC power status: 0 on, 1 standby, 2 and 3 changing.
    private static final Pattern AMP = Pattern.compile("logical_address: 0x05 device_type: 5 .*?power_status: (\\d)");

    private static long lastPress;
    private static boolean watching;

    /** Sony's TVs with their Bravia Sync service: the only way an app can send CEC. */
    static boolean available(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SONY_SYNC, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Once per process: a check a while after every screen-on. */
    static void watch(Context c) {
        if (watching || !available(c)) return;
        watching = true;
        final Context app = c.getApplicationContext();
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                woke(app);
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_ON));
        woke(app);   // the process starting counts too: after a restart, the home screen is the first thing up
    }


    private static void woke(Context app) {
        MAIN.removeCallbacksAndMessages(null);
        MAIN.postDelayed(() -> check(app, true), AFTER_WAKE);
        MAIN.postDelayed(() -> check(app, true), RETRY);   // once more, in case the first came too early
    }

    /** Home pressed: the wake request every time, on or not (one per press; a double press sends one). */
    static void homePressed(Context c) {
        if (!watching) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastPress < PRESS_GAP) return;
        lastPress = now;
        AppLog.w("soundbar: Home, waking it");
        send(c.getApplicationContext(), 1);
    }

    private static long lastCycle;

    /**
     * SoundbarRefresh (an activity a button-mapping app opens): sound to the TV's speakers, then back to the soundbar. The
     * TV only sends <System Audio Mode Request> when system audio is off, so turning it off first makes it go even
     * while the TV thinks the soundbar is on (and wakes a soundbar that went to sleep without the TV noticing).
     */
    static void cycle(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCycle < 3000) return;   // one cycle at a time
        lastCycle = now;
        final Context app = c.getApplicationContext();
        AppLog.w("soundbar: refresh, sound to the TV and back");
        send(app, 0);
        // Back on only once the TV says it's off (the soundbar takes a few seconds to answer over CEC): sent sooner,
        // the TV finished turning it off after it had asked the soundbar back, and the sound stayed on its speakers.
        WORK.execute(() -> {
            boolean off = false;
            for (int i = 0; i < 16 && !off; i++) {
                SystemClock.sleep(500);
                off = !systemAudio(app);
            }
            if (!off) AppLog.w("soundbar: system audio didn't go off, back on anyway");
            MAIN.post(() -> send(app, 1));
        });
    }

    /** The TV's sound goes to the audio system (true also when it can't tell: the request then goes as before). */
    private static boolean systemAudio(Context app) {
        try (Adb adb = Adb.connect(app, 0, null)) {
            return !adb.shell("dumpsys hdmi_control").contains("mSystemAudioActivated: false");
        } catch (Exception e) {
            return true;
        }
    }

    private static void check(Context app, boolean wake) {
        WORK.execute(() -> {
            int power = power(app);
            if (power < 0 || power == 0) return;   // unknown (no soundbar, or not the TV's output), or on
            AppLog.w("soundbar: in standby (" + power + ")" + (wake ? " after the TV came on" : "") + ", waking it");
            MAIN.post(() -> send(app, 1));
            MAIN.postDelayed(() -> WORK.execute(() -> AppLog.w("soundbar: now " + describe(power(app)))), SETTLE);
        });
    }

    private static String describe(int power) {
        return power == 0 ? "on" : power == 1 ? "in standby" : power < 0 ? "unknown" : "changing (" + power + ")";
    }

    /**
     * The audio system's power status as the TV last heard it, or -1 (none on the TV's CEC). System audio on counts as
     * on: the soundbar says so the moment it wakes, while the power status waits for the TV's next minutely question.
     */
    static int power(Context app) {
        try (Adb adb = Adb.connect(app, 0, null)) {
            String dump = adb.shell("dumpsys hdmi_control");
            if (dump.contains("mSystemAudioActivated: true")) return 0;
            Matcher m = AMP.matcher(dump);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (Exception e) {
            AppLog.w("soundbar: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Sony's service turns system audio on (1: it sends <System Audio Mode Request>; the TV drops it while system audio
     * is on already, which is fine: a soundbar going to standby turns it off) or off (0: the TV's own speakers). Sony's
     * service takes no "power on" key: its power keys become CEC's Power, a toggle, which would turn off a soundbar
     * that's on. Bound only for this message.
     */
    private static void send(Context app, int systemAudio) {
        Intent i = new Intent(SONY_SYNC + ".BraviaSyncService").setPackage(SONY_SYNC);
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    Message m = Message.obtain(null, MSG_CHANGE_PEER_AUDIO_MODE, systemAudio, 0);
                    m.replyTo = new Messenger(MAIN);   // it answers every message; nothing to read here
                    new Messenger(binder).send(m);
                } catch (RemoteException | RuntimeException e) {
                    AppLog.w("soundbar: " + e.getMessage());
                }
                MAIN.postDelayed(() -> {
                    try {
                        app.unbindService(this);
                    } catch (RuntimeException ignored) {
                    }
                }, 2000);
            }

            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        try {
            if (!app.bindService(i, conn, Context.BIND_AUTO_CREATE)) AppLog.w("soundbar: Bravia Sync didn't answer");
        } catch (RuntimeException e) {
            AppLog.w("soundbar: " + e.getMessage());
        }
    }
}
