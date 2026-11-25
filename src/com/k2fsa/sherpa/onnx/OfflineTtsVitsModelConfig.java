package com.k2fsa.sherpa.onnx;

/** VITS 模型配置。本项目只备一份空值占位，JNI 用它判断走哪条分支。 */
public class OfflineTtsVitsModelConfig {
    public String model = "";
    public String tokens = "";
    public String lexicon = "";
    public String dataDir = "";
    public String dictDir = "";
    public float noiseScale = 0.667f;
    public float noiseScaleW = 0.8f;
    public float lengthScale = 1.0f;

    public OfflineTtsVitsModelConfig() {}
}
