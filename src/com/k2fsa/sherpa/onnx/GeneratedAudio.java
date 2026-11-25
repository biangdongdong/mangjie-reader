package com.k2fsa.sherpa.onnx;

/**
 * 合成结果：一段 PCM 浮点采样 + 采样率。
 * 字段名与类型必须与 libsherpa-onnx-jni.so 里 GetFieldID 查找的一致，
 * 否则 JNI 拿不到值，合成结果会全是 0。
 */
public class GeneratedAudio {
    public int sampleRate;
    public float[] samples;

    public GeneratedAudio(float[] samples, int sampleRate) {
        this.samples = samples;
        this.sampleRate = sampleRate;
    }

    public int getSampleRate() { return sampleRate; }
    public float[] getSamples() { return samples; }
}
