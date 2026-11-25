package com.dianming.phoneapp;

import com.dianming.phoneapp.ISpeakServiceCallback;
import com.dianming.phoneapp.ISpeakCallback;
import com.dianming.phoneapp.IIme;
import com.dianming.phoneapp.ISpeakItemDataCallback;
import com.dianming.phoneapp.IActionFinishedCallback;
import android.view.accessibility.AccessibilityNodeInfo;

interface ISpeakService {
    
    /* Voice Related */
    void SpeakNow(String content);
    void SpeakNowToEnd(String content);
    void SpeakNowToEndWithRunnable(String content, ISpeakServiceCallback callback);
    void clearRemainSpeakItems();
    int getRemainSpeakItemCount();
    int setSpeakMode(boolean value);
    
    /* License Related */
    String getTipString();    
    String getTipStringV1();
    boolean IsEvalVersion();
    String getBasePackageVersion();
    
    /* Config Related */
    int GInt(String key, int defaultValue);
    boolean GBool(String key, boolean defaultValue);
    String GString(String key, String defaultValue);
    void PInt(String key, int value);
    void PBool(String key, boolean value);
    void PString(String key, String value);
    void saveToFile();
    
    /* Register clients so we can notify then when the config is changed */
    void registerCallback(ISpeakServiceCallback callback);
    
    /* MemConfig Related */
    int GMemInt(String key, int defaultValue);
    boolean GMemBool(String key, boolean defaultValue);
    String GMemString(String key, String defaultValue);
    void PMemInt(String key, int value);
    void PMemBool(String key, boolean value);
    void PMemString(String key, String value);
    
    /* Key */
    int setKey(int value);
    int initExt();    
    
    /* Accessibility Data Update Related */
    void notifyAccessibilityDataUpdated();
    
    void SpeakNowWithCallback(String content, ISpeakItemDataCallback callback);  
    
    /* If not finished to speak, not to invoke the callback */
    void SpeakNowWithRunnable(String content, ISpeakServiceCallback callback);
    
    void SpeakEffect(int index, boolean considerSetting);
    void SpeakEffectByIndex(String strId, int index);
    void SpeakEffectWithMoreParameters(int index, float volume, float rate, boolean considerSetting);
    
    void speakNowNoInterruptCurrent(String content);      
    
    String getRootNode();
    void speakSample(int id, String speed);
    int getBasePackageVersionCode();
    void stopCurrentReading();
    
    /*增加设置备份与恢复的命令接口*/
    void onCommandBySettings(int cmdId);
    
    
    int speak(int which, String content, int queueType, ISpeakCallback callback);
    void stopIndependent(int which, int stopFlag);
    
    /* 向主包发送命令的接口 */
    int requestAction(int action, IActionFinishedCallback callback);
    
    /* 让主包在当前窗口上查找一个Node并执行一个操作 */
    boolean findNodeAndPerformAction(String screenName, String className, String text, String desc, int action);
    
    boolean findNodeByParentAndPerformAction(String parentClassName, int count, String className, int index, String text, String desc, int action1, int action2);
    /* 向主包发送json指令， callback暂时没有使用，为将来异步操作做准备 */
    int requestJsonAction(String jsonStr, IActionFinishedCallback callback);
    
    String getDeviceInfo(int which);

    /* 向主包发送json指令， callback暂时没有使用，为将来异步操作做准备 */
    String requestJsonQuery(String jsonStr, IActionFinishedCallback callback);
    
    /* 向主包发送命令执行快捷功能 */
    int handleSTFuntion(String funName, String data, IActionFinishedCallback callback);
    
    AccessibilityNodeInfo[] queryAccessibilityNodeInfos(in String[] cmdNodes);
    
    /* 指定单双语音朗读 */
    int speakIndependent(String prefixCn, String prefixEn, String content, int queueType, ISpeakCallback callback);
    
    /* 使用第三方tts朗读 */
    int speakWithThirdTts(String engine, String content, int queueType, ISpeakCallback callback);
    
    boolean speakNowWithCallback(String prefixCn, String prefixEn, String content, ISpeakItemDataCallback callback); 

    void registerIme(IIme ime);

    void requestTouchExplorationPassthroughRegion(boolean enable);

    void vibrateEffect(int effectType, boolean considerSetting);

    boolean speakNowWithByteDanceCallback(String bdttPrefix, String content, ISpeakItemDataCallback callback);

    /* 指定自然语音语音朗读 */
    int speakIndependentBd(String prefixBd, String content, int queueType, ISpeakCallback callback);

    /* 指定单语音语音朗读 */
    int speakSingleVoice(String content, int queueType, ISpeakCallback callback);

    boolean bdVoiceInited();

    int scenceSpeak(int mainVoiceSceneOrdinal, String content, int queueType, ISpeakCallback callback);

    boolean speakNowWithVivoCallback(String bdttPrefix, String content, ISpeakItemDataCallback callback);

    /* 指定vivo自然语音语音朗读 */
    int speakIndependentVv(String prefixVv, String content, int queueType, ISpeakCallback callback);

}
