package com.muse.player;

import android.net.Uri;

public class MediaItem {
    public final Uri uri;
    public final String path;
    public final String name;
    public final boolean video;
    public final long durationMs;
    public final String bucket;

    public MediaItem(Uri uri, String path, String name, boolean video, long durationMs, String bucket) {
        this.uri = uri;
        this.path = path;
        this.name = name;
        this.video = video;
        this.durationMs = durationMs;
        this.bucket = bucket == null ? "" : bucket;
    }

    public static String fmtDuration(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        if (h > 0) return h + ":" + String.format("%02d", m % 60) + ":" + String.format("%02d", s % 60);
        return m + ":" + String.format("%02d", s % 60);
    }
}
