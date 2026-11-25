package com.k2fsa.sherpa.onnx;

/**
 * Matcha（conditional flow matching）声学模型配置。
 * 对应模型包：matcha-icefall-zh-baker（acousticModel=model-steps-3.onnx 或 model.onnx，
 * vocoder=vocos-22khz-univ.onnx，tokens=tokens.txt，lexicon=lexicon.txt）。
 */
public class OfflineTtsMatchaModelConfig {
    public String acousticModel = "";
    public String vocoder = "";
    public String tokens = "";
    public String lexicon = "";
    public String dataDir = "";
    public String dictDir = "";
    public float noiseScale = 1.0f;
    public float lengthScale = 1.0f;

    public OfflineTtsMatchaModelConfig() {}

    public OfflineTtsMatchaModelConfig(String acousticModel, String vocoder,
                                       String tokens, String lexicon,
                                       String dataDir, String dictDir,
                                       float noiseScale, float lengthScale) {
        this.acousticModel = acousticModel;
        this.vocoder = vocoder;
        this.tokens = tokens;
        this.lexicon = lexicon;
        this.dataDir = dataDir;
        this.dictDir = dictDir;
        this.noiseScale = noiseScale;
        this.lengthScale = lengthScale;
    }
}
