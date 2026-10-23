package com.muse.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * 视频播放页。
 * 显示模式：自动（竖屏完整/横屏填充）、完整（FIT 留黑边）、填充（CROP 铺满裁剪）、拉伸。
 * 方向：默认跟随传感器；可手动锁定横屏/竖屏；播放页保持沉浸式。
 */
public class PlayerActivity extends Activity implements TextureView.SurfaceTextureListener {

    static final int MODE_AUTO = 0, MODE_FIT = 1, MODE_CROP = 2, MODE_STRETCH = 3;
    static final String[] MODE_NAMES = {"自动", "完整", "填充", "拉伸"};

    private ArrayList<String> paths, names;
    private int index;
    private TextureView texture;
    private Surface surface;
    private MediaPlayer mp;
    private int videoW, videoH;
    private int mode = MODE_AUTO;
    private boolean loop = true;
    private boolean muted = false;
    private int rotateState = 0; // 0 跟随传感器 1 锁横屏 2 锁竖屏

    private View controls, topBar;
    private TextView titleTv, curTv, durTv, playBtn;
    private SeekBar seek;
    private final TextView[] modeChips = new TextView[4];
    private TextView loopChip, muteChip;

    // 字幕（v2.1）：0 关 1 原文 2 双语（在线翻译）
    private static final int REQ_SUB_PICK = 77;
    private java.util.List<Subtitles.Cue> cues;
    private File subFile;
    private int subMode = 0;
    private int subColor = 0; // 0=默认配色（原文白/中文淡黄），非 0=用户选的统一颜色
    private boolean subMostlyZh = false;
    private boolean subTranslating = false;
    private boolean asrRunning = false;
    private java.util.concurrent.atomic.AtomicBoolean asrCancel;
    private LinearLayout subLayer;
    private TextView subOrigTv, subZhTv, ccChip;
    private final Handler ui = new Handler();
    private boolean seeking = false;
    private boolean controlsVisible = true;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (mp != null && !seeking) {
                try {
                    int cur = mp.getCurrentPosition();
                    int dur = mp.getDuration();
                    if (dur > 0) {
                        seek.setMax(dur);
                        seek.setProgress(cur);
                        curTv.setText(MediaItem.fmtDuration(cur));
                        durTv.setText(MediaItem.fmtDuration(dur));
                    }
                    updateSubtitle(cur);
                } catch (Exception ignored) { }
            }
            ui.postDelayed(this, 400);
        }
    };
    private final Runnable hideLater = () -> setControls(false);

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        paths = getIntent().getStringArrayListExtra("paths");
        names = getIntent().getStringArrayListExtra("names");
        index = getIntent().getIntExtra("index", 0);
        if (paths == null || paths.isEmpty()) { finish(); return; }
        SharedPreferences sp = getSharedPreferences("player", MODE_PRIVATE);
        mode = sp.getInt("mode", MODE_AUTO);
        loop = sp.getBoolean("loop", true);
        subColor = sp.getInt("sub_color", 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        setContentView(buildUi());
        applySubColors();
        texture.setSurfaceTextureListener(this);
    }

    private FrameLayout frameRoot;

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        frameRoot = root;
        root.setBackgroundColor(Color.BLACK);
        texture = new TextureView(this);
        root.addView(texture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        texture.setOnClickListener(v -> setControls(!controlsVisible));

        // 字幕层（双语叠加：原文白字、中文黄字，压在控制层下面）
        subLayer = new LinearLayout(this);
        subLayer.setOrientation(LinearLayout.VERTICAL);
        subLayer.setGravity(Gravity.CENTER_HORIZONTAL);
        subLayer.setPadding(dp(28), 0, dp(28), 0);
        subOrigTv = subLine(15f, Color.WHITE);
        subZhTv = subLine(16.5f, Color.rgb(255, 243, 196));
        subLayer.addView(subOrigTv);
        subLayer.addView(subZhTv);
        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.gravity = Gravity.BOTTOM;
        sLp.bottomMargin = dp(28);
        root.addView(subLayer, sLp);
        subLayer.setVisibility(View.GONE);

        mountChrome();
        return root;
    }

    private TextView subLine(float size, int color) {
        TextView v = new TextView(this);
        v.setTextColor(color);
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        v.setShadowLayer(dp(4), 0, dp(1), Color.argb(200, 0, 0, 0));
        v.setLineSpacing(0, 1.25f);
        v.setVisibility(View.GONE);
        return v;
    }

    private void adjustSubMargin() {
        if (subLayer == null || controls == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) subLayer.getLayoutParams();
        int h = controls.getVisibility() == View.VISIBLE ? controls.getHeight() : 0;
        lp.bottomMargin = h > 0 ? h + dp(12) : dp(28);
        subLayer.setLayoutParams(lp);
    }

    /** （重）挂顶栏与控制层：竖屏玻璃舱 / 横屏玻璃坞，旋转时重建 */
    private void mountChrome() {
        if (topBar != null) frameRoot.removeView(topBar);
        if (controls != null) frameRoot.removeView(controls);
        topBar = buildTopBar();
        FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.gravity = Gravity.TOP;
        frameRoot.addView(topBar, tLp);
        controls = buildControls();
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.gravity = Gravity.BOTTOM;
        cLp.setMargins(dp(14), 0, dp(14), dp(14));
        frameRoot.addView(controls, cLp);
        if (mp != null && playBtn != null) {
            try { playBtn.setText(mp.isPlaying() ? "⏸" : "▶"); } catch (Exception ignored) { }
        }
        setControls(controlsVisible);
    }

    private GradientDrawable glassPill(int alpha) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(alpha, 255, 255, 255));
        g.setCornerRadius(dp(999));
        g.setStroke(1, Color.argb(150, 255, 255, 255));
        return g;
    }

    private GradientDrawable deckBg() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(218, 255, 255, 255), Color.argb(188, 255, 255, 255)});
        g.setCornerRadius(dp(30));
        g.setStroke(1, Color.argb(205, 255, 255, 255));
        return g;
    }

    private void vermilionOval(TextView v) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(255, 122, 77), Color.rgb(224, 68, 31)});
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(1, Color.argb(160, 255, 255, 255));
        v.setBackground(g);
        v.setTextColor(Color.WHITE);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(12), dp(14), dp(4));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(Color.WHITE);
        back.setTextSize(24);
        back.setGravity(Gravity.CENTER);
        back.setShadowLayer(dp(3), 0, dp(1), Color.argb(120, 80, 30, 0));
        back.setBackground(glassPill(95));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(38), dp(38)));
        titleTv = new TextView(this);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(12.5f);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleTv.setShadowLayer(dp(3), 0, dp(1), Color.argb(120, 80, 30, 0));
        titleTv.setBackground(glassPill(95));
        titleTv.setPadding(dp(13), dp(8), dp(13), dp(8));
        titleTv.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tiLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tiLp.leftMargin = dp(9);
        bar.addView(titleTv, tiLp);
        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        TextView rot = new TextView(this);
        rot.setText("⟳ 横竖");
        rot.setTextColor(Color.WHITE);
        rot.setTextSize(12.5f);
        rot.setShadowLayer(dp(3), 0, dp(1), Color.argb(120, 80, 30, 0));
        rot.setBackground(glassPill(95));
        rot.setPadding(dp(12), dp(8), dp(12), dp(8));
        rot.setOnClickListener(v -> cycleRotate());
        bar.addView(rot);
        return bar;
    }

    private View buildControls() {
        boolean land = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        return land ? buildDockControls() : buildDeckControls();
    }

    private SeekBar makeSeek() {
        SeekBar s = new SeekBar(this);
        s.setProgressTintList(ColorStateList.valueOf(Color.rgb(242, 96, 60)));
        s.setSecondaryProgressTintList(ColorStateList.valueOf(Color.argb(60, 242, 96, 60)));
        s.setThumbTintList(ColorStateList.valueOf(Color.rgb(224, 68, 31)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) curTv.setText(MediaItem.fmtDuration(p));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                if (mp != null) mp.seekTo(sb.getProgress());
            }
        });
        return s;
    }

    /** 竖屏：玻璃控制舱 */
    private View buildDeckControls() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(deckBg());
        box.setPadding(dp(14), dp(14), dp(14), dp(16));

        LinearLayout prog = new LinearLayout(this);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        curTv = smallTime("0:00");
        prog.addView(curTv);
        seek = makeSeek();
        prog.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        durTv = smallTime("0:00");
        prog.addView(durTv);
        box.addView(prog);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(12), 0, dp(2));
        TextView prevB = roundBtn("⏪", v -> seekBy(-5000));
        prevB.setOnLongClickListener(v -> { step(-1); return true; });
        row.addView(prevB);
        playBtn = roundBtn("⏸", v -> togglePlay());
        vermilionOval(playBtn);
        playBtn.setTextSize(21);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(dp(66), dp(66));
        pLp.leftMargin = dp(26);
        row.addView(playBtn, pLp);
        TextView next = roundBtn("⏩", v -> seekBy(5000));
        next.setOnLongClickListener(v -> { step(1); return true; });
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(dp(50), dp(50));
        nLp.leftMargin = dp(26);
        row.addView(next, nLp);
        box.addView(row);

        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        modes.setPadding(0, dp(12), 0, 0);
        for (int i = 0; i < 4; i++) {
            final int m = i;
            TextView c = chip(MODE_NAMES[i]);
            c.setOnClickListener(v -> { mode = m; savePrefs(); refreshChips(); applyTransform(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(5);
            modes.addView(c, lp);
            modeChips[i] = c;
        }
        loopChip = chip("循环");
        loopChip.setOnClickListener(v -> { loop = !loop; if (mp != null) mp.setLooping(loop); savePrefs(); refreshChips(); });
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp.leftMargin = dp(5);
        modes.addView(loopChip, lLp);
        muteChip = chip("静音");
        muteChip.setOnClickListener(v -> { muted = !muted; applyVolume(); refreshChips(); });
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.leftMargin = dp(5);
        modes.addView(muteChip, mLp);
        box.addView(modes);

        LinearLayout subRow = new LinearLayout(this);
        subRow.setGravity(Gravity.CENTER);
        subRow.setPadding(0, dp(10), 0, 0);
        ccChip = chip("CC 字幕");
        ccChip.setPadding(dp(15), dp(7), dp(15), dp(7));
        ccChip.setOnClickListener(v -> cycleSubMode());
        ccChip.setOnLongClickListener(v -> { showSubtitleSettings(); return true; });
        subRow.addView(ccChip);
        TextView setWp = new TextView(this);
        setWp.setText("设为动态壁纸");
        setWp.setTextColor(Color.rgb(224, 68, 31));
        setWp.setTextSize(12.5f);
        setWp.setGravity(Gravity.CENTER);
        setWp.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.argb(150, 255, 255, 255));
        gd.setCornerRadius(dp(999));
        gd.setStroke(1, Color.argb(150, 242, 96, 60));
        setWp.setBackground(gd);
        setWp.setOnClickListener(v -> setAsWallpaper());
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.leftMargin = dp(9);
        subRow.addView(setWp, sLp);
        box.addView(subRow);
        refreshChips();
        return box;
    }

    /** 横屏：一条玻璃坞 */
    private View buildDockControls() {
        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(deckBg());
        dock.setPadding(dp(16), dp(10), dp(16), dp(10));

        playBtn = roundBtn("⏸", v -> togglePlay());
        vermilionOval(playBtn);
        playBtn.setTextSize(18);
        dock.addView(playBtn, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout prog = new LinearLayout(this);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        curTv = smallTime("0:00");
        prog.addView(curTv);
        seek = makeSeek();
        prog.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        durTv = smallTime("0:00");
        prog.addView(durTv);
        mid.addView(prog);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        midLp.leftMargin = dp(14);
        midLp.rightMargin = dp(14);
        dock.addView(mid, midLp);

        TextView prev = chip("⏪ 5秒");
        prev.setOnClickListener(v -> seekBy(-5000));
        prev.setOnLongClickListener(v -> { step(-1); return true; });
        dock.addView(prev);
        TextView next = chip("5秒 ⏩");
        next.setOnClickListener(v -> seekBy(5000));
        next.setOnLongClickListener(v -> { step(1); return true; });
        LinearLayout.LayoutParams nxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nxLp.leftMargin = dp(8);
        dock.addView(next, nxLp);

        // 横屏坞里模式做成一颗循环切换钮；modeChips 用占位视图承接 refreshChips
        for (int i = 0; i < 4; i++) modeChips[i] = new TextView(this);
        TextView modeCycle = chip("显示 · " + MODE_NAMES[mode]);
        styleToggle(modeCycle, false);
        modeCycle.setOnClickListener(v -> {
            mode = (mode + 1) % 4;
            savePrefs();
            applyTransform();
            modeCycle.setText("显示 · " + MODE_NAMES[mode]);
            refreshChips();
        });
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mcLp.leftMargin = dp(8);
        dock.addView(modeCycle, mcLp);

        loopChip = chip("循环");
        loopChip.setOnClickListener(v -> { loop = !loop; if (mp != null) mp.setLooping(loop); savePrefs(); refreshChips(); });
        LinearLayout.LayoutParams lLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp2.leftMargin = dp(8);
        dock.addView(loopChip, lLp2);
        muteChip = chip("静音");
        muteChip.setOnClickListener(v -> { muted = !muted; applyVolume(); refreshChips(); });
        LinearLayout.LayoutParams mLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp2.leftMargin = dp(8);
        dock.addView(muteChip, mLp2);
        ccChip = chip("CC 字幕");
        ccChip.setOnClickListener(v -> cycleSubMode());
        ccChip.setOnLongClickListener(v -> { showSubtitleSettings(); return true; });
        LinearLayout.LayoutParams ccLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccLp2.leftMargin = dp(8);
        dock.addView(ccChip, ccLp2);
        refreshChips();
        return dock;
    }

    private TextView smallTime(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.rgb(138, 124, 104));
        v.setTextSize(11.5f);
        return v;
    }

    private TextView chip(String t) {
        TextView c = new TextView(this);
        c.setText(t);
        c.setTextSize(12.5f);
        c.setSingleLine(true);
        c.setPadding(dp(10), dp(6), dp(10), dp(6));
        return c;
    }

    private TextView roundBtn(String t, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.rgb(107, 91, 69));
        v.setTextSize(16);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.argb(170, 255, 255, 255));
        g.setStroke(1, Color.argb(200, 255, 255, 255));
        v.setBackground(g);
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(50)));
        return v;
    }

    private void refreshChips() {
        for (int i = 0; i < 4; i++) styleToggle(modeChips[i], i == mode);
        styleToggle(loopChip, loop);
        styleToggle(muteChip, muted);
        if (ccChip != null) {
            String[] ccNames = {"CC 字幕", "CC 原文", "CC 中文", "CC 双语"};
            ccChip.setText(ccNames[subMode]);
            styleToggle(ccChip, subMode != 0);
        }
    }

    private void styleToggle(TextView c, boolean on) {
        GradientDrawable g;
        if (on) {
            g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(255, 122, 77), Color.rgb(224, 68, 31)});
            c.setTextColor(Color.WHITE);
        } else {
            g = new GradientDrawable();
            g.setColor(Color.argb(150, 255, 255, 255));
            g.setStroke(1, Color.rgb(227, 213, 190));
            c.setTextColor(Color.rgb(107, 97, 84));
        }
        g.setCornerRadius(dp(999));
        c.setBackground(g);
    }

    private void savePrefs() {
        getSharedPreferences("player", MODE_PRIVATE).edit()
                .putInt("mode", mode).putBoolean("loop", loop)
                .putInt("sub_color", subColor).apply();
    }

    private void applySubColors() {
        if (subOrigTv == null || subZhTv == null) return;
        if (subColor != 0) {
            subOrigTv.setTextColor(subColor);
            subZhTv.setTextColor(subColor);
        } else {
            subOrigTv.setTextColor(Color.WHITE);
            subZhTv.setTextColor(Color.rgb(255, 243, 196));
        }
    }

    /** 点按快退/快进（毫秒），长按这两个钮才是切上/下一个视频。 */
    private void seekBy(int deltaMs) {
        if (mp == null) return;
        try {
            int pos = mp.getCurrentPosition() + deltaMs;
            int dur = mp.getDuration();
            if (pos < 0) pos = 0;
            if (dur > 0 && pos > dur - 300) pos = Math.max(0, dur - 300);
            mp.seekTo(pos);
            if (seek != null) seek.setProgress(pos);
            if (curTv != null) curTv.setText(MediaItem.fmtDuration(pos));
            setControls(true);
            try { updateSubtitle(pos); } catch (Exception ignored) { }
        } catch (Exception ignored) { }
    }

    // ---------- 播放控制 ----------

    private void setControls(boolean show) {
        controlsVisible = show;
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(hideLater);
        if (show) ui.postDelayed(hideLater, 3200);
        controls.post(this::adjustSubMargin);
    }

    private void cycleRotate() {
        rotateState = (rotateState + 1) % 3;
        if (rotateState == 0) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            Toast.makeText(this, "方向：跟随手机自动旋转", Toast.LENGTH_SHORT).show();
        } else if (rotateState == 1) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            Toast.makeText(this, "方向：锁定横屏", Toast.LENGTH_SHORT).show();
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            Toast.makeText(this, "方向：锁定竖屏", Toast.LENGTH_SHORT).show();
        }
        setControls(true);
    }

    private void togglePlay() {
        if (mp == null) return;
        if (mp.isPlaying()) { mp.pause(); playBtn.setText("▶"); }
        else { mp.start(); playBtn.setText("⏸"); }
        setControls(true);
    }

    private void step(int d) {
        int n = index + d;
        if (n < 0) n = paths.size() - 1;
        if (n >= paths.size()) n = 0;
        index = n;
        playCurrent();
    }

    private void applyVolume() {
        if (mp != null) {
            float v = muted ? 0f : 1f;
            mp.setVolume(v, v);
        }
    }

    private void playCurrent() {
        if (surface == null) return;
        releasePlayer();
        titleTv.setText(names.get(index));
        mp = new MediaPlayer();
        try {
            mp.setSurface(surface);
            mp.setDataSource(paths.get(index));
            mp.setLooping(loop);
            mp.setOnPreparedListener(p -> {
                applyVolume();
                p.start();
                playBtn.setText("⏸");
                ui.post(ticker);
            });
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                videoW = w; videoH = h;
                texture.post(this::applyTransform);
            });
            mp.setOnCompletionListener(p -> { if (!loop) step(1); });
            mp.setOnErrorListener((p, what, extra) -> {
                Toast.makeText(this, "这个视频的格式系统解不了：" + names.get(index), Toast.LENGTH_LONG).show();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        setControls(true);
        loadSubtitlesFor(paths.get(index));
    }

    // ---------- 字幕（v2.1） ----------

    private void hideSubLines() {
        if (subLayer != null) subLayer.setVisibility(View.GONE);
        if (subOrigTv != null) subOrigTv.setVisibility(View.GONE);
        if (subZhTv != null) subZhTv.setVisibility(View.GONE);
    }

    private void loadSubtitlesFor(final String videoPath) {
        cues = null;
        subFile = null;
        subMode = 0;
        subMostlyZh = false;
        subTranslating = false;
        refreshChips();
        hideSubLines();
        File comp = Subtitles.findCompanion(videoPath);
        if (comp == null) {
            File gen = AsrSubtitle.generatedFile(this, videoPath);
            if (gen.isFile() && gen.length() > 0) comp = gen;
        }
        if (comp == null) return;
        final File f = comp;
        new Thread(() -> {
            try {
                java.util.List<Subtitles.Cue> parsed = Subtitles.parse(f);
                boolean hadZh = Subtitles.loadZh(this, f, parsed);
                ui.post(() -> {
                    if (!videoPath.equals(paths.get(index))) return;
                    if (parsed.isEmpty()) return;
                    cues = parsed;
                    subMostlyZh = Subtitles.isMostlyChinese(parsed);
                    subFile = f;
                    subMode = 1;
                    refreshChips();
                    Toast.makeText(this, "已加载字幕：" + f.getName()
                            + (hadZh ? "（含缓存译文，点 CC 看双语）" : "（点 CC 切双语翻译）"),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception ignored) { }
        }).start();
    }

    private void cycleSubMode() {
        if (cues == null || cues.isEmpty()) {
            promptNoSubtitle();
            return;
        }
        if (subMode == 0) subMode = 1;
        else if (subMode == 1) { subMode = 2; ensureTranslated(); }
        else if (subMode == 2) { subMode = 3; ensureTranslated(); }
        else subMode = 0;
        refreshChips();
        setControls(true);
        if (mp != null) {
            try { updateSubtitle(mp.getCurrentPosition()); } catch (Exception ignored) { }
        }
    }

    private void ensureTranslated() {
        if (cues == null || cues.isEmpty()) return;
        if (Subtitles.isMostlyChinese(cues)) {
            Toast.makeText(this, "字幕本身就是中文，不用翻译", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean all = true;
        for (Subtitles.Cue c : cues) if (c.zh == null || c.zh.isEmpty()) { all = false; break; }
        if (all || subTranslating) return;
        subTranslating = true;
        Toast.makeText(this, "开始在线翻译，字幕多的话要等一会儿…", Toast.LENGTH_SHORT).show();
        final File f = subFile;
        new Thread(() -> {
            try {
                Subtitles.translateAll(this, f, cues, (done, total) -> ui.post(() -> {
                    if (subMode >= 2 && subZhTv != null) {
                        subZhTv.setText("正在翻译 " + done + " / " + total);
                        subZhTv.setVisibility(View.VISIBLE);
                        subLayer.setVisibility(View.VISIBLE);
                    }
                }));
                ui.post(() -> {
                    subTranslating = false;
                    Toast.makeText(this, "翻译完成", Toast.LENGTH_SHORT).show();
                    if (mp != null) {
                        try { updateSubtitle(mp.getCurrentPosition()); } catch (Exception ignored) { }
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    subTranslating = false;
                    if (subMode == 2) subMode = 1; // 只看中文没翻成，退回原文
                    refreshChips();
                    Toast.makeText(this, "翻译失败：在线翻译需要联网，检查网络后重试",
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ---------- 玻璃弹窗（与全 App 液态玻璃统一风格） ----------

    private LinearLayout glassCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(244, 255, 255, 255), Color.argb(230, 255, 251, 246)});
        g.setCornerRadius(dp(26));
        g.setStroke(1, Color.argb(220, 255, 255, 255));
        box.setBackground(g);
        box.setPadding(dp(20), dp(18), dp(20), dp(16));
        return box;
    }

    private android.app.Dialog glassDialog(LinearLayout card) {
        android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(card);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.86);
            d.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    private TextView dlgTitle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(17);
        v.setTextColor(Color.rgb(51, 45, 38));
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(0, 0, 0, dp(12));
        return v;
    }

    private TextView dlgItem(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(15);
        v.setTextColor(Color.rgb(74, 63, 48));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(150, 255, 255, 255));
        g.setCornerRadius(dp(16));
        g.setStroke(1, Color.argb(190, 255, 255, 255));
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView dlgTextBtn(String t, boolean primary) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(15);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(11), dp(10), dp(11));
        if (primary) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(255, 122, 77), Color.rgb(224, 68, 31)});
            g.setCornerRadius(dp(16));
            v.setBackground(g);
            v.setTextColor(Color.WHITE);
            v.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            v.setTextColor(Color.rgb(140, 128, 110));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        v.setLayoutParams(lp);
        return v;
    }

    /** 整行大按钮（一眼能看出能点）。 */
    private TextView dlgBigBtn(String t, boolean primary) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(15.5f);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        if (primary) {
            g.setOrientation(GradientDrawable.Orientation.TL_BR);
            g.setColors(new int[]{Color.rgb(255, 122, 77), Color.rgb(224, 68, 31)});
            v.setTextColor(Color.WHITE);
            v.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            g.setColor(Color.argb(150, 255, 255, 255));
            v.setTextColor(Color.rgb(74, 63, 48));
        }
        g.setCornerRadius(dp(16));
        g.setStroke(1, Color.argb(190, 255, 255, 255));
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    /** 没有字幕时让用户选：挑现成字幕文件，或用语音识别现生成。 */
    private void promptNoSubtitle() {
        if (asrRunning) {
            Toast.makeText(this, "正在生成字幕，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean ready = AsrSubtitle.modelReady(this);
        LinearLayout card = glassCard();
        card.addView(dlgTitle("这个视频没有字幕"));
        TextView item1 = dlgItem("选一个字幕文件（.srt / .vtt / .ass）");
        card.addView(item1);
        final android.app.Dialog dlg = glassDialog(card);
        item1.setOnClickListener(v -> { dlg.dismiss(); pickSubtitle(); });
        if (ready) {
            TextView item2 = dlgItem("语音生成字幕（听声音认字，全程离线）");
            card.addView(item2);
            item2.setOnClickListener(v -> { dlg.dismiss(); startGenerateSubtitles(); });
        } else {
            // 模型还没下：给一个明确的大按钮，并写清要点了才能下载
            final TextView desc = new TextView(this);
            desc.setText("想听声音自动生成字幕？先点下面按钮下载语言模型"
                    + "（约 160MB，只下这一次，之后全程离线）：");
            desc.setTextSize(14);
            desc.setTextColor(Color.rgb(107, 95, 78));
            desc.setLineSpacing(0, 1.25f);
            desc.setPadding(0, dp(2), 0, dp(10));
            card.addView(desc);
            final ProgressBar pb = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgressTintList(ColorStateList.valueOf(Color.rgb(224, 68, 31)));
            pb.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(70, 160, 140, 115)));
            pb.setVisibility(View.GONE);
            LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
            pbLp.bottomMargin = dp(10);
            pb.setLayoutParams(pbLp);
            card.addView(pb);
            final TextView dlBtn = dlgBigBtn("⬇ 下载语言模型", true);
            card.addView(dlBtn);
            final java.util.concurrent.atomic.AtomicBoolean dlCancel =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final boolean[] dlState = {false, false}; // [0]=下载中 [1]=已下好
            dlBtn.setOnClickListener(v -> {
                if (dlState[1]) { dlg.dismiss(); startGenerateSubtitles(); return; }
                if (dlState[0]) { dlCancel.set(true); return; }
                dlState[0] = true;
                dlCancel.set(false);
                pb.setVisibility(View.VISIBLE);
                pb.setProgress(0);
                dlBtn.setText("取消下载");
                desc.setText("正在下载语言模型…");
                new Thread(() -> {
                    try {
                        AsrSubtitle.downloadModel(this, (stage, pct) -> ui.post(() -> {
                            if (isFinishing()) return;
                            desc.setText(stage + "… " + pct + "%");
                            pb.setProgress(pct);
                        }), dlCancel);
                        ui.post(() -> {
                            dlState[0] = false;
                            dlState[1] = true;
                            pb.setVisibility(View.GONE);
                            desc.setText("语言模型下载好了 ✓ 点下面按钮开始生成字幕：");
                            dlBtn.setText("开始生成字幕");
                        });
                    } catch (Throwable t) {
                        final boolean wasCancel = t instanceof AsrSubtitle.Cancelled;
                        final String m = t.getMessage() != null ? t.getMessage()
                                : t.getClass().getSimpleName();
                        ui.post(() -> {
                            dlState[0] = false;
                            pb.setVisibility(View.GONE);
                            if (wasCancel) {
                                desc.setText("下载取消了。想生成字幕时，再点按钮下载语言模型"
                                        + "（约 160MB，只下一次）：");
                            } else {
                                desc.setText("下载没成功：" + m + "\n检查网络后点按钮重试：");
                            }
                            dlBtn.setText("⬇ 重新下载语言模型");
                        });
                    }
                }).start();
            });
        }
        LinearLayout btnRow = new LinearLayout(this);
        TextView cancel = dlgTextBtn("取消", false);
        btnRow.addView(cancel);
        card.addView(btnRow);
        cancel.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    private void startGenerateSubtitles() {
        if (asrRunning) return;
        if (index < 0 || index >= paths.size()) return;
        final String videoPath = paths.get(index);
        asrRunning = true;
        asrCancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean cancel = asrCancel;
        if (mp != null) {
            try { if (mp.isPlaying()) mp.pause(); } catch (Exception ignored) { }
        }
        if (playBtn != null) playBtn.setText("▶");
        LinearLayout card = glassCard();
        final TextView titleTv = dlgTitle("语音生成字幕");
        final TextView stageTv = new TextView(this);
        stageTv.setText("准备中…");
        stageTv.setTextSize(14.5f);
        stageTv.setTextColor(Color.rgb(107, 95, 78));
        stageTv.setPadding(0, 0, 0, dp(10));
        final ProgressBar pb = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        pb.setProgressTintList(ColorStateList.valueOf(Color.rgb(224, 68, 31)));
        pb.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(70, 160, 140, 115)));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        pbLp.bottomMargin = dp(14);
        pb.setLayoutParams(pbLp);
        final LinearLayout btnRow = new LinearLayout(this);
        final TextView cancelBtn = dlgTextBtn("取消生成", false);
        btnRow.addView(cancelBtn);
        card.addView(titleTv);
        card.addView(stageTv);
        card.addView(pb);
        card.addView(btnRow);
        final android.app.Dialog dlg = glassDialog(card);
        dlg.setCancelable(false);
        dlg.setCanceledOnTouchOutside(false);
        cancelBtn.setOnClickListener(v -> cancel.set(true));
        dlg.show();
        new Thread(() -> {
            try {
                java.util.List<Subtitles.Cue> gen = AsrSubtitle.generate(this, videoPath,
                        (stage, pct) -> ui.post(() -> {
                            if (isFinishing()) return;
                            stageTv.setText(stage + "… " + pct + "%");
                            pb.setProgress(pct);
                        }), cancel);
                File f = AsrSubtitle.saveSrt(this, videoPath, gen);
                java.util.List<Subtitles.Cue> parsed = Subtitles.parse(f);
                ui.post(() -> {
                    asrRunning = false;
                    try { dlg.dismiss(); } catch (Exception ignored) { }
                    if (isFinishing()) return;
                    if (!videoPath.equals(paths.get(index)) || parsed.isEmpty()) {
                        if (!parsed.isEmpty()) {
                            Toast.makeText(this, "字幕生成好了，下次打开这个视频会自动加载",
                                    Toast.LENGTH_LONG).show();
                        }
                        return;
                    }
                    cues = parsed;
                    subMostlyZh = Subtitles.isMostlyChinese(parsed);
                    subFile = f;
                    subMode = 1;
                    refreshChips();
                    setControls(true);
                    Toast.makeText(this, "字幕生成好了（" + parsed.size()
                            + " 条），点 CC 切「双语」看中文", Toast.LENGTH_LONG).show();
                    if (mp != null) {
                        try { updateSubtitle(mp.getCurrentPosition()); } catch (Exception ignored) { }
                    }
                });
            } catch (AsrSubtitle.Cancelled ce) {
                ui.post(() -> {
                    asrRunning = false;
                    try { dlg.dismiss(); } catch (Exception ignored) { }
                    if (!isFinishing()) {
                        Toast.makeText(this, "已取消生成", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable t) {
                // 失败原因留在弹窗里不消失，方便看清/截图
                String m = t.getMessage();
                if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
                if (t.getCause() != null && t.getCause().getMessage() != null) {
                    m = m + "（" + t.getCause().getMessage() + "）";
                }
                final String msg = m;
                ui.post(() -> {
                    asrRunning = false;
                    if (isFinishing()) return;
                    titleTv.setText("生成没成功");
                    stageTv.setText(msg);
                    stageTv.setTextColor(Color.rgb(178, 58, 32));
                    pb.setVisibility(View.GONE);
                    btnRow.removeAllViews();
                    TextView retry = dlgTextBtn("重试", true);
                    TextView close = dlgTextBtn("关闭", false);
                    retry.setOnClickListener(v -> { dlg.dismiss(); startGenerateSubtitles(); });
                    close.setOnClickListener(v -> dlg.dismiss());
                    btnRow.addView(retry);
                    btnRow.addView(close);
                });
            }
        }).start();
    }

    /** 长按 CC 打开：显示方式（关/原文/中文/双语）+ 字幕颜色 + 换字幕文件。 */
    private void showSubtitleSettings() {
        LinearLayout card = glassCard();
        card.addView(dlgTitle("字幕设置"));
        TextView modeLabel = new TextView(this);
        modeLabel.setText("显示方式");
        modeLabel.setTextSize(13.5f);
        modeLabel.setTextColor(Color.rgb(140, 128, 110));
        modeLabel.setPadding(0, 0, 0, dp(8));
        card.addView(modeLabel);
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setPadding(0, 0, 0, dp(14));
        final String[] modeNames = {"关", "原文", "中文", "双语"};
        final TextView[] modeBtns = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int m = i;
            TextView b = chip(modeNames[i]);
            b.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            b.setLayoutParams(lp);
            styleToggle(b, subMode == i);
            b.setOnClickListener(v -> {
                subMode = m;
                for (int j = 0; j < 4; j++) styleToggle(modeBtns[j], j == subMode);
                if (subMode >= 2) ensureTranslated();
                refreshChips();
                setControls(true);
                if (mp != null) {
                    try { updateSubtitle(mp.getCurrentPosition()); } catch (Exception ignored) { }
                }
            });
            modeBtns[i] = b;
            modeRow.addView(b);
        }
        card.addView(modeRow);
        TextView colorLabel = new TextView(this);
        colorLabel.setText("字幕颜色");
        colorLabel.setTextSize(13.5f);
        colorLabel.setTextColor(Color.rgb(140, 128, 110));
        colorLabel.setPadding(0, 0, 0, dp(8));
        card.addView(colorLabel);
        final int[] colorVals = {0, 0xFFFFFFFF, 0xFFFFF3C4, 0xFFFF6B4A,
                0xFF7FD4FF, 0xFF9BE15D, 0xFFFFB3C7};
        final String[] colorNames = {"默认", "纯白", "淡黄", "朱红", "天蓝", "草绿", "粉红"};
        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setPadding(0, 0, 0, dp(14));
        final View[] swatches = new View[colorVals.length];
        for (int i = 0; i < colorVals.length; i++) {
            final int ci = i;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            col.setLayoutParams(colLp);
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(colorVals[i] == 0 ? Color.rgb(238, 232, 222) : colorVals[i]);
            dot.setBackground(g);
            col.addView(dot, new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView nm = new TextView(this);
            nm.setText(colorNames[i]);
            nm.setTextSize(10.5f);
            nm.setTextColor(Color.rgb(140, 128, 110));
            nm.setPadding(0, dp(3), 0, 0);
            col.addView(nm);
            swatches[i] = dot;
            col.setOnClickListener(v -> {
                subColor = colorVals[ci];
                savePrefs();
                applySubColors();
                styleSwatches(swatches, colorVals);
            });
            colorRow.addView(col);
        }
        styleSwatches(swatches, colorVals);
        card.addView(colorRow);
        LinearLayout btnRow = new LinearLayout(this);
        TextView pick = dlgTextBtn("选字幕文件", false);
        TextView done = dlgTextBtn("完成", true);
        btnRow.addView(pick);
        btnRow.addView(done);
        card.addView(btnRow);
        final android.app.Dialog dlg = glassDialog(card);
        pick.setOnClickListener(v -> { dlg.dismiss(); pickSubtitle(); });
        done.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    private void styleSwatches(View[] swatches, int[] colorVals) {
        for (int i = 0; i < swatches.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(colorVals[i] == 0 ? Color.rgb(238, 232, 222) : colorVals[i]);
            boolean sel = subColor == colorVals[i];
            g.setStroke(sel ? dp(3) : 1, sel ? Color.rgb(224, 68, 31)
                    : Color.argb(160, 180, 165, 145));
            swatches[i].setBackground(g);
        }
    }

    private void pickSubtitle() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_SUB_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int rc, int result, Intent data) {
        super.onActivityResult(rc, result, data);
        if (rc != REQ_SUB_PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        final String dispName = queryName(uri);
        if (dispName != null && !Subtitles.isSubtitleFile(dispName)) {
            Toast.makeText(this, "请选择字幕文件（.srt / .vtt / .ass）", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                String ext = ".srt";
                if (dispName != null) {
                    int d = dispName.lastIndexOf('.');
                    if (d > 0) ext = dispName.substring(d).toLowerCase();
                }
                File dst = new File(getCacheDir(), "manual_sub" + ext);
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("打不开字幕文件");
                FileOutputStream fos = new FileOutputStream(dst);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                in.close();
                java.util.List<Subtitles.Cue> parsed = Subtitles.parse(dst);
                boolean hadZh = Subtitles.loadZh(this, dst, parsed);
                ui.post(() -> {
                    if (parsed.isEmpty()) {
                        Toast.makeText(this, "这个字幕文件没解析出内容", Toast.LENGTH_LONG).show();
                        return;
                    }
                    cues = parsed;
                    subMostlyZh = Subtitles.isMostlyChinese(parsed);
                    subFile = dst;
                    subMode = 1;
                    refreshChips();
                    Toast.makeText(this, "字幕已加载：" + parsed.size() + " 条"
                            + (hadZh ? "（含缓存译文）" : "（点 CC 切双语翻译）"),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "字幕加载失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String queryName(android.net.Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) { } finally {
            if (c != null) c.close();
        }
        return name;
    }

    private void updateSubtitle(int pos) {
        if (subLayer == null) return;
        if (subMode == 0 || cues == null || cues.isEmpty()) { hideSubLines(); return; }
        int lo = 0, hi = cues.size() - 1, idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).start <= pos) { idx = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        Subtitles.Cue cue = idx >= 0 ? cues.get(idx) : null;
        if (cue == null || pos > cue.end) { hideSubLines(); return; }
        subLayer.setVisibility(View.VISIBLE);
        boolean showOrig = subMode == 1 || subMode == 3;
        boolean showZh = subMode >= 2;
        if (showOrig) {
            subOrigTv.setText(cue.text);
            subOrigTv.setVisibility(View.VISIBLE);
        } else {
            subOrigTv.setVisibility(View.GONE);
        }
        if (showZh) {
            String zh = cue.zh;
            if ((zh == null || zh.isEmpty()) && subMostlyZh) zh = cue.text;
            if (zh != null && !zh.isEmpty()) {
                subZhTv.setText(zh);
                subZhTv.setVisibility(View.VISIBLE);
            } else if (subTranslating) {
                subZhTv.setText("正在翻译…");
                subZhTv.setVisibility(View.VISIBLE);
            } else {
                subZhTv.setVisibility(View.GONE);
            }
        } else {
            subZhTv.setVisibility(View.GONE);
        }
    }

    private void releasePlayer() {
        ui.removeCallbacks(ticker);
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
    }

    /** 按当前模式 + 屏幕方向计算画面变换。自动模式：竖屏完整、横屏填充。 */
    private void applyTransform() {
        if (texture == null || videoW == 0 || videoH == 0) return;
        int vw = texture.getWidth(), vh = texture.getHeight();
        if (vw == 0 || vh == 0) return;
        int eff = mode;
        if (eff == MODE_AUTO) {
            boolean landscapeScreen = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            eff = landscapeScreen ? MODE_CROP : MODE_FIT;
        }
        Matrix m = new Matrix();
        if (eff != MODE_STRETCH) {
            float va = (float) vw / vh;
            float da = (float) videoW / videoH;
            if (eff == MODE_FIT) {
                if (da > va) m.setScale(1f, va / da, vw / 2f, vh / 2f);
                else m.setScale(da / va, 1f, vw / 2f, vh / 2f);
            } else { // CROP
                if (da > va) m.setScale(da / va, 1f, vw / 2f, vh / 2f);
                else m.setScale(1f, va / da, vw / 2f, vh / 2f);
            }
        }
        texture.setTransform(m);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (texture != null) texture.post(this::applyTransform);
        if (frameRoot != null) mountChrome();
    }

    // ---------- 动态壁纸 ----------

    private void setAsWallpaper() {
        String path = paths.get(index);
        if (!new File(path).exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences("player", MODE_PRIVATE).edit()
                .putString("wp_path", path).apply();
        try {
            Intent it = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            it.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, VideoWpService.class));
            startActivity(it);
        } catch (Exception e) {
            Toast.makeText(this, "这个系统没有第三方动态壁纸入口，可到系统主题里试试", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Surface 生命周期 ----------

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        playCurrent();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        texture.post(this::applyTransform);
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releasePlayer();
        if (surface != null) { surface.release(); surface = null; }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    @Override protected void onPause() {
        super.onPause();
        if (mp != null && mp.isPlaying()) { mp.pause(); playBtn.setText("▶"); }
    }

    @Override protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }
}
