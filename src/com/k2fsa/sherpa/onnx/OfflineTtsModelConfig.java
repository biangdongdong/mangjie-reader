package com.k2fsa.sherpa.onnx;

/** 模型总配置。字段集与官方 OfflineTtsModelConfig 完全对齐，JNI 按字段名逐个查找。 */
public class OfflineTtsModelConfig {
    public OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
    public OfflineTtsMatchaModelConfig matcha = new OfflineTtsMatchaModelConfig();
    public OfflineTtsKokoroModelConfig kokoro = new OfflineTtsKokoroModelConfig();
    public OfflineTtsZipVoiceModelConfig zipvoice = new OfflineTtsZipVoiceModelConfig();
    public OfflineTtsKittenModelConfig kitten = new OfflineTtsKittenModelConfig();
    public OfflineTtsPocketModelConfig pocket = new OfflineTtsPocketModelConfig();
    public OfflineTtsSupertonicModelConfig supertonic = new OfflineTtsSupertonicModelConfig();
    public int numThreads = 2;
    public boolean debug = false;
    public String provider = "cpu";

    public OfflineTtsModelConfig() {}
}
