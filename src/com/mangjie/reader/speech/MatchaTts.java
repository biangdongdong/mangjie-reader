package com.mangjie.reader.speech;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置离线语音库：Matcha zh-baker + Vocos 声码器。
 *
 * 两条硬约束：
 *  1. 解压 126MB 与建 onnx 会话加起来要十几秒，绝不能放在 TextToSpeechService.onLoadLanguage 里
 *     —— 那是 binder 同步调用，调用方常在主线程，卡住就被系统判成引擎不可用。
 *     所以拆成：present() 毫秒级检查、ensureReady() 后台加载、服务 onCreate 里预热。
 *  2. 失败不能固化。之前「inited 一次失败即永久 false」的写法会让朗读永远出不了声；
 *     现在失败落到 FAILED，下一次朗读会重新试一遍。
 */
public final class MatchaTts {

    private static final String TAG = "MangjieTts";

    /** assets 里模型目录；没有这个目录说明当前是不带语音库的版本 */
    private static final String ASSET_DIR = "tts";

    /** 声学模型文件名（matcha-icefall-zh-baker 包里叫 model-steps-3.onnx，不是 model.onnx） */
    public static final String MODEL_FILE = "model-steps-3.onnx";

    /** 声码器文件名 */
    public static final String VOCODER_FILE = "vocos.onnx";

    /** 模型必需文件（缺一个就判为不可用，宁可不出声也不崩） */
    private static final String[] REQUIRED = {
            MODEL_FILE, VOCODER_FILE, "tokens.txt", "lexicon.txt"
    };

    /** 文本正则化：有就带上，数字/日期才读得像人话 */
    private static final String[] RULES = {
            "date.fst", "number.fst", "phone.fst"
    };

    /** 首次合成最多等模型多久（解压 + 建会话），之后都是毫秒级 */
    private static final long FIRST_WAIT_MS = 30000;

    private static final Object LOCK = new Object();       // 只管状态
    private static final Object SYNTH_LOCK = new Object(); // 引擎实例不保证并发安全，合成串行

    private static final int IDLE = 0, LOADING = 1, READY = 2, FAILED = 3;
    private static int state = IDLE;
    private static OfflineTts tts;
    private static String err = "";
    private static Thread loader;
    private static String cacheText = "";
    private static short[] cachePcm;

    private MatchaTts() {}

    /**
     * 毫秒级检查：assets 里模型齐不齐、原生库能不能加载。
     * 这一步会被 binder 同步调用，所以不许解压、不许建会话。
     */
    public static boolean present(Context ctx) {
        try {
            String[] list = ctx.getAssets().list(ASSET_DIR);
            if (list == null || list.length == 0) {
                err = "未内置语音库";
                return false;
            }
            for (String need : REQUIRED) {
                boolean found = false;
                for (String name : list) {
                    if (name.equals(need)) { found = true; break; }
                }
                if (!found) {
                    err = "assets 里缺 " + need;
                    return false;
                }
            }
            if (!OfflineTts.available()) {
                err = "原生库未加载：" + OfflineTts.loadError();
                return false;
            }
            return true;
        } catch (Throwable t) {
            err = String.valueOf(t);
            return false;
        }
    }

    /** 服务启动时预热：后台开始解压与建会话，用户真正开口时通常已经好了 */
    public static void prewarm(final Context ctx) {
        synchronized (LOCK) {
            if (state == READY || state == LOADING) return;
            startLoadLocked(ctx);
        }
    }

    /**
     * 等模型就绪。timeoutMs <= 0 表示只发起加载、不等。
     * 首次会真的等（解压 + 建会话），之后立刻返回。
     */
    public static boolean ensureReady(Context ctx, long timeoutMs) {
        synchronized (LOCK) {
            if (state == READY && tts != null) return true;
            if (state == FAILED) {
                state = IDLE;   // 允许重试：上一次可能只是首启没解压完或临时 OOM
            }
            if (state == IDLE) startLoadLocked(ctx);
            if (timeoutMs <= 0) return false;

            long end = System.currentTimeMillis() + timeoutMs;
            while (state == LOADING) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    LOCK.wait(Math.min(left, 300));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return state == READY && tts != null;
        }
    }

    public static String lastError() {
        synchronized (LOCK) { return err; }
    }

    /** 采样率随模型而定，播放端必须用这个值 */
    public static int lastSampleRate() {
        synchronized (LOCK) {
            if (tts == null) return 22050;
            try { return tts.sampleRate(); } catch (Throwable t) { return 22050; }
        }
    }

    /**
     * 合成一句，返回 16bit 单声道 PCM；失败返回 null。
     * 必须后台线程调用；首次会等模型就绪。
     */
    public static short[] synth(Context ctx, String text, float speed) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;

        if (!ensureReady(ctx, FIRST_WAIT_MS)) {
            Log.w(TAG, "模型没就绪，放弃这句：" + lastError());
            return null;
        }
        if (t.equals(cacheText) && cachePcm != null) return cachePcm;

        OfflineTts engine;
        synchronized (LOCK) { engine = tts; }
        if (engine == null) return null;

