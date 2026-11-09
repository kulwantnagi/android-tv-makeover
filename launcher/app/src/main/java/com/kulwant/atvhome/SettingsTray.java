package com.kulwant.atvhome;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Apple TV style Control Centre: a frosted-glass panel that slides in from the right. The glass is a
 * blurred snapshot of whatever is behind the panel, tinted, with a light edge. D-pad: Up/Down move
 * between rows, Left/Right between cells (on the volume slider they change the volume), Back closes.
 */
final class SettingsTray {

    interface Host {
        void launch(Intent i);
        void openInputs();
        void nextWallpaper();
        /** the photo timer changed: reschedule the rotation */
        void wallpaperTimerChanged();
    }

    /** photo timer choices, seconds */
    private static final int[] TIMERS = {15, 30, 60, 300, 900};
    private static final int BLUE = 0xFF0A84FF;

    final View scrim;
    final FrameLayout panel;
    private final Context c;
    private final Host host;
    private final SharedPreferences prefs;
    private final AudioManager audio;
    private final int W, H, panelW, margin, pad;

    /** focusable cells by row; the volume slider is a row of its own */
    private final List<View[]> rows = new ArrayList<>();
    private int row, col;
    private View volumeRow, volumeFill;
    private ImageView volumeIcon, wifiCircle, btCircle;
    private TextView volumeText, wifiSub, btSub, timerSub, historySub;
    private boolean open;

    SettingsTray(Context c, int W, int H, Host host) {
        this.c = c;
        this.W = W;
        this.H = H;
        this.host = host;
        prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        panelW = dp(360);
        margin = dp(24);
        pad = dp(20);

        scrim = new View(c);
        scrim.setBackgroundColor(0x66000000);
        scrim.setVisibility(View.GONE);

        panel = new FrameLayout(c);
        panel.setVisibility(View.GONE);
        panel.setElevation(dp(24));
        final float radius = dp(30);
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        panel.setClipToOutline(true);
        // light top edge and hairline border, drawn over the glass
        GradientDrawable edge = Ui.rounded(0, radius, Math.max(1, dp(1)), 0x4DFFFFFF);
        panel.setForeground(edge);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(panelW, H - 2 * margin, Gravity.END | Gravity.TOP);
        plp.topMargin = margin;
        plp.rightMargin = margin;
        panel.setLayoutParams(plp);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(22), pad, pad);
        col.setClipToPadding(false);   // the focused cell grows 4% past the padding
        panel.addView(col, new FrameLayout.LayoutParams(-1, -1));
        int inner = panelW - 2 * pad, gap = dp(12), half = (inner - gap) / 2;

        // header
        TextView title = Ui.text(c, 600, 24, 0xFFFFFFFF);
        title.setText("Settings");
        col.addView(title);

        // 2 x 2 toggle tiles
        TextView[] sub = new TextView[1];
        ImageView[] circle = new ImageView[1];
        View wifi = tile(R.drawable.ic_wifi, "Wi-Fi", sub, circle);
        wifiSub = sub[0];
        wifiCircle = circle[0];
        View bt = tile(R.drawable.ic_bluetooth, "Bluetooth", sub, circle);
        btSub = sub[0];
        btCircle = circle[0];
        View inputs = tile(R.drawable.ic_input, "Inputs", sub, circle);
        sub[0].setText("HDMI · AV");
        View off = tile(R.drawable.ic_power, "Turn off", sub, circle);
        sub[0].setText("Screen off");
        addRow(col, dp(14), dp(60), half, gap, wifi, bt);
        addRow(col, dp(10), dp(60), half, gap, inputs, off);

        // volume slider
        volumeRow = slider(inner);
        addRow(col, dp(14), dp(44), inner, 0, volumeRow);

        // wallpaper
        TextView section = Ui.text(c, 600, 12, 0x99FFFFFF);
        section.setText("WALLPAPER");
        section.setLetterSpacing(0.08f);
        col.addView(section, margins(-2, -2, dp(14), 0));
        View next = cell(R.drawable.ic_image, "Next photo", null, false);
        View timer = cell(R.drawable.ic_timer, "Change every", sub, false);
        timerSub = sub[0];
        addRow(col, dp(8), dp(48), half, gap, next, timer);

