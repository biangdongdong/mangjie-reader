package com.k2fsa.sherpa.onnx;

/**
 * ZipVoice 配置占位。
 * JNI 在读取模型配置时会按字段名查找（so 里确认存在 zipvoice 字段），
 * 字段缺失会让 GetFieldID 返回空，因此这里把官方字段集补齐，值全部留空。
 */
public class OfflineTtsZipVoiceModelConfig {
    public String encoder = "";
    public String decoder = "";
    public String vocoder = "";
    public String tokens = "";
    public String lexicon = "";
    public String dataDir = "";
    public float featScale = 1.0f;
    public float tShift = 1.0f;
    public float targetRms = 1.0f;
    public float guidanceScale = 1.0f;
}
