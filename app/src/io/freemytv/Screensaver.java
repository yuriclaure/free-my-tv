package io.freemytv;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits; with Watch from the screensaver on (Home Settings), OK first opens the
 * title on screen in its app.
 */
public class Screensaver extends DreamService {
    private DreamView view;
    /** Home Settings > Screensaver > Aerial videos: the videos instead of the picks (and then any key just wakes). */
    private AerialSaver aerial;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        // Also here, not only when the home screen resumes: on Android 11 the dream is in the main process, and a TV
        // that idles here would go days without the home screen's check. ScreensaverAlone has reloaded the settings.
        ImmichPhotos.syncIfDue(this, null);
        boolean videos = Prefs.SAVER_AERIAL.equals(Prefs.saverSource());
        // Interactive: the keys come here instead of waking the TV (the videos: ← → change them, any other key closes).
        setInteractive(videos || Prefs.saverWatch());
        setFullscreen(true);
        setScreenBright(true);
        if (videos) {
            aerial = new AerialSaver(this);
            setContentView(aerial);
        } else {
            view = new DreamView(this);
            setContentView(view);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isInteractive()) return super.dispatchKeyEvent(event);
        if (aerial != null) {   // ← → change the video, ↓ its info panel, the volume is the system's, any other key closes
            int what = aerial.onKey(event);
            if (what == AerialSaver.KEY_PASS) return super.dispatchKeyEvent(event);
            if (what == AerialSaver.KEY_CLOSE && event.getAction() == KeyEvent.ACTION_DOWN) aerial.fadeOut(this::finish);
            return true;
        }
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        // OK acts when it's released, so the app it opens doesn't get the release; any other key closes at once.
        if (ok ? event.getAction() == KeyEvent.ACTION_UP : event.getAction() == KeyEvent.ACTION_DOWN) {
            if (ok && view != null) view.watch();
            finish();
        }
        return true;
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
        if (aerial != null) aerial.start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) view.stop();
        if (aerial != null) aerial.closing();   // the video stays on its last frame while the window fades out
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        if (aerial != null) aerial.stop();
        super.onDetachedFromWindow();
    }
}