        // history, all settings
        View history = cell(R.drawable.ic_delete, "Clear watch history", sub, false);
        historySub = sub[0];
        addRow(col, dp(10), dp(44), inner, 0, history);
        View all = cell(R.drawable.ic_settings, "All settings", null, true);
        addRow(col, dp(10), dp(44), inner, 0, all);
    }

    boolean isOpen() {
        return open;
    }

    /** backdrop: the screen behind the panel, already blurred, at 1/scale size */
    void open(Bitmap backdrop, int scale) {
        open = true;
        row = 0;
        col = 0;
        setGlass(backdrop, scale);
        refresh();
        focus();
        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(250).start();
        panel.setVisibility(View.VISIBLE);
        panel.setTranslationX(panelW + margin);
        panel.setAlpha(0.6f);
        panel.animate().translationX(0).alpha(1f).setDuration(320)
                .setInterpolator(new DecelerateInterpolator(2.2f)).start();
        c.registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
    }

    void close() {
        if (!open) return;
        open = false;
        try {
            c.unregisterReceiver(volumeReceiver);
        } catch (Exception ignored) {
        }
        scrim.animate().alpha(0f).setDuration(200).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        panel.animate().translationX(panelW + margin).alpha(0.6f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }

    /** Called by HomeActivity whenever the network changes. */
    void setWifi(String text, boolean connected) {
        wifiSub.setText(text);
        setCircle(wifiCircle, connected);
    }

    // ---------------------------------------------------------------- keys

    void onKey(int k, boolean select, boolean repeat) {
        View[] r = rows.get(row);
        boolean slider = r[0] == volumeRow;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row > 0) row--;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row < rows.size() - 1) row++;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (slider) volume(-1);
                else if (col > 0) col--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (slider) volume(1);
                else if (col < r.length - 1) col++;
                break;
            case KeyEvent.KEYCODE_BACK:
                close();
                return;
            default:
                if (select && !repeat) activate(rows.get(row)[Math.min(col, r.length - 1)]);
                return;
        }
        col = Math.min(col, rows.get(row).length - 1);
        focus();
    }

    private void activate(View v) {
        int i = rowsIndexOf(v);
        switch (i) {
            case 0:   // Wi-Fi
                close();
                host.launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
                break;
            case 1:   // Bluetooth
                close();
                host.launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                break;
            case 2:   // Inputs
                close();
                host.openInputs();
                break;
            case 3:   // Turn off: the screen sleeps like the remote's power key (needs the TV's ADB)
                close();
                new Thread(() -> {
                    try {
                        LocalAdb.shell(c, "input keyevent KEYCODE_SLEEP");
                    } catch (Exception ignored) {
                    }
                }).start();
                break;
            case 4:   // volume: mute / unmute
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                refreshVolume();
                break;
            case 5:   // next photo
                host.nextWallpaper();
                break;
            case 6: { // photo timer: cycle through TIMERS
                int now = timerSeconds(), next = TIMERS[0];
                for (int t : TIMERS) if (t > now) { next = t; break; }
                prefs.edit().putInt("wallpaper_seconds", next).apply();
                timerSub.setText(timerLabel(next));
                host.wallpaperTimerChanged();
                break;
            }
            case 7:   // clear watch history
                new Thread(() -> PlayHistory.clear(c.getApplicationContext())).start();
                historySub.setText("Cleared");
                break;
            case 8:
                close();
                host.launch(new Intent(Settings.ACTION_SETTINGS));
                break;
        }
    }

    /** Cells in reading order: 0 Wi-Fi, 1 Bluetooth, 2 Inputs, 3 Turn off, 4 volume, 5 next photo, ... */
    private int rowsIndexOf(View v) {
        int i = 0;
        for (View[] r : rows) for (View x : r) {
            if (x == v) return i;
            i++;
        }
        return -1;
    }

    // ---------------------------------------------------------------- state

    int timerSeconds() {
        return prefs.getInt("wallpaper_seconds", c.getResources().getInteger(R.integer.wallpaper_seconds));
    }

    private static String timerLabel(int s) {
        return s < 60 ? s + " seconds" : s / 60 + (s == 60 ? " minute" : " minutes");
    }

    private void refresh() {
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        boolean btOn = ba != null && ba.isEnabled();
        btSub.setText(ba == null ? "Not available" : btOn ? "On" : "Off");
        setCircle(btCircle, btOn);
        timerSub.setText(timerLabel(timerSeconds()));
        historySub.setText("Last played rows above the dock");
        refreshVolume();
    }

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context x, Intent i) {
            refreshVolume();
        }
    };

    private void volume(int dir) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int step = Math.max(1, max / 20);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, now + dir * step)), 0);
        refreshVolume();
    }

    private void refreshVolume() {
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC) || now == 0;
        float f = muted ? 0f : now / (float) max;
        int trackW = panelW - 2 * pad, h = dp(44);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) volumeFill.getLayoutParams();
        lp.width = f == 0 ? 0 : Math.max(h, Math.round(trackW * f));   // never narrower than its round ends
        volumeFill.setLayoutParams(lp);
        volumeIcon.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume);
        // icon and number turn dark where the white fill is under them
        volumeIcon.setColorFilter(lp.width > dp(40) ? 0xFF1C1C1E : 0xFFFFFFFF);
        volumeText.setText(muted ? "Muted" : Math.round(f * 100) + "%");
        volumeText.setTextColor(lp.width > trackW - dp(56) ? 0xFF1C1C1E : 0xFFFFFFFF);
    }

    // ---------------------------------------------------------------- look

    /** Frosted glass: the blurred screen behind the panel, darkened a little, then a faint white sheen. */
    private void setGlass(Bitmap backdrop, int scale) {
        Drawable glass;
        if (backdrop != null) {
            int x = (W - margin - panelW) / scale, y = margin / scale;
            int w = Math.min(panelW / scale, backdrop.getWidth() - x), h = Math.min((H - 2 * margin) / scale, backdrop.getHeight() - y);
            BitmapDrawable bd = new BitmapDrawable(c.getResources(), Bitmap.createBitmap(backdrop, x, y, w, h));
            bd.setFilterBitmap(true);
            glass = bd;
        } else {
            glass = new GradientDrawable();
            ((GradientDrawable) glass).setColor(0xFF2C2C2E);
        }
        GradientDrawable tint = new GradientDrawable();
        tint.setColor(0x52000000);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2EFFFFFF, 0x14FFFFFF, 0x0AFFFFFF});
        panel.setBackground(new LayerDrawable(new Drawable[]{glass, tint, sheen}));
    }

    private void focus() {
        for (int r = 0; r < rows.size(); r++) {
            View[] cells = rows.get(r);
            for (int i = 0; i < cells.length; i++) {
                boolean f = r == row && i == col;
                View v = cells[i];
                float radius = (Float) v.getTag();
                v.setBackground(f ? Ui.rounded(0x52FFFFFF, radius, dp(1.5f), 0xB3FFFFFF)
                        : Ui.rounded(0x1FFFFFFF, radius, Math.max(1, dp(1)), 0x1FFFFFFF));
                float s = f ? 1.04f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(160).start();
            }
        }
    }

    private void setCircle(ImageView circle, boolean on) {
        // Apple style: an active toggle is a white disc with a blue glyph
        circle.setBackground(Ui.rounded(on ? 0xFFFFFFFF : 0x33FFFFFF, dp(17), 0, 0));
        circle.setColorFilter(on ? BLUE : 0xFFFFFFFF);
    }

    private View tile(int icon, String label, TextView[] subOut, ImageView[] circleOut) {
        // Control Centre tile: round toggle on the left, name and state on the right
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), 0, dp(10), 0);
        t.setTag((float) dp(20));
        ImageView ic = new ImageView(c);
        ic.setImageResource(icon);
        ic.setPadding(dp(8), dp(8), dp(8), dp(8));
        setCircle(ic, false);
        t.addView(ic, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView l = Ui.text(c, 600, 14, 0xFFFFFFFF);
        l.setText(label);
        texts.addView(l);
        TextView s = Ui.text(c, 400, 11, 0x99FFFFFF);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(s, margins(-1, -2, dp(3), 0));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(10);
        t.addView(texts, tlp);
        subOut[0] = s;
        circleOut[0] = ic;
        return t;
    }

    private View cell(int icon, String label, TextView[] subOut, boolean chevron) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(12), 0);
        r.setTag((float) dp(16));
        ImageView ic = new ImageView(c);
        ic.setImageResource(icon);
        r.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView l = Ui.text(c, 500, 14, 0xFFFFFFFF);
        l.setText(label);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(l);
        if (subOut != null) {
            TextView s = Ui.text(c, 400, 11, 0x99FFFFFF);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(s, margins(-2, -2, dp(2), 0));
            subOut[0] = s;
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(12);
        r.addView(texts, tlp);
        if (chevron) {
            ImageView ch = new ImageView(c);
            ch.setImageResource(R.drawable.ic_chevron);
            ch.setAlpha(0.6f);
            r.addView(ch, new LinearLayout.LayoutParams(dp(18), dp(18)));
        }
        return r;
    }

    /** Capsule slider like Control Centre's: white fill grows from the left, icon inside. */
    private View slider(int width) {
        FrameLayout s = new FrameLayout(c);
        s.setTag((float) dp(22));
        s.setClipToOutline(true);
        final float radius = dp(22);
        s.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        volumeFill = new View(c);
        volumeFill.setBackground(Ui.rounded(0xF2FFFFFF, radius, 0, 0));
        s.addView(volumeFill, new FrameLayout.LayoutParams(0, -1));
        volumeIcon = new ImageView(c);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER_VERTICAL);
        ilp.leftMargin = dp(14);
        s.addView(volumeIcon, ilp);
        volumeText = Ui.text(c, 600, 13, 0xFFFFFFFF);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.END);
        vlp.rightMargin = dp(16);
        s.addView(volumeText, vlp);
        return s;
    }

    private void addRow(LinearLayout parent, int top, int h, int cellW, int gap, View... cells) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        for (int i = 0; i < cells.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellW, h);
            if (i > 0) lp.leftMargin = gap;
            r.addView(cells[i], lp);
        }
        parent.addView(r, margins(-1, -2, top, 0));
        parent.setClipChildren(false);
        rows.add(cells);
    }

    private static LinearLayout.LayoutParams margins(int w, int h, int top, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = top;
        lp.leftMargin = left;
        return lp;
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }
}
