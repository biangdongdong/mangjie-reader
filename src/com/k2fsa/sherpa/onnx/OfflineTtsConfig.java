package com.k2fsa.sherpa.onnx;

/** 顶层 TTS 配置：模型 + 文本正则化 + 句间静音。字段名必须与 JNI 一致。 */
public class OfflineTtsConfig {
    public OfflineTtsModelConfig model = new OfflineTtsModelConfig();
    public String ruleFsts = "";
    public String ruleFars = "";
    public int maxNumSentences = 1;
    public float silenceScale = 0.2f;

    public OfflineTtsConfig() {}
}
