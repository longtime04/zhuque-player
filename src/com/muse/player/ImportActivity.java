package com.muse.player;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 万能导入页（v2.0 页面化）：
 * 1) 其他 App 分享进来（Wallpaper Engine 长按壁纸 → 分享 → 朱雀解析）自动开解；
 * 2) 主页进来点选文件框自己挑；
 * 3) force_scan=true 时为「深度扫描」模式，跳过包解析直接雕刻。
 * 解包走 PkgImport 调度链，结果文件进相册「朱雀导入」，可在本页直接打开。
 */
public class ImportActivity extends Activity {

    private static final int PICK_REQ = 41;

    private boolean forceScan = false;
    private boolean busy = false;
    private LinearLayout statusCard, resultCard, errorCard;
    private TextView statusText, resultTitle, resultFiles, errorText;
    private TextView playBtn;
    private List<File> lastMedia = new ArrayList<>();

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        forceScan = getIntent().getBooleanExtra("force_scan", false);
        getWindow().setStatusBarColor(MainActivity.INK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildUi());

        Uri incoming = null;
        Intent it = getIntent();
        if (Intent.ACTION_SEND.equals(it.getAction())) {
            incoming = it.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(it.getAction())) {
            ArrayList<Uri> list = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null && !list.isEmpty()) incoming = list.get(0);
        } else if (Intent.ACTION_VIEW.equals(it.getAction())) {
            incoming = it.getData();
        } else if (it.getData() != null) {
            incoming = it.getData();
        }
        if (incoming != null) startImport(incoming);
    }

    // ---------- 界面 ----------

    private View buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(MainActivity.PAGE_BG);
        View blob = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(96, 255, 179, 126));
        blob.setBackground(bg);
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(dp(280), dp(280));
        bLp.gravity = Gravity.TOP | Gravity.END;
        bLp.setMargins(0, -dp(120), -dp(100), 0);
        frame.addView(blob, bLp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(18));
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(22);
        back.setTextColor(Color.rgb(107, 97, 84));
        back.setGravity(Gravity.CENTER);
        back.setBackground(MainActivity.glassBg(165, 999, this));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView title = new TextView(this);
        title.setText(forceScan ? "  深度扫描" : "  万能导入");
        title.setTextColor(MainActivity.WHITE);
        title.setTextSize(19);
        title.getPaint().setFakeBoldText(true);
        bar.addView(title);
        root.addView(bar);

        TextView lead = new TextView(this);
        lead.setText(forceScan
                ? "不认识的包也能试：把整个文件逐段扫描，\n把藏在里面的视频、图片雕出来。"
                : "把包丢进来，能解的当场解开，视频图片直接进相册。\n在别的 App 里分享文件时选「朱雀解析」也一样。");
        lead.setTextColor(MainActivity.MUTED);
        lead.setTextSize(12.5f);
        lead.setLineSpacing(0, 1.5f);
        LinearLayout.LayoutParams leadLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        leadLp.topMargin = dp(12);
        root.addView(lead, leadLp);

        // 选文件框
        LinearLayout drop = new LinearLayout(this);
        drop.setOrientation(LinearLayout.VERTICAL);
        drop.setGravity(Gravity.CENTER);
        drop.setPadding(dp(16), dp(22), dp(16), dp(18));
        GradientDrawable dg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(200, 255, 255, 255), Color.argb(140, 255, 214, 170)});
        dg.setCornerRadius(dp(24));
        dg.setStroke(dp(1), Color.argb(170, 242, 96, 60));
        drop.setBackground(dg);
        TextView big = new TextView(this);
        big.setText("📦");
        big.setTextSize(29);
        big.setGravity(Gravity.CENTER);
        drop.addView(big);
        TextView bt = new TextView(this);
        bt.setText(forceScan ? "点这里选要扫描的文件" : "点这里选文件");
        bt.setTextColor(MainActivity.WHITE);
        bt.setTextSize(14.5f);
        bt.getPaint().setFakeBoldText(true);
        bt.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btLp.topMargin = dp(7);
        drop.addView(bt, btLp);
        TextView bs = new TextView(this);
        bs.setText("壁纸包 / 压缩包 / 任意不认识的包都行");
        bs.setTextColor(MainActivity.MUTED);
        bs.setTextSize(11);
        bs.setGravity(Gravity.CENTER);
        drop.addView(bs);
        drop.setOnClickListener(v -> pickFile());
        LinearLayout.LayoutParams dropLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dropLp.topMargin = dp(14);
        root.addView(drop, dropLp);

        // 支持的包
        LinearLayout fmts = new LinearLayout(this);
        fmts.setOrientation(LinearLayout.VERTICAL);
        fmts.setBackground(MainActivity.glassBg(175, 22, this));
        fmts.setPadding(dp(15), dp(12), dp(15), dp(8));
        TextView fh = new TextView(this);
        fh.setText("支 持 的 包");
        fh.setTextColor(MainActivity.MUTED);
        fh.setTextSize(11);
        fmts.addView(fh);
        fmts.addView(fmtRow("Wallpaper 壁纸包 .mpkg", "✓ 精确解包", true));
        fmts.addView(fmtRow("压缩包 zip / tar / gz / bz2 / xz / 7z", "✓ 直接解", true));
        fmts.addView(fmtRow("不认识的包", "✓ 深度扫描兜底", true));
        fmts.addView(fmtRow("scene 场景包 / 真加密视频", "✗ 解不出，会说明", false));
        LinearLayout.LayoutParams fLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp2.topMargin = dp(12);
        root.addView(fmts, fLp2);

        // 状态卡
        statusCard = new LinearLayout(this);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setBackground(MainActivity.glassBg(175, 22, this));
        statusCard.setPadding(dp(15), dp(13), dp(15), dp(13));
        statusCard.addView(new ProgressBar(this));
        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(76, 70, 60));
        statusText.setTextSize(13);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        stLp.leftMargin = dp(12);
        statusCard.addView(statusText, stLp);
        statusCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams sLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp2.topMargin = dp(12);
        root.addView(statusCard, sLp2);

        // 结果卡
        resultCard = new LinearLayout(this);
        resultCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(150, 255, 178, 120), Color.argb(115, 255, 255, 255)});
        rg.setCornerRadius(dp(22));
        rg.setStroke(1, Color.argb(190, 255, 255, 255));
        resultCard.setBackground(rg);
        resultCard.setPadding(dp(15), dp(13), dp(15), dp(13));
        resultTitle = new TextView(this);
        resultTitle.setTextColor(Color.rgb(194, 72, 31));
        resultTitle.setTextSize(13.5f);
        resultTitle.getPaint().setFakeBoldText(true);
        resultCard.addView(resultTitle);
        resultFiles = new TextView(this);
        resultFiles.setTextColor(Color.rgb(76, 70, 60));
        resultFiles.setTextSize(12.5f);
        resultFiles.setLineSpacing(0, 1.5f);
        LinearLayout.LayoutParams rfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rfLp.topMargin = dp(6);
        resultCard.addView(resultFiles, rfLp);
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(10);
        resultCard.addView(btnRow, brLp);
        TextView again = pillBtn("再导一个", false);
        again.setOnClickListener(v -> pickFile());
        btnRow.addView(again);
        playBtn = pillBtn("立即打开", true);
        playBtn.setOnClickListener(v -> openFirst());
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.leftMargin = dp(9);
        btnRow.addView(playBtn, pbLp);
        resultCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams rLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp2.topMargin = dp(12);
        root.addView(resultCard, rLp2);

        // 错误卡
        errorCard = new LinearLayout(this);
        errorCard.setOrientation(LinearLayout.VERTICAL);
        errorCard.setBackground(MainActivity.glassBg(175, 22, this));
        errorCard.setPadding(dp(15), dp(13), dp(15), dp(13));
        TextView eh = new TextView(this);
        eh.setText("这个包没解出来");
        eh.setTextColor(Color.rgb(194, 72, 31));
        eh.setTextSize(13.5f);
        eh.getPaint().setFakeBoldText(true);
        errorCard.addView(eh);
        errorText = new TextView(this);
        errorText.setTextColor(Color.rgb(76, 70, 60));
        errorText.setTextSize(12.5f);
        errorText.setLineSpacing(0, 1.55f);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eLp.topMargin = dp(6);
        errorCard.addView(errorText, eLp);
        errorCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams eLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eLp2.topMargin = dp(12);
        root.addView(errorCard, eLp2);
        return frame;
    }

    private View fmtRow(String name, String state, boolean ok) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(Color.rgb(76, 70, 60));
        n.setTextSize(12.5f);
        row.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView s = new TextView(this);
        s.setText(state);
        s.setTextColor(ok ? MainActivity.VERM_DARK : MainActivity.MUTED);
        s.setTextSize(11.5f);
        row.addView(s);
        return row;
    }

    private TextView pillBtn(String text, boolean primary) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12.5f);
        v.setPadding(dp(15), dp(8), dp(15), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(999));
        if (primary) {
            g.setColor(MainActivity.VERM);
            v.setTextColor(Color.WHITE);
        } else {
            g.setColor(Color.argb(150, 255, 255, 255));
            g.setStroke(1, Color.rgb(227, 213, 190));
            v.setTextColor(Color.rgb(107, 97, 84));
        }
        v.setBackground(g);
        return v;
    }

    // ---------- 导入流程 ----------

    private void pickFile() {
        if (busy) return;
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try {
            startActivityForResult(it, PICK_REQ);
        } catch (Exception e) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int rc, int result, Intent data) {
        super.onActivityResult(rc, result, data);
        if (rc == PICK_REQ && result == RESULT_OK && data != null && data.getData() != null) {
            startImport(data.getData());
        }
    }

    private void startImport(final Uri uri) {
        if (busy) return;
        busy = true;
        resultCard.setVisibility(View.GONE);
        errorCard.setVisibility(View.GONE);
        statusCard.setVisibility(View.VISIBLE);
        statusText.setText(forceScan ? "正在深度扫描…" : "正在解包，大包要等一会儿…");
        final String name = queryName(uri);
        new Thread(() -> {
            try {
                PkgImport.Result r = PkgImport.importAny(this, uri, name, forceScan);
                runOnUiThread(() -> onImported(r));
            } catch (Exception e) {
                runOnUiThread(() -> onImportError(e));
            }
        }).start();
    }

    private void onImported(PkgImport.Result r) {
        busy = false;
        statusCard.setVisibility(View.GONE);
        if (r == null || r.media.isEmpty()) {
            errorText.setText("包里没有找到完整的视频或图片。\n"
                    + "· scene 场景壁纸包是程序不是视频，拆不出；\n"
                    + "· 真正加密的视频，钥匙在对方服务器手里，文件本身解不了；\n"
                    + "· 也可能这个包里压根没有媒体文件。");
            errorCard.setVisibility(View.VISIBLE);
            return;
        }
        lastMedia = r.media;
        resultTitle.setText("✓ 解出来了（" + (r.how.isEmpty() ? "导入" : r.how) + "）");
        StringBuilder sb = new StringBuilder();
        for (File f : r.media) sb.append(f.getName()).append("\n");
        sb.append("已存到相册 · 朱雀导入");
        resultFiles.setText(sb.toString().trim());
        resultCard.setVisibility(View.VISIBLE);
    }

    private void onImportError(Exception e) {
        busy = false;
        statusCard.setVisibility(View.GONE);
        errorText.setText("读取或解包失败：" + (e.getMessage() == null ? e.toString() : e.getMessage())
                + "\n换个文件试试，或者这个包本身已经损坏。");
        errorCard.setVisibility(View.VISIBLE);
    }

    private void openFirst() {
        if (lastMedia.isEmpty()) return;
        ArrayList<String> vPaths = new ArrayList<>(), vNames = new ArrayList<>();
        ArrayList<String> iPaths = new ArrayList<>(), iNames = new ArrayList<>();
        for (File f : lastMedia) {
            if (MpkgImport.isVideo(f.getName())) { vPaths.add(f.getPath()); vNames.add(f.getName()); }
            else { iPaths.add(f.getPath()); iNames.add(f.getName()); }
        }
        if (!vPaths.isEmpty()) {
            Intent it = new Intent(this, PlayerActivity.class);
            it.putStringArrayListExtra("paths", vPaths);
            it.putStringArrayListExtra("names", vNames);
            it.putExtra("index", 0);
            startActivity(it);
        } else if (!iPaths.isEmpty()) {
            Intent it = new Intent(this, ImageActivity.class);
            it.putStringArrayListExtra("paths", iPaths);
            it.putStringArrayListExtra("names", iNames);
            it.putExtra("index", 0);
            startActivity(it);
        }
    }

    private String queryName(Uri uri) {
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
        if (name == null && uri.getLastPathSegment() != null) {
            name = uri.getLastPathSegment();
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
        }
        return name == null ? "未命名文件" : name;
    }
}
