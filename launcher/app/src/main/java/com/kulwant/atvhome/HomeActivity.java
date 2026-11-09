package com.kulwant.atvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen. All D-pad navigation is handled here (no Android focus search), so
 * movement is deterministic: zones TOP (pills) / DOCK / GRID, Up/Down move exactly one row.
 */
public class HomeActivity extends Activity implements Wallpapers.Listener, PlayHistory.Listener {

    private enum Zone { TOP, RECENT, DOCK, GRID }

    private static final int PILLS = 3;
    /** apps per grid row (config.xml: columns) */
    private int COLS;
    /** dock slots the dock cards are sized for (config.xml: dock_columns); the tray hugs the apps */
    private int DOCK_COLS;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    // layout (px)
    private int W, H, side, bandTop, bandH, trayPad, dockCell, dockCardW, dockCardH, gridTop, rowH;

    private FrameLayout root, content;
    private TextView greeting, clock, date, dockTitle;
    private ImageView wifiIcon;
    private View band;
    private Frost frost;

    // Top Shelf: while you move along the dock, the background becomes the focused app's picture
    // (res/drawable-nodpi/shelf_<package, dots as _>.jpg). Photos come back after SHELF_IDLE_MS
    // without a key press, when focus leaves the dock, or on returning Home.
    private static final long SHELF_IDLE_MS = 20_000, SHELF_SETTLE_MS = 350;
    private final ImageView[] shelfViews = new ImageView[2];   // [0] below [1]
    private final Runnable shelfSettle = this::shelfSettled;
    private String shelfPkg;   // app whose picture is up, or null when the photos show
    private final java.util.Map<String, android.graphics.Bitmap[]> shelfCache = new java.util.HashMap<>();
    private android.graphics.Bitmap wallBlur;
    private LinearLayout pillRow;
    // follower counters under the round buttons: YouTube, Instagram, Facebook
    private static final String[] SOCIAL_KEYS = {"yt", "ig", "fb"};
    private final String[] socialIds = new String[3];
    private final View[] socialPills = new View[3];
    private final TextView[] socialTexts = new TextView[3];
    private boolean hasSocial;
    private static final long SUBS_REFRESH_MS = 30 * 60_000L;
    private final View[] pills = new View[PILLS];
    private GlassHighlight highlight;
    private View glow;
    private GradientDrawable glowBg;
    private RecyclerView dockRv, gridRv;
    private TileAdapter dockAdapter, gridAdapter;
    private InputsMenu inputs;
    private SettingsTray tray;

    // "last played" row above the dock: the focused dock app's newest PlayHistory items
    // small on purpose: the row sits over the wallpaper photo
    private static final int RECENT_W = 128, RECENT_H = 72, RECENT_GAP = 8;
    private LinearLayout recentRow;
    private final View[] recentCards = new View[PlayHistory.PER_APP];
    private final ImageView[] recentArts = new ImageView[PlayHistory.PER_APP];
    private final TextView[] recentTitles = new TextView[PlayHistory.PER_APP];
    private final TextView[] recentSubs = new TextView[PlayHistory.PER_APP];
    private final List<PlayHistory.Item> recents = new ArrayList<>();

    private Zone zone = Zone.DOCK;
    private int topIdx, recentIdx, dockIdx, gridIdx;
    private final RectF hl = new RectF();
    private float hlRadius, hlRadiusNow;
    private boolean hlPlaced;
    private ValueAnimator hlAnim, glowAnim;
    private int glowColor = 0;
    private int lastTitleApp = -1;
    private boolean titleChanging;

    // rotating wallpapers: Files from the photo store (see Wallpapers)
    private final List<Object> wallpapers = new ArrayList<>();
    private final ImageView[] wpViews = new ImageView[2];
    private int wpIdx;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        COLS = Math.max(3, Math.min(6, getResources().getInteger(R.integer.columns)));
        DOCK_COLS = Math.max(4, Math.min(8, getResources().getInteger(R.integer.dock_columns)));
        zone = Zone.values()[prefs.getInt("zone", Zone.DOCK.ordinal())];
        topIdx = prefs.getInt("top", 0);
        dockIdx = prefs.getInt("dock", 0);
        gridIdx = prefs.getInt("grid", 0);
        if (zone == Zone.TOP || zone == Zone.RECENT) zone = Zone.DOCK;   // come back to apps, not the buttons

