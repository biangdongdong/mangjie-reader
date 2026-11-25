package com.mangjie.reader.media;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 音频源的播放引擎：只负责播，不碰界面。
 * 界面（播放页）在 MainActivity，按这里回报的状态刷新行文字。
 * 用系统 MediaPlayer，不引 media3；语速走 PlaybackParams（Android 6 以上）。
 */
public class AudioBookPlayer {

    public interface Listener {
        /** 状态变了（播放/暂停/换章/出错），界面重画一遍 */
        void onState();
        /** 时长出来了 */
        void onReady(int durationMs);
        /** 每秒一次的位置回调 */
        void onTick(int posMs, int durationMs);
        /** 播放出错 */
        void onError(String msg);
        /** 这一章播完了，该自动下一章 */
        void onEnd();
    }

    private MediaPlayer mp;
    private Listener listener;
    private float rate = 1.0f;
    private int durationMs = 0;
    private boolean prepared = false;
    private String currentUrl = "";
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private boolean ticking = false;

    public void setListener(Listener l) { this.listener = l; }

    public boolean isPlaying() {
        try { return mp != null && prepared && mp.isPlaying(); } catch (Throwable t) { return false; }
    }

    public boolean isActive() { return mp != null; }

    public String url() { return currentUrl; }

    public int position() {
        try { return mp == null ? 0 : mp.getCurrentPosition(); } catch (Throwable t) { return 0; }
    }

    public int duration() { return durationMs; }

    public float rate() { return rate; }

    /** 播一个地址；local 表示本地文件（file:// 或绝对路径） */
    public void play(String url, boolean local) {
        stop();
        currentUrl = url == null ? "" : url;
        durationMs = 0;
        prepared = false;
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            if (local) mp.setDataSource(currentUrl);
            else mp.setDataSource(currentUrl);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer p) {
                    prepared = true;
                    durationMs = p.getDuration() <= 0 ? 0 : p.getDuration();
                    applyRate();
                    try { p.start(); } catch (Throwable ignored) {}
                    if (listener != null) { listener.onReady(durationMs); listener.onState(); }
                    startTick();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer p) {
                    stopTick();
                    if (listener != null) listener.onEnd();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer p, int what, int extra) {
                    stopTick();
                    if (listener != null) listener.onError("播放出错 what=" + what + " extra=" + extra);
                    return true;
                }
            });
            mp.prepareAsync();
            if (listener != null) listener.onState();
        } catch (Throwable t) {
            if (listener != null) listener.onError("打不开音频：" + t.getClass().getSimpleName());
        }
    }

    public void toggle() {
        try {
            if (mp == null || !prepared) return;
            if (mp.isPlaying()) mp.pause();
            else mp.start();
            if (listener != null) listener.onState();
        } catch (Throwable ignored) {}
    }

    public void pause() {
        try {
            if (mp != null && prepared && mp.isPlaying()) {
                mp.pause();
                if (listener != null) listener.onState();
            }
        } catch (Throwable ignored) {}
    }

    public void resume() {
        try {
            if (mp != null && prepared && !mp.isPlaying()) {
                mp.start();
                if (listener != null) listener.onState();
            }
        } catch (Throwable ignored) {}
    }

    /** 相对跳，毫秒，正数往前 */
    public void seekBy(int deltaMs) {
        try {
            if (mp == null || !prepared) return;
            int pos = mp.getCurrentPosition() + deltaMs;
            if (pos < 0) pos = 0;
            if (durationMs > 0 && pos > durationMs) pos = durationMs;
            mp.seekTo(pos);
            if (listener != null) listener.onTick(pos, durationMs);
        } catch (Throwable ignored) {}
    }

    /** 跳到指定位置（毫秒），进度条拖动用 */
    public void seekTo(int ms) {
        try {
            if (mp == null || !prepared) return;
            if (ms < 0) ms = 0;
            if (durationMs > 0 && ms > durationMs) ms = durationMs;
            mp.seekTo(ms);
            if (listener != null) listener.onTick(ms, durationMs);
        } catch (Throwable ignored) {}
    }

    public void setRate(float r) {
        if (r < 0.5f) r = 0.5f;
        if (r > 3.0f) r = 3.0f;
        rate = r;
        applyRate();
        if (listener != null) listener.onState();
    }

    private void applyRate() {
        if (mp == null || Build.VERSION.SDK_INT < 23) return;
        try {
            android.media.PlaybackParams pp = new android.media.PlaybackParams();
            pp.setSpeed(rate);
            mp.setPlaybackParams(pp);
        } catch (Throwable ignored) {}
    }

    public void stop() {
        stopTick();
        try {
            if (mp != null) { mp.reset(); mp.release(); }
        } catch (Throwable ignored) {}
        mp = null;
        prepared = false;
        durationMs = 0;
        if (listener != null) listener.onState();
    }

    private void startTick() {
        stopTick();
        ticking = true;
        ticker.postDelayed(new Runnable() {
            @Override public void run() {
                if (!ticking) return;
                if (listener != null) listener.onTick(position(), durationMs);
                if (ticking) ticker.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void stopTick() {
        ticking = false;
        try { ticker.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
    }

    /** 时间格式 mm:ss */
    public static String fmt(int ms) {
        if (ms <= 0) return "0:00";
        int s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
