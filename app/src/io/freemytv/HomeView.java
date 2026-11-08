package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.usb.UsbManager;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the focused card's photo, heavily blurred and almost white (Backdrop); without picks, one of your own photos
 *    (Photos) the same way, or else a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours or days
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf: identical glass tiles, 40 apart, the app's wordmark in white (dark by day); the focused one fills with its
    // brand colour. The glass runs 48 from the screen's sides and 30 from its bottom, with 36 of padding (the focused
    // tile grows 12% without touching its edge). Tiles are 16:9, sized so a full row of HomeApps.shelfVisible fills the
    // glass: 259 × 146 with six; fewer apps keep that size and the glass narrows around them; more, and it scrolls.
    private static final float SHELF_PAD = 36, TILE_GAP = 40;
    private static final float TILE_RADIUS = 24;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.12f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT). 624: the shelf's glass (bottom at
    // the screen's bottom) ends about 40 px above the drawer's row titles, not touching them.
    private static final float SHIFT = 624, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = Ui.LEGACY ? 500 : 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, MENU = 8, RECENT = 9, BARS = 10;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Ui.background(1);
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_STATUS = 25, L_STATUS_GAP = 22;
    private static final String[] STATUS_SYMBOLS = {"wifi", "ethernet", "bluetooth", "usb"};
    private static final float L_SHELF_L = 48, L_SHELF_R = 1872, L_SHELF_BOTTOM = 1080;   // the band reaches the screen's bottom: SHELF_PAD of glass above and below the tiles
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs, bothTabs;
    private static int tabsGeneration = -1;
    private static final String WATCH_TAB = "watch", MUSIC_TAB = "music";
    /** Tab slots: up to four tabs (Now Playing joins the three); the corner buttons come after them in topIndex. */
    private static final int TABS = 4;
    private static String[] musicTabs;
    // Now Playing (Continue Watching and Picks, while an app plays music): the cover, the track, a progress bar and five
    // round controls (previous, play/pause, next, open the app, close it). Read from the app's media session.
    private android.media.session.MediaController music;
    /**
     * Paused from the tab's own play/pause: kept on screen (paused) so it can be played again, until the home screen
     * is left. Paused anywhere else, the tab goes at once (NowPlaying.Watch.music only follows music that plays).
     */
    private android.media.session.MediaController musicHeld;
    /** Closed with ✕: that app's session is ignored for a moment, so the tab goes at once while the app shuts down. */
    private String musicClosed = "";
    private long musicClosedUntil;
    private int musicIndex = 1;
    private final Spring[] musicFocus = new Spring[5];
    private final RectF[] musicRects = new RectF[5];
    private String musicKey = "", musicArtKey = "", musicTitle = "", musicSub = "", musicEyebrow = "", musicOpen = "", musicPkg = "";
    private Bitmap musicArt;
    private BitmapShader musicArtShader;
    private Backdrop musicBackdrop;
    private Paint lMusicTitle, lMusicSub, lMusicEyebrow, lMusicTime, lMusicPill;
    private final Paint musicFill = new Paint(Paint.ANTI_ALIAS_FLAG), musicBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.drawable.Drawable[] musicIcons = new android.graphics.drawable.Drawable[6];
    private final RectF musicBars = new RectF();
    /** The Continue Watching tab's cards: one per Watch Next entry, with its TMDB pictures when found. */
    private List<Recs.Pick> watchCards = new ArrayList<>();
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    /** Cards in a row: the picks (Recs.MAX_CARDS) or, in the Continue Watching tab, the Watch Next list. */
    private static final int CARD_MAX = Math.max(Recs.MAX_CARDS, WatchNext.MAX);
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_WATCH = 6, Z_MUSIC = 7;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final NowPlaying.Watch playing;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The background follows the focused pick once it has been there this long (see bgFollow). */
    // On Android 8 to 11 it waits until you rest on a card and fades faster: a fade under the next card move cost ~12 ms
    // of each of its frames on a 2018 BRAVIA (from ~32 to ~45 ms), so browsing at a steady pace never got smooth.
    private static final long BG_FOLLOW_MS = Ui.LEGACY ? 900 : 500;
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final ConnectivityManager connectivity;
    private final BluetoothAdapter bluetooth;
    private boolean networkRegistered, devicesRegistered, mediaRegistered;
    private int network;   // 0 = not connected, 1 = Wi-Fi, 2 = Ethernet
    private boolean bluetoothConnected, usbConnected;
    /** The status layer spans the screen while the icons are on (they sit at the left); off, it keeps its old width. */
    private boolean statusWide;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { if (recentsOpen()) recents.openFocused(); else activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return a11yHolds(id); }
        @Override public void hold(int id) { if (recentsOpen()) recents.closeFocused(); else if (sheetOpen) a11yHold(); else if (canReorder()) startReorder(); else showMenu(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, menuLayer, recentLayer;
    /** Now Playing's bouncing bars alone, over the top layer: bouncing at 30 fps redraws only them (BARS_FPS). */
    private final Layer barsLayer;
    private boolean barsOwn;   // the bars layer draws them (the top layer leaves them out)
    private RecentApps recents;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The three tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[TABS + HomeApps.MAX_TOP];
    private final RectF[] topRects = new RectF[TABS + HomeApps.MAX_TOP];
    private final Spring[] cardFocus = new Spring[CARD_MAX];
    /** How open (wide) each card is: 1 for the one at cardIndex, 0 for the others; the widths always add up to the same. */
    private final Spring[] cardOpen = new Spring[CARD_MAX];
    /** Picks per tab (Prefs.cards()), read when the cards update, not on every frame. */
    private int cardSlots = 3;
    /** The row scrolls in whole cards: the focused one stays among the first three. */
    private final Spring cardScroll = new Spring(0.32f, 0.78f, 0);
    // Settings > Top of the screen: the picks (above), what you were watching in your apps (a row of 16:9 cards that
    // scrolls, Z_WATCH) or nothing. Its texts are measured once per list and its pictures decoded once (WatchArt), so
    // drawing the row costs no more than the picks.
    private String topMode = Prefs.TOP_PICKS;
    private List<WatchNext.Item> watching = new ArrayList<>();
    private final Spring[] watchFocus = new Spring[WatchNext.MAX];
    // Laid out like the picks: the focused card wide with its TMDB backdrop, the others narrow with the poster.
    private final Spring[] watchOpen = new Spring[WatchNext.MAX];
    private final RectF[] watchRects = new RectF[WatchNext.MAX];
    private Recs.Pick[] watchPicks = new Recs.Pick[0];   // each card's title on TMDB, or null (the app's picture)
    private String[] watchTags;   // a narrow card's line: the episode, or the time left
    private final Spring watchScroll = new Spring(0.32f, 1f, 0);
    private int watchIndex;
    private boolean watchAllowed = true;
    private boolean watchLoaded;   // read at least once: before that, no "nothing half-watched"
    private String[] watchTitles, watchMetas;
    private String[] watchDetails;   // the opened card's right side: episode · time left (the icon says the app)
    // Each app's switcher icon for the cards' line, made on LOADER; drawn scaled from this size.
    private final Map<String, Bitmap> watchIcons = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> watchIconsPending = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private float watchTextsK;
    private int watchTextsGen = -1;
    private LinearGradient watchSheen, watchRim;   // focus sheen and rim, made once per card size (not per frame)
    private WatchArt watchArt;
    // Holding OK on a card: a small menu (hide it, open the app, cancel) on its own layer, drawn only while it shows.
    private static final float MENU_W = 720, MENU_PAD = 44, MENU_INSET = 16, MENU_ROW = 76, MENU_STEP = 80, MENU_R = 36;
    private final Spring menu = new Spring(0.26f, 0.86f, 0), menuPick = new Spring(0.22f, 0.9f, 0);
    private final String[] menuOptions = new String[3];
    private final RectF menuPanel = new RectF();
    private final Set<String> hiddenNow = new HashSet<>();   // hidden this session (a reload already under way may not know)
    private boolean menuOpen;
    private int menuIndex;
    private WatchNext.Item menuItem;
    private String menuTitle = "", menuSub = "";
    private float menuOptionsTop;
    private Paint menuTitlePaint, menuSubPaint, menuOptionPaint;
    private final RectF[] cardRects = new RectF[CARD_MAX];
    private final RectF[] cardLines = new RectF[CARD_MAX];   // the same, not snapped to pixels: the text glides with it
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Paint lGlanceClock, lGlanceDate, lGlanceWeather;
    private String glanceWeather = "";
    private long glanceWeatherAt = -1;
    private int glanceWeatherPrefs = -1;
    private String glanceWeatherLanguage = "";
    private Shader lightGradient;
    // Continue Watching: the background takes the focused card's colour (worked out once per picture, WatchArt), easing
    // from one to the next. Drawn as a plain fill under a fixed shade, so a colour change creates nothing per frame.
    // Until the first picture's colour is in (a cold start reads the list and the pictures), the tint is a cool grey,
    // so the background and the glass already look as they will and only ease to the card's colour.
    private static final int TINT_WAIT = 0xFF8C93A0;
    private Shader tintShade;
    private int tintFrom = TINT_WAIT, tintTo = TINT_WAIT;
    private boolean tinted;   // a card's colour is in (else TINT_WAIT)
    private final Spring tintMix = new Spring(0.7f, 1f, 1);
    // The glass (shelf, drawer) reads that same colour: two flat images blended while it eases (Android 13+, where the
    // glass reads images), so nothing is made per frame. On older TVs the glass is a flat fill of glassLight().
    private int[] tintGlassPx;
    private boolean glassEasing;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /**
     * The focused tile's brand plate (top, bottom). Apps not listed take their SHELF colour, darkening toward the
     * bottom (HomeApps.Item.brand).
     */
    private static final Map<String, int[]> PLATES = new HashMap<>();
    static {
        PLATES.put("netflix", new int[] {0xFFE50914, 0xFFB20710});
        PLATES.put("youtube", new int[] {0xFFFF0033, 0xFFCC0029});
        PLATES.put("prime", new int[] {0xFF1A98FF, 0xFF0B6FD6});
        PLATES.put("disney", new int[] {0xFF1838C8, 0xFF0B1A5E});
        PLATES.put("max", new int[] {0xFF5C30E8, 0xFF221684});
        PLATES.put("appletv", new int[] {0xFF2C2C2E, 0xFF0A0A0B});
        PLATES.put("spotify", new int[] {0xFF1DB954, 0xFF128A3E});
        PLATES.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A});
        PLATES.put("now", new int[] {0xFF14A69B, 0xFF0A4F55});
        PLATES.put("iplayer", new int[] {0xFFFF4B95, 0xFFAF0C59});
        PLATES.put("my5", new int[] {0xFF5A4A8A, 0xFF241C3F});
        PLATES.put("itv", new int[] {0xFF19C2B8, 0xFF1C5BD8});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    /** By day, a resting tile's logo: near-black (drawn at 86%). */
    private final android.graphics.PorterDuffColorFilter darkLogo =
            new android.graphics.PorterDuffColorFilter(L_INK, android.graphics.PorterDuff.Mode.SRC_IN);
    private final Paint tileEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        tileEdge.setStyle(Paint.Style.STROKE);
    }
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    /** The first app the shelf shows: with more apps than fit, it scrolls with the focus. */
    private int shelfFirst;
    private final int shelfVisible;

    // Moving shelf apps or top buttons: hold OK, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderTop, reorderAnim, okHeld, holdArmed;
    private int orderRevision, pendingOrderSaves;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring[] topSlide = new Spring[HomeApps.MAX_TOP];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private int drawerHome;            // where the Home Assistant tiles start (the list's size without them)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    /** "Off in 42 min" next to the round buttons while the sleep timer runs (Settings > Sleep Timer); "" otherwise. */
    private String sleepText = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;
    private float touchX, touchY;
    private final RectF hitBox = new RectF();   // the mouse's hit tests (menu rows, Continue Watching cards)

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp, dayLow;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private boolean clock24;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] dayText = new String[5], dayMaxText = new String[5], dayMinText = new String[5];
    private boolean textDays;   // the weather card shows the next days (Home Settings → Forecast) and has them
    // The wide weather card (the drawer's full width): the next 24 hours as a curve, the week as rows.
    private String wxSub, wxToday;
    private final String[] wxCurveTemp = new String[HomeData.HOURS + 1], wxCurveHour = new String[HomeData.HOURS + 1];
    private final String[] wxRowDay = new String[6], wxRowMax = new String[6], wxRowMin = new String[6], wxRowRain = new String[6];
    private final String[] wxRowSymbol = new String[6];
    private final double[] wxRowHi = new double[6], wxRowLo = new double[6];
    private int wxRows;
    private final Path wxLine = new Path(), wxFill = new Path();
    private final float[] wxPointX = new float[HomeData.HOURS + 1], wxPointY = new float[HomeData.HOURS + 1];
    private int wxPoints;
    private LinearGradient wxFillShader, wxBarShader;
    private final RectF wxBuiltFor = new RectF();
    private HomeData.Weather wxBuiltWeather;
    private boolean wxBuiltDark;
    private Paint wxBig, wxSubPaint, wxCurvePaint, wxHourPaint, wxRowPaint, wxRowLow, wxRowHigh, wxRainPaint;
    private final Paint wxStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            drawKind(c);
        }

        private void drawKind(Canvas c) {
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case BARS: drawMusicBars(c, true); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case MENU: drawMenu(c); break;
                case RECENT: if (recents != null) recents.draw(c, w, h); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    { NetflixWatch.onChange = this::reloadSoon; }   // new Netflix titles: Continue Watching reads again
    { RemoteFix.onStuck = () -> handler.post(this::showStuckRemote); }   // a stuck remote: say so at the top

    /** RemoteFix's message, at the top like the other toasts: now if the home screen shows, else when it comes back. */
    private void showStuckRemote() {
        if (!running) return;
        String text = RemoteFix.take();
        if (text != null) showToast(text);
    }
    /** While the home screen shows: Netflix's titles every 10 minutes (WatchNext.load asks NetflixWatch, which refreshes). */
    private final Runnable netflixTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (NetflixWatch.signedIn(getContext())) reloadSoon();
            handler.postDelayed(this, NetflixWatch.EVERY);
        }
    };
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private boolean bgWasFading;

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            setNetwork(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? 2
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 1 : 0);
        }

        @Override public void onLost(Network network) {
            setNetwork(0);
        }
    };

    /** Wi-Fi or Ethernet, for the icon at the top; none when the TV has no network. */
    private void setNetwork(int type) {
        if (type == network) return;
        network = type;
        status.invalidate();
    }

    private final BroadcastReceiver deviceCallback = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateDevices();
        }
    };

    /** The drive broadcasts, in a filter of their own (see startStatus). */
    private final BroadcastReceiver mediaCallback = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateDevices();
        }
    };

    /**
     * Bluetooth (when something is connected) and a USB drive, for the icons at the top. Only the audio profiles count:
     * a Bluetooth remote is always connected, and its HID connection would keep the icon on for good. The queries go to
     * LOADER: a Bluetooth remote that reconnects on the first key press after a while would otherwise pay for the
     * profile states, the storage volumes and the binder calls on that very press.
     */
    private void updateDevices() {
        LOADER.execute(() -> {
            boolean bt = false;
            try {
                if (bluetooth != null && bluetooth.isEnabled()) {
                    bt = bluetooth.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                            || bluetooth.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED;
                }
            } catch (RuntimeException ignored) {
            }
            boolean drive = false;
            try {
                StorageManager sm = (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);
                if (sm != null) {
                    for (StorageVolume v : sm.getStorageVolumes()) {
                        // Only a mounted one: #192's unmounted drive doesn't show.
                        if (v.isRemovable() && Environment.MEDIA_MOUNTED.equals(v.getState())) {
                            drive = true;
                            break;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
            boolean btOn = bt, driveOn = drive;
            handler.post(() -> setDevices(btOn, driveOn));
        });
    }

    private void setDevices(boolean bt, boolean drive) {
        if (bt == bluetoothConnected && drive == usbConnected) return;
        bluetoothConnected = bt;
        usbConnected = drive;
        status.invalidate();
    }

    /** Reads the icons' state and listens for changes only while the setting is on (nothing runs with it off). */
    private void startStatus() {
        if (!Prefs.statusIcons()) return;
        updateDevices();
        // The registration hands the current network over at once, so the first read is this callback. With no
        // network at all it hands nothing, and the icon would keep the one from before the pause (Wi-Fi turned off).
        if (connectivity != null && !networkRegistered) {
            try {
                if (connectivity.getActiveNetwork() == null) setNetwork(0);
                connectivity.registerDefaultNetworkCallback(networkCallback, handler);
                networkRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        if (!devicesRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            // Headphones connect their link first and the audio a moment later; with a Bluetooth remote already
            // connected the adapter's own state doesn't change then, so the profiles' broadcasts say it.
            f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            try {
                if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(deviceCallback, f, Context.RECEIVER_NOT_EXPORTED);
                else getContext().registerReceiver(deviceCallback, f);
                devicesRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        // A mounted drive's broadcast carries a file:// URI: in the same filter as the Bluetooth actions it would make
        // those never match (they have no data), so it gets a filter of its own.
        if (!mediaRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_MEDIA_MOUNTED);
            f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
            f.addAction(Intent.ACTION_MEDIA_EJECT);
            f.addAction(Intent.ACTION_MEDIA_REMOVED);
            f.addDataScheme("file");
            try {
                if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(mediaCallback, f, Context.RECEIVER_NOT_EXPORTED);
                else getContext().registerReceiver(mediaCallback, f);
                mediaRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void stopStatus() {
        if (connectivity != null && networkRegistered) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
            networkRegistered = false;
        }
        if (devicesRegistered || mediaRegistered) {
            // Registered once per filter, so unregistered once per filter.
            if (devicesRegistered) {
                try {
                    getContext().unregisterReceiver(deviceCallback);
                } catch (RuntimeException ignored) {
                }
                devicesRegistered = false;
            }
            if (mediaRegistered) {
                try {
                    getContext().unregisterReceiver(mediaCallback);
                } catch (RuntimeException ignored) {
                }
                mediaRegistered = false;
            }
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            playing.start();   // the listener allowed after the screen showed (a no-op once watching)
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // new ones every hour
            updateSleep();
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        playing = new NowPlaying.Watch(a, this::onPlaying);
        click = new Click(a);
        shelfVisible = HomeApps.shelfVisible(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        connectivity = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        BluetoothManager bm = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetooth = bm == null ? null : bm.getAdapter();
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        try {
            if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        } catch (RuntimeException e) {   // some projectors (Rockchip rk3566 HY300) ship without the launcherapps service
            AppLog.w("no app-change callback: " + e.getMessage());
        }
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        menuLayer = new Layer(a, MENU);
        recentLayer = new Layer(a, RECENT);
        barsLayer = new Layer(a, BARS);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) {
            v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        }
        for (View v : new View[] {sky, heroLayer, barsLayer, status, platter, tiles, drawerLayer, sheetLayer, menuLayer, toastLayer, recentLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        barsLayer.setVisibility(INVISIBLE);
        setContentDescription("Free My TV");
        recentLayer.setVisibility(INVISIBLE);
        drawerLayer.setVisibility(INVISIBLE);
        menuLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < topSlide.length; i++) topSlide[i] = new Spring(0.32f, 0.86f, 0);
        for (int i = 0; i < CARD_MAX; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            // Barely any overshoot: a card that widened past its size and came back moved its text at the end.
            cardOpen[i] = new Spring(0.32f, 0.9f, i == 0 ? 1 : 0);
            cardRects[i] = new RectF();
            cardLines[i] = new RectF();
        }
        for (int i = 0; i < musicFocus.length; i++) {
            musicFocus[i] = new Spring(0.3f, 0.82f, 0);
            musicRects[i] = new RectF();
        }
        String[] mc = {"mc_skip_previous", "mc_play_arrow", "mc_pause", "mc_skip_next", "mc_open_in_new", "mc_close"};
        for (int i = 0; i < mc.length; i++) {
            int id = a.getResources().getIdentifier(mc[i], "drawable", a.getPackageName());
            musicIcons[i] = id == 0 ? null : a.getDrawable(id).mutate();
        }
        for (int i = 0; i < watchFocus.length; i++) {
            watchFocus[i] = new Spring(0.32f, 0.78f, 0);
            watchOpen[i] = new Spring(0.32f, 0.78f, i == 0 ? 1 : 0);
            watchRects[i] = new RectF();
        }
        topMode = Prefs.topContent();
        watchArt = new WatchArt(a, () -> {
            heroDirty = true;
            updateTint();
            heroLayer.invalidate();
            wake();
        });
        setLightButtons(HomeApps.topButtons(null));
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        boolean h24 = Ui.h24();
        if (h24 != clock24) {   // 12/24 hours changed (the TV's or the Clock row's): the clock and the drawer's times again
            clock24 = h24;
            clockMinute = -1;
            textPrefs = -1;
        }
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        Photos.importInbox(getContext(), ownReady);   // photos a computer just sent
        ImmichPhotos.syncIfDue(getContext(), ownReady);   // or a new choice from the Immich server, every 6 hours
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        startStatus();
        handler.postDelayed(tick, 15_000);
        playing.start();
        checkNotices();
        hiddenNow.retainAll(Prefs.watchHidden());   // Home Settings may have shown the hidden ones again
        if (!again) reload();
        NetflixWatch.homeResumed(getContext());   // back from Netflix: its Continue Watching now, and again in a moment
        showStuckRemote();
        handler.postDelayed(netflixTick, NetflixWatch.EVERY);
        updatePalette();
        updatePicks();
        updateSleep();      // a timer just set in Settings shows at once, not at the next tick
        if (allAppsOnResume) {
            allAppsOnResume = false;
            reset(false);
            openSheet();
        } else if (sheetOpen) {
            closeSheet();
        }
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(bgFollow);
        cancelReorder(true);
        if (recents != null) recents.closeNow();
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        closeMenu(true);
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(homeTick);
        handler.removeCallbacks(reloadSoon);
        handler.removeCallbacks(netflixTick);
        handler.removeCallbacks(bars);
        musicHeld = null;   // leaving the home screen: a paused track's tab is gone when it comes back
        playing.stop();
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        stopStatus();
    }

    void destroy() {
        pause();
        try {
            if (launcherApps != null) launcherApps.unregisterCallback(packages);
        } catch (RuntimeException ignored) {
        }
        click.release();
        watchArt.close();   // its thread is shared: nothing is left running for this view
    }

    /**
     * Starting state (Home pressed while the home screen is showing): first app focused, the first tab at its first
     * pick, drawer and sheet closed.
     */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        closeMenu(!animated);
        if (recents != null) recents.close();   // Home closes Recent Apps too (twice opens it: HomeActivity)
        closeSearch(animated);
        zone = Z_SHELF;
        shelfIndex = 0;
        cardIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        if (reorderTop) cancelReorder(true);
        lightButtons.clear();
        for (HomeApps.Item it : list) if (it.kind != HomeApps.SEARCH || picksMode()) lightButtons.add(it);   // search's results are cards
        if (zone == Z_TOP && topIndex >= TABS + lightButtons.size()) {
            topIndex = 2 + lightButtons.size();
            if (topIndex < TABS && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
            else if (topIndex < TABS) selectTab(Math.min(topIndex, tabCount() - 1));
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")};
            bothTabs = new String[] {Ui.tr("Continue Watching"), Ui.tr("Explore Movies"), Ui.tr("Explore Shows")};
            musicTabs = new String[] {Ui.tr("Now Playing"), Ui.tr("Continue Watching"), Ui.tr("Explore Movies"), Ui.tr("Explore Shows")};
            tabsGeneration = Ui.generation;
        }
        return musicShown() ? musicTabs : bothMode() ? bothTabs : tabs;
    }

    /** The tabs drawn now: three, or four with Now Playing first while music plays (TABS slots, the buttons after them). */
    private int tabCount() {
        return musicShown() ? 4 : 3;
    }

    /**
     * What tab t lists: TMDB's kind ("movie", "tv", null for both), WATCH_TAB in Continue Watching and Picks, or MUSIC_TAB
     * (Now Playing, first while music plays; the others move one place along).
     */
    private String tabKind(int t) {
        if (musicShown()) {
            if (t == 0) return MUSIC_TAB;
            t--;
        }
        if (bothMode()) return t == 0 ? WATCH_TAB : TAB_KIND[t];
        return TAB_KIND[t];
    }

    /** The Continue Watching tab is the one showing (Continue Watching and Picks). */
    private boolean watchTab() {
        return !searchOpen && bothMode() && WATCH_TAB.equals(tabKind(tab));
    }

    /** Music is playing (or paused) in an app: Continue Watching and Picks gets a Now Playing tab, first. */
    private boolean musicShown() {
        return bothMode() && music != null;
    }

    /** The Now Playing tab is the one showing. */
    private boolean musicTab() {
        return !searchOpen && musicShown() && tab == 0;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(790 * kk));
        measure(barsLayer, Math.round(BARS_W * kk), Math.round(BARS_H * kk));
        // The icons at the top left need the whole width; with them off the layer keeps its old size (the corner buttons
        // only), so the GPU still composites a small layer.
        statusWide = Prefs.statusIcons();
        measure(status, statusWide ? width : Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(330 * kk));   // down to the screen's bottom: the glass's shadow
        measure(tiles, width, Math.round(290 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(menuLayer, width, height);
        measure(toastLayer, width, Math.round(220 * kk));   // full width: a toast may sit right of a long title
        measure(recentLayer, width, height);
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(barsLayer, Math.round(BARS_X * kk), Math.round(BARS_Y * kk));
        place(status, statusWide ? 0 : width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        menuLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, 0, 0);
        recentLayer.layout(0, 0, width, height);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        watchArt.setScale(k);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        Symbols.warm(getContext(), "bell", 26 * k);
        Symbols.warm(getContext(), "bedtime", 21 * k);   // the sleep timer's "Off in", at lQuote's size
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        // No photo yet (or none at all, in Continue Watching): the glass reads the plain background from the first frame.
        if (bgTo == null && bgFadeAt == 0) neutralGlass();
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        // The cards' line grows with the card: glyphs scaled smoothly, not jumping between whole pixel sizes.
        lCardTitle.setFlags(lCardTitle.getFlags() | Paint.LINEAR_TEXT_FLAG);
        lCardMeta.setFlags(lCardMeta.getFlags() | Paint.LINEAR_TEXT_FLAG);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        // Continue Watching and Picks: the time, the date and the weather where the title would be.
        lGlanceClock = Ui.numbers(Ui.light, 86 * k, L_INK);
        lGlanceClock.setLetterSpacing(-0.01f);
        lGlanceDate = Ui.text(Ui.semibold, 27 * k, L_INK);
        lGlanceWeather = Ui.numbers(Ui.medium, 25 * k, L_DIM);
        lMusicTitle = Ui.text(Ui.displaySemibold, 96 * k, L_INK);
        lMusicTitle.setLetterSpacing(-0.02f);
        lMusicSub = Ui.text(Ui.medium, 36 * k, L_DIM);
        lMusicEyebrow = Ui.text(Ui.semibold, 22 * k, L_DIM);
        lMusicEyebrow.setLetterSpacing(0.1f);
        lMusicTime = Ui.numbers(Ui.medium, 22 * k, L_DIM);
        lMusicPill = Ui.text(Ui.semibold, 26 * k, L_INK);
        lightGradient = tintShade = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        dayLow = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        dayLow.setTextAlign(Paint.Align.CENTER);
        wxBig = Ui.numbers(Ui.light, 88 * k, 0xFFFFFFFF);
        wxSubPaint = Ui.text(Ui.medium, 25 * k, 0xF2FFFFFF);
        wxCurvePaint = Ui.numbers(Ui.semibold, 21 * k, 0xF2FFFFFF);
        wxCurvePaint.setTextAlign(Paint.Align.CENTER);
        wxHourPaint = Ui.numbers(Ui.medium, 19 * k, 0x6BFFFFFF);
        wxHourPaint.setTextAlign(Paint.Align.CENTER);
        wxRowPaint = Ui.text(Ui.semibold, 21 * k, 0xF2FFFFFF);
        wxRowLow = Ui.numbers(Ui.medium, 20 * k, 0x9EFFFFFF);
        wxRowLow.setTextAlign(Paint.Align.RIGHT);
        wxRowHigh = Ui.numbers(Ui.semibold, 20 * k, 0xF2FFFFFF);
        wxRainPaint = Ui.numbers(Ui.semibold, 17 * k, 0xFF6CB6FF);
        wxStroke.setStyle(Paint.Style.STROKE);
        wxStroke.setStrokeJoin(Paint.Join.ROUND);
        wxStroke.setStrokeCap(Paint.Cap.ROUND);
        wxBuiltWeather = null;
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        menuTitlePaint = Ui.text(Ui.semibold, 34 * k, L_INK);
        menuSubPaint = Ui.text(Ui.medium, 25 * k, L_DIM);
        menuOptionPaint = Ui.text(Ui.medium, 30 * k, L_INK);
        if (menuOpen) layoutMenu();
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
        onPlaying();   // the line fits the new size; a title that came before the first layout bounces and is said now
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        settleWhileSliding();
        boolean tilesMoving = step(tileFocus, dt);
        boolean topReorderFrame = reorderTop && reorderAnim;
        if (reorderAnim) {
            boolean moving = stepReorder(dt, up);
            if (!topReorderFrame) tilesMoving |= moving;
        }
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt) | cardsLoading;
        cardsLoading = false;   // the next draw sets it again while a card still waits
        boolean topMoving = step(topFocus, dt) | topReorderFrame, cardsMoving = step(cardFocus, dt) | step(cardOpen, dt) | cardScroll.step(dt)
                | step(watchFocus, dt) | watchScroll.step(dt) | step(musicFocus, dt);
        boolean searchMoving = searchShow.step(dt) | searching | hearing;
        hearShown += (hearLevel - hearShown) * Math.min(1, dt * 12);
        if (searchMoving || searchWasMoving) {
            // The bar grows out of the button (and its ring turns while it searches), the clock and the tabs give way;
            // once more as it settles, so the last frame is the finished one.
            heroDirty = statusDirty = true;
        }
        searchWasMoving = searchMoving;
        boolean tintMoving = tintMix.step(dt);
        if (tintMoving) {
            sky.invalidate();
            platter.invalidate();   // the glass follows the background's colour
            heroDirty = true;       // the cards' text colours too
            statusDirty = true;     // and the corner buttons' glass, on the status layer
            if (glassEasing) scene.setGlassMix(tintMix.value);
        }
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);
        boolean menuMoving = menu.step(dt) | menuPick.step(dt);
        if (menuMoving || nightMoving && menuOpen) menuLayer.invalidate();
        else if (!menuOpen && menuLayer.getVisibility() == VISIBLE) menuLayer.setVisibility(INVISIBLE);
        boolean recentMoving = recents != null && recents.step(dt);
        if (recents != null) {
            if (recents.visible() != (recentLayer.getVisibility() == VISIBLE)) recentLayer.setVisibility(recents.visible() ? VISIBLE : INVISIBLE);
            if (recentMoving) recentLayer.invalidate();   // idle, the cards are drawn once
            setUnderRecents(recents.covering());
        }

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        // On Android 8 to 11 the glass of the top buttons and the shelf catches up once the background's fade ends,
        // not on every frame of it (less to draw on each frame of a card move).
        boolean glassNow = Ui.LEGACY ? !bgFading && bgWasFading : bgFading;
        bgWasFading = bgFading;
        if (statusMoving || statusDirty || glassNow) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (glassNow || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        // Now Playing's bars bounce while it plays, at BARS_FPS: in their own small layer while the screen is otherwise
        // still (redrawing the whole top layer that often took ~45% of a core and 21 ms a frame on a 2018 BRAVIA), in the
        // top layer while it moves anyway. The progress bar and its time still come once a second with the top layer.
        boolean barsLive = musicTab() && musicPlaying() && !drawerOpen && drawer.value < 0.001f && !sheetOpen;
        boolean own = barsLive && !heroMoving && !heroFading && heroLayer.getAlpha() > 0.99f && heroLayer.getTranslationY() == 0;
        if (own != barsOwn) {
            barsOwn = own;
            barsLayer.setVisibility(own ? VISIBLE : INVISIBLE);
            heroLayer.invalidate();
        }
        if (own) barsLayer.invalidate();
        if (barsLive && SystemClock.uptimeMillis() - heroTickAt >= 1000) {
            heroTickAt = SystemClock.uptimeMillis();
            heroLayer.invalidate();   // the progress bar
        }
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (clockMode() || bothMode()) heroLayer.invalidate();   // both show the time at the top
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || menuMoving || sheetMoving || toastFading || heroFading || bgFading || nightMoving || tintMoving || sheetOpen
                || sheet.value > 0.001f || recentMoving || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty()
                || searchMoving;
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, barsLive ? 1000 / BARS_FPS : 1000);
    }

    private final int[] underState = new int[9];
    private boolean underHidden;

    /** Recent Apps covers the screen: the layers below aren't drawn (on slower TVs they cost more than the cards). */
    private void setUnderRecents(boolean covered) {
        if (covered == underHidden) return;
        underHidden = covered;
        View[] under = {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, menuLayer, toastLayer};
        for (int i = 0; i < under.length; i++) {
            if (covered) {
                underState[i] = under[i].getVisibility();
                under[i].setVisibility(INVISIBLE);
            } else {
                under[i].setVisibility(underState[i]);
            }
        }
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    /**
     * While the drawer slides, the focus in the drawer, the hero and the capsule jumps instead of animating: the shelf's
     * focus alone is cheap over the sliding sky (10 ms a frame on a Realtek TCL), but a redraw of the drawer's cards or
     * of the hero on top of it took 35 to 45 ms of GPU there (most frames dropped when a key came during the slide).
     */
    private void settleWhileSliding() {
        if (drawer.settled()) return;
        if (settle(drawerFocus)) drawerDirty = true;
        if (settle(cardFocus) | settle(topFocus)) heroDirty = statusDirty = true;
        if (settle(statusFocus)) statusDirty = true;
        if (!heroFocus.settled()) {
            heroFocus.snap(heroFocus.target);
            heroDirty = true;
        }
    }

    private static boolean settle(Spring[] springs) {
        boolean moved = false;
        for (Spring s : springs) {
            if (s.settled()) continue;
            s.snap(s.target);
            moved = true;
        }
        return moved;
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private void updateSleep() {
        boolean here = Prefs.sleepShown() && !android.provider.Settings.canDrawOverlays(getContext());   // else SleepOverlay
        int left = here ? SleepTimer.minutesLeft(getContext()) : 0;
        String text = left == 0 ? "" : Ui.trf("Off in {time}", "time", SleepTimer.duration(left));
        if (text.equals(sleepText)) return;
        sleepText = text;
        statusDirty = true;
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /**
     * No photo: the glass reads the background's own colour (not Scene's default aurora, which isn't on screen): the
     * gray, or in Continue Watching the focused card's tint.
     */
    private void neutralGlass() {
        if (!scene.shaded()) {   // Android 8 to 12: a gray image would be one more full-area pass for the flat look
            scene.clearFrost();
            scene.clearGlass();
            nightPair = false;
            glassEasing = false;
            return;
        }
        plainImage(neutralFrostPx, Backdrop.FW, Backdrop.FH, tintTo);
        plainImage(neutralGlassPx, Backdrop.GW, Backdrop.GH, tintTo);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
        glassEasing = false;
    }

    /** The plain background (no photo) as an image for the glass, row by row (`card`: the tint's colour). */
    private void plainImage(int[] px, int width, int height, int card) {
        for (int j = 0; j < height; j++) {
            java.util.Arrays.fill(px, j * width, (j + 1) * width, plainAt((j + 0.5f) / height, card));
        }
    }

    /** The plain background's colour at height y (0 = top, 1 = bottom), as drawLightSky draws it. */
    private int plainAt(float y, int card) {
        if (!continueMode()) return blend(0xFFEBEBF0, 0xFF141416, night);
        return blend(tintColor(card), 0xFF000000, 0.2f * y);   // under drawTint's shade
    }

    /** The card's colour as a pale tint by day, a deep one at night (drawTint, before its shade). */
    private int tintColor(int card) {
        return blend(blend(0xFFEEEFF3, card, 0.34f), blend(0xFF141416, card, 0.3f), night);
    }

    /** The day glass's colour: white, or in Continue Watching white with a little of the background's tint. */
    private int glassLight() {
        if (!continueMode()) return 0xFFFFFFFF;
        return blend(0xFFFFFFFF, blend(tintFrom, tintTo, Ui.clamp(tintMix.value)), 0.18f);
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        if (lMusicTitle != null) {
            lMusicTitle.setColor(tone(L_INK, WHITE));
            lMusicSub.setColor(tone(L_DIM, 0xB8FFFFFF));
            lMusicEyebrow.setColor(tone(L_DIM, 0xB3FFFFFF));
            lMusicTime.setColor(tone(L_DIM, 0x99FFFFFF));
        }
        if (lGlanceClock != null) {
            lGlanceClock.setColor(tone(L_INK, WHITE));
            lGlanceDate.setColor(tone(L_INK, WHITE));
            lGlanceWeather.setColor(tone(L_DIM, 0xFF98989D));
        }
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
        // On the card's own backdrop the text keeps the picks' colours; on the plain tint it's made readable.
        if (continueMode() && bgTo == null) applyWatchContrast();
    }

    private int watchTextBg, watchQuoteBg, watchTitleInk, watchMetaInk, watchQuoteInk;
    private int watchTitleColor, watchMetaColor, watchQuoteColor;
    private boolean watchColorsReady;

    /** Contrast changes only with the background or the day/night text colours. */
    private void applyWatchContrast() {
        int card = blend(tintFrom, tintTo, Ui.clamp(tintMix.value));
        int behind = plainAt((L_CARDS_TOP + L_CARD_H + 70) * k / Math.max(1, h), card);
        int quoteBg = plainAt((L_TABBAR_TOP + L_TABBAR_H / 2) * k / Math.max(1, h), card);
        int title = lCardTitle.getColor(), meta = lCardMeta.getColor(), quote = lQuote.getColor();
        if (!watchColorsReady || behind != watchTextBg || quoteBg != watchQuoteBg
                || title != watchTitleInk || meta != watchMetaInk || quote != watchQuoteInk) {
            watchTextBg = behind;
            watchQuoteBg = quoteBg;
            watchTitleInk = title;
            watchMetaInk = meta;
            watchQuoteInk = quote;
            watchTitleColor = readable(title, behind);
            watchMetaColor = readable(meta, behind);
            watchQuoteColor = readable(quote, quoteBg);
            watchColorsReady = true;
        }
        lCardTitle.setColor(watchTitleColor);
        lCardMeta.setColor(watchMetaColor);
        lQuote.setColor(watchQuoteColor);
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = Math.min(shelf.size(), shelfVisible);
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched, sized so a full row (shelfVisible)
        // fills the glass from L_SHELF_L to L_SHELF_R; the glass keeps its bottom edge and narrows around fewer apps.
        int slots = Math.max(1, shelfVisible);
        float full = (L_SHELF_R - L_SHELF_L) * k - 2 * pad;
        float tw = (full - (slots - 1) * gap) / slots;
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float bottom = L_SHELF_BOTTOM * k, top = bottom - th - 2 * pad;
        platterRect.set(left - pad, top, left + width + pad, bottom);
        snap(platterRect);
        shelfFirst = Math.max(0, Math.min(shelfFirst, shelf.size() - n));
        float x = left - shelfFirst * (tw + gap), y = top + pad;
        for (int i = 0; i < shelf.size(); i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            snap(tileRects[i]);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Scrolls the shelf, one app at a time, so the focused app is shown: the tiles slide there (unless `snap`). */
    private void revealShelf(boolean snap) {
        int n = Math.min(shelf.size(), shelfVisible);
        int first = Math.max(0, Math.min(Math.max(shelfFirst, shelfIndex - n + 1), Math.min(shelfIndex, shelf.size() - n)));
        if (first == shelfFirst || tileRects.length == 0) return;
        float dx = (shelfFirst - first) * (tileRects[0].width() + TILE_GAP * k);
        for (int i = 0; i < tileRects.length; i++) {
            tileRects[i].offset(dx, 0);
            if (!snap && i < tileSlide.length) tileSlide[i].value -= dx;
        }
        shelfFirst = first;
        tilesDirty = true;
        if (snap) return;
        reorderAnim = true;   // the same slides as moving an app
        wake();
    }

    /**
     * Shelf layer: the app switcher's material turned on its side, a band of near-opaque light glass from edge to edge
     * at the bottom of the screen (day and night alike), a soft shadow on the picture above its top edge and a fine
     * light line along it. 85% opaque (the switcher's is 98%): the picture shows faintly through, without blur behind it.
     */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float top = platterRect.top - BAND_ABOVE * k, edge = 40 * k;
        if (bandGlass == null || bandTop != top || bandH != h) {
            bandTop = top;
            bandH = h;
            bandGlass = new LinearGradient(0, top, 0, h, 0xD9F7F7F9, 0xD9ECECF1, Shader.TileMode.CLAMP);   // 85%: the picture just shows through
            bandShadow = new LinearGradient(0, top - edge, 0, top, 0x00000000, 0x40000000, Shader.TileMode.CLAMP);
        }
        bandPaint.setColor(0xFFFFFFFF);   // a shader draws at the paint's alpha: the lines below leave it low
        bandPaint.setShader(bandShadow);
        c.drawRect(0, top - edge, w, top, bandPaint);
        bandPaint.setShader(bandGlass);
        c.drawRect(0, top, w, h, bandPaint);
        bandPaint.setShader(null);
        bandPaint.setColor(0xB3FFFFFF);
        c.drawRect(0, top, w, top + Math.max(1, 1.5f * k), bandPaint);
        // Its bottom edge shows only when the drawer lifts it: a faint line so it ends cleanly there.
        bandPaint.setColor(0x14000000);
        c.drawRect(0, h - Math.max(1, 1.5f * k), w, h, bandPaint);
    }

    /** How far above the glass's layout edge (SHELF_PAD over the tiles) the band starts (TV px). */
    private static final float BAND_ABOVE = 0;
    private final Paint bandPaint = new Paint();
    private LinearGradient bandGlass, bandShadow;
    private float bandTop, bandH;

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;
    // What's playing, cut to fit the tab row and the clock's row: once per title, never while drawing.
    private String playingTab, playingClock;
    private float playingTabWidth, playingTabCap, playingClockCap;
    private final RectF barRect = new RectF();
    // The bars bounce for a few seconds when the title changes, then stand still: bouncing all along redrew the top
    // layer 10 times a second (~8% of a core on a Skyworth's Cortex-A55), the still screen nothing.
    private static final long BAR_FRAME_MS = 100, BARS_BOUNCE_MS = 3000;
    /** Now Playing's bars, while its tab shows the music playing (an idle home screen otherwise draws once a second). */
    private static final int BARS_FPS = 30;
    private long heroTickAt;
    private long barsUntil;
    private String shownTitle;   // kept while the screen is hidden: showing it again with the same title is no change
    private final Runnable bars = new Runnable() {
        @Override public void run() {
            if (!running || playingTab == null) return;
            heroDirty = true;
            wake();
            if (SystemClock.uptimeMillis() < barsUntil) handler.postDelayed(this, BAR_FRAME_MS);
        }
    };

    private void bounceBars() {
        handler.removeCallbacks(bars);
        if (playingTab == null) return;
        barsUntil = SystemClock.uptimeMillis() + BARS_BOUNCE_MS;
        handler.post(bars);
    }

    /** What's playing changed (or stopped). */
    private void onPlaying() {
        // Pausing stops the watcher, which reports "nothing playing": taken as such, the Now Playing tab went away and
        // the background switched to a card's while hidden, then faded back after the screensaver (a flicker). Resuming
        // starts the watcher again, which reports what's really playing.
        if (!running) return;
        updateMusic();
        fitPlaying();
        heroDirty = true;
        wake();
        // Only a new title bounces the bars and is said to TalkBack: not a subtitle some players rewrite every second (the
        // time), and not the same title again when the screen shows.
        String title = playing.title, now = playing.text;
        if (title != null && now != null && running && playingTab != null && !title.equals(shownTitle)) {
            shownTitle = title;
            bounceBars();
            if (readerOn()) announceForAccessibility(Ui.trf("Now playing: {what}", "what", now.replace(" · ", ", ")));
        }
    }

    /**
     * A screen reader is on (TalkBack, or another service that speaks): only then is a new title announced. Other
     * accessibility services (a button mapper, a sleep timer) would get the announcement too, for nothing.
     */
    private boolean readerOn() {
        AccessibilityManager am = getContext().getSystemService(AccessibilityManager.class);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    private void fitPlaying() {
        String now = playing.text;
        if (now == null || lQuote == null) {
            playingTab = playingClock = null;
            return;
        }
        fitPaint.set(lQuote);
        playingTab = TextUtils.ellipsize(now, fitPaint, 640 * k, TextUtils.TruncateAt.END).toString();
        playingTabWidth = lQuote.measureText(playingTab);
        playingTabCap = Ui.cap(lQuote);
        playingClockCap = Ui.cap(lClockQuote);
        fitPaint.set(lClockQuote);
        playingClock = TextUtils.ellipsize(now, fitPaint, (CONTENT_R - MARGIN - 60) * k, TextUtils.TruncateAt.END).toString();
    }

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = clockMode();
        // At the right margin, under the round buttons: the shelf's glass is narrower and centred with few apps.
        float right = CONTENT_R * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = TABS; i < TABS + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // Beside the Now Playing tab, which shows what's playing big, this row stays empty.
        if (!clock && musicShown()) {
            text.setColor(textColor);
            return;
        }
        // What an app is playing takes the quotes' place while it plays.
        String line = clock ? playingClock : playingTab;
        if (line != null) {
            float cap = clock ? playingClockCap : playingTabCap, gap = cap * 0.6f;
            float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + cap / 2;
            float x = clock ? MARGIN * k : right - playingTabWidth - gap - NowPlaying.barsWidth(cap);
            NowPlaying.drawBars(c, x, base, cap, text, SystemClock.uptimeMillis() < barsUntil, barRect);
            c.drawText(line, x + NowPlaying.barsWidth(cap) + gap, base, text);
            text.setColor(textColor);
            return;
        }
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (!reorderTop && liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        int last = Math.min(shelf.size(), shelfFirst + shelfVisible);
        // Scrolling, tiles slide in and out through the glass's sides (only those that show any part are drawn). Only
        // then: on a shelf that fits, the clip would cut the end tiles' shadows in a straight line.
        boolean scrolls = shelf.size() > shelfVisible;
        if (scrolls) {
            c.save();
            c.clipRect(platterRect.left, 0, platterRect.right, h);
        }
        for (int pass = 0; pass < 2; pass++) {
            // The focused tile last, outside the clip: it's always one of those shown, and its grown size and deep
            // shadow reach past the glass's sides (the band goes to the screen's edges; the first app's was cut).
            if (pass == 1 && scrolls) {
                c.restore();
                scrolls = false;
            }
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float slid = i < tileSlide.length ? tileSlide[i].value : 0;
                if (tileRects[i].right + slid <= platterRect.left || tileRects[i].left + slid >= platterRect.right) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = !reorderTop && i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (scrolls) c.restore();
        // More apps than fit: a small arrow inside the glass's edge on each side that has more, where no tile reaches
        // even focused (inside the screen's safe area).
        float cy = platterRect.centerY(), size = 28 * k, inset = 14 * k;
        int ink = 0x80000000;   // on the band's light glass
        if (last < shelf.size()) Symbols.draw(getContext(), c, "chevron_right", platterRect.right - inset, cy, size, ink);
        if (shelfFirst > 0) {
            c.save();
            c.scale(-1, 1, platterRect.left + inset, cy);
            Symbols.draw(getContext(), c, "chevron_right", platterRect.left + inset, cy, size, ink);
            c.restore();
        }
    }

    /**
     * A shelf app: the same white card for every app on the band's light glass, with its wordmark in near-black, so
     * the row stays calm under the artwork. Focused, it grows 12% from the center with a deep shadow, fills
     * with its brand colour and the logo turns white. An app without a logo shows its TV art.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = !reorderTop && i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        // At rest the white tiles stand just off the band (a faint shadow); a deep one comes with the focus.
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.08f + 0.26f * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        if (it.logo != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                int[] plate = PLATES.get(it.key);
                int top = plate != null ? plate[0] : it.brand != 0 ? it.brand : 0xFF3A3A3C;
                int bottom = plate != null ? plate[1] : blend(top, 0xFF000000, 0.3f);
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, top, bottom, Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(null);
            tileFill.setColor(0xF2FFFFFF);   // a white card on the band's light glass, day and night
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileShaders[i]);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setShader(tileGlow);
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            // The white card's fine edge at rest (inside the tile, as wide on screen at any size); focused, the brand
            // colour and the shadow are enough on the light band (a white rim there looked like a stray outline).
            float inset = 0.75f * k / s;
            tmp.inset(inset, inset);
            tileEdge.setStrokeWidth(1.5f * k / s);
            tileEdge.setColor(0x0A000000);
            if (fc < 0.99f) c.drawRoundRect(tmp, r0 - inset, r0 - inset, tileEdge);
            c.restore();
            drawQuietLogo(c, it, cell, s, fc, tileFocus[i].settled() && l == 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`: the shader of an anti-aliased rounded rectangle. A
     * clipPath there isn't anti-aliased on some GPUs (stair-stepped corners on a MediaTek TV), and on Android 8 to 11 it
     * cost a mask on every frame of a focus animation (measured on a Mi Box 3: most of a 49 ms frame). `shader` is the
     * image's own, made with it (HomeApps.Item, Backdrop) and dropped with it; the canvas does the scaling, so drawing
     * changes nothing in it and allocates nothing.
     */
    private void roundImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float sx = cell.width() / image.getWidth(), sy = cell.height() / image.getHeight();
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        imageFill.setShader(shader);
        c.drawRoundRect(0, 0, image.getWidth(), image.getHeight(), r / sx, r / sy, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    /** coverImage in black and white while it's set (search's titles none of your apps has). */
    private boolean grayImage;
    private static final android.graphics.ColorMatrixColorFilter GRAY;
    static {
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        m.setSaturation(0);
        GRAY = new android.graphics.ColorMatrixColorFilter(m);
    }

    /**
     * Search: "Not on your apps" on the card itself, a dark capsule in its top left (the card at `cell`, grown by `s`).
     * Laid out once at its resting size and scaled with the card: a new text size on each frame of the focus made the
     * text jump as the card settled.
     */
    private void drawNotOnPill(Canvas c, float s) {
        if (lNotOn == null || lNotOn.getTextSize() != 22 * k || !notOnText.equals(Ui.tr("Not on your apps"))) {
            lNotOn = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
            notOnText = Ui.tr("Not on your apps");
            notOnWidth = lNotOn.measureText(notOnText);
        }
        float h = 46 * k, w = notOnWidth + 32 * k, l = cell.left + 16 * k * s, t = cell.top + 16 * k * s;
        c.save();
        c.translate(l, t);
        c.scale(s, s);
        tmp.set(0, 0, w, h);
        fill.setShader(null);
        fill.setColor(0xB3141518);
        c.drawRoundRect(tmp, h / 2, h / 2, fill);
        c.drawText(notOnText, 16 * k, h / 2 + Ui.halfCap(lNotOn), lNotOn);
        c.restore();
    }

    private Paint lNotOn;
    private String notOnText = "";
    private float notOnWidth;

    /** The image cropped at the center to the cell's shape (not stretched), at `alpha`. */
    private void coverImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r, float alpha) {
        float iw = image.getWidth(), ih = image.getHeight();
        float sw = Math.min(iw, cell.width() / cell.height() * ih), sl = (iw - sw) / 2;
        float sx = cell.width() / sw, sy = cell.height() / ih;
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        c.translate(-sl, 0);
        imageFill.setShader(shader);
        imageFill.setAlpha(Math.round(255 * alpha));
        if (grayImage) imageFill.setColorFilter(GRAY);
        c.drawRoundRect(sl, 0, sl + sw, ih, r / sx, r / sy, imageFill);
        imageFill.setColorFilter(null);
        imageFill.setAlpha(255);
        imageFill.setShader(null);
        c.restore();
    }

    /** Like coverImage, but crops whichever side is too long (an app's 16:9 picture on a wider or narrower card). */
    private void fillImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float iw = image.getWidth(), ih = image.getHeight(), want = cell.width() / cell.height();
        float sw = Math.min(iw, want * ih), sh = Math.min(ih, iw / want), sl = (iw - sw) / 2, st = (ih - sh) / 2;
        float sx = cell.width() / sw, sy = cell.height() / sh;
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        c.translate(-sl, -st);
        imageFill.setShader(shader);
        c.drawRoundRect(sl, st, sl + sw, st + sh, r / sx, r / sy, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    /**
     * The shelf's logo in one colour: near-black on its white card, turning white as the tile takes the focus. Put on
     * whole pixels only once the tile is `still`: while the focus springs back from its overshoot, snapping on and off
     * (each time the scale crossed 1) made the logo shake at the end of the animation.
     * (its brand colour behind). Two draws with prebuilt filters, so an animation creates nothing per frame.
     */
    private void drawQuietLogo(Canvas c, HomeApps.Item it, RectF box, float s, float fc, boolean still) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, false, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (still && (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f)) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        float white = fc;   // how much of the ink is white: near-black on the white tile, white on the brand colour
        if (white < 0.999f) {
            bitmapPaint.setColorFilter(darkLogo);
            bitmapPaint.setAlpha(Math.round(255 * 0.86f * (1 - white)));
            c.drawBitmap(logo, null, tmp, bitmapPaint);
        }
        if (white > 0.001f) {
            bitmapPaint.setColorFilter(whiteLogo);
            bitmapPaint.setAlpha(Math.round(255 * white));
            c.drawBitmap(logo, null, tmp, bitmapPaint);
        }
        bitmapPaint.setAlpha(255);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        // The same amount of ink for every logo, a large share of the tile (the wordmarks are the tile's only content).
        float scale = (float) Math.sqrt(bw * bh * 0.12f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.82f / lw, bh * 0.6f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = Ui.downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts(c));
        list.addAll(HomeAssistant.items(c));   // empty unless configured and on in Home Settings
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        int home = inputs;
        while (home < list.size() && list.get(home).kind != HomeApps.HOME_ASSISTANT) home++;
        drawerItems = list;
        drawerInputs = inputs;
        drawerHome = home;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = Math.max(1, (drawerInputs > 0 ? 1 : 0) + (drawerHome > drawerInputs ? 1 : 0) + (n > drawerHome ? 1 : 0));
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if ((i == drawerInputs || i == drawerHome) && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            snap(drawerRects[i]);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        snap(weatherCard);
        snap(quotesCard);
        // The symbols at their sizes at rest and focused: drawing them never scales one.
        for (HomeApps.Item it : drawerItems) {
            Symbols.warm(getContext(), it.symbol, 34 * k);
            Symbols.warm(getContext(), it.symbol, 34 * k * 1.07f);
        }
        Symbols.warmWeather(getContext(), 52 * k);
        Symbols.warmWeather(getContext(), 30 * k);
        Symbols.warmWeather(getContext(), 24 * k);   // the wide card's curve and week rows
        Symbols.warmWeather(getContext(), 22 * k);
        Symbols.warm(getContext(), "sunrise", 22 * k);
        Symbols.warm(getContext(), "sunset", 22 * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
        readHome();
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        handler.removeCallbacks(homeTick);
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    // MARK: - Home Assistant tiles

    /** Reads the tiles' states now and every HomeAssistant.REFRESH_MS while the drawer is open. */
    private final Runnable homeTick = this::readHome;

    private void readHome() {
        handler.removeCallbacks(homeTick);
        if (!running || !drawerOpen || drawerHome >= drawerItems.size()) return;
        HomeAssistant.refresh(getContext(), this::homeChanged);
        handler.postDelayed(homeTick, HomeAssistant.REFRESH_MS);
    }

    /** A state or a name changed: the tiles are drawn again (and TalkBack reads the new state). */
    private void homeChanged() {
        // A cover shows once its class is read, and a garage door or a gate goes: then the tiles themselves change.
        List<HomeApps.Item> tiles = HomeAssistant.itemsNow(getContext());
        boolean same = tiles.size() == drawerItems.size() - drawerHome;
        for (int i = 0; same && i < tiles.size(); i++) same = tiles.get(i).key.equals(drawerItems.get(drawerHome + i).key);
        if (!same) {
            List<HomeApps.Item> list = new ArrayList<>(drawerItems.subList(0, drawerHome));
            list.addAll(tiles);
            setDrawerItems(list);
        }
        for (int i = drawerHome; i < drawerItems.size(); i++) {
            HomeApps.Item it = drawerItems.get(i);
            if (it.control != null) it.label = it.control.name;
        }
        drawerDirty = true;
        wake();
    }

    private void pressHome(HomeApps.Item it) {
        if (it.control == null) return;
        HomeAssistant.activate(getContext(), it.control, ok -> {
            if (!ok) showToast(Ui.tr("Couldn't reach Home Assistant."));
            homeChanged();
        });
        homeChanged();   // a light shows its new state at once
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, glassLight(), lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        dayLow.setColor(tone(L_DIM, 0xADFFFFFF));
        wxBig.setColor(drawerInk(1));
        wxSubPaint.setColor(drawerInk(0.95f));
        wxCurvePaint.setColor(drawerInk(0.95f));
        wxHourPaint.setColor(tone(L_FAINT, 0x6BFFFFFF));
        wxRowPaint.setColor(drawerInk(0.95f));
        wxRowLow.setColor(tone(L_DIM, 0x9EFFFFFF));
        wxRowHigh.setColor(drawerInk(0.95f));
        wxRainPaint.setColor(tone(0xFF1F6FD1, 0xFF6CB6FF));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerHome) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        if (drawerHome < drawerItems.size()) c.drawText("Home Assistant", drawerRects[drawerHome].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected
                || it.kind == HomeApps.HOME_ASSISTANT && it.control != null && !it.control.on();
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours or 5 days, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        if (r.width() >= 1400 * k && !wx.hours.isEmpty()) {
            drawWideWeather(c, r, wx);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k;
        if (textDays) {
            float hw = (r.right - 30 * k - hx) / 5;
            for (int i = 0; i < wx.days.size() && i < 5; i++) {
                float hc = hx + hw * (i + 0.5f);
                c.drawText(dayText[i], hc, r.top + 96 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.dayCode.get(i), true), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
                // The high, then the low dimmed, centered together (closer than the next day, so the pairs read apart).
                float maxW = hourTemp.measureText(dayMaxText[i]), minW = dayLow.measureText(dayMinText[i]), gap = 6 * k;
                float left = hc - (maxW + gap + minW) / 2;
                c.drawText(dayMaxText[i], left + maxW / 2, r.top + 196 * k, hourTemp);
                c.drawText(dayMinText[i], left + maxW + gap + minW / 2, r.top + 196 * k, dayLow);
            }
        } else {
            float hw = (r.right - 30 * k - hx) / 6;
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                float hc = hx + hw * (i + 0.5f);
                c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
                c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
            }
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    /**
     * The weather card across the drawer: the city, the condition and feels-like on top; a large temperature that
     * starts the next 24 hours' curve (every third hour's temperature, symbol and time on it); and on the right the
     * week as rows, each day's low and high at the ends of a bar on the week's range, the chance of rain on wet days.
     */
    private void drawWideWeather(Canvas c, RectF r, HomeData.Weather wx) {
        Context ctx = getContext();
        float x = r.left + 34 * k;
        c.drawText(wxSub, x + cardTitle.measureText(weatherTitle) + 22 * k, r.top + 54 * k, wxSubPaint);
        c.drawText(nowTemp, r.left + 30 * k - Ui.leftBearing(wxBig, nowTemp), r.top + 154 * k, wxBig);
        float rowsL = r.right - 636 * k, divider = rowsL - 34 * k;
        float x0 = r.left + 220 * k, x1 = divider - 40 * k, yT = r.top + 160 * k, yB = r.top + 210 * k, base = r.top + 250 * k;
        boolean dk = dark() > 0.5f;
        if (wx != wxBuiltWeather || !wxBuiltFor.equals(r) || dk != wxBuiltDark) buildWideWeather(r, wx, x0, x1, yT, yB, base, rowsL, dk);
        fill.setColor(0xFFFFFFFF);
        fill.setShader(wxFillShader);
        c.drawPath(wxFill, fill);
        fill.setShader(null);
        wxStroke.setStrokeWidth(3 * k);
        wxStroke.setColor(drawerInk(0.85f));
        c.drawPath(wxLine, wxStroke);
        fill.setColor(drawerInk(1));
        c.drawCircle(wxPointX[0], wxPointY[0], 7 * k, fill);
        int iconInk = drawerInk(0.8f);
        for (int i = 0; i < wxPoints; i += 3) {
            float px = wxPointX[i];
            c.drawText(wxCurveTemp[i], px, wxPointY[i] - 18 * k, wxCurvePaint);
            wxHourPaint.setColor(i == 0 ? drawerInk(0.95f) : tone(L_FAINT, 0x6BFFFFFF));
            c.drawText(wxCurveHour[i], px, base + 27 * k, wxHourPaint);
            int code = i == 0 ? wx.code : wx.hourCode.get(i - 1);
            boolean day = i == 0 ? wx.isDay : wx.hourDay.get(i - 1);
            Symbols.draw(ctx, c, Symbols.weather(code, day), px, r.top + 92 * k, 24 * k, iconInk);
        }
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(divider - k, r.top + 34 * k, divider + k, r.bottom - 34 * k, k, k, fill);
        float barL = rowsL + 230 * k, barR = r.right - 100 * k, rowH = 41 * k;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < wxRows; i++) {
            lo = Math.min(lo, wxRowLo[i]);
            hi = Math.max(hi, wxRowHi[i]);
        }
        double span = Math.max(1, hi - lo);
        for (int i = 0; i < wxRows; i++) {
            float y = r.top + 32 * k + i * rowH, textBase = y + 20 * k, mid = y + 13.5f * k;
            c.drawText(wxRowDay[i], rowsL, textBase, wxRowPaint);
            Symbols.draw(ctx, c, wxRowSymbol[i], rowsL + 92 * k, mid, 22 * k, drawerInk(0.9f));
            if (wxRowRain[i] != null) c.drawText(wxRowRain[i], rowsL + 118 * k, textBase - 2 * k, wxRainPaint);
            c.drawText(wxRowMin[i], barL - 14 * k, textBase, wxRowLow);
            fill.setColor(tone(0x14000000, 0x1AFFFFFF));
            tmp.set(barL, mid - 3.5f * k, barR, mid + 3.5f * k);
            c.drawRoundRect(tmp, 3.5f * k, 3.5f * k, fill);
            if (!Double.isNaN(wxRowLo[i]) && !Double.isNaN(wxRowHi[i])) {
                float a = barL + (float) ((wxRowLo[i] - lo) / span) * (barR - barL), b = barL + (float) ((wxRowHi[i] - lo) / span) * (barR - barL);
                tmp.set(a, mid - 3.5f * k, Math.max(b, a + 7 * k), mid + 3.5f * k);
                fill.setColor(0xFFFFFFFF);   // a shader draws at the paint's alpha: the track's left it at 10%
                fill.setShader(wxBarShader);
                c.drawRoundRect(tmp, 3.5f * k, 3.5f * k, fill);
                fill.setShader(null);
            }
            c.drawText(wxRowMax[i], barR + 14 * k, textBase, wxRowHigh);
        }
    }

    /** The curve's points and paths, and the shaders: made again only when the forecast, the card or day/night change. */
    private void buildWideWeather(RectF r, HomeData.Weather wx, float x0, float x1, float yT, float yB, float base, float rowsL, boolean dk) {
        wxBuiltWeather = wx;
        wxBuiltFor.set(r);
        wxBuiltDark = dk;
        wxPoints = Math.min(HomeData.HOURS, wx.hours.size()) + 1;
        double lo = wx.temp, hi = wx.temp;
        for (int i = 1; i < wxPoints; i++) {
            lo = Math.min(lo, wx.hourTemp.get(i - 1));
            hi = Math.max(hi, wx.hourTemp.get(i - 1));
        }
        double span = Math.max(2, hi - lo), mid = (hi + lo) / 2;
        lo = mid - span / 2 - 0.4;
        hi = mid + span / 2 + 0.4;
        float step = (x1 - x0) / Math.max(1, wxPoints - 1);
        wxLine.reset();
        wxFill.reset();
        for (int i = 0; i < wxPoints; i++) {
            double t = i == 0 ? wx.temp : wx.hourTemp.get(i - 1);
            wxPointX[i] = x0 + i * step;
            wxPointY[i] = yB - (float) ((t - lo) / (hi - lo)) * (yB - yT);
            if (i == 0) wxLine.moveTo(wxPointX[i], wxPointY[i]);
            else wxLine.lineTo(wxPointX[i], wxPointY[i]);
        }
        wxFill.set(wxLine);
        wxFill.lineTo(wxPointX[wxPoints - 1], base);
        wxFill.lineTo(x0, base);
        wxFill.close();
        int ink = dk ? 0xFFFFFFFF : L_INK;
        wxFillShader = new LinearGradient(0, yT - 20 * k, 0, base, Ui.alpha(ink, dk ? 0.2f : 0.12f), Ui.alpha(ink, 0), Shader.TileMode.CLAMP);
        float barL = rowsL + 230 * k, barR = r.right - 100 * k;
        wxBarShader = dk ? new LinearGradient(barL, 0, barR, 0, 0xFF7CC4FF, 0xFFFFCF6B, Shader.TileMode.CLAMP)
                : new LinearGradient(barL, 0, barR, 0, 0xFF3A8EE6, 0xFFF0A030, Shader.TileMode.CLAMP);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            textDays = Prefs.forecast().equals("days") && !wx.days.isEmpty();   // no days yet (an older forecast, offline): the hours
            for (int i = 0; textDays && i < wx.days.size() && i < 5; i++) {
                dayText[i] = HomeData.weekday(wx.days.get(i));
                dayMaxText[i] = HomeData.degrees(wx.dayMax.get(i));
                dayMinText[i] = HomeData.degrees(wx.dayMin.get(i));
            }
            for (int i = 0; !textDays && i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            String feels = Double.isNaN(wx.feels) ? null : Ui.trf("Feels {temp}", "temp", HomeData.degrees(wx.feels));
            String cond = wx.code < 0 ? "" : HomeData.condition(wx.code, wx.isDay);
            wxSub = feels == null ? cond : cond.isEmpty() ? feels : cond + "  ·  " + feels;
            int points = Math.min(HomeData.HOURS, wx.hours.size()) + 1;
            for (int i = 0; i < points; i++) {
                wxCurveTemp[i] = HomeData.degrees(i == 0 ? wx.temp : wx.hourTemp.get(i - 1));
                wxCurveHour[i] = i == 0 ? Ui.tr("Now") : HomeData.hour(wx.hours.get(i - 1));
            }
            wxRows = 0;
            if (!Double.isNaN(wx.max)) {
                wxRowDay[0] = Ui.tr("Today");
                wxRowHi[0] = wx.max;
                wxRowLo[0] = wx.min;
                wxRowSymbol[0] = Symbols.weather(wx.todayCode >= 0 ? wx.todayCode : wx.code, true);
                wxRowRain[0] = wx.todayRain >= 30 ? wx.todayRain + "%" : null;
                wxRows = 1;
            }
            for (int i = 0; i < wx.days.size() && wxRows < 6; i++, wxRows++) {
                wxRowDay[wxRows] = HomeData.weekday(wx.days.get(i));
                wxRowHi[wxRows] = wx.dayMax.get(i);
                wxRowLo[wxRows] = wx.dayMin.get(i);
                wxRowSymbol[wxRows] = Symbols.weather(wx.dayCode.get(i), true);
                int rain = i < wx.dayRain.size() ? wx.dayRain.get(i) : -1;
                wxRowRain[wxRows] = rain >= 30 ? rain + "%" : null;
            }
            for (int i = 0; i < wxRows; i++) {
                wxRowMax[i] = HomeData.degrees(wxRowHi[i]);
                wxRowMin[i] = HomeData.degrees(wxRowLo[i]);
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale);
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        revealShelf(snap);
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        heroChanged |= aim(cardOpen, cardIndex, snap);
        float scrollTo = Math.max(0, cardIndex - 2);
        heroChanged |= cardScroll.target != scrollTo;
        cardScroll.target = scrollTo;
        if (snap) cardScroll.snap(scrollTo);
        if (watchTab()) loadWatchCards();
        if (picksMode() && !cards.isEmpty()) {
            handler.removeCallbacks(bgFollow);
            handler.postDelayed(bgFollow, BG_FOLLOW_MS);
        }
        heroChanged |= aim(watchFocus, !sheetOpen && zone == Z_WATCH ? watchIndex : -1, snap);
        heroChanged |= aim(musicFocus, !sheetOpen && zone == Z_MUSIC ? musicIndex : -1, snap);
        heroChanged |= aim(watchOpen, watchIndex, snap);
        if (continueMode() && !watching.isEmpty()) {
            loadWatchBackdrops();
            handler.removeCallbacks(bgFollow);
            handler.postDelayed(bgFollow, BG_FOLLOW_MS);
        }
        updateTint();
        // As the picks: the row scrolls so the focused (wide) card is at most the third one on screen.
        float watchTo = Math.max(0, watchIndex - 2);
        if (watchScroll.target != watchTo || snap) {
            heroChanged = true;
            if (snap) watchScroll.snap(watchTo); else watchScroll.target = watchTo;
        }
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    /** All Apps from the remote's button. Coming back from another app, resume() opens it (it closes the sheet). */
    void showAllApps() {
        if (!running) {
            allAppsOnResume = true;
            return;
        }
        reset(false);
        openSheet();
    }

    private boolean allAppsOnResume;

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i), gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, HomeApps.Item it, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private float titleRight;   // where the top title ends (0: none), so toasts don't cover it

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        // Centered, unless that covers the title (a long one, such as Continue Watching): then just right of it, and
        // never under the top buttons.
        float left = Math.max((w - tw) / 2f - padX, Math.min(titleRight + 40 * k, toastRight() - tw - 2 * padX));
        tmp.set(left, toastTop, left + tw + 2 * padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    /** Where a toast must end: before the top buttons (or the content's right edge without them). */
    private float toastRight() {
        return lightButtons.isEmpty() || topRects[TABS] == null ? w - MARGIN * k : topRects[TABS].left - 24 * k;
    }

    private StaticLayout layout(String text) {
        // As wide as fits between the title (the clock corner) and the top buttons, 720 at most: a long one wraps.
        float room = toastRight() - (titleRight + 40 * k) - 2 * 28 * k - 26 * k - 16 * k;
        int max = Math.round(room > 300 * k ? Math.min(720 * k, room) : 720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        final int orderAtLoad = orderRevision;
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons(c) : null;   // they change with Prefs too
            // Only read when it's shown: with the picks or the clock, Continue Watching costs nothing.
            final String mode = Prefs.topContent();
            final boolean allowed = WatchNext.allowed(c);
            final List<WatchNext.Item> w = Prefs.watchShown(mode) ? WatchNext.load(c) : new ArrayList<>();
            handler.post(() -> {
                // A provider/package refresh must not cancel a drag or undo a move that was just saved.
                boolean keepOrder = reordering || pendingOrderSaves > 0 || orderAtLoad != orderRevision;
                if (top != null && !keepOrder) setLightButtons(top);
                setTopContent(mode, w, allowed);
                if (s != null && !keepOrder) setShelf(s);
                if (apps && keepOrder) appsStale = true;
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK, Back and Settings (system keys pass through to the system)

    /** A mouse (or a touch screen) clicks what is under it, like OK on the focused item. */
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (searchOpen) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchX = e.getX();
                touchY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (Math.hypot(e.getX() - touchX, e.getY() - touchY) < 24 * k) tap(e.getX(), e.getY());
                return true;
            default:
                return true;
        }
    }

    /** A mouse moves the focus to what is under the pointer, like a trackpad on tvOS. */
    @Override public boolean onHoverEvent(MotionEvent e) {
        if (searchOpen) return true;
        if (e.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) hover(e.getX(), e.getY());
        return true;
    }

    /** The mouse wheel moves through the shelf, the cards, the drawer and All Apps. */
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if (searchOpen) return true;
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v != 0) {
                wheel(v < 0 ? 1 : -1);
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    /**
     * The item the pointer was last over (zone * 1000 + index, -1: none). Hover moves the focus only when the pointer
     * comes onto another item, as LG's mouseover does: a resting pointer nudged by a hand (an air mouse) doesn't pull
     * the focus back from where the arrows took it.
     */
    private int hovered = -1;
    private static final int H_MENU = 7, H_GRID = 8;   // hover's codes for the menu's rows and All Apps' cells

    /** True when the pointer has just come onto (zone, index); index -1 (nothing there) only forgets the last one. */
    private boolean entered(int zone, int index) {
        int code = index < 0 ? -1 : zone * 1000 + index;
        if (code == hovered) return false;
        hovered = code;
        return code >= 0;
    }

    private void hover(float x, float y) {
        wake();
        if (reordering) return;
        if (menuOpen) {   // only the menu's rows: what's behind it stays as it was
            int row = menuAt(x, y);
            if (entered(H_MENU, row)) moveMenu(row - menuIndex);
            return;
        }
        if (sheetOpen) {
            int hit = gridAt(x, y, true);
            if (entered(H_GRID, hit) && hit != gridIndex) {
                gridIndex = hit;
                retargetGrid(false);
                gridDirty = true;
            }
            return;
        }
        if (drawerOpen) {
            int hit = hit(drawerRects, x, y);
            if (entered(Z_DRAWER, hit) && (zone != Z_DRAWER || drawerIndex != hit)) {
                zone = Z_DRAWER;
                drawerIndex = hit;
                retarget(false);
            }
            return;
        }
        int z = Z_TOP, hit = topAt(x, y);
        if (hit < 0) {
            z = Z_CARDS;
            hit = cardAt(x, y, true);
        }
        if (hit < 0) {
            z = Z_WATCH;
            hit = watchAt(x, y, true);
        }
        if (hit < 0) {
            z = Z_SHELF;
            hit = tileAt(x, y);
        }
        if (!entered(z, hit)) return;
        int now = zone == Z_TOP ? topIndex : zone == Z_CARDS ? cardIndex : zone == Z_WATCH ? watchIndex : shelfIndex;
        if (zone == z && now == hit) return;
        zone = z;
        if (z == Z_TOP) {
            topIndex = hit;
            if (hit < TABS) selectTab(hit);   // as with the arrows (and on LG): focusing a tab selects it
        } else if (z == Z_CARDS) {
            cardIndex = hit;
        } else if (z == Z_WATCH) {
            watchIndex = hit;
        } else {
            shelfIndex = hit;
        }
        retarget(false);
    }

    private void wheel(int dir) {
        wake();
        if (reordering) return;   // the arrows move the lifted app; the wheel would move the focus off it
        if (menuOpen) {
            moveMenu(dir);
        } else if (sheetOpen) {
            int next = Math.max(0, Math.min(others.size() - 1, gridIndex + dir * COLUMNS));
            if (next != gridIndex) {
                gridIndex = next;
                retargetGrid(false);
                gridDirty = true;
            }
        } else if (drawerOpen) {
            int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dir));
            if (next != drawerIndex) {
                zone = Z_DRAWER;
                drawerIndex = next;
                retarget(false);
            }
        } else if (zone == Z_CARDS && !cards.isEmpty()) {
            int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dir));
            if (next != cardIndex) {
                cardIndex = next;
                retarget(false);
            }
        } else if (zone == Z_WATCH && !watching.isEmpty()) {
            int next = Math.max(0, Math.min(watching.size() - 1, watchIndex + dir));
            if (next != watchIndex) {
                watchIndex = next;
                retarget(false);
            }
        } else if (!shelf.isEmpty()) {
            int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dir));
            if (next != shelfIndex) {
                zone = Z_SHELF;
                shelfIndex = next;
                retarget(false);
            }
        }
    }

    private void tap(float x, float y) {
        wake();
        if (reordering) return;
        if (menuOpen) {   // a click on a row picks it; anywhere else closes the menu (never what's behind it)
            int row = menuAt(x, y);
            if (row < 0) {
                closeMenu(false);
                click.move();
                return;
            }
            menuIndex = row;
            menuPick.target = row;
            pickMenu();
            return;
        }
        if (sheetOpen) {
            int hit = gridAt(x, y, false);
            if (hit >= 0) {
                gridIndex = hit;
                retargetGrid(false);
                click.move();
                gridDirty = true;
                activate();
            }
            return;
        }
        if (drawerOpen) {
            int hit = hit(drawerRects, x, y);
            if (hit >= 0) {
                zone = Z_DRAWER;
                drawerIndex = hit;
                retarget(false);
                activate();
            }
            return;
        }
        int hit = topAt(x, y);
        if (hit >= 0) {
            zone = Z_TOP;
            topIndex = hit;
            retarget(false);
            activate();
            return;
        }
        hit = cardAt(x, y, false);
        if (hit >= 0) {
            zone = Z_CARDS;
            cardIndex = hit;
            retarget(false);
            activate();
            return;
        }
        hit = watchAt(x, y, false);
        if (hit >= 0) {
            zone = Z_WATCH;
            watchIndex = hit;
            retarget(false);
            activate();
            return;
        }
        hit = tileAt(x, y);
        if (hit >= 0) {
            zone = Z_SHELF;
            shelfIndex = hit;
            retarget(false);
            activate();
        }
    }

    /** The menu's row under a point, if any. */
    private int menuAt(float x, float y) {
        for (int i = 0; i < menuOptions.length; i++) {
            menuRow(i, hitBox);
            if (hitBox.contains(x, y)) return i;
        }
        return -1;
    }

    /**
     * The tab or round button under a point: the tabs only with the picks, and only the buttons there now (a shorter
     * list leaves the old places of the others in topRects).
     */
    private int topAt(float x, float y) {
        int hit = hit(topRects, x, y);
        return hit >= TABS + lightButtons.size() || hit >= tabCount() && hit < TABS || hit >= 0 && hit < TABS && !picksMode() ? -1 : hit;
    }

    /**
     * The picks' card under a point, if any. Hovering (`still`) only takes a card that keeps its row where it is: the
     * row scrolls so the focused card is the third (retarget), so hovering the one peeking in at the edge, or the first
     * one of a scrolled row, would slide the next one under the pointer, and the focus would run along the row as the
     * pointer moves. A click takes any card.
     */
    private int cardAt(float x, float y, boolean still) {
        int hit = hit(cardRects, x, y);
        if (hit < 0 || hit >= cards.size()) return -1;
        return still && Math.max(0, hit - 2) != cardScroll.target ? -1 : hit;
    }

    /** The Continue Watching card under a point, if any (only the ones on screen; `still` as in cardAt). */
    private int watchAt(float x, float y, boolean still) {
        if (!continueMode()) return -1;
        for (int i = 0; i < watching.size(); i++) {
            watchRect(i, hitBox);
            if (hitBox.left > w || hitBox.right <= watchLeftEdge()) continue;
            if (!hitBox.contains(x, y)) continue;
            return still && Math.max(0, i - 2) != watchScroll.target ? -1 : i;
        }
        return -1;
    }

    /**
     * The shelf tile under a point, if any: only the ones on screen. The next tile's place is just past the glass, at the
     * screen's edge, and a pointer resting there would scroll the shelf one app at a time to its end.
     */
    private int tileAt(float x, float y) {
        int last = Math.min(tileRects.length, shelfFirst + Math.min(shelf.size(), shelfVisible));
        for (int i = shelfFirst; i < last; i++) if (tileRects[i].contains(x, y)) return i;
        return -1;
    }

    private static int hit(RectF[] rects, float x, float y) {
        for (int i = 0; i < rects.length; i++) if (!rects[i].isEmpty() && rects[i].contains(x, y)) return i;
        return -1;
    }

    /**
     * The All Apps cell under a point, if any (the sheet's own layout, scrolled). Hovering (`still`) only takes the
     * rows on screen: a row peeks in at the bottom (and at the top once scrolled), and focusing it would scroll the
     * next one under the pointer (retargetGrid's rows).
     */
    private int gridAt(float x, float y, boolean still) {
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k, top = GRID_TOP * k - scroll.value * GRID_ROW * k;
        float cw = GRID_W * k, ch = GRID_H * k, gap = GRID_GAP * k, row = GRID_ROW * k;
        for (int i = 0; i < others.size(); i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float x0 = left + col * (cw + gap), y0 = top + r * row;
            if (x >= x0 && x < x0 + cw && y >= y0 && y < y0 + ch) {
                if (still && (r < scroll.target || r > scroll.target + VISIBLE_ROWS - 1)) return -1;
                focusedCell.set(x0, y0, x0 + cw, y0 + ch);
                return i;
            }
        }
        return -1;
    }

    boolean onKey(KeyEvent e) {
        wake();
        if (menuOpen) return menuKey(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (recents != null && recents.isOpen()) {   // not as it fades out: an OK or ▲ then is the home screen's, not a card's
            int was = recents.focus();
            boolean handled = recents.key(e);
            recentLayer.invalidate();
            if (recents.focus() != was) spoken.focusMoved();
            if (!recents.visible() || recents.count() == 0) spoken.readAgain();
            return handled;
        }
        if (searchOpen && searchKey(e)) return true;
        if (down && e.getKeyCode() == KeyEvent.KEYCODE_APP_SWITCH && !Prefs.recents().equals("off")) {
            openRecents();
            return true;
        }
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode, no menu
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf; on a Continue
                // Watching card: its menu. A timer, since not every remote repeats a held key, or the long-press repeat,
                // whichever comes first; the release after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder() || canHold();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_NOTIFICATION:
                // The remote's Settings button opens TV Settings; ours stay on the gear on screen. TCL's and Hisense's
                // gear (NOTIFICATION) is a Remote Buttons choice (RemoteKeys, first): with Nothing, the key is left alone.
                // Where the system takes the key first, it never gets here.
                if (RemoteKeys.HISENSE || RemoteKeys.TCL) return false;
                if (down && e.getRepeatCount() == 0) HomeApps.open(activity, HomeApps.tvSettings(), this, w / 2, h / 2, 1, 1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    /** Home pressed twice (HomeActivity). */
    void showRecents() {
        openRecents();
    }

    /** Recent Apps over a blurred copy of the screen (without the copy, over a dark veil). */
    private void openRecents() {
        if (recents == null) {
            recents = new RecentApps(activity, new RecentApps.Host() {
                @Override public void open(HomeApps.Item item) {
                    handler.post(() -> {
                        if (!HomeApps.open(activity, item, HomeView.this, getWidth() / 2, getHeight() / 2, 1, 1)) {
                            showToast(Ui.trf("Couldn't open {app}.", "app", item.label));
                        }
                    });
                }
                @Override public void repaint() {
                    recentLayer.postInvalidate();
                    handler.post(() -> { wake(); spoken.readAgain(); });   // the list arrived: TalkBack reads the cards
                }
            }, k);
        }
        ScreenBlur.capture(activity, handler, blurred -> {
            recents.setBackdrop(blurred);
            recents.open();
            recentLayer.setVisibility(VISIBLE);
            recentLayer.invalidate();
            spoken.readAgain();
            wake();
        });
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        if (menuOpen) {
            pickMenu();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < TABS) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - TABS);
            r = topRects[topIndex];
        } else if (zone == Z_MUSIC) {
            musicAction(musicIndex);
            return;
        } else if (zone == Z_WATCH) {
            if (watchIndex >= watching.size()) return;
            click.open();
            openWatching(watching.get(watchIndex));
            return;
        } else if (zone == Z_CARDS && watchTab()) {   // resumes it in its app, as Continue Watching's own row does
            if (cardIndex >= watching.size()) return;
            click.open();
            openWatching(watching.get(cardIndex));
            return;
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.SEARCH) {
            openSearch();
            return;
        }
        if (it.kind == HomeApps.RECENTS) {
            openRecents();
            return;
        }
        if (it.kind == HomeApps.HOME_ASSISTANT) {
            pressHome(it);
            return;
        }
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move;
    // on a Continue Watching card, its menu opens

    private void hold() {
        if (sheetOpen) {
            toggleShelf();
        } else if (canReorder()) {
            startReorder();
        } else if (canHold()) {
            handler.removeCallbacks(holdStart);
            holdArmed = false;
            okDown = false;     // the menu gets keys now: this press's release must not pick an option
            showMenu();
        }
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int max = HomeApps.maxShelf(c), shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= max) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= max) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving shelf apps and top buttons

    private boolean canReorder() {
        if (sheetOpen || menuOpen) return false;
        if (zone == Z_TOP) return topIndex >= TABS && topIndex < TABS + lightButtons.size() && lightButtons.size() > 1;
        if (zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        cancelReorder(true);   // settle an earlier drop before lifting another row
        wake();
        reorderTop = zone == Z_TOP;
        orderRevision++;
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(reorderTop ? lightButtons : shelf);
        liftIndex = reorderTop ? topIndex - TABS : shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        if (reorderTop) {
            int i = topIndex - TABS, j = i + dx;
            if (j < 0 || j >= lightButtons.size()) return;
            java.util.Collections.swap(lightButtons, i, j);
            Spring focus = topFocus[TABS + i];
            topFocus[TABS + i] = topFocus[TABS + j];
            topFocus[TABS + j] = focus;
            Spring slide = topSlide[i];
            topSlide[i] = topSlide[j];
            topSlide[j] = slide;
            float d = topRects[TABS + i].left - topRects[TABS + j].left;
            topSlide[j].value += d;
            topSlide[i].value -= d;
            topIndex = TABS + j;
            liftIndex = j;
            reorderAnim = statusDirty = true;
            wake();
            retarget(false);
            click.move();
            return;
        }
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the item where it is and saves that row's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        orderRevision++;
        moveLift.target = 0;
        List<HomeApps.Item> items = reorderTop ? lightButtons : shelf;
        boolean changed = false;
        for (int i = 0; i < items.size() && !changed; i++) changed = items.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) { if (appsStale && running) reloadSoon(); return; }
        final List<String> order = new ArrayList<>();
        if (reorderTop) {
            for (HomeApps.Item it : lightButtons) order.add(it.key);
            // Unavailable buttons (for example a missing store) keep their saved slots.
            List<String> saved = Prefs.topButtons();
            Prefs.setTopButtons(VisibleOrder.replace(saved == null ? HomeApps.TOP_DEFAULT : saved, order));
            if (appsStale && running) reloadSoon();
            return;
        }
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        pendingOrderSaves++;
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> {
                Prefs.setShelf(save);
                pendingOrderSaves--;
                if (appsStale && running) reloadSoon();
            });
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            orderRevision++;
            moveLift.target = 0;
            int n = reorderTop ? lightButtons.size() : shelf.size();
            if (reorderTop && reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = lightButtons.get(topIndex - TABS);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                for (int a = 0; a < n; a++) {
                    int b = lightButtons.indexOf(reorderOrigin.get(a));
                    focus[a] = topFocus[TABS + b];
                    slide[a] = topSlide[b];
                    slide[a].value += topRects[TABS + b].left - topRects[TABS + a].left;
                }
                lightButtons.clear();
                lightButtons.addAll(reorderOrigin);
                for (int a = 0; a < n; a++) {
                    topFocus[TABS + a] = focus[a];
                    topSlide[a] = slide[a];
                }
                liftIndex = lightButtons.indexOf(lifted);
                topIndex = liftIndex + TABS;
                statusDirty = true;
            } else if (!reorderTop && reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
            if (appsStale && running) reloadSoon();
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            for (Spring s : topSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            reorderTop = false;
            statusDirty = true;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | step(topSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        if (!moving) { reorderTop = false; liftIndex = -1; }
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8, MENU_ITEMS = 10, RECENT_CARDS = 11;

    private boolean recentsOpen() {
        return recents != null && recents.isOpen();   // not while it fades out: TalkBack reads the home screen again
    }

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (recentsOpen()) {   // it covers the screen: only its cards are read (or, with none, its message)
            for (int i = 0; i < Math.max(1, recents.count()); i++) out.add(a11yId(RECENT_CARDS, i));
            return;
        }
        if (menuOpen) {
            for (int i = 0; i < menuOptions.length; i++) out.add(a11yId(MENU_ITEMS, i));
            return;
        }
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = picksMode() ? 0 : TABS; i < TABS + lightButtons.size(); i++) if (i < tabCount() || i >= TABS) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(CARD_MAX, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        if (continueMode()) for (int i = 0; i < watching.size(); i++) out.add(a11yId(Z_WATCH, i));
        for (int i = shelfFirst; i < Math.min(shelf.size(), shelfFirst + shelfVisible); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case RECENT_CARDS:
                if (!recentsOpen()) return "";
                return recents.count() == 0 ? Ui.tr("Recent Apps") + ". " + recents.emptyText() : recents.spoken(i);
            case MENU_ITEMS:
                if (!menuOpen || i >= menuOptions.length) return "";
                return (i == 0 && menuItem != null ? menuItem.title + ". " : "") + menuOptions[i] + ", "
                        + Ui.trf("{i} of {n}", "i", i + 1, "n", menuOptions.length);
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (reordering && reorderTop && i == topIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(lightButtons, i - TABS), "n", i - TABS + 1, "total", lightButtons.size());
                }
                if (i >= 3) return label(lightButtons, i - 3);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", tabCount());
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_WATCH:
                if (i >= watching.size()) return "";
                WatchNext.Item it = watching.get(i);
                String left = timeLeft(it);
                return it.title + (it.episode != null ? ", " + it.episode : "") + (left != null ? ", " + left : "")
                        + (it.app != null && !it.app.isEmpty() ? ". " + Ui.trf("App: {app}", "app", it.app) : "");
            case Z_SHELF:
                if (reordering && !reorderTop && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case RECENT_CARDS:
                if (!recentsOpen()) return false;
                if (recents.count() == 0) recents.emptyBounds(a11yRect);
                else if (!recents.bounds(i, a11yRect)) return false;
                dy = 0;
                break;
            case MENU_ITEMS:
                if (!menuOpen || i >= menuOptions.length) return false;
                menuRow(i, a11yRect);
                dy = 0;
                break;
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= TABS + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(CARD_MAX, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_WATCH:
                if (i >= watching.size()) return false;
                watchRect(i, a11yRect);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    /** What holding OK does on an item, TalkBack offers as a long click. */
    private boolean a11yHolds(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == RECENT_CARDS) return recentsOpen() && recents.stoppable(i);   // TalkBack's long click closes it, as ▲ does
        if (group == GRID) return canToggleShelf(i);
        if (group == Z_TOP) {
            return !reordering && !menuOpen && !sheetOpen && lightButtons.size() > 1
                    && i >= TABS && i < TABS + lightButtons.size();
        }
        if (group == Z_SHELF) {   // TalkBack's long click lifts the app, as holding OK does
            return !reordering && !menuOpen && !sheetOpen && shelf.size() > 1 && i < shelf.size()
                    && shelf.get(i).kind == HomeApps.APP && shelf.get(i).pkg != null;
        }
        return group == Z_WATCH && !menuOpen && continueMode() && i < watching.size();
    }

    private int a11yFocused() {
        if (recentsOpen()) return a11yId(RECENT_CARDS, recents.count() == 0 ? 0 : recents.focus());
        if (menuOpen) return a11yId(MENU_ITEMS, menuIndex);
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_WATCH: return watchIndex < watching.size() ? a11yId(Z_WATCH, watchIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /**
     * The background is the focused pick's, as on Netflix and Prime Video: once the focus has rested on it BG_FOLLOW_MS,
     * so a quick run along the row fades once. Its backdrop is already in memory (this tab's picks all are).
     */
    private final Runnable bgFollow = new Runnable() {
        @Override public void run() {
            if (running && continueMode()) {
                if (watchBackground()) wake();
                return;
            }
            if (!running || !picksMode() || cards.isEmpty()) return;
            Backdrop b = recs.backdrop(cards.get(Math.min(cardIndex, cards.size() - 1)));
            // Not built yet (updateCards shows it when it is), or already there: nothing to fade, no frame to draw
            if (b == null || b == bgTo) return;
            showBackdrop(b);
            wake();
        }
    };

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (recentsOpen()) {
            if (group == RECENT_CARDS) recents.setFocus(i);
            recentLayer.invalidate();
            return;
        }
        if (menuOpen) {
            if (group == MENU_ITEMS && i < menuOptions.length && i != menuIndex) moveMenu(i - menuIndex);
            return;
        }
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= TABS + lightButtons.size() || (i < TABS && (!picksMode() || i >= tabCount()))) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < TABS) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_WATCH:
                if (i >= watching.size()) return;
                if (drawerOpen) closeDrawer();
                watchIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        if (i < 0 || i >= list.size() || list.get(i).label == null) return "";
        HomeApps.Item it = list.get(i);
        if (it.kind == HomeApps.HOME_ASSISTANT && it.control != null && it.control.hasOnOff()) {
            return Ui.trf("{name}, {state}", "name", it.label, "state", it.control.on() ? Ui.tr("On") : Ui.tr("Off"));
        }
        return it.label;
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        if (Prefs.forecast().equals("days") && !wx.days.isEmpty()) {   // as the card: the hours until there are days
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.days.size() && i < 5; i++) {
                // "Monday: High 25°, low 18°; Tuesday: …": the whole day ("Sun" and "Sat" are words of their own), and
                // which number is the high (the sentence's own end dropped).
                String range = Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.dayMax.get(i)),
                        "min", HomeData.degrees(wx.dayMin.get(i))).replaceAll("[.。।]$", "");
                if (i > 0) list.append("; ");
                list.append(HomeData.weekdayName(wx.days.get(i))).append(": ").append(range);
            }
            s.append(' ').append(Ui.trf("Next days: {list}.", "list", list));
        } else if (!wx.hours.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = tabCount(); i < TABS; i++) topRects[i].setEmpty();   // a slot with no tab: nothing to hit
        for (int i = 0; i < tabCount(); i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            snap(topRects[i]);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[TABS + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        for (HomeApps.Item it : lightButtons) {
            Symbols.warm(getContext(), it.symbol, 27 * k);
            Symbols.warm(getContext(), it.symbol, 27 * k * 1.1f);
        }
        if (Prefs.statusIcons()) {   // at the sizes drawStatusIcon uses: drawing never scales a 150 px PNG down to 25
            for (String name : STATUS_SYMBOLS) {
                Symbols.warm(getContext(), name, Symbols.pointSizeForHeight(getContext(), name, L_STATUS * k * statusScale(name)));
            }
        }
        Symbols.warmWeather(getContext(), 40 * k);
        heroDirty = statusDirty = true;
        layoutCards();
    }

    /** The cards side by side: the one at cardIndex wide, the others tall, sliding as `cardOpen` moves. */
    private void layoutCards() {
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g, x = MARGIN * k - cardScroll.value * (tall + g);
        for (int i = 0; i < CARD_MAX; i++) {
            float w = tall + (wide - tall) * Ui.clamp(cardOpen[i].value);
            cardRects[i].set(x, top, x + w, top + ch);
            cardLines[i].set(cardRects[i]);
            snap(cardRects[i]);
            x += w + g;
        }
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the focused one. */
    private void updateCards() {
        cardSlots = Prefs.cards();
        if (searchOpen) {   // the row is search's: the tab's cards come back when it closes
            heroDirty = true;
            return;
        }
        if (!picksMode()) {
            // No picks on screen: no cards, and the plain gradient behind, or with the clock your photo if there is one.
            if (!cards.isEmpty()) cards = new ArrayList<>();
            if (continueMode()) {
                // TMDB titles found since (Recs notifies): their posters and the focused one's backdrop.
                findWatchPicks();
                loadWatchBackdrops();
                watchBackground();
                heroDirty = true;
                return;
            }
            boolean own = clockMode() && Photos.on(getContext());
            Backdrop b = own ? Photos.backdrop(getContext(), ownReady) : null;
            if (b != null || !own) showBackdrop(b);
            heroDirty = true;
            return;
        }
        if (bothMode()) rebuildWatchCards();
        List<Recs.Pick> list = tabList(tab);
        // The Continue Watching tab's list is made again only when something in it changed (rebuildWatchCards).
        if (watchTab() ? list != cards : !samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) zone = Z_SHELF;
            retarget(false);   // the wide card and the scroll follow the clamped index, whatever has the focus
        }
        // This tab's cards, then the first three of the others (what shows at first): switching tabs is quick, and
        // the images stay under Recs's cap in memory. Continue Watching can list 12: only the ones near the focus.
        if (watchTab()) loadWatchCards();
        else for (Recs.Pick p : list) recs.backdrop(p);
        for (int t = 0; t < tabCount(); t++) {
            if (t == tab) continue;
            List<Recs.Pick> other = tabList(t);
            for (int i = 0; i < Math.min(3, other.size()); i++) recs.backdrop(other.get(i));
        }
        if (musicTab()) {   // the cover's colours behind Now Playing, once they're made (updateMusic)
            if (musicBackdrop != null) showBackdrop(musicBackdrop);
            heroDirty = true;
            return;
        }
        // Picks bring their own background; without them (no TMDB key), your photo if there is one.
        boolean own = !picksOn() && Photos.on(getContext());
        Backdrop b = own ? Photos.backdrop(getContext(), ownReady)
                : cards.isEmpty() ? null : recs.backdrop(cards.get(Math.min(cardIndex, cards.size() - 1)));
        if (b != null || (cards.isEmpty() && !own)) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private List<Recs.Pick> tabList(int t) {
        String kind = tabKind(t);
        if (MUSIC_TAB.equals(kind)) return java.util.Collections.emptyList();   // Now Playing: no cards
        return WATCH_TAB.equals(kind) ? watchCards : recs.now(kind);
    }

    /** The Continue Watching tab's backdrops near the focus (the cards on screen and the next few). */
    private void loadWatchCards() {
        int from = Math.max(0, cardIndex - 2);
        for (int i = from; i < Math.min(watchCards.size(), from + Recs.WATCH_READY); i++) recs.backdrop(watchCards.get(i));
    }

    /**
     * One card per Watch Next entry, drawn as a pick: the title the app gives, its TMDB backdrop and poster when found
     * (else the app's own picture, drawLightCard), "app · episode · time left" when wide, the episode when narrow.
     * The list stays the same object while nothing in it changes, so updateCards doesn't move the focus.
     */
    private void rebuildWatchCards() {
        findWatchPicks();
        prepareWatchTexts();
        List<Recs.Pick> next = new ArrayList<>(watching.size());
        boolean same = watchCards.size() == watching.size();
        for (int i = 0; i < watching.size(); i++) {
            Recs.Pick found = i < watchPicks.length ? watchPicks[i] : null;
            Recs.Pick p = new Recs.Pick();
            p.kind = found == null ? WATCH_TAB : found.kind;
            p.id = found == null ? 0 : found.id;
            p.title = watchTitles[i];
            p.backdrop = found == null ? null : found.backdrop;
            p.poster = found == null ? null : found.poster;
            p.logo = found == null ? null : found.logo;
            p.meta = watchMetas[i];
            p.tag = watchTags[i];
            p.pkg = watching.get(i).pkg;
            p.detailed = true;
            if (same) {
                Recs.Pick old = watchCards.get(i);
                same = old.title.equals(p.title) && old.meta.equals(p.meta) && java.util.Objects.equals(old.tag, p.tag)
                        && java.util.Objects.equals(old.backdrop, p.backdrop) && java.util.Objects.equals(old.pkg, p.pkg);
            }
            next.add(p);
        }
        if (!same) watchCards = next;
    }

    // MARK: - Now Playing

    private boolean musicPlaying() {
        android.media.session.PlaybackState st = music == null ? null : music.getPlaybackState();
        return st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING;
    }

    /**
     * The music session changed (NowPlaying.Watch.music): Now Playing comes or goes as the first tab (the tab showing
     * stays the same; opened from the shelf, it opens on Now Playing), its texts, and its cover and the cover's colours
     * for the background, made off the main thread.
     */
    private void updateMusic() {
        android.media.session.MediaController m = playing.music;
        if (m != null && m.getPackageName().equals(musicClosed) && SystemClock.uptimeMillis() < musicClosedUntil) m = null;
        if (m == null && musicHeld != null) {
            // Paused here: it stays while the session is still there and not stopped (✕, or the app stopping it).
            android.media.session.PlaybackState st = musicHeld.getPlaybackState();
            int state = st == null ? android.media.session.PlaybackState.STATE_NONE : st.getState();
            if (state == android.media.session.PlaybackState.STATE_PAUSED) m = musicHeld;
            else musicHeld = null;
        }
        boolean had = musicShown();
        music = m;
        boolean has = musicShown();
        if (had != has) {
            if (has) {
                boolean inTabs = zone == Z_CARDS || zone == Z_TOP && topIndex < TABS;
                if (zone == Z_TOP && topIndex < TABS) topIndex++;
                tab = inTabs ? tab + 1 : 0;
            } else {
                if (zone == Z_MUSIC) zone = Z_SHELF;
                if (zone == Z_TOP && topIndex > 0 && topIndex < TABS) topIndex--;
                tab = Math.max(0, tab - 1);
            }
            cardIndex = 0;
            layoutLight();
            updateCards();
            retarget(false);
        }
        if (m == null) {
            heroDirty = true;
            return;
        }
        android.media.MediaMetadata md = m.getMetadata();
        if (md == null) return;
        String title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
        String artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        String album = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM);
        String pkg = m.getPackageName(), app = pkg;
        try {
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            app = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        musicPkg = pkg;
        musicTitle = title == null ? "" : title.trim();
        musicSub = (artist == null ? "" : artist.trim()) + (TextUtils.isEmpty(album) ? "" : "  ·  " + album.trim());
        musicEyebrow = Ui.trf("Playing on {app}", "app", app).toUpperCase(Ui.locale());
        musicOpen = Ui.trf("Open {app}", "app", app);
        heroDirty = true;
        // The picture's own look too: a new track's cover can come a moment after its title, in a second update.
        String artKey = pkg + "|" + musicTitle + "|" + artist + "|" + NowPlaying.artSignature(md);
        if (artKey.equals(musicArtKey)) return;
        musicArtKey = artKey;
        Bitmap art = NowPlaying.art(md);
        if (art == null) {
            musicArt = null;
            musicArtShader = null;
            musicBackdrop = null;
            if (musicTab()) showBackdrop(null);
            return;
        }
        final Bitmap src = art;
        final int size = Math.round(452 * k);
        LOADER.execute(() -> {
            try {
                // The cover square, at its size on screen, and the backdrop built from it (as a pick's).
                int side = Math.min(src.getWidth(), src.getHeight());
                Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
                Bitmap cover = Ui.downscale(square, size, size);
                if (cover == src) cover = src.copy(Bitmap.Config.ARGB_8888, false);
                Backdrop b = Backdrop.make(src.copy(Bitmap.Config.ARGB_8888, true), true);
                final Bitmap done = cover;
                handler.post(() -> {
                    if (!artKey.equals(musicArtKey)) return;   // the track changed meanwhile
                    musicArt = done;
                    musicArtShader = new BitmapShader(done, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    musicBackdrop = b;
                    if (musicTab()) showBackdrop(b);
                    heroDirty = true;
                    wake();
                });
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("now playing cover: " + e.getMessage());
            }
        });
    }

    /** OK on a control: previous, play/pause, next, open the app, or close it (force-stop, as Recent Apps does). */
    private void musicAction(int i) {
        android.media.session.MediaController m = music;
        if (m == null) return;
        click.open();
        android.media.session.MediaController.TransportControls t = m.getTransportControls();
        final String pkg = m.getPackageName();
        switch (i) {
            case 0: t.skipToPrevious(); break;
            case 1:
                if (musicPlaying()) {
                    musicHeld = m;   // stays on screen, paused, to be played again from here
                    t.pause();
                } else {
                    t.play();
                }
                break;
            case 2: t.skipToNext(); break;
            case 3: {
                HomeApps.Item it = new HomeApps.Item();
                it.kind = HomeApps.APP;
                it.pkg = pkg;
                it.key = pkg;
                it.label = pkg;
                HomeApps.open(activity, it, null);
                break;
            }
            case 4: {
                // Closed: the music stops and the tab goes now, not when the app gets round to saying so.
                musicHeld = null;
                musicClosed = pkg;
                musicClosedUntil = SystemClock.uptimeMillis() + 5000;
                t.stop();
                updateMusic();
                if (!HomeMode.mayStop(pkg)) break;   // the TV's own pieces are never force-stopped
                final Context app = getContext().getApplicationContext();
                new Thread(() -> {
                    try (Adb adb = Adb.connect(app, 10_000, null)) {
                        adb.shell("am force-stop " + pkg);
                    } catch (java.io.IOException | RuntimeException e) {
                        AppLog.w("close music app: " + e);
                    }
                }, "close-music").start();
                break;
            }
            default: break;
        }
        heroDirty = true;
        wake();
    }

    /**
     * Now Playing: the cover on the left (in the cards' place), and beside it on the background "Playing on <app>" with
     * the level bars, the title large, the artist and album, the progress, and the round controls.
     */
    /** Where the bars layer sits (TV px): around the eyebrow's bars at 600, 314 (drawMusic). */
    private static final float BARS_X = 590, BARS_Y = 280, BARS_W = 60, BARS_H = 44;

    /** Now Playing's eyebrow bars, in the top layer or (`own`) in their own small layer. */
    private void drawMusicBars(Canvas c, boolean own) {
        if (lMusicEyebrow == null) return;
        float cap = Ui.halfCap(lMusicEyebrow) * 2, eyebrowBase = 314 * k;
        NowPlaying.drawBars(c, 600 * k, eyebrowBase, cap, lMusicEyebrow, musicPlaying(), musicBars);
    }

    private void drawMusic(Canvas c) {
        float x0 = MARGIN * k, y0 = L_CARDS_TOP * k, side = 452 * k, r = 28 * k;
        tmp.set(x0, y0 + 24 * k, x0 + side, y0 + side + 24 * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.25f, 0.5f)));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        cell.set(x0, y0, x0 + side, y0 + side);
        if (musicArt != null) {
            roundImage(c, musicArt, musicArtShader, cell, r);
        } else {
            musicFill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, musicFill);
        }
        float tx = 600 * k, width = (CONTENT_R - 600) * k;
        boolean live = musicPlaying();
        float cap = Ui.halfCap(lMusicEyebrow) * 2, eyebrowBase = 314 * k;
        if (!barsOwn) drawMusicBars(c, false);
        c.drawText(musicEyebrow, tx + NowPlaying.barsWidth(cap) + cap * 0.7f, eyebrowBase, lMusicEyebrow);
        fitPaint.set(lMusicTitle);
        c.drawText(TextUtils.ellipsize(musicTitle, fitPaint, width, TextUtils.TruncateAt.END).toString(), tx - Ui.leftBearing(lMusicTitle, musicTitle), 418 * k, lMusicTitle);
        fitPaint.set(lMusicSub);
        c.drawText(TextUtils.ellipsize(musicSub, fitPaint, width, TextUtils.TruncateAt.END).toString(), tx, 488 * k, lMusicSub);
        // Progress: where the player said it was, moved on by the time since (at its speed) while it plays.
        android.media.session.PlaybackState st = music == null ? null : music.getPlaybackState();
        android.media.MediaMetadata md = music == null ? null : music.getMetadata();
        long duration = md == null ? 0 : md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION), position = 0;
        if (st != null) {
            position = st.getPosition();
            if (live) position += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        float barX = 602 * k, barY = 540 * k, barW = 900 * k, barH = 6 * k;
        if (duration > 0) {
            position = Math.max(0, Math.min(duration, position));
            musicBar.setColor(tone(0x29000000, 0x38FFFFFF));
            tmp.set(barX, barY, barX + barW, barY + barH);
            c.drawRoundRect(tmp, barH / 2, barH / 2, musicBar);
            musicBar.setColor(tone(L_INK, 0xFFFFFFFF));
            tmp.right = barX + Math.max(barH, barW * position / duration);
            c.drawRoundRect(tmp, barH / 2, barH / 2, musicBar);
            lMusicTime.setTextAlign(Paint.Align.LEFT);
            c.drawText(minutes(position), barX, barY + 40 * k, lMusicTime);
            lMusicTime.setTextAlign(Paint.Align.RIGHT);
            c.drawText(minutes(duration), barX + barW, barY + 40 * k, lMusicTime);
            lMusicTime.setTextAlign(Paint.Align.LEFT);
        }
        // The controls: previous, play/pause, next, open the app (a pill with its name), close it.
        float pillW = lMusicPill.measureText(musicOpen) + (28 + 30 + 14 + 28) * k;
        musicRects[0].set(602 * k, 612 * k, 692 * k, 702 * k);
        musicRects[1].set(716 * k, 600 * k, 828 * k, 712 * k);
        musicRects[2].set(852 * k, 612 * k, 942 * k, 702 * k);
        musicRects[3].set(1000 * k, 622 * k, 1000 * k + pillW, 692 * k);
        musicRects[4].set(musicRects[3].right + 20 * k, 622 * k, musicRects[3].right + 90 * k, 692 * k);
        for (int i = 0; i < 5; i++) {
            int icon0 = i == 0 ? 0 : i == 1 ? (live ? 2 : 1) : i + 1;   // previous, play or pause, next, open, close
            float f = Ui.clamp(musicFocus[i].value), grow = 1 + 0.12f * f;
            RectF b = musicRects[i];
            float cx = b.centerX(), cy = b.centerY(), hw = b.width() * grow / 2, hh = b.height() * grow / 2;
            tmp.set(cx - hw, cy - hh, cx + hw, cy + hh);
            float rr = hh;
            if (f > 0.01f) {
                cell.set(tmp);
                cell.offset(0, 12 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.35f * f));
                shadows.draw(c, cell, rr, SHADOW_BLUR * k, shadow);
            }
            musicFill.setColor(blend(tone(0x14000000, 0x24FFFFFF), 0xFFFFFFFF, f));
            c.drawRoundRect(tmp, rr, rr, musicFill);
            int ink = blend(tone(L_INK, 0xFFFFFFFF), L_INK, f);
            android.graphics.drawable.Drawable d = musicIcons[icon0];
            float icon = (i == 1 ? 56 : i == 3 ? 30 : 44) * k * grow;
            float ix = i == 3 ? tmp.left + 28 * k * grow + icon / 2 : cx;
            if (d != null) {
                d.setTint(ink);
                d.setBounds(Math.round(ix - icon / 2), Math.round(cy - icon / 2), Math.round(ix + icon / 2), Math.round(cy + icon / 2));
                d.draw(c);
            }
            if (i == 3) {
                lMusicPill.setColor(ink);
                lMusicPill.setTextSize(26 * k * grow);
                c.drawText(musicOpen, ix + icon / 2 + 14 * k * grow, cy + Ui.halfCap(lMusicPill), lMusicPill);
                lMusicPill.setTextSize(26 * k);
            }
        }
    }

    private static String minutes(long ms) {
        long s = ms / 1000;
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    /** Your photo's background is ready (or new photos came in): draw it. */
    private final Runnable ownReady = () -> {
        if (running) updatePicks();
    };

    // MARK: - Continue watching (Settings > Top of the screen)

    /** Picks need a TMDB key: without one, "picks" shows the clock, like "nothing". */
    private boolean picksMode() {
        return Prefs.picksShown(topMode) && picksOn();
    }

    /** The clock, the date and the weather: nothing at the top, or picks without a TMDB key. */
    private boolean clockMode() {
        return !picksMode() && !continueMode();
    }

    /** Continue Watching's own row: chosen alone, or chosen with the picks when there's no TMDB key for them. */
    private boolean continueMode() {
        return topMode.equals(Prefs.TOP_CONTINUE) || topMode.equals(Prefs.TOP_BOTH) && !picksOn();
    }

    /** Continue Watching and Picks: the picks' tabs, Continue Watching first. */
    private boolean bothMode() {
        return topMode.equals(Prefs.TOP_BOTH) && picksOn();
    }

    /** What the top shows now (from reload, off the main thread) and, for Continue Watching, its list. */
    private void setTopContent(String mode, List<WatchNext.Item> list, boolean allowed) {
        if (!hiddenNow.isEmpty()) {
            List<WatchNext.Item> shown = new ArrayList<>(list.size());
            for (WatchNext.Item it : list) if (!hiddenNow.contains(WatchNext.hideKey(it))) shown.add(it);
            list = shown;
        }
        boolean modeChanged = !mode.equals(topMode);
        boolean listChanged = !sameWatching(list, watching);
        // The permission (granted or taken back) and the first read change what an empty row says: redraw for those.
        boolean textChanged = allowed != watchAllowed || !watchLoaded && Prefs.watchShown(mode);
        watchAllowed = allowed;
        if (Prefs.watchShown(mode)) watchLoaded = true;
        if (!modeChanged && !listChanged) {
            if (textChanged) heroDirty = true;
            return;
        }
        topMode = mode;
        if (modeChanged) closeMenu(false);
        watching = list;
        watchTitles = null;
        findWatchPicks();
        watchIndex = Math.min(watchIndex, Math.max(0, list.size() - 1));
        boolean lost = zone == Z_WATCH && list.isEmpty() || !picksMode() && (zone == Z_CARDS || zone == Z_TOP && topIndex < TABS);
        if (modeChanged || lost) zone = Z_SHELF;
        if (modeChanged) layoutLight();   // the tabs' names depend on the mode
        if (!modeChanged && bothMode()) updateCards();   // the Continue Watching tab's cards
        if (modeChanged) {
            updateCards();
            if (bgTo == null && bgFadeAt == 0) neutralGlass();   // the plain background depends on the mode too
            sky.invalidate();   // the background's gradient depends on the mode
        }
        retarget(modeChanged);
        heroDirty = true;
    }

    private static boolean sameWatching(List<WatchNext.Item> a, List<WatchNext.Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            WatchNext.Item x = a.get(i), y = b.get(i);
            // The episode and the link too: "S2 E6" becoming "S2 E7" keeps the title, often the picture and a 0 position.
            if (!x.pkg.equals(y.pkg) || !x.title.equals(y.title) || x.position != y.position || x.duration != y.duration
                    || !java.util.Objects.equals(x.episode, y.episode) || !java.util.Objects.equals(x.intent, y.intent)
                    || !java.util.Objects.equals(x.picture(), y.picture())) return false;
        }
        return true;
    }

    /** Card i's place on screen (without its focus growth), scrolled with the row. */
    private void watchRect(int i, RectF out) {
        layoutWatch();
        out.set(watchRects[i]);
    }

    /** As layoutCards: narrow cards and one wide (the focused one), scrolled in whole narrow cards. */
    private void layoutWatch() {
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g, x = MARGIN * k - watchScroll.value * (tall + g);
        for (int i = 0; i < watchRects.length; i++) {
            float cw = Math.max(tall * 0.5f, tall + (wide - tall) * watchOpen[i].value);
            watchRects[i].set(x, top, x + cw, top + ch);
            snap(watchRects[i]);
            x += cw + g;
        }
    }

    /** Each card's TMDB title (null until found, or when TMDB has none: the app's picture then). */
    private void findWatchPicks() {
        Recs.Pick[] found = new Recs.Pick[watching.size()];
        List<Recs.Pick> shown = new ArrayList<>();
        for (int i = 0; i < found.length; i++) {
            WatchNext.Item it = watching.get(i);
            found[i] = recs.forWatching(it.title, it.episode != null);
            if (found[i] != null) shown.add(found[i]);
        }
        watchPicks = found;
        recs.setWatchShown(shown);
    }

    /** The backdrops (and posters) of the cards on screen and the next few, within Recs.WATCH_READY. */
    private void loadWatchBackdrops() {
        int from = Math.max(0, watchIndex - 2);
        for (int i = from; i < Math.min(watchPicks.length, from + Recs.WATCH_READY); i++) {
            if (watchPicks[i] != null) recs.backdrop(watchPicks[i]);
        }
    }

    /**
     * The focused card's backdrop behind everything, as with the picks; a card TMDB doesn't know shows the tint of its
     * own picture instead. True when the background changed.
     */
    private boolean watchBackground() {
        Backdrop b = null;
        if (!watching.isEmpty()) {
            int i = Math.min(watchIndex, watching.size() - 1);
            Recs.Pick p = i < watchPicks.length ? watchPicks[i] : null;
            if (p != null) {
                b = recs.backdrop(p);
                if (b == null) return false;   // still being built: Recs notifies, and this runs again
            }
        }
        if (b == bgTo) return false;
        showBackdrop(b);
        if (b == null && bgTo == null) updateTint();
        return true;
    }

    /** Each card's title and details line ("Netflix · S2 E5 · 23 min left"), cut to the card's width once per list. */
    private void prepareWatchTexts() {
        // Cut to the card's width when drawn, as the picks' (a card's width changes as it opens).
        int n = watching.size();
        watchTitles = new String[n];
        watchMetas = new String[n];
        watchTags = new String[n];
        watchDetails = new String[n];
        for (int i = 0; i < n; i++) {
            WatchNext.Item it = watching.get(i);
            watchTitles[i] = it.title;
            StringBuilder meta = new StringBuilder(it.app == null ? "" : it.app);
            if (it.episode != null) meta.append(meta.length() > 0 ? " · " : "").append(it.episode);
            String left = timeLeft(it);
            if (left != null) meta.append(meta.length() > 0 ? " · " : "").append(left);
            watchMetas[i] = meta.toString();
            StringBuilder details = new StringBuilder(it.episode == null ? "" : it.episode);
            if (left != null) details.append(details.length() > 0 ? " · " : "").append(left);
            watchDetails[i] = details.toString();
            watchTags[i] = it.episode != null ? it.episode : left;
        }
        watchTextsK = k;
        watchTextsGen = Ui.generation;
    }

    private static String timeLeft(WatchNext.Item it) {
        if (it.progress() < 0) return null;
        long min = Math.max(1, (it.duration - it.position) / 60_000);
        return min >= 60 ? Ui.trf("{h} h {m} min left", "h", min / 60, "m", min % 60) : Ui.trf("{n} min left", "n", min);
    }

    private void drawWatchRow(Canvas c) {
        if (watching.isEmpty()) {
            if (!watchLoaded) return;   // not read yet: saying "nothing" now could be wrong
            String text = watchAllowed ? Ui.tr("Nothing half-watched in your apps.")
                    : Ui.tr("Missing permission: pick Continue Watching again in Home Settings.");
            c.drawText(text, MARGIN * k, (L_CARDS_TOP + 60) * k, lCardMeta);
            return;
        }
        if (watchTitles == null || watchTextsK != k || watchTextsGen != Ui.generation) prepareWatchTexts();
        layoutWatch();
        int top = -1;
        for (int i = 0; i < watching.size(); i++) if (watchFocus[i].value > 0.02f && (top < 0 || watchFocus[i].value > watchFocus[top].value)) top = i;
        // Cards scrolled past the left margin slide out under this edge instead of leaving a sliver there (the first
        // card's focus growth and shadow still fit).
        c.save();
        c.clipRect(watchLeftEdge(), 0, w, h);
        for (int i = 0; i < watching.size(); i++) if (i != top) drawWatchCard(c, i);
        if (top >= 0) drawWatchCard(c, top);
        c.restore();
    }

    private float watchLeftEdge() {
        return (MARGIN - 32) * k;
    }

    /** `ink`, or as much nearer black (light backgrounds) or white (dark ones) as it takes for 4.5:1 on `bg`. */
    private static int readable(int ink, int bg) {
        float lb = luminance(bg);
        int target = lb > 0.18f ? 0xFF000000 : 0xFFFFFFFF;   // either one reaches 4.5:1 on its side of 0.18
        for (int i = 0; i < 10; i++) {
            int c = blend(ink, target, i / 10f);
            float lc = luminance(blend(bg, c | 0xFF000000, (c >>> 24) / 255f));
            if ((Math.max(lb, lc) + 0.05f) / (Math.min(lb, lc) + 0.05f) >= 4.5f) return c;
        }
        return target;
    }

    /** Relative luminance (WCAG) of an opaque colour. */
    private static float luminance(int c) {
        return 0.2126f * linear((c >> 16) & 255) + 0.7152f * linear((c >> 8) & 255) + 0.0722f * linear(c & 255);
    }

    private static float linear(int v) {
        float x = v / 255f;
        return x <= 0.04045f ? x / 12.92f : (float) Math.pow((x + 0.055f) / 1.055f, 2.4);
    }

    /**
     * A pick's card (same sizes, focus, shadow, sheen and rim): the TMDB poster while narrow and its backdrop as it
     * widens, or the app's own picture when TMDB doesn't know the title; the progress bar on the picture.
     */
    private void drawWatchCard(Canvas c, int i) {
        cell.set(watchRects[i]);
        if (cell.left > w || cell.right <= watchLeftEdge()) return;   // scrolled off screen
        WatchNext.Item it = watching.get(i);
        Recs.Pick pick = i < watchPicks.length ? watchPicks[i] : null;
        float open = Ui.clamp(watchOpen[i].value);
        float v = watchFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = cell.width(), bh = cell.height(), r0 = 18 * k, r = r0 * s;
        float cx = cell.centerX(), cy = cell.centerY() - 6 * k * v, textLeft = cell.left, textTop = cell.bottom;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        Backdrop b = pick == null ? null : recs.backdrop(pick);
        Recs.Poster poster = b == null ? null : recs.poster(pick);
        Bitmap img = b != null ? null : watchArt.get(it.pictureUri, it.pictureKey, it.bannerKey, it.pkg);
        if (b != null && poster != null) {   // as drawLightCard: the backdrop fades in over the poster as it widens
            float back = Ui.clamp((open - 0.1f) / 0.6f);
            if (back < 0.999f) coverImage(c, poster.bitmap, poster.shader, cell, r, 1);
            if (back > 0.001f) coverImage(c, b.wide, b.wideShader, cell, r, back);
        } else if (b != null) {
            coverImage(c, b.wide, b.wideShader, cell, r, 1);
        } else if (img != null) {
            fillImage(c, img, watchArt.shader(img), cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        float progress = it.progress();
        if (progress >= 0) {
            float inset = 16 * k * s, bar = 6 * k * s, bottom = cell.bottom - inset;
            fill.setShader(null);
            fill.setColor(0x73000000);
            tmp.set(cell.left + inset, bottom - bar, cell.right - inset, bottom);
            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
            fill.setColor(0xF2FFFFFF);
            tmp.right = tmp.left + Math.max(bar, tmp.width() * progress);
            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
        }
        if (f > 0.01f) {
            // Drawn at the card's resting size, scaled with it: the gradients are made once, not on every frame.
            if (watchSheen == null) {   // in a unit box, stretched to the card as it widens (nothing made per frame)
                watchSheen = new LinearGradient(-0.5f, -0.5f, 0, 0.5f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                watchRim = new LinearGradient(0, -0.5f, 0, 0.5f, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
            }
            imageMatrix.setScale(bw, bh);
            watchSheen.setLocalMatrix(imageMatrix);
            watchRim.setLocalMatrix(imageMatrix);
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(watchSheen);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(watchRim);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        drawCardLine(c, watchTitles[i], it.pkg, watchDetails[i], watchTags[i], textLeft, textLeft + watchRects[i].width(), textTop, open, -1);
    }

    /** Resumes it where you left it (the app's own link); if that link can't be used, the app itself. */
    private void openWatching(WatchNext.Item it) {
        NetflixWatch.lastApp = it.pkg;
        Intent link = it.intent == null ? null : linkIntent(it.intent, it.pkg);
        if (link != null && link.resolveActivity(activity.getPackageManager()) != null) {
            try {
                activity.startActivity(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("continue watching link: " + e.getMessage());
            }
        }
        if (!openApp(it)) showToast(Ui.trf("Couldn't open {app}.", "app", appName(it)));
    }

    /**
     * Another app wrote this link (#94's linkIntent, by @GizmoH2o): it only ever opens that app, never ours or a third
     * one, with no selector and no permissions handed along. Null if it can't be used.
     */
    private static Intent linkIntent(String uri, String pkg) {
        if (HomeMode.PKG.equals(pkg)) return null;
        Intent intent;
        try {
            intent = uri.startsWith("intent:") ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
        } catch (java.net.URISyntaxException | RuntimeException e) {   // a bad launchFlags= throws NumberFormatException
            AppLog.w("continue watching link: " + e.getMessage());
            return null;
        }
        if (intent.getComponent() != null && !intent.getComponent().getPackageName().equals(pkg)) return null;
        intent.setSelector(null);
        intent.setPackage(pkg);
        intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        return intent;
    }

    /** The item's app by name, for a toast (its package if the name is unknown). */
    private static String appName(WatchNext.Item it) {
        return it.app != null && !it.app.isEmpty() ? it.app : it.pkg;
    }

    /** The item's app, from its start (guarded like HomeApps.open: a missing or refusing app shows a toast). */
    private boolean openApp(WatchNext.Item it) {
        Intent launch = HomeApps.launchIntent(activity.getPackageManager(), it.pkg);
        if (launch == null) return false;
        try {
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + it.pkg + ": " + e.getMessage());
            return false;
        }
    }

    // MARK: - Continue Watching: the menu of a held card

    private boolean canHold() {
        if (sheetOpen || drawerOpen) return false;
        if (zone == Z_CARDS && watchTab()) return cardIndex < watching.size();
        return zone == Z_WATCH && continueMode() && watchIndex < watching.size();
    }

    /** The focused card's menu (held OK, or TalkBack's long click). */
    private void showMenu() {
        if (menuOpen || !canHold() || menuTitlePaint == null) return;
        menuItem = watching.get(zone == Z_CARDS ? cardIndex : watchIndex);
        float width = (MENU_W - 2 * MENU_PAD) * k;
        String title = menuItem.title + (menuItem.episode != null ? " · " + menuItem.episode : "");
        menuTitle = TextUtils.ellipsize(title, new TextPaint(menuTitlePaint), width, TextUtils.TruncateAt.END).toString();
        menuSub = menuItem.app == null ? "" : TextUtils.ellipsize(menuItem.app, new TextPaint(menuSubPaint), width,
                TextUtils.TruncateAt.END).toString();
        menuOptions[0] = Ui.tr("Hide from Continue Watching");
        menuOptions[1] = Ui.tr("Open the app");
        menuOptions[2] = Ui.tr("Cancel");
        layoutMenu();
        menuOpen = true;
        menuIndex = 0;
        menuPick.snap(0);
        menu.target = 1;
        menuLayer.setVisibility(VISIBLE);
        menuLayer.invalidate();
        wake();
        click.open();
        spoken.focusMoved();
    }

    /** The panel, centered on the screen: title, the app's name (if known) and the three options. */
    private void layoutMenu() {
        float rows = (MENU_PAD + 34 + (menuSub.isEmpty() ? 0 : 38) + 32 + 2 * MENU_STEP + MENU_ROW + MENU_INSET) * k;
        float left = (w - MENU_W * k) / 2, top = (h - rows) / 2;
        menuPanel.set(left, top, left + MENU_W * k, top + rows);
        menuOptionsTop = top + (MENU_PAD + 34 + (menuSub.isEmpty() ? 0 : 38) + 32) * k;
    }

    private void menuRow(int i, RectF out) {
        float y = menuOptionsTop + i * MENU_STEP * k;
        out.set(menuPanel.left + MENU_INSET * k, y, menuPanel.right - MENU_INSET * k, y + MENU_ROW * k);
    }

    private void closeMenu(boolean snap) {
        if (!menuOpen && !snap) return;
        boolean was = menuOpen;
        menuOpen = false;
        menu.target = 0;
        if (snap) {
            menu.snap(0);
            menuLayer.setVisibility(INVISIBLE);
        }
        menuLayer.invalidate();
        wake();
        if (was) spoken.focusMoved();
    }

    private boolean menuKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) moveMenu(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveMenu(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled() && spoken.keyClick()) pickMenu();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        closeMenu(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void moveMenu(int d) {
        int next = Math.max(0, Math.min(menuOptions.length - 1, menuIndex + d));
        if (next == menuIndex) return;
        menuIndex = next;
        menuPick.target = next;
        menuLayer.invalidate();
        wake();
        click.move();
        spoken.focusMoved();
    }

    private void pickMenu() {
        WatchNext.Item it = menuItem;
        int choice = menuIndex;
        closeMenu(false);
        if (it == null) return;
        if (choice == 0) {
            click.open();
            hideWatching(it);
        } else if (choice == 1) {
            click.open();
            if (openApp(it)) return;
            showToast(Ui.trf("Couldn't open {app}.", "app", appName(it)));
        } else {
            click.move();
        }
    }

    /** Remembered (Settings > Home Settings shows it again) and taken off the row now; the focus stays in place. */
    private void hideWatching(WatchNext.Item it) {
        String key = WatchNext.hideKey(it);
        Set<String> hidden = Prefs.watchHidden();
        hidden.add(key);
        Prefs.setWatchHidden(hidden);
        hiddenNow.add(key);
        List<WatchNext.Item> list = new ArrayList<>(watching.size());
        for (WatchNext.Item x : watching) if (!key.equals(WatchNext.hideKey(x))) list.add(x);
        watching = list;
        watchTitles = null;
        findWatchPicks();
        watchIndex = Math.min(watchIndex, Math.max(0, list.size() - 1));
        if (list.isEmpty() && zone == Z_WATCH) zone = Z_SHELF;
        if (bothMode()) updateCards();   // the Continue Watching tab loses that card too
        retarget(false);
        heroDirty = true;
        showToast(Ui.tr("Hidden. Settings > Home Settings shows them again."));
    }

    /** Only while it shows (its layer is invisible otherwise); texts were cut to size when it opened. */
    private void drawMenu(Canvas c) {
        float v = menu.value, a = Ui.clamp(v);
        if (a <= 0.004f) return;
        fill.setShader(null);
        fill.setColor(Ui.alpha(tone(0x38000000, 0x80000000), a));
        c.drawRect(screen, fill);
        float s = 0.92f + 0.08f * v, r = MENU_R * k;
        c.save();
        c.scale(s, s, menuPanel.centerX(), menuPanel.centerY());
        tmp.set(menuPanel);
        tmp.offset(0, 18 * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.5f) * a));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        fill.setColor(Ui.alpha(tone(0xFFFFFFFF, 0xFF2C2C2E), a));
        c.drawRoundRect(menuPanel, r, r, fill);
        border.setColor(Ui.alpha(tone(0x14000000, 0x1FFFFFFF), a));
        c.drawRoundRect(menuPanel, r, r, border);
        float x = menuPanel.left + MENU_PAD * k, y = menuPanel.top + (MENU_PAD + 34) * k;
        menuTitlePaint.setColor(Ui.alpha(tone(L_INK, WHITE), a));
        c.drawText(menuTitle, x, y, menuTitlePaint);
        if (!menuSub.isEmpty()) {
            menuSubPaint.setColor(Ui.alpha(tone(L_DIM, 0xFF98989D), a));
            c.drawText(menuSub, x, y + 38 * k, menuSubPaint);
        }
        // The focus pill slides between the options.
        float rowR = (MENU_R - MENU_INSET) * k, pick = menuPick.value;
        menuRow(0, tmp);
        tmp.offset(0, pick * MENU_STEP * k);
        fill.setColor(Ui.alpha(tone(L_INK, WHITE), a));
        c.drawRoundRect(tmp, rowR, rowR, fill);
        int ink = tone(L_INK, WHITE), onPill = tone(WHITE, L_INK);
        for (int i = 0; i < menuOptions.length; i++) {
            menuRow(i, tmp);
            float f = Ui.clamp(1 - Math.abs(pick - i));
            int base = i == 0 ? tone(0xFFD70015, 0xFFFF6961) : ink;   // hiding is the destructive one: red
            menuOptionPaint.setColor(Ui.alpha(blend(base, onPill, f), a));
            c.drawText(menuOptions[i], tmp.left + 28 * k, tmp.centerY() + Ui.halfCap(menuOptionPaint), menuOptionPaint);
        }
        c.restore();
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        // A tab opens at its first pick, its row at the start: at once, as sliding back would first show the new
        // tab's cards scrolled to where the last tab was.
        cardIndex = 0;
        aim(cardOpen, 0, true);
        cardScroll.snap(0);
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the focused card's photo, heavily blurred and almost white; without it, a light gradient. At night,
     * the same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (continueMode()) {
                drawTint(c);
            } else if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f && !continueMode()) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    /** The card's colour as a pale tint by day, a deep one at night, with a fixed shade toward the bottom. */
    private void drawTint(Canvas c) {
        fill.setShader(null);
        fill.setColor(tintColor(blend(tintFrom, tintTo, Ui.clamp(tintMix.value))));
        c.drawRect(screen, fill);
        if (tintShade == null) tintShade = new LinearGradient(0, 0, 0, h, 0x00000000, 0x33000000, Shader.TileMode.CLAMP);
        fill.setShader(tintShade);
        c.drawRect(screen, fill);
        fill.setShader(null);
    }

    /** The colour to ease toward: the focused card's (or the last one focused), once its picture is in. */
    private void updateTint() {
        if (!continueMode() || watching.isEmpty()) {
            if (tinted) {
                tinted = false;
                tintFrom = tintTo = TINT_WAIT;
                tintMix.snap(1);
                sky.invalidate();
                platter.invalidate();
                if (bgTo == null && bgFadeAt == 0) neutralGlass();
            }
            return;
        }
        WatchNext.Item it = watching.get(Math.min(watchIndex, watching.size() - 1));
        Integer color = watchArt.color(it.pictureKey, it.bannerKey);
        if (color == null || tinted && color == tintTo) return;
        // The first colour eases in from TINT_WAIT like any other change: no jump when the pictures arrive.
        tinted = true;
        tintFrom = blend(tintFrom, tintTo, Ui.clamp(tintMix.value));
        tintTo = color;
        tintMix.snap(0);
        tintMix.target = 1;
        easeGlass();
        sky.invalidate();
        platter.invalidate();
        drawerDirty = heroDirty = true;
        wake();
    }

    /**
     * The glass eases from the old tint to the new one with the background: both as images, blended by the doFrame
     * (Android 13+, where the glass reads them). Before that, the glass is a flat fill of glassLight(), which already
     * follows the tint: no images to fill on every move.
     */
    private void easeGlass() {
        if (bgTo != null || bgFadeAt != 0 || Build.VERSION.SDK_INT < 33) return;
        if (tintGlassPx == null) tintGlassPx = new int[Backdrop.GW * Backdrop.GH];
        plainImage(neutralGlassPx, Backdrop.GW, Backdrop.GH, tintFrom);
        plainImage(tintGlassPx, Backdrop.GW, Backdrop.GH, tintTo);
        scene.setGlassImages(neutralGlassPx, tintGlassPx);
        scene.setGlassMix(0);
        plainImage(neutralFrostPx, Backdrop.FW, Backdrop.FH, tintTo);   // the drawer's glass: closed while the row moves
        scene.setFrost(neutralFrostPx);
        nightPair = false;
        glassEasing = true;
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        drawLightTopNow(c);
        // Search's bar, over the clock: on this layer, as wide as the screen (the top buttons' layer is only their strip).
        if (searchShow.value > 0.001f) {
            for (int i = 0; i < lightButtons.size(); i++) if (lightButtons.get(i).kind == HomeApps.SEARCH) drawSearchBar(c, topRects[TABS + i]);
        }
        if (searchOpen) drawSearchNote(c);
        if (searchOpen && accentOpen) drawAccents(c);   // over the cards
    }

    private void drawLightTopNow(Canvas c) {
        applyTone();
        if (clockMode()) {
            titleRight = 0;
            drawClock(c);
            if (HomeData.coinCount() > 0 || playingTab != null) drawQuotes(c);
            return;
        }
        boolean searchUp = searchOpen || searchShow.value > 0.01f;
        if (bothMode()) {
            if (searchShow.value < 0.5f) drawGlance(c);   // under the bar as it grows
        } else {
            String hello = picksMode() ? Ui.tr("For You") : Ui.tr("Continue Watching");
            c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle);
            titleRight = MARGIN * k + lTitle.measureText(hello);
        }
        if (!picksMode()) {
            if (continueMode()) drawWatchRow(c);
            if (HomeData.coinCount() > 0 || playingTab != null) drawQuotes(c);
            return;
        }
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        if (searchOpen) drawSearchKeys(c);
        for (int i = 0; i < tabCount() && !searchUp; i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        if (musicTab()) {
            drawMusic(c);
            if (HomeData.coinCount() > 0 || playingTab != null) drawQuotes(c);
            return;
        }
        layoutCards();
        int top = -1;
        int slots = cardSlots;   // the others aren't drawn: at 3, no card peeks in at the right edge
        if (searchUp) slots = Math.min(cards.size(), CARD_MAX);   // all of search's results, in a row that scrolls
        else if (watchTab()) {   // as many as the Watch Next list has (the row scrolls), and no empty cards
            slots = cards.size();
            if (slots == 0 && watchLoaded) {
                String text = watchAllowed ? Ui.tr("Nothing half-watched in your apps.")
                        : Ui.tr("Missing permission: pick Continue Watching again in Home Settings.");
                c.drawText(text, MARGIN * k, (L_CARDS_TOP + 60) * k, lCardMeta);
            }
        }
        for (int i = 0; i < slots; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < slots; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (HomeData.coinCount() > 0 || playingTab != null) drawQuotes(c);
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    /**
     * Where the title goes in Continue Watching and Picks: the time, large, on the title's baseline; a hairline; then
     * the date over the weather (its symbol, now, and the day's high and low), the pair as tall as the time's digits.
     */
    /**
     * The clock corner. On Android 8 to 11 it's kept as a bitmap until the time, the date or the weather changes: it was
     * laid out again on every frame of a card move, ~9 ms of the UI thread's frame on a 2018 BRAVIA (its weather line
     * alone ~3 ms).
     */
    private void drawGlance(Canvas c) {
        if (!Ui.LEGACY) {
            drawGlanceNow(c);
            return;
        }
        HomeData.Weather wx = data.weather;
        String key = clockText + "|" + dateText + "|" + Prefs.weather() + "|" + wx.code + wx.isDay + "|" + data.weatherAt
                + "|" + lGlanceClock.getColor() + "|" + lGlanceDate.getColor() + "|" + lGlanceWeather.getColor() + "|" + Ui.language;
        if (!key.equals(glanceKey)) glanceRight = -1;   // measured again by the drawing below
        glanceRect.set(0, 0, Math.round(1240 * k), Math.round((L_TITLE_BASE + 18) * k));
        glanceSnapshot.draw(c, glanceRect, key, this::drawGlanceNow);
        if (glanceRight >= 0) titleRight = glanceRight;
        glanceKey = key;
    }

    private final Snapshot glanceSnapshot = new Snapshot();
    private final RectF glanceRect = new RectF();
    private String glanceKey = "";
    private float glanceRight = -1;

    private void drawGlanceNow(Canvas c) {
        float x = MARGIN * k, base = L_TITLE_BASE * k;
        c.drawText(clockText, x - Ui.leftBearing(lGlanceClock, clockText), base, lGlanceClock);
        float cap = Ui.halfCap(lGlanceClock) * 2;   // the digits' height
        x += lGlanceClock.measureText(clockText) + 30 * k;
        fill.setShader(null);
        fill.setColor(Ui.alpha(lGlanceWeather.getColor(), 0.45f));
        tmp.set(x, base - cap - 2 * k, x + 2 * k, base + 2 * k);
        c.drawRoundRect(tmp, k, k, fill);
        x += 2 * k + 28 * k;
        HomeData.Weather wx = data.weather;
        boolean weather = Prefs.weather() && !Double.isNaN(wx.temp);
        float right = x + lGlanceDate.measureText(dateText);
        if (!weather) {   // the date alone, centred on the digits
            c.drawText(dateText, x, base - cap / 2 + Ui.halfCap(lGlanceDate), lGlanceDate);
        } else {
            c.drawText(dateText, x, base - cap + 2 * Ui.halfCap(lGlanceDate), lGlanceDate);
            if (data.weatherAt != glanceWeatherAt || Prefs.version != glanceWeatherPrefs || !Ui.language.equals(glanceWeatherLanguage)) {
                glanceWeatherAt = data.weatherAt;
                glanceWeatherPrefs = Prefs.version;
                glanceWeatherLanguage = Ui.language;
                glanceWeather = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "  ·  "
                        + HomeData.highLow(wx, "  ");
            }
            float size = 28 * k;
            String symbol = Symbols.weather(wx.code, wx.isDay);
            float sw = Symbols.width(getContext(), symbol, size);
            Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lGlanceWeather), size, lGlanceWeather.getColor());
            c.drawText(glanceWeather, x + sw + 12 * k, base, lGlanceWeather);
            right = Math.max(right, x + sw + 12 * k + lGlanceWeather.measureText(glanceWeather));
        }
        titleRight = right;
        glanceRight = right;
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        if (base.right < 0 || base.left > getWidth()) return;   // slid off screen
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : b.wide;
        Recs.Poster poster = recs.poster(p);
        float open = Ui.clamp(cardOpen[i].value);
        float shown = 1;
        grayImage = p != null && p.notOn;   // search: a title none of your apps has, in black and white
        if (poster != null) {   // narrow: the poster; as the card widens the backdrop fades in over it
            float back = img == null ? 0 : Ui.clamp((open - 0.1f) / 0.6f);
            if (back < 0.999f) coverImage(c, poster.bitmap, poster.shader, cell, r, shown);   // not once the backdrop covers it
            if (back > 0.001f) coverImage(c, img, b.wideShader, cell, r, back * shown);
        } else if (img != null) {
            coverImage(c, img, b.wideShader, cell, r, shown);
        } else {
            // Continue Watching: a title TMDB doesn't know (or whose backdrop is still coming) shows the app's picture.
            WatchNext.Item it = watchTab() && p != null && i < watching.size() ? watching.get(i) : null;
            Bitmap art = it == null || p.backdrop != null ? null : watchArt.get(it.pictureUri, it.pictureKey, it.bannerKey, it.pkg);
            if (art != null) {
                fillImage(c, art, watchArt.shader(art), cell, r);
            } else {
                fill.setShader(null);
                fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
                c.drawRoundRect(cell, r, r, fill);
                if (p != null && p.backdrop != null) {   // its picture and logo are on their way: a small spinning ring
                    float sr = 22 * k * s, turn = (SystemClock.uptimeMillis() % 1000) / 1000f * 360;
                    cardSpinner.setStrokeWidth(4 * k * s);
                    cardSpinner.setColor(tone(0x66000000, 0x99FFFFFF));
                    tmp.set(cell.centerX() - sr, cell.centerY() - sr, cell.centerX() + sr, cell.centerY() + sr);
                    c.drawArc(tmp, turn, 270, false, cardSpinner);
                    cardsLoading = true;
                }
            }
        }
        grayImage = false;
        if (p != null && p.notOn) drawNotOnPill(c, s);
        boolean choosing = searchOpen && searchZone == SZ_CHOOSE && i == cardIndex && p != null;
        if (p != null && p.logo != null && open > 0.02f && !choosing) drawCardLogo(c, p, open, r);   // every tab: the opened card's logo
        if (choosing) drawSearchChoice(c, p, r);
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
            }
            if (lightSheens[i] == null) {   // in a unit box: the matrix below stretches them to the card as it widens
                lightSheens[i] = new LinearGradient(-0.5f, -0.5f, 0, 0.5f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -0.5f, 0, 0.5f, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
            }
            imageMatrix.setScale(bw, bh);
            lightSheens[i].setLocalMatrix(imageMatrix);
            lightRims[i].setLocalMatrix(imageMatrix);
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            // The soft sheen only where the GPU has room: on Android 8 to 11 the card's whole area filled again with a
            // gradient on every frame of a move pushed a 2018 BRAVIA's frames from ~26 to ~57 ms. A plain rim there.
            if (!Ui.LEGACY) {
                sheen.setShader(lightSheens[i]);
                sheen.setAlpha(alpha);
                c.drawRoundRect(tmp, r0, r0, sheen);
                sheen.setAlpha(255);
                rim.setShader(lightRims[i]);
                rim.setAlpha(alpha);
            } else {
                rim.setShader(null);
                rim.setColor(Ui.alpha(0xFFFFFFFF, 0.75f * f));
            }
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (watchTab() && i < watching.size()) drawProgress(c, watching.get(i), s, r, open);   // over the focus rim
        // The cards peeking in at the sides show only their image: their text would start off screen.
        if (p == null || base.left < 0 || base.left > (CONTENT_R - 100) * k) return;
        if (watchTab() && i < watching.size() && watchDetails != null && i < watchDetails.length) {
            WatchNext.Item it = watching.get(i);
            drawCardLine(c, watchTitles[i], it.pkg, watchDetails[i], watchTags[i], cardLines[i].left, cardLines[i].right, base.bottom, open, -1);
        } else {
            // Explore: the service it's on as its icon, so the details on the right leave the service's name out.
            String pkg = p.servicePkg != null ? p.servicePkg : p.pkg, meta = p.meta;
            if (p.appLabel != null && pkg != null && meta != null && meta.endsWith(" · " + p.appLabel)) meta = meta.substring(0, meta.length() - p.appLabel.length() - 3);
            else if (p.appLabel != null && p.appLabel.equals(meta)) meta = "";
            lineApps = searchOpen ? p.apps : null;   // search: every app that has it, stacked
            drawCardLine(c, p.title, pkg, scoredMeta(meta, p.audience), p.tag, cardLines[i].left, cardLines[i].right, base.bottom, open, p.audience);
            lineApps = null;
        }
    }

    /**
     * How far into it you are: a thin rim on the card's edge, and a straight bar centred on its bottom edge, between the
     * corners (where that edge is straight), half over the picture and half below it: a dark track, and the part
     * watched in the app's colour. `cell` is the card, `s` its focus growth, `r` its corner radius, `open` how far it's
     * opened: the bar goes from 3 px on a narrow card to 4 px on the opened one, growing with it.
     */
    private void drawProgress(Canvas c, WatchNext.Item it, float s, float r, float open) {
        float progress = it.progress();
        if (progress < 0) return;
        progressRim.setStrokeWidth(2 * k * s);
        progressRim.setColor(tone(0x33000000, 0x40FFFFFF));
        c.drawRoundRect(cell, r, r, progressRim);
        float bar = (3 + open) * k * s, from = cell.left + r, len = cell.width() - 2 * r, y = cell.bottom - bar / 2;
        if (len <= 0) return;
        fill.setShader(null);
        fill.setColor(0xD9141619);
        c.drawRect(from, y, from + len, y + bar, fill);
        int brand = SwitcherActivity.appColor(it.pkg);
        fill.setColor(brand != 0 ? brandBar(brand) : tone(L_INK, 0xFFFFFFFF));
        c.drawRect(from, y, from + Math.max(bar, len * Math.min(1, progress)), y + bar, fill);
    }

    /**
     * The title's logo on its opened card (every tab): a soft shade up from the bottom of the picture and the logo
     * in its lower left, fading in as the card widens (`open`), so the picture says which show it is.
     */
    private void drawCardLogo(Canvas c, Recs.Pick p, float open, float r) {
        Bitmap logo = recs.logo(p);
        if (logo == null) return;
        if (cardShade == null) {   // in a unit box, stretched to the card as it grows (nothing made per frame)
            cardShade = new LinearGradient(0, 0, 0, 1, new int[] {0x00000000, 0x00000000, 0xB3000000},
                    new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        }
        imageMatrix.setScale(1, cell.height());
        cardShade.setLocalMatrix(imageMatrix);
        int alpha = Math.round(255 * Ui.clamp((open - 0.3f) / 0.6f));
        if (alpha <= 0) return;
        c.save();
        c.translate(cell.left, cell.top);
        fill.setShader(cardShade);
        fill.setAlpha(alpha);
        c.drawRoundRect(0, 0, cell.width(), cell.height(), r, r, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        c.restore();
        // At most 300 × 105 (TV px), keeping its shape; its lower edge 37 px above the picture's.
        float maxW = 300 * k, maxH = 105 * k, sc = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
        float lw = logo.getWidth() * sc, lh = logo.getHeight() * sc, lx = cell.left + 44 * k, ly = cell.bottom - 37 * k - lh;
        tmp.set(lx, ly, lx + lw, ly + lh);
        bitmapPaint.setAlpha(alpha);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setAlpha(255);
    }

    /**
     * The app's colour for its progress line: lifted a little at night where a dark brand (Apple TV's near-black)
     * would vanish on the dark rim.
     */
    private int brandBar(int brand) {
        float lum = (0.2126f * android.graphics.Color.red(brand) + 0.7152f * android.graphics.Color.green(brand) + 0.0722f * android.graphics.Color.blue(brand)) / 255f;
        if (lum < 0.18f) return tone(0xFF1C1C1E, 0xFFFFFFFF);
        return 0xFF000000 | brand;
    }

    private static final float LINE_ICON = 44;   // the app's icon on an opened card (TV px); 36 on a narrow one

    /**
     * A card's line under the picture: the app's icon (when it has one) and the title on the left, the details on the
     * right edge (`wide` on the opened card: episode and time left, or genre and length; `narrow` on a narrow one),
     * cross-fading as the card widens.
     */
    // MARK: - Search
    // The Search button (a top button): a light bar grows out of it across the top row, letters line up under it, and
    // IMDb's titles come in as Explore's cards as you type, the ones on your apps first, each with every app that has
    // it. OK on a card opens the choice of app inside the card; OK there opens the title in it.

    private boolean searchOpen, searching, searchWasMoving;
    // What the row says when it has no cards for what was typed: "No results for …" or that search couldn't reach
    // IMDb (made once per answer, not per frame); null while there's nothing to say.
    private String searchNote, searchNoteSub;
    private int keyMode;   // KM_LETTERS, KM_NUMBERS, KM_SYMBOLS (Play/Pause goes round them, as on Apple TV)
    private static final int KM_LETTERS = 0, KM_NUMBERS = 1, KM_SYMBOLS = 2;
    // Holding OK on a letter: its accented forms above the keys (← → and OK pick one, Back leaves them).
    private boolean accentOpen, accentHeld;
    private int accentIndex;
    private static final java.util.Map<String, String> ACCENTS = new java.util.HashMap<>();
    static {
        ACCENTS.put("a", "àáâäãåæ");
        ACCENTS.put("c", "ç");
        ACCENTS.put("e", "èéêë");
        ACCENTS.put("i", "ìíîï");
        ACCENTS.put("n", "ñ");
        ACCENTS.put("o", "òóôöõøœ");
        ACCENTS.put("s", "ß");
        ACCENTS.put("u", "ùúûü");
        ACCENTS.put("y", "ýÿ");
    }
    private final Runnable holdAccent = this::openAccents;
    private int searchZone, keyIndex, chooseIndex, searchGen;
    private static final int SZ_KEYS = 0, SZ_CARDS = 1, SZ_CHOOSE = 2, SZ_BAR = 3;
    /** The bar's buttons at its right end: the microphone (where the TV can take voice input) and close. */
    private static final int BAR_MIC = 0, BAR_CLOSE = 1;
    private int barIndex = BAR_CLOSE;
    private Boolean canListen;   // the TV has a voice input (Google's): read once
    static final int VOICE = 41;
    private final StringBuilder query = new StringBuilder();
    private static final int MAX_QUERY = 60;   // the longest titles, and still inside the bar
    private final Spring searchShow = new Spring(0.34f, 0.9f, 0);
    private List<Recs.Pick> found = new ArrayList<>();
    private List<String[]> lineApps;   // drawCardLine: the apps to stack after the first (search's cards)
    private Paint lKey, lKeyOn, lKeySmall, lBar, lBarHint, lChoice, lChoiceNo, lChoiceSub, lPill, lNote, lNoteSub;
    private final android.graphics.Path keyPath = new android.graphics.Path();
    private static final java.util.concurrent.ExecutorService SEARCH = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ExecutorService SCORES = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final String SPACE = " ", DIGITS = "123", LETTERS = "abc", SYMBOLS = "#+=", ERASE = "⌫";
    private static final String[] KEYS_ABC = "a b c d e f g h i j k l m n o p q r s t u v w x y z".split(" ");
    private static final String[] KEYS_123 = "1 2 3 4 5 6 7 8 9 0".split(" ");
    private static final String[] KEYS_SYM = "& ' - : ! ? . , ( ) / +".split(" ");
    private final Runnable searchNow = this::runSearch;

    /** This mode's keys, then space, the keys to the other two modes, and erase. */
    private String[] searchKeys() {
        String[] base = keyMode == KM_NUMBERS ? KEYS_123 : keyMode == KM_SYMBOLS ? KEYS_SYM : KEYS_ABC;
        String[] out = java.util.Arrays.copyOf(base, base.length + 4);
        out[base.length] = SPACE;
        out[base.length + 1] = keyMode == KM_LETTERS ? DIGITS : LETTERS;
        out[base.length + 2] = keyMode == KM_SYMBOLS ? DIGITS : SYMBOLS;
        out[base.length + 3] = ERASE;
        return out;
    }

    /** To another keyboard mode, the focus staying where it was (on the key that brings it back, for a mode key). */
    private void keyMode(int mode) {
        keyMode = mode;
        keyIndex = Math.min(keyIndex, searchKeys().length - 1);
        accentOpen = false;
        heroDirty = true;
    }

    /** OK held on a letter that has accented forms: they open above it. */
    private void openAccents() {
        accentHeld = true;
        String key = keyIndex < searchKeys().length ? searchKeys()[keyIndex] : "";
        if (!ACCENTS.containsKey(key)) {
            accentHeld = false;   // nothing to offer: the release types it as usual
            return;
        }
        accentOpen = true;
        accentIndex = 0;
        heroDirty = true;
        click.move();
        wake();
    }

    private void openSearch() {
        searchOpen = true;
        searchShow.target = 1;
        searchZone = SZ_KEYS;
        zone = Z_TOP;   // the Search button keeps the focus, under the bar
        cards = found;
        cardIndex = 0;
        heroDirty = statusDirty = true;
        retarget(false);
        spoken.readAgain();
        wake();
    }

    private void closeSearch(boolean animated) {
        if (!searchOpen) return;
        stopHearing();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        searchOpen = false;
        searching = false;
        searchGen++;
        handler.removeCallbacks(searchNow);
        searchShow.target = 0;
        if (!animated) searchShow.snap(0);
        // The next opening starts over: nothing typed, no results, the letters from the start.
        query.setLength(0);
        found = new ArrayList<>();
        searchNote = searchNoteSub = null;
        keyMode = KM_LETTERS;
        accentOpen = false;
        handler.removeCallbacks(holdAccent);
        keyIndex = 0;
        chooseIndex = 0;
        barIndex = BAR_CLOSE;
        searchZone = SZ_KEYS;
        zone = Z_TOP;
        cardIndex = 0;
        cards = new ArrayList<>();
        updateCards();   // the tab's own cards again
        heroDirty = statusDirty = true;
        retarget(!animated);
        wake();
    }

    /** The remote (and a keyboard's letters) while search is open; false: not search's (the volume, Home…). */
    private boolean searchKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (hearing) {   // listening: OK ends it (and searches), Back cancels it; the arrows wait
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (!down && !e.isCanceled()) {
                    stopHearing();
                    click.move();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (down && e.getRepeatCount() == 0) recognizer.stopListening();
                return true;
            }
            if (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            stopHearing();   // typing instead
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) searchMove(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) searchMove(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (down) searchMove(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) searchMove(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (searchZone == SZ_KEYS && accentOpen) {
                    if (down && e.getRepeatCount() == 0) {
                        type(ACCENTS.get(searchKeys()[keyIndex]).charAt(accentIndex));
                        accentOpen = false;
                        heroDirty = true;
                        click.move();
                    }
                } else if (searchZone == SZ_KEYS) {   // a letter on the release: held, it shows its accents instead
                    if (down) {
                        if (e.getRepeatCount() == 0 && !e.isLongPress()) {
                            accentHeld = false;
                            handler.postDelayed(holdAccent, 450);
                        } else if (!accentHeld) {   // the remote says it's held (or repeats it): no need to wait
                            handler.removeCallbacks(holdAccent);
                            openAccents();
                        }
                    } else {
                        handler.removeCallbacks(holdAccent);
                        if (!accentHeld) searchOk();
                        accentHeld = false;
                    }
                } else if (down && e.getRepeatCount() == 0) {
                    searchOk();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && e.getRepeatCount() == 0) {   // as on Apple TV: letters, numbers, symbols, round again
                    if (searchZone != SZ_KEYS) toKeys();
                    keyMode((keyMode + 1) % 3);
                    click.move();
                    wake();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down && !e.isCanceled()) searchBack();
                return true;
            case KeyEvent.KEYCODE_DEL:
                if (down) erase();
                return true;
            default:
                int ch = e.getUnicodeChar();
                if (ch >= 32 && (Character.isLetterOrDigit(ch) || " &'-:!?.,()/+".indexOf(ch) >= 0)) {   // a keyboard, or the remote's digits
                    if (down) {
                        if (searchZone != SZ_KEYS) toKeys();
                        type((char) Character.toLowerCase(ch));
                    }
                    return true;
                }
                return false;
        }
    }

    private void toKeys() {
        searchZone = SZ_KEYS;
        zone = Z_TOP;
    }

    private void searchMove(int dx, int dy) {
        if (accentOpen) {
            int n = ACCENTS.get(searchKeys()[keyIndex]).length();
            if (dx != 0) {
                int next = Math.max(0, Math.min(n - 1, accentIndex + dx));
                if (next == accentIndex) return;
                accentIndex = next;
            } else {
                accentOpen = false;   // up or down leaves them
            }
            heroDirty = true;
            click.move();
            return;
        }
        switch (searchZone) {
            case SZ_KEYS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(searchKeys().length - 1, keyIndex + dx));
                    if (next == keyIndex) return;
                    keyIndex = next;
                } else if (dy > 0 && !found.isEmpty()) {
                    searchZone = SZ_CARDS;
                    zone = Z_CARDS;
                    score();
                } else if (dy < 0) {
                    searchZone = SZ_BAR;
                    if (barIndex == BAR_MIC && !listens()) barIndex = BAR_CLOSE;
                } else {
                    return;
                }
                break;
            case SZ_BAR:
                if (dx != 0) {
                    int next = Math.max(listens() ? BAR_MIC : BAR_CLOSE, Math.min(BAR_CLOSE, barIndex + dx));
                    if (next == barIndex) return;
                    barIndex = next;
                } else if (dy > 0) {
                    toKeys();
                } else {
                    return;
                }
                break;
            case SZ_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return;
                    cardIndex = next;
                    score();
                } else if (dy < 0) {
                    toKeys();
                } else {
                    return;
                }
                break;
            default:   // the choice inside the card
                Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
                int n = p == null || p.apps == null ? 0 : p.apps.size();
                if (dx != 0 && n > 0) {
                    int next = Math.max(0, Math.min(n - 1, chooseIndex + dx));
                    if (next == chooseIndex) return;
                    chooseIndex = next;
                } else if (dy < 0) {
                    searchZone = SZ_CARDS;
                } else {
                    return;
                }
        }
        heroDirty = statusDirty = true;
        retarget(false);
        click.move();
    }

    private void searchOk() {
        if (searchZone == SZ_BAR) {
            if (barIndex == BAR_CLOSE) {
                closeSearch(true);
                click.move();
            } else {
                listen();
            }
            return;
        }
        if (searchZone == SZ_KEYS) {
            String key = searchKeys()[keyIndex];
            if (key.equals(ERASE)) {
                erase();
            } else if (key.equals(DIGITS) || key.equals(LETTERS) || key.equals(SYMBOLS)) {
                keyMode(key.equals(DIGITS) ? KM_NUMBERS : key.equals(SYMBOLS) ? KM_SYMBOLS : KM_LETTERS);
            } else {
                type(key.charAt(0));
            }
            click.move();
            return;
        }
        Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
        if (p == null) return;
        if (searchZone == SZ_CARDS) {   // the choice opens inside the card
            searchZone = SZ_CHOOSE;
            chooseIndex = 0;
            heroDirty = true;
            click.move();
            return;
        }
        if (p.notOn || p.apps == null || chooseIndex >= p.apps.size()) return;
        click.open();
        final String[] app = p.apps.get(chooseIndex);
        final RectF r = new RectF(cardRects[cardIndex]);
        SEARCH.execute(() -> {   // its own page in that app when Watchmode knows it, else the app's home
            String link = recs.searchLink(p, app[0], app[1]);
            post(() -> HomeApps.open(activity, HomeApps.app(app[1], app[2], link), this,
                    Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height())));
        });
    }

    private void searchBack() {
        if (accentOpen) {
            accentOpen = false;
            heroDirty = true;
            click.move();
            return;
        }
        if (searchZone == SZ_BAR) toKeys();
        else if (searchZone == SZ_CHOOSE) searchZone = SZ_CARDS;
        else if (searchZone == SZ_CARDS) toKeys();
        else {
            closeSearch(true);
            click.move();
            return;
        }
        heroDirty = statusDirty = true;
        retarget(false);
        click.move();
    }

    /** The TV can take voice input (Google's voice search on Android TV and Google TV). */
    private boolean listens() {
        if (canListen == null) {
            canListen = !getContext().getPackageManager().queryIntentActivities(
                    new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isEmpty();
        }
        return canListen;
    }

    /**
     * The microphone: the bar itself listens, through the TV's speech recognition service (Google's, the one the
     * remote's mic uses), with what it hears written into the bar as it comes and searched when you stop. Android asks
     * once to allow the microphone; refused, or without that service, the TV's own voice screen does it instead.
     */
    private void listen() {
        if (hearing) {   // OK on the microphone again: that's all, search what it heard
            recognizer.stopListening();
            return;
        }
        Context ctx = getContext();
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
            listenWithTv();
        } else if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            hear();
        } else {
            activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, VOICE);
        }
    }

    /** Android's answer to the microphone permission (HomeActivity). */
    void onMicAllowed(boolean allowed) {
        if (!searchOpen) return;
        if (allowed) hear();
        else listenWithTv();
    }

    private android.speech.SpeechRecognizer recognizer;
    private boolean hearing;             // the bar is listening
    private float hearLevel, hearShown;  // the voice's loudness (0 to 1), and as the microphone's ring shows it
    private String beforeVoice = "";     // what was typed before: back when nothing was heard

    private void hear() {
        if (recognizer == null) {
            recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(voiceListener);
        }
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        beforeVoice = query.toString();
        handler.removeCallbacks(searchNow);
        hearing = true;
        hearLevel = hearShown = 0;
        searchZone = SZ_BAR;
        barIndex = BAR_MIC;
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            AppLog.w("voice: " + e.getMessage());
            hearing = false;
            listenWithTv();
        }
        click.open();
        heroDirty = true;
        wake();
    }

    /** Back while listening, or search closing: stop, and leave what was typed before. */
    private void stopHearing() {
        if (!hearing) return;
        hearing = false;
        if (recognizer != null) recognizer.cancel();
        setQuery(beforeVoice);
        heroDirty = true;
    }

    private void setQuery(String text) {
        String t = text.toLowerCase(Ui.locale());
        query.setLength(0);
        query.append(t, 0, Math.min(MAX_QUERY, t.length()));
    }

    private static String heard(android.os.Bundle b) {
        java.util.ArrayList<String> list = b == null ? null : b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() || list.get(0) == null ? null : list.get(0).trim();
    }

    private final android.speech.RecognitionListener voiceListener = new android.speech.RecognitionListener() {
        @Override public void onReadyForSpeech(android.os.Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float db) {
            hearLevel = Ui.clamp((db + 2) / 12f);
        }
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {
            hearLevel = 0;
        }
        @Override public void onPartialResults(android.os.Bundle b) {
            String s = heard(b);
            if (!hearing || s == null || s.isEmpty()) return;
            setQuery(s);   // written as it's heard; searched once it's all heard
            heroDirty = true;
        }
        @Override public void onResults(android.os.Bundle b) {
            if (!hearing) return;
            hearing = false;
            String s = heard(b);
            if (s != null && !s.isEmpty()) setQuery(s);
            done();
        }
        @Override public void onError(int error) {
            if (!hearing) return;
            hearing = false;
            AppLog.w("voice error " + error);
            if (error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                setQuery(beforeVoice);
                listenWithTv();
            }
            done();   // what it heard so far, if anything
        }
        @Override public void onEvent(int type, android.os.Bundle params) {}

        private void done() {
            toKeys();
            if (!query.toString().equals(beforeVoice)) runSearch();
            heroDirty = statusDirty = true;
            retarget(false);
            wake();
        }
    };

    /** The TV's own voice screen (Google's), for a result: where the bar can't listen itself. */
    private void listenWithTv() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, Ui.tr("Search movies and shows"))
                .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            activity.startActivityForResult(i, VOICE);
            click.open();
        } catch (RuntimeException e) {
            AppLog.w("voice: " + e.getMessage());
            canListen = false;
            barIndex = BAR_CLOSE;
            heroDirty = true;
        }
    }

    /** What the voice input heard (null: nothing, or cancelled): it replaces what was typed. */
    void onVoice(Intent data) {
        java.util.ArrayList<String> heard = data == null ? null : data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (!searchOpen || heard == null || heard.isEmpty() || heard.get(0).trim().isEmpty()) return;
        setQuery(heard.get(0).trim());
        toKeys();
        handler.removeCallbacks(searchNow);
        runSearch();
        heroDirty = true;
        wake();
    }

    private void type(char ch) {
        if (query.length() >= MAX_QUERY || (ch == ' ' && (query.length() == 0 || query.charAt(query.length() - 1) == ' '))) return;
        query.append(ch);
        searchSoon();
    }

    private void erase() {
        if (query.length() == 0) return;
        query.setLength(query.length() - 1);
        searchSoon();
    }

    /** A moment after the last letter: each letter doesn't start its own search. */
    private void searchSoon() {
        heroDirty = true;   // the bar's text
        handler.removeCallbacks(searchNow);
        handler.postDelayed(searchNow, 350);
        wake();
    }

    private void runSearch() {
        final String q = query.toString().trim();
        final int gen = ++searchGen;
        if (q.isEmpty()) {
            searching = false;
            searchNote = searchNoteSub = null;
            found = new ArrayList<>();
            cards = found;
            cardIndex = 0;
            heroDirty = statusDirty = true;
            retarget(false);
            return;
        }
        searching = true;
        wake();
        SEARCH.execute(() -> {
            List<Recs.Pick> list = null;
            try {
                list = recs.search(q);
            } catch (Exception e) {
                AppLog.w("search: " + e.getMessage());
            }
            final List<Recs.Pick> got = list;
            post(() -> {
                if (gen != searchGen || !searchOpen) return;
                searching = false;
                // A failed search clears the last one's cards too: they were for other letters.
                found = got != null ? got : new ArrayList<>();
                cards = found;
                cardIndex = 0;
                if (found.isEmpty()) {
                    toKeys();
                    searchNote = got != null ? Ui.trf("No results for “{query}”", "query", q) : Ui.tr("Couldn't search");
                    searchNoteSub = got != null ? Ui.tr("Check the spelling, or try fewer letters") : Ui.tr("Check the TV's internet connection");
                } else {
                    searchNote = searchNoteSub = null;
                    if (searchZone != SZ_KEYS) searchZone = SZ_CARDS;
                    score();
                }
                heroDirty = statusDirty = true;
                retarget(false);
                wake();
            });
        });
    }

    /** The opened card's audience score, looked up once it's the one in front. */
    private void score() {
        if (cardIndex >= cards.size()) return;
        final Recs.Pick p = cards.get(cardIndex);
        if (p.scoreAsked || p.notOn) return;
        SCORES.execute(() -> {
            recs.searchScore(p);
            post(() -> {
                heroDirty = true;
                wake();
            });
        });
    }

    private void searchPaints() {
        if (lKey != null && lKey.getTextSize() == 34 * k) return;
        lKey = Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF);
        lKey.setTextAlign(Paint.Align.CENTER);
        lKeyOn = new Paint(lKey);
        lKeyOn.setColor(0xFF16181D);
        lKeySmall = Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF);
        lKeySmall.setTextAlign(Paint.Align.CENTER);
        lBar = Ui.text(Ui.medium, 36 * k, 0xFF16181D);
        lBarHint = Ui.text(Ui.medium, 34 * k, 0x8016181D);
        lChoice = Ui.text(Ui.semibold, 27 * k, 0xFF16181D);
        lChoiceNo = Ui.text(Ui.semibold, 32 * k, 0xFFB4361E);
        lChoiceSub = Ui.text(Ui.medium, 24 * k, 0x9916181D);
        lPill = Ui.text(Ui.semibold, 27 * k, 0xFF16181D);
        lNote = Ui.text(Ui.semibold, 40 * k, 0xFFFFFFFF);
        lNoteSub = Ui.text(Ui.medium, 28 * k, 0xFFFFFFFF);
        lNote.setTextAlign(Paint.Align.CENTER);
        lNoteSub.setTextAlign(Paint.Align.CENTER);
    }

    /** Where the cards would be, when a search found none: what it looked for, or that it couldn't. */
    private void drawSearchNote(Canvas c) {
        if (searchNote == null || searching || !found.isEmpty()) return;
        searchPaints();
        // Centred across the screen, in the middle of where the cards would be.
        float x = getWidth() / 2f, y = (L_CARDS_TOP + L_CARD_H / 2) * k;
        lNote.setColor(tone(L_INK, 0xFFFFFFFF));
        c.drawText(searchNote, x, y - 6 * k, lNote);
        lNoteSub.setColor(Ui.alpha(tone(L_INK, 0xFFFFFFFF), 0.6f));
        c.drawText(searchNoteSub, x, y + 44 * k, lNoteSub);
    }

    /** The bar, grown out of the Search button (`b`) toward the left edge as searchShow goes from 0 to 1. */
    private void drawSearchBar(Canvas c, RectF b) {
        searchPaints();
        float t = Ui.clamp(searchShow.value), h = b.height() + (76 * k - b.height()) * t, cy = b.centerY();
        float left = b.left + (MARGIN * k - b.left) * t;
        cell.set(left, cy - h / 2, b.right, cy + h / 2);
        tmp.set(cell);
        tmp.offset(0, 10 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.12f + 0.2f * t));
        shadows.draw(c, tmp, h / 2, SHADOW_BLUR * k, shadow);
        fill.setShader(null);
        fill.setColor(0xF7F6F7F9);
        c.drawRoundRect(cell, h / 2, h / 2, fill);
        Symbols.draw(getContext(), c, "search", cell.left + h / 2, cy, 30 * k, 0xFF16181D);
        if (t < 0.6f) return;
        int a = Math.round(255 * Ui.clamp((t - 0.6f) / 0.4f));
        float tx = cell.left + h / 2 + 36 * k, base = cy + Ui.halfCap(lBar);
        if (query.length() == 0) {
            lBarHint.setAlpha(Math.round(0x80 * a / 255f));
            c.drawText(hearing ? Ui.tr("Listening…") : Ui.tr("Search movies and shows"), tx + 18 * k, base, lBarHint);   // after the caret, not under it
        } else {
            lBar.setAlpha(a);
            String q = query.toString();
            c.drawText(q, tx, base, lBar);
            tx += lBar.measureText(q);
        }
        if (searchZone == SZ_KEYS) {   // the caret, where the next letter goes
            fill.setColor(Ui.alpha(0xFF16181D, a / 255f));
            c.drawRect(tx + 4 * k, cy - 20 * k, tx + 7 * k, cy + 20 * k, fill);
        }
        // Its buttons at the right end: the microphone (where the TV has voice input) and close.
        float bs = 56 * k, gap = 10 * k, bx = cell.right - (h - bs) / 2 - bs;
        for (int j = BAR_CLOSE; j >= BAR_MIC; j--) {
            if (j == BAR_MIC && !listens()) continue;
            boolean on = searchZone == SZ_BAR && barIndex == j, live = hearing && j == BAR_MIC;
            float ccx = bx + bs / 2;
            if (live) {   // listening: red, with a ring that swells with the voice
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFE5402F, (0.18f + 0.22f * hearShown) * a / 255f));
                c.drawCircle(ccx, cy, bs / 2 + (5 + 9 * hearShown) * k, fill);
                fill.setColor(Ui.alpha(0xFFE5402F, a / 255f));
                c.drawCircle(ccx, cy, bs / 2, fill);
                on = true;   // the white icon
            } else if (on) {
                fill.setColor(Ui.alpha(0xFF16181D, a / 255f));
                c.drawCircle(ccx, cy, bs / 2, fill);
            }
            Paint line = cardSpinner;
            line.setStrokeWidth(3.5f * k);
            line.setColor(Ui.alpha(on ? 0xFFFFFFFF : 0xFF4A4D55, a / 255f));
            keyPath.reset();
            if (j == BAR_CLOSE) {
                float d = 10 * k;
                keyPath.moveTo(ccx - d, cy - d);
                keyPath.lineTo(ccx + d, cy + d);
                keyPath.moveTo(ccx + d, cy - d);
                keyPath.lineTo(ccx - d, cy + d);
            } else {   // a microphone: the capsule, the cradle under it, the stand
                tmp.set(ccx - 6 * k, cy - 15 * k, ccx + 6 * k, cy + 5 * k);
                keyPath.addRoundRect(tmp, 6 * k, 6 * k, android.graphics.Path.Direction.CW);
                tmp.set(ccx - 11 * k, cy - 7 * k, ccx + 11 * k, cy + 11 * k);
                keyPath.arcTo(tmp, 0, 180, true);
                keyPath.moveTo(ccx, cy + 11 * k);
                keyPath.lineTo(ccx, cy + 16 * k);
            }
            c.drawPath(keyPath, line);
            bx -= bs + gap;
        }
        if (searching) {   // looking: a small ring turning, just left of the buttons
            float sr = 14 * k, turn = (SystemClock.uptimeMillis() % 1000) / 1000f * 360, sx = bx + bs - sr - 6 * k;
            cardSpinner.setStrokeWidth(3.5f * k);
            cardSpinner.setColor(0x9916181D);
            tmp.set(sx - sr, cy - sr, sx + sr, cy + sr);
            c.drawArc(tmp, turn, 270, false, cardSpinner);
        }
    }

    /** The letters, in one line across the screen where the tabs are: no panel, only the focused key on white. */
    private void drawSearchKeys(Canvas c) {
        searchPaints();
        String[] keys = searchKeys();
        float x0 = MARGIN * k, w = (CONTENT_R - MARGIN) * k / keys.length, top = L_TABBAR_TOP * k, h = L_TABBAR_H * k;
        int ink = tone(L_INK, 0xFFFFFFFF);
        if (keys.length > 30) w = (CONTENT_R - MARGIN) * k / keys.length;
        for (int i = 0; i < keys.length; i++) {
            float l = x0 + i * w, cx = l + w / 2, cy = top + h / 2;
            boolean on = i == keyIndex && searchZone == SZ_KEYS;
            if (on) {
                cell.set(l + 3 * k, top, l + w - 3 * k, top + h);
                tmp.set(cell);
                tmp.offset(0, 6 * k);
                shadow.setColor(0x40000000);
                shadows.draw(c, tmp, 14 * k, SHADOW_BLUR * k, shadow);
                fill.setShader(null);
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(cell, 14 * k, 14 * k, fill);
            }
            int color = on ? 0xFF16181D : ink;
            String key = keys[i];
            if (key.equals(SPACE) || key.equals(ERASE)) {
                Paint line = cardSpinner;   // a stroke paint: the space's bracket, the erase key's arrow
                line.setStrokeWidth(3.5f * k);
                line.setColor(color);
                keyPath.reset();
                if (key.equals(SPACE)) {
                    keyPath.moveTo(cx - 16 * k, cy - 2 * k);
                    keyPath.lineTo(cx - 16 * k, cy + 8 * k);
                    keyPath.lineTo(cx + 16 * k, cy + 8 * k);
                    keyPath.lineTo(cx + 16 * k, cy - 2 * k);
                } else {
                    keyPath.moveTo(cx - 20 * k, cy);
                    keyPath.lineTo(cx - 9 * k, cy - 12 * k);
                    keyPath.lineTo(cx + 18 * k, cy - 12 * k);
                    keyPath.lineTo(cx + 18 * k, cy + 12 * k);
                    keyPath.lineTo(cx - 9 * k, cy + 12 * k);
                    keyPath.close();
                    keyPath.moveTo(cx - 1 * k, cy - 5 * k);
                    keyPath.lineTo(cx + 9 * k, cy + 5 * k);
                    keyPath.moveTo(cx + 9 * k, cy - 5 * k);
                    keyPath.lineTo(cx - 1 * k, cy + 5 * k);
                }
                c.drawPath(keyPath, line);
            } else {
                Paint p = key.length() > 1 ? lKeySmall : on ? lKeyOn : lKey;
                p.setColor(color);
                c.drawText(key, cx, cy + Ui.halfCap(p), p);
            }
        }

    }

    /** The held letter's accented forms, in a dark capsule just under it: drawn after the cards, over them. */
    private void drawAccents(Canvas c) {
        searchPaints();
        String[] keys = searchKeys();
        float x0 = MARGIN * k, w = (CONTENT_R - MARGIN) * k / keys.length, top = L_TABBAR_TOP * k, h = L_TABBAR_H * k;
        if (!accentOpen || keyIndex >= keys.length || !ACCENTS.containsKey(keys[keyIndex])) return;
        String forms = ACCENTS.get(keys[keyIndex]);
        float bw = 64 * k, ph = 72 * k, pw = forms.length() * bw + 16 * k, kx = x0 + keyIndex * w + w / 2;
        float pl = Math.max(MARGIN * k, Math.min(CONTENT_R * k - pw, kx - pw / 2)), pt = top + h + 10 * k;
        cell.set(pl, pt, pl + pw, pt + ph);
        tmp.set(cell);
        tmp.offset(0, 8 * k);
        shadow.setColor(0x66000000);
        shadows.draw(c, tmp, ph / 2, SHADOW_BLUR * k, shadow);
        fill.setShader(null);
        fill.setColor(0xF01C1E22);
        c.drawRoundRect(cell, ph / 2, ph / 2, fill);
        for (int j = 0; j < forms.length(); j++) {
            float l = pl + 8 * k + j * bw, ccx = l + bw / 2, ccy = pt + ph / 2;
            boolean on = j == accentIndex;
            if (on) {
                cell.set(l + 4 * k, pt + 8 * k, l + bw - 4 * k, pt + ph - 8 * k);
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(cell, 14 * k, 14 * k, fill);
            }
            Paint p = on ? lKeyOn : lKey;
            p.setColor(on ? 0xFF16181D : 0xFFFFFFFF);
            String f = String.valueOf(forms.charAt(j));
            c.drawText(f, ccx, ccy + Ui.halfCap(p), p);
        }
    }

    /** Inside the opened card, after OK: "Open in" and its apps (the focused one dark), or where it is instead. */
    private void drawSearchChoice(Canvas c, Recs.Pick p, float r) {
        searchPaints();
        float h = 132 * k, cy = cell.bottom - h / 2;
        c.save();
        c.clipRect(cell.left, cell.bottom - h, cell.right, cell.bottom);
        fill.setShader(null);
        fill.setColor(0xF5F6F7F9);
        c.drawRoundRect(cell, r, r, fill);
        c.restore();
        float x = cell.left + 40 * k;
        if (p.notOn || p.apps == null || p.apps.isEmpty()) {
            c.drawText(Ui.tr("Not on your apps"), x, cy - 6 * k, lChoiceNo);
            String where = p.rent != null ? Ui.trf("Rent or buy: {stores}", "stores", p.rent) : Recs.upcoming(p) ? Ui.tr("Not streaming anywhere yet") : Ui.tr("Not available to stream");
            c.drawText(where, x, cy + 32 * k, lChoiceSub);
            return;
        }
        String open = Ui.tr("Open in");
        c.drawText(open, x, cy + Ui.halfCap(lChoice), lChoice);
        x += lChoice.measureText(open) + 24 * k;
        float ph = 76 * k, icon = 46 * k;
        for (int j = 0; j < p.apps.size(); j++) {
            String[] app = p.apps.get(j);
            boolean on = j == chooseIndex;
            float w = 16 * k + icon + 14 * k + lPill.measureText(app[2]) + 28 * k;
            if (x + w > cell.right - 24 * k) break;
            tmp.set(x, cy - ph / 2, x + w, cy + ph / 2);
            fill.setColor(on ? 0xFF16181D : 0x1416181D);
            c.drawRoundRect(tmp, ph / 2, ph / 2, fill);
            Bitmap b = watchIcon(app[1]);
            if (b != null) {
                cell2.set(x + 16 * k, cy - icon / 2, x + 16 * k + icon, cy + icon / 2);
                c.drawBitmap(b, null, cell2, bitmapPaint);
            }
            lPill.setColor(on ? 0xFFFFFFFF : 0xFF16181D);
            c.drawText(app[2], x + 16 * k + icon + 14 * k, cy + Ui.halfCap(lPill), lPill);
            x += w + 14 * k;
        }
    }

    private final RectF cell2 = new RectF();


    /** "86% · Drama · 1h 52m": the audience score before the details, made once per score and details. */
    private String scoredMeta(String meta, int audience) {
        if (audience < 0) return meta;
        String key = audience + "\u0000" + meta;
        String s = scoredMetas.get(key);
        if (s == null) {
            s = meta == null || meta.isEmpty() ? audience + "%" : audience + "% · " + meta;
            if (scoredMetas.size() > 200) scoredMetas.clear();
            scoredMetas.put(key, s);
        }
        return s;
    }

    private final java.util.HashMap<String, String> scoredMetas = new java.util.HashMap<>();
    private Bitmap popcorn;
    private final Paint popcornPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** The popcorn bucket before an audience score (TV px, at the opened card's size). */
    private static final float POPCORN = 22, POPCORN_GAP = 7;

    /** A plain popcorn bucket (white with red stripes, kernels on top), drawn once at `px`: not Rotten Tomatoes' logo. */
    private static Bitmap popcornIcon(int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float u = px / 16f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        android.graphics.Path bucket = new android.graphics.Path();
        bucket.moveTo(3.2f * u, 6.5f * u);
        bucket.lineTo(12.8f * u, 6.5f * u);
        bucket.lineTo(11.5f * u, 15.4f * u);
        bucket.lineTo(4.5f * u, 15.4f * u);
        bucket.close();
        p.setColor(0xFFF5F0E6);
        c.drawPath(bucket, p);
        p.setColor(0xFFE0412E);
        android.graphics.Path stripe = new android.graphics.Path();
        stripe.moveTo(5.3f * u, 6.5f * u); stripe.lineTo(6.6f * u, 6.5f * u); stripe.lineTo(7.1f * u, 15.4f * u); stripe.lineTo(6.0f * u, 15.4f * u); stripe.close();
        stripe.moveTo(9.4f * u, 6.5f * u); stripe.lineTo(10.7f * u, 6.5f * u); stripe.lineTo(10.0f * u, 15.4f * u); stripe.lineTo(8.9f * u, 15.4f * u); stripe.close();
        c.drawPath(stripe, p);
        p.setColor(0xFFFFE7A8);
        c.drawCircle(5 * u, 5.2f * u, 1.9f * u, p);
        c.drawCircle(11 * u, 5.2f * u, 1.9f * u, p);
        p.setColor(0xFFFFF2C8);
        c.drawCircle(8 * u, 4.1f * u, 2.2f * u, p);
        c.drawCircle(9.6f * u, 2.7f * u, 1.4f * u, p);
        p.setColor(0xFFFFF7DE);
        c.drawCircle(6.6f * u, 2.6f * u, 1.4f * u, p);
        return b;
    }

    private void drawCardLine(Canvas c, String title, String pkg, String wide, String narrow, float left, float right, float bottom, float open, int audience) {
        float baseline = bottom + 50 * k;
        float size = (36 + (LINE_ICON - 36) * open) * k, x = left;
        Bitmap icon = watchIcon(pkg);
        if (icon != null) {
            float cy = baseline - (27 + 4 * open) * k * 0.36f;
            tmp.set(left, cy - size / 2, left + size, cy + size / 2);
            c.drawBitmap(icon, null, tmp, bitmapPaint);
            x = left + size + 14 * k;
            // Search: the other apps that have it, each overlapping the one before, a ring of the background between.
            for (int j = 1; lineApps != null && j < lineApps.size(); j++) {
                Bitmap more = watchIcon(lineApps.get(j)[1]);
                if (more == null) continue;
                float l = left + j * size * 0.7f, ring = 3 * k;
                tmp.set(l - ring, cy - size / 2 - ring, l + size + ring, cy + size / 2 + ring);
                fill.setShader(null);
                fill.setColor(tone(0xFFEDEDF0, 0xFF1C1E22));
                c.drawRoundRect(tmp, size * 0.24f + ring, size * 0.24f + ring, fill);
                tmp.set(l, cy - size / 2, l + size, cy + size / 2);
                c.drawBitmap(more, null, tmp, bitmapPaint);
                x = l + size + 14 * k;
            }
        }
        int a = lCardMeta.getAlpha();
        // A narrow card's line is a little smaller, so its title fits beside the episode (as the opened one grows). The
        // text is laid out at its full size and the canvas scales it: a new text size on every frame of the opening
        // meant laying every card's text out again each frame (~3 ms a line on a 2018 BRAVIA).
        float ft = (27 + 4 * open) / 31f, fm = (21 + 4 * open) / 25f;
        // Room for the details: each at its full width while it's well visible, giving it back gradually as it fades out.
        // A cut-off at the end of the fade freed it all on one frame, and the title jumped longer as the card settled.
        float detailW = 0;
        boolean scored = audience >= 0 && wide != null && !wide.isEmpty();
        float scoreRoom = scored ? (POPCORN + POPCORN_GAP) * k : 0;   // the popcorn bucket before the score
        if (wide != null && !wide.isEmpty()) detailW = Math.max(detailW, (lCardMeta.measureText(wide) + scoreRoom) * fm * Ui.clamp(open * 2));
        if (narrow != null) detailW = Math.max(detailW, lCardMeta.measureText(narrow) * fm * Ui.clamp((1 - open) * 2));
        float titleW = Math.max(40 * k, right - x - (detailW + Math.min(16 * k, detailW)));
        c.save();
        c.scale(ft, ft, x, baseline);
        c.drawText(fitTitle(title, titleW / ft), x, baseline, lCardTitle);
        c.restore();
        lCardMeta.setTextAlign(Paint.Align.RIGHT);
        float metaBase = baseline - 1 * k;
        c.save();
        c.scale(fm, fm, right, metaBase);
        if (open > 0.02f && wide != null && !wide.isEmpty()) {
            lCardMeta.setAlpha(Math.round(a * open));
            c.drawText(wide, right, metaBase, lCardMeta);
            // On the selected (opened) card only: its audience score, after a popcorn bucket, fading in as it opens.
            if (scored) {
                int px = Math.max(8, Math.round(POPCORN * k));
                if (popcorn == null || popcorn.getWidth() != px) popcorn = popcornIcon(px);
                float iconRight = right - lCardMeta.measureText(wide) - POPCORN_GAP * k, cy = metaBase - Ui.halfCap(lCardMeta);
                tmp.set(iconRight - POPCORN * k, cy - POPCORN * k / 2 - 1 * k, iconRight, cy + POPCORN * k / 2 - 1 * k);
                popcornPaint.setAlpha(Math.round(255 * open));
                c.drawBitmap(popcorn, null, tmp, popcornPaint);
            }
        }
        if (open < 0.98f && narrow != null) {
            lCardMeta.setAlpha(Math.round(a * (1 - open)));
            c.drawText(narrow, right, metaBase, lCardMeta);
        }
        c.restore();
        lCardMeta.setTextAlign(Paint.Align.LEFT);
        lCardMeta.setAlpha(a);
    }

    /** The title cut to `width` (lCardTitle's size), kept per title and width: ellipsize lays the text out each time. */
    private String fitTitle(String title, float width) {
        String key = title + "\u0000" + Math.round(width / (4 * k));   // 4 px steps: a growing card reuses them
        String fit = fitTitles.get(key);
        if (fit != null) return fit;
        fitPaint.set(lCardTitle);
        fit = TextUtils.ellipsize(title, fitPaint, width, TextUtils.TruncateAt.END).toString();
        if (fitTitles.size() > 400) fitTitles.clear();
        fitTitles.put(key, fit);
        return fit;
    }

    private final java.util.HashMap<String, String> fitTitles = new java.util.HashMap<>();

    /** The app's switcher icon, or null while it's being made (on LOADER; the frame after it's ready shows it). */
    private Bitmap watchIcon(String pkg) {
        if (pkg == null) return null;
        Bitmap b = watchIcons.get(pkg);
        if (b != null || !watchIconsPending.add(pkg)) return b;
        int px = Math.max(8, Math.round(LINE_ICON * k));
        Context ctx = getContext().getApplicationContext();
        LOADER.execute(() -> {
            try {
                watchIcons.put(pkg, SwitcherActivity.appIcon(ctx, pkg, px));
                postInvalidate();
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("card icon " + pkg + ": " + e.getMessage());
            }
        });
        return null;
    }

    private final Paint progressRim = new Paint(Paint.ANTI_ALIAS_FLAG), cardSpinner = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        progressRim.setStyle(Paint.Style.STROKE);
        cardSpinner.setStyle(Paint.Style.STROKE);
        cardSpinner.setStrokeCap(Paint.Cap.ROUND);
    }
    /** A card drew its loading ring last frame: the cards keep redrawing until their pictures are in. */
    private boolean cardsLoading;
    private LinearGradient cardShade;

    /**
     * The round buttons (Live TV, HDMI Inputs and Settings by default): icon-only glass circles (light by day, smoked at
     * night); the focused one turns white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        drawStatusIcons(c);
        for (int slot = 0; slot < lightButtons.size(); slot++) {
            int i = slot;
            // The lifted button passes over its neighbors while they exchange places.
            if (reorderTop && liftIndex >= 0 && liftIndex < lightButtons.size()) {
                if (slot == lightButtons.size() - 1) i = liftIndex;
                else if (slot >= liftIndex) i = slot + 1;
            }
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[TABS + i];
            if (it.kind == HomeApps.SEARCH && searchShow.value > 0.001f) continue;   // the bar takes its place (drawLightTop)
            float lift = reorderTop && i == liftIndex ? Math.max(0, moveLift.value) : 0;
            float dx = topSlide[i].value;
            boolean transform = dx != 0 || lift > 0.001f;
            if (transform) {
                c.save();
                c.translate(dx, -6 * k * lift);
                if (lift > 0.001f) c.rotate(wiggle * lift, r.centerX(), r.centerY());
            }
            float f = Ui.clamp(topFocus[TABS + i].value), s = 1 + 0.1f * f + LIFT_GROW * lift, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(glassLight(), SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = it.label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
            if (transform) c.restore();
        }
        if (sleepText.isEmpty()) return;
        float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[TABS].left) - 28 * k, cy = L_BUTTON_CY * k;
        float tw = lQuote.measureText(sleepText), size = lQuote.getTextSize();
        c.drawText(sleepText, right - tw, cy + Ui.halfCap(lQuote), lQuote);
        Symbols.draw(getContext(), c, "bedtime", right - tw - size * 0.9f, cy, size, lQuote.getColor());
    }

    /** Wi-Fi/Ethernet, Bluetooth (when something is connected) and a USB drive, at the top left. */
    private void drawStatusIcons(Canvas c) {
        if (!Prefs.statusIcons()) return;
        float height = L_STATUS * k, gap = L_STATUS_GAP * k;
        int color = Ui.alpha(tone(L_INK, 0xEBFFFFFF), 0.85f);
        float x = MARGIN * k;
        // The top title ("For You", or "Continue Watching") is on this line too: the icons start after it, not on it.
        if (!clockMode()) x += lTitle.measureText(picksMode() ? Ui.tr("For You") : Ui.tr("Continue Watching")) + gap;
        if (network != 0) x = drawStatusIcon(c, network == 2 ? "ethernet" : "wifi", x, height, gap, color);
        if (bluetoothConnected) x = drawStatusIcon(c, "bluetooth", x, height, gap, color);
        if (usbConnected) drawStatusIcon(c, "usb", x, height, gap, color);
    }

    /** Narrow symbols need a little more height to look as big as wide ones. */
    private static float statusScale(String name) {
        if ("bluetooth".equals(name)) return 1.18f;
        if ("usb".equals(name)) return 1.3f;
        return 1;
    }

    /** Draws a status icon left-aligned at `left`; returns where the next one starts. */
    private float drawStatusIcon(Canvas c, String name, float left, float height, float gap, int color) {
        float size = Symbols.pointSizeForHeight(getContext(), name, height * statusScale(name));
        float width = Symbols.width(getContext(), name, size);
        Symbols.draw(getContext(), c, name, left + width / 2, L_BUTTON_CY * k, size, color);
        return left + width + gap;
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;
                if (!picksMode()) {
                    // No tabs or picks: up goes to what you were watching, or to the corner buttons.
                    if (continueMode() && !watching.isEmpty()) {
                        zone = Z_WATCH;
                    } else if (!lightButtons.isEmpty()) {
                        zone = Z_TOP;
                        topIndex = TABS;
                    } else {
                        return true;
                    }
                    break;
                }
                if (musicTab()) {   // up from the shelf: Now Playing's controls
                    zone = Z_MUSIC;
                    break;
                }
                if (cards.isEmpty()) {
                    if (!picksOn() && lightButtons.isEmpty()) return true;   // no tabs and no buttons above
                    zone = Z_TOP;
                    topIndex = picksMode() ? tab : TABS;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_MUSIC:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(musicFocus.length - 1, musicIndex + dx));
                    if (next == musicIndex) return true;
                    musicIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_WATCH:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(watching.size() - 1, watchIndex + dx));
                    if (next == watchIndex) return true;
                    watchIndex = next;
                } else if (dy < 0) {
                    if (lightButtons.isEmpty()) return true;
                    zone = Z_TOP;
                    topIndex = TABS;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_TOP:
                if (dx != 0 && !picksMode() && topIndex + dx < TABS) return true;   // no tabs to the left
                if (dy > 0 && !picksMode()) {
                    zone = continueMode() && !watching.isEmpty() ? Z_WATCH : Z_SHELF;
                    break;
                }
                if (dx != 0) {
                    int next = Math.max(picksMode() ? 0 : TABS, Math.min(TABS - 1 + lightButtons.size(), topIndex + dx));
                    // The tab slots past tabCount() aren't drawn: step over them.
                    if (next >= tabCount() && next < TABS) next = dx > 0 ? (lightButtons.isEmpty() ? topIndex : TABS) : tabCount() - 1;
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < TABS) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (musicTab() && topIndex < TABS) {
                        zone = Z_MUSIC;
                    } else if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                    }
                } else if (topIndex < TABS && !lightButtons.isEmpty()) {
                    topIndex = TABS;   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    /** Rounds a shape's edges to whole pixels (crisp at rest; a scale animation moves them again). */
    private static void snap(RectF r) {
        r.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
