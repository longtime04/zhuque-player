package com.muse.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.content.res.ColorStateList;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 媒体库主页：MediaStore 扫描本机视频/图片，网格画廊 + 全部/视频/图片筛选 + 按文件夹浏览。
 */
public class MainActivity extends Activity {

    // 朱雀解析 · 小清新液态玻璃配色（v2.0 起）
    static final int INK = Color.rgb(253, 249, 240);
    static final int INK2 = Color.rgb(248, 236, 220);
    static final int TEAL = Color.rgb(242, 96, 60);
    static final int JADE = Color.rgb(255, 255, 255);
    static final int GOLD = Color.rgb(176, 124, 46);
    static final int MUTED = Color.rgb(151, 137, 122);
    static final int PAGE_BG = Color.rgb(248, 241, 229);
    static final int WHITE = Color.rgb(51, 48, 43);
    static final int VERM = Color.rgb(242, 96, 60);
    static final int VERM_DARK = Color.rgb(224, 68, 31);
    static final String GITHUB_URL = "https://github.com/longtime04";
    static final String DONATE_URL = "https://longtime04.github.io/donate/";

    private final List<MediaItem> all = new ArrayList<>();
    private final List<MediaItem> shown = new ArrayList<>();
    private int filter = 0; // 0 全部 1 视频 2 图片
    private String bucketFilter = null; // null = 全部文件夹
    private GridAdapter adapter;
    private TextView folderLabel;
    private TextView emptyText;
    private final TextView[] chips = new TextView[3];

