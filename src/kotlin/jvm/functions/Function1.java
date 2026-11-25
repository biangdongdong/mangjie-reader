package kotlin.jvm.functions;

/**
 * 只为了让 libsherpa-onnx-jni.so 的 generateWithCallbackImpl 签名能解析：
 * 官方 Kotlin wrapper 用 Function1 做「每生成一块 PCM 就回调一次」。
 * 本项目走整句合成，不调用回调版本，但类必须存在，否则签名解析失败。
 */
public interface Function1<P1, R> {
    R invoke(P1 p1);
}
