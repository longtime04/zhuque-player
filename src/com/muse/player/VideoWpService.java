package com.muse.player;

import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.io.File;

/** 视频动态壁纸服务：循环、静音播放 player 偏好里选定的视频文件。 */
public class VideoWpService extends WallpaperService {

    @Override public Engine onCreateEngine() {
        return new Engine() {
            private MediaPlayer mp;

            private String currentPath() {
                SharedPreferences sp = getSharedPreferences("player", MODE_PRIVATE);
                return sp.getString("wp_path", null);
            }

            @Override public void onSurfaceCreated(SurfaceHolder holder) {
                super.onSurfaceCreated(holder);
                startPlayback(holder);
            }

            @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                super.onSurfaceChanged(holder, format, w, h);
                if (mp == null) startPlayback(holder);
            }

            private void startPlayback(SurfaceHolder holder) {
                stopPlayback();
                String path = currentPath();
                if (path == null || !new File(path).exists()) return;
                try {
                    mp = new MediaPlayer();
                    mp.setSurface(holder.getSurface());
                    mp.setDataSource(path);
                    mp.setLooping(true);
                    mp.setVolume(0f, 0f);
                    mp.setOnPreparedListener(p -> {
                        if (isVisible()) p.start();
                    });
                    mp.prepareAsync();
                } catch (Exception e) {
                    stopPlayback();
                }
            }

            @Override public void onVisibilityChanged(boolean visible) {
                super.onVisibilityChanged(visible);
                if (mp == null) return;
                try {
                    if (visible) mp.start();
                    else mp.pause();
                } catch (Exception ignored) { }
            }

            @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
                stopPlayback();
                super.onSurfaceDestroyed(holder);
            }

            @Override public void onDestroy() {
                stopPlayback();
                super.onDestroy();
            }

            private void stopPlayback() {
                if (mp != null) {
                    try { mp.release(); } catch (Exception ignored) { }
                    mp = null;
                }
            }
        };
    }
}