        buildUi();
        setContentView(root);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        IntentFilter time = new IntentFilter();
        time.addAction(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        time.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        time.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(statusReceiver, time);

        IntentFilter pk = new IntentFilter();
        pk.addAction(Intent.ACTION_PACKAGE_ADDED);
        pk.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pk.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pk.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pk.addDataScheme("package");
        registerReceiver(packageReceiver, pk);

        loadApps();

        Wallpapers.setListener(this);
        PlayHistory.setListener(this);
        startForegroundService(new Intent(this, PhoneService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        updateWifi();
        if (inputs.isOpen()) inputs.close();
        tray.close();
        scheduleMinute();
        scheduleWallpaper();
        showRecents();   // refreshes the "2 h ago" labels
        // catch what was playing just before Home was pressed (the service only looks every few seconds)
        if (getResources().getBoolean(R.bool.show_recently_watched)) {
            io.execute(() -> PlayHistory.poll(getApplicationContext()));
        }
        if (hasSocial && System.currentTimeMillis() - prefs.getLong("counts_at", 0) > SUBS_REFRESH_MS) {
            refreshSubs.run();
        } else if (hasSocial) {
            main.postDelayed(refreshSubs, SUBS_REFRESH_MS);
        }
    }

    @Override
    protected void onPause() {
        // nothing ticks (and no wallpaper is decoded) while an app is in front
        hideShelf(0);
        main.removeCallbacks(minuteTick);
        main.removeCallbacks(nextWallpaper);
        main.removeCallbacks(refreshSubs);
        super.onPause();
    }

    /** Some TV firmwares don't deliver ACTION_TIME_TICK, so the clock runs on our own timer. */
    private final Runnable minuteTick = () -> {
        updateClock();
        scheduleMinute();
    };

    private void scheduleMinute() {
        main.removeCallbacks(minuteTick);
        long now = System.currentTimeMillis();
        main.postDelayed(minuteTick, 60_000 - now % 60_000 + 50);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close menus and return to the dock.
        if (inputs.isOpen()) inputs.close();
        tray.close();
        hideShelf(450);
        if (zone == Zone.GRID && hasWindowFocus()) {
            zone = Zone.DOCK;
            applyFocus(true);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(statusReceiver);
        unregisterReceiver(packageReceiver);
        Wallpapers.setListener(null);
        PlayHistory.setListener(null);
        io.shutdownNow();
        super.onDestroy();
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(a)
                    || WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(a)) updateWifi();
            else updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    private void loadApps() {
        io.execute(() -> {
            AppRepo.Result r = AppRepo.load(getApplicationContext());
            main.post(() -> setApps(r));
        });
    }

    private void setApps(AppRepo.Result r) {
        dockAdapter.setApps(r.dock);
        gridAdapter.setApps(r.grid);
        layoutDock(Math.max(1, r.dock.size()));
        int rows = (r.grid.size() + COLS - 1) / COLS;
        ViewGroup.LayoutParams glp = gridRv.getLayoutParams();
        glp.height = Math.max(rows, 1) * rowH;
        gridRv.setLayoutParams(glp);
        ViewGroup.LayoutParams clp = content.getLayoutParams();
        clp.height = gridTop + Math.max(rows, 1) * rowH + Ui.dp(this, 40);
        content.setLayoutParams(clp);
        lastTitleApp = -1;
        clampIndices();
        // Wait for the RecyclerViews to lay out before placing the highlight.
        content.post(() -> content.post(() -> applyFocus(hlPlaced)));
        preloadShelves();
    }

    private void clampIndices() {
        int nd = dockAdapter.getItemCount(), ng = gridAdapter.getItemCount();
        dockIdx = Math.max(0, Math.min(dockIdx, nd - 1));
        gridIdx = Math.max(0, Math.min(gridIdx, ng - 1));
        if (zone == Zone.GRID && ng == 0) zone = Zone.DOCK;
        if (zone == Zone.DOCK && nd == 0) zone = ng > 0 ? Zone.GRID : Zone.TOP;
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void buildUi() {
        W = getResources().getDisplayMetrics().widthPixels;
        H = getResources().getDisplayMetrics().heightPixels;
        side = dp(48);
        // cells shrink to fit DOCK_COLS / COLS per row; cards keep the 16:9 TV banner shape
        int gap = dp(24);
        trayPad = dp(18);
        dockCell = (W - 2 * side - 2 * trayPad) / DOCK_COLS;
        dockCardW = dockCell - dp(18);
        dockCardH = dockCardW * 9 / 16;
        int gridCell = (W - 2 * side + gap) / COLS, gridCardW = gridCell - gap;
        int gridCardH = gridCardW * 9 / 16;
        bandH = dockCardH + dp(40);
        int bandBottom = dp(30);
        bandTop = H - bandBottom - bandH;
        rowH = gridCardH + dp(42);
        gridTop = H + dp(56);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(false);

        boolean useWallpaper = getResources().getBoolean(R.bool.use_wallpaper);
        if (useWallpaper) {
            findWallpapers();
            for (int i = 0; i < 2; i++) {
                wpViews[i] = new ImageView(this);
                wpViews[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
                wpViews[i].setAlpha(i == 0 ? 1f : 0f);
                root.addView(wpViews[i], new FrameLayout.LayoutParams(-1, -1));
            }
            if (!wallpapers.isEmpty()) {
                wpIdx = prefs.getInt("wallpaper", 0) % wallpapers.size();
                android.graphics.Bitmap first = loadWallpaper(wallpapers.get(wpIdx));
                wpViews[0].setImageBitmap(first);
                io.execute(() -> {
                    android.graphics.Bitmap b = Frost.blurred(first);
                    main.post(() -> {
                        wallBlur = b;
                        if (shelfPkg == null && frost != null) frost.setSource(b, 0);
                    });
                });
            }
            for (int i = 0; i < 2; i++) {
                shelfViews[i] = new ImageView(this);
                shelfViews[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
                shelfViews[i].setAlpha(0f);
                root.addView(shelfViews[i], new FrameLayout.LayoutParams(-1, -1));
            }
            // darken: evenly, and more towards the bottom where the dock and grid sit
            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x59000000, 0x66000000, 0xD9000000}));
            root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        }

        content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, H * 2));

        // ambient glow behind the dock title
        glow = new View(this);
        glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        // circle fades out before the view edges; scaleY flattens it into an ellipse
        glowBg.setGradientRadius(dp(290));
        glowBg.setGradientCenter(0.4f, 0.5f);
        glowBg.setColors(new int[]{0, 0});
        glow.setBackground(glowBg);
        glow.setScaleY(0.45f);
        content.addView(glow, at(dp(900), dp(600), 0, bandTop - dp(40) - dp(300)));

        // dock tray: frosted glass, centred, hugging the dock apps (sized in layoutDock)
        if (useWallpaper) {
            frost = new Frost(this, dp(30), W, H);
            band = frost;
        } else {
            band = new View(this);
            band.setBackground(Ui.rounded(0xFF1C1C1E, dp(30), dp(1), 0x33FFFFFF));
        }
        content.addView(band, at(W - 2 * side, bandH, side, bandTop));

        // the single glass highlight (below tiles and pills)
        highlight = new GlassHighlight(this);
        highlight.setAlpha(0f);
        content.addView(highlight, new FrameLayout.LayoutParams(-1, -1));

        // large greeting title, then clock and date as a quiet "tab" row under it
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        greeting = Ui.text(this, 500, 44, 0xFFFFFFFF);
        greeting.setLetterSpacing(-0.015f);
        tl.addView(greeting);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.BOTTOM);
        clock = Ui.text(this, 600, 18, 0xFFFFFFFF);
        date = Ui.text(this, 400, 18, 0x99FFFFFF);
        tabs.addView(clock);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = dp(28);
        tabs.addView(date, dlp);
        LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(-2, -2);
        tbp.topMargin = dp(16);
        tbp.leftMargin = dp(2);
        tl.addView(tabs, tbp);
        content.addView(tl, at(W * 2 / 3, -2, side, dp(46)));

        // round buttons: Inputs, Wi-Fi, Settings
        pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pills[0] = pill(R.drawable.ic_input);
        pills[1] = pill(R.drawable.ic_wifi);
        wifiIcon = (ImageView) ((ViewGroup) pills[1]).getChildAt(0);
        pills[2] = pill(R.drawable.ic_settings);
        for (int i = 0; i < PILLS; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            if (i > 0) lp.leftMargin = dp(14);
            pillRow.addView(pills[i], lp);
        }
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        plp.topMargin = dp(40);
        plp.rightMargin = side;
        content.addView(pillRow, plp);

        // follower counters: one compact row under the buttons (display only, never focused)
        socialIds[0] = getString(R.string.youtube_channel).trim();
        socialIds[1] = getString(R.string.instagram_user).trim();
        socialIds[2] = getString(R.string.facebook_page).trim();
        int[] logos = {R.drawable.ic_youtube, R.drawable.ic_instagram, R.drawable.ic_facebook};
        LinearLayout counters = new LinearLayout(this);
        counters.setOrientation(LinearLayout.HORIZONTAL);
        counters.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        for (int i = 0; i < 3; i++) {
            if (socialIds[i].isEmpty()) continue;
            hasSocial = true;
            LinearLayout sp = new LinearLayout(this);
            sp.setOrientation(LinearLayout.HORIZONTAL);
            sp.setGravity(Gravity.CENTER_VERTICAL);
            ImageView logo = new ImageView(this);
            logo.setImageResource(logos[i]);
            sp.addView(logo, new LinearLayout.LayoutParams(dp(16), dp(16)));
            socialTexts[i] = Ui.text(this, 500, 14, 0xD9FFFFFF);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.leftMargin = dp(6);
            sp.addView(socialTexts[i], tlp);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.leftMargin = dp(18);
            counters.addView(sp, slp);
            socialPills[i] = sp;
            showCount(i, prefs.getString("count_" + SOCIAL_KEYS[i], null));
        }
        FrameLayout.LayoutParams clp2 = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp2.topMargin = dp(40 + 44 + 26);
        clp2.rightMargin = side + dp(4);
        content.addView(counters, clp2);

        // focused dock app name, above the tray's left edge
        dockTitle = Ui.text(this, 500, 26, 0xF2FFFFFF);
        content.addView(dockTitle, at(W - 2 * side, -2, side, bandTop - dp(46)));

        // last played: small text cards just above the tray, only while the app has history
        recentRow = new LinearLayout(this);
        recentRow.setOrientation(LinearLayout.HORIZONTAL);
        recentRow.setAlpha(0f);
        for (int i = 0; i < PlayHistory.PER_APP; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(RECENT_W), dp(RECENT_H));
            if (i > 0) lp.leftMargin = dp(RECENT_GAP);
            recentRow.addView(recentCard(i), lp);
        }
        content.addView(recentRow, at(-2, dp(RECENT_H), side, bandTop - dp(10 + RECENT_H)));