    static final LruCache<String, Bitmap> THUMBS = new LruCache<>(96);
    static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private boolean created;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(INK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildUi());
        ensurePermission();
        created = true;
    }

    @Override protected void onResume() {
        super.onResume();
        // 从导入页/播放页回来时刷新一遍，让新导入的视频立即出现在列表里
        if (created && !all.isEmpty()) loadMedia();
    }

    private View buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(PAGE_BG);
        addBlob(frame, Color.argb(96, 255, 179, 126), dp(300), Gravity.TOP | Gravity.END, 0, -dp(120), -dp(100), 0);
        addBlob(frame, Color.argb(66, 247, 141, 116), dp(250), Gravity.BOTTOM | Gravity.START, -dp(120), 0, 0, dp(150));
        addBlob(frame, Color.argb(58, 168, 216, 185), dp(200), Gravity.BOTTOM | Gravity.END, 0, 0, -dp(70), -dp(70));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable tg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{INK, INK2});
        top.setBackground(tg);
        top.setPadding(dp(18), dp(18), dp(18), dp(14));
        LinearLayout trow = new LinearLayout(this);
        trow.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = new TextView(this);
        logo.setText("▶");
        logo.setTextColor(JADE);
        logo.setTextSize(16);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(255, 122, 77), VERM_DARK});
        lg.setCornerRadius(dp(13));
        logo.setBackground(lg);
        trow.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("  朱雀解析");
        title.setTextColor(WHITE);
        title.setTextSize(19);
        title.getPaint().setFakeBoldText(true);
        trow.addView(title);
        TextView sub = new TextView(this);
        sub.setText("播放 · 万能解包");
        sub.setTextColor(MUTED);
        sub.setTextSize(11f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.leftMargin = dp(8);
        trow.addView(sub, subLp);
        View spacer = new View(this);
        trow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        ImageView ghBtn = new ImageView(this);
        try {
            ghBtn.setImageBitmap(android.graphics.BitmapFactory.decodeStream(
                    getAssets().open("icons/github.png")));
        } catch (Exception ignored) { }
        ghBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        ghBtn.setBackground(glassBg(150, 999, this));
        ghBtn.setOnClickListener(v -> openUrl(GITHUB_URL));
        trow.addView(ghBtn, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextSize(15);
        gear.setTextColor(Color.rgb(107, 97, 84));
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(glassBg(150, 999, this));
        gear.setOnClickListener(v -> showAbout());
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        gearLp.leftMargin = dp(8);
        trow.addView(gear, gearLp);
        top.addView(trow);

        // 文件夹行
        LinearLayout folder = new LinearLayout(this);
        folder.setGravity(Gravity.CENTER_VERTICAL);
        folder.setBackground(glassBg(165, 16, this));
        folder.setPadding(dp(12), dp(9), dp(10), dp(9));
        folderLabel = new TextView(this);
        folderLabel.setText("正在读取媒体库…");
        folderLabel.setTextColor(Color.rgb(107, 97, 84));
        folderLabel.setTextSize(12.5f);
        folder.addView(folderLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView chg = new TextView(this);
        chg.setText("按文件夹看");
        chg.setTextColor(VERM_DARK);
        chg.setTextSize(12.5f);
        chg.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable cg = new GradientDrawable();
        cg.setCornerRadius(dp(999));
        cg.setColor(Color.argb(120, 255, 255, 255));
        cg.setStroke(1, Color.argb(150, 242, 96, 60));
        chg.setBackground(cg);
        chg.setOnClickListener(v -> showBucketDialog());
        folder.addView(chg);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = dp(13);
        top.addView(folder, fLp);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(12);
        top.addView(buildToolsCard(), cardLp);
        top.addView(buildDonateCard(), cardLp);
        root.addView(top);

        // 筛选 chips
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(14), dp(13), dp(14), dp(2));
        String[] names = {"全部", "视频", "图片"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView c = new TextView(this);
            c.setText(names[i]);
            c.setTextSize(13);
            c.setPadding(dp(15), dp(6), dp(15), dp(6));
            c.setOnClickListener(v -> { filter = idx; refreshChips(); applyFilter(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(8);
            tabs.addView(c, lp);
            chips[i] = c;
        }
        root.addView(tabs);
        refreshChips();

        // 网格
        GridView grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setPadding(dp(14), dp(10), dp(14), dp(14));
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        adapter = new GridAdapter();
        adapter.host = this;
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> openItem(pos));
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        emptyText = new TextView(this);
        emptyText.setText("没有读到视频或图片。\n把文件放进手机存储后，点这里重新读取。");
        emptyText.setTextColor(MUTED);
        emptyText.setTextSize(13.5f);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setLineSpacing(0, 1.5f);
        emptyText.setVisibility(View.GONE);
        emptyText.setOnClickListener(v -> ensurePermission());
        root.addView(emptyText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    /** 背景光斑：大号半透明色圆，让玻璃卡片有颜色可透 */
    private void addBlob(FrameLayout parent, int color, int size, int gravity,
                         int left, int top, int right, int bottom) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = gravity;
        lp.setMargins(left, top, right, bottom);
        parent.addView(v, lp);
    }

    static GradientDrawable glassBg(int alpha, int cornerDp, Activity a) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(alpha, 255, 255, 255), Color.argb(alpha - 40, 255, 255, 255)});
        g.setCornerRadius((int) (cornerDp * a.getResources().getDisplayMetrics().density + 0.5f));
        g.setStroke(1, Color.argb(190, 255, 255, 255));
        return g;
    }

    private void refreshChips() {
        for (int i = 0; i < 3; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(999));
            if (i == filter) {
                g.setColor(TEAL);
                chips[i].setTextColor(Color.WHITE);
            } else {
                g.setColor(Color.argb(150, 255, 255, 255));
                g.setStroke(1, Color.rgb(227, 213, 190));
                chips[i].setTextColor(Color.rgb(138, 124, 104));
            }
            chips[i].setBackground(g);
        }
    }

    // ---------- 工具卡 / 咖啡卡 / 关于 ----------

    private View buildToolsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glassBg(175, 22, this));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView tt = new TextView(this);
        tt.setText("工 具");
        tt.setTextColor(MUTED);
        tt.setTextSize(11);
        card.addView(tt);
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(10);
        card.addView(row, rLp);
        row.addView(toolCell("📦", "万能导入", "壁纸包 / 压缩包\n导进来就解开", Color.rgb(253, 243, 227),
                v -> startActivity(new Intent(this, ImportActivity.class))), toolLp(0));
        row.addView(toolCell("🔍", "深度扫描", "不认识的包\n把视频雕出来", Color.rgb(240, 247, 240),
                v -> {
                    Intent it = new Intent(this, ImportActivity.class);
                    it.putExtra("force_scan", true);
                    startActivity(it);
                }), toolLp(dp(9)));
        row.addView(toolCell("🗨", "字幕翻译", "点 CC 选字幕\n没字幕能语音生成", Color.rgb(253, 240, 236),
                v -> showSubtitleHelp()), toolLp(dp(9)));
        return card;
    }

    private LinearLayout.LayoutParams toolLp(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = left;
        return lp;
    }

    private View toolCell(String icon, String name, String desc, int tint, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(tint);
        g.setCornerRadius(dp(16));
        g.setStroke(1, Color.argb(200, 255, 255, 255));
        c.setBackground(g);
        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(17);
        c.addView(ic);
        TextView nm = new TextView(this);
        nm.setText(name);
        nm.setTextColor(WHITE);
        nm.setTextSize(13);
        nm.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = dp(5);
        c.addView(nm, nLp);
        TextView ds = new TextView(this);
        ds.setText(desc);
        ds.setTextColor(MUTED);
        ds.setTextSize(10);
        ds.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = dp(2);
        c.addView(ds, dLp);
        c.setOnClickListener(l);
        return c;
    }

    private View buildDonateCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(150, 255, 196, 140), Color.argb(115, 255, 255, 255)});
        g.setCornerRadius(dp(20));
        g.setStroke(1, Color.argb(190, 255, 255, 255));
        card.setBackground(g);
        card.setPadding(dp(14), dp(11), dp(12), dp(11));
        TextView cf = new TextView(this);
        cf.setText("☕");
        cf.setTextSize(19);
        card.addView(cf);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView b = new TextView(this);
        b.setText("请作者喝杯咖啡");
        b.setTextColor(Color.rgb(124, 74, 30));
        b.setTextSize(13);
        b.getPaint().setFakeBoldText(true);
        col.addView(b);
        TextView s = new TextView(this);
        s.setText("朱雀解析 · 为爱发电");
        s.setTextColor(Color.rgb(165, 121, 63));
        s.setTextSize(10.5f);
        col.addView(s);
        LinearLayout.LayoutParams cLp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cLp2.leftMargin = dp(10);
        card.addView(col, cLp2);
        TextView go = new TextView(this);
        go.setText("去打赏");
        go.setTextColor(Color.WHITE);
        go.setTextSize(11.5f);
        go.setPadding(dp(13), dp(6), dp(13), dp(6));
        GradientDrawable gg = new GradientDrawable();
        gg.setColor(VERM);
        gg.setCornerRadius(dp(999));
        go.setBackground(gg);
        go.setOnClickListener(v -> openUrl(DONATE_URL));
        card.addView(go);
        card.setOnClickListener(v -> openUrl(DONATE_URL));
        return card;
    }

    void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- 玻璃弹窗（主页用，与播放页统一风格） ----------

    private LinearLayout glassCardM() {
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

    private android.app.Dialog glassDialogM(LinearLayout card) {
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

    private TextView dlgTitleM(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(17);
        v.setTextColor(Color.rgb(51, 45, 38));
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(0, 0, 0, dp(10));
        return v;
    }

    private TextView dlgBodyM(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14.5f);
        v.setTextColor(Color.rgb(96, 85, 68));
        v.setLineSpacing(0, 1.25f);
        v.setPadding(0, 0, 0, dp(12));
        return v;
    }

    private TextView dlgBtnM(String t, boolean primary) {
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

    private void showAbout() {
        String ver = "";
        try {
            ver = " v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        LinearLayout card = glassCardM();
        card.addView(dlgTitleM("朱雀解析" + ver));
        card.addView(dlgBodyM("播放 · 万能解包\n\n本地播放器 + 万能解包：Wallpaper 壁纸包、常见压缩包直接解，"
                + "不认识的包深度扫描把视频雕出来。\n\n字幕：外挂字幕自动加载、点 CC 在线翻译双语；"
                + "视频没字幕时点 CC 选「语音生成字幕」，听声音现认（首次自动下载约 160MB 模型，之后离线可用）。"
                + "\n\n作者 GitHub：longtime04"));
        LinearLayout row = new LinearLayout(this);
        TextView gh = dlgBtnM("GitHub 主页", false);
        TextView coffee = dlgBtnM("喝杯咖啡", true);
        TextView close = dlgBtnM("关闭", false);
        row.addView(gh);
        row.addView(coffee);
        row.addView(close);
        card.addView(row);
        final android.app.Dialog dlg = glassDialogM(card);
        gh.setOnClickListener(v -> { dlg.dismiss(); openUrl(GITHUB_URL); });
        coffee.setOnClickListener(v -> { dlg.dismiss(); openUrl(DONATE_URL); });
        close.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    /** 字幕工具卡：说明 + 语音模型状态/下载入口。 */
    private void showSubtitleHelp() {
        LinearLayout card = glassCardM();
        card.addView(dlgTitleM("字幕 & 语音生成"));
        card.addView(dlgBodyM("· 有字幕文件：打开视频点 CC 选字幕，再点一次 CC 切「双语」看中文翻译\n"
                + "· 没字幕：点 CC 选「语音生成字幕」，App 听声音现认，认完同样能翻中文"));
        final TextView status = dlgBodyM("");
        card.addView(status);
        final ProgressBar pb = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgressTintList(ColorStateList.valueOf(Color.rgb(224, 68, 31)));
        pb.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(70, 160, 140, 115)));
        pb.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        pbLp.bottomMargin = dp(12);
        pb.setLayoutParams(pbLp);
        card.addView(pb);
        LinearLayout row = new LinearLayout(this);
        final TextView dlBtn = dlgBtnM("", true);
        final TextView closeBtn = dlgBtnM("关闭", false);
        row.addView(dlBtn);
        row.addView(closeBtn);
        card.addView(row);
        final android.app.Dialog dlg = glassDialogM(card);
        final java.util.concurrent.atomic.AtomicBoolean cancel =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final boolean[] running = {false};
        Runnable refresh = () -> {
            if (AsrSubtitle.modelReady(this)) {
                status.setText("语音模型：已就绪 ✓（生成字幕全程离线）");
                dlBtn.setText("已下载");
                dlBtn.setEnabled(false);
                dlBtn.setAlpha(0.55f);
            } else {
                status.setText("语音模型：还没下载，点下面按钮下载（约 160MB，只下一次）");
                dlBtn.setText("点击下载语言模型");
                dlBtn.setEnabled(true);
                dlBtn.setAlpha(1f);
            }
        };
        refresh.run();
        dlBtn.setOnClickListener(v -> {
            if (running[0]) { cancel.set(true); return; }
            if (AsrSubtitle.modelReady(this)) return;
            running[0] = true;
            cancel.set(false);
            pb.setVisibility(View.VISIBLE);
            pb.setProgress(0);
            dlBtn.setText("取消下载");
            new Thread(() -> {
                try {
                    AsrSubtitle.downloadModel(this, (stage, pct) -> MAIN.post(() -> {
                        status.setText(stage + "… " + pct + "%");
                        pb.setProgress(pct);
                    }), cancel);
                    MAIN.post(() -> {
                        running[0] = false;
                        pb.setVisibility(View.GONE);
                        refresh.run();
                        Toast.makeText(this, "语音模型下载好了", Toast.LENGTH_SHORT).show();
                    });
                } catch (Throwable t) {
                    final String m = t.getMessage() != null ? t.getMessage()
                            : t.getClass().getSimpleName();
                    MAIN.post(() -> {
                        running[0] = false;
                        if (t instanceof AsrSubtitle.Cancelled) {
                            refresh.run();
                        } else {
                            status.setText("下载没成功：" + m);
                            dlBtn.setText("点击重新下载");
                            dlBtn.setEnabled(true);
                            dlBtn.setAlpha(1f);
                        }
                        pb.setVisibility(View.GONE);
                    });
                }
            }).start();
        });
        closeBtn.setOnClickListener(v -> {
            if (running[0]) cancel.set(true);
            dlg.dismiss();
        });
        dlg.show();
    }

    // ---------- 权限与扫描 ----------

    private void ensurePermission() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (need.isEmpty()) loadMedia();
        else requestPermissions(need.toArray(new String[0]), 7);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] ps, int[] rs) {
        super.onRequestPermissionsResult(rc, ps, rs);
        boolean ok = false;
        for (int r : rs) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
        if (ok) loadMedia();
        else {
            folderLabel.setText("没有读取权限");
            emptyText.setVisibility(View.VISIBLE);
            Toast.makeText(this, "需要存储读取权限才能列出本机视频和图片", Toast.LENGTH_LONG).show();
        }
    }

    private void loadMedia() {
        POOL.execute(() -> {
            List<MediaItem> found = new ArrayList<>();
            query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, found);
            query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, found);
            found.sort((a, b2) -> a.name.compareToIgnoreCase(b2.name));
            MAIN.post(() -> {
                all.clear();
                all.addAll(found);
                applyFilter();
                folderLabel.setText(bucketFilter == null
                        ? "全部文件夹（共 " + all.size() + " 个）"
                        : bucketFilter + "（共 " + shown.size() + " 个）");
            });
        });
    }

    private void query(Uri base, boolean video, List<MediaItem> out) {
        String[] cols = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                video ? MediaStore.Video.Media.DURATION : MediaStore.MediaColumns.DURATION
        };
        Cursor c = null;
        try {
            c = getContentResolver().query(base, cols, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                String path = c.getString(2);
                String bucket = c.getString(3);
                long dur = 0;
                try { dur = c.getLong(4); } catch (Exception ignored) { }
                if (name == null || path == null) continue;
                out.add(new MediaItem(ContentUris.withAppendedId(base, id), path, name, video, dur, bucket));
            }
        } catch (Exception e) {
            // 个别系统列缺失时跳过该类媒体
        } finally {
            if (c != null) c.close();
        }
    }

    private void applyFilter() {
        shown.clear();
        for (MediaItem m : all) {
            if (filter == 1 && !m.video) continue;
            if (filter == 2 && m.video) continue;
            if (bucketFilter != null && !bucketFilter.equals(m.bucket)) continue;
            shown.add(m);
        }
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        folderLabel.setText(bucketFilter == null
                ? "全部文件夹（共 " + all.size() + " 个）"
                : bucketFilter + "（共 " + shown.size() + " 个）");
    }

    private void showBucketDialog() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MediaItem m : all) counts.put(m.bucket, counts.getOrDefault(m.bucket, 0) + 1);
        List<String> names = new ArrayList<>();
        names.add("全部文件夹");
        names.addAll(counts.keySet());
        String[] labels = new String[names.size()];
        for (int i = 0; i < names.size(); i++) {
            labels[i] = i == 0 ? "全部文件夹（" + all.size() + "）"
                    : names.get(i) + "（" + counts.get(names.get(i)) + "）";
        }
        new AlertDialog.Builder(this)
                .setTitle("选择文件夹")
                .setItems(labels, (d, w) -> {
                    bucketFilter = w == 0 ? null : names.get(w);
                    applyFilter();
                })
                .show();
    }

    private void openItem(int pos) {
        MediaItem m = shown.get(pos);
        if (m.video) {
            ArrayList<String> paths = new ArrayList<>();
            ArrayList<String> namesL = new ArrayList<>();
            int start = 0;
            for (MediaItem x : shown) {
                if (!x.video) continue;
                if (x == m) start = paths.size();
                paths.add(x.path);
                namesL.add(x.name);
            }
            Intent it = new Intent(this, PlayerActivity.class);
            it.putStringArrayListExtra("paths", paths);
            it.putStringArrayListExtra("names", namesL);
            it.putExtra("index", start);
            startActivity(it);
        } else {
            ArrayList<String> paths = new ArrayList<>();
            ArrayList<String> namesL = new ArrayList<>();
            int start = 0;
            for (MediaItem x : shown) {
                if (x.video) continue;
                if (x == m) start = paths.size();
                paths.add(x.path);
                namesL.add(x.name);
            }
            Intent it = new Intent(this, ImageActivity.class);
            it.putStringArrayListExtra("paths", paths);
            it.putStringArrayListExtra("names", namesL);
            it.putExtra("index", start);
            startActivity(it);
        }
    }

    // ---------- 缩略图 ----------

    static void loadThumb(final Activity act, final MediaItem m, final ImageView iv, final String tag) {
        iv.setTag(tag);
        Bitmap hit = THUMBS.get(m.path);
        if (hit != null) { iv.setImageBitmap(hit); return; }
        iv.setImageBitmap(null);
        POOL.execute(() -> {
            Bitmap bmp = null;
            try {
                ContentResolver cr = act.getContentResolver();
                if (Build.VERSION.SDK_INT >= 29) {
                    bmp = cr.loadThumbnail(m.uri, new Size(360, 360), null);
                } else if (m.video) {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(m.path);
                    bmp = r.getFrameAtTime(0);
                    r.release();
                } else {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 4;
                    bmp = android.graphics.BitmapFactory.decodeFile(m.path, o);
                }
            } catch (Exception ignored) { }
            if (bmp != null) THUMBS.put(m.path, bmp);
            final Bitmap out = bmp;
            MAIN.post(() -> { if (tag.equals(iv.getTag()) && out != null) iv.setImageBitmap(out); });
        });
    }

    // ---------- 网格适配器 ----------

    static class GridAdapter extends BaseAdapter {
        MainActivity host;
        @Override public int getCount() { return host == null ? 0 : host.shown.size(); }
        @Override public Object getItem(int i) { return host.shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            MainActivity a = host;
            FrameLayout cell;
            ImageView iv;
            TextView badge, name;
            if (convert == null) {
                cell = new FrameLayout(a);
                cell.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, a.dp(196)));
                iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(Color.rgb(243, 231, 211));
                cell.addView(iv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                LinearLayout strip = new LinearLayout(a);
                strip.setGravity(Gravity.CENTER_VERTICAL);
                strip.setBackgroundColor(Color.argb(185, 255, 255, 255));
                strip.setPadding(a.dp(8), a.dp(5), a.dp(8), a.dp(5));
                badge = new TextView(a);
                badge.setTextSize(10.5f);
                badge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
                strip.addView(badge);
                name = new TextView(a);
                name.setTextColor(WHITE);
                name.setTextSize(11.5f);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                nLp.leftMargin = a.dp(7);
                strip.addView(name, nLp);
                FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sLp.gravity = Gravity.BOTTOM;
                cell.addView(strip, sLp);
                Object[] h = {iv, badge, name};
                cell.setTag(h);
            } else {
                cell = (FrameLayout) convert;
                Object[] h = (Object[]) cell.getTag();
                iv = (ImageView) h[0];
                badge = (TextView) h[1];
                name = (TextView) h[2];
            }
            MediaItem m = a.shown.get(pos);
            name.setText(m.name);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(a.dp(999));
            if (m.video) {
                badge.setText("▶ " + MediaItem.fmtDuration(m.durationMs));
                badge.setTextColor(Color.WHITE);
                bg.setColor(Color.argb(140, 0, 0, 0));
            } else {
                badge.setText("图片");
                badge.setTextColor(GOLD);
                bg.setColor(Color.argb(140, 0, 0, 0));
                bg.setStroke(1, Color.argb(140, 222, 198, 132));
            }
            badge.setBackground(bg);
            loadThumb(a, m, iv, m.path);
            return cell;
        }
    }

}
