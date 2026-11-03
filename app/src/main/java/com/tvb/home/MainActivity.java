package com.tvb.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal, ad-free home screen for Android TV / Google TV.
 *
 * Layout: clock on top, a row of favourite apps, then every installed app.
 * Long-press OK on any app to add it to (or remove it from) the favourites.
 * No network access, no background services, no tracking.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "tvb_home";
    private static final String KEY_FAVORITES = "favorites";

    /** Shown as favourites on first run, if installed. Editable later with a long press. */
    private static final String[] DEFAULT_FAVORITES = {
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.google.android.youtube.tvmusic",
            "com.spotify.tv.android",
    };

    private static final int COLUMNS = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 15_000);
        }
    };

    private ScrollView scroll;
    private LinearLayout content;
    private TextView clockView;
    private TextView dateView;
    private View firstTile;

    /** Package name -> app entry, sorted by label. */
    private final Map<String, App> apps = new LinkedHashMap<>();

    private static final class App {
        final String packageName;
        final String label;
        final Drawable banner; // may be null
        final Drawable icon;

        App(String packageName, String label, Drawable banner, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B1020, 0xFF16223F, 0xFF2A1B45});
        getWindow().setBackgroundDrawable(bg);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(56), dp(32), dp(56), dp(48));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
        handler.post(clockTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the top.
        scroll.smoothScrollTo(0, 0);
        if (firstTile != null) firstTile.requestFocus();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // We are the home screen: there is nothing to go back to.
        scroll.smoothScrollTo(0, 0);
        if (firstTile != null) firstTile.requestFocus();
    }

    // ---------------------------------------------------------------- building the screen

    private void rebuild() {
        loadApps();
        content.removeAllViews();
        firstTile = null;

        content.addView(buildHeader());

        List<String> favorites = getFavorites();
        content.addView(sectionTitle("المفضلة"));
        HorizontalScrollView favScroll = new HorizontalScrollView(this);
        favScroll.setHorizontalScrollBarEnabled(false);
        favScroll.setClipChildren(false);
        favScroll.setClipToPadding(false);
        LinearLayout favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        favRow.setClipChildren(false);
        favRow.setPadding(dp(8), dp(12), dp(8), dp(12));
        for (String pkg : favorites) {
            App app = apps.get(pkg);
            if (app == null) continue;
            View tile = appTile(app, dp(256), dp(144), true);
            favRow.addView(tile, tileParams(dp(256), dp(144)));
            if (firstTile == null) firstTile = tile;
        }
        if (favRow.getChildCount() == 0) {
            TextView empty = text("اضغط مطولاً على أي تطبيق تحت حتى تضيفه هنا", 16, 0xAAFFFFFF);
            favRow.addView(empty);
        }
        favScroll.addView(favRow);
        content.addView(favScroll);

        content.addView(sectionTitle("كل التطبيقات"));
        List<View> tiles = new ArrayList<>();
        for (App app : apps.values()) {
            tiles.add(appTile(app, dp(208), dp(117), false));
        }
        tiles.add(actionTile("الإعدادات", () -> safeStart(new Intent(Settings.ACTION_SETTINGS))));
        addGrid(tiles, dp(208), dp(117));
        if (firstTile == null && !tiles.isEmpty()) firstTile = tiles.get(0);

        TextView hint = text("ضغطة مطوّلة على OK = إضافة أو إزالة من المفضلة", 14, 0x88FFFFFF);
        hint.setPadding(dp(8), dp(24), dp(8), 0);
        content.addView(hint);

        updateClock();
        if (firstTile != null) firstTile.post(() -> firstTile.requestFocus());
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(8), 0, dp(8), dp(8));
        clockView = text("", 56, Color.WHITE);
        clockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        dateView = text("", 18, 0xCCFFFFFF);
        header.addView(clockView);
        header.addView(dateView);
        return header;
    }

    private void updateClock() {
        if (clockView == null) return;
        Date now = new Date();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clockView.setText(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        dateView.setText(DateFormat.getDateInstance(DateFormat.FULL, Locale.getDefault()).format(now));
    }

    private void addGrid(List<View> tiles, int w, int h) {
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setPadding(dp(8), dp(8), dp(8), dp(8));
                content.addView(row);
            }
            row.addView(tiles.get(i), tileParams(w, h));
        }
    }

    private LinearLayout.LayoutParams tileParams(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginEnd(dp(20));
        return lp;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 20, 0xDDFFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(8), dp(24), dp(8), dp(4));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    // ---------------------------------------------------------------- tiles

    private View appTile(App app, int w, int h, boolean isFavorite) {
        FrameLayout tile = baseTile();

        if (app.banner != null) {
            ImageView img = new ImageView(this);
            img.setImageDrawable(app.banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setClipToOutline(true);
            tile.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            ImageView img = new ImageView(this);
            img.setImageDrawable(app.icon);
            box.addView(img, new LinearLayout.LayoutParams(h / 2, h / 2));
            TextView label = text(app.label, 14, Color.WHITE);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setPadding(dp(8), dp(6), dp(8), 0);
            box.addView(label);
            tile.addView(box, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        tile.setContentDescription(app.label);
        tile.setOnClickListener(v -> launch(app.packageName));
        tile.setOnLongClickListener(v -> {
            toggleFavorite(app);
            return true;
        });
        return tile;
    }

    private View actionTile(String label, Runnable action) {
        FrameLayout tile = baseTile();
        TextView t = text(label, 18, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        tile.addView(t, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tile.setContentDescription(label);
        tile.setOnClickListener(v -> action.run());
        return tile;
    }

    private FrameLayout baseTile() {
        FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(false);
        tile.setClickable(true);
        tile.setClipToOutline(true);

        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(14));
        normal.setColor(0x33FFFFFF);
        tile.setBackground(normal);
        tile.setForeground(focusRing(false));

        tile.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(hasFocus ? dp(8) : 0).setDuration(140).start();
            ((FrameLayout) v).setForeground(focusRing(hasFocus));
            if (hasFocus) v.bringToFront();
        });
        return tile;
    }

    private Drawable focusRing(boolean focused) {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(14));
        ring.setColor(Color.TRANSPARENT);
        if (focused) ring.setStroke(dp(3), Color.WHITE);
        return ring;
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<App> list = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        seen.put(getPackageName(), true);

        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (seen.containsKey(pkg)) continue;
                seen.put(pkg, true);
                Drawable banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                list.add(new App(pkg, String.valueOf(ri.loadLabel(pm)), banner, ri.loadIcon(pm)));
            }
        }
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));

        apps.clear();
        for (App a : list) apps.put(a.packageName, a);
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Toast.makeText(this, "ما يكدر يفتح هذا التطبيق", Toast.LENGTH_SHORT).show();
            return;
        }
        safeStart(intent);
    }

    private void safeStart(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "ما يكدر يفتح هذا", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- favourites

    private List<String> getFavorites() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String stored = prefs.getString(KEY_FAVORITES, null);
        List<String> result = new ArrayList<>();
        if (stored == null) {
            for (String pkg : DEFAULT_FAVORITES) {
                if (apps.containsKey(pkg)) result.add(pkg);
            }
            saveFavorites(result);
            return result;
        }
        if (!stored.isEmpty()) {
            Collections.addAll(result, stored.split(","));
        }
        return result;
    }

    private void saveFavorites(List<String> favorites) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_FAVORITES, TextUtils.join(",", favorites))
                .apply();
    }

    private void toggleFavorite(App app) {
        List<String> favorites = getFavorites();
        if (favorites.remove(app.packageName)) {
            Toast.makeText(this, "انشال من المفضلة: " + app.label, Toast.LENGTH_SHORT).show();
        } else {
            favorites.add(app.packageName);
            Toast.makeText(this, "انضاف للمفضلة: " + app.label, Toast.LENGTH_SHORT).show();
        }
        saveFavorites(favorites);
        rebuild();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
