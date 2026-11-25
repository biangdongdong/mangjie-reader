package com.mangjie.reader.speech;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;

/**
 * 内置离线语音引擎（系统 TTS 引擎形态）。
 *
 * 只做中文（zho/CHN/cmn）。系统里的朗读接口（TextToSpeech.speak）绑到本服务后，
 * 一行调用都不用改，打断（stop）走 onStop，播报完成走 callback.done()。
 *
 * 声明位置见 AndroidManifest-tts.xml：exported + BIND_TEXT_SERVICE 权限 + TTS_SERVICE intent-filter。
 * 不带语音库的普通版不声明该服务，所以它不会出现在系统「语音引擎」列表里。
 */
public class MangjieTtsService extends TextToSpeechService {

    private static final String TAG = "MangjieTtsEngine";

    private static final String LANG = "zho";
    private static final String COUNTRY = "CHN";
    private static final String VARIANT = "cmn";

    /** 合成的 PCM 以 20ms 为一块回调，块间检查打断标志，保证「停止朗读」响应快 */
    private static final int BLOCK_MS = 20;

    private volatile boolean stopRequested = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "引擎启动，后台预热模型");
        // 只起线程，不等结果：解压 126MB + 建会话要十几秒，绝不能在 binder 线程里做
        MatchaTts.prewarm(this);
    }

    // ---------- 语言能力 ----------

    @Override
    protected String[] onGetLanguage() {
        return new String[]{LANG, COUNTRY, VARIANT};
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return matchLang(lang, country) ? TextToSpeech.LANG_COUNTRY_AVAILABLE
                : TextToSpeech.LANG_NOT_SUPPORTED;
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        if (!matchLang(lang, country)) return TextToSpeech.LANG_NOT_SUPPORTED;
        // 这里只做毫秒级检查（assets 齐不齐、so 能不能加载），解压与建会话在后台线程做。
        // 只要资源在，就先报「可用」，才能真正开口；否则 setLanguage 会卡住调用方并被系统判失败。
        if (!MatchaTts.present(this)) {
            Log.w(TAG, "语音库缺失: " + MatchaTts.lastError());
            return TextToSpeech.LANG_MISSING_DATA;
        }
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    private boolean matchLang(String lang, String country) {
        if (lang == null) return false;
        String l = lang.toLowerCase();
        boolean zh = "zho".equals(l) || "cmn".equals(l) || "chi".equals(l) || "zh".equals(l)
                || "zh_cn".equals(l) || "zh-cn".equals(l);
        if (!zh) return false;
        if (country == null || country.isEmpty()) return true;
        String c = country.toUpperCase();
        return "CHN".equals(c) || "CN".equals(c) || "HKG".equals(c) || "TWN".equals(c);
    }

    // ---------- 合成 ----------

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopRequested = false;

        int maxBuf = callback.getMaxBufferSize();

        String text = request == null ? null : request.getText();
        if (text == null || text.trim().isEmpty()) {
            callback.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1);
            callback.done();
            return;
        }

        float rate = 1.0f;
        try { rate = request.getSpeechRate(); } catch (Throwable ignored) {}
        if (rate < 0.3f) rate = 0.3f;
        if (rate > 2.5f) rate = 2.5f;

        long t0 = android.os.SystemClock.elapsedRealtime();
        short[] pcm = MatchaTts.synth(this, text, rate);
        long cost = android.os.SystemClock.elapsedRealtime() - t0;
        if (pcm == null || pcm.length == 0) {
            Log.w(TAG, "合成失败（" + cost + "ms）：" + MatchaTts.lastError());
            callback.error();
            return;
        }

        int sr = MatchaTts.lastSampleRate();
        if (sr <= 0) sr = 22050;

        // 16bit 单声道，每块 BLOCK_MS 毫秒，且不超过引擎给的单次上限
        int samplesPerBlock = Math.max(128, sr * BLOCK_MS / 1000);
        int bytesPerBlock = samplesPerBlock * 2;
        if (maxBuf > 0 && bytesPerBlock > maxBuf) {
            bytesPerBlock = maxBuf - (maxBuf % 2);
            samplesPerBlock = Math.max(64, bytesPerBlock / 2);
        }

        int ret = callback.start(sr, AudioFormat.ENCODING_PCM_16BIT, 1);
        if (ret != TextToSpeech.SUCCESS) {
            Log.w(TAG, "callback.start 被拒: " + ret);
            return;
        }

        int pos = 0;
        Log.i(TAG, "开始回调 " + pcm.length + " 采样 @" + sr + "Hz，合成耗时 " + cost + "ms");
        while (pos < pcm.length) {
            if (stopRequested) {
                Log.i(TAG, "合成被停止@ " + pos + "/" + pcm.length);
                // 停止时不再交数据，交给系统去 stopPlayback
                return;
            }
            int n = Math.min(samplesPerBlock, pcm.length - pos);
            int bytes = n * 2;
            byte[] buf = new byte[bytes];
            for (int i = 0; i < n; i++) {
                short s = pcm[pos + i];
                buf[i * 2] = (byte) (s & 0xff);
                buf[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
            }
            int r = callback.audioAvailable(buf, 0, bytes);
            if (r != TextToSpeech.SUCCESS) {
                Log.w(TAG, "audioAvailable 中断: " + r);
                return;
            }
            pos += n;
        }
        callback.done();
    }

    @Override
    protected void onStop() {
        stopRequested = true;
        Log.i(TAG, "收到停止");
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        super.onDestroy();
        Log.i(TAG, "引擎销毁");
    }
}
