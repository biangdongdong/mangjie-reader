package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;
import android.util.Log;

/**
 * 纯 Java 实现的 sherpa-onnx OfflineTts 桥。
 *
 * 为什么自己写而不用官方 Kotlin wrapper：本项目是 javac + d8 脚本构建，
 * 没有 Kotlin 编译器，也无法引 kotlin-stdlib。JNI 采用静态注册，
 * 只要类全名（com.k2fsa.sherpa.onnx.OfflineTts）与 native 方法名、
 * 参数签名一致，so 就能找到我们；配置项在 native 侧用 GetFieldID 按
 * 字段名读取，所以字段名/类型必须与官方完全一致。
 *
 * 官方 so 中实际注册的方法（8 个）：
 *   newFromFile(OfflineTtsConfig)J
 *   newFromAsset(AssetManager, OfflineTtsConfig)J
 *   delete(J)V
 *   generateImpl(J, String, int, float)GeneratedAudio
 *   generateWithCallbackImpl(J, String, int, float, Function1)GeneratedAudio
 *   getSampleRate(J)I
 *   getNumSpeakers(J)I
 */
public class OfflineTts {

    private static final String TAG = "MangjieTts";
    private static boolean libOk = false;
    private static String libErr = "";

    static {
        try {
            // onnxruntime 是 sherpa-onnx-jni 的依赖，必须先加载
            System.loadLibrary("onnxruntime");
            System.loadLibrary("sherpa-onnx-jni");
            libOk = true;
        } catch (Throwable t) {
            libOk = false;
            libErr = t.toString();
            Log.e(TAG, "原生库加载失败: " + libErr);
        }
    }

    public static boolean available() { return libOk; }
    public static String loadError() { return libErr; }

    /** 字段名/类型固定：JNI 按 GetFieldID 读取 */
    public OfflineTtsConfig config;
    private long ptr = 0;

    private OfflineTts() {}

    /** 从文件加载（模型已解压到私有目录时用） */
    public static OfflineTts fromFile(OfflineTtsConfig c) {
        if (!libOk) throw new IllegalStateException("原生库未加载：" + libErr);
        OfflineTts t = new OfflineTts();
        t.config = c;
        t.ptr = t.newFromFile(c);
        if (t.ptr == 0) throw new IllegalStateException("模型加载失败（newFromFile 返回 0），检查路径与文件是否齐全");
        return t;
    }

    /** 从 assets 直接加载（sherpa 自己负责拷贝） */
    public static OfflineTts fromAsset(AssetManager am, OfflineTtsConfig c) {
        if (!libOk) throw new IllegalStateException("原生库未加载：" + libErr);
        OfflineTts t = new OfflineTts();
        t.config = c;
        t.ptr = t.newFromAsset(am, c);
        if (t.ptr == 0) throw new IllegalStateException("模型加载失败（newFromAsset 返回 0）");
        return t;
    }

    private native long newFromFile(OfflineTtsConfig config);
    private native long newFromAsset(AssetManager assetManager, OfflineTtsConfig config);
    private native void delete(long ptr);
    private native GeneratedAudio generateImpl(long ptr, String text, int sid, float speed);
    private native int getSampleRate(long ptr);
    private native int getNumSpeakers(long ptr);

    // 官方还声明了这两个，so 里 getLang 没有对应符号、回调版本本项目不用；
    // 保留声明是为了与官方类结构一致，不调用就不会触发链接。
    private native GeneratedAudio generateWithCallbackImpl(long ptr, String text, int sid,
                                                          float speed, kotlin.jvm.functions.Function1 cb);
    private native String getLang(long ptr);

    /** 合成一整句；返回 null 表示合成失败 */
    public GeneratedAudio generate(String text, int speakerId, float speed) {
        if (ptr == 0) return null;
        try {
            return generateImpl(ptr, text, speakerId, speed);
        } catch (Throwable t) {
            Log.e(TAG, "合成失败: " + t);
            return null;
        }
    }

    public int sampleRate() {
        if (ptr == 0) return 0;
        try { return getSampleRate(ptr); } catch (Throwable t) { return 0; }
    }

    public int numSpeakers() {
        if (ptr == 0) return 0;
        try { return getNumSpeakers(ptr); } catch (Throwable t) { return 0; }
    }

    public synchronized void release() {
        if (ptr == 0) return;
        long p = ptr;
        ptr = 0;
        try { delete(p); } catch (Throwable t) { Log.e(TAG, "释放失败: " + t); }
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }
}
