package com.muse.player;

import android.app.Activity;
import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;

/** 图片查看：双指缩放、拖动、双击放大、左右滑切换上下一张，可设为静态壁纸。 */
public class ImageActivity extends Activity {

    private ArrayList<String> paths, names;
    private int index;
    private ImageView iv;
    private TextView titleTv;
    private final Matrix matrix = new Matrix();
    private final Matrix saved = new Matrix();
    private final PointF start = new PointF();
    private final PointF mid = new PointF();
    private float oldDist = 1f;
    private int touchMode = 0; // 0 无 1 拖 2 缩放
    private long downTime;
    private float downX;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        paths = getIntent().getStringArrayListExtra("paths");
        names = getIntent().getStringArrayListExtra("names");
        index = getIntent().getIntExtra("index", 0);
        if (paths == null || paths.isEmpty()) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.MATRIX);
        root.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setOnTouchListener(this::onTouch);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.argb(110, 0, 0, 0));
        bar.setPadding(dp(8), dp(10), dp(12), dp(10));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(Color.WHITE);
        back.setTextSize(26);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        titleTv = new TextView(this);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(14.5f);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView setWp = new TextView(this);
        setWp.setText("设为壁纸");
        setWp.setTextColor(Color.rgb(222, 198, 132));
        setWp.setTextSize(13f);
        setWp.getPaint().setFakeBoldText(true);
        setWp.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(999));
        gd.setStroke(dp(1), Color.argb(170, 222, 198, 132));
        setWp.setBackground(gd);
        setWp.setOnClickListener(v -> setAsWallpaper());
        bar.addView(setWp);
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.gravity = Gravity.TOP;
        root.addView(bar, bLp);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        TextView prev = navBtn("‹ 上一张", v -> step(-1));
        TextView next = navBtn("下一张 ›", v -> step(1));
        nav.addView(prev);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.leftMargin = dp(16);
        nav.addView(next, nLp);
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.BOTTOM;
        navLp.bottomMargin = dp(18);
        root.addView(nav, navLp);

        setContentView(root);
        show();
    }

    private TextView navBtn(String t, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(13);
        v.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(999));
        g.setColor(Color.argb(90, 0, 0, 0));
        g.setStroke(1, Color.argb(110, 255, 255, 255));
        v.setBackground(g);
        v.setOnClickListener(l);
        return v;
    }

    private void step(int d) {
        int n = index + d;
        if (n < 0) n = paths.size() - 1;
        if (n >= paths.size()) n = 0;
        index = n;
        show();
    }

    private void show() {
        titleTv.setText(names.get(index));
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        Bitmap bmp = BitmapFactory.decodeFile(paths.get(index), o);
        if (bmp == null) {
            Toast.makeText(this, "这张图片打不开", Toast.LENGTH_SHORT).show();
            return;
        }
        // 超大图降采样防爆内存
        int maxSide = Math.max(bmp.getWidth(), bmp.getHeight());
        if (maxSide > 4096) {
            int s = 1;
            while (maxSide / s > 4096) s *= 2;
            o.inSampleSize = s;
            bmp = BitmapFactory.decodeFile(paths.get(index), o);
        }
        iv.setImageBitmap(bmp);
        iv.post(this::fitToView);
    }

    private void fitToView() {
        if (iv.getDrawable() == null) return;
        int iw = iv.getDrawable().getIntrinsicWidth();
        int ih = iv.getDrawable().getIntrinsicHeight();
        int vw = iv.getWidth(), vh = iv.getHeight();
        if (iw == 0 || vw == 0) return;
        float scale = Math.min((float) vw / iw, (float) vh / ih);
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate((vw - iw * scale) / 2f, (vh - ih * scale) / 2f);
        iv.setImageMatrix(matrix);
    }

    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                saved.set(matrix);
                start.set(e.getX(), e.getY());
                downX = e.getX();
                downTime = System.currentTimeMillis();
                touchMode = 1;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                oldDist = spacing(e);
                if (oldDist > 10f) {
                    saved.set(matrix);
                    midPoint(mid, e);
                    touchMode = 2;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (touchMode == 1) {
                    matrix.set(saved);
                    matrix.postTranslate(e.getX() - start.x, e.getY() - start.y);
                } else if (touchMode == 2) {
                    float nd = spacing(e);
                    if (nd > 10f) {
                        matrix.set(saved);
                        float s = nd / oldDist;
                        matrix.postScale(s, s, mid.x, mid.y);
                    }
                }
                iv.setImageMatrix(matrix);
                break;
            case MotionEvent.ACTION_UP:
                // 短距离快速滑动 = 切换上一张/下一张（未缩放时）
                float dx = e.getX() - downX;
                if (System.currentTimeMillis() - downTime < 300 && Math.abs(dx) > dp(70)) {
                    step(dx < 0 ? 1 : -1);
                }
                touchMode = 0;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                touchMode = 0;
                break;
        }
        return true;
    }

    private float spacing(MotionEvent e) {
        float x = e.getX(0) - e.getX(1);
        float y = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private void midPoint(PointF p, MotionEvent e) {
        p.set((e.getX(0) + e.getX(1)) / 2f, (e.getY(0) + e.getY(1)) / 2f);
    }

    private void setAsWallpaper() {
        final String path = paths.get(index);
        Toast.makeText(this, "正在设置…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(this);
                Bitmap bmp = BitmapFactory.decodeFile(path);
                if (bmp == null) throw new Exception("图片读取失败");
                wm.setBitmap(bmp);
                runOnUiThread(() -> Toast.makeText(this, "已设为壁纸", Toast.LENGTH_SHORT).show());
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "设置失败：" + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
