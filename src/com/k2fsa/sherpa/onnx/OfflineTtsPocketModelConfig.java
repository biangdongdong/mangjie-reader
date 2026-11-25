package com.k2fsa.sherpa.onnx;

/** Pocket 配置占位：字段名对齐官方，值留空。 */
public class OfflineTtsPocketModelConfig {
    public String encoder = "";
    public String decoder = "";
    public String lmFlow = "";
    public String lmMain = "";
    public String textConditioner = "";
    public String vocabJson = "";
    public String tokenScoresJson = "";
    public int voiceEmbeddingCacheCapacity = 0;
}