        // dock
        dockAdapter = new TileAdapter(dockCell, dockCardW, dockCardH, 0, false);
        dockRv = recycler(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false), dockAdapter);
        content.addView(dockRv, at(dockCell * DOCK_COLS, dockCardH, side, bandTop + (bandH - dockCardH) / 2));
        layoutDock(DOCK_COLS);

        // grid
        TextView appsHeader = Ui.text(this, 500, 15, 0x80FFFFFF);
        appsHeader.setText("Apps");
        appsHeader.setLetterSpacing(0.04f);
        content.addView(appsHeader, at(-2, -2, side + dp(2), gridTop - dp(30)));
        gridAdapter = new TileAdapter(gridCell, gridCardW, gridCardH, rowH - gridCardH, true);
        gridRv = recycler(new GridLayoutManager(this, COLS), gridAdapter);
        content.addView(gridRv, at(gridCell * COLS, rowH, side - (gridCell - gridCardW) / 2, gridTop));


        inputs = new InputsMenu(this);
        root.addView(inputs.panel);
        tray = new SettingsTray(this, W, H, trayHost);
        root.addView(tray.scrim, new FrameLayout.LayoutParams(-1, -1));
        root.addView(tray.panel);
    }

    private FrameLayout.LayoutParams at(int w, int h, int x, int y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }

    private RecyclerView recycler(RecyclerView.LayoutManager lm, TileAdapter a) {
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(lm);
        rv.setAdapter(a);
        rv.setItemAnimator(null);
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setFocusable(false);
        rv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rv.setNestedScrollingEnabled(false);
        return rv;
    }

    /** Centres the tray, dock and title on the {@code n} dock apps (cards stay sized for DOCK_COLS). */
    private void layoutDock(int n) {
        n = Math.min(n, DOCK_COLS);
        int trayW = dockCell * n + 2 * trayPad, trayX = (W - trayW) / 2;
        FrameLayout.LayoutParams b = (FrameLayout.LayoutParams) band.getLayoutParams();
        b.width = trayW;
        b.leftMargin = trayX;
        band.setLayoutParams(b);
        FrameLayout.LayoutParams d = (FrameLayout.LayoutParams) dockRv.getLayoutParams();
        d.width = dockCell * n;
        d.leftMargin = trayX + trayPad;
        dockRv.setLayoutParams(d);
        int textX = trayX + trayPad + (dockCell - dockCardW) / 2;
        FrameLayout.LayoutParams t = (FrameLayout.LayoutParams) dockTitle.getLayoutParams();
        t.leftMargin = textX;
        dockTitle.setLayoutParams(t);
        FrameLayout.LayoutParams r = (FrameLayout.LayoutParams) recentRow.getLayoutParams();
        r.leftMargin = textX;
        recentRow.setLayoutParams(r);
        FrameLayout.LayoutParams g = (FrameLayout.LayoutParams) glow.getLayoutParams();
        g.leftMargin = textX + dp(80) - dp(360);
        glow.setLayoutParams(g);
    }

    /** Picture card: thumbnail or poster (cropped to 16:9) with the title on a dark fade at the bottom. */
    private View recentCard(int i) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(Ui.rounded(0x99000000, dp(12), 0, 0));
        final float radius = dp(12);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        card.setForeground(Ui.rounded(0, radius, dp(1), 0x26FFFFFF));
        recentArts[i] = new ImageView(this);
        // posters are portrait: keep the upper part, where titles and faces usually are
        recentArts[i].setScaleType(ImageView.ScaleType.MATRIX);
        card.addView(recentArts[i], new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x66000000, 0xE6000000}));
        card.addView(fade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(8), 0, dp(8), dp(6));
        recentTitles[i] = Ui.text(this, 600, 12, 0xFFFFFFFF);
        recentTitles[i].setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(recentTitles[i]);
        recentSubs[i] = Ui.text(this, 400, 10, 0xB3FFFFFF);
        recentSubs[i].setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(3);
        text.addView(recentSubs[i], lp);
        card.addView(text, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        recentCards[i] = card;
        return card;
    }

    /** Fills the card width and keeps the top of the picture (CENTER_CROP would cut posters' heads off). */
    private void setArt(ImageView v, android.graphics.Bitmap b) {
        v.setImageBitmap(b);
        if (b == null) return;
        float s = Math.max(dp(RECENT_W) / (float) b.getWidth(), dp(RECENT_H) / (float) b.getHeight());
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(s, s);
        // landscape thumbnails: centred; portrait posters: from a little below the top
        float extra = b.getHeight() * s - dp(RECENT_H);
        m.postTranslate((dp(RECENT_W) - b.getWidth() * s) / 2f,
                b.getHeight() > b.getWidth() ? -extra * 0.15f : -extra / 2f);
        v.setImageMatrix(m);
    }

    /** Fills the row from the focused dock app's history; the title moves up to make room. */
    private void showRecents() {
        if (recentRow == null) return;
        recents.clear();
        if (getResources().getBoolean(R.bool.show_recently_watched) && dockAdapter.getItemCount() > 0) {
            recents.addAll(PlayHistory.forApp(this, dockAdapter.apps.get(dockIdx).pkg));
        }
        for (int i = 0; i < PlayHistory.PER_APP; i++) {
            boolean has = i < recents.size();
            recentCards[i].setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            if (!has) continue;
            PlayHistory.Item it = recents.get(i);
            setArt(recentArts[i], it.art.isEmpty() ? null : android.graphics.BitmapFactory.decodeFile(
                    new java.io.File(PlayHistory.dir(this), it.art).getPath()));
            recentTitles[i].setText(it.title);
            String ago = PlayHistory.ago(it.at);
            recentSubs[i].setText(it.sub.isEmpty() ? ago : it.sub + " · " + ago);
        }
        boolean show = !recents.isEmpty();
        float lift = show ? -dp(RECENT_H + 10) : 0;
        recentRow.animate().alpha(show ? 1f : 0f).setDuration(200).start();
        dockTitle.animate().translationY(lift).setDuration(250).start();
        glow.animate().translationY(lift).setDuration(250).start();
        if (zone == Zone.RECENT && !show) {
            zone = Zone.DOCK;
            applyFocus(true);
        } else {
            recentIdx = Math.max(0, Math.min(recentIdx, recents.size() - 1));
        }
    }

    @Override
    public void onHistoryChanged() {
        showRecents();
        if (zone == Zone.RECENT) applyFocus(true);
    }

    private View pill(int icon) {
        LinearLayout p = new LinearLayout(this);
        p.setGravity(Gravity.CENTER);
        p.setBackground(Ui.rounded(0x2EFFFFFF, dp(22), dp(1), 0x33FFFFFF));
        p.setAlpha(0.85f);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        p.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        return p;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        String part = h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 17 ? "Good afternoon" : "Good evening";
        String name = getString(R.string.greeting_name).trim();
        greeting.setText(name.isEmpty() ? part : part + ", " + name);
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(DateFormat.format(h24 ? "H:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE, d MMMM", now));
    }

    // ---------------------------------------------------------------- wallpapers

    private boolean findWallpapers() {
        // bundled wallpapers are copied into the photo store on first run (see Wallpapers)
        wallpapers.clear();
        wallpapers.addAll(Wallpapers.userPhotos(this));
        return !wallpapers.isEmpty();
    }

    /** Decoded at screen size in RGB_565: about 1.8 MB each on a 720p TV. */
    private android.graphics.Bitmap loadWallpaper(Object src) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        decode(src, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= W && o.outHeight / (sample * 2) >= H) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
        return decode(src, o);
    }

    private android.graphics.Bitmap decode(Object src, android.graphics.BitmapFactory.Options o) {
        if (src instanceof Integer) return android.graphics.BitmapFactory.decodeResource(getResources(), (Integer) src, o);
        return android.graphics.BitmapFactory.decodeFile(((java.io.File) src).getPath(), o);
    }

    /** The phone page added, replaced, deleted or asked to show a photo: update the rotation now. */
    @Override
    public void onWallpapersChanged(java.io.File added) {
        if (wpViews[0] == null) return;
        Object current = wallpapers.isEmpty() ? null : wallpapers.get(Math.min(wpIdx, wallpapers.size() - 1));
        findWallpapers();
        if (wallpapers.isEmpty()) {
            for (ImageView v : wpViews) v.setImageDrawable(null);
            return;
        }
        int at = added != null ? wallpapers.indexOf(added) : wallpapers.indexOf(current);
        if (at < 0) {
            // the photo on screen was deleted: move on to the next one
            wpIdx = Math.max(0, wpIdx - 1) % wallpapers.size();
            main.removeCallbacks(nextWallpaper);
            nextWallpaper.run();
        } else if (added != null) {
            wpIdx = (at - 1 + wallpapers.size()) % wallpapers.size();
            main.removeCallbacks(nextWallpaper);
            nextWallpaper.run();
        } else {
            wpIdx = at;
        }
    }

    // ---------------------------------------------------------------- follower counters

    private final Runnable refreshSubs = () -> {
        main.removeCallbacks(this.refreshSubs);
        String[] ids = socialIds.clone();
        io.execute(() -> {
            String[] counts = {
                    ids[0].isEmpty() ? null : SocialCount.youtube(ids[0]),
                    ids[1].isEmpty() ? null : SocialCount.instagram(ids[1]),
                    ids[2].isEmpty() ? null : SocialCount.facebook(ids[2]),
            };
            main.post(() -> {
                boolean allOk = true;
                SharedPreferences.Editor e = prefs.edit();
                for (int i = 0; i < 3; i++) {
                    if (ids[i].isEmpty()) continue;
                    if (counts[i] == null) {
                        allOk = false;   // keep showing the last known number
                        continue;
                    }
                    e.putString("count_" + SOCIAL_KEYS[i], counts[i]);
                    showCount(i, counts[i]);
                }
                if (allOk) e.putLong("counts_at", System.currentTimeMillis());
                e.apply();
                // retry sooner after a failure (e.g. Wi-Fi still connecting after boot)
                if (hasWindowFocus()) main.postDelayed(this.refreshSubs, allOk ? SUBS_REFRESH_MS : 5 * 60_000L);
            });
        });
    };

    private void showCount(int i, String count) {
        if (socialPills[i] == null) return;
        socialPills[i].setVisibility(count == null ? View.GONE : View.VISIBLE);
        if (count != null) socialTexts[i].setText(count);
    }

    private void scheduleWallpaper() {
        main.removeCallbacks(nextWallpaper);
        if (wallpapers.size() < 2 || wpViews[0] == null) return;
        int secs = Math.max(5, tray.timerSeconds());   // set in the Settings tray, default from config.xml
        main.postDelayed(nextWallpaper, secs * 1000L);
    }

    /** Fades the next wallpaper in over the current one, then frees the old bitmap. */
    private final Runnable nextWallpaper = () -> {
        if (wallpapers.isEmpty()) return;
        wpIdx = (wpIdx + 1) % wallpapers.size();
        prefs.edit().putInt("wallpaper", wpIdx).apply();
        Object src = wallpapers.get(wpIdx);
        io.execute(() -> {
            android.graphics.Bitmap bmp = loadWallpaper(src);
            if (bmp == null) return;
            bmp.prepareToDraw();
            android.graphics.Bitmap blur = Frost.blurred(bmp);
            main.post(() -> {
                wallBlur = blur;
                if (shelfPkg == null && frost != null) frost.setSource(blur, 2000);
                crossfade(wpViews[0], wpViews[1], bmp, 2000, true);
                if (hasWindowFocus()) scheduleWallpaper();
            });
        });
    };

    @SuppressWarnings("deprecation")
    private void updateWifi() {
        String text = "Offline";
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni != null && ni.isConnected()) {
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
                text = "Ethernet";
            } else if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                text = "Wi-Fi";
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                String ssid = wi == null ? null : unquote(wi.getSSID());
                if ((ssid == null || ssid.equals("<unknown ssid>")) && wi != null) {
                    try {
                        List<WifiConfiguration> cfgs = wm.getConfiguredNetworks();
                        if (cfgs != null) for (WifiConfiguration cfg : cfgs) {
                            if (cfg.networkId == wi.getNetworkId()) ssid = unquote(cfg.SSID);
                        }
                    } catch (SecurityException ignored) {
                    }
                }
                if (ssid != null && !ssid.isEmpty() && !ssid.equals("<unknown ssid>")) text = ssid;
            }
        }
        // icon-only button: dim when offline, network name for accessibility
        wifiIcon.setAlpha(text.equals("Offline") ? 0.35f : 1f);
        tray.setWifi(text, !text.equals("Offline"));
        pills[1].setContentDescription(text);
    }

    private static String unquote(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK;
        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;

        boolean select = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (tray.isOpen()) {
            tray.onKey(k, select, e.getRepeatCount() > 0);
            return true;
        }
        if (inputs.isOpen()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) inputs.move(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) inputs.move(1);
            else if (select && e.getRepeatCount() == 0) {
                Intent i = inputs.selected();
                inputs.close();
                if (i != null) launch(i);
            } else inputs.close();   // back, left, right
            return true;
        }

        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); break;
            case KeyEvent.KEYCODE_BACK:
                if (zone != Zone.DOCK && dockAdapter.getItemCount() > 0) {
                    zone = Zone.DOCK;
                    applyFocus(true);
                }
                break;
            default:
                if (e.getRepeatCount() == 0) activate();
        }
        return true;
    }

    private void up() {
        if (zone == Zone.DOCK) {
            zone = recents.isEmpty() ? Zone.TOP : Zone.RECENT;
            recentIdx = 0;
        } else if (zone == Zone.RECENT) {
            zone = Zone.TOP;
        } else if (zone == Zone.GRID) {
            if (gridIdx >= COLS) gridIdx -= COLS;
            else if (dockAdapter.getItemCount() > 0) {
                zone = Zone.DOCK;
                dockIdx = Math.min(gridIdx, dockAdapter.getItemCount() - 1);
            } else zone = Zone.TOP;
        } else return;
        applyFocus(true);
    }

    private void down() {
        int ng = gridAdapter.getItemCount();
        if (zone == Zone.TOP || zone == Zone.RECENT) {
            if (dockAdapter.getItemCount() > 0) zone = Zone.DOCK;
            else if (ng > 0) zone = Zone.GRID;
            else return;
        } else if (zone == Zone.DOCK) {
            if (ng == 0) return;
            zone = Zone.GRID;
            gridIdx = Math.min(dockIdx, ng - 1);
        } else {
            if (gridIdx + COLS < ng) gridIdx += COLS;
            else if (gridIdx / COLS < (ng - 1) / COLS) gridIdx = ng - 1;
            else return;
        }
        applyFocus(true);
    }

    private void left() {
        if (zone == Zone.TOP && topIdx > 0) topIdx--;
        else if (zone == Zone.RECENT && recentIdx > 0) recentIdx--;
        else if (zone == Zone.DOCK && dockIdx > 0) dockIdx--;
        else if (zone == Zone.GRID && gridIdx % COLS > 0) gridIdx--;
        else return;
        applyFocus(true);
    }

    private void right() {
        if (zone == Zone.TOP && topIdx < PILLS - 1) topIdx++;
        else if (zone == Zone.RECENT && recentIdx < recents.size() - 1) recentIdx++;
        else if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount() - 1) dockIdx++;
        else if (zone == Zone.GRID && gridIdx % COLS < COLS - 1 && gridIdx + 1 < gridAdapter.getItemCount()) gridIdx++;
        else return;
        applyFocus(true);
    }

    private void activate() {
        if (zone == Zone.TOP) {
            if (topIdx == 0) {
                openInputs();
            } else if (topIdx == 1) {
                launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } else {
                openTray();
            }
        } else if (zone == Zone.RECENT && dockIdx < dockAdapter.getItemCount()) {
            // a history entry has no link to the exact video (dumpsys doesn't give one): open its app
            launch(AppRepo.launchIntent(getPackageManager(), dockAdapter.apps.get(dockIdx)));
        } else if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount()) {
            pressThen(dockRv, dockIdx, AppRepo.launchIntent(getPackageManager(), dockAdapter.apps.get(dockIdx)));
        } else if (zone == Zone.GRID && gridIdx < gridAdapter.getItemCount()) {
            pressThen(gridRv, gridIdx, AppRepo.launchIntent(getPackageManager(), gridAdapter.apps.get(gridIdx)));
        }
    }

    /** The focused tile presses in and springs back, as on Apple TV; the app opens on the way back. */
    private void pressThen(RecyclerView rv, int pos, Intent i) {
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
        if (card == null) {
            launch(i);
            return;
        }
        float up = TileAdapter.FOCUS_SCALE, down = up * 0.94f;
        card.animate().scaleX(down).scaleY(down).setDuration(90).withEndAction(() -> {
            launch(i);
            card.animate().scaleX(up).scaleY(up).setDuration(160)
                    .setInterpolator(new OvershootInterpolator(2f)).start();
        }).start();
    }

    private void openInputs() {
        int[] loc = new int[2];
        pills[0].getLocationInWindow(loc);
        // the menu is wider than the round button: keep it inside the right margin
        inputs.open(Math.min(loc[0], W - side - dp(200)), loc[1] + pills[0].getHeight() + dp(10));
    }

    /** The tray's glass is the screen behind it: drawn at 1/8 size and blurred (a few ms at 160x90). */
    private void openTray() {
        final int scale = 8;
        android.graphics.Bitmap shot = null;
        try {
            shot = android.graphics.Bitmap.createBitmap(Math.max(1, W / scale), Math.max(1, H / scale),
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(shot);
            cv.scale(1f / scale, 1f / scale);
            root.draw(cv);
            shot = Ui.blur(shot, 4, 3);
        } catch (Exception ignored) {
        }
        tray.open(shot, scale);
    }

    private final SettingsTray.Host trayHost = new SettingsTray.Host() {
        @Override
        public void launch(Intent i) {
            HomeActivity.this.launch(i);
        }

        @Override
        public void openInputs() {
            HomeActivity.this.openInputs();
        }

        @Override
        public void nextWallpaper() {
            main.removeCallbacks(HomeActivity.this.nextWallpaper);
            HomeActivity.this.nextWallpaper.run();
        }

        @Override
        public void wallpaperTimerChanged() {
            scheduleWallpaper();
        }
    };

    private void launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- focus rendering

    private void applyFocus(boolean animate) {
        prefs.edit().putInt("zone", zone.ordinal()).putInt("top", topIdx)
                .putInt("dock", dockIdx).putInt("grid", gridIdx).apply();

        dockAdapter.setFocused(dockRv, zone == Zone.DOCK ? dockIdx : -1);
        gridAdapter.setFocused(gridRv, zone == Zone.GRID ? gridIdx : -1);
        for (int i = 0; i < PILLS; i++) {
            float a = zone == Zone.TOP && i == topIdx ? 1f : 0.8f;
            pills[i].animate().alpha(a).setDuration(150).start();
        }

        // scroll: dock/top at rest, grid rows bring the grid up one row at a time
        float shift = 0;
        if (zone == Zone.GRID) {
            int row = gridIdx / COLS;
            shift = gridTop - dp(78) + Math.max(0, row - 1) * rowH;
        }
        if (animate) content.animate().translationY(-shift).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2f))
                .setUpdateListener(a -> band.invalidate())   // the frost samples what's behind it
                .start();
        else content.setTranslationY(-shift);
        band.invalidate();

        // Top Shelf: only while you're moving along the dock (Home always opens on the photos)
        main.removeCallbacks(shelfSettle);
        if (animate && zone == Zone.DOCK && dockAdapter.getItemCount() > 0) {
            // only once focus rests on an app: fast presses just move the frame
            main.postDelayed(shelfSettle, SHELF_SETTLE_MS);
        } else if (zone != Zone.DOCK) {
            hideShelf(450);
        }

        // dock title + glow follow the dock selection
        if (dockAdapter.getItemCount() > 0 && dockIdx != lastTitleApp) {
            lastTitleApp = dockIdx;
            AppRepo.App a = dockAdapter.apps.get(dockIdx);
            if (animate) {
                titleChanging = true;
                dockTitle.animate().alpha(0f).setDuration(90).withEndAction(() -> {
                    dockTitle.setText(a.label);
                    titleChanging = false;
                    dockTitle.animate().alpha(titleAlpha()).setDuration(200).start();
                }).start();
            } else {
                dockTitle.setText(a.label);
                dockTitle.setAlpha(titleAlpha());
            }
            setGlow(a.glow, animate);
            showRecents();
        } else if (!titleChanging) {
            dockTitle.animate().alpha(titleAlpha()).setDuration(200).start();
        }
        glow.animate().alpha(zone == Zone.GRID ? 0f : zone == Zone.TOP ? 0.6f : 1f).setDuration(300).start();

        RectF target = targetRect();
        boolean tile = zone == Zone.DOCK || zone == Zone.GRID;
        highlight.setLift(tile);
        if (target != null) moveHighlight(target, animate && hlPlaced);
    }

    // ---------------------------------------------------------------- Top Shelf

    private int shelfRes(String pkg) {
        return getResources().getIdentifier("shelf_" + pkg.replace('.', '_'), "drawable", getPackageName());
    }

    /** Loads every dock app's Top Shelf picture in the background, so switching never waits for one. */
    private void preloadShelves() {
        if (shelfViews[0] == null) return;
        for (AppRepo.App a : new java.util.ArrayList<>(dockAdapter.apps)) {
            int res = shelfRes(a.pkg);
            if (res == 0 || shelfCache.containsKey(a.pkg)) continue;
            io.execute(() -> {
                android.graphics.Bitmap[] pair = loadShelf(res);
                main.post(() -> {
                    if (!shelfCache.containsKey(a.pkg)) shelfCache.put(a.pkg, pair);
                });
            });
        }
    }

    /** {picture, blurred copy}; also uploads the picture to the GPU now, not mid-fade. Off the main thread. */
    private android.graphics.Bitmap[] loadShelf(int res) {
        android.graphics.Bitmap img = loadWallpaper(res);
        if (img != null) img.prepareToDraw();
        return new android.graphics.Bitmap[]{img, Frost.blurred(img)};
    }

    /** Focus has rested on a dock app: show its picture. */
    private void shelfSettled() {
        if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount()) showShelf(dockAdapter.apps.get(dockIdx).pkg);
    }

    private void showShelf(String pkg) {
        main.removeCallbacks(shelfIdle);
        main.postDelayed(shelfIdle, SHELF_IDLE_MS);
        if (pkg.equals(shelfPkg)) return;
        int res = shelfRes(pkg);
        if (res == 0) {
            hideShelf(450);
            return;
        }
        shelfPkg = pkg;
        android.graphics.Bitmap[] cached = shelfCache.get(pkg);
        if (cached != null) {
            putShelf(pkg, cached);
            return;
        }
        io.execute(() -> {
            android.graphics.Bitmap[] pair = loadShelf(res);
            main.post(() -> {
                shelfCache.put(pkg, pair);
                if (pkg.equals(shelfPkg)) putShelf(pkg, pair);
            });
        });
    }

    /** Crossfades a shelf picture in over the photos (or over the previous app's picture). */
    private void putShelf(String pkg, android.graphics.Bitmap[] pair) {
        if (pair[0] == null) return;
        boolean shelfShowing = shelfViews[1].getAlpha() > 0f && shelfViews[1].getDrawable() != null;
        crossfade(shelfViews[0], shelfViews[1], pair[0], 450, shelfShowing);
        if (frost != null) frost.setSource(pair[1], 450);
    }

    /**
     * Fades {@code img} in on {@code upper} (drawn above {@code lower}). The picture showing now is
     * first copied to {@code lower} at full opacity, so nothing behind (or the black window) shows
     * through mid-fade. A fade still running is finished at once. {@code keepCurrent} false:
     * nothing of this pair is showing, so the new picture fades in over whatever is below.
     * The draw order never changes, so no layout pass.
     */
    private void crossfade(ImageView lower, ImageView upper, android.graphics.Bitmap img, long ms, boolean keepCurrent) {
        lower.animate().cancel();
        upper.animate().cancel();
        android.graphics.Bitmap shown = keepCurrent ? bitmapOf(upper.getAlpha() > 0f ? upper : lower) : null;
        lower.setImageBitmap(shown);
        lower.setAlpha(shown != null ? 1f : 0f);
        upper.setImageBitmap(img);
        upper.setAlpha(0f);
        upper.animate().alpha(1f).setDuration(ms).withEndAction(() -> {
            lower.setAlpha(0f);
            lower.setImageDrawable(null);
        }).start();
    }

    private static android.graphics.Bitmap bitmapOf(ImageView v) {
        android.graphics.drawable.Drawable d = v.getDrawable();
        return d instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable) d).getBitmap() : null;
    }

    /** Back to the photos. */
    private void hideShelf(long ms) {
        main.removeCallbacks(shelfIdle);
        if (shelfPkg == null || shelfViews[0] == null) return;
        shelfPkg = null;
        for (ImageView v : shelfViews) {
            if (ms == 0) v.setAlpha(0f);
            else v.animate().alpha(0f).setDuration(ms).start();
        }
        if (frost != null && wallBlur != null) frost.setSource(wallBlur, ms);
    }

    private final Runnable shelfIdle = () -> hideShelf(900);

    private float titleAlpha() {
        return zone == Zone.TOP ? 0.55f : 1f;
    }

    private RectF targetRect() {
        if (zone == Zone.TOP) {
            View p = pills[topIdx];
            float x = pillRow.getLeft() + p.getLeft(), y = pillRow.getTop() + p.getTop();
            if (p.getWidth() == 0) return null;
            float o = dp(3);
            hlRadius = p.getHeight() / 2f + o;
            return new RectF(x - o, y - o, x + p.getWidth() + o, y + p.getHeight() + o);
        }
        if (zone == Zone.RECENT) {
            View card = recentCards[recentIdx];
            float x = recentRow.getLeft() + card.getLeft(), y = recentRow.getTop() + card.getTop();
            if (card.getWidth() == 0) return null;
            float o = dp(3);
            hlRadius = dp(15);
            return new RectF(x - o, y - o, x + card.getWidth() + o, y + card.getHeight() + o);
        }
        RecyclerView rv = zone == Zone.DOCK ? dockRv : gridRv;
        int pos = zone == Zone.DOCK ? dockIdx : gridIdx;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh == null) return null;
        View card = vh.itemView.findViewById(R.id.card);
        float cx = rv.getLeft() + vh.itemView.getLeft() + card.getLeft() + card.getWidth() / 2f;
        float cy = rv.getTop() + vh.itemView.getTop() + card.getTop() + card.getHeight() / 2f;
        float hw = card.getWidth() * TileAdapter.FOCUS_SCALE / 2f + dp(5);
        float hh = card.getHeight() * TileAdapter.FOCUS_SCALE / 2f + dp(5);
        hlRadius = dp(10) * TileAdapter.FOCUS_SCALE + dp(5);
        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    private void moveHighlight(RectF to, boolean animate) {
        if (hlAnim != null) hlAnim.cancel();
        final RectF from = new RectF(hl);
        final float r0 = hlRadiusNow, r1 = hlRadius;
        if (!animate) {
            hl.set(to);
            placeHighlight(hl, r1);
            highlight.animate().alpha(1f).setDuration(200).start();
            hlPlaced = true;
            return;
        }
        hlAnim = ValueAnimator.ofFloat(0f, 1f);
        hlAnim.setDuration(340);
        hlAnim.setInterpolator(new OvershootInterpolator(1.15f));
        highlight.animate().cancel();
        highlight.setAlpha(1f);
        hlAnim.addUpdateListener(va -> {
            float t = (float) va.getAnimatedValue();
            hl.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t);
            placeHighlight(hl, r0 + (r1 - r0) * Math.min(1f, t));
        });
        hlAnim.start();
    }

    private void placeHighlight(RectF r, float radius) {
        hlRadiusNow = radius;
        highlight.set(r, radius);
    }

    private void setGlow(int color, boolean animate) {
        if (glowAnim != null) glowAnim.cancel();
        int from = glowColor == 0 ? (color & 0x00FFFFFF) : glowColor;
        glowColor = color;
        if (!animate) {
            paintGlow(color);
            return;
        }
        glowAnim = ValueAnimator.ofArgb(from, color);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(va -> paintGlow((int) va.getAnimatedValue()));
        glowAnim.start();
    }

    private void paintGlow(int c) {
        int rgb = c & 0x00FFFFFF;
        int a = (c >>> 24) * 0x5C / 0xFF;
        glowBg.setColors(new int[]{(a << 24) | rgb, ((a / 3) << 24) | rgb, rgb});
    }
}