        GeneratedAudio audio;
        synchronized (SYNTH_LOCK) {
            audio = engine.generate(t, 0, speed);
        }
        if (audio == null || audio.samples == null || audio.samples.length == 0) {
            Log.w(TAG, "合成返回空：" + shortText(t));
            return null;
        }
        short[] pcm = toPcm16(audio.samples);
        cacheText = t;
        cachePcm = pcm;
        return pcm;
    }

    // ---------- 内部 ----------

    /** 调用方必须持 LOCK */
    private static void startLoadLocked(final Context ctx) {
        state = LOADING;
        err = "";
        final Context app = ctx.getApplicationContext();
        loader = new Thread(new Runnable() {
            @Override public void run() {
                OfflineTts loaded = null;
                String fail = "";
                try {
                    loaded = doLoad(app);
                } catch (Throwable t) {
                    fail = String.valueOf(t);
                    Log.e(TAG, "离线语音库加载失败: " + fail);
                }
                synchronized (LOCK) {
                    if (loaded != null) {
                        tts = loaded;
                        state = READY;
                        err = "";
                        Log.i(TAG, "离线语音库就绪，采样率 " + loaded.sampleRate());
                    } else {
                        tts = null;
                        state = FAILED;
                        err = fail.isEmpty() ? "未知原因" : fail;
                    }
                    LOCK.notifyAll();
                }
            }
        }, "matcha-tts-load");
        loader.setDaemon(true);
        loader.start();
    }

    /** 真正的重活：解压模型 + 建会话。只在后台线程跑 */
    private static OfflineTts doLoad(Context ctx) throws Exception {
        AssetManager am = ctx.getAssets();
        String[] list = am.list(ASSET_DIR);
        if (list == null || list.length == 0) throw new IllegalStateException("未内置语音库");
        if (!OfflineTts.available()) {
            throw new IllegalStateException("原生库未加载：" + OfflineTts.loadError());
        }

        File dir = prepare(ctx, am, list);
        if (dir == null) throw new IllegalStateException("模型解压失败");

        OfflineTtsConfig cfg = new OfflineTtsConfig();
        OfflineTtsMatchaModelConfig m = new OfflineTtsMatchaModelConfig();
        m.acousticModel = new File(dir, MODEL_FILE).getAbsolutePath();
        m.vocoder = new File(dir, VOCODER_FILE).getAbsolutePath();
        m.tokens = new File(dir, "tokens.txt").getAbsolutePath();
        m.lexicon = new File(dir, "lexicon.txt").getAbsolutePath();
        m.dataDir = "";
        m.dictDir = "";
        m.noiseScale = 0.667f;
        m.lengthScale = 1.0f;
        cfg.model.matcha = m;

        List<String> rules = new ArrayList<String>();
        for (String r : RULES) {
            File f = new File(dir, r);
            if (f.exists() && f.length() > 0) rules.add(f.getAbsolutePath());
        }
        cfg.ruleFsts = join(rules, ",");
        cfg.ruleFars = "";
        cfg.maxNumSentences = 1;
        cfg.silenceScale = 0.2f;
        cfg.model.numThreads = pickThreads();
        cfg.model.debug = false;
        cfg.model.provider = "cpu";

        Log.i(TAG, "线程 " + cfg.model.numThreads + "，规则 " + (rules.isEmpty() ? "无" : rules.size() + " 个"));
        return OfflineTts.fromFile(cfg);
    }

    private static short[] toPcm16(float[] src) {
        short[] out = new short[src.length];
        for (int i = 0; i < src.length; i++) writeOne(out, i, src[i]);
        return out;
    }

    private static void writeOne(short[] out, int i, float v) {
        if (v > 1f) v = 1f;
        if (v < -1f) v = -1f;
        out[i] = (short) Math.round(v * 32767f);
    }

    /**
     * 把 assets/tts 拷到私有目录。已存在且非空的文件跳过，所以只在首次安装付一次代价；
     * 拷完再校验必需文件，缺任何一个都返回 null（宁可不出声，也不让 JNI 拿半套模型崩掉）。
     */
    private static File prepare(Context ctx, AssetManager am, String[] list) {
        File dir = new File(ctx.getFilesDir(), ASSET_DIR);
        if (!dir.exists() && !dir.mkdirs()) return null;
        for (String name : list) {
            if (!isModelAsset(name)) continue;
            File out = new File(dir, name);
            if (out.isFile() && out.length() > 0) continue;
            try {
                copyAsset(am, ASSET_DIR + "/" + name, out);
            } catch (Throwable t) {
                Log.e(TAG, "解压 " + name + " 失败: " + t);
                return null;
            }
        }
        for (String name : REQUIRED) {
            File f = new File(dir, name);
            if (!f.isFile() || f.length() <= 0) {
                Log.e(TAG, "模型文件缺失: " + name);
                return null;
            }
        }
        return dir;
    }

    private static boolean isModelAsset(String name) {
        String l = name.toLowerCase();
        return l.endsWith(".onnx") || l.endsWith(".txt") || l.endsWith(".fst")
                || l.endsWith(".bin") || l.endsWith(".json");
    }

    private static void copyAsset(AssetManager am, String assetPath, File out) throws Exception {
        InputStream in = am.open(assetPath);
        FileOutputStream os = new FileOutputStream(out);
        try {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
            }
            os.flush();
            Log.i(TAG, "解压 " + out.getName() + " " + (total / 1024) + "KB");
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { os.close(); } catch (Exception ignored) {}
        }
    }

    private static int pickThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        int n = cores / 2;
        if (n < 1) n = 1;
        if (n > 4) n = 4;
        return n;
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static String shortText(String t) {
        return t.length() > 12 ? t.substring(0, 12) + "…" : t;
    }
}
