package com.oneui.launcher;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.hardware.camera2.*;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class MainActivity extends Activity {
    static final int ACCENT = 0xFF3E91FF;

    static class App { String label, pkg; Drawable icon; Intent intent; }
    static class Folder { String name; List<String> pkgs = new ArrayList<>(); }

    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final List<Folder> folders = new ArrayList<>();
    private SharedPreferences prefs;
    private boolean dark = true;
    private int state = 0; // 0 = home, 1 = drawer, 2 = quick panel
    private FrameLayout root;
    private View drawer, qs;
    private GridView drawerGrid, folderGrid;
    private EditText search;
    private BaseAdapter drawerAdapter, folderAdapter;
    private LinearLayout dock;
    private GestureDetector gd;
    private boolean torch;
    private CameraManager cm;
    private String camId;
    private int qsKey;
    private String shape = "squircle";
    private int iconDp = 60;
    private int cols = 4;
    private int rows = 0; // 0 = auto
    private boolean showLabels = true;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private int screenH() { return getResources().getDisplayMetrics().heightPixels; }
    private int panelColor() { return dark ? 0xF21C1C1E : 0xF2F2F2F7; }
    private int textColor() { return dark ? Color.WHITE : 0xFF1C1C1E; }
    private int tileColor() { return dark ? 0xFF2C2C2E : Color.WHITE; }

    // ---------------------------------------------------------------- lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("oneui", MODE_PRIVATE);
        dark = prefs.getBoolean("dark", true);
        shape = prefs.getString("shape", "squircle");
        iconDp = prefs.getInt("iconDp", 60);
        cols = prefs.getInt("cols", 4);
        rows = prefs.getInt("rows", 0);
        showLabels = prefs.getBoolean("labels", true);
        loadFolders();
        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dy = e2.getY() - e1.getY(), dx = e2.getX() - e1.getX();
                if (Math.abs(dy) < dp(80) || Math.abs(dy) < Math.abs(dx)) return false;
                if (dy < 0) {
                    if (state == 0) go(1); else if (state == 2) go(0);
                } else {
                    if (state == 0) go(2);
                    else if (state == 1 && !drawerGrid.canScrollVertically(-1)) go(0);
                }
                return true;
            }
        });
        buildUI();
    }

    @Override protected void onResume() {
        super.onResume();
        NotifService.onChange = () -> {
            drawerAdapter.notifyDataSetChanged();
            folderAdapter.notifyDataSetChanged();
            buildDock();
        };
        if (!notifEnabled()) NotifService.counts = new HashMap<>();
        if (stateKey() != qsKey) rebuildQs();
        loadApps();
        buildDock();
        folderAdapter.notifyDataSetChanged();
    }

    @Override protected void onPause() {
        super.onPause();
        NotifService.onChange = null;
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); go(0); }

    @Override public void onBackPressed() { if (state != 0) go(0); }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        gd.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    // ---------------------------------------------------------------- UI building
    private void buildUI() {
        root = new FrameLayout(this);
        root.addView(buildHome(), new FrameLayout.LayoutParams(-1, -1));
        drawer = buildDrawer();
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-1, -1);
        dl.topMargin = dp(70);
        root.addView(drawer, dl);
        qs = buildQs();
        root.addView(qs, new FrameLayout.LayoutParams(-1, -2));
        drawer.setTranslationY(screenH());
        qs.setTranslationY(-screenH());
        state = 0;
        setContentView(root);
    }

    private View buildHome() {
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setPadding(dp(16), dp(52), dp(16), dp(24));

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm"); time.setFormat24Hour("HH:mm");
        time.setTextSize(64); time.setTextColor(Color.WHITE);
        time.setGravity(Gravity.CENTER);
        time.setShadowLayer(8, 0, 2, 0x66000000);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM"); date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(16); date.setTextColor(0xDDFFFFFF);
        date.setGravity(Gravity.CENTER);
        date.setShadowLayer(6, 0, 1, 0x66000000);
        home.addView(time); home.addView(date);

        folderGrid = new GridView(this);
        folderGrid.setNumColumns(cols);
        folderGrid.setVerticalSpacing(rows == 0 ? dp(14) : 0);
        folderGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        folderAdapter = new BaseAdapter() {
            public int getCount() { return folders.size() + 1; }
            public Object getItem(int i) { return i; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                return sized(i < folders.size() ? folderTile(folders.get(i)) : plusTile(), folderGrid);
            }
        };
        folderGrid.setAdapter(folderAdapter);
        folderGrid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (rows > 0 && (b - t) != (ob - ot)) v.post(() -> folderAdapter.notifyDataSetChanged());
        });
        folderGrid.setOnItemClickListener((p, v, i, id) -> {
            if (i < folders.size()) openFolder(folders.get(i));
            else prompt("New folder", "", n -> { Folder f = new Folder(); f.name = n; folders.add(f); saveFolders(); folderAdapter.notifyDataSetChanged(); });
        });
        folderGrid.setOnItemLongClickListener((p, v, i, id) -> {
            if (i >= folders.size()) return false;
            folderMenu(folders.get(i));
            return true;
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, 0, 1f);
        fp.topMargin = dp(24);
        home.addView(folderGrid, fp);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(8), dp(12), dp(8), dp(12));
        dock.setBackground(round(0x44FFFFFF, 32));
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(-1, -2);
        dp2.topMargin = dp(8);
        home.addView(dock, dp2);

        View hint = new View(this);
        hint.setBackground(round(0x77FFFFFF, 3));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(40), dp(5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(12);
        home.addView(hint, hp);
        return home;
    }

    private View buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackground(corners(panelColor(), 32, true));
        d.setPadding(dp(16), dp(12), dp(16), dp(24));
        d.setClickable(true);

        View handle = new View(this);
        handle.setBackground(round(0x66888888, 3));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(40), dp(5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(12);
        d.addView(handle, hp);

        search = new EditText(this);
        search.setHint("Search apps");
        search.setHintTextColor(0x99888888);
        search.setTextColor(textColor());
        search.setSingleLine(true);
        search.setPadding(dp(20), dp(12), dp(20), dp(12));
        search.setBackground(round(tileColor(), 24));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { filter(s.toString()); }
            public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.bottomMargin = dp(12);
        d.addView(search, sp);

        drawerGrid = new GridView(this);
        drawerGrid.setNumColumns(cols);
        drawerGrid.setVerticalSpacing(rows == 0 ? dp(14) : 0);
        drawerGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        drawerGrid.setVerticalScrollBarEnabled(false);
        drawerAdapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) { return sized(cell(shown.get(i), true, textColor()), drawerGrid); }
        };
        drawerGrid.setAdapter(drawerAdapter);
        drawerGrid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (rows > 0 && (b - t) != (ob - ot)) v.post(() -> drawerAdapter.notifyDataSetChanged());
        });
        drawerGrid.setOnItemClickListener((p, v, i, id) -> { App a = shown.get(i); go(0); launch(a.intent); });
        drawerGrid.setOnItemLongClickListener((p, v, i, id) -> { appMenu(shown.get(i)); return true; });
        d.addView(drawerGrid, new LinearLayout.LayoutParams(-1, 0, 1f));
        return d;
    }

    private View buildQs() {
        LinearLayout q = new LinearLayout(this);
        q.setOrientation(LinearLayout.VERTICAL);
        q.setBackground(corners(panelColor(), 32, false));
        q.setPadding(dp(14), dp(52), dp(14), dp(20));
        q.setClickable(true);

        TextView title = new TextView(this);
        title.setText("Quick panel");
        title.setTextColor(textColor()); title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, 0, dp(12));
        q.addView(title);

        View internet = tile("📶", "Internet", false, v -> { go(0); safeStart(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); });
        View bt = tile("🔵", "Bluetooth", false, v -> { go(0); safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); });
        View flash = tile("🔦", "Flashlight", torch, v -> toggleTorch(v));
        View mode = tile("🌙", dark ? "Dark" : "Light", dark, v -> toggleDark());
        View sound = tile("🔔", "Sound", false, v -> { go(0); safeStart(new Intent(Settings.ACTION_SOUND_SETTINGS)); });
        View settings = tile("⚙️", "Settings", false, v -> { go(0); safeStart(new Intent(Settings.ACTION_SETTINGS)); });
        q.addView(row(internet, bt, flash));
        q.addView(row(mode, sound, settings));
        View icons = tile("🎨", "Icons & grid", false, v -> iconSettings());
        View wall = tile("🖼️", "Wallpaper", false, v -> { go(0); safeStart(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")); });
        View homeSet = tile("🏠", "Default home", false, v -> { go(0); safeStart(new Intent(Settings.ACTION_HOME_SETTINGS)); });
        q.addView(row(icons, wall, homeSet));

        TextView vl = new TextView(this);
        vl.setText("🔊  Media volume");
        vl.setTextColor(textColor()); vl.setTextSize(14);
        vl.setPadding(dp(8), dp(16), 0, dp(4));
        q.addView(vl);
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        SeekBar sb = new SeekBar(this);
        sb.setMax(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        sb.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        q.addView(sb);

        TextView bl = new TextView(this);
        bl.setText("☀️  Brightness");
        bl.setTextColor(textColor()); bl.setTextSize(14);
        bl.setPadding(dp(8), dp(12), 0, dp(4));
        q.addView(bl);
        if (Settings.System.canWrite(this)) {
            final android.content.ContentResolver cr = getContentResolver();
            SeekBar bs = new SeekBar(this);
            bs.setMax(254);
            int cur = 128;
            try { cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS); } catch (Exception ignored) {}
            bs.setProgress(Math.max(0, Math.min(254, cur - 1)));
            bs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (!fromUser) return;
                    try {
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, p + 1);
                    } catch (Exception ignored) {}
                }
                public void onStartTrackingTouch(SeekBar s) {}
                public void onStopTrackingTouch(SeekBar s) {}
            });
            q.addView(bs);
        } else {
            q.addView(linkText("Tap to allow brightness control", v ->
                safeStart(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())))));
        }
        if (!notifEnabled()) {
            q.addView(linkText("🔴  Tap to enable app badges", v ->
                safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        }
        qsKey = stateKey();
        return q;
    }

    private TextView linkText(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(ACCENT); t.setTextSize(14);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private boolean notifEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private int stateKey() {
        return (Settings.System.canWrite(this) ? 1 : 0) | (notifEnabled() ? 2 : 0);
    }

    private void rebuildQs() {
        boolean open = state == 2;
        root.removeView(qs);
        qs = buildQs();
        root.addView(qs, new FrameLayout.LayoutParams(-1, -2));
        qs.setTranslationY(open ? 0 : -screenH());
    }

    private int iconPx() {
        int colDp = (getResources().getConfiguration().screenWidthDp - 32) / cols;
        return dp(Math.min(iconDp, colDp - 8));
    }

    private View sized(View c, GridView g) {
        if (rows > 0) {
            int h = g.getHeight() > 0 ? g.getHeight() : screenH() * 55 / 100;
            int min = iconPx() + (showLabels ? dp(30) : dp(8));
            c.setLayoutParams(new AbsListView.LayoutParams(-1, Math.max(h / rows, min)));
            if (c instanceof LinearLayout) ((LinearLayout) c).setGravity(Gravity.CENTER);
        }
        return c;
    }

    private SeekBar seek(int max, int progress, IntConsumer change) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(progress);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar x, int p, boolean fromUser) { if (fromUser) change.accept(p); }
            public void onStartTrackingTouch(SeekBar x) {}
            public void onStopTrackingTouch(SeekBar x) {}
        });
        return sb;
    }

    private TextView sectionLabel(String t) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextColor(textColor()); v.setTextSize(14);
        v.setPadding(0, dp(20), 0, dp(4));
        return v;
    }

    private Drawable copy(Drawable d) {
        if (d == null) return null;
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(getResources()).mutate() : d;
    }

    private View iconView(Drawable icon, int px) {
        View result = null;
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) icon;
            Drawable bg = copy(ad.getBackground()), fg = copy(ad.getForeground());
            if (bg != null && fg != null) {
                LayerDrawable ld = new LayerDrawable(new Drawable[]{bg, fg});
                int in = -(int) (px * 0.195f);
                ld.setLayerInset(0, in, in, in, in);
                ld.setLayerInset(1, in, in, in, in);
                View v = new View(this);
                v.setBackground(ld);
                result = v;
            }
        }
        if (result == null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            int pad = (int) (px * 0.13f);
            iv.setPadding(pad, pad, pad, pad);
            iv.setBackgroundColor(0xFFF2F2F7);
            result = iv;
        }
        result.setOutlineProvider(shapeOutline(shape));
        result.setClipToOutline(true);
        return result;
    }

    private ViewOutlineProvider shapeOutline(final String sh) {
        return new ViewOutlineProvider() {
            public void getOutline(View v, Outline o) {
                int w = v.getWidth(), h = v.getHeight();
                if (w == 0 || h == 0) return;
                try {
                    switch (sh) {
                        case "circle": o.setRoundRect(0, 0, w, h, Math.min(w, h) / 2f); break;
                        case "rounded": o.setRoundRect(0, 0, w, h, w * 0.28f); break;
                        case "square": o.setRoundRect(0, 0, w, h, w * 0.08f); break;
                        case "teardrop": {
                            Path p = new Path();
                            float r = w / 2f, sm = w * 0.1f;
                            p.addRoundRect(new RectF(0, 0, w, h), new float[]{r, r, sm, sm, r, r, r, r}, Path.Direction.CW);
                            o.setConvexPath(p);
                            break;
                        }
                        default: {
                            Path p = new Path();
                            int N = 96;
                            float cx = w / 2f, cy = h / 2f;
                            for (int i = 0; i < N; i++) {
                                double t = 2 * Math.PI * i / N;
                                double co = Math.cos(t), si = Math.sin(t);
                                float x = (float) (cx + cx * Math.signum(co) * Math.sqrt(Math.abs(co)));
                                float y = (float) (cy + cy * Math.signum(si) * Math.sqrt(Math.abs(si)));
                                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                            }
                            p.close();
                            o.setConvexPath(p);
                        }
                    }
                } catch (Exception e) {
                    o.setRoundRect(0, 0, w, h, w * 0.3f);
                }
            }
        };
    }

    private void refreshIcons() {
        int sp = rows == 0 ? dp(14) : 0;
        folderGrid.setNumColumns(cols);
        drawerGrid.setNumColumns(cols);
        folderGrid.setVerticalSpacing(sp);
        drawerGrid.setVerticalSpacing(sp);
        drawerAdapter.notifyDataSetChanged();
        folderAdapter.notifyDataSetChanged();
        buildDock();
    }

    private void iconSettings() {
        go(0);
        final String[] keys = {"circle", "squircle", "rounded", "teardrop", "square"};
        final String[] names = {"Circle", "Squircle", "Rounded", "Teardrop", "Square"};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(12));

        TextView title = new TextView(this);
        title.setText("Icons & grid"); title.setTextSize(20); title.setTextColor(textColor());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(14));
        box.addView(title);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        final View[] prev = new View[keys.length];
        for (int i = 0; i < keys.length; i++) {
            final int idx = i;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            View pv = new View(this);
            pv.setBackgroundColor(keys[i].equals(shape) ? ACCENT : 0xFF8E8E93);
            pv.setOutlineProvider(shapeOutline(keys[i]));
            pv.setClipToOutline(true);
            prev[i] = pv;
            col.addView(pv, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView cap = caption(names[i], textColor());
            cap.setTextSize(10);
            col.addView(cap);
            col.setOnClickListener(v -> {
                shape = keys[idx];
                prefs.edit().putString("shape", shape).apply();
                for (int k = 0; k < keys.length; k++) prev[k].setBackgroundColor(k == idx ? ACCENT : 0xFF8E8E93);
                refreshIcons();
            });
            chips.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        box.addView(chips);

        final TextView sizeLabel = new TextView(this);
        sizeLabel.setText("Icon size: " + iconDp);
        sizeLabel.setTextColor(textColor()); sizeLabel.setTextSize(14);
        sizeLabel.setPadding(0, dp(20), 0, dp(4));
        box.addView(sizeLabel);
        SeekBar sb2 = new SeekBar(this);
        sb2.setMax(28);
        sb2.setProgress(iconDp - 44);
        sb2.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                iconDp = 44 + p;
                sizeLabel.setText("Icon size: " + iconDp);
                prefs.edit().putInt("iconDp", iconDp).apply();
                refreshIcons();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(sb2);

        final TextView colLabel = sectionLabel("Columns: " + cols);
        box.addView(colLabel);
        box.addView(seek(3, cols - 3, pr -> {
            cols = 3 + pr;
            colLabel.setText("Columns: " + cols);
            prefs.edit().putInt("cols", cols).apply();
            refreshIcons();
        }));

        final TextView rowLabel = sectionLabel("Rows: " + (rows == 0 ? "Auto" : String.valueOf(rows)));
        box.addView(rowLabel);
        box.addView(seek(6, rows == 0 ? 0 : rows - 2, pr -> {
            rows = pr == 0 ? 0 : pr + 2;
            rowLabel.setText("Rows: " + (rows == 0 ? "Auto" : String.valueOf(rows)));
            prefs.edit().putInt("rows", rows).apply();
            refreshIcons();
        }));

        Switch sw = new Switch(this);
        sw.setText("Show icon labels");
        sw.setTextColor(textColor());
        sw.setChecked(showLabels);
        sw.setPadding(0, dp(20), 0, dp(8));
        sw.setOnCheckedChangeListener((bv, on) -> {
            showLabels = on;
            prefs.edit().putBoolean("labels", on).apply();
            refreshIcons();
        });
        box.addView(sw);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(this).setView(sv)
            .setPositiveButton("Done", null).create();
        dlg.getWindow().setBackgroundDrawable(round(panelColor() | 0xFF000000, 28));
        dlg.show();
    }

    private int count(String pkg) {
        Integer c = NotifService.counts.get(pkg);
        return c == null ? 0 : c;
    }

    private FrameLayout badged(View content, int n) {
        FrameLayout fl = new FrameLayout(this);
        fl.addView(content, new FrameLayout.LayoutParams(-1, -1));
        if (n > 0) {
            TextView b = new TextView(this);
            b.setText(n > 99 ? "99+" : String.valueOf(n));
            b.setTextColor(Color.WHITE); b.setTextSize(10);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(dp(18)); b.setMinHeight(dp(18));
            b.setPadding(dp(4), 0, dp(4), 0);
            b.setBackground(round(0xFFFF3B30, 9));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.setMargins(0, dp(2), dp(2), 0);
            fl.addView(b, lp);
        }
        return fl;
    }

    private View tile(String icon, String label, boolean active, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        TextView i = new TextView(this);
        i.setText(icon); i.setTextSize(26); i.setGravity(Gravity.CENTER);
        TextView n = new TextView(this);
        n.setText(label); n.setTextSize(12); n.setGravity(Gravity.CENTER);
        t.addView(i); t.addView(n);
        setTileActive(t, active);
        t.setOnClickListener(l);
        return t;
    }

    private void setTileActive(View t, boolean on) {
        LinearLayout l = (LinearLayout) t;
        l.setBackground(round(on ? ACCENT : tileColor(), 20));
        ((TextView) l.getChildAt(1)).setTextColor(on ? Color.WHITE : textColor());
    }

    private LinearLayout row(View... tiles) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View t : tiles) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(84), 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            r.addView(t, lp);
        }
        return r;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable corners(int color, int radiusDp, boolean top) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = dp(radiusDp);
        g.setCornerRadii(top ? new float[]{r, r, r, r, 0, 0, 0, 0} : new float[]{0, 0, 0, 0, r, r, r, r});
        return g;
    }

    private View cell(App a, boolean label, int tc) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        View iv = iconView(a.icon, iconPx());
        c.addView(badged(iv, count(a.pkg)), new LinearLayout.LayoutParams(iconPx(), iconPx()));
        if (label && showLabels) c.addView(caption(a.label, tc));
        return c;
    }

    private TextView caption(String s, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color); t.setTextSize(12);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private View folderTile(Folder f) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0x66FFFFFF);
        box.setOutlineProvider(shapeOutline(shape));
        box.setClipToOutline(true);
        int n = 0;
        int mini = (int) (iconPx() * 0.3f);
        LinearLayout r = null;
        for (String p : f.pkgs) {
            App a = find(p);
            if (a == null) continue;
            if (n == 4) break;
            if (n % 2 == 0) { r = new LinearLayout(this); box.addView(r); }
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(mini, mini);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            r.addView(iv, lp);
            n++;
        }
        int total = 0;
        for (String p2 : f.pkgs) total += count(p2);
        c.addView(badged(box, total), new LinearLayout.LayoutParams(iconPx(), iconPx()));
        TextView cap = caption(f.name, Color.WHITE);
        cap.setShadowLayer(4, 0, 1, 0x88000000);
        if (showLabels) c.addView(cap);
        return c;
    }

    private View plusTile() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView plus = new TextView(this);
        plus.setText("+"); plus.setTextSize(30); plus.setTextColor(Color.WHITE);
        plus.setGravity(Gravity.CENTER);
        plus.setBackgroundColor(0x33FFFFFF);
        plus.setOutlineProvider(shapeOutline(shape));
        plus.setClipToOutline(true);
        c.addView(plus, new LinearLayout.LayoutParams(iconPx(), iconPx()));
        TextView cap = caption("New folder", Color.WHITE);
        cap.setShadowLayer(4, 0, 1, 0x88000000);
        if (showLabels) c.addView(cap);
        return c;
    }

    // ---------------------------------------------------------------- navigation
    private void go(int s) {
        int h = screenH();
        drawer.animate().translationY(s == 1 ? 0 : h).setDuration(260).start();
        qs.animate().translationY(s == 2 ? 0 : -h).setDuration(260).start();
        state = s;
        if (s != 1) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
            search.clearFocus();
            if (search.getText().length() > 0) search.setText("");
        }
    }

    private void toggleDark() {
        dark = !dark;
        prefs.edit().putBoolean("dark", dark).apply();
        buildUI();
        loadApps();
        buildDock();
        root.post(() -> go(2));
    }

    private void toggleTorch(View tile) {
        try {
            if (cm == null) cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (camId == null) {
                for (String id : cm.getCameraIdList()) {
                    Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (f != null && f) { camId = id; break; }
                }
            }
            if (camId == null) { toast("No flashlight found"); return; }
            torch = !torch;
            cm.setTorchMode(camId, torch);
            setTileActive(tile, torch);
        } catch (Exception e) {
            torch = false;
            toast("Flashlight unavailable");
        }
    }

    // ---------------------------------------------------------------- apps
    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        all.clear();
        for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            App a = new App();
            a.label = String.valueOf(r.loadLabel(pm));
            a.pkg = r.activityInfo.packageName;
            a.icon = r.loadIcon(pm);
            a.intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(a.pkg, r.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            all.add(a);
        }
        Collections.sort(all, (x, y) -> x.label.compareToIgnoreCase(y.label));
        filter(search == null ? "" : search.getText().toString());
    }

    private void filter(String q) {
        shown.clear();
        String s = q.trim().toLowerCase();
        for (App a : all) if (s.isEmpty() || a.label.toLowerCase().contains(s)) shown.add(a);
        if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
    }

    private App find(String pkg) {
        for (App a : all) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private void buildDock() {
        dock.removeAllViews();
        PackageManager pm = getPackageManager();
        Intent[] intents = {
            new Intent(Intent.ACTION_DIAL),
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
        };
        for (Intent in : intents) {
            ResolveInfo r = pm.resolveActivity(in, 0);
            if (r == null) continue;
            App a = new App();
            a.icon = r.loadIcon(pm); a.label = ""; a.pkg = r.activityInfo.packageName;
            a.intent = in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            View v = cell(a, false, Color.WHITE);
            v.setOnClickListener(x -> launch(a.intent));
            dock.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        }
    }

    private void launch(Intent i) {
        try { startActivity(i); } catch (Exception e) { toast("Can't open app"); }
    }

    private void safeStart(Intent i) {
        try { startActivity(i); } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------------------------------------------------------- menus & folders
    private void appMenu(App a) {
        new AlertDialog.Builder(this).setTitle(a.label)
            .setItems(new String[]{"Add to folder", "App info", "Uninstall"}, (d, w) -> {
                if (w == 0) pickFolder(a);
                else if (w == 1) safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                else safeStart(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
            }).show();
    }

    private void pickFolder(App a) {
        String[] names = new String[folders.size() + 1];
        for (int i = 0; i < folders.size(); i++) names[i] = folders.get(i).name;
        names[folders.size()] = "+ New folder…";
        new AlertDialog.Builder(this).setTitle("Add to folder")
            .setItems(names, (d, w) -> {
                if (w < folders.size()) addToFolder(folders.get(w), a.pkg);
                else prompt("New folder", "", n -> {
                    Folder f = new Folder(); f.name = n; folders.add(f);
                    addToFolder(f, a.pkg);
                });
            }).show();
    }

    private void addToFolder(Folder f, String pkg) {
        if (!f.pkgs.contains(pkg)) f.pkgs.add(pkg);
        saveFolders();
        folderAdapter.notifyDataSetChanged();
        toast("Added to " + f.name);
    }

    private void folderMenu(Folder f) {
        new AlertDialog.Builder(this).setTitle(f.name)
            .setItems(new String[]{"Rename", "Delete folder"}, (d, w) -> {
                if (w == 0) prompt("Rename folder", f.name, n -> { f.name = n; saveFolders(); folderAdapter.notifyDataSetChanged(); });
                else { folders.remove(f); saveFolders(); folderAdapter.notifyDataSetChanged(); }
            }).show();
    }

    private void openFolder(Folder f) {
        final List<App> items = new ArrayList<>();
        for (String p : f.pkgs) { App a = find(p); if (a != null) items.add(a); }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(20));
        TextView title = new TextView(this);
        title.setText(f.name); title.setTextSize(20); title.setTextColor(textColor());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(12));
        box.addView(title);

        GridView g = new GridView(this);
        g.setNumColumns(3);
        g.setVerticalSpacing(dp(14));
        g.setSelector(new ColorDrawable(Color.TRANSPARENT));
        BaseAdapter ad = new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int i) { return items.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) { return cell(items.get(i), true, textColor()); }
        };
        g.setAdapter(ad);
        box.addView(g, new LinearLayout.LayoutParams(-1, dp(240)));
        TextView hint = new TextView(this);
        hint.setText("Long-press an app to remove it");
        hint.setTextSize(11); hint.setTextColor(0x99888888);
        box.addView(hint);

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        dlg.getWindow().setBackgroundDrawable(round(panelColor() | 0xFF000000, 28));
        g.setOnItemClickListener((p, v, i, id) -> { dlg.dismiss(); launch(items.get(i).intent); });
        g.setOnItemLongClickListener((p, v, i, id) -> {
            f.pkgs.remove(items.get(i).pkg);
            items.remove(i);
            saveFolders();
            ad.notifyDataSetChanged();
            folderAdapter.notifyDataSetChanged();
            return true;
        });
        dlg.show();
    }

    private void prompt(String title, String initial, Consumer<String> done) {
        EditText et = new EditText(this);
        et.setText(initial); et.setSingleLine(true);
        et.setSelection(initial.length());
        new AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK", (d, w) -> {
                String n = et.getText().toString().trim();
                if (!n.isEmpty()) done.accept(n);
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void loadFolders() {
        folders.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString("folders", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Folder f = new Folder();
                f.name = o.getString("name");
                JSONArray p = o.getJSONArray("apps");
                for (int j = 0; j < p.length(); j++) f.pkgs.add(p.getString(j));
                folders.add(f);
            }
        } catch (Exception ignored) {}
    }

    private void saveFolders() {
        try {
            JSONArray arr = new JSONArray();
            for (Folder f : folders) {
                JSONObject o = new JSONObject();
                o.put("name", f.name);
                o.put("apps", new JSONArray(f.pkgs));
                arr.put(o);
            }
            prefs.edit().putString("folders", arr.toString()).apply();
        } catch (Exception ignored) {}
    }
}
