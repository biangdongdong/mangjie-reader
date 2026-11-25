package com.mangjie.reader.speech;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * 音道预热：开始朗读前先播一小段音量 0 的 PCM。
 *
 * 出处是开源阅读 Legado 的 MediaHelp.playSilentSound——它播一段静音音频，注释写的是
 * "Stupid Android 8 Oreo hack to make media buttons work"。实际效果是两个：
 * 一是把音频输出通路先建起来，二是让部分引擎第一句出声不那么迟。
 *
 * 我们没有 res/raw 里的静音资源，就用 AudioTrack 现场合成 0.2 秒全零 PCM，不落文件。
 * 音量设 0，不抢焦点，播完自动释放。
 *
 * 注意：收益是听感层面的，能不能真的缩短首字延迟要真机验，别当成确定优化。
 */
public final class SilentWarmup {

    private static final Handler H = new Handler(Looper.getMainLooper());
    /** 短时间连着起好几次朗读，只预热一次 */
    private static long lastAt = 0L;

    private SilentWarmup() {}

    public static void warm() {
        long now = System.currentTimeMillis();
        if (now - lastAt < 3000L) return;
        lastAt = now;
        try {
            int rate = 16000;
            int frames = rate / 5;                    // 0.2 秒
            byte[] pcm = new byte[frames * 2];        // 全零，就是静音
            final AudioTrack t = new AudioTrack(
                    AudioManager.STREAM_MUSIC, rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    pcm.length, AudioTrack.MODE_STATIC);
            t.write(pcm, 0, pcm.length);
            t.setVolume(0f);
            t.play();
            H.postDelayed(new Runnable() {
                @Override public void run() {
                    try { t.stop(); } catch (Throwable ignored) {}
                    try { t.release(); } catch (Throwable ignored) {}
                }
            }, 400);
        } catch (Throwable ignored) {}
    }
}
