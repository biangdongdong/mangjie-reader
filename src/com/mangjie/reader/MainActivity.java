/*
 * 本文件的部分实现参照「开源阅读 Legado」（https://github.com/LegadoTeam/legado）。
 * Legado：Copyright (C) 2019-2023 gedoor，GNU General Public License v3.0。
 * 本工程对其的使用方式与修改说明见仓库根目录 NOTICE，完整条款见 LICENSE。
 */
package com.mangjie.reader;

import static com.mangjie.reader.reader.TextUtil.*;

import com.mangjie.reader.model.Item;
import com.mangjie.reader.model.Screen;
import com.mangjie.reader.reader.DictScreen;
import com.mangjie.reader.reader.ExportScreen;
import com.mangjie.reader.reader.StatsScreen;
import com.mangjie.reader.shelves.ShelfMenu;
import com.mangjie.reader.shell.ScreenStack;
import com.mangjie.reader.input.GestureJudge;
import com.mangjie.reader.input.InputCheck;
import com.mangjie.reader.input.GridNav;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Build;
import android.os.Bundle;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.SystemClock;
import android.os.Environment;
import android.net.Uri;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Comparator;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.database.ContentObserver;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.speech.tts.TextToSpeech.EngineInfo;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.SeekBar;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import org.json.JSONArray;
import android.media.MediaPlayer;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.pm.PackageManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 盲界阅读 · Android 原生 demo
 * 主菜单 + 设置整层（13 项）；配色 8 套、字号三档可即时切换并记住。
 */
public class MainActivity extends Activity {

    /** 规则引擎（书源选择器/JSON/JS 片段），独立模块，自带状态 */
    private final com.mangjie.reader.net.RuleEngine rules = new com.mangjie.reader.net.RuleEngine(            new com.mangjie.reader.net.RuleEngine.Host() {
                @Override public void log(String tag, String msg) { devLogLine(tag, msg); }
                @Override public String evalJs(String expr, String data, String base) {
                    return MainActivity.this.evalJs(expr, data, base);
                }
            });

    /**
     * 数据目录：1.1.45 起整体搬进应用私有目录，不再暴露给文件管理器。
     *
     * 这里不能写成 getFilesDir().getAbsolutePath()：本常量是 static final，类加载时就要有值，
     * 那时还没有 Activity 实例。所以由 App.onCreate 提前把 files 路径存好，这里直接取。
     * 下面 60 多处引用全部由这一行决定走向，改别的地方都是白改。
     */
    private static final String DATA_DIR = com.mangjie.reader.App.filesPath();

    /** 旧版数据根，只在迁移时用一次 */
    public static final String LEGACY_DATA_DIR = "/sdcard/盲界阅读";

    private static final int ROW_DP = 58;
    private static final int ROW_PLAY_DP = ROW_DP + 18;   // 播放页那颗大按钮比普通行高一点
    private static final int TITLE_SP = 22;               // 顶栏标题
    private static final int STATUS_SP = 18;              // 顶栏电量、时间
    private static final int BTN_SP = 17;                 // 小按钮
    private static final int FOOT_SP = 14;                // 底部提示
    private static final int HEADER_DP = 46;
    private static final int HL_DEFAULT = 0xFFF0503F;   // 选中红条默认色（可在光标条页改）

    /** 8 套配色：名称 / 底色 / 字色 */
    private static final String[] PAL_NAMES = {
            "黑底白字", "黑底黄字", "黑底绿字", "黑底青字",
            "白底黑字", "黄底黑字", "蓝底白字", "灰底黑字"
    };
    private static final int[] PAL_BG = {
            0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000,
            0xFFFFFFFF, 0xFFFFF2B0, 0xFF0B2B4A, 0xFFCFCFCF
    };
    private static final int[] PAL_FG = {
            0xFFFFFFFF, 0xFFFFD24A, 0xFF3FE07A, 0xFF6FE0E8,
            0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFF000000
    };
    private static final int[] CUS_COLORS = {
            0xFF000000, 0xFF101010, 0xFF1B1B1B, 0xFF0B2B4A,
            0xFF12321F, 0xFF2B1B2B, 0xFFFFFFFF, 0xFFF4F1EA,
            0xFFFFF2B0, 0xFFCFCFCF, 0xFFE8EEF4, 0xFFEFE7D8
    };
    private static final String[] CUS_NAMES = {
            "纯黑", "近黑", "深灰", "深海蓝", "深墨绿", "深紫", "纯白", "米白",
            "淡黄", "浅灰", "浅蓝灰", "浅米"
    };
    private static final int[] CUS_FG = {
            0xFFFFFFFF, 0xFFFFE082, 0xFFFFD24A, 0xFF3FE07A, 0xFF6FE0E8, 0xFFFFB74D,
            0xFF000000, 0xFF212121, 0xFF37474F, 0xFF8D6E63, 0xFF0B2B4A, 0xFF7A1F1F
    };
    private static final String[] CUS_FG_NAMES = {
            "白", "暖黄", "金黄", "亮绿", "青", "橙", "黑", "深灰",
            "蓝灰", "棕", "深蓝", "暗红"
    };
    private static final int[] FONT_SP = {22, 26, 30};
    private static final String[] FONT_NAMES = {"小", "中", "大"};

    private final List<Screen> stack = new ArrayList<>();
    private LinearLayout column;
    private TextView titleView;
    private LinearLayout titleBar;                    // 标题栏容器，跟随主题时要改它的底色
    private TextView statusView;
    private TextView footerView;
    private Drawable hlBgCache;
    private Drawable hlGridBgCache;
    private String lastLoggedLunar = "";
    private final com.mangjie.reader.speech.VoiceProfile menuVoice = new com.mangjie.reader.speech.VoiceProfile();
    private final com.mangjie.reader.speech.VoiceProfile readerVoice = new com.mangjie.reader.speech.VoiceProfile();  // 计算器状态与求值

    /** 点明语音服务：日志入口 +「一句念完」回调。
     *  点明那边念完一句由它自己的服务回调，不依赖系统 TTS 的 onDone（荣耀上那个一次都不回），
     *  所以走这条通道时用它的回调直接推进下一句，不再走批与看门狗那一套。 */
    private final com.mangjie.reader.speech.DianmingVoice.Host dmHost = new com.mangjie.reader.speech.DianmingVoice.Host() {
        @Override public void log(String tag, String msg) { devLogLine(tag, msg); }
        @Override public void onSentenceDone(int idx) {
            if (!dmVoiceOn()) { devLogLine("点明", "不推进：读屏已不是点明"); return; }
            if (!top().reader) { devLogLine("点明", "不推进：当前页不是正文页（" + top().title + "）"); return; }
            devLogLine("点明", "回调推进：第" + (idx + 1) + "句念完，cur=" + (top().cur + 1) + "，准备下一句");
            advanceSentence();
        }
    };

    /** 当前读屏是不是点明：直接看无障碍服务 id 里有没有 dianming。
     *  点明读屏注册的是 com.dianming.phoneapp 的服务，TalkBack、小米、华为、荣耀自带的都没有这个前缀。
     *  不再给用户开关：是点明就自动走点明通道，不是就老实用系统 TTS。 */
    private boolean dmReaderDetected() {
        try {
            String ids = com.mangjie.reader.a11y.ReaderState.serviceIds(am);
            return ids != null && ids.toLowerCase().contains("dianming");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 点明通道是否该启用：自动判定，检不出点明读屏的机型一律回落本机语音 */
    private boolean dmVoiceOn() { return dmReaderDetected(); }

    /** 读屏开着时长按的计时：手指按住不动 700ms 就呼出快捷菜单 */
    private Runnable a11yLongPress;
    private float a11yDownX, a11yDownY;

    /** 读屏接管触摸时仍然能用的长按。只认一次手指、只认没怎么移动的那一下：
     *  动起来就是翻页/探索，立刻撤掉，不会和读屏的滑动手势打架。 */
    private void a11yLongPressWatch(final MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                a11yDownX = ev.getX();
                a11yDownY = ev.getY();
                cancelA11yLongPress();
                a11yLongPress = new Runnable() {
                    @Override public void run() {
                        a11yLongPress = null;
                        if (dialogOn || menuOn || inputOn) return;
                        if (stack.isEmpty() || !top().reader) return;
                        devLogLine("阅读键", "读屏开着时长按 → 打开阅读菜单");
                        openReaderMenu();
                    }
                };
                ui.postDelayed(a11yLongPress, 700);
                break;
            case MotionEvent.ACTION_MOVE:
                if (a11yLongPress != null
                        && (Math.abs(ev.getX() - a11yDownX) > dp(20) || Math.abs(ev.getY() - a11yDownY) > dp(20))) {
                    cancelA11yLongPress();
                }
                break;
            default:
                cancelA11yLongPress();
                break;
        }
    }

    private void cancelA11yLongPress() {
        if (a11yLongPress != null) {
            ui.removeCallbacks(a11yLongPress);
            a11yLongPress = null;
        }
    }
    private TextView clockView;
    private BatteryIcon batteryIcon;
    private WifiIcon wifiIcon;
    private boolean wifiOn = false;
    private BroadcastReceiver batteryReceiver;
    /** 拔耳机（音频输出断开）监听，对齐 Legado 的 ACTION_AUDIO_BECOMING_NOISY → 暂停朗读 */
    private BroadcastReceiver noisyReceiver;
    private BroadcastReceiver usbReceiver;
    private boolean usbConnected = false;        // 数据线插着
    private boolean usbConfigured = false;       // 对端已完成枚举（多半是电脑，纯充电器一般不会 configured）
    private int batteryPct = -1;
    private View separator;
    private ScrollView scroll;
    /** rowsBox 里排在条目前面的固定 child 数（播放页头部占 1 格），行号换算要用 */
    private int rowLead = 0;
    private LinearLayout rowsBox;
    private LinearLayout tabsBox;   // 书架顶部的分组按钮条
    private int twoStartX = -1;     // 双指横滑起点
    private boolean twoSwiped = false;
    private boolean shelfTwoMode = false;   // 书架双指手势进行中：后续事件都吞掉，不落到单指逻辑
    private int bsrcDetailIdx = -1;          // 书源详情页当前看的是第几个源，菜单里要用

    private com.mangjie.reader.gesture.GestureEngine gestures;
    private boolean gestureOn = true;
    private com.mangjie.reader.a11y.TouchExplorer explorer;
    private View downControl;
    private View lastControlTap;            // 上次摸到的控件（第二下才执行，对齐 TalkBack）
    private long lastControlTapTime;
    private int twoStartY = 0;
    private float twoLastY = 0;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean screenReaderOn = false;
    private AccessibilityManager am;

    private Runnable longPressRunnable;
    private boolean longFired = false;
    private int downRow = -1;
    private SliderRow draggingSlider = null;
    private String draggingSliderKey = null;
    private float downY = 0;
    private boolean movedFar = false;
    private boolean twoFingerScrolled = false;
    private float lastMoveY = 0f;
    private int maxPointers = 1;
    private int dirLock = 0;                 // 1=先右，-1=先左，100=竖向
    private String reversedGesture = null;
    private boolean fastSwipe = false;
    private long lastMoveTime = 0;
    private long swipeCooldownUntil = 0;
    private boolean ignoringGesture = false;
    private boolean fastFlick = false;
    private float downX = 0f;
    private long lastTapTime = 0L;
    private VelocityTracker vTracker = null;
    private int lastSpokenRow = -1;

    private ContentObserver a11yObserver;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hhmm = new SimpleDateFormat("H:mm", Locale.CHINA);

    private boolean menuOn = false;          // 功能菜单（≡）
    private int srcGrpIdx = -1;              // 正在改分组的书源下标
    private Screen menuScreen;               // 浮层菜单的数据：menuOn 时行号与激活都走它
    private LinearLayout shadowBox;          // 浮层上面的原页残影（只给人看，读屏跳过）

    /** 菜单开着时取菜单项，否则取当前页 */
    private Screen curScreen() { return (menuOn && menuScreen != null) ? menuScreen : top(); }
    private boolean netReading = false;      // 当前阅读页来自网上阅读（正文按句切，但目录/换源走书源那套）
    private boolean netChainJump = false;    // 连播自动换章，别播「上次读到」
    private int netChapterSeq = 0;           // 每次开章自增，用来作废过期的延时回调
    private int menuSel = 0;
    /** 定时停止的到期时刻（0 = 没设定），只为菜单显示剩余时间与取消 */
    private long sleepUntil = 0;
    private String clipPath = null;
    private boolean clipCut = false;

    private boolean dialogOn = false;
    private int dialogSel = 0;
    private String dialogMsg = "";
    private String[] dialogBtns = new String[0];

    // ---------------- 设置项状态（存 SharedPreferences） ----------------
    private SharedPreferences prefs;
    private int paletteIdx = 0;
    private int fontIdx = 1;
    private boolean speechOn = true;
    private Voice voiceObj = null;          // 界面提示用的语音对象
    private Voice rdVoiceObj = null;
    private TextToSpeech rdTts = null;      // 正文朗读专用实例
    private boolean rdReady = false;
    private boolean resumeReadingAfterSpeak = false;
    private Runnable resumeGuard = null;
    // 1.0.62 只加日志用：正文最近一次进展（开口/读完）的时刻与句子号，用来量「后台卡在哪儿」
    private long lastProgAt = 0;
    private String lastProgId = "";
    private long bgAt = 0;              // 退到后台的时刻
    private int bgProg = 0;             // 后台期间正文出声次数
    private String bgLastId = "";       // 退后台前最后一句
    // 1.0.63：最近一次「正文末块开口」的 id 与时刻。onDone 往往比 onStart 晚到，
    // onBatchDone 给下一句挂表时那句可能已经在念了，靠它把「已开口」补告诉看门狗
    private String lastStartTailId = "";
    private long lastStartTailAt = 0;
    private boolean bgRun = false;      // 应用是否在后台（onPause/onResume 维护）
    private int bgStallCount = 0;       // 后台期间判死次数，回前台归零
    private float readTouchStartY = 0f;
    private boolean readTouchMoved = false;
    // 长按扫字：按下 0.5 秒进模式，拖动逐字念，抬手标记起点/终点
    private final com.mangjie.reader.reader.Selection readSel = new com.mangjie.reader.reader.Selection();
    private com.mangjie.reader.reader.TextCursor scanCursor = null;
    private Runnable scanTask = null;
    private boolean scanActive = false;
    private int scanAnchorX = 0;
    private int scanAnchorIdx = 0;
    private int scanCurIdx = 0;
    private int scanSpokenIdx = -1;
    private String selPageTag = null;      // 选区属于哪一页；换页就清掉，防止索引错位
    private int readTouchMaxPointers = 1;
    private float readTouchStartX = 0;
    private int tapAnchorRow = 0;
    private boolean devLog = false;
    private final java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private List<Voice> zhVoices = new ArrayList<>();
    private boolean soundFx = true;              // 经过每一行的音效反馈（对齐读屏 earcon）
    private boolean hapticOn = true;             // 经过每一行的震动反馈
    private int sfxVol = 50;                     // 音效反馈音量 0-100
    private int hapLevel = 1;                    // 震动强度 0弱 1中 2强
    private boolean useLinear = false;           // 转子马达机器上：按线性马达的方式驱动（振幅可控的短脉冲）
    private boolean usbAutoMtp = true;           // 插上电脑自动切「传输文件」
    private Boolean hasLinearCache = null;       // 本机是否有线性马达（有振幅控制能力的多半是 LRA）
    private boolean autoUpload = false;      // 自动上传日志（由 logMode 派生，供宿主接口读）
    private static final int REQ_PICK_SOURCE = 9101;   // 开发者：选书源文件上传
    /** 1.1.45：配置搬进私有目录后，文件管理器够不着了，字体/词库/规则各补一个导入入口 */
    private static final int REQ_PICK_FONT = 9102;
    private static final int REQ_PICK_DICT = 9103;
    private static final int REQ_PICK_RULE = 9104;
    /** 选文件回来要落到哪个目录，导入成功后往哪儿退 */
    private int pickKind = 0;
    private boolean clearSrcArmed = false;           // 清空服务器书源：点第二次才真删
    private boolean autoUpdate = true;       // 开发者：启动后自动检查更新并下载
    private boolean devMode = false;         // 开发者选项是否显示（关于页连点版本号 10 次切换）
    private String agPendingTitle = "";      // 新建日程时暂存的标题
    private int logMode = 1;                 // 日志上传档位：0 关闭 / 1 正式（仅异常·WiFi）/ 2 开发（每5分钟）
    private Handler autoUploadHandler = null;   // 语音加倍：在当前语速上再 ×2
    // 语言、省电、电脑连接三项已撤（与阅读无关，且省电/语言原本不生效）

    /** 以主题红条 HL 为基准调明暗：f>0 提亮，f<0 压暗 */
    private int shadeHL(float f) {
        return com.mangjie.reader.view.Highlight.shade(hlColor(), f);
    }

    // ---------------------------------------------------------------- 主题 / 外观

    private static final String[] HL_PRESET_NAMES = {"暖红", "橙", "金黄", "绿", "青", "蓝", "紫", "白"};
    private static final int[] HL_PRESET = {0xFFF0503F, 0xFFFF7043, 0xFFFFB300, 0xFF43A047,
            0xFF26C6DA, 0xFF3D7BFF, 0xFF9C27B0, 0xFFFFFFFF};
    private Runnable colorSayTask = null;         // 颜色名延迟播报，手指停下才念

    /** 手指停下 0.3 秒再补一句颜色名，避免每滑一格念一长串 */
    private void sayLater(final String text) {
        if (colorSayTask != null) ui.removeCallbacks(colorSayTask);
        colorSayTask = new Runnable() {
            @Override public void run() { colorSayTask = null; speak(text); }
        };
        ui.postDelayed(colorSayTask, 300);
    }

    /** 光标条页：颜色（预设 + RGB）+ 形状 + 效果 */
    private String hlPresetLabel(int c) {
        for (int i = 0; i < HL_PRESET.length; i++)
            if ((c & 0xFFFFFF) == (HL_PRESET[i] & 0xFFFFFF))
                return HL_PRESET_NAMES[i] + "　" + hex(HL_PRESET[i]);
        return "自定义　" + hex(c);
    }

    /** 光标条颜色页：预设一行循环 + RGB 三通道 + 色名 + 对比度 */
    /** 光标条改完一项：按当前停在哪一页重建，颜色页与样式页各自刷新 */
    private void refreshHl(int cur) {
        Screen t = top();
        String k = (t == null || t.key == null) ? "" : t.key;
        if ("hlcolor".equals(k)) {
            Screen n = hlColorScreen(cur);
            stack.set(stack.size() - 1, n);
        } else {
            Screen n = hlStyleScreen(cur);
            stack.set(stack.size() - 1, n);
        }
    }

    private Screen hlColorScreen(int cur) {
        List<Item> items = new ArrayList<>();
        int c = hlColor();
        items.add(new Item("预设色：" + hlPresetLabel(c) + "（点按切下一档）", "hl:pick"));
        items.add(new Item("红通道：" + android.graphics.Color.red(c), "hl:r"));
        items.add(new Item("绿通道：" + android.graphics.Color.green(c), "hl:g"));
        items.add(new Item("蓝通道：" + android.graphics.Color.blue(c), "hl:b"));
        items.add(new Item("当前颜色：" + com.mangjie.reader.view.ColorName.full(c) + "　" + hex(c), null));
        double ct = com.mangjie.reader.view.ThemeColors.contrast(palBg(), c);
        items.add(new Item("与底色的对比度：" + String.format(java.util.Locale.US, "%.1f", ct)
                + " 倍" + (ct >= 3.0 ? "，够分辨" : "，偏淡"), null));
        Screen s = new Screen("光标条颜色", items).k("hlcolor");
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    /** 光标条样式页：形状 + 不透明度 + 内高光 + 投影 + 渐变 */
    private Screen hlStyleScreen(int cur) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("光标条形状：" + com.mangjie.reader.view.ThemeColors.shapeName(prefs) + "（点按切下一档）", "hl:shape"));
        items.add(new Item("不透明度：" + com.mangjie.reader.view.ThemeColors.hlAlpha(prefs) + "%", "hl:alpha"));
        items.add(new Item("内高光：" + (com.mangjie.reader.view.ThemeColors.hlGloss(prefs) ? "开" : "关"), "hl:gloss"));
        items.add(new Item("投影：" + (com.mangjie.reader.view.ThemeColors.hlShadow(prefs) ? "开" : "关"), "hl:shadow"));
        items.add(new Item("渐变方向：" + com.mangjie.reader.view.ThemeColors.gradName(prefs), "hl:grad"));
        Screen s = new Screen("光标条样式", items).k("hlstyle");
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    /** 阅读配色页：8 套主题 + 自定义入口 + 恢复 + 夜间另一套 */
    private Screen readColorScreen(int cur) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("当前主题：" + palName() + (prefs.getBoolean("cusOn", false) ? "（已叠加自定义）" : ""), null));
        for (int i = 0; i < PAL_NAMES.length; i++) {
            items.add(new Item("主题·" + PAL_NAMES[i] + (i == paletteIdx ? "　✔" : ""), "pick:color:" + i));
        }
        items.add(new Item("自定义底色与文字色（当前 "
                + com.mangjie.reader.view.ColorName.full(palBg()) + " / "
                + com.mangjie.reader.view.ColorName.full(palFg()) + "）", "go:custom"));
        int np = prefs.getInt("nightPal", 0);
        items.add(new Item("夜间另用一套：" + PAL_NAMES[np < PAL_NAMES.length ? np : 0], "go:nightpal"));
        items.add(new Item(prefs.getBoolean("cusOn", false)
                ? "恢复所选主题（退掉自定义）" : "当前就是主题原色，未做自定义", "tc:reset"));
        items.add(new Item("试听当前配色", "tc:try"));
        Screen s = new Screen("阅读配色", items).k("readcolor");
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    /** 自定义配色页：底色与文字色各挑 RGB，另有 12 色快捷块 */
    private Screen themeColorScreen(int cur) {
        List<Item> items = new ArrayList<>();
        int bg = palBg(), fg = palFg();
        items.add(new Item("底色　红通道：" + android.graphics.Color.red(bg), "bg:r"));
        items.add(new Item("底色　绿通道：" + android.graphics.Color.green(bg), "bg:g"));
        items.add(new Item("底色　蓝通道：" + android.graphics.Color.blue(bg), "bg:b"));
        items.add(new Item("文字　红通道：" + android.graphics.Color.red(fg), "fg:r"));
        items.add(new Item("文字　绿通道：" + android.graphics.Color.green(fg), "fg:g"));
        items.add(new Item("文字　蓝通道：" + android.graphics.Color.blue(fg), "fg:b"));
        items.add(new Item("快捷底色（12 色）", "pick:cust:bg"));
        items.add(new Item("快捷文字色（12 色）", "pick:cust:fg"));
        items.add(new Item("当前底色 " + com.mangjie.reader.view.ColorName.full(bg), null));
        items.add(new Item("当前文字 " + com.mangjie.reader.view.ColorName.full(fg), null));
        items.add(new Item(com.mangjie.reader.view.ThemeColors.contrastText(bg, fg), null));
        items.add(new Item("试听当前配色", "tc:try"));
        Screen s = new Screen("自定义配色", items).k("themecolor");
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    /** 左右滑调一行：颜色通道 / 不透明度 / 形状 / 渐变方向 / 开关 */
    private boolean adjustColorRow(String k, int delta) {
        Screen s = top();
        int sign = delta > 0 ? 1 : -1;
        String grp = k.substring(0, k.indexOf(':'));
        String ch = k.substring(k.indexOf(':') + 1);

        if ("shape".equals(ch)) {
            int len = com.mangjie.reader.view.ThemeColors.SHAPE_NAMES.length;
            int v = ((com.mangjie.reader.view.ThemeColors.hlShape(prefs) + sign) % len + len) % len;
            prefs.edit().putInt("hlShape", v).apply();
            invalidateThemeCache();
            s.items.set(s.cur, new Item("光标条形状：" + com.mangjie.reader.view.ThemeColors.SHAPE_NAMES[v], k));
            render(false);
            speak("形状 " + com.mangjie.reader.view.ThemeColors.SHAPE_NAMES[v]);
            return true;
        }
        if ("alpha".equals(ch)) {
            int was = com.mangjie.reader.view.ThemeColors.hlAlpha(prefs);
            int v = was + sign * 5;
            if (v > 100) v = 0; else if (v < 0) v = 100;
            prefs.edit().putInt("hlAlpha", v).apply();
            invalidateThemeCache();
            s.items.set(s.cur, new Item("不透明度：" + v + "%", k));
            render(false);
            speak("不透明度 " + v + "%" + (was == v ? "，已到头" : ""));
            return true;
        }
        if ("grad".equals(ch)) {
            int v = ((com.mangjie.reader.view.ThemeColors.hlGrad(prefs) + sign) % 3 + 3) % 3;
            prefs.edit().putInt("hlGrad", v).apply();
            invalidateThemeCache();
            s.items.set(s.cur, new Item("渐变方向：" + com.mangjie.reader.view.ThemeColors.GRAD_NAMES[v], k));
            render(false);
            speak("渐变 " + com.mangjie.reader.view.ThemeColors.GRAD_NAMES[v]);
            return true;
        }
        if ("gloss".equals(ch) || "shadow".equals(ch)) {
            boolean was = "gloss".equals(ch) ? com.mangjie.reader.view.ThemeColors.hlGloss(prefs)
                                            : com.mangjie.reader.view.ThemeColors.hlShadow(prefs);
            prefs.edit().putBoolean("hl" + ("gloss".equals(ch) ? "Gloss" : "Shadow"), !was).apply();
            invalidateThemeCache();
            String label = ("gloss".equals(ch) ? "内高光" : "投影") + "：" + (!was ? "开" : "关");
            s.items.set(s.cur, new Item(label, k));
            render(false);
            speak(label);
            return true;
        }

        // 颜色通道：hl 光标条 / bg 背景 / fg 文字
        int curColor;
        if ("hl".equals(grp)) curColor = hlColor();
        else if ("bg".equals(grp)) curColor = palBg();
        else curColor = palFg();
        int r = android.graphics.Color.red(curColor);
        int g = android.graphics.Color.green(curColor);
        int b = android.graphics.Color.blue(curColor);
        int nn = ("r".equals(ch) ? r : "g".equals(ch) ? g : b) + sign * 10;   // 到头环回：255 再往前回 0
        if (nn > 255) nn = 0; else if (nn < 0) nn = 255;
        if ("r".equals(ch)) r = nn;
        else if ("g".equals(ch)) g = nn;
        else b = nn;
        int nv = 0xFF000000 | (r << 16) | (g << 8) | b;

        if ("hl".equals(grp)) {
            com.mangjie.reader.view.ThemeColors.setHlColor(prefs, nv);
            invalidateThemeCache();
            render(false);
            speak(com.mangjie.reader.view.ColorName.channelName(ch) + " " + nn);
            sayLater("大概是" + com.mangjie.reader.view.ColorName.nameOf(nv));
        } else if ("bg".equals(grp)) {
            beginCustomOverlay();
            prefs.edit().putInt("cusBgOv", nv).apply();
            savePrefs();
            stack.set(stack.size() - 1, themeColorScreen(Math.min(s.cur, themeColorScreen(0).items.size() - 1)));
            render(true);
            speak("背景" + com.mangjie.reader.view.ColorName.channelName(ch) + " " + nn);
            sayLater("背景大概是" + com.mangjie.reader.view.ColorName.nameOf(nv) + "，"
                    + com.mangjie.reader.view.ThemeColors.contrastText(nv, palFg()));
        } else {
            beginCustomOverlay();
            prefs.edit().putInt("cusFgOv", nv).apply();
            savePrefs();
            stack.set(stack.size() - 1, themeColorScreen(Math.min(s.cur, themeColorScreen(0).items.size() - 1)));
            render(true);
            speak("文字" + com.mangjie.reader.view.ColorName.channelName(ch) + " " + nn);
            sayLater("文字大概是" + com.mangjie.reader.view.ColorName.nameOf(nv) + "，"
                    + com.mangjie.reader.view.ThemeColors.contrastText(palBg(), nv));
        }
        return true;
    }

    /** 光标条主色：优先用主题里配的，没配就是原来的暖红 */
    private int hlColor() {
        if (prefs == null) return HL_DEFAULT;
        return com.mangjie.reader.view.ThemeColors.hlColor(prefs);
    }

    /** 光标条相关参数一改，缓存全丢，下一次绘制按新参数来 */
    private void invalidateThemeCache() {
        hlBgCache = null;
        hlGridBgCache = null;
    }

    /** 光标条绘制参数：形状 / 不透明度 / 高光 / 投影 / 渐变方向 */
    private Drawable hlRow(Drawable cache, boolean grid) {
        float d = getResources().getDisplayMetrics().density;
        int shape = com.mangjie.reader.view.ThemeColors.hlShape(prefs);
        int alpha = com.mangjie.reader.view.ThemeColors.hlAlpha(prefs);
        boolean gloss = com.mangjie.reader.view.ThemeColors.hlGloss(prefs);
        boolean shadow = com.mangjie.reader.view.ThemeColors.hlShadow(prefs);
        int grad = com.mangjie.reader.view.ThemeColors.hlGrad(prefs);
        return grid
                ? com.mangjie.reader.view.Highlight.grid(hlColor(), d, shape, alpha, gloss, shadow, grad)
                : com.mangjie.reader.view.Highlight.row(hlColor(), d, shape, alpha, gloss, shadow, grad);
    }

    /** 标题栏跟随主题：底色用背景色、标题用文字色 */
    private void applyTitleTheme() {
        if (titleBar == null || prefs == null) return;
        boolean follow = com.mangjie.reader.view.ThemeColors.titleFollow(prefs);
        titleBar.setBackgroundColor(follow ? palBg() : 0xFF000000);
        if (titleView != null) titleView.setTextColor(follow ? palFg() : 0xFFFFFFFF);
    }

    /** 选中条：圆角 + 竖向渐变 + 亮描边 + 底部投影，颜色全部由 HL 派生 */
    private Drawable hlBackground() {
        if (hlBgCache != null) return hlBgCache;
        hlBgCache = hlRow(null, false);
        return hlBgCache;
    }

    /** 网格格用：再向内缩一点，圆角才看得出来 */
    private Drawable hlGridBackground() {
        if (hlGridBgCache != null) return hlGridBgCache;
        hlGridBgCache = hlRow(null, true);
        return hlGridBgCache;
    }

    private void paintSelected(View v) { v.setBackground(hlBackground()); v.invalidate(); }

    private void paintSelectedGrid(View v) { v.setBackground(hlGridBackground()); v.invalidate(); }

    private int palBg() {
        if (paletteIdx < 0) return prefs.getInt("cusBg", 0xFF101010);
        int base = PAL_BG[paletteIdx];
        if (prefs.getBoolean("cusOn", false)) return prefs.getInt("cusBgOv", base);   // 自定义叠在主题上
        return base;
    }
    private int palFg() {
        if (paletteIdx < 0) return prefs.getInt("cusFg", 0xFFFFE082);
        int base = PAL_FG[paletteIdx];
        if (prefs.getBoolean("cusOn", false)) return prefs.getInt("cusFgOv", base);
        return base;
    }
    private String palName() {
        if (paletteIdx < 0) return "自定义配色";
        String n = paletteIdx < PAL_NAMES.length ? PAL_NAMES[paletteIdx] : ("配色" + paletteIdx);
        return prefs.getBoolean("cusOn", false) ? (n + "＋自定义") : n;
    }

    /** 开始自定义时，先把当前主题的底色/字色快照进叠加层，主题本身不动 */
    private void beginCustomOverlay() {
        if (prefs.getBoolean("cusOn", false)) return;
        int bg = palBg(), fg = palFg();
        prefs.edit().putInt("cusBgOv", bg).putInt("cusFgOv", fg).putBoolean("cusOn", true).apply();
    }

    /** 退掉自定义，回到所选主题原本的样子 */
    private void clearCustomOverlay() {
        prefs.edit().putBoolean("cusOn", false).apply();
    }
    private int fontSp() { return FONT_SP[fontIdx]; }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        // 先把公有存储里的旧配置整体搬进私有目录，再让下面各模块去读目录。
        // 顺序错了会出现「书源列表空、规则空」，然后用户以为数据丢了。
        tryMigrateLegacy();
        applyDavConfig();
        initHttp();
        initUpdater();
        // 顺序不能反：BookSourceOps 构造时要把 nb 存下来，nb 还没造出来就传进去，
        // 后面点「书源详情」一摸 nbSources 就空指针（beta29 崩过两次）
        initNetBook();
        migrateShelfGroupsOnce();   // 自建分组退役前，先把只在组里的书并进收藏
        initSourceOps();
        initLogUploader();
        com.mangjie.reader.reader.TocRules.setUserDir(new File(DATA_DIR + "/目录规则"));
        ReadService.setCtl(new ReadService.Ctl() {
            @Override public void onReadCmd(final String cmd) {
                runOnUiThread(new Runnable() { @Override public void run() { handleReadCmd(cmd); } });
            }
        });
        // 通知权限不在这里要（1.1.45）：等真开前台朗读、通知栏要有按钮的时候才申请
        startStatTimer();
        startNightTimer();
        startDailyUpdCheck();

        loadPrefs();
        devLog = prefs.getBoolean("devLog", true);
        // 常亮不在这里一刀切：只有不在朗读时才保持屏幕亮，朗读中允许系统自动熄屏
        applyWindowColors();

        buildUi();
        registerBattery();
        registerUsb();
        registerHeadsetOut();
        initSpeech();
        if (migrateSpeak != null) {
            // 语音刚起来，别抢首帧，等界面出来再念
            final String m = migrateSpeak;
            migrateSpeak = null;
            ui.postDelayed(new Runnable() { @Override public void run() { speak(m); } }, 900);
        }
        initAccessibilityWatch();
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(android.net.Network n) { refreshWifi(); }
                    @Override public void onLost(android.net.Network n) { refreshWifi(); }
                    @Override public void onCapabilitiesChanged(android.net.Network n,
                            android.net.NetworkCapabilities cap) { refreshWifi(); }
                });
            }
        } catch (Exception e) { devLogLine("状态栏", "网络监听注册失败：" + e.getMessage()); }

        installCrashLogger();          // 崩溃也要落盘，便于定位
        logStartup();
        upd.cleanupOldApks();
        checkLegacyData();
        initSystemBack();

        stack.add(mainScreen());

        gestureOn = prefs.getBoolean("gestureOn", true);
        gestures = makeGestureEngine();

        explorer = new com.mangjie.reader.a11y.TouchExplorer(this,
                new com.mangjie.reader.a11y.TouchExplorer.Rows() {
                    @Override public int count() { Screen s = top(); return s == null ? 0 : s.items.size(); }
                    @Override public String label(int i) {
                        Screen s = top();
                        if (s == null || i < 0 || i >= s.items.size()) return "";
                        return s.items.get(i).label;
                    }
                    @Override public ViewGroup group() { return rowsBox; }
                });

        showLegacyPromptIfAny();
        upd.fetchChangelog(false);
        // 1.1.45：首次启动不再弹授权页。文件访问、通知、安装三项各在自己真正被用到时才申请，
        // 用户不碰那个功能就一直不打扰。授权状态页仍然留着，想查状态自己进 设置-授权状态。
        logup.uploadIfNew(false);   // 只有比上次传出去的多了才传，避免每次开机白传一遍
        // 日志传完再查更新：静默查，有新版本就自动下载（晚到 10 秒，别抢首帧；只在 WiFi 下自动下）
        if (autoUpdate) {
            ui.postDelayed(new Runnable() { @Override public void run() {
                if (!autoUpdate) return;
                if (!wifiOn) { devLogLine("更新", "当前不在无线网络，跳过自动检查"); return; }
                upd.checkUpdate(true);
            } }, 10000);
        }
        if (logMode == 2) logup.startAutoUpload();
        // 系统书源同步：服务器「书源」目录有新增或变化就拉下来，只写「系统」前缀的文件
        ui.postDelayed(new Runnable() { @Override public void run() {
            com.mangjie.reader.net.SourceSync.sync(
                    new java.io.File(BOOKSRC_DIR), prefs, ui,
                    new com.mangjie.reader.net.SourceSync.Done() {
                        @Override public void onCleaned(int removed) {
                            devLogLine("书源", "清掉本地旧系统副本 " + removed + " 个");
                        }
                        @Override public void onDone(int changedFiles, int totalSources, String note) {
                            if (!note.isEmpty()) {
                                devLogLine("书源", "系统书源同步失败　" + note);
                                return;
                            }
                            if (changedFiles <= 0) return;
                            devLogLine("书源", "系统书源同步完成　文件 " + changedFiles
                                    + " 个，共 " + totalSources + " 个源");
                            nb.loadBookSources();
                            speak("系统书源已更新，共 " + totalSources + " 个");
                        }
                    });
        } }, 4000);
        ui.post(new Runnable() { @Override public void run() { render(true); } });
        // 首帧先画出来，目录规则/净化规则/简繁表挪到首帧之后读，冷启动快一点
        ui.postDelayed(new Runnable() { @Override public void run() {
            long t0 = System.currentTimeMillis();
            com.mangjie.reader.reader.TocRules.init(MainActivity.this);
            com.mangjie.reader.reader.Purify.load(new File(DATA_DIR + "/净化"));
            com.mangjie.reader.reader.Simplifier.init(MainActivity.this);
            devLogLine("启动", "规则已加载（目录/净化/简繁）　用时 "
                    + (System.currentTimeMillis() - t0) + "ms");
            logup.uploadCrashIfAny();   // 也放到首帧之后：要读整个日志文件找异常
            // 网文章节缓存对账：书不在书架（收藏/最近读过的书）里才清，读到一半换书不动它
            final java.util.Set<String> alive = chapterCacheAlive();
            com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
                final int n = com.mangjie.reader.net.ChapterCache.reconcile(DATA_DIR, alive);
                if (n > 0) ui.post(new Runnable() { @Override public void run() {
                    devLogLine("缓存", "对账完成，清掉 " + n + " 本已不在书架的书");
                } });
            } });
        } }, 120);
    }

    /** 缓存对账的存活名单：收藏的书 + 最近读过的书 + 当前在看的这本 */
    private java.util.Set<String> chapterCacheAlive() {
        java.util.Set<String> keys = new java.util.HashSet<String>();
        String fav = prefs.getString("nbFav", "");
        for (String line : fav.split("\n")) {
            String[] p = line.split("\u0001");
            if (p.length >= 2 && !p[1].trim().isEmpty())
                keys.add(com.mangjie.reader.net.ChapterCache.bookKey(p[1].trim()));
        }
        String recent = prefs.getString("nbRecentList", "");
        for (String line : recent.split("\n")) {
            if (!line.trim().isEmpty())
                keys.add(com.mangjie.reader.net.ChapterCache.bookKey(line.trim()));
        }
        if (nb != null && nb.nbBookUrl != null && !nb.nbBookUrl.isEmpty())
            keys.add(com.mangjie.reader.net.ChapterCache.bookKey(nb.nbBookUrl));
        return keys;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        refreshGrant();
        if (req == 1001) speak(hasNotify() ? "通知权限已授权" : "通知权限未授权");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreenReaderState();     // 从无障碍设置页回来时，状态必须重算，不能带着旧值过日子
        refreshGrant();
        // 从系统授权页回来：还没搬的数据这时能搬了；用户刚才要读外部文件的动作也接着做完
        if (prefs != null && !prefs.getBoolean("movedToPrivate", false)) {
            if (hasAllFiles()) tryMigrateLegacy();
        }
        runPendingFileAction();
        refreshWifi();
        devLogLine("生命周期", "回到前台　读屏=" + (screenReaderOn ? "开" : "关")
                + "　" + memText() + "　当前页=" + (stack.isEmpty() ? "" : top().title)
                + (bgAt > 0 ? "　后台 " + ((System.currentTimeMillis() - bgAt) / 1000) + " 秒，期间正文出声 "
                        + bgProg + " 次，退后台时最后一句=" + bgLastId : "")
                + readStateText());
        bgAt = 0;
        bgProg = 0;
        bgRun = false;
        // 点明通道不再给用户开关：当前读屏是点明，就自动探测并接管；不是就老实用本机语音
        if (dmReaderDetected()) {
            if (!com.mangjie.reader.speech.DianmingVoice.isConnected())
                com.mangjie.reader.speech.DianmingVoice.probe(this, dmHost);
            devLogLine("点明", "读屏=点明，自动走点明通道　" + com.mangjie.reader.speech.DianmingVoice.status());
        } else {
            devLogLine("点明", "读屏不是点明（" + com.mangjie.reader.a11y.ReaderState.serviceIds(am) + "），用本机语音");
        }
        // 后台判过死的，回前台检查一次：朗读还开着、确实没在念、进展也停了三秒以上，就补一次续读
        final int bgStalls = bgStallCount;
        bgStallCount = 0;
        if (bgStalls > 0) {
            ui.postDelayed(new Runnable() { @Override public void run() {
                if (!autoPlay || stack.isEmpty() || !top().reader || readingNow) return;
                if (System.currentTimeMillis() - lastProgAt <= 3000) return;
                devLogLine("阅读", "回前台补一次续读（后台判死 " + bgStalls + " 次）");
                readingNow = true;
                speakSentence(top().cur);
            } }, 1600);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentPos();     // 退后台先钉住当前位置，被系统回收也不至于倒退
        savePrefs();
        bgAt = System.currentTimeMillis();
        bgProg = 0;
        bgLastId = lastProgId;
        bgRun = true;
        devLogLine("生命周期", "退到后台　" + memText() + "　退时最后一句=" + lastProgId
                + "　距上次进展=" + (lastProgAt == 0 ? "无" : (bgAt - lastProgAt) + "ms") + readStateText());
        if (logMode == 2) logup.uploadIfNew(false);
    }

    @Override
    protected void onDestroy() {
        dropReadFocus();
        try { if (batteryReceiver != null) unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        try { if (usbReceiver != null) unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        try { if (noisyReceiver != null) unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        if (a11yObserver != null) {
            try { getContentResolver().unregisterContentObserver(a11yObserver); } catch (Exception ignored) {}
        }
        if (audioPlayer != null) { try { audioPlayer.stop(); } catch (Throwable ignored) {} }
        ReadService.stop(this);
        ReadService.setCtl(null);
        if (stats != null) stats.stopTimer();
        if (nightHandler != null) { nightHandler.removeCallbacksAndMessages(null); nightHandler = null; }
        devLogLine("生命周期", "程序退出");
        try { logup.stopAutoUpload(); } catch (Exception ignored) {}
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (rdTts != null) { try { rdTts.stop(); rdTts.shutdown(); } catch (Exception ignored) {} }
        if (toneGen != null) { try { toneGen.release(); toneGen = null; } catch (Exception ignored) {} }
        super.onDestroy();
    }

    private com.mangjie.reader.net.NetBookController nb;

    public void handleNetBook(String k) {
        // 主界面「搜索」：先弹输入框拿关键词。这一支在把动作搬进 shell 时丢了，
        // 键还留在菜单里，点了只落一条日志，页面不动也不提示。
        if ("search".equals(k)) { startSearchInput(); return; }
        if ("cat".equals(k)) { pushScreen(bookSourceScreen()); return; }
        if ("favtoggle".equals(k)) { nbToggleFav(); return; }
        if ("explore".equals(k)) { nb.nbExplore(); return; }
        if ("exploreall".equals(k)) { nb.nbExploreAll(); return; }
        if ("expclr".equals(k)) {                       // 发现页空列表那行：点一下清筛选
            prefs.edit().remove("exGroup").remove("exKey").apply();
            nb.nbExplore();
            return;
        }
        // 分发时已去掉 "nb:" 前缀，这里统一按后缀匹配
        String key = (k != null && k.startsWith("nb:")) ? k.substring(3) : k;
        if (key.startsWith("esrc:")) { nb.nbExploreSrc(rules.num(key, 5)); return; }
        if (key.startsWith("egrp:")) { nb.nbExploreGroup(rules.num(key, 5)); return; }
        if (key.startsWith("ecat:")) { nb.nbOpenCat(rules.num(key, 5)); return; }
        if ("catmore".equals(key)) { nb.nbCatMore(); return; }
        if ("subs".equals(k)) { ops.openSubsScreen(); return; }
        if ("subfavs".equals(k)) { ops.openSubFavs(); return; }
        if ("suball".equals(k)) { ops.openSubAll(); return; }
        if ("subgroup".equals(k)) { ops.subGroupNext(); return; }
        if (k.startsWith("subcat:")) {
            String[] p2 = k.substring(7).split(":");
            if (p2.length >= 2) {
                int si2, ci2;
                try { si2 = Integer.parseInt(p2[0]); ci2 = Integer.parseInt(p2[1]); }
                catch (Exception e) { return; }
                ops.subOpenCat(si2, ci2);
            }
            return;
        }
        if (k.startsWith("subfavopen:")) {
            int i;
            try { i = Integer.parseInt(k.substring(11)); } catch (Exception e) { return; }
            ops.openSubFavAt(i);
            return;
        }
        if (k.startsWith("subarts:")) {
            int i;
            try { i = Integer.parseInt(k.substring(8)); } catch (Exception e) { return; }
            ops.subOpenArticles(i);
            return;
        }
        if (k.startsWith("subart:")) {
            String[] p2 = k.substring(7).split(":");
            int i;
            try { i = Integer.parseInt(p2[0]); } catch (Exception e) { return; }
            String tag = p2.length > 1 ? p2[1] : "";
            int hit = ops.findSubArticle(i, tag);      // 列表刷新过就按链接指纹重新定位
            if (hit < 0) { speak("列表刚刷新过，退回上一层重新打开一次"); return; }
            ops.subOpenArticle(hit);
            return;
        }
        if ("speaksub".equals(k)) {
            String t = ops.subArticleBody == null ? "" : ops.subArticleBody.trim();
            if (t.length() > 3500) t = t.substring(0, 3500);
            speak(t);
            return;
        }
        if ("favsub".equals(k)) {
            String one = ops.subArticleName + "\u0001" + ops.subArticleBody;
            if (one.length() > 2000) one = one.substring(0, 2000);
            String store = prefs.getString("subFav", "");
            store = one + "\n" + store;
            prefs.edit().putString("subFav", store).apply();
            speak("已收藏 " + ops.subArticleName);
            return;
        }
        if ("fav".equals(k)) { pushScreen(favBooksScreen()); return; }
        if ("recent".equals(k)) { pushScreen(recentBooksScreen()); return; }
        if ("impfile".equals(k)) {
            stack.add(ops.bsrcPickScreen(internalRoot()));
            render(true);
            return;
        }
        if ("impurl".equals(k)) {
            inputAction = "bsrcimpurl"; inputTitle = "书源网址"; openInput("输入书源 JSON 的下载地址");
            return;
        }
        if ("export".equals(k)) { ops.bsrcExport(); return; }
        if ("reload".equals(k)) {
            nb.loadBookSources();
            stack.remove(stack.size() - 1);
            stack.add(bookSourceScreen());
            render(true);
            speak("已载入 " + nb.nbSources.size() + " 个书源");
            return;
        }
        if ("switch".equals(k)) { nb.nbStartSwitch(); return; }
        if (k.startsWith("ssone:")) {
            int i;
            try { i = Integer.parseInt(k.substring(6)); } catch (Exception e) { return; }
            nb.loadBookSources();
            if (i < 0 || i >= nb.nbSources.size()) return;
            String nm = nb.nbSources.get(i).optString("bookSourceName", "");
            nb.setSearchScope("s:" + nm);
            afterScopeChange(nm);
            return;
        }
        // 搜索页第一行那个「搜索框」：双击回输入页改关键词
        String hk = (k != null && k.startsWith("nb:")) ? k.substring(3) : k;
        if ("reinput".equals(hk)) { startSearchInput(); return; }
        // 清空最近搜索（入口在搜索菜单里）
        if ("histclr".equals(hk)) { nb.clearSearchHistory(); return; }
        // 停止这一轮搜索，已找到的留着
        if ("stopsearch".equals(hk)) { nb.stopSearch(); return; }
        // 最近搜过的词：双击直接按这个词重搜
        if (hk != null && hk.startsWith("hist:")) { nb.nbSearch(hk.substring(5)); return; }
        if ("prec".equals(k)) {
            boolean on = !nb.precisionSearch();
            nb.setPrecisionSearch(on);
            speak(on ? "精准搜索已开，只留书名或作者含关键字的结果" : "精准搜索已关，源返回的都留着");
            String lastKey = nb.nbLastKey == null ? "" : nb.nbLastKey.trim();
            Screen tp = top();
            if (!lastKey.isEmpty() && tp != null && "nbSearch".equals(tp.key)) {
                nb.nbSearch(lastKey);   // 跟 Legado 一样：切开关就按当前关键字重搜
            } else {
                hideMenuOverlay();
                render(true);
            }
            return;
        }
        if ("ssall".equals(k)) { nb.setSearchScope(""); afterScopeChange("全部书源"); return; }
        if (k.startsWith("ssg:")) {
            int i;
            try { i = Integer.parseInt(k.substring(4)); } catch (Exception e) { return; }
            List<String> gs = nb.sourceGroupNames();
            if (i < 0 || i >= gs.size()) return;
            nb.setSearchScope("g:" + gs.get(i));
            afterScopeChange("分组 " + gs.get(i));
            return;
        }
        if ("fsrc".equals(k)) { stack.add(searchBySourceScreen()); render(true); return; }
        if ("fz".equals(k)) { if (stack.size() > 1) stack.remove(stack.size() - 1); render(true); speak("看全部来源"); return; }
        if (k.startsWith("fs:")) { int si; try { si = Integer.parseInt(k.substring(3)); } catch (Exception e) { return; } showSearchBySource(String.valueOf(si)); return; }
        if ("tocrev".equals(k)) {
            boolean on = !prefs.getBoolean("tocRevAll", false);
            prefs.edit().putBoolean("tocRevAll", on).apply();
            if (!nb.nbChapters.isEmpty()) java.util.Collections.reverse(nb.nbChapters);
            if (!stack.isEmpty() && !nb.nbChapters.isEmpty()) stack.set(stack.size() - 1, nb.nbChapterListScreen());
            render(true);
            speak(on ? "目录倒序已开" : "目录倒序已关");
            return;
        }
        if ("tocfilter".equals(k)) {
            inputAction = "tocfilter";
            inputTitle = "目录过滤词";
            openInput("多个词用逗号隔开，例如 求票,广告,公告");
            return;
        }
        if ("updauto".equals(k)) {
            boolean on = !prefs.getBoolean("updAuto", true);
            prefs.edit().putBoolean("updAuto", on).apply();
            if (!stack.isEmpty()) stack.set(stack.size() - 1, setDataScreen());
            render(true);
            speak(on ? "每天自动检查已开" : "每天自动检查已关");
            return;
        }
        if ("checkupd".equals(k)) { nb.nbCheckUpdates(); return; }
        if ("cacheall".equals(k)) { nb.nbCacheAll(); return; }
        if ("cachech".equals(k)) { nb.nbCacheChapter(); return; }
        if (k.startsWith("sw:")) {
            int i;
            try { i = Integer.parseInt(k.substring(3)); } catch (Exception e) { return; }
            nb.nbJumpChapter = nb.nbLastChapter == null || nb.nbLastChapter.isEmpty() ? null : nb.nbLastChapter;
            nb.nbOpenBook(i);
            return;
        }
        if (k.startsWith("book:")) {
            String[] p2 = k.substring(5).split(":");
            int i;
            try { i = Integer.parseInt(p2[0]); } catch (Exception e) { return; }
            if (p2.length > 1) {
                int seq;
                try { seq = Integer.parseInt(p2[1]); } catch (Exception e) { seq = -1; }
                if (seq >= 0 && seq != nb.nbResultsSeq) {      // 结果列表已经被新一轮搜索换掉了
                    speak("搜索结果已经更新，重新搜一次再点");
                    return;
                }
            }
            nb.nbOpenBook(i);
            return;
        }
        if (k.startsWith("openbook:")) {
            int i;
            try { i = Integer.parseInt(k.substring(9)); } catch (Exception e) { return; }
            nb.nbOpenBook(i);
            return;
        }
        if ("log".equals(k)) { pushScreen(shelfLogScreen()); return; }
        if (k.startsWith("readall:")) {
            int i;
            try { i = Integer.parseInt(k.substring(8)); } catch (Exception e) { return; }
            if (nb.nbIsAudioBook) { audioPlayChapter(i); return; }
            nb.nbOpenChapter(i, true);
            return;
        }
        if (k.startsWith("ch:")) {
            int i;
            try { i = Integer.parseInt(k.substring(3)); } catch (Exception e) { return; }
            if (nb.nbIsAudioBook) { audioPlayChapter(i); return; }
            nb.nbOpenChapter(i, false);
            return;
        }
        if (k.startsWith("ab:")) { if (handleAudioKey(k.substring(3))) return; }
        if ("chain".equals(k)) {
            boolean on = !prefs.getBoolean("nbChain", true);
            prefs.edit().putBoolean("nbChain", on).apply();
            if (!on) { nb.nbChainStop(); if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} } }
            if (!stack.isEmpty()) stack.set(stack.size() - 1, top());
            render(true);
            speak(on ? "整章连播已开" : "整章连播已关");
            return;
        }
        if ("speakchapter".equals(k)) { speakChapter(); return; }
        if ("favadd".equals(k)) {
            String one = nb.nbBookName + "\u0001" + nb.nbBookUrl + "\u0001" + nb.nbSourceIdx;
            String store = prefs.getString("nbFav", "");
            if (!store.contains(nb.nbBookUrl)) {
                store = one + "\n" + store;
                prefs.edit().putString("nbFav", store).apply();
            }
            speak("已收藏 " + nb.nbBookName);
            return;
        }
        if ("detail".equals(k)) { openNetDetail(); return; }
        if ("chapterlist".equals(k)) {
            if (nb == null) return;
            pushScreen(nb.nbChapterListScreen());
            return;
        }
        if ("swsort".equals(k)) {
            // 排序三态：按源顺序 → 按章数 → 按字数（对齐 Legado 的字数排序）
            int m = prefs.getInt("swSortMode", 1);
            m = (m + 1) % 3;
            prefs.edit().putInt("swSortMode", m).apply();
            speak("排序 " + (m == 0 ? "按源顺序" : (m == 1 ? "按章数" : "按字数")));
            nb.nbStartSwitch();      // 立即按新设置重搜一次
            return;
        }
        if ("swauthor".equals(k) || "swtoc".equals(k)) {
            String pre = "swauthor".equals(k) ? "swCheckAuthor" : "swLoadToc";
            boolean v = !prefs.getBoolean(pre, true);
            prefs.edit().putBoolean(pre, v).apply();
            speak((("swauthor".equals(k)) ? "校验作者 " : "先验目录 ") + (v ? "开" : "关"));
            nb.nbStartSwitch();
            return;
        }
        if ("swgroup".equals(k)) {
            inputAction = "swgroup";
            inputTitle = "书源分组";
            openInput("只在这些分组里找，逗号分隔；留空为全部");
            return;
        }
        if ("swkey".equals(k)) {
            inputAction = "swkey";
            inputTitle = "筛选词";
            openInput("按书源名、书名或作者过滤；留空为不过滤");
            return;
        }
        if ("recclear".equals(k)) {
            prefs.edit().remove("nbRecentList").remove("nbRecent").apply();
            if (!stack.isEmpty()) stack.set(stack.size() - 1, recentBooksScreen());
            render(true);
            speak("已清空最近读过的书，缓存没删");
            return;
        }
        if ("recdel".equals(k)) {
            removeRecentBook(nb == null ? null : nb.nbBookUrl);
            speak("已从最近读过的书里移除，缓存保留到清缓存为止");
            refreshBookInfo();
            return;
        }
        if ("cacheclear".equals(k)) {
            if (nb == null || nb.nbBookUrl == null) { speak("现在没有打开网上书"); return; }
            int cnt = com.mangjie.reader.net.ChapterCache.chapterCount(DATA_DIR, nb.nbBookUrl);
            com.mangjie.reader.net.ChapterCache.clearBook(DATA_DIR, nb.nbBookUrl);
            nb.nbClearTextCache();
            devLogLine("缓存", "清除本书缓存 " + nb.nbBookName + "　" + cnt + " 章");
            speak("已清除本书缓存 " + cnt + " 章");
            toast("已清除 " + cnt + " 章缓存");
            refreshBookInfo();
            return;
        }
        if (k.startsWith("openrec:")) {
            String payload = k.substring(8);
            String[] p = payload.split("\u0001");
            if (p.length < 3) return;
            nb.loadBookSources();
            nb.nbResults = new ArrayList<>();
            nb.nbResults.add(new String[]{p[0], "", p[1], p[2]});
            nb.nbOpenBook(0);
            return;
        }
        if (k.startsWith("openfav:") || "openrecent".equals(k)) {
            String payload = k.startsWith("openfav:") ? k.substring(8) : prefs.getString("nbRecent", "");
            String[] p = payload.split("\u0001");
            String name = "", url = "", ref = "";
            if (p.length >= 3) { name = p[0]; url = p[1]; ref = p[2]; }
            else if (p.length == 2) { url = p[0]; ref = p[1]; }        // 老记录：只有网址 + 序号
            else return;
            nb.loadBookSources();
            int si = nb.sourceIdxByRef(ref, url);
            if (si < 0) {                                             // 源没了：拿书名重新找源
                if (name.isEmpty()) {
                    for (String line : prefs.getString("nbFav", "").split("\n")) {
                        String[] q = line.split("\u0001");
                        if (q.length >= 2 && q[1].trim().equals(url)) { name = q[0]; break; }
                    }
                }
                nb.nbOpenByFallback(name, url);
                return;
            }
            nb.nbResults = new ArrayList<>();
            nb.nbResults.add(new String[]{name, "", url, "" + si});
            nb.nbOpenBook(0);
            return;
        }
    }

    private com.mangjie.reader.net.Http http;
    private com.mangjie.reader.net.Updater upd;
    private com.mangjie.reader.net.BookSourceOps ops;
    private com.mangjie.reader.net.LogUploader logup;



    private void initLogUploader() {
        logup = new com.mangjie.reader.net.LogUploader(new com.mangjie.reader.net.LogUploader.Host() {
            @Override public void log(String tag, String msg) { devLogLine(tag, msg); }
            @Override public void speak(String msg) { MainActivity.this.speak(msg); }
            @Override public void toast(String msg) { MainActivity.this.toast(msg); }
            @Override public android.os.Handler ui() { return ui; }
            @Override public String dataDir() { return DATA_DIR; }
            @Override public String deviceShort() { return com.mangjie.reader.reader.TextUtil.deviceShort(); }
            @Override public android.util.DisplayMetrics metrics() { return getResources().getDisplayMetrics(); }
            @Override public String extRoot() { return android.os.Environment.getExternalStorageDirectory().getPath(); }
            @Override public android.app.ActivityManager.MemoryInfo memInfo() {
                android.app.ActivityManager am =
                        (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return mi;
            }
            @Override public String appVersion() { return MainActivity.this.appVersion(); }
            @Override public String davBase() { return DAV_BASE; }
            @Override public String logDir() { return DAV_LOG_DIR; }
            @Override public String davLastErr() { return DAV_LAST_ERR; }
            @Override public void davMkcol(String dir) { MainActivity.this.davMkcol(dir); }
            @Override public boolean davPut(String url, byte[] data) { return MainActivity.this.davPut(url, data); }
            @Override public void refreshTop(String msg) { MainActivity.this.refreshTop(msg); }
            @Override public boolean autoUploadOn() { return autoUpload; }
            @Override public boolean logNetOn() { return logMode > 0; }
            @Override public boolean devModeOn() { return logMode >= 2; }
            @Override public boolean onWifi() { return wifiOn; }
            @Override public long savedUploadedChars() { return prefs.getLong("logUpChars", 0L); }
            @Override public void saveUploadedChars(long chars) { prefs.edit().putLong("logUpChars", chars).apply(); }
            @Override public String a11yInfo() { return MainActivity.this.a11yInfo(); }
            @Override public String deviceTag() { return MainActivity.this.deviceTag(); }
        });
    }

    /**
     * 日志头里的无障碍环境。批量测试时全靠这几行把「读不了」分组：
     * 是读屏在抢焦点、是引擎不吃队列、还是没装中文语音包，看这里就能分开。
     */
    private String a11yInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("读屏：").append(com.mangjie.reader.a11y.ReaderState.serviceIds(am)).append("\n");
        } catch (Throwable t) { sb.append("读屏：读取失败\n"); }
        try {
            sb.append("朗读引擎：").append(readerVoice.enginePkg == null ? "系统默认" : readerVoice.enginePkg).append("\n");
            sb.append("界面引擎：").append(menuVoice.enginePkg == null ? "系统默认" : menuVoice.enginePkg).append("\n");
            sb.append("朗读加倍：").append(com.mangjie.reader.speech.VoiceProfile.mulLabel(readerVoice.speedMul)).append("\n");
        } catch (Throwable ignored) {}
        try { sb.append("点明通道：").append(dmVoiceOn() ? "开" : "关").append("\n"); } catch (Throwable ignored) {}
        sb.append("语音开关：").append(speechOn ? "开" : "关").append("\n");
        sb.append("日志档位：").append(logMode).append("\n");
        return sb.toString();
    }

    /** 日志文件名用的设备短标识：只留 ASCII，同一台机器每次算出来都一样，换机可区分 */
    private String deviceTag() {
        try {
            String model = Build.MODEL == null ? "dev" : Build.MODEL.replaceAll("[^A-Za-z0-9_-]", "_");
            String id = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID);
            if (id == null || id.trim().isEmpty()) id = Integer.toHexString(Build.FINGERPRINT.hashCode());
            if (model.length() > 18) model = model.substring(0, 18);
            return model + "-" + id.substring(0, Math.min(6, id.length()));
        } catch (Throwable t) {
            return "dev";
        }
    }


    private void initSourceOps() {
        ops = new com.mangjie.reader.net.BookSourceOps(new com.mangjie.reader.net.BookSourceOps.Host() {
            @Override public void log(String tag, String msg) { devLogLine(tag, msg); }
            @Override public void speak(String msg) { MainActivity.this.speak(msg); }
            @Override public void toast(String msg) { MainActivity.this.toast(msg); }
            @Override public android.os.Handler ui() { return ui; }
            @Override public String httpText(String url) { return MainActivity.this.httpGetText(url); }
            @Override public String readTextFile(java.io.File f) { return MainActivity.this.readTextFile(f); }
            @Override public void setUrlCtx(String key, int page) { if (http != null) http.setCtx(key, page); }
            @Override public void setJsLib(String lib) { MainActivity.this.setJsLib(lib); }
            @Override public void setSource(org.json.JSONObject src) { MainActivity.this.setSource(src); }
            @Override public String requestUrl(String spec, String base) { return MainActivity.this.requestUrl(spec, base); }
            @Override public String requestUrlT(String spec, String base, int t) { return MainActivity.this.requestUrlT(spec, base, t); }
            @Override public String bookSrcDir() { return BOOKSRC_DIR; }
            @Override public String dataDir() { return DATA_DIR; }
            @Override public String subSrcDir() { return SUBSRC_DIR; }
            @Override public boolean prefBool(String key, boolean def) { return prefs.getBoolean(key, def); }
            @Override public int prefInt(String key, int def) { return prefs.getInt(key, def); }
            @Override public String prefString(String key, String def) {
                String v = prefs.getString(key, def);
                return v == null ? def : v;
            }
            @Override public void prefSet(String key, String value) { prefs.edit().putString(key, value).apply(); }
            @Override public void pushScreen(com.mangjie.reader.model.Screen s) { MainActivity.this.pushScreen(s); }
            @Override public com.mangjie.reader.model.Screen topScreen() { return top(); }
            @Override public void replaceTop(com.mangjie.reader.model.Screen s) {
                stack.remove(stack.size() - 1);
                stack.add(s);
                render(true);
            }
            @Override public com.mangjie.reader.model.Screen bookSourceScreen() { return MainActivity.this.bookSourceScreen(); }
            @Override public com.mangjie.reader.model.Screen subSourceScreen() { return MainActivity.this.subSourceScreen(); }
            @Override public String stamp() { return MainActivity.this.stamp(); }
            @Override public void renderNow() { MainActivity.this.render(true); }
            @Override public boolean openUrl(String url) { return MainActivity.this.openInBrowser(url); }
        }, nb, rules);
    }

    private Screen subSourceScreen() { return ops.subSourceScreen(); }

    /** 所有书源分组的入口页（对齐 Legado 的 bookSourceGroup 浏览） */
    private Screen sourceGroupScreen() {
        nb.loadBookSources();
        List<Item> items = new ArrayList<>();
        List<String> gs = nb.sourceGroupNames();
        for (int i = 0; i < gs.size(); i++) {
            List<Integer> idx = nb.sourceGroupIdx(gs.get(i));
            int on = 0, text = 0;
            for (Integer k : idx) {
                if (nb.srcEnabled(nb.nbSources.get(k))) on++;
                if (nb.srcType(nb.nbSources.get(k)) == 0) text++;
            }
            items.add(new Item(gs.get(i) + "　" + idx.size() + " 个（开 " + on + "，文本 " + text + "）", "bsrc:grp:" + i));
        }
        if (gs.isEmpty()) items.add(new Item("（书源都没有填分组）"));
        items.add(new Item("返回书源列表", "nb:cat"));
        return new Screen("书源分组　" + gs.size() + " 组", items).k("bsrc");
    }

    /** 点进某个分组，只看这组的源 */
    private Screen sourceGroupListScreen(int gi) {
        nb.loadBookSources();
        List<String> gs = nb.sourceGroupNames();
        if (gi < 0 || gi >= gs.size()) return sourceGroupScreen();
        String g = gs.get(gi);
        List<Item> items = new ArrayList<>();
        for (Integer k : nb.sourceGroupIdx(g)) {
            JSONObject o = nb.nbSources.get(k);
            String name = o.optString("bookSourceName", "未命名");
            String ty = com.mangjie.reader.net.NetBookController.srcTypeName(nb.srcType(o));
            items.add(new Item(name + "（" + (nb.srcEnabled(o) ? "开" : "关") + "／" + ty + "）", "bsrc:detail:" + k));
        }
        if (items.isEmpty()) items.add(new Item("（空）"));
        items.add(new Item("返回分组列表", "bsrc:groups"));
        return new Screen("分组：" + g, items).k("bsrc");
    }


    private void initUpdater() {
        upd = new com.mangjie.reader.net.Updater(new com.mangjie.reader.net.Updater.Host() {
            @Override public void log(String tag, String msg) { devLogLine(tag, msg); }
            @Override public void speak(String msg) { MainActivity.this.speak(msg); }
            @Override public void toast(String msg) { MainActivity.this.toast(msg); }
            @Override public android.os.Handler ui() { return ui; }
            @Override public String davGet(String name) { return MainActivity.this.davGet(name); }
            @Override public String davLastErr() { return DAV_LAST_ERR; }
            @Override public String davDir() { return DAV_DIR; }
            @Override public String davBase() { return DAV_BASE; }
            @Override public String davUser() { return DAV_USER; }
            @Override public String davPass() { return DAV_PASS; }
            @Override public String appVersion() { return MainActivity.this.appVersion(); }
            @Override public String localApkFor(String ver) { return MainActivity.this.localApkFor(ver); }
            @Override public void installApk(String path) { MainActivity.this.installApk(path); }
            @Override public java.io.File apkDir() { return getExternalFilesDir("apk"); }
            @Override public String changelogPath() { return CHANGELOG_URL_PATH; }
            @Override public String changelogPathOld() { return CHANGELOG_URL_PATH_OLD; }
            @Override public String davLastUrl() { return DAV_LAST_URL; }
            @Override public String changelogCache() { return CHANGELOG_CACHE; }
            @Override public void refreshChangelogPage() {
                stack.remove(stack.size() - 1);
                stack.add(changelogScreen());
                render(true);
                MainActivity.this.speak("更新日志已刷新");
            }
            @Override public String dataDir() { return DATA_DIR; }
        });
    }







    private void startDailyUpdCheck() {
        if (!prefs.getBoolean("updAuto", true)) return;
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(new java.util.Date());
        if (today.equals(prefs.getString("updLast", ""))) return;
        String store = prefs.getString("nbFav", "");
        if (store == null || store.trim().isEmpty()) return;
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            int changed = 0;
            int checked = 0;
            for (String line : store.split("\n")) {
                String[] f = line.split("\u0001");
                if (f.length < 3) continue;
                int si;
                try { si = Integer.parseInt(f[2]); } catch (Exception e) { continue; }
                if (si < 0 || si >= nb.nbSources.size()) continue;
                int now = nb.nbCountChapters(nb.nbSources.get(si), f[1]);
                int old = prefs.getInt("nbCnt_" + f[1], 0);
                checked++;
                if (now > 0 && old > 0 && now > old) changed++;
                if (now > 0 && old <= 0) prefs.edit().putInt("nbCnt_" + f[1], now).apply();
            }
            prefs.edit().putString("updLast", today)
                    .putString("updPending", changed > 0 ? (changed + " 本有新章") : "").apply();
            devLogLine("追更", "每日自动检查 " + checked + " 本，更新 " + changed + " 本");
        } });
    }



    private String subArticleName = "";

    private void initHttp() {
        http = new com.mangjie.reader.net.Http(new com.mangjie.reader.net.Http.Host() {
            @Override public void log(String tag, String msg) { devLogLine(tag, msg); }
            @Override public String hostOf(String url) { return rules.hostOf(url); }
            @Override public String appVersion() { return MainActivity.this.appVersion(); }
            @Override public String evalJs(String code, String result) { return MainActivity.this.evalJs(code, result, jsBase); }
        });
    }

    private void initNetBook() {
        nb = new com.mangjie.reader.net.NetBookController(
                new com.mangjie.reader.net.NetBookController.Host() {
            @Override public void speakQueued(String msg) { MainActivity.this.speakQueued(msg); }
                    @Override public String lastJsError() { return MainActivity.this.lastJsErr; }
                    @Override public void log(String tag, String msg) { devLogLine(tag, msg); }
                    @Override public void speak(String msg) { MainActivity.this.speak(msg); }
                    @Override public void render(boolean reset) { MainActivity.this.render(reset); }
                    @Override public void setUrlCtx(String key, int page) { if (http != null) http.setCtx(key, page); }
                    @Override public void setJsPage(int page) { MainActivity.this.jsPage = page <= 0 ? 1 : page; }
                    @Override public void setJsLib(String lib) { MainActivity.this.setJsLib(lib); }
                    @Override public void setSource(org.json.JSONObject src) { MainActivity.this.setSource(src); }
                    @Override public String requestUrl(String url, String base) { return MainActivity.this.requestUrl(url, base); }
                    @Override public String requestUrlT(String url, String base, int t) { return MainActivity.this.requestUrlT(url, base, t); }
                    @Override public int lastHttpStatus() { return http == null ? 0 : http.lastStatus; }
                    @Override public com.mangjie.reader.net.Http.Resp requestResp(String url, String base, int t) {
                        if (http == null) return null;
                        if (t <= 0) return http.fetch(url, base);
                        return http.fetch(url, base, t);
                    }
                    @Override public String readTextFile(java.io.File f) { return MainActivity.this.readTextFile(f); }
                    @Override public Screen bookSourceScreen() { return MainActivity.this.bookSourceScreen(); }
                    @Override public void speakChapter() { MainActivity.this.speakChapter(); }
                    @Override public void openNetChapter(String title, String body, String key, boolean autoRead) {
                        MainActivity.this.openNetChapter(title, body, key, autoRead);
                    }
                    @Override public java.util.List<String> chunkForTts(String text, int size) {
                        return com.mangjie.reader.reader.TextUtil.chunkForTts(text, size);
                    }
                    @Override public boolean chainSpeak(String chunk) {
                        if (tts != null && ttsReady) {
                            tts.speak(chunk, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "nbc");
                            return true;
                        }
                        return false;
                    }
                    @Override public void toast(String msg) { MainActivity.this.toast(msg); }
                    @Override public Screen top() { return MainActivity.this.top(); }
                    @Override public String safeName(String s) { return MainActivity.this.safeName(s); }
                    @Override public String dataDir() { return DATA_DIR; }
                    @Override public void pushScreen(Screen s) { MainActivity.this.pushScreen(s); }
                    @Override public void appendSearchRow(final String label, final String key, final Screen target) {
                        MainActivity.this.appendSearchRow(label, key, target);
                    }
                    @Override public void insertSearchRowView(final Screen target, final int at, final int placeholderAt) {
                        MainActivity.this.insertSearchRowView(target, at, placeholderAt);
                    }
                    @Override public void dropTailRow(final String key) {
                        MainActivity.this.dropTailRow(key);
                    }
                    @Override public void updateSearchRow(final int idx, final String label) {
                        MainActivity.this.updateSearchRow(idx, label);
                    }
                    @Override public String groupOf(java.util.regex.Matcher m, String rule) {
                        return MainActivity.this.groupOf(m, rule);
                    }
                    @Override public void openSubsScreen() { MainActivity.this.ops.openSubsScreen(); }
                    @Override public void subOpenArticles(int i) { MainActivity.this.ops.subOpenArticles(i); }
                }, rules, prefs, ui, stack, BOOKSRC_DIR);
    }

    private void loadPrefs() {
        if (prefs == null) return;
        paletteIdx = prefs.getInt("palette", 0);
        lineSpaceIdx = prefs.getInt("lineSpace", 1);
        sidePadIdx = prefs.getInt("sidePad", 1);
        simplifyOn = prefs.getBoolean("simplify", false);
        fontIdx = prefs.getInt("font", 1);
        speechOn = prefs.getBoolean("speech", true);
        menuVoice.enginePkg = prefs.getString("engine", null);
        menuVoice.voiceName = prefs.getString("voice", null);
        readerVoice.voiceName = prefs.getString("rdVoice", null);
        readerVoice.enginePkg = prefs.getString("rdEngine", null);
        menuVoice.rateP = prefs.getInt("rateP", 50);
        menuVoice.pitchP = prefs.getInt("pitchP", 50);
        menuVoice.speedMul = readSpeedMul("rateMul", "rateDouble");
        soundFx = prefs.getBoolean("soundFx", true);
        hapticOn = prefs.getBoolean("haptic", true);
        sfxVol = prefs.getInt("sfxVol", 50);
        hapLevel = prefs.getInt("hapLevel", 1);
        useLinear = prefs.getBoolean("useLinear", false);
        usbAutoMtp = prefs.getBoolean("usbAutoMtp", true);
        logMode = prefs.getInt("logMode", 1);
        if (logMode < 0 || logMode > 2) logMode = 1;
        autoUpload = logMode > 0;
        autoUpdate = prefs.getBoolean("autoUpdate", true);
        devMode = prefs.getBoolean("devMode", false);
        // 老版本存过「关」，这里按新要求把写日志打开一次（之后用户自己关掉的尊重）
        if (!prefs.getBoolean("devDefaultDone", false)) {
            devLog = true;
            prefs.edit().putBoolean("devLog", true)
                    .putBoolean("devDefaultDone", true).apply();
        }
        readerVoice.rateP = prefs.getInt("rdRateP", 50);
        readerVoice.pitchP = prefs.getInt("rdPitchP", 50);
        readerVoice.speedMul = readSpeedMul("rdMul", "rdDouble");
    }

    private void savePrefs() {
        if (prefs == null) return;
        prefs.edit()
                .putInt("palette", paletteIdx)
                .putInt("lineSpace", lineSpaceIdx)
                .putInt("sidePad", sidePadIdx)
                .putBoolean("simplify", simplifyOn)
                .putInt("font", fontIdx)
                .putBoolean("speech", speechOn)
                .putString("engine", menuVoice.enginePkg)
                .putString("voice", menuVoice.voiceName)
                .putString("rdVoice", readerVoice.voiceName)
                .putString("rdEngine", readerVoice.enginePkg)
                .putInt("rateP", menuVoice.rateP)
                .putInt("pitchP", menuVoice.pitchP)
                .putInt("rateMul", menuVoice.speedMul).remove("rateDouble")
.putBoolean("soundFx", soundFx)
                .putBoolean("haptic", hapticOn)
                .putInt("sfxVol", sfxVol)
                .putInt("hapLevel", hapLevel)
                .putBoolean("useLinear", useLinear)
                .putBoolean("usbAutoMtp", usbAutoMtp)
                .putBoolean("autoUpload", autoUpload)
                .putInt("logMode", logMode)
                .putBoolean("autoUpdate", autoUpdate)
                .putBoolean("devMode", devMode)
                .putInt("rdRateP", readerVoice.rateP)
                .putInt("rdPitchP", readerVoice.pitchP)
                .putInt("rdMul", readerVoice.speedMul).remove("rdDouble")
                .apply();
    }

    private void applyWindowColors() {
        getWindow().setStatusBarColor(palBg());
        getWindow().setNavigationBarColor(palBg());
    }

    // ---------------------------------------------------------------- 界面骨架

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }


    /** 电量：直接读系统 ACTION_BATTERY_CHANGED 的粘性广播，跟着真实电量变 */
    private void registerUsb() {
        usbReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent it) {
                boolean c = it.getBooleanExtra("connected", false);
                boolean cfg = it.getBooleanExtra("configured", false);
                devLogLine("USB", "connected=" + c + "　configured=" + cfg
                        + "　mtp=" + it.getBooleanExtra("mtp", false));
                boolean wasCfg = usbConfigured;
                usbConfigured = cfg;
                usbConnected = c;
                if (cfg && !wasCfg && usbAutoMtp) {
                    trySetMtp(true);      // 插上电脑就自动切「传输文件」
                    refreshUsbPageIfOpen();
                }
            }
        };
        IntentFilter f = new IntentFilter("android.hardware.usb.action.USB_STATE");
        try { registerReceiver(usbReceiver, f); } catch (Exception ignored) {}
    }

    /**
     * 拔耳机就暂停朗读：对齐 Legado 的 BaseReadAloudService（收到 ACTION_AUDIO_BECOMING_NOISY 调 pause）。
     * 不暂停的话，耳机一拔书就外放出来。
     */
    private void registerHeadsetOut() {
        noisyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent it) {
                String a = it == null ? null : it.getAction();
                if (!android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(a)) return;
                devLogLine("耳机", "音频输出断开（拔耳机/断开蓝牙）");
                if (audio().isPlaying()) {
                    audio().pause();
                    ui.post(new Runnable() { @Override public void run() {
                        toast("耳机已断开，音频暂停");
                        speak("耳机已断开，音频已暂停");
                    } });
                }
                if (autoPlay) {
                    ui.post(new Runnable() { @Override public void run() {
                        pauseReading();
                        toast("耳机已断开，朗读暂停");
                        devLogLine("耳机", "朗读已暂停，等按播放键再读");
                    } });
                }
            }
        };
        IntentFilter f = new IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        try { registerReceiver(noisyReceiver, f); } catch (Exception ignored) {}
    }

    /** 尝试把 USB 模式切到「传输文件」(MTP)。普通应用没有 MANAGE_USB 权限，失败时给出引导 */
    private boolean trySetMtp(boolean silent) {
        boolean ok = false;
        try {
            android.hardware.usb.UsbManager um =
                    (android.hardware.usb.UsbManager) getSystemService(USB_SERVICE);
            if (um != null) {
                java.lang.reflect.Method m = android.hardware.usb.UsbManager.class
                        .getMethod("setCurrentFunction", String.class, boolean.class);
                m.invoke(um, "mtp", true);
                ok = true;
            }
        } catch (Throwable t) {
            devLogLine("USB", "切换 MTP 失败：" + t.getClass().getSimpleName());
        }
        if (!ok && !silent) {
            speak("无法自动切换，请下拉通知栏，点 USB 选项，选传输文件");
            toast("请在通知栏把 USB 用途改为「传输文件」");
        }
        devLogLine("USB", "切换 MTP " + (ok ? "成功" : "失败（无系统权限）"));
        return ok;
    }

    private void refreshUsbPageIfOpen() {
        speak("已连接电脑，正在切换传输文件模式");
    }

    private void registerBattery() {
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent it) {
                int level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
                setBattery(pct);
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = registerReceiver(batteryReceiver, f);
        if (sticky != null) {
            int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            setBattery((level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1);
        }
    }

    private void setBattery(int pct) {
        batteryPct = pct;
        if (statusView == null) return;
        String txt = pct < 0 ? "--%" : (pct + "%");
        statusView.setText(txt);
        if (batteryIcon != null) batteryIcon.invalidate();
        statusView.setContentDescription(pct < 0 ? "电量未知" : ("电量 " + pct + "%"));
    }

    private void refreshWifi() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean on = false;
            if (cm != null) {
                android.net.Network n = cm.getActiveNetwork();
                if (n != null) {
                    android.net.NetworkCapabilities cap = cm.getNetworkCapabilities(n);
                    on = cap != null && cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
                }
            }
            if (on != wifiOn) devLogLine("状态栏", "无线网络=" + (on ? "已连接" : "未连接"));
            wifiOn = on;
            if (wifiIcon != null) wifiIcon.invalidate();
        } catch (Exception ignored) {}
    }

    /** 右上角的无线网络图标：三道弧 + 一个点，连上是绿、没连是白 */
    private static class WifiIcon extends View {
        MainActivity host;
        WifiIcon(MainActivity h) { super(h); host = h; }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            if (host == null) return;
            float w = getWidth(), h = getHeight();
            if (w <= 1 || h <= 1) return;
            int color = host.wifiOn ? 0xFF00E676 : host.palFg();
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, host.dp(1.6f)));
            float cx = w / 2f, cy = h - host.dp(1.5f);
            float[] radii = {w * 0.52f, w * 0.34f, w * 0.17f};
            for (float r : radii) {
                android.graphics.RectF oval = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
                cv.drawArc(oval, 225f, 90f, false, p);
            }
            p.setStyle(android.graphics.Paint.Style.FILL);
            cv.drawCircle(cx, cy, Math.max(1f, host.dp(1.4f)), p);
        }
    }

    /** 右上角的小电池图标：外框 + 电极 + 按电量填充 */
    private static class BatteryIcon extends View {
        MainActivity host;
        BatteryIcon(MainActivity h) { super(h); host = h; }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            if (host == null) return;
            float w = getWidth(), h = getHeight();
            if (w <= 1 || h <= 1) return;
            float bodyW = w - host.dp(4);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(host.palFg());
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, host.dp(1)));
            float r = host.dp(1.5f);
            cv.drawRoundRect(new android.graphics.RectF(0, 0, bodyW, h), r, r, p);

            p.setStyle(android.graphics.Paint.Style.FILL);
            cv.drawRect(bodyW, h * 0.3f, w, h * 0.7f, p);

            int pct = host.batteryPct < 0 ? 0 : host.batteryPct;
            if (pct > 100) pct = 100;
            float innerL = host.dp(2), innerT = host.dp(2);
            float innerW = bodyW - host.dp(4), innerH = h - host.dp(4);
            if (innerW > 0 && innerH > 0 && pct > 0) {
                cv.drawRect(innerL, innerT, innerL + innerW * pct / 100f, innerT + innerH, p);
            }
        }
    }

    private void buildUi() {
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(palBg());

        LinearLayout bar = new LinearLayout(this);
        titleBar = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));

        titleView = new TextView(this);
        titleView.setTextColor(palFg());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        applyTitleTheme();

        wifiIcon = new WifiIcon(this);
        bar.addView(wifiIcon, new LinearLayout.LayoutParams(dp(27), dp(15)));
        LinearLayout.LayoutParams wlp = (LinearLayout.LayoutParams) wifiIcon.getLayoutParams();
        wlp.leftMargin = dp(8);

        batteryIcon = new BatteryIcon(this);
        bar.addView(batteryIcon, new LinearLayout.LayoutParams(dp(27), dp(13)));
        LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) batteryIcon.getLayoutParams();
        blp.leftMargin = dp(8);

        statusView = new TextView(this);
        statusView.setPadding(dp(6), 0, 0, 0);
        statusView.setText("--%");
        statusView.setContentDescription("电量未知");
        statusView.setTextColor(palFg());
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATUS_SP);
        bar.addView(statusView);

        clockView = new TextView(this);
        clockView.setTextColor(palFg());
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATUS_SP);
        clockView.setPadding(dp(6), 0, 0, 0);
        clockView.setContentDescription("当前时间");
        bar.addView(clockView);

        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        separator = new View(this);
        separator.setBackgroundColor(palFg());
        column.addView(separator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 书架顶部的分组按钮条（横排，一溜能摸到）
        tabsBox = new LinearLayout(this);
        tabsBox.setOrientation(LinearLayout.HORIZONTAL);
        tabsBox.setPadding(dp(2), dp(4), dp(2), dp(4));
        tabsBox.setVisibility(View.GONE);
        column.addView(tabsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(palBg());

        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rowsBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 详情页的小封面：浮在焦点行这一头，不占正文宽度，读屏不会读到它
        scrollWrap = new FrameLayout(this);
        scrollWrap.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        coverMini = new ImageView(this);
        coverMini.setVisibility(View.GONE);
        coverMini.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        coverMini.setContentDescription(null);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(44), dp(58));
        clp.gravity = Gravity.TOP | Gravity.RIGHT;
        clp.rightMargin = dp(4);
        scrollWrap.addView(coverMini, clp);
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int sx, int sy, int ox, int oy) { placeCover(); }
        });

        column.addView(scrollWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 浮层菜单：整屏半透明遮罩 + 里面一张卡片列表，盖在原页上头；关掉就把这一层藏起来，原页没动过
        FrameLayout rootWrap = new FrameLayout(this);
        rootWrap.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        menuOverlay = new FrameLayout(this);
        menuOverlay.setVisibility(View.GONE);
        menuOverlay.setBackgroundColor(0xF0000000);
        menuScroll = new ScrollView(this);
        menuScroll.setVerticalScrollBarEnabled(false);
        menuBox = new LinearLayout(this);
        menuBox.setOrientation(LinearLayout.VERTICAL);
        menuBox.setPadding(dp(4), dp(4), dp(4), dp(6));
        menuScroll.addView(menuBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(10);
        menuOverlay.addView(menuScroll, mlp);
        rootWrap.addView(menuOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        footerView = new TextView(this);
        footerView.setTextColor(palFg());
        footerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, FOOT_SP);
        footerView.setPadding(dp(6), dp(4), dp(6), dp(6));
        footerView.setVisibility(View.GONE);
        column.addView(footerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(rootWrap);
    }

    // ---------------------------------------------------------------- 浮层菜单
    private FrameLayout menuOverlay;
    private ScrollView menuScroll;
    private LinearLayout menuBox;

    /** 把菜单摊在浮层上：卡片里一列行，读屏只认这一列，原页在下面不动 */
    private void renderMenuOverlay() {
        if (menuScreen == null) { menuOn = false; menuOverlay.setVisibility(View.GONE); render(false); return; }
        menuOverlay.setVisibility(View.VISIBLE);
        menuBox.removeAllViews();
        TextView focusView = null;
        for (int i = 0; i < menuScreen.items.size(); i++) {
            final int idx = i;
            String label = menuScreen.items.get(i).label;
            TextView row = makeRow(label, i == menuScreen.cur,
                    label + (i == menuScreen.cur ? "，当前" : ""));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx != menuScreen.cur) {
                        menuScreen.cur = idx;
                        renderMenuOverlay();
                        speak(menuScreen.items.get(idx).label);
                    } else {
                        activate();
                    }
                }
            });
            bindA11yFocus(row, i);
            menuBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
            if (i == menuScreen.cur) focusView = row;
        }
        menuOverlay.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        if (menuScroll != null) {
            final int cur0 = menuScreen.cur;
            menuScroll.post(new Runnable() { @Override public void run() {
                menuScroll.scrollTo(0, Math.max(0, cur0 * dp(ROW_DP)));
            } });
        }
        focusRow(focusView);
    }

    /** 打开浮层菜单：names 与 keys 一一对应 */
    private void openFloatMenu(String title, String[] names, String[] keys) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            items.add(new Item(names[i], i < keys.length ? keys[i] : ""));
        }
        menuScreen = new Screen(title, items);
        menuScreen.cur = 0;
        menuOn = true;
        if (scrollWrap != null) {
            scrollWrap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            scrollWrap.setVisibility(View.INVISIBLE);
        }
        if (tabsBox != null) tabsBox.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (footerView != null) footerView.setVisibility(View.GONE);
        render(false);
        speak(title + "，共" + names.length + "项，返回键退出");
    }

    /** 只把浮层收掉，不重建页面、不播报 */
    private void hideMenuOverlay() {
        menuOn = false;
        menuScreen = null;
        if (menuOverlay != null) menuOverlay.setVisibility(View.GONE);
        if (scrollWrap != null) {
            scrollWrap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            scrollWrap.setVisibility(View.VISIBLE);
        }
        if (tabsBox != null) tabsBox.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    /** 关掉浮层，回到原页原位 */
    private void closeFloatMenu() {
        menuOn = false;
        menuScreen = null;
        if (menuOverlay != null) menuOverlay.setVisibility(View.GONE);
        if (scrollWrap != null) {
            scrollWrap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            scrollWrap.setVisibility(View.VISIBLE);
        }
        if (tabsBox != null) tabsBox.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        Screen sc = top();
        render(false);      // 页面重建，但不在这里念标题：标题与位置下面一次说清，免得一句念两遍
        if (screenReaderOn) notifyPageChanged(sc);
        else speak(sc.title + "，第" + (sc.cur + 1) + "项，共" + sc.items.size() + "项");
    }

    private Screen top() { return shell().top(); }




    /** 网格格子被读屏聚焦：红格跟过去；也记日志，方便下次取证 */

    /** 读屏焦点进入某格：红格跟过去，只改底色，不重建视图（否则焦点会丢） */



    // ================================================================ 屏幕与目录工具
    /** 屏幕栈已搬到 shell/ScreenStack，主类只留转发与接线（外壳第一刀） */
    private ScreenStack screenStack;
    private ScreenStack shell() {
        if (screenStack == null) screenStack = new ScreenStack(new ScreenStack.Host() {
            @Override public java.util.List<Screen> stack() { return stack; }
            @Override public void render(boolean reset) { MainActivity.this.render(reset); }
            @Override public void speak(String m) { MainActivity.this.speak(m); }
            @Override public void log(String tag, String m) { devLogLine(tag, m); }
            @Override public void cancelGestures() { if (gestures != null) gestures.cancelPending(); }
            @Override public int overlay() { return dialogOn ? 1 : (inputOn ? 2 : (menuOn ? 3 : 0)); }
            @Override public void closeOverlay(int kind) {
                if (kind == 1) { closeDialog(); speak("已取消"); }
                else if (kind == 2) { closeInput(); speak("已取消"); }
                else if (kind == 3) { closeFileMenu(); }
            }
            @Override public void onLeaveReader() {
                Screen t = top();
                if (t != null) savePos(t.cur);
                autoPlay = false;
                if (tts != null) tts.stop();
            }
            @Override public Screen rebuildFor(String key, int cur) {
                if ("settings".equals(key)) return settingsScreen();
                if ("appearance".equals(key)) return appearanceScreen();
                if ("text".equals(key)) return textScreen(cur);
                if ("readcolor".equals(key)) return readColorScreen(cur);
                if ("hlcolor".equals(key)) return hlColorScreen(cur);
                if ("hlstyle".equals(key)) return hlStyleScreen(cur);
                if ("fb".equals(key)) return feedbackScreen(cur);
                return null;
            }
            @Override public void exitApp() { finish(); }
            @Override public void reloadExplore() { nb.loadBookSources(); nb.nbExplore(); }
        });
        return screenStack;
    }

    private void pushScreen(Screen sc) { shell().push(sc); }

    /** 边搜边出：往列表尾补一行，不重建整页，列表位置就不会乱跑 */
    /** 只改某一行文字（合并同书多源时），重绑焦点，滚动位置不动 */
    private void updateSearchRow(int idx, String label) {
        Screen sc = top();
        if (sc == null || idx < 0 || idx >= sc.items.size() || rowsBox == null) return;
        sc.items.set(idx, new Item(label, sc.items.get(idx).key));
        int at = idx - rowOffset + rowLead;
        if (at >= 0 && at < rowsBox.getChildCount()) {
            View v = rowsBox.getChildAt(at);
            if (v instanceof TextView) {
                ((TextView) v).setText(label);
                bindA11yFocus((TextView) v, idx);
            }
        }
    }

    /** 拿掉列表里 key 匹配的那一行（分类页翻页时用），连同它的 View 一起删，行号重绑 */
    private void dropTailRow(String key) {
        Screen sc = top();
        if (sc == null || rowsBox == null) return;
        int at = -1;
        for (int i = sc.items.size() - 1; i >= 0; i--) {
            Item it = sc.items.get(i);
            if (it != null && key.equals(it.key)) { at = i; break; }
        }
        if (at < 0) return;
        sc.items.remove(at);
        int vi = at - rowOffset;
        if (vi >= 0 && vi < rowsBox.getChildCount()) rowsBox.removeViewAt(vi);
        for (int i = 0; i < rowsBox.getChildCount(); i++) {
            View cv = rowsBox.getChildAt(i);
            if (cv instanceof TextView) bindA11yFocus((TextView) cv, i + rowOffset);
        }
        if (sc.cur > sc.items.size() - 1) sc.cur = Math.max(0, sc.items.size() - 1);
    }

    /**
     * 搜索页插一行：只插，不重建。
     * 命中的书一旦上屏位置就钉死，后面又搜到一本只往后追加，读屏不会把前面重念一遍。
     * 插入点之后的行号确实变了，那几行重新写序号与无障碍描述；前面的行一个字都不动。
     */
    private void insertSearchRowView(Screen target, int at, int placeholderAt) {
        if (target == null || target.reader) return;
        if (top() != target || menuOn || dialogOn || inputOn || rowsBox == null) return;   // 不在这页，回来 render 自然出
        // 「正在搜…」那条占位的 View 先拿掉。at 是 items 里删过占位之后的下标，
        // 删完 View 后两边下标就对齐了，不要再跟着减一次
        if (placeholderAt >= 0 && placeholderAt < rowsBox.getChildCount()) {
            rowsBox.removeViewAt(placeholderAt);
        }
        if (at < 0 || at >= target.items.size()) return;
        int vi = at - rowOffset;
        if (vi < 0 || vi > rowsBox.getChildCount()) return;
        Item it = target.items.get(at);
        final TextView row = makeRow(it.label, false,
                com.mangjie.reader.reader.PageText.row(it.label, at, target.items.size()));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Screen s0 = top();
                int idx = rowsBox.indexOfChild(v) + rowOffset;
                if (idx < 0 || idx >= s0.items.size()) return;
                s0.cur = idx;
                highlightSelection();
                activate();
            }
        });
        row.setLongClickable(true);
        rowsBox.addView(row, vi);
        for (int i = vi + 1; i < rowsBox.getChildCount(); i++) {
            View cv = rowsBox.getChildAt(i);
            if (!(cv instanceof TextView)) continue;
            int idx = i + rowOffset;
            if (idx >= target.items.size()) break;
            Item ii = target.items.get(idx);
            ((TextView) cv).setText(com.mangjie.reader.reader.PageText.row(ii.label, idx, target.items.size()));
            bindA11yFocus((TextView) cv, idx);
        }
        bindA11yFocus(row, at);
    }

    private void appendSearchRow(String label, String key, Screen target) {
        // 认发起搜索的那一页，不是 top()：人可能已经点进某本书的详情页，
        // 用 top() 的话这些结果全被丢掉，退回来就只剩最开头那几本
        Screen sc = target != null ? target : top();
        if (sc == null || sc.reader) return;
        // 顶上那条「正在搜…」占位：第一次出结果时连 items 带 View 一起拿掉
        // （占位不一定在第 0 行，搜索页第一行现在是精准搜索开关，所以按 key 找）
        int secAt = -1;
        for (int i = 0; i < sc.items.size() && i < 4; i++) {
            if ("sec".equals(sc.items.get(i).key)) { secAt = i; break; }
        }
        // 只有这一页还在最上面、且没被菜单/对话框/输入框挡着，才动界面；
        // 否则只把条目记进列表，人回来时 render 自然就出来了
        boolean onTop = top() == sc && !menuOn && !dialogOn && !inputOn && rowsBox != null;
        if (secAt >= 0) {
            sc.items.remove(secAt);
            if (onTop) {
                if (rowsBox.getChildCount() > secAt) rowsBox.removeViewAt(secAt);
                // 拿走占位后行号整体前移，重绑一遍，读屏念的序号才对
                for (int i = 0; i < rowsBox.getChildCount(); i++) {
                    View cv = rowsBox.getChildAt(i);
                    if (cv instanceof TextView) bindA11yFocus((TextView) cv, i + rowOffset);
                }
            }
        }
        sc.items.add(new Item(label, key));
        if (!onTop) return;                    // 记下了，等人回到这一页再显示
        final TextView row = makeRow(label, false,
                com.mangjie.reader.reader.PageText.row(label, sc.items.size() - 1, sc.items.size()));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Screen s0 = top();
                int idx = rowsBox.indexOfChild(v) + rowOffset;
                if (idx < 0 || idx >= s0.items.size()) return;
                s0.cur = idx;
                highlightSelection();
                activate();
            }
        });
        row.setLongClickable(true);
        rowsBox.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
        bindA11yFocus(row, rowsBox.getChildCount() - 1 + rowOffset);
    }

    private File featDir(String sub) {
        File d = new File(DATA_DIR, sub);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private String stamp() { return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date()); }

    // ---------------- 定时闹钟 / 纪念日 / 倒计时



    // ---------------- 日程（存在 prefs：date|time|remind|title，一行一条）










    // ---------------- 记事本






    private double parseD(String v) {
        try { return Double.parseDouble(v); } catch (Exception e) { return 0; }
    }






    // ================================================================ 网络听书：书源规则引擎
    private static final String BOOKSRC_DIR = DATA_DIR + "/书源";
    /** 整章连播：念完这一章自动进下一章 */


    // ---------------- 本地大书：按章 / 按段装载 ----------------
    /** 纯文本超过 1MB 才值得建章表：再小的书整本读更快；没有上限，多大的书都能读到结尾 */
    private static final long PLAIN_INDEX_MIN = 1024L * 1024;

    private boolean localMode = false;                                   // 当前这本书是按章读的
    private File localFile = null;
    private com.mangjie.reader.reader.LocalText.Index localIdx = null;   // txt 章表 / 段表
    private com.mangjie.reader.reader.EpubBooks.Index epubIdx = null;    // epub 章表
    private int chapterNo = 0;                                           // 当前章号（0 起）

    private String readTextFile(File f) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            r.close();
            return sb.toString();
        } catch (Exception e) { return null; }
    }



    // ---- 书源管理：导入、导出、删除








    /** 逐条过：搜索能不能出结果、第一本书能不能拿目录、第一章能不能拿正文 */



    // ---- 订阅源管理（与书源管理平级）
    private static final String SUBSRC_DIR = DATA_DIR + "/订阅源";


    // 开源阅读的订阅源字段名：sourceUrl，其次兼容旧写法











    /** 全 App 只有一个菜单入口：手势「先左再右」和物理菜单键都走这里，按当前页认菜单 */
    private void openPageMenu() {
        Screen sc = top();
        if (sc == null) return;
        if (sc.reader) { openReaderMenu(); return; }
        String k = sc.key == null ? "" : sc.key;
        if ("bsrc".equals(k)) { openBookSourceMenu(); return; }
        // 发现·书源页：按 Legado 的做法，页面级只留筛选，源上的活交给行菜单
        if ("bsrcExplore".equals(k)) { openExplorePageMenu(); return; }
        if ("bsrcDetail".equals(k)) { openBsrcDetailMenu(); return; }
        if ("detail".equals(k)) { openDetailMenu(); return; }
        if (k.startsWith("toc")) { openTocMenu(); return; }
        if ("nbSearch".equals(k)) { openSearchMenu(); return; }
        if ("subsrc".equals(k) || "sub".equals(k) || k.startsWith("subDetail")) { openSubMenu(); return; }
        if ("subList".equals(k)) { openSubListMenu(); return; }
        if ("audio".equals(k) || sc.player) { openAudioMenu(); return; }
        if (isShelfPage()) { openShelfMenu(); return; }
        if (sc.fileMode) { openFileMenu(); return; }
        List<Item> common = commonPageMenu(k);
        if (common != null) { showFloat(sc.title, common); return; }
        speak("当前页面没有功能菜单");
        toast("当前页面没有功能菜单");
    }

    /** 设置类页面的菜单：同一页能干的活都收进菜单，页面上就不用堆操作行 */
    private List<Item> commonPageMenu(String k) {
        List<Item> items = new ArrayList<Item>();
        if ("settings".equals(k)) {
            items.add(new Item("外观", "set:appearance"));
            items.add(new Item("阅读", "set:read"));
            items.add(new Item("书源管理", "go:booksrc"));
            items.add(new Item("订阅源管理", "go:subsrc"));
            items.add(new Item("其他", "set:data"));
            items.add(new Item("手势", "set:gesture"));
            items.add(new Item("操作反馈", "go:fb"));
            items.add(new Item("软件升级", "go:update"));
            items.add(new Item("更新日志", "go:changelog"));
            items.add(new Item("系统信息", "go:sysinfo"));
            return items;
        }
        if ("setData".equals(k)) {
            items.add(new Item("备份与恢复", "go:backup"));
            items.add(new Item("清空订阅缓存", "go:subcacheclear"));
            items.add(new Item("改搜索线程数", "src:threads"));
            items.add(new Item("每天自动检查更新：" + (prefs.getBoolean("updAuto", true) ? "开" : "关"), "nb:updauto"));
            items.add(new Item("还原（只动外观三项）", "go:factory"));
            items.add(new Item("软件升级", "go:update"));
            return items;
        }
        if ("mydict".equals(k)) {
            items.add(new Item("查词", "mydict:look"));
            items.add(new Item("导入词库文件", "mydict:imp"));
            items.add(new Item("重新载入词库", "mydict:reload"));
            items.add(new Item("帮助：词典怎么放", "help:sec:5"));
            return items;
        }
        if ("backup".equals(k) || "backupDav".equals(k)) {
            items.add(new Item("服务器设置", "backup:dav"));
            items.add(new Item("导出备份到存储卡", "backup:export"));
            items.add(new Item("从存储卡恢复", "backup:restore"));
            return items;
        }
        if ("help".equals(k) || "helpSec".equals(k)) {
            items.add(new Item("手势与调值规则", "go:gesturehelp"));
            items.add(new Item("界面说明", "go:uihelp"));
            items.add(new Item("查看无障碍日志", "shelf:log"));
            items.add(new Item("返回设置", "nav:back"));
            return items;
        }
        if ("tocRule".equals(k) || "tocRuleList".equals(k)) {
            items.add(new Item("看内置目录规则", "tocrule:list"));
            items.add(new Item("重新载入规则", "tocrule:reload"));
            return items;
        }
        if ("pur".equals(k) || "purBuiltin".equals(k)) {
            items.add(new Item("看内置净化规则", "pur:builtin"));
            items.add(new Item("重新载入规则", "purify:reload"));
            return items;
        }
        if ("excerpt".equals(k)) {
            items.add(new Item("导出为 Markdown", "ex:export"));
            items.add(new Item("清空摘抄", "ex:clear"));
            return items;
        }
        if ("record".equals(k)) {
            items.add(new Item("书签总览", "rec:bms"));
            items.add(new Item("清空统计", "stats:clear"));
            return items;
        }
        return null;
    }

    /** 书籍详情菜单 */
    private void openDetailMenu() {
        String bk = nb.nbBookUrl == null ? "" : nb.nbBookUrl;
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("打开目录", "nb:chapterlist"));
        items.add(new Item(nb.nbIsAudioBook ? "从头开始播放" : "从头朗读这本", "nb:readall:0"));
        items.add(new Item("换源", "nb:switch"));
        items.add(new Item("打开书籍详情", "nb:detail"));
        if (nbInFav(bk)) items.add(new Item("移出书架", "bkn:unfav:" + bk));
        else items.add(new Item("加入书架", "nb:favadd"));
        items.add(new Item("置顶到书架最前", "bkn:top:" + bk));
        items.add(new Item("清除本书缓存", "nb:cacheclear"));
        items.add(new Item("缓存全部章节", "nb:cacheall"));
        showFloat("书籍菜单", items);
    }

    /** 目录页菜单（换源、缓存、倒序、过滤词都从这里的页面顶部搬过来） */
    private void openTocMenu() {
        List<Item> items = new ArrayList<Item>();
        if (nbInFav(nb.nbBookUrl)) items.add(new Item("移出书架", "nb:favtoggle"));
        else items.add(new Item("加入书架", "nb:favtoggle"));
        items.add(new Item("书籍详情", "nb:detail"));
        items.add(new Item("换源", "nb:switch"));
        items.add(new Item("缓存整本到本地", "nb:cacheall"));
        items.add(new Item("目录倒序：" + (prefs.getBoolean("tocRevAll", false) ? "开" : "关"), "nb:tocrev"));
        items.add(new Item("目录过滤词", "nb:tocfilter"));
        if (nb.nbChapters.size() > 1)
            items.add(new Item(nb.nbIsAudioBook ? "从头开始播放" : "从头开始朗读", "nb:readall:0"));
        items.add(new Item("清除本书缓存", "nb:cacheclear"));
        items.add(new Item("查看听书日志", "nb:log"));
        showFloat("目录菜单", items);
    }

    /** 换完搜索范围：浮层收掉，回到搜索页按原关键词重搜；没有关键词就要用户输入 */
    private void afterScopeChange(String label) {
        hideMenuOverlay();
        speak("搜索范围：" + label);
        String lastKey = nb.nbLastKey == null ? "" : nb.nbLastKey.trim();
        Screen tp = top();
        if (!lastKey.isEmpty() && tp != null && "nbSearch".equals(tp.key)) {
            nb.nbSearch(lastKey);
            return;
        }
        startSearchInput();
    }

    /** 搜索结果页菜单：照 Legado，精准搜索与搜索范围都在这里，不再单独做范围页 */
    private void openSearchMenu() {
        nb.loadBookSources();
        List<Item> items = new ArrayList<Item>();
        String cur = nb.searchScope();
        items.add(new Item("精准搜索：" + (nb.precisionSearch() ? "开（只留书名或作者含关键字的）" : "关") + "，点一下切换", "nb:prec"));
        items.add(new Item("换关键词重新搜", "nb:searchagain"));
        int hN = nb.recentSearchCount();
        if (hN > 0) items.add(new Item("清空最近搜索（" + hN + " 条）", "nb:histclr"));
        if (nb.searching()) items.add(new Item("停止搜索（已经出来的都留着）", "nb:stopsearch"));
        items.add(new Item("全部书源" + (cur.isEmpty() ? "　当前" : ""), "nb:ssall"));
        List<String> gs = nb.sourceGroupNames();
        for (int i = 0; i < gs.size(); i++) {
            int n = nb.sourceGroupIdx(gs.get(i)).size();
            boolean on = cur.equals("g:" + gs.get(i));
            items.add(new Item("分组：" + gs.get(i) + "（" + n + " 个源）" + (on ? "　当前" : ""), "nb:ssg:" + i));
        }
        items.add(new Item("只用一个来源筛选结果", "nb:fsrc"));
        showFloat("搜索菜单", items);
    }

    /** 书源详情菜单 */
    private void openBsrcDetailMenu() {
        int i = bsrcDetailIdx;
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("只用这个源搜书", "nb:ssone:" + i));
        items.add(new Item("校验这个书源（搜索/目录/正文）", "bsrc:test:" + i));
        items.add(new Item("查看这个源的 JSON 原文", "bsrc:json:" + i));
        items.add(new Item("看这个源的调试日志（最近 40 条）", "bsrc:log:" + i));
        items.add(new Item("启用或关闭这个源", "bsrc:toggle:" + i));
        items.add(new Item("改这个源的分组", "bsrc:grpd:" + i));
        items.add(new Item("复制这个书源（规则照抄一份）", "bsrc:copy:" + i));
        items.add(new Item("只导出这一个源", "bsrc:expone:" + i));
        items.add(new Item("删除这个书源", "bsrc:del:" + i));
        items.add(new Item("返回书源列表", "nb:cat"));
        showFloat("书源菜单", items);
    }

    /** 订阅列表菜单（分组切换、全部最新、收藏的文章都从页面顶部搬过来） */
    private void openSubListMenu() {
        String gf = ops.subGroupFilter();
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("换订阅分组（现在：" + (gf.isEmpty() ? "全部" : gf) + "）", "nb:subgroup"));
        items.add(new Item("看全部订阅的最新", "nb:suball"));
        items.add(new Item("收藏的文章（" + ops.subFavCount() + " 篇）", "nb:subfavs"));
        items.add(new Item("订阅源管理", "go:subsrc"));
        showFloat("订阅菜单", items);
    }

    /** 订阅源管理菜单 */
    private void openSubMenu() {
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("重新载入订阅源", "sub:reload"));
        items.add(new Item("导入订阅源（选文件）", "sub:impfile"));
        items.add(new Item("导入订阅源（填网址）", "sub:impurl"));
        items.add(new Item("导出全部订阅源", "sub:export"));
        items.add(new Item("全部启用", "sub:allon"));
        items.add(new Item("全部关闭", "sub:alloff"));
        showFloat("订阅源菜单", items);
    }

    /** 音频播放页菜单 */
    private void openAudioMenu() {
        List<Item> items = new ArrayList<Item>();
        items.add(new Item(audio().isPlaying() ? "暂停" : "播放", "ab:toggle"));
        items.add(new Item("上一章", "ab:prev"));
        items.add(new Item("下一章", "ab:next"));
        items.add(new Item("退 15 秒", "ab:r15"));
        items.add(new Item("进 15 秒", "ab:f15"));
        items.add(new Item("语速：" + aRate + " 倍", "ab:rate"));
        items.add(new Item("报时间和进度", "ab:tell"));
        items.add(new Item("打开章节目录", "ab:toc"));
        items.add(new Item(nb.nbIsFav(nb.nbBookUrl) ? "移出书架" : "加入书架", "nb:favtoggle"));
        showFloat("播放器菜单", items);
    }

    /** 把一批项做成悬浮菜单，省得每处都写一遍取名字取键 */
    private void showFloat(String title, List<Item> items) {
        String[] names = new String[items.size()];
        String[] keys = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            names[i] = items.get(i).label;
            keys[i] = items.get(i).key == null ? "" : items.get(i).key;
        }
        openFloatMenu(title, names, keys);
    }

    /** 书源菜单：单指先左滑再右滑（LR）呼出，替代原来在列表末尾堆一长串操作 */
    /** 书源菜单屏搬到了 BookSourceOps，这里只留入口，老调用点不用改 */
    private Screen bookSourceMenuScreen() { return ops.bsrcMenuScreen(); }

    /** 发现页菜单：对齐 Legado 的 main_explore（只有分组），另加源名筛选与重算 */
    private void openExplorePageMenu() {
        List<Item> items = new ArrayList<Item>();
        String g = prefs.getString("exGroup", "");
        String kw = prefs.getString("exKey", "");
        items.add(new Item("按书源分组：" + (g.isEmpty() ? "全部" : g), "bsrc:expgroup"));
        items.add(new Item("按源名筛选：" + (kw.isEmpty() ? "全部" : kw) + "（点一下填）", "bsrc:expkey"));
        items.add(new Item("搜索书源：输名字、域名或分组", "bsrc:exfind"));
        if (!g.isEmpty() || !kw.isEmpty()) items.add(new Item("清除筛选，显示全部", "bsrc:expclr"));
        items.add(new Item("重新载入书源（重算发现分类）", "bsrc:exreload"));
        showFloat("发现菜单", items);
    }

    private Screen exploreGroupPickScreen() {
        List<Item> items = new ArrayList<>();
        String cur = prefs.getString("exGroup", "");
        items.add(new Item((cur.isEmpty() ? "· " : "") + "全部分组", "exgrp:"));
        List<String> names = nb.exploreGroupNames();
        for (int i = 0; i < names.size(); i++) {
            String nm = names.get(i);
            items.add(new Item((cur.equals(nm) ? "· " : "") + nm, "exgrp:" + nm));
        }
        if (names.isEmpty()) items.add(new Item("（有发现页的源都没写分组）"));
        items.add(new Item("返回发现", "nav:back"));
        return new Screen("按书源分组筛选", items).k("exGroupPick");
    }

    /**
     * 发现页「搜索书源」：在全库书源里按名字、域名、分组、备注找源，点一下直接进。
     * 和「按源名筛选」的区别：筛选只过滤发现页上那批源（还得有 exploreUrl 才算数），
     * 这个是全库搜，每条带域名，同名源（比如三条「轻之文库」）能一眼分开；
     * 没有发现页的源拿它去搜书，不至于搜到了却点不动。
     */
    private Screen exploreFindScreen(String kw) {
        nb.loadBookSources();
        String k = kw == null ? "" : kw.trim().toLowerCase();
        List<Object[]> hits = new ArrayList<Object[]>();
        for (int i = 0; i < nb.nbSources.size(); i++) {
            JSONObject o = nb.nbSources.get(i);
            String nm = o.optString("bookSourceName", "").trim();
            String url = o.optString("bookSourceUrl", "").trim();
            String grp = o.optString("bookSourceGroup", "").trim();
            String cm = o.optString("bookSourceComment", "").trim();
            String hp = hostOf(url);
            if (hp.isEmpty()) hp = url.length() > 40 ? url.substring(0, 40) : url;
            String nmL = nm.toLowerCase();
            int sc = 0;
            if (nmL.equals(k)) sc = 500;
            else if (nmL.startsWith(k)) sc = 400;
            else if (nmL.contains(k)) sc = 300;
            else if (hp.toLowerCase().contains(k)) sc = 200;
            else if (grp.toLowerCase().contains(k)) sc = 120;
            else if (url.toLowerCase().contains(k)) sc = 90;
            else if (cm.toLowerCase().contains(k)) sc = 40;
            if (sc <= 0) continue;
            hits.add(new Object[]{Integer.valueOf(sc), Integer.valueOf(i), nm, hp, grp, o});
        }
        // 分数高的在前；同分按名字，保证同一个词每次排出来顺序一样
        java.util.Collections.sort(hits, new java.util.Comparator<Object[]>() {
            @Override public int compare(Object[] a, Object[] b) {
                int x = ((Integer) a[0]).intValue();
                int y = ((Integer) b[0]).intValue();
                if (x != y) return y - x;
                return String.valueOf(a[2]).compareTo(String.valueOf(b[2]));
            }
        });
        List<Item> items = new ArrayList<Item>();
        int shown = 0;
        for (int j = 0; j < hits.size(); j++) {
            if (shown >= 80) break;
            Object[] h = hits.get(j);
            int si = ((Integer) h[1]).intValue();
            JSONObject o = (JSONObject) h[5];
            String nm = String.valueOf(h[2]);
            String hp = String.valueOf(h[3]);
            String grp = String.valueOf(h[4]);
            StringBuilder sb = new StringBuilder(nm.isEmpty() ? ("书源 " + si) : nm);
            if (!hp.isEmpty()) sb.append("　").append(hp);
            if (!grp.isEmpty()) sb.append("　").append(grp);
            if (!o.optBoolean("enabled", true)) sb.append("　已关闭");
            boolean ex = nb.exploreOn(o) && nb.hasExploreUrl(o);
            sb.append(ex ? "　有发现页" : "　没有发现页");
            items.add(new Item(sb.toString(), ex ? ("nb:esrc:" + si) : ("nb:ssone:" + si)));
            shown++;
        }
        if (items.isEmpty()) {
            items.add(new Item("（书名、网址、分组里都没有「" + kw + "」）"));
            speak("没找到名字里含「" + kw + "」的书源");
        } else {
            if (hits.size() > shown) items.add(new Item("（还有 " + (hits.size() - shown) + " 个没列出，词再具体一点）"));
            speak("找到 " + shown + " 个书源");
        }
        return new Screen("搜索书源 · " + kw, items).k("bsrcFind");
    }

    /** 发现页里某一行的行菜单：对齐 Legado 的 explore_item（编辑、置顶、搜索、刷新、删除） */
    private void openExploreRowMenu(int idx) {
        Screen s0 = top();
        if (s0 == null || idx < 0 || idx >= s0.items.size()) return;
        String key = s0.items.get(idx).key;
        if (key == null || !key.startsWith("nb:esrc:")) return;
        int si = rules.num(key, 8);
        nb.loadBookSources();
        if (si < 0 || si >= nb.nbSources.size()) return;
        JSONObject o = nb.nbSources.get(si);
        String nm = o.optString("bookSourceName", "未命名");
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("打开发现分类", "nb:esrc:" + si));
        items.add(new Item("编辑（填搜索和正文规则）", "bsrc:edit:" + si));
        items.add(new Item("置顶（按权重排到最前）", "bsrc:srctop:" + si));
        items.add(new Item("只用这个源搜书", "nb:ssone:" + si));
        items.add(new Item("刷新这个源的发现分类", "bsrc:exrefresh:" + si));
        items.add(new Item("删除这个书源", "bsrc:del:" + si));
        showFloat(nm, items);
    }

    private void openBookSourceMenu() {
        nb.loadBookSources();
        Screen ms = bookSourceMenuScreen();
        String[] names = new String[ms.items.size()];
        String[] keys = new String[ms.items.size()];
        for (int i = 0; i < ms.items.size(); i++) {
            names[i] = ms.items.get(i).label;
            keys[i] = ms.items.get(i).key == null ? "" : ms.items.get(i).key;
        }
        openFloatMenu("书源菜单", names, keys);
    }

    /** 当前筛选出来、要参与批量校验的那些源的原索引 */
    private List<Integer> bsrcKeepIdx() { return ops.bsrcKeepIdx(); }

    /** 筛选页：类型和状态各一行，点一下往下换一档 */
    private Screen bsrcFilterScreen() { return ops.bsrcFilterScreen(); }

    private Screen bookSourceScreen() {
        nb.loadBookSources();
        List<Item> items = new ArrayList<>();
        if (nb.nbSources.isEmpty()) {
            items.add(new Item("（还没有书源）"));
            items.add(new Item("导入书源", "nb:impfile"));
            items.add(new Item("导入书源", "nb:impurl"));
            items.add(new Item("把开源阅读 Legado 导出的书源 JSON 放进书源目录"));
            items.add(new Item("书源目录：应用私有目录，导入走上面的入口"));
            items.add(new Item("重新载入书源", "nb:reload"));
            return new Screen("书源管理", items).k("bsrc");
        }
        int ft = prefs.getInt("srcFilterType", -1);
        int fo = prefs.getInt("srcFilterOn", -1);
        int srt = prefs.getInt("srcSort", 0);
        final List<Integer> keep = new ArrayList<Integer>();
        for (int i = 0; i < nb.nbSources.size(); i++) {
            JSONObject o = nb.nbSources.get(i);
            if (ft >= 0 && nb.srcType(o) != ft) continue;
            if (fo == 1 && !nb.srcEnabled(o)) continue;
            if (fo == 0 && nb.srcEnabled(o)) continue;
            keep.add(i);
        }
        // 筛选条件会一直留着。系统书源整批换过之后，可能一条都筛不出来，
        // 页面上只剩「（按当前筛选一个都没有）」，看着像"服务器书源没下下来"。
        // 这里兜一次：明明有源却被筛成 0，就自动清空筛选，并把条数说清楚。
        if (keep.isEmpty() && !nb.nbSources.isEmpty() && (ft >= 0 || fo >= 0)) {
            prefs.edit().putInt("srcFilterType", -1).putInt("srcFilterOn", -1).apply();
            ft = -1; fo = -1;
            devLogLine("书源", "筛选把 " + nb.nbSources.size() + " 个源筛成 0，已自动清空筛选");
            for (int i = 0; i < nb.nbSources.size(); i++) keep.add(i);
        }
        if (srt == 1) {
            java.util.Collections.sort(keep, new java.util.Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) {
                    return nb.nbSources.get(a).optString("bookSourceName", "")
                            .compareTo(nb.nbSources.get(b).optString("bookSourceName", ""));
                }
            });
        } else if (srt == 2) {
            java.util.Collections.sort(keep, new java.util.Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) {
                    return nb.nbSources.get(b).optInt("weight", 0) - nb.nbSources.get(a).optInt("weight", 0);
                }
            });
        }
        for (int k = 0; k < keep.size(); k++) {
            int i = keep.get(k);
            JSONObject o = nb.nbSources.get(i);
            String name = o.optString("bookSourceName", "未命名");
            String ty = com.mangjie.reader.net.NetBookController.srcTypeName(nb.srcType(o));
            items.add(new Item(name + "（" + (nb.srcEnabled(o) ? "开" : "关") + "／" + ty + "）", "bsrc:detail:" + i));
        }
        if (keep.isEmpty()) items.add(new Item("（按当前筛选一个都没有）"));
        return new Screen("书源管理　" + keep.size() + "/" + nb.nbSources.size() + " 个", items).k("bsrc");
    }
    /** 在一个元素上执行取值规则（含 ##正则##替换 处理） */

    private String groupOf(java.util.regex.Matcher m, String rule) {
        if (rule == null || rule.isEmpty()) return null;
        String r = rule.trim();
        if (r.startsWith("$")) {
            try { return m.group(Integer.parseInt(r.substring(1))); } catch (Exception e) { return null; }
        }
        // 规则不是分组，返回整段匹配
        return m.group();
    }



    /** 规则求值：返回多个候选文本 */
    // ---- JS 规则（Rhino 1.8.1，与开源阅读同库）
    private final java.util.Map<String, String> ruleVars = new java.util.HashMap<>();
    private String jsBase = "";
    private String jsHtml = "";
    private int jsPage = 1;
    /** 规则里的 cache 对象：跨调用保留，源里 put 的下一段规则要 get 回来 */
    private JsCache jsCache = null;
    /** 当前书源自带的共享 JS（源里的 jsLib 字段）。Legado 每次 eval 都先跑它，我们以前完全没加载，
     *  于是 48 个靠库函数干活的源（洋柿子、拷贝轻说等）的发现/搜索/正文全哑 */
    private org.json.JSONObject jsSrcJson = null;
    private String jsLibCode = "";
    private org.mozilla.javascript.Script jsLibScript = null;
    private String jsLibScriptFor = "";

    /** 由 Host 在切到某个源时调用；空串表示这个源没有 jsLib */
    private void setJsLib(String lib) {
        jsLibCode = lib == null ? "" : lib.trim();
    }

    /** 由 Host 在切到某个源时调用，JS 里就能拿到 source.getKey() 这些 */
    private void setSource(org.json.JSONObject src) {
        jsSrcJson = src;
        if (http != null) {
            http.setSourceHeader(com.mangjie.reader.net.Http.parseHeader(
                    src == null ? "" : src.optString("header", "")));
        }
    }
    private String jsKey = "";

    // ---- Cookie 罐子：给 JS 里的 cookie.getKey 用（真正的存储在 net/Http 里）
    private java.util.Map<String, String> cookieFor(String url) { return http.cookieFor(url); }
    private void putCookie(String url, String name, String value) { http.putCookie(url, name, value); }
    private void removeCookie(String url, String name) { http.removeCookie(url, name); }

    private String requestUrl(String spec, String base) { return http.requestUrl(spec, base); }
    private String requestUrlT(String spec, String base, int timeoutMs) { return http.requestUrlT(spec, base, timeoutMs); }
    private String httpGetText(String url, int timeoutMs) throws Exception { return http.httpGetText(url, timeoutMs); }
    private String httpGetText(String url) { return http.httpGetText(url); }

    private static final class JsCookie {
        private final MainActivity a;
        JsCookie(MainActivity a) { this.a = a; }

        public String getAll(String url) {
            java.util.Map<String, String> jar = a.cookieFor(url);
            if (jar == null) return "";
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : jar.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }

        public String getKey(String url, String name) {
            java.util.Map<String, String> jar = a.cookieFor(url);
            if (jar == null) return "";
            String v = jar.get(name);
            return v == null ? "" : v;
        }

        public void put(String url, String name, String value) { a.putCookie(url, name, value); }

        public void remove(String url, String name) { a.removeCookie(url, name); }
    }

    /**
     * Legado 的 cache 桥：规则里 fqRouteRequest(result, source, java, cache, cookie) 这类
     * 会把 cache 当参数传下去、内部再 cache.get/put。以前根本没注入，直接
     * ReferenceError: "cache" is not defined，脚本整段不跑，URL 没被改写，
     * 请求打到占位域名（fq-reader.invalid）→ UnknownHostException → 目录 0 章。
     */
    private static final class JsCache {
        private final java.util.LinkedHashMap<String, Object> m =
                new java.util.LinkedHashMap<String, Object>(64, 0.75f, true) {
                    @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Object> e) {
                        return size() > 512;
                    }
                };
        public Object get(String k) { return m.get(k == null ? "" : k); }
        public Object get(String k, Object def) {
            Object v = m.get(k == null ? "" : k);
            return v == null ? def : v;
        }
        public void put(String k, Object v) { if (k != null) m.put(k, v); }
        public void delete(String k) { if (k != null) m.remove(k); }
        public boolean has(String k) { return k != null && m.containsKey(k); }
        public void clear() { m.clear(); }
        public int size() { return m.size(); }
        @Override public String toString() { return ""; }
    }


    private static final class JsJava {
        private final MainActivity a;
        private final com.mangjie.reader.net.TtfFonts.Host ttfHost;
        JsJava(MainActivity a) {
            this.a = a;
            this.ttfHost = new com.mangjie.reader.net.TtfFonts.Host() {
                @Override public byte[] getBytes(String url) { return a.http.httpGetBytes(url); }
                @Override public void log(String tag, String msg) { a.devLogLine(tag, msg); }
            };
        }

        /** 混淆字体解密：传 url / base64 / 本地路径，拿回字体解析器（同一来源缓存复用） */
        public com.mangjie.reader.net.QueryTTF queryTTF(String data) {
            return com.mangjie.reader.net.TtfFonts.from(ttfHost, data);
        }

        /** 三参版：能还原就还原，不删字 */
        public String replaceFont(String text, Object error, Object correct) {
            return replaceFont(text, error, correct, false);
        }

        /** 四参版：filter 为 true 时删掉错字体里没有轮廓的字符 */
        public String replaceFont(String text, Object error, Object correct, boolean filter) {
            com.mangjie.reader.net.QueryTTF e = asTtf(error);
            com.mangjie.reader.net.QueryTTF c = asTtf(correct);
            if (e == null || c == null) return text;
            return com.mangjie.reader.net.TtfFonts.replaceFont(text, e, c, filter);
        }

        private com.mangjie.reader.net.QueryTTF asTtf(Object o) {
            if (o instanceof com.mangjie.reader.net.QueryTTF) return (com.mangjie.reader.net.QueryTTF) o;
            if (o instanceof org.mozilla.javascript.NativeJavaObject) {
                Object u = ((org.mozilla.javascript.NativeJavaObject) o).unwrap();
                if (u instanceof com.mangjie.reader.net.QueryTTF) return (com.mangjie.reader.net.QueryTTF) u;
            }
            return null;
        }

        /**
         * java.lock(key, run, timeoutMs)：对齐 Legado / Max 的并发锁语义。
         * 番茄的 fqPolicyLock 优先用 host.lock，只有拿不到它才退到 System.properties.compute——
         * 那条路在 Rhino 里回调根本不会被调起，结果 fixed 成「额度锁等待失败」，正文一条也拿不到。
         */
        public void lock(String key, Object run, int timeoutMs) {
            if (!(run instanceof org.mozilla.javascript.Function)) return;
            org.mozilla.javascript.Function f = (org.mozilla.javascript.Function) run;
            org.mozilla.javascript.Context cx = a.jsCx;
            org.mozilla.javascript.Scriptable sc = a.jsScope;
            if (cx == null || sc == null) return;
            Object mutex = a.lockFor(key == null ? "" : key);
            synchronized (mutex) {
                try { f.call(cx, sc, sc, new Object[0]); }
                catch (Throwable t) { a.devLogLine("听书", "锁内执行失败：" + tail(t.getMessage())); }
            }
        }

        public String ajax(String url) {
            if (url == null) return "";
            String u = url.trim();
            if (u.startsWith("//")) u = "https:" + u;
            return a.httpGetText(u);
        }

        /**
         * java.ajax(url, opts)：第二参可能是超时（数字）也可能是逗号后面的 option JSON。
         * 以前一律丢掉了——源里写 java.ajax(url, 60000) 就等于没设超时，
         * 写 java.ajax(url + ',{"method":"POST"...}') 时选项也在 url 里，这里补上两种都认。
         */
        public String ajax(String url, String opts) {
            if (url == null) return "";
            String u = url.trim();
            if (opts == null) return a.requestUrl(u, a.jsBase);
            String o = opts.trim();
            if (o.isEmpty()) return a.requestUrl(u, a.jsBase);
            if (o.matches("\\d+")) {
                int t = 0;
                try { t = Integer.parseInt(o); } catch (Exception ignored) {}
                return a.requestUrlT(u, a.jsBase, t > 0 ? t : 0);
            }
            // 不是数字就当成 option 片段接到地址后面，由 UrlResolver 统一解析
            if (!u.contains(",{")) {
                u = u + (o.startsWith("{") ? "," + o : "," + o);
            }
            return a.requestUrl(u, a.jsBase);
        }

        /** 带超时的重载：源里 java.ajax(url, 60000) 会落到这里 */
        public String ajax(String url, int timeoutMs) {
            if (url == null) return "";
            String u = url.trim();
            if (u.startsWith("//")) u = "https:" + u;
            return a.requestUrlT(u, a.jsBase, timeoutMs > 0 ? timeoutMs : 0);
        }

        /**
         * java.connect(url)：给源自己做 POST/PUT 用。
         * 返回 HttpURLConnection，脚本里可以 setRequestMethod / setRequestProperty / getOutputStream。
         */
        public Object connect(String url) {
            if (url == null) return null;
            String u = url.trim();
            if (u.startsWith("//")) u = "https:" + u;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                return c;
            } catch (Throwable t) {
                a.devLogLine("听书", "java.connect 失败 " + u + "：" + tail(t.getMessage()));
                return null;
            }
        }

        /** java.setContent(text)：规则里用来改写当前 result（后续规则作用在它上面） */
        public String setContent(String content) {
            a.jsHtml = content == null ? "" : content;
            return a.jsHtml;
        }

        /** java.openUrl(url)：交给系统浏览器打开（登录、网页阅读这类） */
        public void openUrl(String url) {
            if (url == null || url.trim().isEmpty()) return;
            // 统一走 openInBrowser：它负责补协议头、失败提示，别再各写一套 startActivity
            if (!a.openInBrowser(url.trim())) a.toast("打不开这个链接");
        }

        public String base64Encode(String s) {
            try { return android.util.Base64.encodeToString(s.getBytes("UTF-8"), android.util.Base64.NO_WRAP); }
            catch (Exception e) { return ""; }
        }

        public String base64Decode(String s) {
            try { return new String(android.util.Base64.decode(s, android.util.Base64.NO_WRAP), "UTF-8"); }
            catch (Exception e) { return ""; }
        }

        /** 解密类源常用：拿到字节数组再喂给 SecretKeySpec。返回 byte[]，脚本里当 Java 数组用 */
        public byte[] base64DecodeToByteArray(String s) {
            try { return android.util.Base64.decode(s == null ? "" : s, android.util.Base64.NO_WRAP); }
            catch (Exception e) { return new byte[0]; }
        }

        public String md5Encode(String s) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] b = md.digest(s.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte x : b) sb.append(String.format("%02x", x));
                return sb.toString();
            } catch (Exception e) { return ""; }
        }

        public String md5Encode16(String s) {
            String h = md5Encode(s);
            return h.length() >= 24 ? h.substring(8, 24) : h;
        }

        public int random(int min, int max) {
            if (max <= min) return min;
            return min + new java.util.Random().nextInt(max - min + 1);
        }

        /**
         * java.randomUUID()：对齐 Legado JsExtensions.randomUUID。
         * 阅友那类接口把 uuid 当 tmpToken 拼进签名，没有它整条 searchUrl 脚本算不出，
         * 表现就是「搜索成功、书地址取空」。
         */
        public String randomUUID() {
            return java.util.UUID.randomUUID().toString();
        }

        /** 16 进制小写，输出口径与 md5Encode 一致 */
        private String hexLower(byte[] b) {
            if (b == null) return "";
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        }

        /** 算法名归一：Legado 里传 HmacMD5 / HmacSHA256，也有源图省事传 MD5 / SHA256 */
        private String macAlg(String alg) {
            String a = alg == null ? "" : alg.trim();
            if (a.isEmpty()) return "HmacMD5";
            String up = a.toUpperCase();
            if (up.startsWith("HMAC")) {
                return "Hmac" + a.substring(4).replace("-", "").replace("_", "").toUpperCase();
            }
            return "Hmac" + a.replace("-", "").replace("_", "").toUpperCase();
        }

        private byte[] hmacBytes(String data, String alg, String key) {
            String a = macAlg(alg);
            try {
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance(a);
                mac.init(new javax.crypto.spec.SecretKeySpec(
                        (key == null ? "" : key).getBytes("UTF-8"), a));
                return mac.doFinal((data == null ? "" : data).getBytes("UTF-8"));
            } catch (Throwable t) {
                // 以前缺这个方法时，loose 代理让调用返回空串，签名就静默变成 ""，最难查
                this.a.devLogLine("JS", "HMac 失败 alg=" + alg + "→" + a + "："
                        + t.getClass().getSimpleName() + "：" + tail(t.getMessage()));
                return null;
            }
        }

        /** java.HMacHex(data, algorithm, key)：签名算法里最常用的一项 */
        public String HMacHex(String data, String alg, String key) {
            return hexLower(hmacBytes(data, alg, key));
        }

        /** java.HMacBase64(data, algorithm, key) */
        public String HMacBase64(String data, String alg, String key) {
            byte[] b = hmacBytes(data, alg, key);
            return b == null ? "" : android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
        }

        /** 摘要算法名归一：SHA256 / SHA-256 都要能过 */
        private String digestAlg(String alg) {
            String a = alg == null ? "" : alg.trim();
            if (a.isEmpty()) return "MD5";
            String up = a.toUpperCase().replace("_", "-");
            if (up.equals("SHA1") || up.equals("SHA-1")) return "SHA-1";
            if (up.equals("SHA256") || up.equals("SHA-256")) return "SHA-256";
            if (up.equals("SHA384") || up.equals("SHA-384")) return "SHA-384";
            if (up.equals("SHA512") || up.equals("SHA-512")) return "SHA-512";
            return a;
        }

        private byte[] digestBytes(String data, String alg, String label) {
            String a = digestAlg(alg);
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance(a);
                return md.digest((data == null ? "" : data).getBytes("UTF-8"));
            } catch (Throwable t) {
                this.a.devLogLine("JS", label + " 失败 alg=" + alg + "→" + a + "："
                        + t.getClass().getSimpleName() + "：" + tail(t.getMessage()));
                return null;
            }
        }

        /** java.digestHex(data, algorithm) */
        public String digestHex(String data, String alg) {
            return hexLower(digestBytes(data, alg, "digestHex"));
        }

        /** java.digestBase64Str(data, algorithm) */
        public String digestBase64Str(String data, String alg) {
            byte[] b = digestBytes(data, alg, "digestBase64Str");
            return b == null ? "" : android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
        }

        public String get(String k) { return a.rules.varGet(k); }

        public void put(String k, String v) { a.rules.varPut(k, v); }

        public void log(String s) { a.devLogLine("听书", "JS " + s); }
        public void toast(Object s) { a.toast(s == null ? "" : String.valueOf(s)); }
        public void longToast(Object s) { a.toast(s == null ? "" : String.valueOf(s)); }
        public String androidId() { try { return android.provider.Settings.Secure.getString(
                a.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID); } catch (Throwable t) { return ""; } }
        public String t2s(String s) { return s; }

        /** java.s2t：简转繁。没有映射表就原样返回，别让脚本 TypeError 打断整条规则 */
        public String s2t(String s) { return s; }
        // Legado 的 getWebViewUA 返回 WebView 自己的 UA（带 wv 标识），源里拿它拼 header；
        // 以前返回空串，拼出来的 header 就是空的
        public String getWebViewUA() {
            return "Mozilla/5.0 (Linux; Android 13; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36";
        }
        /**
         * java.importScript(path)：对齐 Legado JsExtensions.importScript——它只负责「把脚本内容拿回来」，
         * 由规则自己 eval。以前这里直接返回 ""，jsLib 里写 eval(java.importScript("…")) 就变成 eval("")，
         * 源自带的那套函数全没了，之后所有规则都报某函数未定义。
         */
        public String importScript(String p) {
            if (p == null) return "";
            String path = p.trim();
            if (path.isEmpty()) return "";
            if (path.startsWith("http://") || path.startsWith("https://")) {
                synchronized (a.jsLibFetched) {
                    String c = a.jsLibFetched.get(path);
                    if (c != null) return c;
                }
                String c = "";
                try { c = a.httpGetText(path, 20000); }
                catch (Throwable t) { a.devLogLine("JS", "importScript 下载失败 " + path + "：" + tail(t.getMessage())); }
                c = c == null ? "" : c;
                synchronized (a.jsLibFetched) { a.jsLibFetched.put(path, c); }
                return c;
            }
            String rel = path.startsWith("/") ? path.substring(1) : path;
            // 1) assets 里的脚本
            try {
                java.io.InputStream in = a.getAssets().open(rel);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                return new String(bos.toByteArray(), "UTF-8");
            } catch (Throwable ignored) { }
            // 2) 应用私有目录（Legado 的 readTxtFile 口径）
            try {
                java.io.File f = new java.io.File(path.startsWith("/") ? path : a.getFilesDir() + "/" + path);
                if (f.isFile()) {
                    java.io.FileInputStream fin = new java.io.FileInputStream(f);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = fin.read(buf)) > 0) bos.write(buf, 0, n);
                    fin.close();
                    return new String(bos.toByteArray(), "UTF-8");
                }
            } catch (Throwable ignored) { }
            a.devLogLine("JS", "importScript 找不到脚本：" + path);
            return "";
        }
        public String lang() { return "zh"; }

        // ---------------- 书源 JS 函数（对齐 Legado JsExtensions 的语义）

        /** HTML 转纯文本，保留图片；相对图片地址按 base 补全 */
        public String htmlFormat(String html) {
            return com.mangjie.reader.reader.HtmlFormat.formatKeepImg(html, a.jsBase);
        }

        public String encodeURI(String s) {
            try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
            catch (Exception e) { return ""; }
        }

        public String encodeURI(String s, String enc) {
            try { return java.net.URLEncoder.encode(s == null ? "" : s, enc == null ? "UTF-8" : enc); }
            catch (Exception e) { return ""; }
        }

        public String decodeURI(String s) {
            try { return java.net.URLDecoder.decode(s == null ? "" : s, "UTF-8"); }
            catch (Exception e) { return ""; }
        }

        /** 毫秒时间戳格式化，默认格式与 Legado 一致：yyyy/MM/dd HH:mm */
        public String timeFormat(long time) {
            return timeFormat(time, "yyyy/MM/dd HH:mm");
        }

        public String timeFormat(long time, String format) {
            try {
                return new java.text.SimpleDateFormat(format == null ? "yyyy/MM/dd HH:mm" : format,
                        java.util.Locale.getDefault()).format(new java.util.Date(time));
            } catch (Exception e) { return ""; }
        }

        public byte[] strToBytes(String s) {
            try { return (s == null ? "" : s).getBytes("UTF-8"); }
            catch (Exception e) { return new byte[0]; }
        }

        public byte[] strToBytes(String s, String charset) {
            try { return (s == null ? "" : s).getBytes(charset == null ? "UTF-8" : charset); }
            catch (Exception e) { return new byte[0]; }
        }

        /** JS 里的字节数组可能是 Java byte[]，也可能是 Rhino 的 NativeArray，两种都认 */
        public String bytesToStr(Object bytes) {
            return bytesToStr(bytes, "UTF-8");
        }

        public String bytesToStr(Object bytes, String charset) {
            byte[] b = asBytes(bytes);
            if (b == null) return "";
            try { return new String(b, charset == null ? "UTF-8" : charset); }
            catch (Exception e) { return ""; }
        }

        private byte[] asBytes(Object o) {
            if (o instanceof byte[]) return (byte[]) o;
            if (o instanceof org.mozilla.javascript.NativeJavaArray) {
                Object u = ((org.mozilla.javascript.NativeJavaArray) o).unwrap();
                if (u instanceof byte[]) return (byte[]) u;
            }
            if (o instanceof org.mozilla.javascript.NativeArray) {
                org.mozilla.javascript.NativeArray arr = (org.mozilla.javascript.NativeArray) o;
                byte[] b = new byte[(int) arr.getLength()];
                for (int i = 0; i < b.length; i++) {
                    Object v = arr.get(i, arr);
                    b[i] = (byte) ((int) org.mozilla.javascript.ScriptRuntime.toNumber(v));
                }
                return b;
            }
            return null;
        }

        public String hexEncodeToString(String s) {
            byte[] b = strToBytes(s);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
            return sb.toString();
        }

        public String hexDecodeToString(String hex) {
            if (hex == null || hex.isEmpty()) return "";
            String h = hex.replaceAll("\\s+", "");
            if (h.length() % 2 != 0) return "";
            byte[] b = new byte[h.length() / 2];
            for (int i = 0; i < b.length; i++) {
                try { b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16); }
                catch (Exception e) { return ""; }
            }
            try { return new String(b, "UTF-8"); } catch (Exception e) { return ""; }
        }

        /** 并发抓多个 url，返回带 body()/code()/url() 的数组，直接当字符串用也能拿到正文 */
        public Object[] ajaxAll(Object urlList) {
            java.util.List<String> urls = new java.util.ArrayList<String>();
            if (urlList instanceof org.mozilla.javascript.NativeArray) {
                org.mozilla.javascript.NativeArray arr = (org.mozilla.javascript.NativeArray) urlList;
                for (int i = 0; i < arr.getLength(); i++) {
                    Object v = arr.get(i, arr);
                    if (v == null) continue;
                    String u = org.mozilla.javascript.Context.toString(v).trim();
                    if (!u.isEmpty()) urls.add(u);
                }
            } else if (urlList instanceof String) {
                urls.add(((String) urlList).trim());
            }
            if (urls.isEmpty()) return new JsResp[0];
            JsResp[] out = new JsResp[urls.size()];
            final int threads = Math.min(4, Math.max(1, urls.size()));
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                final java.util.List<String> us = urls;
                final JsResp[] res = out;
                java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
                for (int i = 0; i < urls.size(); i++) {
                    final int idx = i;
                    fs.add(pool.submit(new Runnable() { @Override public void run() {
                        String u = us.get(idx);
                        if (u.startsWith("//")) u = "https:" + u;
                        String body = a.httpGetText(u);
                        res[idx] = new JsResp(body == null ? "" : body, u, body == null ? 0 : 200);
                    }}));
                }
                for (java.util.concurrent.Future<?> f : fs) {
                    try { f.get(30, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
                }
                for (int i = 0; i < out.length; i++) {
                    if (out[i] == null) out[i] = new JsResp("", urls.get(i), 0);
                }
                return out;
            } finally {
                pool.shutdown();
            }
        }

        /** HEAD 请求，返回带 header('名字')/status() 的对象 */
        public JsHead head(String url) {
            String u = url == null ? "" : url.trim();
            if (u.startsWith("//")) u = "https:" + u;
            return new JsHead(a.http.httpHead(u));
        }

        public String getCookie(String url) {
            try {
                java.util.Map<String, String> c = a.http.cookieFor(url);
                if (c == null || c.isEmpty()) return "";
                StringBuilder sb = new StringBuilder();
                for (java.util.Map.Entry<String, String> e : c.entrySet()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(e.getKey()).append('=').append(e.getValue());
                }
                return sb.toString();
            } catch (Exception e) { return ""; }
        }

        public String getCookie(String url, String key) {
            try {
                java.util.Map<String, String> c = a.http.cookieFor(url);
                if (c == null || key == null) return "";
                String v = c.get(key);
                return v == null ? "" : v;
            } catch (Exception e) { return ""; }
        }

        /** 一次抓取的返回体，模仿 Legado 的 StrResponse */
        static final class JsResp {
            final String body, url;
            final int code;
            JsResp(String body, String url, int code) { this.body = body; this.url = url; this.code = code; }
            public String body() { return body; }
            public String string() { return body; }
            public String url() { return url; }
            public int code() { return code; }
            @Override public String toString() { return body; }
        }

        /** HEAD 的响应头 */
        static final class JsHead {
            private final java.util.Map<String, String> h;
            JsHead(java.util.Map<String, String> h) { this.h = h == null ? new java.util.HashMap<String, String>() : h; }
            public String header(String name) {
                if (name == null) return "";
                String v = h.get(name);
                if (v != null) return v;
                v = h.get(name.toLowerCase(java.util.Locale.ROOT));
                return v == null ? "" : v;
            }
            public int status() {
                String s = h.get(":status");
                try { return s == null ? 0 : Integer.parseInt(s); } catch (Exception e) { return 0; }
            }
            public String toString() { return h.toString(); }
        }

        /**
         * java.post(url, body[, headers])：Legado 里 POST 是独立函数，不是靠 ajax 传参。
         * 返回 Legado 的 StrResponse 形状（复用本类已有的 JsResp）：可 .body() / .code() / .url()，
         * 也能直接当字符串拼。
         */
        public JsResp post(String url, String body) { return post(url, body, null); }

        public JsResp post(String url, String body, Object headers) {
            String u = url == null ? "" : url.trim();
            if (u.startsWith("//")) u = "https:" + u;
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("method", "POST");
                if (body != null) o.put("body", body);
                org.json.JSONObject hs = headersToJson(headers);
                if (hs != null && hs.length() > 0) o.put("headers", hs);
                com.mangjie.reader.net.Http.Resp r = a.http.fetch(u + "," + o.toString(), a.jsBase, 0);
                if (r == null) return new JsResp("", u, 0);
                return new JsResp(r.body, r.url, r.code);
            } catch (Throwable t) {
                a.devLogLine("听书", "java.post 失败 " + u + "：" + tail(t.getMessage()));
                return new JsResp("", u, 0);
            }
        }

        /** headers 可能是 Java Map（内部直接传），也可能是 Rhino 的 NativeObject（脚本里写的对象字面量） */
        private org.json.JSONObject headersToJson(Object headers) {
            if (headers == null) return null;
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                if (headers instanceof java.util.Map) {
                    for (Object k : ((java.util.Map<?, ?>) headers).keySet()) {
                        Object v = ((java.util.Map<?, ?>) headers).get(k);
                        o.put(String.valueOf(k), v == null ? "" : String.valueOf(v));
                    }
                    return o;
                }
                if (headers instanceof org.mozilla.javascript.NativeObject) {
                    org.mozilla.javascript.NativeObject n = (org.mozilla.javascript.NativeObject) headers;
                    for (Object id : n.getIds()) {
                        Object v = n.get(String.valueOf(id), n);
                        if (v == org.mozilla.javascript.Scriptable.NOT_FOUND) continue;
                        o.put(String.valueOf(id), org.mozilla.javascript.Context.toString(v));
                    }
                    return o;
                }
            } catch (Throwable ignored) {}
            return null;
        }

        /**
         * java.getStringList(rule)：对当前 result 再跑一次规则，返回字符串数组。
         * Legado 返回 Java List，脚本里用 .size() / .get(i)，这里保持一致。
         */
        public java.util.List<String> getStringList(String rule) {
            return a.ruleAll(rule, a.jsBase, a.jsHtml);
        }

        /**
         * java.getElements(rule)：返回 jsoup 的 Elements（不是普通数组）。
         * 脚本里用的 .text() / .attr() / .size() / .get(i) 都是 Elements 原生方法，
         * 返回数组会缺这几个，所以必须包成 Elements。
         */
        public org.jsoup.select.Elements getElements(String rule) {
            java.util.List<org.jsoup.nodes.Element> es = a.rules.defaultChain(rule, a.jsBase, a.jsHtml);
            return new org.jsoup.select.Elements(es == null ? new java.util.ArrayList<org.jsoup.nodes.Element>() : es);
        }

        /**
         * java.getElement(rule)：取第一个元素。
         * 没命中时给一个空元素，而不是 null —— null 会让脚本下一步 .text() 直接 TypeError，
         * 一条规则取不到不该把整段脚本打断。
         */
        public org.jsoup.nodes.Element getElement(String rule) {
            java.util.List<org.jsoup.nodes.Element> es = a.rules.defaultChain(rule, a.jsBase, a.jsHtml);
            if (es == null || es.isEmpty()) return new org.jsoup.nodes.Element("div");
            return es.get(0);
        }

        public String getString(String rule) {
            java.util.List<String> v = a.ruleAll(rule, a.jsBase, a.jsHtml);
            String first = v.isEmpty() || v.get(0) == null ? "" : v.get(0);
            if (!first.isEmpty()) return first;
            /*
             * java.getString("bookId") 这种直接传键名的用法（西瓜免费那类）：
             * 内容本身是 JSON 时按 $.key、再退 $..key 取一次，别直接返回空。
             */
            String r2 = rule == null ? "" : rule.trim();
            if (!r2.isEmpty() && !r2.startsWith("$") && !r2.startsWith("@") && !r2.startsWith("/")) {
                for (String path : new String[]{"$." + r2, "$.." + r2}) {
                    java.util.List<String> v2 = a.ruleAll(path, a.jsBase, a.jsHtml);
                    if (!v2.isEmpty() && v2.get(0) != null && !v2.get(0).trim().isEmpty()) return v2.get(0);
                }
            }
            return first;
        }
    }

    private static void putJs(org.mozilla.javascript.Scriptable scope, String key, String val) {
        scope.put(key, scope, val == null ? "" : val);
    }


    /** 脚本宿主转发：规则引擎已独立成模块 */
    public String hostOf(String url) { return rules.hostOf(url); }
    public java.util.List<String> ruleAll(String rule, String base, String html) { return rules.ruleAll(rule, base, html); }

    // ================= JS 引擎（对齐 Legado 的 JsExtensions / RhinoScriptEngine） =================

    /**
     * 1 = ES6（Legado 就是 VERSION_ES6），2 = 回落 1_8。
     * 安卓上 ES6 解析曾经报 Failed resolution，所以第一次用 ES6，真出错就回落到 1_8 再试。
     */
    private static volatile int jsLangMode = 0;

    /** 拦敏感类（对齐 Legado 的 RhinoClassShutter，黑名单：默认全放行，只拦这些） */
    private static final org.mozilla.javascript.ClassShutter JS_SHUTTER = new org.mozilla.javascript.ClassShutter() {
        private final String[] BAN = {
                "java.lang.Class", "java.lang.ClassLoader", "java.net.URLClassLoader",
                "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.ProcessImpl",
                "java.lang.UNIXProcess", "java.io.File", "java.io.FileDescriptor",
                "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.PrintStream",
                "java.io.FileReader", "java.io.FileWriter", "java.io.PrintWriter",
                "java.io.RandomAccessFile", "java.io.ObjectInputStream", "java.io.ObjectOutputStream",
                "java.security.AccessController", "java.util.Formatter",
                "java.nio.file.", "java.lang.reflect.", "java.lang.invoke.",
                "sun.", "libcore.", "dalvik.system.",
                "android.os.Process", "android.app.ActivityThread", "android.app.AppGlobals",
                "android.provider.Settings", "android.content.Intent"
        };
        @Override public boolean visibleToScripts(String full) {
            if (full == null) return false;
            for (String b : BAN) {
                if (full.equals(b)) return false;
                if (b.endsWith(".") && full.startsWith(b)) return false;
            }
            return true;
        }
    };

    /** 自管的 Java 包对象。Rhino 自带那套包解析在 Android 上会出现「org.jsoup.Jsoup 被当成包、
     *  调 parse 报 not a function」的情况（桌面 jdk17 同代码正常，只在真机上坏），
     *  原因被它自己吞掉了。这里换成用应用自己的 ClassLoader 解析，失败也留一条日志，不再静默。 */
    private class JsPkg implements org.mozilla.javascript.Scriptable {
        private final String full;
        private final org.mozilla.javascript.Scriptable parent;
        JsPkg(String full, org.mozilla.javascript.Scriptable parent) {
            this.full = full;
            this.parent = parent;
        }
        private Class<?> load(String f, String name) {
            try {
                return Class.forName(f, false, MainActivity.this.getClassLoader());   // false：不触发静态初始化
            } catch (Throwable t) {
                // 小写名多半是子包（org.jsoup.nodes），不是失败；大写才像类名，记下来
                if (name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0)) && devLog) {
                    synchronized (JS_PKG_MISS) {
                        if (JS_PKG_MISS.size() < 300 && JS_PKG_MISS.add(f))
                            devLogLine("JS", "类解析失败 " + f + "：" + t.getClass().getSimpleName() + "：" + tail(t.getMessage()));
                    }
                }
                return null;
            }
        }
        @Override public String getClassName() { return "JavaPackage"; }
        @Override public Object get(String name, org.mozilla.javascript.Scriptable start) {
            String f = full + "." + name;
            Class<?> c = load(f, name);
            if (c != null) {
                try { return new org.mozilla.javascript.NativeJavaClass(parent, c); } catch (Throwable t) { }
            }
            return new JsPkg(f, parent);
        }
        @Override public Object get(int i, org.mozilla.javascript.Scriptable start) { return NOT_FOUND; }
        @Override public boolean has(String name, org.mozilla.javascript.Scriptable start) { return true; }
        @Override public boolean has(int i, org.mozilla.javascript.Scriptable start) { return false; }
        @Override public void put(String name, org.mozilla.javascript.Scriptable start, Object v) { }
        @Override public void put(int i, org.mozilla.javascript.Scriptable start, Object v) { }
        @Override public void delete(String name) { }
        @Override public void delete(int i) { }
        @Override public org.mozilla.javascript.Scriptable getPrototype() { return null; }
        @Override public void setPrototype(org.mozilla.javascript.Scriptable p) { }
        @Override public org.mozilla.javascript.Scriptable getParentScope() { return parent; }
        @Override public void setParentScope(org.mozilla.javascript.Scriptable p) { }
        @Override public Object[] getIds() { return new Object[0]; }
        @Override public Object getDefaultValue(Class<?> hint) { return "[JavaPackage " + full + "]"; }
        @Override public boolean hasInstance(org.mozilla.javascript.Scriptable s) { return false; }
    }

    /** 类解析失败只记一次，别把日志刷满 */
    private static final java.util.Set<String> JS_PKG_MISS = new java.util.HashSet<String>();

    /** 接管几个顶级包名（java 除外：java 归 JsJava，那是 Legado 的语义），其余交回 Rhino */
    private static final java.util.Set<String> JS_TOP_PKGS = new java.util.HashSet<String>(
            java.util.Arrays.asList("org", "com", "net", "javax", "edu", "de"));

    private class JsTopLevel extends org.mozilla.javascript.ImporterTopLevel {
        JsTopLevel(org.mozilla.javascript.Context cx) { super(cx); }
        @Override public Object get(String name, org.mozilla.javascript.Scriptable start) {
            if (JS_TOP_PKGS.contains(name)) return new JsPkg(name, start);
            return super.get(name, start);
        }
        @Override public boolean has(String name, org.mozilla.javascript.Scriptable start) {
            if (JS_TOP_PKGS.contains(name)) return true;
            return super.has(name, start);
        }
    }

    /** 每个 Context 都带：解释模式 + ES6（失败回落） + 类白名单 + 允许 JS 直接读写 Java Map（infoMap 要用） */
    private static final org.mozilla.javascript.ContextFactory JSCX = new org.mozilla.javascript.ContextFactory() {
        @Override protected org.mozilla.javascript.Context makeContext() {
            org.mozilla.javascript.Context cx = super.makeContext();
            cx.setOptimizationLevel(-1);
            try {
                cx.setLanguageVersion(jsLangMode == 2
                        ? org.mozilla.javascript.Context.VERSION_1_8
                        : org.mozilla.javascript.Context.VERSION_ES6);
            } catch (Throwable t) {
                try { cx.setLanguageVersion(org.mozilla.javascript.Context.VERSION_1_8); } catch (Throwable ignored) { }
            }
            try { cx.setClassShutter(JS_SHUTTER); } catch (Throwable ignored) { }
            return cx;
        }
        @Override public boolean hasFeature(org.mozilla.javascript.Context cx, int featureIndex) {
            if (featureIndex == org.mozilla.javascript.Context.FEATURE_ENABLE_JAVA_MAP_ACCESS) return true;
            return super.hasFeature(cx, featureIndex);
        }
    };

    /** 探索页 infoMap：Legado 每个源一份，JS 里 infoMap.xxx 读写（同一源多次求值要接着用） */
    private final java.util.Map<String, java.util.Map<String, String>> exInfoMaps =
            new java.util.HashMap<String, java.util.Map<String, String>>();

    private java.util.Map<String, String> infoMapOf(org.json.JSONObject src) {
        String k = src == null ? "" : src.optString("bookSourceUrl", "");
        java.util.Map<String, String> m = exInfoMaps.get(k);
        if (m == null) { m = new java.util.HashMap<String, String>(); exInfoMaps.put(k, m); }
        return m;
    }

    /** jsLib 可能是 JSON 映射（{"名字":"http…js"}），Legado 会下载后 eval */
    private final java.util.Map<String, String> jsLibFetched = new java.util.HashMap<String, String>();

    private String loadJsLibCode() {
        if (jsLibCode == null || jsLibCode.isEmpty()) return "";
        String t = jsLibCode.trim();
        if (!t.startsWith("{")) return t;
        try {
            org.json.JSONObject o = new org.json.JSONObject(t);
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String key = it.next();
                String val = o.optString(key, "").trim();
                if (val.startsWith("http")) {
                    String js = jsLibFetched.get(val);
                    if (js == null) {
                        try { js = httpGetText(val, 20000); } catch (Throwable e) { js = ""; }
                        jsLibFetched.put(val, js == null ? "" : js);
                    }
                    if (js != null && !js.isEmpty()) sb.append(js).append('\n');
                } else if (!val.isEmpty()) {
                    sb.append(val).append('\n');
                }
            }
            devLogLine("听书", "jsLib 是 JSON 映射，拼出 " + sb.length() + " 字节");
            return sb.toString();
        } catch (Throwable e) {
            return t;
        }
    }

    /** 把 Java 对象包成"缺什么用什么"的宽松对象 */
    private static org.mozilla.javascript.Scriptable loose(org.mozilla.javascript.Scriptable scope,
                                                           Object target, String label) {
        org.mozilla.javascript.Scriptable real = (org.mozilla.javascript.Scriptable)
                org.mozilla.javascript.Context.javaToJS(target, scope);
        JsLoose l = new JsLoose(real, label);
        l.setParentScope(scope);
        l.setPrototype(org.mozilla.javascript.ScriptableObject.getObjectPrototype(scope));
        return l;
    }

    /** 未实现成员的替身：既可被调用（返回空串），也可继续点下去（java.util.xxx） */
    private static final class JsFallback extends org.mozilla.javascript.BaseFunction {
        private final String label;
        JsFallback(String label) { this.label = label; }
        @Override public Object call(org.mozilla.javascript.Context cx, org.mozilla.javascript.Scriptable scope,
                                     org.mozilla.javascript.Scriptable thisObj, Object[] args) {
            return "";
        }
        @Override public Object get(String n, org.mozilla.javascript.Scriptable s) { return new JsFallback(label + "." + n); }
        @Override public boolean has(String n, org.mozilla.javascript.Scriptable s) { return true; }
        @Override public Object getDefaultValue(Class<?> hint) { return ""; }
        @Override public String toString() { return ""; }
    }

    private static final class JsLoose extends org.mozilla.javascript.NativeObject {
        private final org.mozilla.javascript.Scriptable real;
        private final String label;
        JsLoose(org.mozilla.javascript.Scriptable real, String label) { this.real = real; this.label = label; }
        @Override public Object get(String name, org.mozilla.javascript.Scriptable start) {
            Object v = real == null ? org.mozilla.javascript.Scriptable.NOT_FOUND : real.get(name, real);
            if (v != org.mozilla.javascript.Scriptable.NOT_FOUND && v != null
                    && v != org.mozilla.javascript.Undefined.instance) return v;
            return new JsFallback(label + "." + name);
        }
        @Override public boolean has(String name, org.mozilla.javascript.Scriptable start) { return true; }
        @Override public Object getDefaultValue(Class<?> hint) { return ""; }
    }

    private static boolean isJsonNumber(String t) {
        if (t == null || t.isEmpty()) return false;
        int n = t.length();
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (!((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E')) return false;
        }
        return true;
        }

    /** 当前 jsLib 求值所在的 Context / Scope：java.lock 要在同一作用域里回调 JS 函数 */
    /** 最近一次 JS 失败的原文（给网络阅读报错用） */
    public String lastJsErr = "";
    private org.mozilla.javascript.Context jsCx = null;
    private org.mozilla.javascript.Scriptable jsScope = null;
    private final java.util.HashMap<String, Object> jsLockMap = new java.util.HashMap<>();

    /** 同一个 key 拿到同一个监视器对象：番茄用锁保护每日额度计数，不能并行 */
    private Object lockFor(String key) {
        synchronized (jsLockMap) {
            Object m = jsLockMap.get(key);
            if (m == null) { m = new Object(); jsLockMap.put(key, m); }
            return m;
        }
    }

    private String evalJs(String code, String data, String base) {
        if (code == null || code.trim().isEmpty()) return "";
        if (jsLangMode == 0) jsLangMode = 1;              // 第一次用 ES6（对齐 Legado）
        String v = evalJsOnce(code, data, base);
        if (v != null) return v;
        if (jsLangMode == 1) {                            // ES6 出事：回落 1_8 再试一次
            jsLangMode = 2;
            devLogLine("听书", "ES6 不可用，回落到 1_8（ES5）重试");
            v = evalJsOnce(code, data, base);
            if (v != null) return v;
        }
        return "";
    }

    /** 返回 null 表示这次求值失败（供 evalJs 回落重试）；"" 表示成功但结果为空 */
    private String evalJsOnce(String code, String data, String base) {
        jsBase = base == null ? "" : base;
        jsHtml = data == null ? "" : data;
        org.mozilla.javascript.Context cx = null;
        try {
            cx = JSCX.enterContext();
            // 用自管的 TopLevel：org / com / net 这些顶级包的解析不再交给 Rhino，
            // 它在 Android 上会把 org.jsoup.Jsoup 当成包，parse 直接报 not a function
            org.mozilla.javascript.Scriptable scope = new JsTopLevel(cx);
            scope.put("log", scope, new org.mozilla.javascript.BaseFunction() {
                @Override public Object call(org.mozilla.javascript.Context c2, org.mozilla.javascript.Scriptable s2,
                                             org.mozilla.javascript.Scriptable t2, Object[] a2) {
                    StringBuilder sbb = new StringBuilder();
                    for (int i = 0; a2 != null && i < a2.length; i++) {
                        if (i > 0) sbb.append(' ');
                        sbb.append(org.mozilla.javascript.Context.toString(a2[i]));
                    }
                    devLogLine("JS", sbb.toString());
                    return org.mozilla.javascript.Undefined.instance;
                }
            });
            jsCx = cx;
            jsScope = scope;
            // 先把源的 jsLib 灌进同一个作用域，再跑规则（Legado 的顺序）
            String lib = loadJsLibCode();
            if (!lib.isEmpty()) {
                try {
                    if (jsLibScript == null || !jsLibScriptFor.equals(lib)) {
                        jsLibScript = cx.compileString(lib, "jsLib", 1, null);   // 编译一次，之后只 exec
                        jsLibScriptFor = lib;
                        devLogLine("听书", "源 jsLib 已编译　" + lib.length() + " 字节");
                    }
                    jsLibScript.exec(cx, scope);
                } catch (Throwable t) {
                    devLogLine("听书", "源 jsLib 执行失败：" + t.getClass().getSimpleName() + "：" + tail(t.getMessage()));
                    return null;          // 让外层回落 1_8 重试
                }
            }
            putJs(scope, "result", data == null ? "" : data);
            putJs(scope, "baseUrl", base == null ? "" : base);
            scope.put("page", scope, Double.valueOf(jsPage));
            putJs(scope, "key", jsKey == null ? "" : jsKey);
            /*
             * Legado AnalyzeRule.kt:834/839 —— bindings["book"] = book（BaseBook）、bindings["src"] = content。
             * 我们原来两个都没挂进作用域，源里只要写 book.xxx / src 就直接 ReferenceError
             * （日志里 127 次 "book" is not defined）。这里给空对象 + 当前内容，至少不打断脚本。
             */
            try {
                scope.put("book", scope, cx.newObject(scope));
            } catch (Throwable ignored) { }
            putJs(scope, "src", data == null ? "" : data);
            // java / cookie 都用宽松代理包一层：规则里点到我们没实现的成员时不再 TypeError 打断整个脚本，
            // 而是返回空串继续跑（Legado 有全套 JsExtensions，我们只实现了一部分）
            scope.put("java", scope, loose(scope, new JsJava(this), "java"));
            scope.put("cookie", scope, loose(scope, new JsCookie(this), "cookie"));
            // cache 必须跨调用保留（源里 put 之后下一段规则要 get 回来），所以用字段实例
            if (jsCache == null) jsCache = new JsCache();
            scope.put("cache", scope, loose(scope, jsCache, "cache"));
            // Legado 的 infoMap 是 Java Map，靠 FEATURE_ENABLE_JAVA_MAP_ACCESS 让 JS 直接 infoMap.xxx 读写；
            // 必须 javaToJS 包一层，直接 put Java 对象会报 Invalid JavaScript value of type java.util.HashMap
            try {
                scope.put("infoMap", scope,
                        org.mozilla.javascript.Context.javaToJS(infoMapOf(jsSrcJson), scope));
            } catch (Throwable t) {
                devLogLine("听书", "infoMap 注入失败：" + t.getMessage());
            }
            // 源对象：番茄系等把分类表、翻页状态都挂在 source 上
            try {
                if (jsSrcJson != null) scope.put("source", scope, new com.mangjie.reader.net.JsSource(jsSrcJson));
            } catch (Throwable t) {
                devLogLine("听书", "source 对象注入失败：" + t.getMessage());
            }
            Object r = cx.evaluateString(scope, code, "rule", 1, null);
            if (r == null || r == org.mozilla.javascript.Undefined.instance) return "";
            if (r instanceof org.mozilla.javascript.NativeArray) {
                // 数组必须序列化成合法 JSON 数组：以前用 \n 拼接，调用方分不清"这是一堆元素"，
                // 洋柿子那类 fqExploreRowsV2().map(JSON.stringify) 就只被当成 1 个对象（结果=1）
                org.mozilla.javascript.NativeArray arr = (org.mozilla.javascript.NativeArray) r;
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < arr.size(); i++) {
                    Object o = arr.get(i, arr);
                    if (sb.length() > 1) sb.append(",");
                    if (o == null || o == org.mozilla.javascript.Undefined.instance) { sb.append("null"); continue; }
                    String sv = null;
                    if (o instanceof org.mozilla.javascript.NativeObject
                            || o instanceof org.mozilla.javascript.NativeArray) {
                        try {
                            Object j = org.mozilla.javascript.NativeJSON.stringify(
                                    cx, scope, o, null, null);
                            if (j != null && j != org.mozilla.javascript.Undefined.instance
                                    && !"undefined".equals(String.valueOf(j))) sv = String.valueOf(j);
                        } catch (Throwable ignored) { }
                    }
                    if (sv == null) sv = org.mozilla.javascript.Context.toString(o);
                    String ts = sv == null ? "" : sv.trim();
                    if (ts.startsWith("{") || ts.startsWith("[") || ts.equals("null")
                            || ts.equals("true") || ts.equals("false") || isJsonNumber(ts)) sb.append(ts);
                    else sb.append(org.json.JSONObject.quote(sv == null ? "" : sv));
                }
                sb.append("]");
                return sb.toString();
            }
            if (r instanceof Number) {
                double d = ((Number) r).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
                return String.valueOf(d);
            }
            return org.mozilla.javascript.Context.toString(r);
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder("JS 失败：" + tail(t.getMessage()));
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            if (root != t) sb.append("　根因：").append(root.getClass().getSimpleName()).append("：").append(tail(root.getMessage()));
            StackTraceElement[] fs = t.getStackTrace();
            if (fs != null) for (int i = 0; i < fs.length && i < 5; i++)
                sb.append("　@").append(fs[i].getClassName()).append('.').append(fs[i].getMethodName()).append(':').append(fs[i].getLineNumber());
            devLogLine("听书", sb.toString());
            lastJsErr = tail(t.getMessage());
            return null;
        } finally {
            if (cx != null) org.mozilla.javascript.Context.exit();
        }
    }

    private static String tail(String s) {
        if (s == null) return "(null)";
        String t = s.replace('\n', ' ');
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    /** 把规则里的 <js>代码</js> 换成执行结果 */

    /** 展开规则里的 {{ }}：以 @ / $. / $[ / // 开头按规则算，否则按 JS 算 */

    /** @put:{"键":"值"} —— 存入变量并从规则里摘掉 */

    /** @get:键 —— 用变量值替换 */




    /** @json: 的极简 JSONPath：支持 $.a.b[0].c 与 $.list[*].name */

    /** XPath：先把 HTML 清洗成 XML，再走系统 XPath */


// httpGetText 已由 net/Http 提供，上面的转发就是入口



    /** 检查收藏的书有没有新章：跟上次打开时记的章数比 */

    private Screen searchBySourceScreen() {
        java.util.Map<String, Integer> cnt = new java.util.LinkedHashMap<String, Integer>();
        for (String[] b : nb.nbResults) {
            String si = b.length > 3 ? b[3] : "0";
            String nm = b.length > 4 && !b[4].isEmpty() ? b[4] : ("源" + si);
            Integer c = cnt.get(si + "\u0001" + nm);
            cnt.put(si + "\u0001" + nm, (c == null ? 0 : c) + 1);
        }
        List<Item> items = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : cnt.entrySet()) {
            String[] p3 = e.getKey().split("\u0001");
            items.add(new Item(p3[1] + "　" + e.getValue() + " 条", "nb:fs:" + p3[0]));
        }
        items.add(new Item("看全部来源", "nb:fz"));
        return new Screen("按来源筛选", items);
    }

    private void showSearchBySource(String si) {
        List<Item> items = new ArrayList<>();
        List<String[]> picked = new ArrayList<>();
        List<Integer> idxs = new ArrayList<>();
        for (int k = 0; k < nb.nbResults.size(); k++) {
            String[] b = nb.nbResults.get(k);
            if ((b.length > 3 ? b[3] : "0").equals(si)) { picked.add(b); idxs.add(k); }
        }
        for (int i = 0; i < picked.size(); i++)
            items.add(new Item(nb.nbResultLabel(picked.get(i)), "nb:book:" + idxs.get(i)));
        if (items.isEmpty()) items.add(new Item("（空）"));
        stack.add(new Screen("来源结果", items));
        render(true);
        speak(picked.size() + " 条");
    }

    /** 换源：拿当前书名再搜一遍别的源，按同名过滤 */

    /** 打开书：拉目录 */

    /** 取一章正文（规则命中失败就退回整页文本）；给阅读页和缓存共用 */

    /** 缓存整本：把目录里每一章抓下来，拼成一个 TXT 存到 网上缓存，本地阅读能直接打开 */

    /** 文件名净化：路径分隔符和非法字符换掉，别让书名把目录撑坏 */
    private String safeName(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) t = "未命名";
        return t.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
    }

    /** 缓存当前这一章，追加到同一本书的缓存文件里 */

    /** 打开章节：拉正文并进阅读页 */



    /** 整章连播：分块念，念到当前章最后一块就自动开下一章 */



    private void speakChapter() {
        String t = nb.nbReaderText == null ? "" : nb.nbReaderText.trim();
        if (t.isEmpty()) return;
        if (prefs.getBoolean("nbChain", true)) {
            nb.nbSpeakChapterChain(t, nb.nbLastChapterIdx());
            devLogLine("听书", "朗读本章（连播），长度 " + t.length());
            return;
        }
        if (t.length() > 3500) t = t.substring(0, 3500);
        speak(t);
        devLogLine("听书", "朗读本章，长度 " + t.length());
    }




    // ---- 默认规则链：id.list@tag.dd@a、[property=og:image]@content、$.a.b 之类

    // ---- 默认规则：按 Legado ElementsSingle 的索引语法实现
    private static final class IdxSpec {
        String beforeRule = "";
        char split = '.';
        final List<Integer> indexDefault = new ArrayList<>();
        final List<Object> indexes = new ArrayList<>();
    }

    /** 逆向扫描索引：支持 .0 / !0 / :n / [1, 3:-2:-10, 2] / [-1:0] */


    /** 顶层切分：跳过 [] () 平衡组与引号内的分隔符 */







    // ---------------- 网络听书



    private Screen favBooksScreen() {
        List<Item> items = new ArrayList<>();
        String store = prefs.getString("nbFav", "");
        for (String one : store.split("\n")) {
            if (one.trim().isEmpty()) continue;
            String[] p = one.split("\u0001");
            if (p.length < 3) continue;
            items.add(new Item(p[0], "nb:openfav:" + p[1] + "\u0001" + p[2]));
        }
        if (items.isEmpty()) items.add(new Item("（还没有收藏，书里长按可以收藏）"));
        return new Screen("我的收藏", items);
    }

    /** 网上书详情页：封面、简介、字数、最新章节、缓存占用，下面挂常用动作 */
    private void openNetDetail() {
        if (nb == null || nb.nbBookUrl == null || nb.nbBookUrl.isEmpty()) { speak("先打开一本网上书"); return; }
        speak("正在取书籍详情");
        final int si = nb.nbSourceIdx;
        final String url = nb.nbBookUrl;
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            final String[] d = nb.nbFetchDetail(si, url);
            final String cover = nb.nbFetchCover(si, d[6]);
            final String[] dd = d;
            final String cv = cover;
            ui.post(new Runnable() { @Override public void run() { showNetDetail(dd, cv); } });
        } });
    }

    private void showNetDetail(String[] d, String coverPath) {
        List<Item> items = new ArrayList<>();
        String name = d[0].isEmpty() ? (nb.nbBookName == null ? "" : nb.nbBookName) : d[0];
        items.add(new Item("书名　" + name));
        if (!d[1].isEmpty()) items.add(new Item("作者　" + d[1]));
        if (!d[2].isEmpty()) items.add(new Item("来源　" + d[2]));
        if (!d[3].isEmpty()) items.add(new Item("字数　" + d[3]));
        if (!d[4].isEmpty()) items.add(new Item("最新章节　" + d[4]));
        items.add(new Item("目录　" + nb.nbChapters.size() + " 章"
                + (nb.nbChapters.isEmpty() ? "（还没取目录）" : "")));
        int cc = com.mangjie.reader.net.ChapterCache.chapterCount(DATA_DIR, nb.nbBookUrl);
        items.add(new Item("缓存　" + cc + " 章　"
                + fmtSize(com.mangjie.reader.net.ChapterCache.sizeOf(DATA_DIR, nb.nbBookUrl))));
        if (!d[5].isEmpty()) {
            String intro = d[5].replace("\n", " ").trim();
            if (intro.length() > 400) intro = intro.substring(0, 400) + "…";
            items.add(new Item("简介　" + intro));
        }
        items.add(new Item("打开目录", "nb:chapterlist"));
        items.add(new Item(nb.nbIsAudioBook ? "从头开始播放" : "从头朗读这本", "nb:readall:0"));
        items.add(new Item("换源", "nb:switch"));
        if (nbInFav(nb.nbBookUrl)) {
            items.add(new Item("★ 已在书架（点一下移出）", "bkn:unfav:" + nb.nbBookUrl));
        } else {
            items.add(new Item("加入书架", "nb:favadd"));
        }
        items.add(new Item("置顶到书架最前", "bkn:top:" + nb.nbBookUrl));
        items.add(new Item("清除本书缓存", "nb:cacheclear"));
        Screen sc = new Screen("书籍详情　" + name, items).k("detail");
        sc.coverPath = coverPath;
        pushScreen(sc);
    }

    /** 详情页小封面：贴在当前焦点行的右端，不挡字也不进读屏焦点 */
    private void placeCover() {
        if (coverMini == null || scrollWrap == null) return;
        Screen sc = top();
        String path = sc == null ? null : sc.coverPath;
        if (path == null || menuOn || dialogOn || inputOn) {
            coverMini.setVisibility(View.GONE);
            return;
        }
        File f = new File(path);
        if (!f.isFile()) { coverMini.setVisibility(View.GONE); return; }
        android.graphics.Bitmap bm = decodeThumb(f, dp(44), dp(58));
        if (bm == null) { coverMini.setVisibility(View.GONE); return; }
        coverMini.setImageBitmap(bm);
        int y;
        if (sc.player) {
            y = dp(4);            // 播放页就一张固定封面，不跟焦点跑
        } else {
            int idx = sc.cur - rowOffset;
            if (idx < 0 || idx >= sc.items.size()) { coverMini.setVisibility(View.GONE); return; }
            y = idx * dp(ROW_DP) - (scroll == null ? 0 : scroll.getScrollY()) + dp(2);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) coverMini.getLayoutParams();
        if (y < 0 || y + dp(58) > scrollWrap.getHeight()) { coverMini.setVisibility(View.GONE); return; }
        lp.topMargin = y;
        coverMini.setLayoutParams(lp);
        coverMini.setVisibility(View.VISIBLE);
    }

    private android.graphics.Bitmap decodeThumb(File f, int w, int h) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = s;
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 书籍信息页上的动作做完后刷新这一页 */
    private void refreshBookInfo() {
        if (!stack.isEmpty() && top().title.equals("书籍信息")) stack.set(stack.size() - 1, bookInfoScreen());
        render(true);
    }

    /** 从「最近读过的书」里删掉某本（缓存不动） */
    private void removeRecentBook(String url) {
        if (url == null || url.isEmpty()) return;
        String store = prefs.getString("nbRecentList", "");
        StringBuilder sb = new StringBuilder();
        for (String line : store.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] q = t.split("\u0001");
            if (q.length >= 2 && q[1].equals(url)) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(t);
        }
        String one = prefs.getString("nbRecent", "");
        String[] oq = one.split("\u0001");
        if (oq.length >= 2 && oq[1].equals(url)) prefs.edit().remove("nbRecent").apply();
        prefs.edit().putString("nbRecentList", sb.toString()).apply();
    }

    private Screen recentBooksScreen() {
        List<Item> items = new ArrayList<>();
        String store = prefs.getString("nbRecentList", "");
        int n = 0;
        for (String one : store.split("\n")) {
            if (one.trim().isEmpty()) continue;
            String[] p = one.split("\u0001");
            if (p.length < 2 || p[1].trim().isEmpty()) continue;
            long sz = com.mangjie.reader.net.ChapterCache.sizeOf(DATA_DIR, p[1]);
            int cnt = com.mangjie.reader.net.ChapterCache.chapterCount(DATA_DIR, p[1]);
            String tail = cnt > 0 ? "　已缓存 " + cnt + " 章 " + fmtSize(sz) : "";
            items.add(new Item((n == 0 ? "上次读：" : "") + p[0] + tail, "nb:openrec:" + one));
            n++;
        }
        if (items.isEmpty()) {
            items.add(new Item("（还没有读过网上书）"));
        } else {
            items.add(new Item("清空最近读过的书（" + n + " 本，缓存不删）", "nb:recclear"));
        }
        return new Screen("最近阅读", items);
    }


    // ---------------- 电子词典（内置常用词表，离线可用）




    /** 网格里确认一格（万年历：打开当天日程；计算器：按下这个键） */

    private int gridDownCell = -1;
    private int gridDir = 0;
    private boolean gridEdgeBack = false;
    private int gridStartCell = 0;
    private int gridStepsX = 0;
    private int gridStepsY = 0;
    private int gridLastCell = -1;
    private boolean gridMovedAcross = false;



    /** 符号读成中文，TTS 念「*」「/」「.」常常没声音 */

    /** 键盘上的数字与运算符直接对应计算器键面 */


    /** 网格里当前那一行/格的文字 */


    /** 日历页移动红格；到月初/月末自动翻月 */





    // ================================================================ 搜索范围（对齐 Legado 的 SearchScope）

    private void startSearchInput() {
        inputAction = "nbsearch";
        inputTitle = "资源搜索";
        openInput("输入书名或作者");
    }

    private Screen mainScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("本地书架，共 " + localShelfCount() + " 本", "shelf:local"));
        items.add(new Item("网络书架，共 " + netShelfCount() + " 本", "shelf:net"));
        items.add(new Item("发现", "nb:explore"));
        items.add(new Item("订阅", "nb:subs"));
        items.add(new Item("搜索", "nb:search"));
        items.add(new Item("阅读统计", "records"));
        if (com.mangjie.reader.tools.TaskProgress.active())
            items.add(new Item("后台任务：" + com.mangjie.reader.tools.TaskProgress.brief() + "，点一下听进度", "task:now"));
        items.add(new Item("语音朗读", "go:speech"));
        items.add(new Item("设置", "settings"));
        items.add(new Item("关于", "about"));
        items.add(new Item("退出", "exit"));
        return new Screen("盲界阅读", items);
    }

    // ================================================================ 书架分组

    /**
     * 一次性迁移：自建分组删掉之前，把「只被加进自建组、没在收藏里」的网文并进网络收藏，
     * 否则删组以后这些书凭空消失。本地书不用迁——本地书架是按文件来源取的。
     */
    private void migrateShelfGroupsOnce() {
        if (prefs.getBoolean("shelfGroupsMigrated", false)) return;
        String rawFav = prefs.getString("nbFav", "");
        java.util.Set<String> favUrls = new java.util.HashSet<>();
        StringBuilder fav = new StringBuilder(rawFav == null ? "" : rawFav);
        for (String one : (rawFav == null ? "" : rawFav).split("\n")) {
            if (one.trim().isEmpty()) continue;
            String[] q = one.split("\u0001");
            if (q.length >= 2) favUrls.add(q[1]);
        }
        String st = prefs.getString("shelfGroups", "");
        int added = 0;
        if (st != null) {
            for (String line : st.split("\n")) {
                if (line.trim().isEmpty()) continue;
                String[] g = line.split("\u0001");
                if (g.length < 2 || !"manual".equals(g[1])) continue;
                String gname = g[0];
                for (String mem : readList("shelfMem_" + gname)) {
                    if (!mem.startsWith("net:")) continue;
                    String[] q = mem.substring(4).split("\u0002");
                    if (q.length < 2) continue;
                    String url = q[0].trim();
                    if (url.isEmpty() || favUrls.contains(url)) continue;
                    favUrls.add(url);
                    if (fav.length() > 0) fav.append("\n");
                    fav.append(q[1]).append("\u0001").append(url).append("\u0001")
                            .append(q.length >= 3 ? q[2] : "0");
                    added++;
                }
                prefs.edit().remove("shelfMem_" + gname).apply();
            }
        }
        prefs.edit().putString("nbFav", fav.toString())
                .remove("shelfGroups").remove("shelfGroupName")
                .putBoolean("shelfGroupsMigrated", true).apply();
        if (added > 0) devLogLine("书架", "自建分组迁入网络书架 " + added + " 本");
    }

    /** 只有本地 / 网络两条固定线：自建分组已删，页内也不再显示分组条 */
    private List<String[]> shelfGroups() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"本地", "local"});
        out.add(new String[]{"网络", "net"});
        return out;
    }





    

    /** 顶部横排分组按钮：选中用主菜单同一种红，未选中黑底细白边 */
    private void buildTabs(Screen sc) {
        if (tabsBox == null) return;
        tabsBox.removeAllViews();
        if (sc == null || sc.tabs == null || sc.tabs.isEmpty()) {
            tabsBox.setVisibility(View.GONE);
            return;
        }
        tabsBox.setVisibility(View.VISIBLE);
        final int n = sc.tabs.size();
        for (int i = 0; i < n; i++) {
            final int gi = i;
            TextView b = new TextView(this);
            b.setText(sc.tabs.get(i));
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(6), dp(9), dp(6), dp(9));
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, BTN_SP);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            float d = getResources().getDisplayMetrics().density;
            if (i == sc.tabCur) {
                b.setBackground(com.mangjie.reader.view.Highlight.tabOn(hlColor(), d));
                b.setTextColor(0xFFFFFFFF);
                b.setContentDescription(sc.tabs.get(i) + "，当前分组");
            } else {
                b.setBackground(com.mangjie.reader.view.Highlight.tabOff(palBg(), palFg(), d));
                b.setTextColor(palFg());
                b.setContentDescription(sc.tabs.get(i) + "，按一下切到这个分组");
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(3); lp.rightMargin = dp(3);
            tabsBox.addView(b, lp);
        }
    }

    private List<Item> localShelfItems() {
        List<Item> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String path : readList("favList")) {
            File f = new File(path);
            if (!seen.add(path)) continue;
            if (!f.exists()) {
                // 文件被移走或删了：留着这一条并说明，别让收藏凭空少一本
                out.add(new Item("（文件不在了）" + f.getName(), "gone:" + path));
                continue;
            }
            out.add(new Item(com.mangjie.reader.shelves.ShelfList.line(f.getName(), path, prefs), "book:" + path));
        }
        for (String path : readList("recentList")) {
            File f = new File(path);
            if (!f.exists() || !seen.add(path)) continue;
            out.add(new Item(com.mangjie.reader.shelves.ShelfList.line(f.getName(), path, prefs) + "　最近读",
                    "book:" + path));
        }
        return sortShelf(out, false);
    }

    private List<Item> netShelfItems() {
        List<Item> out = new ArrayList<>();
        if (nb != null) nb.loadBookSources();
        for (String one : prefs.getString("nbFav", "").split("\n")) {
            if (one.trim().isEmpty()) continue;
            String[] p = one.split("\u0001");
            if (p.length < 3) continue;
            out.add(new Item(p[0], nbFavKey(p[0], p[1], p[2])));
        }
        return sortShelf(out, true);
    }

    

    /** 两个书架各记各的排序：网络书架用 shelfSortNet，第一次没有时沿用老的全局值 */
    private int shelfSortMode(boolean net) {
        if (net) {
            return prefs.getInt("shelfSortNet",
                    prefs.getInt("shelfSort", com.mangjie.reader.shelves.ShelfList.SORT_RECENT));
        }
        return prefs.getInt("shelfSort", com.mangjie.reader.shelves.ShelfList.SORT_RECENT);
    }

    /** 书架排序：最近阅读保持原顺序，书名按拼音 */
    private List<Item> sortShelf(List<Item> items, boolean net) {
        if (shelfSortMode(net) == com.mangjie.reader.shelves.ShelfList.SORT_NAME) {
            java.util.Collections.sort(items, new java.util.Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    return com.mangjie.reader.shelves.ShelfList.compareName(a.label, b.label);
                }
            });
        }
        return items;
    }

    /** 主菜单用：本地收藏 + 最近读过的书去重（不查文件是否存在，主菜单里不做 IO） */
    private int localShelfCount() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String p : readList("favList")) seen.add(p);
        for (String p : readList("recentList")) seen.add(p);
        return seen.size();
    }

    private int netShelfCount() {
        int n = 0;
        for (String one : prefs.getString("nbFav", "").split("\n")) if (!one.trim().isEmpty()) n++;
        return n;
    }

    /** 刷新时沿用当前书架页的类型（本地 / 网络），别再靠 prefs 里的组名 */
    private Screen shelfScreen() { return shelfScreen(shelfNetTop()); }

    private boolean shelfNetTop() {
        if (stack.isEmpty()) return false;
        Screen t = stack.get(stack.size() - 1);
        return t != null && t.shelfNet;
    }

    /** 书架：本地书架 / 网络书架两个入口，页内不再有分组条，空的时候给一个能点的去处 */
    private Screen shelfScreen(boolean net) {
        List<Item> items = new ArrayList<>();
        List<Item> body = net ? netShelfItems() : localShelfItems();
        if (body.isEmpty()) {
            items.add(new Item(net ? "（网络书架还是空的，去搜索或发现里挑一本）" : "（本地书架还是空的，可以导入书进来）"));
            items.add(new Item(net ? "去搜索网文" : "导入本地书（选文件）", net ? "nb:search" : "shelf:import"));
        } else {
            items.addAll(body);
        }

        String title = net ? "网络书架" : "本地书架";
        Screen sc = new Screen(title + "，共 " + body.size() + " 本", items).k("shelf");
        sc.shelfNet = net;
        return sc;
    }

    /** 书架行 key：书名、网址、源标识（源网址优先；老数据是序号，照旧传） */
    private String nbFavKey(String name, String url, String ref) {
        String key = "";
        try { if (nb != null) key = nb.sourceKeyAt(Integer.parseInt(ref)); } catch (Exception ignored) {}
        if (key == null || key.isEmpty()) key = ref == null ? "" : ref;
        return "nb:openfav:" + (name == null ? "" : name) + "\u0001" + (url == null ? "" : url) + "\u0001" + key;
    }

    /** 书架书菜单已搬到 shelves/ShelfMenu，主类只留转发与接线 */
    private ShelfMenu shelfMenu;
    private ShelfMenu shelfMenu() {
        if (shelfMenu == null) shelfMenu = new ShelfMenu(new ShelfMenu.Host() {
            @Override public java.util.List<String> readList(String key) { return MainActivity.this.readList(key); }
            @Override public void saveList(String key, java.util.List<String> items) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) sb.append("\n");
                    sb.append(items.get(i));
                }
                prefs.edit().putString(key, sb.toString()).apply();
            }
            @Override public void removeFromList(String key, String path) {
                MainActivity.this.removeFromList(key, path);
            }
            @Override public void speak(String m) { MainActivity.this.speak(m); }
            @Override public void backToShelf() {
                goBack();
                for (int i = stack.size() - 1; i >= 0; i--) {
                    Screen sc = stack.get(i);
                    if (sc != null && "shelf".equals(sc.key)) stack.set(i, shelfScreen(sc.shelfNet));
                }
                render(true);
            }
        });
        return shelfMenu;
    }

    private Screen shelfBookMenuScreen(String path) { return shelfMenu().menu(path); }

    private void handleShelfBook(String rest) { shelfMenu().handle(rest); }

    private Screen shelfNetBookMenuScreen(String key) {
        String[] parts = nbKeyPayload(key).split("\u0001");
        String url = key.startsWith("nb:openfav:") ? parts[0] : (parts.length >= 2 ? parts[1] : parts[0]);
        String title = key.startsWith("nb:openfav:") ? url : parts[0];
        List<Item> items = new ArrayList<>();
        items.add(new Item("置顶到网络收藏最前", "bkn:top:" + url));
        items.add(new Item("移出网络收藏", "bkn:unfav:" + url));
        items.add(new Item("返回上一层", "nav:back"));
        return new Screen(title, items).k("shelfNetBookMenu");
    }

    /** 书架自己的功能菜单：按架分开列，只放这一架真的用得上的项 */
    private Screen shelfMenuScreen(boolean net) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("排序：" + com.mangjie.reader.shelves.ShelfList.sortName(shelfSortMode(net)), "shelf:sort"));
        if (!net) items.add(new Item("文件与缓存", "shelf:manage"));
        if (net) items.add(new Item("添加网址", "shelf:addurl"));
        items.add(new Item("搜索书架", "shelf:search"));
        items.add(new Item("书架管理", "shelf:batch"));
        if (net) items.add(new Item("更新目录", "nb:checkupd"));
        items.add(new Item(net ? "导出网络收藏" : "导出本地书", "shelf:export"));
        items.add(new Item(net ? "导入网络收藏" : "导入本地书", "shelf:import"));
        items.add(new Item("返回上一层", "nav:back"));
        return new Screen(net ? "网络书架菜单" : "本地书架菜单", items).k("shelfMenu");
    }
    private void nbAddFavRaw(String name, String url, int si) {
        if (url == null || url.isEmpty()) return;
        if (name == null || name.trim().isEmpty()) name = "网络书";
        StringBuilder sb = new StringBuilder(name.trim() + "\u0001" + url + "\u0001" + si);
        for (String line : prefs.getString("nbFav", "").split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] p = t.split("\u0001");
            // 同一本书换个源再来一次，url 会不同，按书名一起去重
            if (p.length >= 2 && (p[1].equals(url) || p[0].equals(name.trim()))) continue;
            sb.append("\n").append(t);
        }
        prefs.edit().putString("nbFav", sb.toString()).apply();
    }

    /** 添加网址：按域名认源，认不出就挨个文本书源试目录，能取到就加入书架并打开 */

    private void nbAddFav() {
        String url = nb == null || nb.nbBookUrl == null ? "" : nb.nbBookUrl;
        if (url.isEmpty()) return;
        String name = nb.nbBookName == null || nb.nbBookName.trim().isEmpty() ? "网络书" : nb.nbBookName.trim();
        nbAddFavRaw(name, url, nb.nbSourceIdx);
    }

    /** 写进书架：书名 + 网址 + 第几个源（老格式，读的时候会换算成源网址） */

    private String nbKeyPayload(String key) {
        if (key == null) return "";
        if (key.startsWith("nb:openfav:")) return key.substring("nb:openfav:".length());
        if (key.startsWith("nb:openrec:")) return prefs.getString("nbRecent", "");
        return "";
    }

    /** 长按书架上的一本网络书：先把这本书认下来，再进书籍详情 */

    /** 书架与书源管理的那几个状态（随这段一起恢复） */
    private final java.util.Set<String> batchSel = new java.util.HashSet<>();
    private final java.util.Set<String> srcMarkSel = new java.util.HashSet<>();
    private static final String[][] SRC_FIELDS = new String[][]{
            {"名称", "bookSourceName"}, {"地址", "bookSourceUrl"},
            {"搜索地址（{{key}} 是关键词）", "searchUrl"},
            {"搜索结果的列表规则", "ruleSearch.bookList"},
            {"搜索结果里的书名规则", "ruleSearch.name"},
            {"搜索结果里的作者规则", "ruleSearch.author"},
            {"搜索结果里的书链规则", "ruleSearch.bookUrl"},
            {"目录的列表规则", "ruleToc.chapterList"},
            {"目录里的章名规则", "ruleToc.chapterName"},
            {"目录里的章链规则", "ruleToc.chapterUrl"},
            {"正文规则", "ruleContent"}};
    private int srcEditIdx = -1;
    private String pendingSrcField = "";
    private void addBookByUrl(final String raw) {
        final String url = raw == null ? "" : raw.trim();
        if (!url.startsWith("http")) { speak("网址要以 http 开头"); toast("网址要以 http 开头"); return; }
        if (nb == null) { speak("网络书还没准备好"); return; }
        nb.loadBookSources();
        closeInput();
        speak("正在确认这个网址");
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            int si = nb.sourceIdxByRef("", url);
            if (si < 0) {
                int tried = 0;
                for (int i = 0; i < nb.nbSources.size() && tried < 12; i++) {
                    if (!nb.srcTextOk(i)) continue;
                    tried++;
                    try { if (nb.nbTocCount(i, url) > 0) { si = i; break; } } catch (Throwable ignored) {}
                }
            }
            final int found = si;
            String name = "";
            if (found >= 0) {
                try { String[] d = nb.nbFetchDetail(found, url); if (d != null && !d[0].isEmpty()) name = d[0].trim(); } catch (Throwable ignored) {}
            }
            final String bookName = name;
            runOnUiThread(new Runnable() { @Override public void run() {
                if (found < 0) {
                    speak("没找到能用的书源，这个网址取不到目录");
                    toast("没找到能用的书源");
                    return;
                }
                nbAddFavRaw(bookName, url, found);
                nb.nbResults = new ArrayList<>();
                nb.nbResults.add(new String[]{bookName, "", url, "" + found});
                nb.nbOpenBook(0);
                speak("已加入书架" + (bookName.isEmpty() ? "" : "：" + bookName));
            } });
        } });
    }

    /** 书详情页那行「加入书架／移出书架」：点了就切换，回来把这一行的字换掉 */
    private List<Item> currentShelfBody() {
        return shelfNetTop() ? netShelfItems() : localShelfItems();
    }

    /** 选择分组：列出所有分组，点哪个进哪个 */
    private void doShelfSearch(String q) {
        String key = q == null ? "" : q.trim();
        if (key.isEmpty()) { speak("请先输入要搜的字"); toast("请先输入要搜的字"); return; }
        closeInput();
        String low = key.toLowerCase();
        List<Item> hits = new ArrayList<>();
        for (Item it : currentShelfBody()) {
            if (it.label != null && it.label.toLowerCase().contains(low)) hits.add(new Item(it.label, it.key));
        }
        int n = hits.size();
        if (n == 0) hits.add(new Item("（没有找到含「" + key + "」的书）"));
        hits.add(new Item("返回上一层", "nav:back"));
        pushScreen(new Screen("书架搜索：" + key, hits).k("shelfSearch"));
        speak(n == 0 ? "没有找到" : "找到 " + n + " 本");
    }
    private int dropNbFav(String url) { return dropNb("nbFav", url); }
    private int dropNb(String prefKey, String url) {
        String raw = prefs.getString(prefKey, "");
        if (raw == null || raw.isEmpty()) return 0;
        List<String> keep = new ArrayList<>();
        int n = 0;
        for (String one : raw.split("\n")) {
            if (one.trim().isEmpty()) continue;
            String[] p = one.split("\u0001");
            if (p.length >= 2 && p[1].equals(url)) { n++; continue; }
            keep.add(one);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep.size(); i++) { if (i > 0) sb.append("\n"); sb.append(keep.get(i)); }
        prefs.edit().putString("nbFav", sb.toString()).apply();
        return n;
    }

    /** 本地文件名 → 书名：去扩展名、去 [作者]/（作者）、去「全本」「精校」这类尾巴 */
    private String localBookTitle(String path) {
        String n = new File(path).getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("（[^）]*）", " ").replaceAll("\\([^)]*\\)", " ");
        String[] tail = {"全本", "完本", "精校", "精校版", "校对版", "无错版", "TXT", "txt", "最新章节"};
        for (String t : tail) n = n.replace(t, " ");
        n = n.replace('_', ' ').replace('-', ' ').trim();
        if (n.length() > 40) n = n.substring(0, 40);
        return n.trim();
    }
    private boolean nbInFav(String url) {
        if (url == null || url.isEmpty()) return false;
        for (String line : prefs.getString("nbFav", "").split("\n")) {
            String[] p = line.split("\u0001");
            if (p.length >= 2 && p[1].trim().equals(url)) return true;
        }
        return false;
    }

    /** 收进书架：放最前面 */
    private void nbToggleFav() {
        String url = nb == null || nb.nbBookUrl == null ? "" : nb.nbBookUrl;
        if (url.isEmpty()) { speak("没有打开的书"); return; }
        boolean in = nbInFav(url);
        if (in) dropNb("nbFav", url); else nbAddFav();
        Screen t = top();
        for (int i = 0; i < t.items.size(); i++) {
            Item it = t.items.get(i);
            if (it.key != null && it.key.endsWith("nb:favtoggle")) {
                t.items.set(i, new Item(in ? "加入书架" : "★ 已在书架（点一下移出）", "nb:favtoggle"));
            }
        }
        render(false);
        speak(in ? "已从书架移出" : "已加入书架");
    }
    private void nbTopFirst(String url) {
        String raw = prefs.getString("nbFav", "");
        List<String> rows = new ArrayList<>();
        String hit = null;
        for (String one : raw.split("\n")) {
            if (one.trim().isEmpty()) continue;
            String[] p = one.split("\u0001");
            if (hit == null && p.length >= 2 && p[1].equals(url)) { hit = one; continue; }
            rows.add(one);
        }
        if (hit == null) { speak("这本不在网络收藏里"); return; }
        rows.add(0, hit);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) { if (i > 0) sb.append('\n'); sb.append(rows.get(i)); }
        prefs.edit().putString("nbFav", sb.toString()).apply();
        speak("已置顶");
        goBack();
        for (int i = stack.size() - 1; i >= 0; i--) {
            Screen sc = stack.get(i);
            if (sc != null && "shelf".equals(sc.key)) stack.set(i, shelfScreen(sc.shelfNet));
        }
        render(true);
    }

    /** 书架自己的功能菜单：菜单键或确认键长按唤出，条目顺序照 Legado 书架菜单折过来 */
    /** 书架菜单：浮层打开，不推整页 */
    private void openNetDetailFromKey(String key) {
        String payload = nbKeyPayload(key);
        String[] p = payload.split("\u0001");
        String name = "", url = "", ref = "";
        if (p.length >= 3) { name = p[0]; url = p[1]; ref = p[2]; }
        else if (p.length == 2) { url = p[0]; ref = p[1]; }
        else return;
        if (nb == null) return;
        nb.loadBookSources();
        int si = nb.sourceIdxByRef(ref, url);
        if (si < 0) {
            speak("这个书源不在了，先按书名重新找源");
            nb.nbOpenByFallback(name, url);
            return;
        }
        nb.nbBookName = name;
        nb.nbBookUrl = url;
        nb.nbSourceIdx = si;
        nb.nbChapters = new ArrayList<>();
        openNetDetail();
    }

    /** 长按网络收藏里的一本：菜单跟本地书分开做，因为存的是网址不是路径 */
    private void openShelfMenu() {
        boolean net = shelfNetTop();
        Screen ms = shelfMenuScreen(net);
        String[] names = new String[ms.items.size()];
        String[] keys = new String[ms.items.size()];
        for (int i = 0; i < ms.items.size(); i++) {
            names[i] = ms.items.get(i).label;
            keys[i] = ms.items.get(i).key == null ? "" : ms.items.get(i).key;
        }
        openFloatMenu(net ? "网络书架菜单" : "本地书架菜单", names, keys);
    }
    private Screen shelfBatchScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("已选 " + batchSel.size() + " 本，点书名勾选或取消", "sec"));
        for (Item it : currentShelfBody()) {
            if (it.key == null || it.key.isEmpty()) continue;
            boolean on = batchSel.contains(it.key);
            items.add(new Item((on ? "✓ " : "") + it.label, "shelf:batchsel:" + it.key));
        }
        items.add(new Item("把已选的移出收藏", "shelf:batchunfav"));
        items.add(new Item("清空选择", "shelf:batchclear"));
        items.add(new Item("返回上一层", "nav:back"));
        return new Screen("书架管理", items).k("shelfBatch");
    }

    /** 从网络收藏里删掉某本书（按网址找行） */
    private void shelfExport() {
        List<Item> body = currentShelfBody();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Item it : body) {
            String k = it.key;
            if (k == null) continue;
            if (k.startsWith("book:")) {
                sb.append("local\t").append(k.substring(5)).append('\n');
                n++;
            } else if (k.startsWith("nb:openfav:")) {
                sb.append("net\t").append(com.mangjie.reader.shelves.ShelfList.strip(it.label))
                        .append('\t').append(k.substring(10)).append('\n');
                n++;
            } else if (k.startsWith("nb:openrec:")) {
                sb.append("net\t").append(com.mangjie.reader.shelves.ShelfList.strip(it.label))
                        .append('\t').append(k.substring(10)).append('\n');
                n++;
            }
        }
        try {
            File f = exportFile("书架", "书架导出.txt", true);
            FileWriter w = new FileWriter(f);
            w.write(sb.toString());
            w.close();
            speakExportDone(f, n + " 本书架");
            devLogLine("书架", "导出 " + n + " 本");
        } catch (Throwable t) {
            speak("导出失败");
        }
    }

    /** 导入书架：只恢复当前这一架的类型，本地书只认还在的文件 */
    private void shelfImport(boolean net) {
        // 先找新的公有导出目录；没有就看私有里有没有老版本留下的那份
        File f = new File(exportDirOf("书架"), "书架导出.txt");
        if (!f.exists()) f = new File(DATA_DIR, "书架导出.txt");
        if (!f.exists()) { speak("没有找到书架导出文件"); return; }
        int n = 0;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            List<String> fav = readList("favList");
            List<String> nb = readList("nbFav");
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split("\t");
                if (p.length >= 2 && "local".equals(p[0])) {
                    if (net) continue;
                    if (new File(p[1]).exists() && !fav.contains(p[1])) { fav.add(0, p[1]); n++; }
                } else if (p.length >= 3 && "net".equals(p[0])) {
                    if (!net) continue;
                    String url = p[2].split("\u0001")[0];
                    boolean has = false;
                    for (String one : nb) { String[] q = one.split("\u0001"); if (q.length >= 2 && q[1].equals(url)) has = true; }
                    if (!has) { nb.add(p[1] + "\u0001" + p[2]); n++; }
                }
            }
            r.close();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fav.size(); i++) { if (i > 0) sb.append('\n'); sb.append(fav.get(i)); }
            StringBuilder nbSb = new StringBuilder();
            for (int i = 0; i < nb.size(); i++) { if (i > 0) nbSb.append('\n'); nbSb.append(nb.get(i)); }
            prefs.edit().putString("favList", sb.toString()).putString("nbFav", nbSb.toString()).apply();
        } catch (Throwable t) {
            speak("导入失败");
            return;
        }
        if (!stack.isEmpty()) stack.set(stack.size() - 1, shelfScreen(net));
        render(true);
        speak(n == 0 ? "没有新书可导入" : "已导入 " + n + " 本");
    }
    /** 导出日志到公有目录：日志本体留在私有目录，导出的副本给用户看/发人 */
    private void exportLog() {
        try {
            File src = new File(DATA_DIR, "无障碍日志.txt");
            if (!src.exists()) { speak("还没有日志文件"); return; }
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.CHINA)
                    .format(new java.util.Date());
            File out = exportFile("日志", "无障碍日志-" + stamp + ".txt", false);
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.OutputStream fo = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.flush();
            in.close();
            fo.close();
            speakExportDone(out, "日志");
            devLogLine("日志", "导出 " + out.getAbsolutePath() + "　" + out.length() + " 字节");
        } catch (Throwable t) {
            speak("导出日志失败：" + (t.getMessage() == null ? "写不进去" : t.getMessage()));
        }
    }

    /**
     * 分享日志：走系统分享面板，直接发到 QQ / 微信 / 邮箱。
     * 给服务器连不上、或者不会用文件管理器找文件的测试者兜底。
     * 用 ApkProvider 的 content:// 通道，Android 7 起 file:// 会被系统直接拒。
     */
    private void shareLog() {
        try {
            File out = new File(DATA_DIR, "无障碍日志.txt");
            if (!out.exists() || out.length() == 0) { speak("还没有日志可以分享"); return; }
            // 直接分享私有目录里那份：ApkProvider 对 getFilesDir() 下的文件放行，
            // 公有导出目录「盲界阅读导出」不在放行范围，复制过去反而会被判越权路径
            android.net.Uri uri = android.net.Uri.parse(
                    "content://" + ApkProvider.authorityOf(this) + out.getAbsolutePath());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "盲界阅读日志 " + appVersion() + "　" + deviceTag());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "把日志发到哪里"));
            speak("选择要把日志发到哪里");
            devLogLine("日志", "分享 " + out.getAbsolutePath() + "　" + out.length() + " 字节");
        } catch (Throwable t) {
            speak("分享日志失败：" + (t.getMessage() == null ? "打不开分享面板" : t.getMessage()));
        }
    }

    private Screen shelfLogScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("刷新日志", "shelf:log"));
        File f = new File(DATA_DIR, "无障碍日志.txt");
        if (f.exists()) {
            try {
                String all = new String(readFile(f), "UTF-8");
                String[] ls = all.split("\n");
                int from = Math.max(0, ls.length - 40);
                if (ls.length == 0 || (ls.length == 1 && ls[0].trim().isEmpty())) {
                    items.add(new Item("（日志是空的）"));
                } else {
                    for (int i = from; i < ls.length; i++) {
                        String t = ls[i];
                        if (t.length() > 40) t = t.substring(0, 40);
                        items.add(new Item(t));
                    }
                }
            } catch (Throwable t) {
                items.add(new Item("（日志读取失败）"));
            }
        } else {
            items.add(new Item("（还没有日志文件）"));
        }
        items.add(new Item("导出日志到存储卡", "shelf:logexport"));
        items.add(new Item("返回上一层", "nav:back"));
        return new Screen("日志", items).k("shelfLog");
    }
    private Screen shelfManageScreen(int cur) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("内存", "f:eb:internal"));
        items.add(new Item("存储卡", "f:eb:sd"));
        items.add(new Item("网上缓存", "f:eb:cache"));
        items.add(new Item("缓存管理", "cachemgr"));
        Screen s = new Screen("管理", items).k("shelfManage");
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    /** 当前分组里的书 */
    private Screen srcMarkScreen() {
        ArrayList<Item> arrayList = new ArrayList<Item>();
        arrayList.add(new Item("已选 " + this.srcMarkSel.size() + " 个，点书名勾选或取消", "sec"));
        int n = this.prefs.getInt("srcFilterType", -1);
        int n2 = this.prefs.getInt("srcFilterOn", -1);
        for (int i = 0; i < this.nb.nbSources.size(); ++i) {
            JSONObject jSONObject = this.nb.nbSources.get(i);
            if (n >= 0 && this.nb.srcType(jSONObject) != n || n2 == 1 && !this.nb.srcEnabled(jSONObject) || n2 == 0 && this.nb.srcEnabled(jSONObject)) continue;
            String string = jSONObject.optString("bookSourceName", "未命名");
            boolean bl = this.srcMarkSel.contains(string);
            arrayList.add(new Item((bl ? "已选 " : "未选 ") + string, "bsrc:marksel:" + i));
        }
        if (this.nb.nbSources.isEmpty()) {
            arrayList.add(new Item("（空）"));
        }
        arrayList.add(new Item("导出已选 " + this.srcMarkSel.size() + " 个", "bsrc:expmark"));
        arrayList.add(new Item("清空选择", "bsrc:markclear"));
        arrayList.add(new Item("返回上一层", "nav:back"));
        return new Screen("勾选书源", arrayList).k("bsrcMark");
    }
    private Screen srcRuleEditScreen() {
        ArrayList<Item> arrayList = new ArrayList<Item>();
        int n = this.srcEditIdx;
        if (n < 0 || n >= this.nb.nbSources.size()) {
            arrayList.add(new Item("这个书源已经不在列表里了"));
            arrayList.add(new Item("返回上一层", "nav:back"));
            return new Screen("填规则", arrayList).k("bsrcEdit");
        }
        JSONObject jSONObject = this.nb.nbSources.get(n);
        arrayList.add(new Item("正在改：" + jSONObject.optString("bookSourceName", "未命名"), "sec"));
        for (int i = 0; i < SRC_FIELDS.length; ++i) {
            String string;
            String string2 = SRC_FIELDS[i][1];
            String string3 = this.nb.getSourceField(n, string2);
            if (string3 == null) {
                string3 = "";
            }
            string = string3.isEmpty() ? "（未填）" : string3;
            if (string.length() > 40) {
                string = string.substring(0, 40) + "…";
            }
            arrayList.add(new Item(SRC_FIELDS[i][0] + "：" + string, "bsrc:fld:" + i));
        }
        arrayList.add(new Item("规则写法：CSS 选择器或 @js: 脚本，多个取值用 @ 或 ## 分隔", "none"));
        arrayList.add(new Item("返回书源详情", "nav:back"));
        return new Screen("填规则", arrayList).k("bsrcEdit");
    }








    

    /** 阅读记录：统计与两个总览合成一页，不再夹一层「详细阅读统计」 */
    /** 阅读统计已搬到 reader/StatsScreen，主类只留转发与接线 */
    private StatsScreen stats;
    private StatsScreen stats() {
        if (stats == null) stats = new StatsScreen(new StatsScreen.Host() {
            @Override public android.content.SharedPreferences prefs() { return prefs; }
            @Override public android.os.Handler ui() { return ui; }
            @Override public boolean isReadingNow() {
                Screen t = stack.isEmpty() ? null : top();
                return t != null && t.reader && (autoPlay || readingNow);
            }
            @Override public String currentBookPath() { return bookPath; }
        });
        return stats;
    }

    private Screen recordsScreen() { return stats().recordsScreen(); }

    private Screen readerVoicePanelScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("语音引擎：" + rdEngineLabel(), "rv:engine"));
        items.add(new Item("发音人：" + rdVoiceLabel(), "rv:voice"));
        items.add(new Item("语速：" + readerVoice.rateP + "%", "rv:rate"));
        items.add(new Item("语调：" + readerVoice.pitchP + "%", "rv:pitch"));
        items.add(new Item("语速加倍：" + com.mangjie.reader.speech.VoiceProfile.mulLabel(readerVoice.speedMul) + "", "rv:double"));
        items.add(new Item("试听", "rv:test"));

        Screen sc = new Screen("语音正文", items);
        sc.voiceCtl = true;
        sc.adjustRate = 2;        // 语速行
        sc.adjustPitch = 3;       // 语调行
        return sc;
    }

    private void refreshVoicePanel(Screen sc) {
        if (sc == null || !sc.voiceCtl) return;
        List<Item> items = new ArrayList<>();
        items.add(new Item("语音引擎：" + rdEngineLabel(), "rv:engine"));
        items.add(new Item("发音人：" + rdVoiceLabel(), "rv:voice"));
        items.add(new Item("语速：" + readerVoice.rateP + "%", "rv:rate"));
        items.add(new Item("语调：" + readerVoice.pitchP + "%", "rv:pitch"));
        items.add(new Item("语速加倍：" + com.mangjie.reader.speech.VoiceProfile.mulLabel(readerVoice.speedMul) + "", "rv:double"));
        items.add(new Item("试听", "rv:test"));
        sc.items = items;
        sc.adjustRate = 2;        // 语速行
        sc.adjustPitch = 3;       // 语调行
    }


    /** 一类缓存，两段都列在这一页：章节缓存（阅读时自动存）与旧版整本缓存 */
    private Screen cacheManageScreen() {
        List<Item> items = new ArrayList<>();

        // 第一段：章节缓存 netcache/<书>/章节.txt
        File croot = com.mangjie.reader.net.ChapterCache.root(DATA_DIR);
        long csize = 0;
        int cbooks = 0;
        File[] cdirs = croot.exists() ? croot.listFiles() : null;
        if (cdirs != null) {
            for (File d : cdirs) {
                if (!d.isDirectory()) continue;
                cbooks++;
                csize += sizeOfDir(d);
            }
        }
        items.add(new Item("【章节缓存】阅读时自动存，移出收藏或最近才清", "sec"));
        items.add(new Item(cbooks == 0 ? "（章节缓存是空的）" : "共 " + cbooks + " 本，占 " + sizeText(csize), "none"));
        if (cbooks > 0) items.add(new Item("清空全部章节缓存", "nb:cacheallclear"));

        // 第二段：旧版整本缓存 `网上缓存/`
        File dir = new File(DATA_DIR, "网上缓存");
        File[] fs = dir.exists() ? dir.listFiles() : null;
        long total = 0;
        int n = 0;
        items.add(new Item("【旧版整本缓存】", "sec"));
        if (fs != null) {
            Arrays.sort(fs, new Comparator<File>() {
                @Override public int compare(File a, File b) { return b.length() > a.length() ? 1 : (b.length() == a.length() ? 0 : -1); }
            });
            for (File f : fs) {
                if (!f.isFile()) continue;
                total += f.length();
                items.add(new Item(f.getName() + "　" + (f.length() / 1024) + " KB", "book:" + f.getAbsolutePath()));
                n++;
            }
        }
        if (n == 0) items.add(new Item("（旧版整本缓存是空的）"));
        else items.add(new Item("共 " + n + " 本，占 " + sizeText(total), "none"));
        if (n > 0) items.add(new Item("清空旧版整本缓存", "cache:clearall"));
        return new Screen("缓存管理", items);
    }

    private long sizeOfDir(File d) {
        long sum = 0;
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        for (File f : fs) sum += f.isDirectory() ? sizeOfDir(f) : f.length();
        return sum;
    }

    private String sizeText(long b) {
        if (b >= 1024L * 1024L) return (b / 1024L / 1024L) + " MB " + (b / 1024L % 1024L) + " KB";
        return (b / 1024L) + " KB";
    }

    private void cacheDeleteAll() {
        File dir = new File(DATA_DIR, "网上缓存");
        File[] fs = dir.exists() ? dir.listFiles() : null;
        if (fs != null) for (File f : fs) if (f.isFile()) f.delete();
        devLogLine("缓存", "已清空网上缓存");
        stack.remove(stack.size() - 1);
        stack.add(cacheManageScreen());
        render(true);
        speak("缓存已清空");
    }

    /** 网络书的目录页（倒序开关重建时用，不重新抓网络） */


    private Screen bookmarkOverviewScreen() {
        List<Item> items = new ArrayList<>();
        List<String> paths = new ArrayList<>(readList("recentList"));
        for (String p2 : readList("favList")) if (!paths.contains(p2)) paths.add(p2);
        int books = 0;
        for (String path : paths) {
            File f = new File(path);
            if (!f.exists()) continue;
            List<Integer> bms = com.mangjie.reader.reader.Bookmarks.parse(
                    prefs.getString(com.mangjie.reader.reader.BookProgress.bmKey(path), ""));
            if (bms.isEmpty()) continue;
            items.add(new Item(f.getName() + "　" + bms.size() + " 个书签", "bookbm:" + path));
            books++;
        }
        if (books == 0) items.add(new Item("（还没有书签）"));
        return new Screen("书签总览", items);
    }

    private int verTapCount = 0;
    private long verTapLast = 0L;

    private void tapVersionItem() {
        long now = System.currentTimeMillis();
        if (now - verTapLast > 4000) verTapCount = 0;
        verTapLast = now;
        verTapCount++;
        devLogLine("关于", "点版本号 " + verTapCount + "/10");
        if (verTapCount >= 10) {
            verTapCount = 0;
            String word = devMode ? "开发者模式已关闭，开发者选项已隐藏" : "开发者模式已开启";
            devMode = !devMode;
            prefs.edit().putBoolean("devMode", devMode).apply();
            stack.set(stack.size() - 1, aboutScreen());
            render(true);
            speak(word);
            return;
        }
        int left = 10 - verTapCount;
        if (left <= 3) speak("再点 " + left + " 次" + (devMode ? "关闭" : "开启") + "开发者模式");
        else speak("已点击 " + verTapCount + " 次，共 10 次");
    }

    private Screen aboutScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("名称  盲界阅读"));
        items.add(new Item("版本  v" + appVersion(), "about:ver"));
        items.add(new Item("版权  © 盲界软件"));
        items.add(new Item("更新日志", "go:changelog"));
        items.add(new Item("系统信息", "go:sysinfo"));
        items.add(new Item("软件升级", "go:update"));
        if (devMode) items.add(new Item("开发者", "go:dev"));
        return new Screen("关于", items).k("about");
    }



    // ================================================================ 更新日志
    /** 版本 / 该版改动（一行一条）。以后每发一版，在最上面加一组，别改旧条目 */
    private static final String CHANGELOG_CACHE = DATA_DIR + "/更新日志.txt";
    private static final String CHANGELOG_URL_PATH = "changelog.txt";             // 相对路径，走 getAppAny：先新目录再旧根目录
    private static final String CHANGELOG_URL_PATH_OLD =
            "日志/更新日志/更新日志.txt";                                            // 老位置，新目录读不到时兜底
    private List<String[]> chgEntries = new ArrayList<>();

    /** 解析：一行版本号开头，其余非空行算这条版本下的说明 */


    /** 联网拉更新日志，成功后写本地缓存；失败保留旧缓存 */

    private Screen changelogScreen() {
        chgEntries = upd.loadChangelog();
        List<Item> items = new ArrayList<>();
        items.add(new Item("联网刷新更新日志", "chg:refresh"));
        if (chgEntries.isEmpty()) items.add(new Item("（还没有更新日志，点上面那项联网拉取）"));
        for (int i = 0; i < chgEntries.size(); i++) {
            String first = chgEntries.get(i)[1].split("\n")[0];
            items.add(new Item(chgEntries.get(i)[0] + "　" + first, "chg:" + i));
        }
        return new Screen("更新日志", items);
    }


    // ================================================================ 旧版残留检查
    /** 旧包的包名：只是用来判断「还装不装着」，界面上不出现旧品牌 */
    private static final String LEGACY_PKG = "com.sunshine.s918";
    private static final String LEGACY_DIR = "/sdcard/阳光听书郎";

    private boolean legacyPromptPending = false;

    /** 主菜单入栈之后再弹提示，顺序不能反 */
    private void showLegacyPromptIfAny() {
        if (!legacyPromptPending) return;
        legacyPromptPending = false;
        List<Item> items = new ArrayList<>();
        items.add(new Item("检测到旧版应用还装在本机"));
        items.add(new Item("卸载旧版", "legacy:uninstall"));
        items.add(new Item("先不处理", "legacy:skip"));
        stack.add(new Screen("旧版清理", items));
        render(true);
        speak("检测到旧版应用，建议卸载，旧数据会一起清掉");
    }

    private boolean legacyInstalled() {
        try {
            getPackageManager().getPackageInfo(LEGACY_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 启动时检查旧版残留：旧版本地数据目录能删就删；旧包还装着就提示去卸载 */
    private void checkLegacyData() {
        boolean hadDir = false;
        try {
            File old = new File(LEGACY_DIR);
            if (old.exists()) {
                hadDir = true;
                boolean ok = deleteTree(old);
                devLogLine("清理", "旧版外置目录" + (ok ? "已删除" : "删除失败") + "：" + LEGACY_DIR);
            }
        } catch (Exception e) {
            devLogLine("清理", "旧版目录处理异常：" + e);
        }
        boolean legacy = legacyInstalled();
        devLogLine("清理", "旧包已安装=" + legacy + "　旧目录存在=" + hadDir);
        if (!legacy && !hadDir) return;
        if (legacy && prefs.getBoolean("legacySkip", false)) return;
        if (legacy) {
            legacyPromptPending = true;
        } else {
            speak("已清理旧版残留数据");
        }
    }

    private void uninstallLegacy() {
        try {
            Intent it = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + LEGACY_PKG));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            devLogLine("清理", "打开旧版卸载确认页");
        } catch (Exception e) {
            toast("打不开卸载页：" + e.getMessage());
            speak("打不开系统的卸载页，请到系统设置里卸载旧版");
        }
    }

    private boolean deleteTree(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteTree(c);
        }
        return f.delete();
    }

    /** 本机厂商 + 型号，如 TCL T508N */

    private String screenDesc() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        return w + " × " + h;
    }

    /** 应用自身的版本号，取自系统记录的 versionName */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    // ---------------------------------------------------------------- 设置：13 项

    /** 手势引擎：按设置挑一套实现，两套对外行为一致 */
    private com.mangjie.reader.gesture.GestureEngine makeGestureEngine() {
        com.mangjie.reader.gesture.TouchGestures.Listener l =
                new com.mangjie.reader.gesture.TouchGestures.Listener() {
                    @Override public void onGesture(String id) { scrollCarry = 0f; runGesture(id); }
                    @Override public void onTwoFingerScroll(float dy) { followScroll(dy); }
                };
        com.mangjie.reader.gesture.GestureEngine eng;
        if (com.mangjie.reader.gesture.TalkBackGestures.ENGINE.equals(gestureEngineKey())) {
            com.mangjie.reader.gesture.TalkBackGestures tb =
                    new com.mangjie.reader.gesture.TalkBackGestures(this, l);
            // 系统版本不够时悄悄退回自写引擎，不能让它变成「手势全哑」
            if (tb.available()) {
                eng = tb;
            } else {
                devLogLine("手势", "系统版本不够，退回盲界自写引擎");
                com.mangjie.reader.gesture.GestureEngine self =
                        new com.mangjie.reader.gesture.TouchGestures(this, l);
                eng = self;
            }
        } else {
            eng = new com.mangjie.reader.gesture.TouchGestures(this, l);
        }
        return eng;
    }

    /** 当前手势引擎 key */
    private String gestureEngineKey() {
        return prefs.getString("gestureEngine", com.mangjie.reader.gesture.TalkBackGestures.ENGINE_SELF);
    }

    /** 当前手势方案 key */
    private String gestureSchemeKey() {
        return prefs.getString("gestureScheme", com.mangjie.reader.gesture.GestureScheme.MANGJIE);
    }

    private Screen setGestureScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("手势方案：" + com.mangjie.reader.gesture.GestureScheme.schemeName(gestureSchemeKey()), "go:gesturescheme"));
        // 帮助是设置页的全局入口，这里别再来一条：两页各有一个「帮助」，看着像两个不一样的东西
        items.add(new Item("双指手势：" + (gestureOn ? "已开启" : "已关闭"), "go:gesturetoggle"));
        items.add(new Item("手势引擎：" + com.mangjie.reader.gesture.TalkBackGestures.engineName(gestureEngineKey()), "go:gestureengine"));
        return new Screen("手势", items).k("gesture");
    }

    private Screen gestureSchemeScreen() {
        List<Item> items = new ArrayList<>();
        String cur = gestureSchemeKey();
        for (int i = 0; i < com.mangjie.reader.gesture.GestureScheme.KEYS.length; i++) {
            String k = com.mangjie.reader.gesture.GestureScheme.KEYS[i];
            items.add(new Item(com.mangjie.reader.gesture.GestureScheme.NAMES[i] + (k.equals(cur) ? "　✔" : ""), "gsch:" + k));
        }
        Screen s = new Screen("手势方案", items).k("gestureScheme");
        for (int i = 0; i < com.mangjie.reader.gesture.GestureScheme.KEYS.length; i++)
            if (com.mangjie.reader.gesture.GestureScheme.KEYS[i].equals(cur)) s.cur = i + 1;
        return s;
    }

    /** 逐条列出当前方案每个手势干什么，读屏用户一行一行摸 */
    private Screen gestureHelpScreen() {
        List<Item> items = new ArrayList<>();
        String scheme = gestureSchemeKey();
        items.add(new Item("读屏开着的时候，这些手势归读屏管，应用不抢。", "sec"));
        items.add(new Item("这些手势只在应用里生效，做手势不会跳回桌面。", "sec"));
        items.add(new Item("上下滑动只换焦点，不改数值。", "sec"));
        items.add(new Item("左右滑动看停在哪一行：停在能调的行上，右滑加、左滑减；停在普通行上，就是下一项、上一项。", "sec"));
        items.add(new Item("能调数值的行有这些：语速、语调、语速加倍、音效反馈音量、震动强度，还有各种开关。", "sec"));
        items.add(new Item("单击一次往前一档，到头自动回到第一档，可以一直循环着调", "sec"));
        items.add(new Item("调完立刻生效并念出当前值", "sec"));
        items.add(new Item("键盘机：左右方向键调值，上下方向键换焦点，回车进输入框，加减号快速加减", "sec"));
        for (String id : com.mangjie.reader.gesture.GestureScheme.GESTURES) {
            String act = com.mangjie.reader.gesture.GestureScheme.action(prefs, scheme, id);
            String meaning = com.mangjie.reader.gesture.GestureScheme.actionName(act);
            if (com.mangjie.reader.gesture.GestureScheme.NONE.equals(act)) meaning = "未指定";
            items.add(new Item(com.mangjie.reader.gesture.GestureScheme.gestureName(id) + "　" + meaning,
                    "help:" + id));
        }
        items.add(new Item("四条拐角手势（先下再左、先上再左、先上再右、先下再右）　未指定", "sec"));
        Screen s = new Screen("手势说明", items).k("gestureHelp");
        s.cur = 1;
        return s;
    }

    /** 帮助首页：手势规则与界面说明各一个入口，说明文字不再散落在各页行名里 */
    /** 帮助各节：每条第一项是节名，其后是正文行 */
    private static final String[][] HELP_SECTIONS = {
            {"手势怎么呼出菜单",
             "书源页：一根手指先往左滑，再往右滑，菜单就出来了。",
             "菜单里有筛选、排序、批量校验，还有导入和导出。",
             "订阅页同样这么滑，菜单里能换分组、看全部最新、看收藏。",
             "目录页同样这么滑，菜单里能收藏、换源、缓存、倒序、填过滤词。",
             "书架不用叫菜单，左右滑动就换分组。",
             "接了键盘，上下键走行，左右键换分组，数字键直接跳到第几项。",
            },
            {"朗读和阅读",
             "阅读页叫菜单有三种办法：先左滑再右滑、长按、按菜单键。",
             "朗读暂停以后再继续，不会跳句，从停下的那一句接着念。",
             "点哪一句就念哪一句，光标条跟着走。",
            },
            {"书源",
             "书源在应用私有目录里，不再需要往文件夹里拷，用「导入书源」选文件就行。",
             "能用开源阅读导出的书源，也就是那种 JSON 文件。",
             "分组看源文件里的一个字段，字段名叫 bookSourceGroup，写多个分组用逗号隔开。",
             "自己新建的源只有名字和地址，规则要进书源详情里一项一项填。",
             "要填八项：搜索地址、搜索列表、书名、书链、目录列表、章名、章链、正文。",
             "搜索地址里放关键词的位置，写成两个花括号包住 key，也就是 {{key}}。",
             "校验书源在书源菜单里，能改关键词，也能改每个源的超时时间。",
             "五个开关分别管搜索、发现、详情、目录、正文，关掉哪一项就不查哪一项。",
             "校验照开源阅读的规矩：先拿关键词搜一本书，搜到了再看详情、目录、正文。",
             "有发现页的源，还会拿第一个分类再走一遍。",
             "哪一步没过，就给这个源打个标签，比如 搜索失效、发现失效、校验超时。",
             "标签写进这个源的分组字段，失败原因写进注释。",
             "下次再校验会先把旧标签擦掉，不会越堆越多。",
             "标签也能当分组用，选 按分组 就能把这些源挑出来集中处理。",
             "校验是一个源一个源跑的，一个源要发好几次请求，慢。先筛窄一点再跑。",
             "跑的过程能暂停、能继续、能停。",
             "导出可以全都导，也可以只导筛出来的，还能进勾选页挑着导。",
            },
            {"订阅源",
             "订阅源也在应用私有目录里，用「导入订阅源」选文件就行。",
             "能用开源阅读导出的订阅源 JSON。",
             "单个网页的源，点开直接交给浏览器看原网页。",
             "手机里没有浏览器时，退回来自己抓正文。",
             "想用应用里的朗读念网页，就用 打开这个源看内容 里面的抽取。",
            },
            {"备份和恢复",
             "备份一个 JSON 文件装走全部设置和四个规则目录；导出的那份在存储卡的 盲界阅读导出/备份 里。",
             "备份里不含服务器密码，也没有 password、token 这类东西。",
             "上传和取回要先在 服务器 里填好地址、账号、密码、目录，没填会直接提示。",
            },
            {"词典",
             "词库在词典页点「导入词库文件」，一行一条，txt、csv、tsv 都能用。",
             "分隔符认这几种：Tab、竖线、冒号、第一个空格。",
             "第一次打开词典会自动放一份内置常用词表，删了下次还会补回来。",
            },
            {"还原",
             "还原只动外观三项：配色、字体、语音引擎和发音人。",
             "语速和语调回到中间值，也就是 50。",
             "书、阅读进度、书源、订阅，还有别的设置，都不动。",
            },
            {"搜索",
             "精准搜索开着，书名和作者都要对得上。",
             "关着只要沾边就算，结果会多出很多。",
             "搜索范围能选全部书源，也能只看一个分组，或者只用某一个源。",
             "一次同时搜几个源，在 其他 里的搜索线程数里填。填多少跑多少，超过 9 就按 9 跑。",
            },
            {"阅读统计",
             "今天、累计、近七天这几行只是显示，点不动。",
             "每本书读到哪、读了多久，书架上就能看到。",
            },
    };

    private Screen helpScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("每种手势怎么用（当前是" + com.mangjie.reader.gesture.GestureScheme.schemeName(gestureSchemeKey()) + "方案）", "go:gesturehelp"));
        items.add(new Item("界面上那些名字是什么意思", "go:uihelp"));
        for (int i = 0; i < HELP_SECTIONS.length; i++) {
            items.add(new Item(HELP_SECTIONS[i][0], "help:sec:" + i));
        }
        return new Screen("帮助", items).k("help");
    }

    private Screen helpSectionScreen(int i) {
        List<Item> items = new ArrayList<>();
        String[] sec = HELP_SECTIONS[i];
        for (int k = 1; k < sec.length; k++) items.add(new Item(sec[k], "none"));
        items.add(new Item("返回帮助目录", "nav:back"));
        return new Screen(sec[0], items).k("helpSec");
    }

    private Screen interfaceHelpScreen() {
        Screen s = new Screen("界面说明", com.mangjie.reader.view.HelpDoc.interfaceItems()).k("uihelp");
        return s;
    }

    private void pickGestureScheme(String key) {
        prefs.edit().putString("gestureScheme", key).apply();
        speak("手势方案改为 " + com.mangjie.reader.gesture.GestureScheme.schemeName(key));
        Screen fresh = gestureSchemeScreen();
        if (stack.size() > 1) stack.set(stack.size() - 1, fresh); else stack.add(fresh);
        render(true);
    }

    private Screen settingsScreen() { return com.mangjie.reader.view.SettingsScreens.settings(); }

    private Screen setAppearanceScreen() { return com.mangjie.reader.view.SettingsScreens.appearance(setHost); }

    private Screen setReadScreen() { return com.mangjie.reader.view.SettingsScreens.read(setHost); }

    /** 设置几页的取值入口：页面定义搬到 view/SettingsScreens，状态仍留在主类 */
    private final com.mangjie.reader.view.SettingsScreens.Host setHost =
            new com.mangjie.reader.view.SettingsScreens.Host() {
        public android.content.SharedPreferences prefs() { return prefs; }
        public String fontName() { return FONT_NAMES[fontIdx]; }
        public String lineSpaceName() { return LINE_SPACE_NAMES[lineSpaceIdx]; }
        public String sidePadName() { return SIDE_PAD_NAMES[sidePadIdx]; }
        public String fontLabel() { return MainActivity.this.fontLabel(); }
        public String paletteName() { return palName(); }
        public boolean simplifyOn() { return simplifyOn; }
        public int searchThreads() { return MainActivity.this.searchThreads(); }
        public int searchThreadsRaw() { return prefs.getInt("searchThreads", 16); }
        public long subCacheSize() { return com.mangjie.reader.net.SubCache.sizeOf(DATA_DIR); }
    };

    /** 实际生效的搜索线程数：跟 Legado 一样，设置值封顶 9 */
    private int searchThreads() {
        return Math.max(1, Math.min(9, prefs.getInt("searchThreads", 16)));
    }

    private Screen setDataScreen() { return com.mangjie.reader.view.SettingsScreens.data(setHost); }

    /** 外观五项合成一页：原来散在设置各处，改一次要滑十几项 */
    private Screen appearanceScreen() { return com.mangjie.reader.view.SettingsScreens.appearance(setHost); }

    private Screen sfxScreen(int cur) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("音效反馈：" + (soundFx ? "已开启" : "已关闭"), "sfx:switch"));
        items.add(new Item("音效反馈音量：" + sfxVol + "%", "sfx:vol"));
        items.add(new Item("试听音效", "sfx:test"));
        Screen s = new Screen("音效反馈", items).k("sfx");
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    private String hapLevelName() {
        return hapLevel == 0 ? "弱" : (hapLevel == 2 ? "强" : "中");
    }

    private Screen hapticScreen(int cur) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("震动反馈：" + (hapticOn ? "已开启" : "已关闭"), "hap:switch"));
        items.add(new Item("震动强度：" + hapLevelName() + "", "hap:level"));
        items.add(new Item("试听震动", "hap:test"));
        if (!hasLinearMotor()) {
            items.add(new Item("使用线性马达：" + (useLinear ? "已开启" : "已关闭"), "hap:linear"));
        }
        Screen s = new Screen("震动反馈", items).k("haptic");
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    /** 反馈页：音效与震动放一处，开关、强度、试听都在 */
    private Screen feedbackScreen(int cur) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("音效反馈：" + (soundFx ? "已开启" : "已关闭"), "sfx:switch"));
        items.add(new Item("音效音量：" + sfxVol + "%", "sfx:vol"));
        items.add(new Item("试听音效", "sfx:test"));
        items.add(new Item("震动反馈：" + (hapticOn ? "已开启" : "已关闭"), "hap:switch"));
        items.add(new Item("震动强度：" + hapLevelName(), "hap:level"));
        items.add(new Item("试听震动", "hap:test"));
        if (!hasLinearMotor()) {
            items.add(new Item("使用线性马达：" + (useLinear ? "已开启" : "已关闭"), "hap:linear"));
        }
        Screen s = new Screen("反馈", items).k("fb");
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    private Screen hapLevelScreen() {
        List<Item> items = new ArrayList<>();
        String[] names = {"弱", "中", "强"};
        for (int i = 0; i < 3; i++) {
            items.add(new Item(names[i] + (hapLevel == i ? "　✔" : ""), "hapl:" + i));
        }
        Screen s = new Screen("震动强度", items).k("hapLevel");
        s.cur = hapLevel;
        return s;
    }

    private void handleHaptic(String what) {
        if ("switch".equals(what)) {
            hapticOn = !hapticOn;
            savePrefs();
            if (hapticOn) buzz(15);                        // 开时先震一下（buzz 本身不判开关）
            refreshTop("震动反馈已" + (hapticOn ? "开启" : "关闭"));
            return;
        }
        if ("level".equals(what)) { stack.add(hapLevelScreen()); render(true); return; }
        if ("linear".equals(what)) {
            useLinear = !useLinear;
            savePrefs();
            buzz(20);
            refreshTop("使用线性马达已" + (useLinear ? "开启" : "关闭"));
            return;
        }
        if ("test".equals(what)) {
            if (!hapticOn) { speak("震动反馈已关闭"); return; }
            buzz(20);
            speak("震动强度 " + hapLevelName());
            return;
        }
    }

    /** 音效音量四档：25% / 50% / 75% / 100% */
    private Screen sfxVolScreen() {
        List<Item> items = new ArrayList<>();
        int[] lv = {25, 50, 75, 100};
        for (int i = 0; i < lv.length; i++) {
            String label = lv[i] + "%" + (sfxVol == lv[i] ? "　✔" : "");
            items.add(new Item(label, "sfxv:" + lv[i]));
        }
        Screen s = new Screen("音效反馈音量", items).k("sfxVol");
        for (int i = 0; i < lv.length; i++) if (sfxVol == lv[i]) s.cur = i;
        return s;
    }

    private void handleSfx(String what) {
        if ("switch".equals(what)) {
            soundFx = !soundFx;
            savePrefs();
            lastSfxAt = 0;
            if (soundFx) sfx(android.media.ToneGenerator.TONE_PROP_ACK, 110);   // 开时给一声确认
            else { if (hapticOn) buzz(20); }                                    // 关时没音可放，用震动确认
            refreshTop("音效反馈已" + (soundFx ? "开启" : "关闭"));
            return;
        }
        if ("vol".equals(what)) {
            stack.add(sfxVolScreen());
            render(true);
            return;
        }
        if ("test".equals(what)) {
            lastSfxAt = 0;
            sfx(android.media.ToneGenerator.TONE_PROP_BEEP, 40);
            if (hapticOn) buzz(15);
            speak("音效反馈音量 " + sfxVol + "%");
            return;
        }
    }

    private Screen speechScreen(int cur) {
        List<Item> items = new ArrayList<>();
        if (!ttsReady) {
            items.add(new Item("没有可用的中文语音，点此去系统设置里启用或安装语音库", "sp:fix"));
        }
        items.add(new Item("语音引擎：" + engineLabel(), "sp:engine"));
        items.add(new Item("发音人：" + voiceLabel(), "sp:voice"));
        items.add(new Item("语速：" + menuVoice.rateP + "%", "sp:rate"));
        items.add(new Item("语调：" + menuVoice.pitchP + "%", "sp:pitch"));
        items.add(new Item("语速加倍：" + com.mangjie.reader.speech.VoiceProfile.mulLabel(menuVoice.speedMul) + "", "sp:double"));
        items.add(new Item("语音：" + (speechOn ? "已开启" : "已关闭"), "sp:switch"));
        items.add(new Item("试听", "sp:test"));
        Screen s = new Screen("语音菜单", items).k("speech");
        int base = ttsReady ? 0 : 1;      // 没有可用语音时最前面多一行提示
        s.adjustRate = 2 + base;
        s.adjustPitch = 3 + base;
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    private String engineLabel() {
        if (menuVoice.enginePkg == null) return "系统默认";
        List<EngineInfo> infos = tts == null ? null : tts.getEngines();
        if (infos != null) {
            for (EngineInfo i : infos) {
                if (menuVoice.enginePkg.equals(i.name)) {
                    return i.label == null || i.label.isEmpty() ? i.name : i.label;
                }
            }
        }
        return menuVoice.enginePkg;
    }

    private String voiceLabel() {
        if (menuVoice.voiceName != null) {
            if (zhVoices.isEmpty()) refreshZhVoices();
            if (zhVoices.isEmpty()) {
                // 语音列表还没就绪，或引擎只报语言代码：给出可读说明
                String lang = menuVoice.voiceName;
                if (lang.length() <= 3) {
                    String cn = "zh".equals(lang) ? "中文" : lang;
                    return cn + "（" + lang + "）";
                }
                return menuVoice.voiceName;
            }
            for (Voice v : zhVoices) {
                if (menuVoice.voiceName.equals(v.getName())) {
                    String loc = v.getLocale() == null ? "" : v.getLocale().getDisplayName();
                    return menuVoice.voiceName + (loc.isEmpty() ? "" : "（" + loc + "）")
                            + (v.isNetworkConnectionRequired() ? "·需联网" : "");
                }
            }
            return menuVoice.voiceName;
        }
        if (tts != null) {
            try {
                Voice v = tts.getVoice();
                if (v != null) {
                    String loc = v.getLocale() == null ? "" : v.getLocale().getDisplayName();
                    return "跟随系统" + (loc.isEmpty() ? "" : "（" + loc + "）");
                }
            } catch (Exception ignored) {}
        }
        return "跟随系统";
    }

    private String engineLabelOf(String pkg, String fallback) {
        try {
            List<EngineInfo> infos = tts == null ? null : tts.getEngines();
            if (infos != null) for (EngineInfo info : infos) {
                if (info.name != null && info.name.equals(pkg)) {
                    return info.label == null || info.label.isEmpty() ? pkg : info.label;
                }
            }
        } catch (Exception ignored) {}
        return fallback == null ? "" : fallback;
    }

    /** 枚举本机装着的语音引擎 */
    private Screen engineScreen(int cur) {
        List<Item> items = new ArrayList<>();
        String sysDef = null;
        try { if (tts != null) sysDef = tts.getDefaultEngine(); } catch (Exception ignored) {}
        String sysDefLabel = sysDef == null ? "" : engineLabelOf(sysDef, sysDef);
        items.add(new Item("系统默认（跟随系统设置" + (sysDefLabel.isEmpty() ? "" : "，当前是" + sysDefLabel) + "）"
                + (menuVoice.enginePkg == null ? "　✔" : ""), "eng:__default"));
        List<String> seen = new ArrayList<String>();
        try {
            List<EngineInfo> infos = tts == null ? null : tts.getEngines();
            if (infos != null) {
                for (EngineInfo info : infos) {
                    if (seen.contains(info.name)) continue;
                    seen.add(info.name);
                    String label = info.label == null || info.label.isEmpty() ? info.name : info.label;
                    String mark = (info.name.equals(menuVoice.enginePkg) ? "　✔ 已选" : "")
                            + (info.name.equals(sysDef) ? "　（系统默认引擎）" : "");
                    items.add(new Item(selfTag(label, info.name) + mark, "eng:" + info.name));
                }
            }
        } catch (Exception ignored) {}

        // 系统里声明的其它 TTS 服务也补进来（有些语音库不在 getEngines 名单里）
        try {
            android.content.Intent q = new android.content.Intent("android.intent.action.TTS_SERVICE");
            List<android.content.pm.ResolveInfo> ris = getPackageManager().queryIntentServices(q, 0);
            if (ris != null) {
                for (android.content.pm.ResolveInfo ri : ris) {
                    if (ri.serviceInfo == null) continue;
                    String pkg = ri.serviceInfo.packageName;
                    if (seen.contains(pkg)) continue;
                    seen.add(pkg);
                    String appName = "";
                    try {
                        appName = String.valueOf(getPackageManager()
                                .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
                    } catch (Exception ignored) {}
                    items.add(new Item(selfTag(appName.isEmpty() ? pkg : appName, pkg)
                            + (pkg.equals(menuVoice.enginePkg) ? "　✔" : ""), "eng:" + pkg));
                }
            }
        } catch (Exception ignored) {}
        if (items.size() <= 1) items.add(new Item("系统只装了一个语音引擎"));
        Screen s = new Screen("语音引擎", items);
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    /** 枚举当前引擎里的中文语音 */
    private List<Voice> engineVoices = new ArrayList<Voice>();
    private String voiceLoadedFor = null;
    private boolean voiceLoading = false;

    /** 朗读实际语速：百分比 × 加倍开关 */
    /**
     * 全局手势契约（定死，不再变）：
     *   上下滑 = 只换焦点，任何页面都不许改成调值；
     *   左右滑 = 焦点在可调行上调数值/切开关（右加左减），不在可调行上仍然换焦点。
     * 返回 true 表示这次左右滑被调值吃掉。
     */
    private static int sign(int delta) { return delta > 0 ? 1 : -1; }

    private boolean adjustFocusedRow(int delta) {
        Screen s = top();
        if (s == null || s.cur < 0 || s.cur >= s.items.size()) return false;
        String k = s.items.get(s.cur).key;
        if (k == null) return false;

        // 1) 语速 / 语调（语音正文与语音菜单各一套）
        if (s.adjustRate >= 0 && (s.cur == s.adjustRate || s.cur == s.adjustPitch)) {
            boolean isReader = s.voiceCtl;
            com.mangjie.reader.speech.VoiceProfile v = isReader ? readerVoice : menuVoice;
            String tag = isReader ? "rv" : "sp";
            if (s.cur == s.adjustRate) {
                int was = v.rateP;
                v.rateP = v.rateP + delta;                              // 到头环回：100 再往前回 0
                if (v.rateP > 100) v.rateP = 0; else if (v.rateP < 0) v.rateP = 100;
                s.items.set(s.cur, new Item("语速：" + v.rateP + "%", tag + ":rate"));
                savePrefs();
                applyVoiceRate(isReader);
                render(false);
                speak("语速 " + v.rateP + "%");
                return true;
            }
            int was = v.pitchP;
            v.pitchP = v.pitchP + delta;
            if (v.pitchP > 100) v.pitchP = 0; else if (v.pitchP < 0) v.pitchP = 100;
            s.items.set(s.cur, new Item("语调：" + v.pitchP + "%", tag + ":pitch"));
            savePrefs();
            applyVoiceRate(isReader);
            render(false);
            speak("语调 " + v.pitchP + "%");
            return true;
        }

        // 2) 音效反馈音量 0–100
        if ("sfx:vol".equals(k)) {
            int was = sfxVol;
            sfxVol = sfxVol + delta;
            if (sfxVol > 100) sfxVol = 0; else if (sfxVol < 0) sfxVol = 100;
            savePrefs();
            s.items.set(s.cur, new Item("音效反馈音量：" + sfxVol + "%", "sfx:vol"));
            render(false);
            speak("音效音量 " + sfxVol + "%");
            return true;
        }

        // 3) 震动强度：0 弱 / 1 中 / 2 强，一档一格
        if ("hap:level".equals(k)) {
            int was = hapLevel;
            hapLevel = ((hapLevel + sign(delta)) % 3 + 3) % 3;
            savePrefs();
            s.items.set(s.cur, new Item("震动强度：" + hapLevelName() + "", "hap:level"));
            render(false);
            speak("震动强度 " + hapLevelName());
            return true;
        }

        // 4) 语速加倍：1–5 倍，一档一格
        if ("rv:double".equals(k) || "sp:double".equals(k)) {
            boolean isReader = "rv:double".equals(k);
            com.mangjie.reader.speech.VoiceProfile v = isReader ? readerVoice : menuVoice;
            int was = v.speedMul;
            v.speedMul = (((v.speedMul - 1) + sign(delta)) % 5 + 5) % 5 + 1;
            savePrefs();
            s.items.set(s.cur, new Item("语速加倍："
                    + com.mangjie.reader.speech.VoiceProfile.mulLabel(v.speedMul) + "", k));
            applyVoiceRate(isReader);
            render(false);
            speak("语速加倍，" + v.speedMul + " 倍");
            return true;
        }

        // 5) 开关类：左右滑切开关，直接复用原来的点按逻辑（播报也由它出）
        if ("go:gesturetoggle".equals(k) || "sfx:switch".equals(k) || "hap:switch".equals(k)
                || "hap:linear".equals(k) || "sp:switch".equals(k) || "night:toggle".equals(k)
                || "dev:a11y".equals(k) || "dev:update".equals(k)) {
            activate();
            return true;
        }

        // 6) 整套预设配色：左右滑成套切换
        if (k.startsWith("pick:color:")) {
            int i = parseIntSafe(k.substring(11), paletteIdx < 0 ? 0 : paletteIdx);
            int v = Math.max(0, Math.min(PAL_NAMES.length - 1, i + (delta > 0 ? 1 : -1)));
            paletteIdx = v;
            clearCustomOverlay();
            savePrefs();
            refreshPal(top().cur);
            render(true);
            speak("配色 " + PAL_NAMES[v]);
            return true;
        }

        // 7) 主题行：光标条通道 / 形状 / 不透明度 / 渐变 / 开关
        if (k.startsWith("hl:") || k.startsWith("bg:") || k.startsWith("fg:")) {
            return adjustColorRow(k, delta);
        }
        return false;
    }

    /** 点按主题与配色页的各类行 */
    /** 配色改完：按当前停在哪一页重建（阅读配色页或自定义配色页） */
    private void refreshPal(int cur) {
        Screen t = top();
        String k = (t == null || t.key == null) ? "" : t.key;
        if ("readcolor".equals(k)) {
            Screen n = readColorScreen(cur);
            stack.set(stack.size() - 1, n);
        } else if ("themecolor".equals(k)) {
            Screen n = themeColorScreen(cur);
            stack.set(stack.size() - 1, n);
        } else {
            Screen n = readColorScreen(cur);
            stack.set(stack.size() - 1, n);
        }
    }

    private void handleThemeKey(String k) {
        Screen s = top();
        if ("tc:try".equals(k)) {
            speak("这是当前配色的样例：盲界阅读，让文字自己念出来。");
            return;
        }
        if ("tc:reset".equals(k)) {
            boolean had = prefs.getBoolean("cusOn", false);
            clearCustomOverlay();
            savePrefs();
            refreshPal(top().cur);
            render(true);
            speak(had ? ("已恢复主题，" + palName()) : ("现在就是主题原色，" + palName()));
            return;
        }
        if (k.startsWith("hl:c")) {
            int i = parseIntSafe(k.substring(4), -1);
            if (i >= 0 && i < HL_PRESET.length) {
                com.mangjie.reader.view.ThemeColors.setHlColor(prefs, HL_PRESET[i]);
                invalidateThemeCache();
                refreshHl(s.cur);
                render(true);
                speak("光标条，" + HL_PRESET_NAMES[i] + "，" + com.mangjie.reader.view.ColorName.describe(HL_PRESET[i]));
                return;
            }
        }
        if ("hl:pick".equals(k)) {          // 点按＝顺序切下一个预设，键盘机也能一档一档走
            int c = hlColor();
            int idx = -1;
            for (int i = 0; i < HL_PRESET.length; i++) {
                if ((c & 0xFFFFFF) == (HL_PRESET[i] & 0xFFFFFF)) idx = i;
            }
            idx = (idx + 1) % HL_PRESET.length;
            com.mangjie.reader.view.ThemeColors.setHlColor(prefs, HL_PRESET[idx]);
            invalidateThemeCache();
            refreshHl(s.cur);
            render(true);
            speak("光标条，" + HL_PRESET_NAMES[idx]);
            return;
        }
        if ("hl:shape".equals(k) || "hl:alpha".equals(k) || "hl:grad".equals(k)
                || "hl:gloss".equals(k) || "hl:shadow".equals(k)) {
            adjustColorRow(k, 5);          // 点按等于往前走一格
            return;
        }
        if (k.startsWith("bg:") || k.startsWith("fg:")) {
            speak("这一行左右滑调数值");
            return;
        }
        speak("未知操作");
    }

    /** 语速/语调改动后立刻让引擎生效（语音正文与语音菜单各一套） */
    private void applyVoiceRate(boolean isReader) {
        rdCapTold = false;                 // 语速改了，封顶提示重新给一次
        try {
            if (isReader) {
                if (rdTts != null) {
                    rdTts.setSpeechRate(rdEffRate());
                    rdTts.setPitch(mapFactor(readerVoice.pitchP));
                }
            } else {
                applySpeechParams();
            }
        } catch (Throwable ignored) {}
    }

    /** 语速加倍倍数：先读新键（int），旧版本存的是布尔，读到就当 2 倍 */
    private int readSpeedMul(String key, String oldBoolKey) {
        int m = 1;
        try {
            m = prefs.getInt(key, 1);
        } catch (Throwable t) {
            try { if (prefs.getBoolean(oldBoolKey, false)) m = 2; } catch (Throwable ignored) {}
        }
        if (m < 1) m = 1;
        if (m > 5) m = 5;
        return m;
    }

    /** 语速加倍选择页：1–5 倍，当前档前面带 ★ */
    private Screen speedMulScreen(final com.mangjie.reader.speech.VoiceProfile v, final String which) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("当前：" + com.mangjie.reader.speech.VoiceProfile.mulLabel(v.speedMul)));
        for (int i = 1; i <= 5; i++) {
            items.add(new Item((i == v.speedMul ? "★ " : "") + com.mangjie.reader.speech.VoiceProfile.mulLabel(i),
                    "spmul:" + which + ":" + i));
        }
        items.add(new Item("返回", "nav:back"));
        Screen sc = new Screen("语速加倍", items).k("spmul");
        sc.cur = Math.max(0, v.speedMul);      // 焦点落在当前档
        return sc;
    }

    private static final float RD_RATE_MAX = 2.5f;   // 超过这个倍率多数引擎不接受
    private boolean rdCapTold = false;

    private float rdEffRate() {
        float f = com.mangjie.reader.speech.VoiceMath.effective(readerVoice.rateP, readerVoice.speedMul);
        if (f > RD_RATE_MAX) {
            if (!rdCapTold) {
                rdCapTold = true;
                devLogLine("语音", "倍率 " + String.format(Locale.US, "%.2f", f) + " 超过上限，夹到 "
                        + RD_RATE_MAX + "（再快引擎就不出声了）");
                speak("朗读倍率已封顶，" + String.format(Locale.US, "%.1f", RD_RATE_MAX) + " 倍");
            }
            return RD_RATE_MAX;
        }
        return f;
    }

    private String rdVoiceLabel() {
        return readerVoice.voiceName == null ? "跟随系统" : readerVoice.voiceName;
    }

    private String rdEngineLabel() {
        if (readerVoice.enginePkg == null) return "跟随系统";
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(readerVoice.enginePkg, 0);
            return String.valueOf(getPackageManager().getApplicationLabel(ai));
        } catch (Exception e) { return readerVoice.enginePkg; }
    }

    /** 从 getEngines 里取某个包的显示名，取不到返回空串 */
    private String engineDisplayName(String pkg) {
        try {
            if (tts == null) return "";
            List<EngineInfo> infos = tts.getEngines();
            if (infos == null) return "";
            for (EngineInfo info : infos) {
                if (info.name.equals(pkg)) return info.label == null ? "" : info.label;
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * 语音版自带的离线引擎在菜单里加「（内置离线）」后缀：
     * 读屏朗读时能直接听出这一项不联网、不依赖别的语音引擎。
     * 普通版没有 TTS 服务，永远走不到这个分支。
     */
    private String selfTag(String label, String pkg) {
        if (label == null) label = "";
        if (pkg == null || !pkg.equals(getPackageName())) return label;
        return label + "（内置离线）";
    }

    private Screen readerEngineScreen(int cur) {
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("跟随系统" + (readerVoice.enginePkg == null ? "　✔" : ""), "reng:__default"));
        List<String> seen = new ArrayList<String>();
        // 1) 先扫系统里声明了 TTS 服务的包（最可靠，装了就能看到）
        try {
            android.content.Intent q = new android.content.Intent("android.intent.action.TTS_SERVICE");
            List<android.content.pm.ResolveInfo> ris = getPackageManager().queryIntentServices(q, 0);
            if (ris != null) {
                for (android.content.pm.ResolveInfo ri : ris) {
                    if (ri.serviceInfo == null) continue;
                    String pkg = ri.serviceInfo.packageName;
                    if (seen.contains(pkg)) continue;
                    seen.add(pkg);
                    String appName = "";
                    try {
                        appName = String.valueOf(getPackageManager().getApplicationLabel(
                                getPackageManager().getApplicationInfo(pkg, 0)));
                    } catch (Exception ignored) {}
                    String label = appName.isEmpty() ? pkg : appName;
                    String eng = engineDisplayName(pkg);
                    if (!eng.isEmpty() && !eng.equals(label)) label = label + "（" + eng + "）";
                    items.add(new Item(selfTag(label, pkg) + (pkg.equals(readerVoice.enginePkg) ? "　✔" : ""), "reng:" + pkg));
                }
            }
        } catch (Exception ignored) {}
        // 2) 再补 getEngines 里报出来、但上面没扫到的
        try {
            List<EngineInfo> infos = tts == null ? null : tts.getEngines();
            if (infos != null) {
                for (EngineInfo info : infos) {
                    if (seen.contains(info.name)) continue;
                    seen.add(info.name);
                    String label = info.label == null || info.label.isEmpty() ? info.name : info.label;
                    items.add(new Item(selfTag(label, info.name) + (info.name.equals(readerVoice.enginePkg) ? "　✔" : ""), "reng:" + info.name));
                }
            }
        } catch (Exception ignored) {}
        if (items.size() <= 1) items.add(new Item("系统只装了一个语音引擎"));
        Screen s = new Screen("朗读语音库", items);
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    /** 阅读发音人：用临时实例问阅读引擎有哪些中文语音，init 完成后再取，避免拿到空列表 */
    private void loadRdVoices(String pkg) {
        voiceLoading = true;
        final String key = "rd:" + (pkg == null ? "__default" : pkg);
        try {
            if (pkg == null) {
                // 跟随系统：直接用现有的主实例问语音，不再新建，避免等不到 init 回调
                ui.post(new Runnable() { @Override public void run() { collectVoices(tts, key); } });
                return;
            }
            TextToSpeech.OnInitListener l = new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
            devLogLine("语音", "界面语音 init 返回 " + status + (status == TextToSpeech.SUCCESS ? "（可用）" : "（不可用）"));
                    if (status != TextToSpeech.SUCCESS) {
                        voiceLoading = false; voiceLoadedFor = key;
                        ui.post(new Runnable() { @Override public void run() {
                            toast("系统语音引擎没起来，朗读和语音菜单都用不了，去「语音正文」里换一个引擎试试");
                            speak("系统语音引擎没起来，朗读用不了，请到语音正文里换一个引擎");
                            try { buzz(80); } catch (Throwable ignored) {}
                        } });
                        ui.post(new Runnable() { @Override public void run() {
                            if (!stack.isEmpty() && "朗读发音人".equals(top().title)) render(false);
                        } });
                        return;
                    }
                    ui.postDelayed(new Runnable() { @Override public void run() { collectVoices(rdTmp, key); } }, 600);
                }
            };
            rdTmp = new TextToSpeech(this, l, pkg);
            voiceLoadTimeout(key, rdTmp);
        } catch (Exception e) {
            voiceLoading = false;
        }
    }

    private TextToSpeech rdTmp = null;

    private void voiceLoadTimeout(final String key, final TextToSpeech inst) {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!voiceLoading) return;
                collectVoices(inst, key);
            }
        }, 3000);
    }

    private void collectVoices(final TextToSpeech inst, final String key) {
        try {
            List<Voice> all = new ArrayList<Voice>();
            Set<Voice> vs = inst == null ? null : inst.getVoices();
            if (vs != null) {
                for (Voice v : vs) {
                    String lang = v.getLocale() == null ? "" : v.getLocale().getLanguage();
                    if ("zh".equals(lang) || "cmn".equals(lang)) all.add(v);
                }
                if (all.isEmpty()) all.addAll(vs);
            }
            java.util.Collections.sort(all, new java.util.Comparator<Voice>() {
                @Override public int compare(Voice a, Voice b) {
                    return String.valueOf(a.getName()).compareTo(String.valueOf(b.getName()));
                }
            });
            engineVoices.clear();
            engineVoices.addAll(all);
        } catch (Exception ignored) {}
        voiceLoading = false;
        voiceLoadedFor = key;
        try { if (inst != null && inst != tts) inst.shutdown(); } catch (Exception ignored) {}
        ui.post(new Runnable() { @Override public void run() {
            if (stack.isEmpty()) return;
            Screen s = top();
            if ("朗读发音人".equals(s.title)) {
                s.items = readerVoiceScreen(s.cur).items;   // 用新条目替掉「正在读取」占位
                s.cur = Math.max(0, Math.min(s.cur, s.items.size() - 1));
                render(false);
            } else if ("发音人".equals(s.title)) {
                s.items = voiceScreen(s.cur).items;
                s.cur = Math.max(0, Math.min(s.cur, s.items.size() - 1));
                render(false);
            }
        } });
    }

    private Screen readerVoiceScreen(int cur) {
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("跟随系统" + (readerVoice.voiceName == null ? "　✔" : ""), "rv:__sys"));
        String key = "rd:" + (readerVoice.enginePkg == null ? "__default" : readerVoice.enginePkg);
        if (!key.equals(voiceLoadedFor) && !voiceLoading) loadRdVoices(readerVoice.enginePkg);
        if (engineVoices.isEmpty()) {
            items.add(new Item(voiceLoading ? "（正在读取发音人，稍候会刷新）" : "（这个引擎没有报出语音列表）"));
        }
        for (int i = 0; i < engineVoices.size(); i++) {
            Voice v = engineVoices.get(i);
            String loc = v.getLocale() == null ? "" : v.getLocale().getDisplayName();
            String label = (v.getName() == null ? ("语音" + (i + 1)) : v.getName())
                    + (loc.isEmpty() ? "" : "（" + loc + "）")
                    + (v.isNetworkConnectionRequired() ? " 需联网" : "");
            if (readerVoice.voiceName != null && readerVoice.voiceName.equals(v.getName())) label += "　✔";
            items.add(new Item(label, "rv:" + i));
        }
        Screen s = new Screen("朗读发音人", items);
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    private Screen voiceScreen(int cur) {
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("跟随系统（默认中文语音）" + (menuVoice.voiceName == null ? "　✔" : ""), "vc:__sys"));

        String key = menuVoice.enginePkg == null ? "__default" : menuVoice.enginePkg;
        if (!key.equals(voiceLoadedFor) && !voiceLoading) loadEngineVoices(menuVoice.enginePkg);

        if (engineVoices.isEmpty()) {
            items.add(new Item(voiceLoading ? "（正在读取发音人，稍候会刷新）" : "（这个引擎没有报出语音列表）"));
        }
        for (int i = 0; i < engineVoices.size(); i++) {
            Voice v = engineVoices.get(i);
            String loc = v.getLocale() == null ? "" : v.getLocale().getDisplayName();
            String label = (v.getName() == null ? ("语音" + (i + 1)) : v.getName())
                    + (loc.isEmpty() ? "" : "（" + loc + "）")
                    + (v.isNetworkConnectionRequired() ? " 需联网" : "");
            if (menuVoice.voiceName != null && menuVoice.voiceName.equals(v.getName())) label += "　✔";
            items.add(new Item(label, "vc:" + i));
        }
        Screen s = new Screen("发音人", items);
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    /** 用临时实例去问指定引擎有哪些语音（主实例只能看到自己的语音） */
    private void loadEngineVoices(String pkg) {
        voiceLoading = true;
        final String key = pkg == null ? "__default" : pkg;
        if (pkg == null) {
            ui.post(new Runnable() { @Override public void run() { collectVoices(tts, key); } });
            return;
        }
        try {
            TextToSpeech tmp = pkg == null ? new TextToSpeech(this, null)
                    : new TextToSpeech(this, null, pkg);
            tmp.setOnUtteranceProgressListener(null);
            final TextToSpeech tmpRef = tmp;
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        List<Voice> all = new ArrayList<Voice>();
                        Set<Voice> vs = tmpRef.getVoices();
                        if (vs != null) {
                            for (Voice v : vs) {
                                String lang = v.getLocale() == null ? "" : v.getLocale().getLanguage();
                                if ("zh".equals(lang) || "cmn".equals(lang)) all.add(v);
                            }
                            if (all.isEmpty()) all.addAll(vs);
                        }
                        java.util.Collections.sort(all, new java.util.Comparator<Voice>() {
                            @Override public int compare(Voice a, Voice b) {
                                return String.valueOf(a.getName()).compareTo(String.valueOf(b.getName()));
                            }
                        });
                        engineVoices.clear();
                        engineVoices.addAll(all);
                    } catch (Exception ignored) {}
                    voiceLoading = false;
                    voiceLoadedFor = key;
                    try { tmpRef.shutdown(); } catch (Exception ignored) {}
                    if (!stack.isEmpty() && ("发音人".equals(top().title) || "朗读发音人".equals(top().title))) render(false);
                }
            }, 900);
        } catch (Exception ex) {
            voiceLoading = false;
        }
    }

    private void refreshZhVoices() {
        zhVoices.clear();
        if (tts == null) return;
        try {
            Set<Voice> vs = tts.getVoices();
            if (vs == null) return;
            for (Voice v : vs) {
                String lang = v.getLocale() == null ? "" : v.getLocale().getLanguage();
                if ("zh".equals(lang) || "cmn".equals(lang)) zhVoices.add(v);
            }
            java.util.Collections.sort(zhVoices, new java.util.Comparator<Voice>() {
                @Override public int compare(Voice a, Voice b) { return a.getName().compareTo(b.getName()); }
            });
        } catch (Exception ignored) {}
    }

    private String hex(int c) { return String.format("#%06X", 0xFFFFFF & c); }

    /** 自定义背景：12 种深色底，选中后进入自定义配色 */
    private Screen customBgScreen(int cur) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < CUS_COLORS.length; i++) {
            boolean sel = prefs.getBoolean("cusOn", false) ? prefs.getInt("cusBgOv", -1) == CUS_COLORS[i]
                    : (paletteIdx < 0 && prefs.getInt("cusBg", 0xFF101010) == CUS_COLORS[i]);
            items.add(new Item(CUS_NAMES[i] + "  " + hex(CUS_COLORS[i]) + (sel ? "　✔" : ""), "pick:cusbg:" + i));
        }
        Screen s = new Screen("自定义背景", items).k("cusBg");
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    private Screen customFgScreen(int cur) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < CUS_FG.length; i++) {
            boolean sel = prefs.getBoolean("cusOn", false) ? prefs.getInt("cusFgOv", -1) == CUS_FG[i]
                    : (paletteIdx < 0 && prefs.getInt("cusFg", 0xFFFFE082) == CUS_FG[i]);
            items.add(new Item(CUS_FG_NAMES[i] + "  " + hex(CUS_FG[i]) + (sel ? "　✔" : ""), "pick:cusfg:" + i));
        }
        Screen s = new Screen("自定义前景", items).k("cusFg");
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    /** 文字设置页：字号 + 正文字体，归在一处 */
    private Screen textScreen(int cur) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < FONT_NAMES.length; i++) {
            items.add(new Item("字号" + FONT_NAMES[i] + (i == fontIdx ? "　✔" : ""), "pick:font:" + i));
        }
        items.add(new Item("正文字体：" + fontLabel(), "go:fontfile"));
        Screen s = new Screen("文字设置", items).k("text");
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    private Screen layoutScreen(int cur) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < LINE_SPACE_NAMES.length; i++) {
            items.add(new Item("行距" + LINE_SPACE_NAMES[i] + (i == lineSpaceIdx ? "　✔" : ""), "layout:line:" + i));
        }
        for (int i = 0; i < SIDE_PAD_NAMES.length; i++) {
            items.add(new Item("页边距" + SIDE_PAD_NAMES[i] + (i == sidePadIdx ? "　✔" : ""), "layout:pad:" + i));
        }

        Screen s = new Screen("版式设置", items);
        s.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return s;
    }

    private File tocRuleDir() { return new File(DATA_DIR + "/目录规则"); }

    private File purifyDir() { return new File(DATA_DIR + "/净化"); }

    private List<com.mangjie.reader.reader.UserRules.Entry> tocUserRules() {
        return com.mangjie.reader.reader.UserRules.load(
                com.mangjie.reader.reader.UserRules.fileIn(tocRuleDir()), "rule");
    }

    private List<com.mangjie.reader.reader.UserRules.Entry> purUserRules() {
        return com.mangjie.reader.reader.UserRules.load(
                com.mangjie.reader.reader.UserRules.fileIn(purifyDir()), "pattern");
    }

    private void saveTocRules(List<com.mangjie.reader.reader.UserRules.Entry> list) {
        com.mangjie.reader.reader.UserRules.save(com.mangjie.reader.reader.UserRules.fileIn(tocRuleDir()),
                list, "rule", false);
        com.mangjie.reader.reader.TocRules.reload(this);
        com.mangjie.reader.reader.ChapterIndex.reset();
    }

    private void savePurRules(List<com.mangjie.reader.reader.UserRules.Entry> list) {
        com.mangjie.reader.reader.UserRules.save(com.mangjie.reader.reader.UserRules.fileIn(purifyDir()),
                list, "pattern", true);
        com.mangjie.reader.reader.Purify.load(purifyDir());
    }

    private void refreshTop(Screen sc) {
        if (!stack.isEmpty()) stack.set(stack.size() - 1, sc);
        render(false);
    }

    /** 内置目录规则列表：只列名字，讲清是自动挑最多的那条 */
    private Screen tocRuleListScreen() {
        List<Item> items = new ArrayList<>();
        java.util.List<String> names = com.mangjie.reader.reader.TocRules.names();
        items.add(new Item("共 " + names.size() + " 条，切章时自动挑命中最多的一条"));
        for (int i = 0; i < names.size(); i++) {
            items.add(new Item((i + 1) + ". " + names.get(i)));
        }
        items.add(new Item("返回目录规则", "nav:back"));
        return new Screen("内置目录规则", items).k("tocRuleList");
    }

    /** 内置净化规则列表：逐条念规则名 */
    private Screen purBuiltinScreen() {
        List<Item> items = new ArrayList<>();
        String[] names = com.mangjie.reader.reader.Purify.builtinNames();
        items.add(new Item("共 " + names.length + " 条，按顺序过正文"));
        for (int i = 0; i < names.length; i++) {
            items.add(new Item((i + 1) + ". " + names[i]));
        }
        items.add(new Item("返回替换净化", "nav:back"));
        return new Screen("内置替换净化规则", items).k("purBuiltin");
    }

    private Screen tocRuleScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("看内置规则（" + com.mangjie.reader.reader.TocRules.count() + " 条）", "tocrule:list"));
        items.add(new Item("上次选中：" + com.mangjie.reader.reader.TocRules.lastRuleName()
                + "（" + com.mangjie.reader.reader.TocRules.lastRuleCount() + " 章）"));
        int i = 0;
        for (com.mangjie.reader.reader.UserRules.Entry e : tocUserRules()) {
            items.add(new Item("自定义：" + e.name + (e.enable ? "" : "（已停用）"), "toc:item:" + i));
            i++;
        }
        items.add(new Item("新增自定义规则", "toc:new"));
        items.add(new Item("导入规则文件", "toc:imp"));
        items.add(new Item("重新载入规则", "tocrule:reload"));
        return new Screen("目录规则", items).k("tocRule");
    }

    private Screen tocRuleDetail(int i) {
        List<com.mangjie.reader.reader.UserRules.Entry> list = tocUserRules();
        if (i < 0 || i >= list.size()) return tocRuleScreen();
        com.mangjie.reader.reader.UserRules.Entry e = list.get(i);
        List<Item> items = new ArrayList<>();
        items.add(new Item("名称：" + e.name, "toc:editname:" + i));
        items.add(new Item("匹配：" + e.pattern, "toc:editpat:" + i));
        items.add(new Item("状态：" + (e.enable ? "已启用" : "已停用"), "toc:toggle:" + i));
        items.add(new Item("删除这条规则", "toc:del:" + i));
        Screen s = new Screen("目录规则 · " + e.name, items);
        s.cur = 0;
        return s;
    }

    private Screen purifyScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("看内置规则（" + com.mangjie.reader.reader.Purify.ruleCount() + " 条）", "pur:builtin"));
        int i = 0;
        for (com.mangjie.reader.reader.UserRules.Entry e : purUserRules()) {
            items.add(new Item("自定义：" + e.name + (e.enable ? "" : "（已停用）"), "pur:item:" + i));
            i++;
        }
        items.add(new Item("新增自定义规则", "pur:new"));
        items.add(new Item("重新载入规则", "purify:reload"));
        return new Screen("替换净化", items).k("pur");
    }

    private Screen purRuleDetail(int i) {
        List<com.mangjie.reader.reader.UserRules.Entry> list = purUserRules();
        if (i < 0 || i >= list.size()) return purifyScreen();
        com.mangjie.reader.reader.UserRules.Entry e = list.get(i);
        List<Item> items = new ArrayList<>();
        items.add(new Item("名称：" + e.name, "pur:editname:" + i));
        items.add(new Item("匹配：" + e.pattern, "pur:editpat:" + i));
        items.add(new Item("替换：" + (e.replacement.isEmpty() ? "（空，整段删掉）" : e.replacement), "pur:editrep:" + i));
        items.add(new Item("状态：" + (e.enable ? "已启用" : "已停用"), "pur:toggle:" + i));
        items.add(new Item("删除这条规则", "pur:del:" + i));
        Screen s = new Screen("替换净化 · " + e.name, items);
        s.cur = 0;
        return s;
    }

    private String fontLabel() {
        String p = prefs == null ? null : prefs.getString("fontPath", null);
        if (p == null || p.isEmpty()) return "默认";
        File f = new File(p);
        return f.exists() ? f.getName() : "默认（原文件已不在）";
    }

    private File fontDir() { return new File(DATA_DIR + "/字体"); }

    private Screen fontFileScreen(int cur) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("默认字体" + (fontLabel().equals("默认") ? "　✔" : ""), "fontfile:0"));
        File[] kids = fontDir().listFiles();
        List<File> ttf = new ArrayList<>();
        if (kids != null) {
            for (File f : kids) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (n.endsWith(".ttf") || n.endsWith(".otf"))) ttf.add(f);
            }
        }
        for (int i = 0; i < ttf.size(); i++) {
            File f = ttf.get(i);
            items.add(new Item(f.getName() + (fontLabel().equals(f.getName()) ? "　✔" : ""), "fontfile:" + (i + 1)));
        }
        if (ttf.isEmpty()) {
            items.add(new Item("（还没有字体，点下面的导入按钮选一个 ttf 或 otf）"));
        }
        items.add(new Item("导入字体文件", "font:imp"));
        items.add(new Item("重新扫描"));
        Screen sc = new Screen("正文字体", items);
        sc.cur = Math.max(0, Math.min(cur, items.size() - 1));
        return sc;
    }

    /** 恢复备份后统一收拾一遍：设置重读、字体缓存清掉、规则和净化重载、界面退回备份页
     *  本地恢复和服务器恢复都走这里，少一处就前功尽弃（旧内存值会被 savePrefs 写回去）。 */
    private void reloadAfterRestore() {
        loadPrefs();
        customFont = null;
        customFontPath = null;
        com.mangjie.reader.reader.TocRules.reload(MainActivity.this);
        com.mangjie.reader.reader.Purify.load(new File(DATA_DIR + "/净化"));
        com.mangjie.reader.reader.ChapterIndex.reset();
        if (nb != null) nb.loadBookSources();
        refreshTop(backupScreen());
        render(true);
        speak("设置已经重新载入");
    }

    private Screen backupScreen() {
        List<Item> items = new ArrayList<>();
        File last = com.mangjie.reader.reader.Backup.latest(new File(DATA_DIR));
        boolean dav = com.mangjie.reader.net.DavClient.configured();
        items.add(new Item("服务器：" + (dav ? "已填，点这里改" : "还没填，点这里填地址和账号"), "backup:dav"));
        items.add(new Item("导出备份到存储卡", "backup:export"));
        items.add(new Item("上传备份到服务器", "backup:upload"));
        items.add(new Item("从服务器取回备份", "backup:download"));
        items.add(new Item("从存储卡恢复最近一次备份", "backup:restore"));
        items.add(new Item(last == null ? "（还没有备份）" : "最近备份：" + last.getName()));
        items.add(new Item("备份目录：" + new File(DATA_DIR, com.mangjie.reader.reader.Backup.DIR_NAME).getAbsolutePath()));
        return new Screen("备份与恢复", items);
    }

    /** 服务器设置：备份用，正式版由用户自己填 */
    private Screen backupDavScreen() {
        List<Item> items = new ArrayList<>();
        String b = prefs.getString("davUrl", "");
        String u = prefs.getString("davUser", "");
        String p = prefs.getString("davPass", "");
        String d = prefs.getString("davDir", "/mangjie/");
        items.add(new Item("地址：" + (b.isEmpty() ? "（未填）" : b), "backup:davurl"));
        items.add(new Item("账号：" + (u.isEmpty() ? "（未填）" : u), "backup:davuser"));
        items.add(new Item("密码：" + (p.isEmpty() ? "（未填）" : "已填，点这里改"), "backup:davpass"));
        items.add(new Item("目录：" + d, "backup:davdir"));
        items.add(new Item("备份时不上传密码，放心导出", "none"));
        items.add(new Item("返回备份与恢复", "nav:back"));
        return new Screen("服务器", items).k("backupDav");
    }

    private Screen nightScreen() {
        boolean on = prefs.getBoolean("autoNight", false);
        int from = prefs.getInt("nightFrom", 22);
        int to = prefs.getInt("nightTo", 7);
        int np = prefs.getInt("nightPal", 0);
        List<Item> items = new ArrayList<>();
        items.add(new Item("当前：" + (prefs.getBoolean("nightMode", false) ? "夜间模式" : "日间模式"), "none"));
        items.add(new Item(prefs.getBoolean("nightMode", false) ? "切到日间" : "切到夜间", "night:manual"));
        items.add(new Item("自动切换：" + (on ? "已开启（" + from + " 点到 " + to + " 点）" : "已关闭"), "night:toggle"));
        items.add(new Item("开始时间：" + from + " 点", "night:from"));
        items.add(new Item("结束时间：" + to + " 点", "night:to"));
        items.add(new Item("夜间配色：" + PAL_NAMES[np < PAL_NAMES.length ? np : 0], "night:pal"));
        items.add(new Item("（自动默认关闭；开着自动时会到点改配色，手动切换保持到你下次改）"));
        Screen s = new Screen("夜间模式", items).k("night");
        return s;
    }

    private Screen nightHourScreen(String which, int cur) {
        List<Item> items = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            items.add(new Item(h + " 点" + (h == cur ? "　✔" : ""), "night:" + which + ":" + h));
        }
        return new Screen("选择" + ("from".equals(which) ? "开始" : "结束") + "时间", items);
    }

    private Screen nightPalScreen() {
        int np = prefs.getInt("nightPal", 0);
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < PAL_NAMES.length; i++) {
            items.add(new Item(PAL_NAMES[i] + (i == np ? "　✔" : ""), "night:palset:" + i));
        }
        return new Screen("夜间配色", items);
    }

    /** 到点换配色：进夜间窗口用夜间配色，出窗口还原白天那套 */
    private void applyAutoNight() {
        try {
            if (!prefs.getBoolean("autoNight", false)) return;
            int from = prefs.getInt("nightFrom", 22);
            int to = prefs.getInt("nightTo", 7);
            int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            boolean inWin = (from <= to) ? (h >= from && h < to) : (h >= from || h < to);
            int nightPal = prefs.getInt("nightPal", 0);
            if (inWin && !nightApplied) {
                if (paletteIdx != nightPal) prefs.edit().putInt("dayPal", paletteIdx).apply();
                paletteIdx = nightPal;
                nightApplied = true;
                prefs.edit().putBoolean("nightMode", true).putInt("palette", paletteIdx).apply();
                devLogLine("夜间", "进入夜间配色 " + palName());
                if (!stack.isEmpty() && !dialogOn) render(false);
            } else if (!inWin && nightApplied) {
                paletteIdx = prefs.getInt("dayPal", 0);
                if (paletteIdx >= PAL_NAMES.length) paletteIdx = 0;
                nightApplied = false;
                prefs.edit().putBoolean("nightMode", false).putInt("palette", paletteIdx).apply();
                devLogLine("夜间", "还原白天配色 " + palName());
                if (!stack.isEmpty() && !dialogOn) render(false);
            }
        } catch (Throwable e) {
            devLogLine("夜间", "切换异常 " + e);
        }
    }

    private void startNightTimer() {
        if (nightHandler != null) return;
        applyAutoNight();
        nightHandler = new Handler(Looper.getMainLooper());
        nightHandler.postDelayed(new Runnable() {
            @Override public void run() {
                applyAutoNight();
                nightHandler.postDelayed(this, 60000);
            }
        }, 60000);
    }

    /** 首次进词典就把内置词表释放到 盲界阅读/词典/内置词表.txt */
    private void ensureBuiltinDict() {
        File dir = new File(DATA_DIR, "词典");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "内置词表.txt");
        if (f.exists() && f.length() > 0) return;
        try {
            java.io.InputStream in = getResources().openRawResource(
                    getResources().getIdentifier("mydict", "raw", getPackageName()));
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            devLogLine("词典", "已释放内置词表");
        } catch (Throwable e) {
            devLogLine("词典", "释放内置词表失败：" + e.getMessage());
        }
    }

    private String pendRuleName = "";
    private String pendRuleUrl = "";

    /** 词典这块已搬到 reader/DictScreen，主类只留转发与接线（滚动式模块化） */
    private DictScreen dict;
    private DictScreen dict() {
        if (dict == null) dict = new DictScreen(new DictScreen.Host() {
            @Override public String dataDir() { return DATA_DIR; }
            @Override public void speak(String m) { MainActivity.this.speak(m); }
            @Override public void log(String tag, String m) { devLogLine(tag, m); }
            @Override public String requestUrlT(String url, String base, int timeoutMs) {
                return MainActivity.this.requestUrlT(url, base, timeoutMs);
            }
            @Override public android.os.Handler ui() { return ui; }
            @Override public java.util.List<Screen> stack() { return stack; }
            @Override public void render(boolean reset) { MainActivity.this.render(reset); }
            @Override public void ensureBuiltinDict() { MainActivity.this.ensureBuiltinDict(); }
        });
        return dict;
    }

    private File dictRuleDir() { return dict().ruleDir(); }
    private Screen dictRulesScreen() { return dict().rulesScreen(); }
    private Screen dictRulesRemoveScreen() { return dict().removeScreen(); }
    private Screen dictRulesHelpScreen() { return dict().helpScreen(); }
    private Screen userDictScreen() { return dict().mainScreen(); }
    private void dictLookup(String word) { dict().lookup(word); }
    private void showDictResult(String word, String online, String hit, int onlineN) {
        dict().showResult(word, online, hit, onlineN);
    }

    /** 摘抄与整本导出已搬到 reader/ExportScreen，主类只留转发与接线 */
    private ExportScreen exportUi;
    private ExportScreen exportUi() {
        if (exportUi == null) exportUi = new ExportScreen(new ExportScreen.Host() {
            @Override public String dataDir() { return DATA_DIR; }
            @Override public void speak(String m) { MainActivity.this.speak(m); }
            @Override public void toast(String m) { MainActivity.this.toast(m); }
            @Override public void log(String tag, String m) { devLogLine(tag, m); }
            @Override public android.os.Handler ui() { return ui; }
            @Override public void render(boolean reset) { MainActivity.this.render(reset); }
            @Override public String nbFavRaw() { return prefs.getString("nbFav", ""); }
            @Override public boolean hasToc() { return nb.nbChapters != null && !nb.nbChapters.isEmpty(); }
            @Override public java.util.List<String[]> chapters() { return nb.nbChapters; }
            @Override public String bookName() { return nb.nbBookName; }
            @Override public String bookAuthor() { return nb.nbBookAuthor; }
            @Override public String fetchChapter(String url) { return nb.nbFetchChapterText(nb.currentSrc(), url); }
        });
        return exportUi;
    }

    private Screen excerptScreen() { return exportUi().excerptScreen(); }
    private String readExcerptRaw() { return exportUi().readExcerptRaw(); }
    private void exportWholeBook(boolean epub) { exportUi().exportWholeBook(epub); }
    private void exportExcerpts() { exportUi().exportExcerpts(); }

    private void startStatTimer() { stats().startTimer(); }

    private Screen sysInfoScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("设备型号  " + deviceModel()));
        items.add(new Item("制造商  " + Build.MANUFACTURER));
        items.add(new Item("Android 版本  " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）"));
        items.add(new Item("屏幕分辨率  " + screenDesc()));
        try {
            ActivityManager amgr = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            amgr.getMemoryInfo(mi);
            // 整机物理内存（总 / 可用），不是本应用的堆上限
            items.add(new Item("运行内存  " + gb(mi.totalMem) + "（总 " + mbText(mi.totalMem) + "）"));
            items.add(new Item("可用内存  " + gb(mi.availMem) + "（" + mbText(mi.availMem) + "）"));
            StatFs sf = new StatFs(getFilesDir().getAbsolutePath());
            long total = sf.getTotalBytes(), free = sf.getAvailableBytes();
            items.add(new Item("存储容量  " + gb(total)));
            items.add(new Item("可用空间  " + gb(free)));
        } catch (Exception e) {
            items.add(new Item("系统信息读取失败"));
        }
        return new Screen("系统信息", items);
    }

    private String sdState() {
        try {
            android.os.storage.StorageManager sm =
                    (android.os.storage.StorageManager) getSystemService(android.content.Context.STORAGE_SERVICE);
            if (sm != null) {
                for (android.os.storage.StorageVolume sv : sm.getStorageVolumes()) {
                    if (sv.isRemovable() && Environment.MEDIA_MOUNTED.equals(sv.getState())) return "已插入";
                }
            }
        } catch (Exception ignored) {}
        return "未插入";
    }



    // ---------------------------------------------------------------- 文件管理


    /** 可移除存储卡的根路径；找不到就返回 null */
    private boolean isRemovablePath(String path) {
        try {
            android.os.storage.StorageManager sm =
                    (android.os.storage.StorageManager) getSystemService(android.content.Context.STORAGE_SERVICE);
            if (sm != null) {
                for (android.os.storage.StorageVolume sv : sm.getStorageVolumes()) {
                    if (!sv.isRemovable()) continue;
                    File dir = Build.VERSION.SDK_INT >= 30 ? sv.getDirectory() : null;
                    if (dir != null && path.startsWith(dir.getAbsolutePath())) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String sdRoot() {
        // 1) 系统接口：列出可移除卷，取它的真实目录
        try {
            android.os.storage.StorageManager sm =
                    (android.os.storage.StorageManager) getSystemService(android.content.Context.STORAGE_SERVICE);
            if (sm != null) {
                for (android.os.storage.StorageVolume sv : sm.getStorageVolumes()) {
                    if (!sv.isRemovable()) continue;
                    if (!Environment.MEDIA_MOUNTED.equals(sv.getState())) continue;
                    File dir = null;
                    if (Build.VERSION.SDK_INT >= 30) dir = sv.getDirectory();
                    if (dir != null && dir.exists()) return dir.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {}

        // 2) 兜底：扫 /storage 下形如 XXXX-XXXX 的卷（不看 canRead，插卡授权前也可能读不到）
        try {
            File[] vols = new File("/storage").listFiles();
            if (vols != null) {
                for (File v : vols) {
                    String n = v.getName();
                    if (n.equals("emulated") || n.equals("self") || n.startsWith(".")) continue;
                    if (v.isDirectory()) return v.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Android 11+ 用 isExternalStorageManager 判断；更低版本看读权限 */
    private boolean hasAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true;
        } catch (Exception ignored) {}
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private String internalRoot() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    /** 真实列目录：目录在前、文件在后，按名称排序 */
    private String curDir = null;

    private Screen listDir(String path) { return listDir(path, false); }

    private Screen listDir(String path, boolean txtOnly) {
        curDir = path;
        Screen built = buildDirScreen(path, txtOnly);
        built.fileMode = true;
        built.txtOnly = txtOnly;
        return built;
    }

    private Screen buildDirScreen(String path, boolean txtOnly) {
        List<Item> items = new ArrayList<>();
        String title = new File(path).getName();
        if (title == null || title.isEmpty()) title = "内存";
        File dir = new File(path);

        File[] files = null;
        try { files = dir.listFiles(); } catch (Exception ignored) {}

        if (files == null) {
            items.add(new Item("无法访问此目录", "f:grant"));
            items.add(new Item("授予访问权限", "f:grant"));
            return new Screen(title, items);
        }
        if (files.length == 0) {
            items.add(new Item("（空目录）"));
            if (!hasAllFilesAccess() || isRemovablePath(path)) {
                items.add(new Item("若这里确实有文件，请先授予「所有文件访问权限」", "f:grant"));
            }
            return new Screen(title, items);
        }

        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            String key;
            if (f.isDirectory()) key = "dir:";
            else if (txtOnly) {
                // 书单里 txt 可读，其它格式也列出来，点开说明不支持，免得“看不到文件”摸不着头脑
                key = isBookFile(f) ? "book:" : "unsupported:";
            } else key = "file:";
            items.add(new Item(f.getName(), key + f.getAbsolutePath()));
        }
        Screen s = new Screen(title, items);
        return s;
    }


    // ---------------------------------------------------------------- 数据迁移（公有 → 私有）

    /** 迁移结果要念的那句话。onCreate 太早，语音还没准备好，先存着，等 initSpeech 之后播 */
    private String migrateSpeak = null;

    /**
     * 把 /sdcard/盲界阅读 整套搬进 files/，只跑一次。
     *
     * 没有文件访问权限时干脆不搬：读不到外部目录，硬搬只会搬空。这时不动旧目录、不写标记，
     * 等用户真的点了要读外部文件、授权回来之后再由 onResume 触发一次。
     */
    private void tryMigrateLegacy() {
        if (prefs == null) return;
        if (prefs.getBoolean("movedToPrivate", false)) return;
        java.io.File oldRoot = new java.io.File(LEGACY_DATA_DIR);
        if (!oldRoot.exists()) {
            prefs.edit().putBoolean("movedToPrivate", true).apply();
            return;
        }
        if (!hasAllFiles()) {
            // 没授权就读不到外部目录，硬搬只会搬空。旧目录原样留着，等授权回来再搬。
            writeMigrateLog("还没有文件访问权限，本轮不搬，旧目录原样保留");
            migrateSpeak = "旧配置还在存储卡里，授权之后才能搬过来；随便点一本手机里的书就会提示你";
            return;
        }
        // 首次启动会真的拷一批文件，给个动静，不然用户以为卡住了
        try { toast("正在把配置搬进应用目录，请稍等"); } catch (Throwable ignored) { }
        com.mangjie.reader.tools.LegacyMigrate.R r =
                com.mangjie.reader.tools.LegacyMigrate.run(oldRoot, new java.io.File(DATA_DIR));
        if (r.ok) {
            prefs.edit().putBoolean("movedToPrivate", true).apply();
            com.mangjie.reader.tools.LegacyMigrate.deleteSource(oldRoot);
            writeMigrateLog("搬完 " + r.files + " 个文件（" + r.bytes + " 字节），跳过 " + r.skipped
                    + " 个同大小文件，外部旧目录已删");
            if (!r.noSource) migrateSpeak = "配置已搬完";
        } else {
            writeMigrateLog("有 " + r.failCount + " 个文件没搬成，第一个是 " + r.failName
                    + "，旧目录保留，下次启动再试");
            migrateSpeak = "有几个配置文件没搬成功，旧目录先留着，下次打开再试";
        }
    }

    /** 迁移发生的时刻 devLog 还没从 prefs 读出来，只能直接往日志文件写一笔 */
    private void writeMigrateLog(String msg) {
        try {
            java.io.File dir = new java.io.File(DATA_DIR);
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "无障碍日志.txt");
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new java.util.Date()) + "  [迁移] " + msg + "\n");
            fw.close();
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- 首次授权向导

    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { return Environment.isExternalStorageManager(); } catch (Exception e) { return false; }
        }
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotify() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean canInstall() {
        try {
            if (Build.VERSION.SDK_INT >= 26) return getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) { return true; }
        return true;
    }

    private Screen grantScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("首次使用需要授权，请逐项允许", "grant:info"));
        items.add(new Item("文件访问权限：" + (hasAllFiles() ? "已授权" : "未授权，双击去授权"), "grant:storage"));
        items.add(new Item("通知权限：" + (hasNotify() ? "已授权" : "未授权，双击申请"), "grant:notify"));
        items.add(new Item("安装应用权限：" + (canInstall() ? "已允许" : "未允许，双击去开启"), "grant:install"));
        items.add(new Item("全部允许，开始使用", "grant:done"));
        items.add(new Item("不再提示，直接使用", "grant:skip"));
        return new Screen("授权", items);
    }

    private void handleGrant(String what) {
        if ("storage".equals(what)) {
            if (hasAllFiles()) { speak("文件访问权限已授权"); return; }
            openGrantSettings();
            return;
        }
        if ("notify".equals(what)) {
            if (hasNotify()) { speak("通知权限已授权"); return; }
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001); }
            catch (Exception e) { toast("申请通知权限失败"); }
            return;
        }
        if ("install".equals(what)) {
            if (canInstall()) { speak("安装应用权限已允许"); return; }
            speak("请在系统设置里允许本应用安装应用");
            toast("请允许安装未知应用");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
                catch (Exception e2) { toast("无法打开系统设置"); }
            }
            return;
        }
        if ("skip".equals(what)) {
            prefs.edit().putBoolean("granted", true).apply();
            stack.remove(stack.size() - 1);
            render(true);
            speak("不再提示");
            return;
        }
        if ("done".equals(what)) {
            if (!hasAllFiles()) { speak("文件访问权限还没给，先在上面那一项授权"); return; }
            if (!canInstall()) { speak("安装应用权限还没允许，先在上面那一项开启"); return; }
            prefs.edit().putBoolean("granted", true).apply();
            stack.remove(stack.size() - 1);
            render(true);
            speak("开始使用");
            return;
        }
        // info 行：重读一次状态（授权页没有 key，必须走 refreshGrant 自己重建）
        refreshGrant();
        speak("当前授权状态");
    }

    private void refreshGrant() {
        if (stack.isEmpty()) return;
        if (!"授权".equals(top().title)) return;
        int cur = top().cur;
        Screen n = grantScreen();
        n.cur = cur;
        stack.set(stack.size() - 1, n);
        render(false);
    }

    // ---------------------------------------------------------------- 导出落盘：一律公有目录

    /** /sdcard/盲界阅读导出/<子目录>；没权限时 App 会自动退回私有 files/导出/<子目录> */
    private File exportDirOf(String sub) {
        File root = com.mangjie.reader.App.exportDir();
        File d = (sub == null || sub.isEmpty()) ? root : new File(root, sub);
        try { if (!d.exists()) d.mkdirs(); } catch (Throwable ignored) { }
        return d;
    }

    /**
     * 取一个导出文件名。overwrite=false 时同名自动让位成 -2、-3，免得把上次的备份冲掉；
     * 书架快照这种「只有最新一份有用」的用 overwrite=true。
     */
    private File exportFile(String sub, String name, boolean overwrite) {
        File d = exportDirOf(sub);
        File f = new File(d, name);
        if (overwrite || !f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 2; i < 100; i++) {
            File t = new File(d, base + "-" + i + ext);
            if (!t.exists()) return t;
        }
        return f;
    }

    /** 导出完统一播报落点：落在私有目录时必须说清楚，不然用户会以为文件丢了 */
    private void speakExportDone(File f, String what) {
        if (f == null) { speak(what + "导出失败"); return; }
        String p = f.getAbsolutePath();
        if (com.mangjie.reader.App.exportIsPublic()) speak(what + "已导出，文件在 " + p);
        else speak(what + "已导出到应用目录 " + p + "，没有存储权限，文件管理器里看不到");
    }

    // ---------------------------------------------------------------- 三个导入入口（字体 / 词库 / 规则）

    private static final int KIND_FONT = 1;
    private static final int KIND_DICT = 2;
    private static final int KIND_RULE = 3;

    /** 选文件：走系统文件选择器，SAF 本身不需要「所有文件访问」权限，所以这里不拦权限 */
    private void importPick(int kind) {
        pickKind = kind;
        String hint = kind == KIND_FONT ? "选一个 ttf 或 otf 字体文件"
                : kind == KIND_DICT ? "选一个词库文件，txt、csv、tsv 都行"
                : "选一个规则文件，json 或者一行一条的 txt";
        speak(hint);
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        int req = kind == KIND_FONT ? REQ_PICK_FONT : kind == KIND_DICT ? REQ_PICK_DICT : REQ_PICK_RULE;
        try {
            startActivityForResult(i, req);
        } catch (Throwable t) {
            try {
                Intent g = new Intent(Intent.ACTION_GET_CONTENT);
                g.setType("*/*");
                startActivityForResult(g, req);
            } catch (Throwable t2) {
                speak("这台机器没有文件选择器，导不了");
            }
        }
    }

    /** 从 content Uri 上抠出文件名，抠不到就叫「导入文件」 */
    private String nameFromUri(Uri u) {
        try {
            String s = u.getLastPathSegment();
            if (s == null) return "导入文件";
            int p = s.lastIndexOf('/');
            if (p >= 0) s = s.substring(p + 1);
            int c = s.indexOf(':');
            if (c >= 0) s = s.substring(c + 1);
            return s.trim().isEmpty() ? "导入文件" : s.trim();
        } catch (Throwable t) { return "导入文件"; }
    }

    /** 把选中的文件读成字节，导进指定目录，重名自动加 -2 */
    private File copyPickInto(Uri u, String name, File dir) throws Exception {
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, name);
        if (out.exists()) {
            String base = name, ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
            for (int i = 2; i < 100; i++) {
                File t = new File(dir, base + "-" + i + ext);
                if (!t.exists()) { out = t; break; }
            }
        }
        java.io.InputStream in = getContentResolver().openInputStream(u);
        java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        fo.flush();
        in.close();
        fo.close();
        if (out.length() <= 0) throw new Exception("读到的内容是空的");
        return out;
    }

    private void handleImportResult(int req, int res, Intent data) {
        if (res != RESULT_OK || data == null || data.getData() == null) { speak("没有选择文件"); return; }
        Uri u = data.getData();
        String name = nameFromUri(u);
        try {
            if (req == REQ_PICK_FONT) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
                    speak("这个不是字体文件，要 ttf 或 otf");
                    return;
                }
                File f = copyPickInto(u, name, fontDir());
                devLogLine("字体", "导入 " + f.getName() + "　" + f.length() + " 字节");
                refreshTop(fontFileScreen(0));
                render(true);
                speak("字体已导入，" + f.getName() + "，在正文字体里就能选到");
                return;
            }
            if (req == REQ_PICK_DICT) {
                File f = copyPickInto(u, name, new File(DATA_DIR, "词典"));
                com.mangjie.reader.reader.Look.load(new File(DATA_DIR, "词典"));
                devLogLine("词典", "导入 " + f.getName() + "　现有 " + com.mangjie.reader.reader.Look.count() + " 条");
                speak("词库已导入，" + f.getName() + "，现在一共 "
                        + com.mangjie.reader.reader.Look.count() + " 条");
                return;
            }
            if (req == REQ_PICK_RULE) {
                int added = importRuleFile(u, name);
                if (added <= 0) { speak("这个文件里没读出规则，看看格式对不对"); return; }
                com.mangjie.reader.reader.TocRules.reload(this);
                com.mangjie.reader.reader.ChapterIndex.reset();
                refreshTop(tocRuleScreen());
                render(true);
                speak("已导入 " + added + " 条目录规则，共 "
                        + tocUserRules().size() + " 条自定义规则");
                return;
            }
        } catch (Throwable t) {
            devLogLine("导入", "失败：" + t.getMessage());
            speak("导入失败：" + (t.getMessage() == null ? "读不了这个文件" : t.getMessage()));
        }
    }

    /** 规则导入：json 数组按条并进「自定义.json」；txt 一行一条，名字用文件名+序号 */
    private int importRuleFile(Uri u, String name) throws Exception {
        java.io.InputStream in = getContentResolver().openInputStream(u);
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        String text = sb.toString().trim();
        if (text.isEmpty()) return 0;

        List<com.mangjie.reader.reader.UserRules.Entry> old = tocUserRules();
        List<com.mangjie.reader.reader.UserRules.Entry> add = new ArrayList<>();
        String base = name;
        int dot = name.lastIndexOf('.');
        if (dot > 0) base = name.substring(0, dot);

        if (text.startsWith("[")) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(text);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    com.mangjie.reader.reader.UserRules.Entry e = new com.mangjie.reader.reader.UserRules.Entry();
                    e.name = o.optString("name", base + "-" + (i + 1));
                    e.pattern = o.optString("rule", o.optString("pattern", ""));
                    e.replacement = o.optString("replacement", "");
                    e.enable = o.optBoolean("enable", true);
                    if (!e.pattern.isEmpty()) add.add(e);
                }
            } catch (Throwable ignored) { }
        } else if (text.startsWith("{")) {
            // 单个对象也认
            try {
                org.json.JSONObject o = new org.json.JSONObject(text);
                com.mangjie.reader.reader.UserRules.Entry e = new com.mangjie.reader.reader.UserRules.Entry();
                e.name = o.optString("name", base);
                e.pattern = o.optString("rule", o.optString("pattern", ""));
                e.replacement = o.optString("replacement", "");
                if (!e.pattern.isEmpty()) add.add(e);
            } catch (Throwable ignored) { }
        } else {
            // 一行一条正则
            int i = 0;
            for (String l : text.split("\n")) {
                String t = l.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                com.mangjie.reader.reader.UserRules.Entry e = new com.mangjie.reader.reader.UserRules.Entry();
                i++;
                e.name = base + "-" + i;
                e.pattern = t;
                add.add(e);
            }
        }
        if (add.isEmpty()) return 0;
        List<com.mangjie.reader.reader.UserRules.Entry> all = new ArrayList<>(old);
        all.addAll(add);
        com.mangjie.reader.reader.UserRules.save(
                com.mangjie.reader.reader.UserRules.fileIn(tocRuleDir()), all, "rule", false);
        return add.size();
    }

    // ---------------------------------------------------------------- 权限按需申请

    /** 等授权的那个动作：系统设置回来后自动接着做，用户不用再点一遍 */
    private Runnable pendingFileAction = null;

    /** 同一项权限被拒后多久不再打扰（24 小时） */
    private static final long ASK_GAP_MS = 24L * 3600 * 1000;

    /**
     * 要读写外部存储的动作统一走这里。有权限就直接做；没权限才拉系统授权页。
     * 首次启动一概不问，用户不碰外部文件就永远见不到这个页面。
     */
    private void ensureFileAccess(final Runnable then, final String why) {
        if (hasAllFiles()) {
            pendingFileAction = null;
            then.run();
            return;
        }
        long last = prefs == null ? 0L : prefs.getLong("askStorageAt", 0L);
        if (System.currentTimeMillis() - last < ASK_GAP_MS) {
            speak(why);       // 刚拒过：不弹设置，只把为什么做不成说清楚
            return;
        }
        if (prefs != null) prefs.edit().putLong("askStorageAt", System.currentTimeMillis()).apply();
        pendingFileAction = then;
        openGrantSettings("要读手机里的文件，得先把「所有文件访问」打开，回来我接着做");
    }

    private void runPendingFileAction() {
        if (pendingFileAction == null) return;
        if (!hasAllFiles()) { pendingFileAction = null; return; }
        final Runnable r = pendingFileAction;
        pendingFileAction = null;
        ui.postDelayed(new Runnable() { @Override public void run() { r.run(); } }, 300);
    }

    /** 这个文件是不是在外部存储上——私有目录里的书不需要任何权限 */
    private boolean needExternalFor(File f) {
        try {
            String p = f.getAbsolutePath();
            if (DATA_DIR != null && p.startsWith(DATA_DIR)) return false;
            return p.startsWith(Environment.getExternalStorageDirectory().getAbsolutePath());
        } catch (Throwable t) { return false; }
    }

    private void openGrantSettings() {
        openGrantSettings("请授予所有文件访问权限");
    }

    private void openGrantSettings(String why) {
        if (why != null && !why.isEmpty()) speak(why);
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
            catch (Exception e2) { toast("无法打开权限设置"); }
        }
    }

    private Screen msgScreen(String title, String... lines) {
        List<Item> items = new ArrayList<>();
        for (String l : lines) items.add(new Item(l));
        return new Screen(title, items);
    }

    // ---------------------------------------------------------------- 电子书 / 阅读

    private List<String> bookSent = new ArrayList<>();
    /** 每一句属于第几个一级句子（二级细分后用来把老进度/书签映射回原位） */
    private List<Integer> bookParents = new ArrayList<>();
    private String bookName = "";
    private String bookPath = "";
    private boolean autoPlay = false;
    private boolean readingNow = false;

    private boolean isBookFile(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".markdown")
                || n.endsWith(".epub") || n.endsWith(".docx") || n.endsWith(".mobi");
    }

    /** 日志里只留前 24 个字，避免日志被正文撑爆 */

    private String memText() {
        try {
            Runtime r = Runtime.getRuntime();
            return "堆用 " + fmtSize(r.totalMemory() - r.freeMemory()) + " / " + fmtSize(r.maxMemory());
        } catch (Exception e) { return "内存未知"; }
    }

    /** 上次崩过就把本地日志立刻上传一份，省得崩溃现场丢在手机里 */

    private void logStartup() {
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        devLogLine("启动", "盲界阅读 v" + ver + " 启动　" + deviceShort()
                + "　读屏=" + (screenReaderOn ? "开" : "关")
                + "　配色=" + (paletteIdx < PAL_NAMES.length ? palName() : paletteIdx)
                + "　字号=" + fontIdx + "　语音开=" + speechOn
                + "　界面语音=" + (menuVoice.enginePkg == null ? "系统默认" : menuVoice.enginePkg) + "/" + (menuVoice.voiceName == null ? "默认" : menuVoice.voiceName)
                + "　朗读语音=" + (readerVoice.enginePkg == null ? "系统默认" : readerVoice.enginePkg) + "/" + (readerVoice.voiceName == null ? "默认" : readerVoice.voiceName)
                + "　语速=" + menuVoice.rateP + "%　语调=" + menuVoice.pitchP + "%　倍数=" + menuVoice.speedMul);
    }

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    File dir = new File(DATA_DIR);
                    if (!dir.exists()) dir.mkdirs();
                    FileWriter fw = new FileWriter(new File(dir, "无障碍日志.txt"), true);
                    fw.write(sdf.format(new Date()) + "  [异常] 线程 " + t.getName() + " 崩了：" + e + "\n");
                    for (StackTraceElement el : e.getStackTrace()) {
                        fw.write("    at " + el.toString() + "\n");
                    }
                    fw.write("    页面栈=" + (stack.isEmpty() ? "" : top().title) + "\n");
                    fw.close();
                } catch (Throwable ignored) {}
                if (old != null) old.uncaughtException(t, e);
            }
        });
    }

    /** 打开电子书：解析放到后台线程做，主线程只负责显示「正在打开」，避免 ANR */
    private void openBook(final File f) {
        // 外部存储上的书才可能要权限；私有目录里的书直接开，不打扰
        if (needExternalFor(f)) {
            ensureFileAccess(new Runnable() {
                @Override public void run() { openBookNow(f); }
            }, "没有文件访问权限，读不了手机里的书，先在设置里允许");
            return;
        }
        openBookNow(f);
    }

    private void openBookNow(final File f) {
        final String fname = f.getName();
        // 先按书身份认一次：改名、挪过文件夹的书，把书架和分组里的旧路径就地改成现在这个
        try {
            com.mangjie.reader.shelves.BookMap.sync(prefs, f.getAbsolutePath());
            String fix = com.mangjie.reader.shelves.BookMap.lastFix;
            if (fix != null && !fix.isEmpty()) {
                devLogLine("书架", fix);
                com.mangjie.reader.shelves.BookMap.lastFix = "";
            }
        } catch (Throwable t) {
            devLogLine("书架", "认书失败：" + t.getMessage());
        }
        final long t0 = System.currentTimeMillis();
        devLogLine("阅读", "开始打开 " + fname + "　大小=" + fmtSize(f.length()) + "　" + memText());

        final List<Item> loading = new ArrayList<>();
        loading.add(new Item("正在打开，请稍等…", "none"));
        stack.add(new Screen("正在打开　" + fname, loading));
        render(true);
        speak("正在打开");

        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                String text = null;
                Throwable err = null;
                boolean truncated = false;
                try {
                    // 本地纯文本不分大小：能认章就按章，认不出章就按 1MB 分段，两种都只读当前一格。
                    // 这样多大的 txt 都能读到结尾，不再有「只载入前 120 万字」这回事
                    if (isPlainTextFile(f) && f.length() > PLAIN_INDEX_MIN) {
                        com.mangjie.reader.reader.LocalText.Index li =
                                com.mangjie.reader.reader.LocalText.index(f);
                        if (li.ok() && li.chapters.size() >= 2 && openChapterized(f, li, t0)) return;
                        com.mangjie.reader.reader.LocalText.Index ci =
                                com.mangjie.reader.reader.LocalText.chunk(f);
                        if (ci.ok() && openChapterized(f, ci, t0)) return;
                        devLogLine("本地", "章表与分段都建不起来，退回整本读　" + li.note + "　" + ci.note);
                    }
                    // epub 也按章读：只解压当前那一章，不再把整本拼成一个大字符串
                    if (f.getName().toLowerCase(Locale.ROOT).endsWith(".epub")) {
                        com.mangjie.reader.reader.EpubBooks.Index ei =
                                com.mangjie.reader.reader.EpubBooks.index(f);
                        if (ei.ok() && openEpubChapterized(f, ei, t0)) return;
                        devLogLine("本地", "epub 建不起章表，退回整本读　" + ei.note);
                    }
                    text = com.mangjie.reader.reader.EbookParser.readBookText(f);
                    if (text != null) {
                        com.mangjie.reader.reader.Purify.Result pr = com.mangjie.reader.reader.Purify.clean(text);
                        if (pr.hitCount > 0) {
                            devLogLine("净化", "规则 " + pr.ruleCount + " 条　命中 " + pr.hitCount
                                    + " 条　正文 " + text.length() + " → " + pr.text.length() + " 字");
                        }
                        text = pr.text;
                    }
                    if (text != null && simplifyOn && com.mangjie.reader.reader.Simplifier.looksTraditional(text)) {
                        int before = text.length();
                        text = com.mangjie.reader.reader.Simplifier.toSimplified(text);
                        devLogLine("简繁", "已转简体　正文 " + before + " 字");
                    }
                    if (text != null && text.length() > 1200000) {
                        devLogLine("阅读", "正文过长，截断到 120 万字：原 " + text.length());
                        text = text.substring(0, 1200000);
                        truncated = true;
                    }
                } catch (Throwable t) {
                    err = t;
                }
                if (err != null || text == null) {
                    final Throwable fe = err;
                    devLogLine("阅读", "解析失败　" + fname + "　"
                            + (fe == null ? "结果为空" : (fe.getClass().getSimpleName() + "　" + fe.getMessage()))
                            + "　耗时 " + (System.currentTimeMillis() - t0) + "ms");
                    ui.post(new Runnable() { @Override public void run() {
                        if (!stack.isEmpty() && top().title.startsWith("正在打开")) stack.remove(stack.size() - 1);
                        render(true);
                        toast("这个文件解析不了，换 txt 或 epub 试试");
                        speak("这个文件解析不了");
                    } });
                    System.gc();
                    return;
                }
                final String body = text;
                final boolean cut = truncated;
                final long parseMs = System.currentTimeMillis() - t0;
                // 切句也放在后台，几十万句在主线程做一样会卡死
                final List<Integer> parents = new ArrayList<Integer>();
                final List<String> sent = com.mangjie.reader.reader.SentenceSplitter.split(body, parents);
                // 第 1 步只建章表、打日志核对：流式扫全文件记每章字节偏移，不读正文
                try {
                    com.mangjie.reader.reader.LocalText.Index li =
                            com.mangjie.reader.reader.LocalText.index(f);
                    if (li != null) {
                        devLogLine("本地", "章表 " + li.chapters.size() + " 章　规则=" + li.rule
                                + "　编码=" + li.charset
                                + "　扫描 " + fmtSize(li.bytes) + "　耗时 " + li.ms + "ms"
                                + (li.note != null ? "　" + li.note : "")
                                + (li.chapters.isEmpty() ? ""
                                : "　首章「" + li.chapters.get(0).title + "」　末章「"
                                  + li.chapters.get(li.chapters.size() - 1).title + "」"));
                    }
                } catch (Throwable ignored) {}
                devLogLine("阅读", "解析完成　正文 " + body.length() + " 字　切出 " + sent.size()
                        + " 句　一级段 " + (parents.isEmpty() ? 0 : (parents.get(parents.size() - 1) + 1))
                        + " 段　耗时 " + parseMs + "ms　" + memText());
                ui.post(new Runnable() { @Override public void run() { finishOpenBook(f, body, sent, parents, cut); } });
            }
        });
    }

    /** 纯文本：.txt（.md 要洗标记，仍走整本，免得按章读时洗不到） */
    private boolean isPlainTextFile(File f) {
        return f != null && f.getName().toLowerCase(Locale.ROOT).endsWith(".txt");
    }

    /**
     * 按章（或按段）装载本地纯文本：只读当前那一格，其余等翻章再读。
     * 返回 true 表示这一趟已接管（成功读进来了），调用方不要再走整本读。
     */
    private boolean openChapterized(final File f, final com.mangjie.reader.reader.LocalText.Index li, final long t0) {
        try {
            int saved = 0;
            try {
                saved = prefs.getInt(com.mangjie.reader.reader.BookProgress.chapterKey(f.getAbsolutePath()), 0);
            } catch (Throwable ignored) {}
            if (saved < 0 || saved >= li.chapters.size()) saved = 0;
            String body = com.mangjie.reader.reader.LocalText.readChapter(f, li, saved);
            if (body == null || body.trim().isEmpty()) return false;

            com.mangjie.reader.reader.Purify.Result pr = com.mangjie.reader.reader.Purify.clean(body);
            if (pr.hitCount > 0) {
                devLogLine("净化", "规则 " + pr.ruleCount + " 条　命中 " + pr.hitCount
                        + " 条　格内正文 " + body.length() + " → " + pr.text.length() + " 字");
            }
            body = pr.text;
            if (simplifyOn && com.mangjie.reader.reader.Simplifier.looksTraditional(body)) {
                body = com.mangjie.reader.reader.Simplifier.toSimplified(body);
            }
            List<Integer> pp = new ArrayList<Integer>();
            List<String> ss = com.mangjie.reader.reader.SentenceSplitter.split(body, pp);
            devLogLine("本地", (li.chunked ? "按段装载" : "按章装载") + "　第" + (saved + 1) + "/" + li.chapters.size()
                    + (li.chunked ? " 段" : " 章") + "　格内 " + body.length() + " 字　切出 " + ss.size() + " 句"
                    + "　耗时 " + (System.currentTimeMillis() - t0) + "ms");

            final com.mangjie.reader.reader.LocalText.Index fli = li;
            final String fbody = body;
            final int fch = saved;
            final List<String> fss = ss;
            final List<Integer> fpp = pp;
            ui.post(new Runnable() { @Override public void run() {
                localMode = true;
                localFile = f;
                localIdx = fli;
                epubIdx = null;
                chapterNo = fch;
                finishOpenBook(f, fbody, fss, fpp, false);
                if (!stack.isEmpty() && top().reader && fli.chapters.size() > 1) {
                    top().extra = "　" + fli.chapters.get(fch).title
                            + "（" + (fch + 1) + "/" + fli.chapters.size() + "）";
                    render(true);
                }
            } });
            return true;
        } catch (Throwable t) {
            devLogLine("本地", "按章装载失败：" + t.getMessage());
            return false;
        }
    }

    /** epub 按章装载：只解压当前这一章，其余等翻章再解 */
    private boolean openEpubChapterized(final File f, final com.mangjie.reader.reader.EpubBooks.Index ei, final long t0) {
        try {
            int saved = 0;
            try {
                saved = prefs.getInt(com.mangjie.reader.reader.BookProgress.chapterKey(f.getAbsolutePath()), 0);
            } catch (Throwable ignored) {}
            if (saved < 0 || saved >= ei.count()) saved = 0;
            String body = com.mangjie.reader.reader.EpubBooks.readChapter(f, ei, saved);
            if (body == null || body.trim().isEmpty()) return false;

            com.mangjie.reader.reader.Purify.Result pr = com.mangjie.reader.reader.Purify.clean(body);
            if (pr.hitCount > 0) {
                devLogLine("净化", "规则 " + pr.ruleCount + " 条　命中 " + pr.hitCount
                        + " 条　章内正文 " + body.length() + " → " + pr.text.length() + " 字");
            }
            body = pr.text;
            if (simplifyOn && com.mangjie.reader.reader.Simplifier.looksTraditional(body)) {
                body = com.mangjie.reader.reader.Simplifier.toSimplified(body);
            }
            List<Integer> pp = new ArrayList<Integer>();
            List<String> ss = com.mangjie.reader.reader.SentenceSplitter.split(body, pp);
            devLogLine("本地", "epub 按章装载　第" + (saved + 1) + "/" + ei.count() + " 章「"
                    + ei.titles.get(saved) + "」　章内 " + body.length() + " 字　切出 " + ss.size() + " 句"
                    + "　建表 " + ei.ms + "ms　耗时 " + (System.currentTimeMillis() - t0) + "ms");

            final com.mangjie.reader.reader.EpubBooks.Index fei = ei;
            final String fbody = body;
            final int fch = saved;
            final List<String> fss = ss;
            final List<Integer> fpp = pp;
            ui.post(new Runnable() { @Override public void run() {
                localMode = true;
                localFile = f;
                localIdx = null;
                epubIdx = fei;
                chapterNo = fch;
                finishOpenBook(f, fbody, fss, fpp, false);
                if (!stack.isEmpty() && top().reader && fei.count() > 1) {
                    top().extra = "　" + fei.titles.get(fch) + "（" + (fch + 1) + "/" + fei.count() + "）";
                    render(true);
                }
            } });
            return true;
        } catch (Throwable t) {
            devLogLine("本地", "epub 按章装载失败：" + t.getMessage());
            return false;
        }
    }

    /**
     * 网上阅读的正文也进分句阅读页：跟本地一样摸到哪句读哪句，有进度、书签、查找。
     * 以前这里按行铺 60 行，一次只显示前 60 行、长行还被截成 200 字，读完半章就摸不到了。
     */
    private void openNetChapter(String chapterTitle, String body, String key, boolean autoRead) {
        List<Integer> parents = new ArrayList<Integer>();
        List<String> sent = com.mangjie.reader.reader.SentenceSplitter.split(body, parents);   // 与本地同一套切句规则
        if (sent.isEmpty()) sent.add("（这一章没有正文）");
        com.mangjie.reader.reader.ChapterIndex.reset();
        netReading = true;
        findHits.clear();            // 换章了，旧章的命中下标对新正文无效
        netChapterSeq++;             // 作废上一章还没跑的延时回调
        bookName = (nb == null || nb.nbBookName == null) ? "" : nb.nbBookName;
        bookPath = "net://" + key;
        bookSent = sent;
        batchLast = -1;
        com.mangjie.reader.reader.SpeakCache.clear();
        bookParents = parents;
        List<Item> items = new ArrayList<Item>();
        for (String line : bookSent) items.add(new Item(line, "rd"));
        Screen s = new Screen(chapterTitle, items);
        s.reader = true;
        if (!stack.isEmpty() && top().reader) stack.set(stack.size() - 1, s);   // 连播下一章时替换，不叠栈
        else stack.add(s);
        int resume = loadPosResolved(sent);
        s.cur = Math.min(Math.max(resume, 0), sent.size() - 1);
        devLogLine("网上阅读", chapterTitle + "　切出 " + sent.size() + " 句　第" + (s.cur + 1) + "句");
        netChainJump = false;
        // 用户主动点开一章 → 进来先静候，等双击或中键；自动续章 → 直接接上念，不打断
        autoPlay = autoRead;
        readingNow = autoRead;
        if (!autoRead) { try { if (rdTts != null) rdTts.stop(); } catch (Exception ignored) {} }   // 静候时别让上一章的句子继续念
        render(true);
        final int fcur = s.cur;
        final int seq = netChapterSeq;
        final int ftotal = sent.size();
        final boolean fauto = autoRead;
        scroll.postDelayed(new Runnable() { @Override public void run() {
            if (!top().reader) return;
            if (seq != netChapterSeq) return;      // 这一章已经被换掉了，别拿旧句号去新章念
            if (fauto) {
                if (speechOn) speakSentence(fcur);
            } else if (!screenReaderOn) {
                // 静候提示：只报位置，不再有「上次读到」那句重复
                speak("第" + (fcur + 1) + "句，共" + ftotal + "句，双击开始朗读");
            }
        } }, 250);
    }

    /** 后台解析完成，回到主线程搭阅读页 */
    private void finishOpenBook(File f, String text, List<String> sent, List<Integer> parents, boolean truncated) {
        bookName = f.getName().replaceAll("(?i)(\\.txt|\\.epub|\\.md|\\.markdown|\\.docx|\\.mobi)$", "");
        bookPath = f.getAbsolutePath();
        if (!f.getAbsolutePath().equals(localMode ? (localFile == null ? "" : localFile.getAbsolutePath()) : "")) {
            localMode = false; localFile = null; localIdx = null; epubIdx = null; chapterNo = 0;
        }
        netReading = false;
        com.mangjie.reader.reader.ChapterIndex.reset();
        findHits.clear();            // 换书了，上一本的命中下标不能用
        bookSent = sent;
        batchLast = -1;
        com.mangjie.reader.reader.SpeakCache.clear();
        bookParents = parents;
        if (bookSent.isEmpty()) bookSent.add("（这篇没有可读的正文）");
        // 切章结果丢后台先算一遍：既写日志，也让用户第一次点目录时直接命中缓存，不在主线程全量扫
        final List<String> sentForToc = bookSent;
        final String tocName = bookName;
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                try {
                    int chNum = com.mangjie.reader.reader.ChapterIndex.indices(sentForToc).size();
                    devLogLine("目录", tocName + "　规则=" + com.mangjie.reader.reader.TocRules.lastRuleName()
                            + "　切出 " + chNum + " 章");
                } catch (Throwable t) {
                    devLogLine("目录", "后台算目录失败：" + t.getMessage());
                }
            }
        });
        List<Item> items = new ArrayList<>();
        for (String line : bookSent) items.add(new Item(line, "rd"));
        Screen s = new Screen(bookName, items);
        s.reader = true;
        addToList("recentList", f.getAbsolutePath());
        int resume = loadPosResolved(sent);
        if (resume > 0 && resume < bookSent.size()) s.cur = resume;
        if (!stack.isEmpty() && top().title.startsWith("正在打开")) stack.set(stack.size() - 1, s);
        else stack.add(s);
        autoPlay = false;          // 本地开书也静候：读屏还在念章节名时，别两个声音一起抢
        readingNow = false;
        try { if (rdTts != null) rdTts.stop(); } catch (Exception ignored) {}   // 静候时别让上一本的句子继续念
        render(true);
        if (truncated) toast("这本书太大，只载入了前 120 万字");
        final int fcur = s.cur;
        final boolean ftr = truncated;
        final int ftotal = bookSent.size();
        scroll.postDelayed(new Runnable() { @Override public void run() {   // 等列表量完高度再播，光标才不会落后
            if (!top().reader) return;
            if (screenReaderOn) return;        // 读屏在场时位置交给它念，本机不出声
            if (ftr) speak("这本书太大，只载入了前面的部分");
            else speak("第" + (fcur + 1) + "句，共" + ftotal + "句，双击开始朗读");
        } }, 250);
    }















    /** xhtml → 纯文字：去 head/脚本/样式、段落转换行、清标签、解实体 */


    /** 读文本：先按 UTF-8 严格解，失败退回 GBK */

    /** 书签存的是章内字符偏移，按章模式下每章单独一本，免得换章之后书签指到别的句子上 */
    private String bmKey() {
        String base = com.mangjie.reader.reader.BookProgress.bmKey(bookPath);
        return localMode ? (base + "#c" + chapterNo) : base;
    }
    private String posKey() { return com.mangjie.reader.reader.BookProgress.posKey(bookPath); }

    /** 书签对外一律是句序号；存在 prefs 里的是字符偏移，切句规则改了也不丢位置 */
    /** 书签键从路径换成指纹，旧书签搬一次，不然升级后全没了 */
    private void migrateLegacyBookmarks() {
        try {
            if (bookPath == null || bookPath.isEmpty()) return;
            String nb2 = bmKey();
            if (prefs.contains(nb2)) return;
            String lb = com.mangjie.reader.reader.BookProgress.legacyBmKey(bookPath);
            String old = prefs.getString(lb, "");
            if (old == null || old.isEmpty()) return;
            prefs.edit().putString(nb2, old).apply();
            if (prefs.getBoolean(com.mangjie.reader.reader.BookProgress.legacyBmOffKey(bookPath), false)) {
                prefs.edit().putBoolean(com.mangjie.reader.reader.BookProgress.bmOffKey(bookPath), true).apply();
            }
            devLogLine("书签", "旧路径书签已迁到指纹键");
        } catch (Throwable t) {
            devLogLine("书签", "书签迁移失败：" + t.getMessage());
        }
    }

    private List<Integer> readBookmarks() {
        migrateLegacyBookmarks();
        List<Integer> raw = com.mangjie.reader.reader.Bookmarks.parse(prefs.getString(bmKey(), ""));
        List<Integer> out = new ArrayList<>();
        if (bookSent.isEmpty()) return raw;
        if (prefs.getBoolean(com.mangjie.reader.reader.BookProgress.bmOffKey(bookPath), false)) {
            for (Integer o : raw) out.add(com.mangjie.reader.reader.BookProgress.indexAtOffset(bookSent, o));
            return out;
        }
        // 旧版本存的是句序号：顺手转成偏移存回去，只做一次
        List<Integer> offs = new ArrayList<>();
        for (Integer o : raw) {
            int idx = Math.min(Math.max(o, 0), bookSent.size() - 1);
            out.add(idx);
            offs.add(com.mangjie.reader.reader.BookProgress.offsetOf(bookSent, idx));
        }
        prefs.edit().putString(bmKey(), com.mangjie.reader.reader.Bookmarks.join(offs))
                .putBoolean(com.mangjie.reader.reader.BookProgress.bmOffKey(bookPath), true).apply();
        if (!raw.isEmpty()) devLogLine("阅读", "书签转为偏移存储：" + raw.size() + " 个");
        return out;
    }

    private void writeBookmarks(List<Integer> bms) {
        List<Integer> offs = new ArrayList<>();
        for (Integer i : bms) {
            offs.add(com.mangjie.reader.reader.BookProgress.offsetOf(bookSent, i == null ? 0 : i));
        }
        prefs.edit().putString(bmKey(), com.mangjie.reader.reader.Bookmarks.join(offs))
                .putBoolean(com.mangjie.reader.reader.BookProgress.bmOffKey(bookPath), true).apply();
    }

    private void addBookmark(int idx) {
        List<Integer> bms = readBookmarks();
        if (bms.contains(idx)) { speak("这一句已有书签"); toast("已有书签"); return; }
        bms.add(idx);
        writeBookmarks(bms);
        speak("已添加书签，第" + (idx + 1) + "句");
        toast("已添加书签");
    }

    private boolean isFav() { return prefs.getBoolean("fav:" + bookPath, false); }

    private void removeFromList(String key, String path) {
        String all = prefs.getString(key, "");
        if (all == null || all.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String one : all.split("\n")) {
            if (one.isEmpty() || one.equals(path)) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(one);
        }
        prefs.edit().putString(key, sb.toString()).apply();
    }

    private Screen bookmarkScreen() {
        return com.mangjie.reader.reader.Bookmarks.screen(readBookmarks(), bookSent);
    }

    /** 退到后台时兜底存一次：只在阅读页且句子表非空时才存，别在书架上乱写键 */
    private void saveCurrentPos() {
        try {
            if (stack.isEmpty() || !top().reader) return;
            if (bookPath == null || bookPath.isEmpty() || bookSent.isEmpty()) return;
            savePos(Math.max(0, Math.min(top().cur, bookSent.size() - 1)));
        } catch (Throwable t) {
            devLogLine("进度", "退后台存位置失败：" + t.getMessage());
        }
    }

    private void savePos(int idx) {
        // 两个都存：句子序号给旧逻辑看，字符偏移给以后的切句规则变动兜底
        prefs.edit().putInt(posKey(), idx)
                .putInt(offKey(), com.mangjie.reader.reader.BookProgress.offsetOf(bookSent, idx))
                .putInt(com.mangjie.reader.reader.BookProgress.totalKey(bookPath), bookSent.size())
                .putInt(com.mangjie.reader.reader.BookProgress.chapterKey(bookPath),
                        localMode ? chapterNo : 0).apply();
    }

    private String offKey() { return com.mangjie.reader.reader.BookProgress.posOffKey(bookPath); }

    /**
     * 进度键从「文件路径」换成「文件指纹」，老进度得搬一次，
     * 否则升级后所有人都会从第一句重新开始。
     */
    private void migrateLegacyPos() {
        try {
            if (bookPath == null || bookPath.isEmpty()) return;
            String nk = posKey(), no = offKey();
            if (prefs.contains(nk) || prefs.contains(no)) return;      // 已经是新键
            String lp = com.mangjie.reader.reader.BookProgress.legacyPosKey(bookPath);
            String lo = com.mangjie.reader.reader.BookProgress.legacyOffKey(bookPath);
            if (!prefs.contains(lp) && !prefs.contains(lo)) return;
            prefs.edit()
                    .putInt(nk, prefs.getInt(lp, 0))
                    .putInt(no, prefs.getInt(lo, -1))
                    .putInt(com.mangjie.reader.reader.BookProgress.totalKey(bookPath),
                            prefs.getInt(com.mangjie.reader.reader.BookProgress.legacyTotalKey(bookPath), 0))
                    .apply();
            devLogLine("进度", "旧路径进度已迁到指纹键：" + new java.io.File(bookPath).getName());
        } catch (Throwable t) {
            devLogLine("进度", "进度迁移失败：" + t.getMessage());
        }
    }

    private int loadPos() {
        migrateLegacyPos();
        return prefs.getInt(posKey(), 0);
    }

    /** 新切句的第一句下标：第 p 个一级句子在新列表里的首个位置，找不到返回 -1 */
    private int parentToIndex(int p) {
        for (int i = 0; i < bookParents.size(); i++) if (bookParents.get(i) == p) return i;
        return -1;
    }

    /** 读进度：优先按字符偏移找句（切句怎么改都不动位置）；只有偏移没有时才按旧句序号近似一次 */
    private int loadPosResolved(List<String> sent) {
        if (sent == null || sent.isEmpty()) return 0;
        migrateLegacyPos();
        int off = prefs.getInt(offKey(), -1);
        if (off >= 0) {
            // 偏移是「上一次打开那一格」里的字符位置：跨章（按章模式）或跨模式（整本 ↔ 按章）
            // 之后它可能比这一格正文还长，用它会把光标一下甩到末尾，先判一下合不合理
            int chars = 0;
            for (int i = 0; i < sent.size(); i++) {
                String s = sent.get(i);
                if (s != null) chars += s.length();
            }
            if (off >= chars) {
                devLogLine("阅读", "偏移 " + off + " 超出本格 " + chars + " 字，忽略偏移，改按句序号定位");
                off = -1;
            }
        }
        if (off >= 0) {
            int byOff = com.mangjie.reader.reader.BookProgress.indexAtOffset(sent, off);
            int oldIdx = loadPos();
            if (oldIdx != byOff) {
                devLogLine("阅读", "按偏移定位：第" + (byOff + 1) + "句（旧序号第" + (oldIdx + 1) + "句）");
            }
            return byOff;
        }
        int idx = loadPos();
        if (idx <= 0) return 0;
        int mapped = parentToIndex(idx);
        if (mapped < 0) mapped = Math.min(idx, sent.size() - 1);
        prefs.edit().putBoolean("posV2:" + bookPath, true).putInt(posKey(), mapped)
                .putInt(offKey(), com.mangjie.reader.reader.BookProgress.offsetOf(sent, mapped)).apply();
        devLogLine("阅读", "进度迁移：旧第" + (idx + 1) + "句 → 新第" + (mapped + 1) + "句");
        return mapped;
    }

    private void saveBool(String key, boolean v) {
        prefs.edit().putBoolean(key, v).apply();
    }

    /** 逗号分隔的路径列表，去重后放最前面 */
    private void addToList(String key, String path) {
        List<String> list = readList(key);
        list.remove(path);
        list.add(0, path);
        while (list.size() > 20) list.remove(list.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) { if (i > 0) sb.append("\n"); sb.append(list.get(i)); }
        prefs.edit().putString(key, sb.toString()).apply();
    }

    private List<String> readList(String key) {
        List<String> out = new ArrayList<String>();
        String raw = prefs.getString(key, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String t : raw.split("\n")) if (!t.trim().isEmpty()) out.add(t);
        return out;
    }

    private Screen pathListScreen(String title, String key, String emptyTip) {
        List<String> paths = readList(key);
        List<Item> items = new ArrayList<Item>();
        if (paths.isEmpty()) items.add(new Item("（" + emptyTip + "）"));
        for (String path : paths) {
            File f = new File(path);
            if (!f.exists()) continue;
            items.add(new Item(f.getName(), "book:" + path));
        }
        if (items.isEmpty()) items.add(new Item("（" + emptyTip + "）"));
        return new Screen(title, items);
    }

    /** 从目录/查找/书签跳回阅读页：只弹掉上层页面，阅读页本身不能弹掉 */
    private void jumpInReader(int idx) {
        while (stack.size() > 1 && !top().reader) stack.remove(stack.size() - 1);
        if (!top().reader) return;
        if (idx < 0 || idx >= bookSent.size()) return;
        // 只跳位置，不自动开读：停住等用户按确认键
        autoPlay = false;
        readingNow = false;
        resumeReadingAfterSpeak = false;
        if (rdTts != null) { try { rdTts.stop(); } catch (Exception ignored) {} }
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
        top().cur = idx;
        render(false);
        updateScreenAwake();
        savePos(idx);
        String tag = chapterPosTag(idx);
        toast(tag + "｜第 " + (idx + 1) + " 句 / 共 " + bookSent.size() + " 句");
        speak("已跳到" + tag + "，按确认键开始朗读");
        devLogLine("阅读", "跳到第" + (idx + 1) + "句，停住等确认　" + tag);
    }

    /** 只把光标和进度挪到某句，不下发朗读——批量朗读时 onDone 用它推进 */
    private void moveCursorTo(int idx) {
        if (idx < 0 || idx >= bookSent.size()) return;
        savePos(idx);      // 每句都存：prefs.apply 是异步落盘，代价低，不至于退后台就倒退好几句
        Screen sc = top();
        sc.cur = idx;
        if (idx < rowOffset || idx >= rowOffset + rowsBox.getChildCount()) render(false);
        highlightSelection();
        if (scroll != null) {
            final int fchild = idx - rowOffset;
            scroll.post(new Runnable() { @Override public void run() {
                View v = rowsBox.getChildAt(fchild);   // 等布局完再取坐标，否则新页全是 0
                if (v != null) scroll.scrollTo(0, Math.max(0, v.getTop() - dp(6)));
            } });
        }
        devLogLine("阅读光标", "跟到第" + (idx + 1) + "句　" + shortText(bookSent.get(idx)));
    }

    /** 读当前这一句，读完自动下一句（从这句起一次排一批进队列） */
    private void speakSentence(int idx) {
        if (idx < 0 || idx >= bookSent.size()) return;
        moveCursorTo(idx);
        readingNow = true;
        if (autoPlay) { holdReadFocus(); ensureReadService(); }
        if (dmRoute(idx)) return;
        speakAhead(idx);
    }

    /** 走点明通道念这一句：接管成功返回 true，调用方不要再动系统 TTS。
     *  点明自己管两句之间的承接（队列 + 完成回调），所以这里不再走「批」，也不挂看门狗。 */
    private boolean dmRoute(int idx) {
        if (!dmVoiceOn()) return false;
        if (!com.mangjie.reader.speech.DianmingVoice.isConnected()) {
            com.mangjie.reader.speech.DianmingVoice.probe(this, dmHost);   // 没连上就再试一次；连不上自然回落
            return false;
        }
        String raw = bookSent.get(idx);
        if (notReadable(raw)) { skipUnreadable(idx); advanceSentence(); return true; }   // 纯标点：跳过并说明
        String sp = com.mangjie.reader.reader.SpeakClean.clean(raw);
        String use = (sp != null && !sp.isEmpty()) ? sp : raw;
        if (com.mangjie.reader.speech.DianmingVoice.speakSentence(use, idx)) {
            devLogLine("点明", "交给点明　第" + (idx + 1) + "句　" + shortText(use));
            cancelResumeGuard();
            return true;
        }
        devLogLine("点明", "接管失败，回落系统语音　第" + (idx + 1) + "句");
        return false;
    }

    /** 阅读页按方向键：立刻把光标和朗读都挪到上/下一句，继续往下读 */
    private void readerJump(int dir) {
        Screen sc = top();
        int n = sc.cur + dir;
        if (n < 0) { speak("已经是第一句"); return; }
        if (n >= sc.items.size()) { speak("已经是最后一句"); return; }
        sc.cur = n;
        savePos(n);
        highlightSelection();
        ensureRowVisible(n);
        autoPlay = true;
        readingNow = true;
        devLogLine("阅读键", "跳到第" + (n + 1) + "句（" + (dir < 0 ? "上" : "下") + "）　"
                + shortText(sc.items.get(n).label));
        speakSentence(n);
    }

    /** 最近一次暂停/继续切换的时刻：用来挡机型把一次双击拆成两次 click（250ms 内重复的丢弃） */
    private long lastToggleAt = 0;

    /** 确认键：朗读中按一下暂停，暂停时按一下从当前句接着读
     *  from 只进日志，标明这次切换来自哪个入口（正文双击 / 耳机键 / 回车键 / 输入法 rd 键）。
     *  机型无障碍把一次双击拆成两次 ACTION_CLICK 时，会出现「暂停→立刻继续」的空转，这里挡掉。 */
    private void toggleRead(String from) {
        if (menuOn) return;      // 菜单开着时的双击不算暂停/继续：读屏长按弹菜单后抬手常会补一次 click
        long nowToggle = System.currentTimeMillis();
        long sinceToggle = (lastToggleAt == 0) ? -1 : (nowToggle - lastToggleAt);
        if (sinceToggle >= 0 && sinceToggle < 250) {
            devLogLine("阅读键", "忽略重复切换　来源=" + from + "　间隔=" + sinceToggle + "ms");
            return;
        }
        lastToggleAt = nowToggle;
        Screen sc = top();
        if (autoPlay) {
            autoPlay = false;
            readingNow = false;
            batchLast = -1;               // 队列已停，残留的 onDone 一律不再推进
            if (com.mangjie.reader.speech.DianmingVoice.isSpeaking())
                com.mangjie.reader.speech.DianmingVoice.stopReading();   // 点明通道要单独停，它不受 TTS.stop 影响
            try { if (rdTts != null) rdTts.stop(); } catch (Exception ignored) {}
            try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
            dropReadFocus();
            if (readServiceOn) ReadService.updateText(this, "已暂停 · 点播放继续");
            if (!screenReaderOn) speak("已暂停");    // 读屏在场时不给结果提示音，避免和它抢
            devLogLine("阅读键", "暂停于第" + (sc.cur + 1) + "句　来源=" + from
                    + (sinceToggle < 0 ? "　距上次=首次" : "　距上次=" + sinceToggle + "ms"));
            applyReaderTapTarget(sc);
        } else {
            autoPlay = true;
            readingNow = true;
            updateScreenAwake();
            devLogLine("阅读键", "继续于第" + (sc.cur + 1) + "句　来源=" + from
                    + (sinceToggle < 0 ? "　距上次=首次" : "　距上次=" + sinceToggle + "ms"));
            devLogLine("语音", "开始朗读　正文引擎=" + (readerVoice.enginePkg == null ? "系统默认" : readerVoice.enginePkg)
                    + "　rdReady=" + rdReady + "　有实例=" + (rdTts != null)
                    + "　语速=" + readerVoice.rateP + "%　加倍=" + readerVoice.speedMul + "　语调=" + readerVoice.pitchP
                    + "　有效倍率=" + String.format(Locale.US, "%.2f", rdEffRate())
                    + "　发音人=" + (rdVoiceObj == null ? "无" : rdVoiceObj.getName())
                    + "　语音开关=" + speechOn + "　读屏=" + screenReaderOn);
            speakSentence(sc.cur);
            applyReaderTapTarget(sc);
        }
    }

    /** 阅读朗读：长句按标点切块、QUEUE_ADD 排队，块间自然承接；只有最后一块的 onDone 才翻句 */
    private void speakReaderText(String text) { speakReaderText(-1, text); }

    /** 切块优先用缓存；念当前句时顺手把下一句算好，切句时少一次卡顿 */
    private void speakReaderText(int idx, String text) {
        String spoken = com.mangjie.reader.reader.SpeakClean.clean(text);
        if (spoken != null && !spoken.equals(text == null ? "" : text.trim())) {
            devLogLine("读法", "朗读净化　" + shortText(text) + " → " + shortText(spoken));
        }
        if (spoken != null && !spoken.isEmpty()) text = spoken;
        List<String> chunks = idx >= 0 ? com.mangjie.reader.reader.SpeakCache.get(idx, text) : null;
        boolean hit = chunks != null && !chunks.isEmpty();
        if (!hit) chunks = chunkForTts(text, 400);
        if (chunks.isEmpty()) return;
        if (idx >= 0 && !hit) com.mangjie.reader.reader.SpeakCache.put(idx, text, chunks);
        prewarmSentence(idx + 1);
        // 三级兜底：系统默认 → 界面引擎（判死后固定）→ 阅读引擎
        TextToSpeech rd;
        if (ttsStallCount >= 3 && sysReady && ttsSys != null) rd = ttsSys;
        else if (ttsForStallFallback || rdFallbackSticky) rd = tts;
        else rd = (rdReady && rdTts != null) ? rdTts : tts;
        if (!speechOn || rd == null) {
            devLogLine("语音", "正文朗读跳过　语音开关=" + speechOn + "　读屏=" + screenReaderOn + "　实例=" + (rd == null ? "无" : "有"));
            return;
        }
        if (rd != tts) { try { tts.stop(); } catch (Exception ignored) {} }
        boolean fallbackEngine = (rd == tts || rd == ttsSys);
        if (!fallbackEngine) {
            try { if (rdVoiceObj != null) rd.setVoice(rdVoiceObj); } catch (Exception ignored) {}
        } else if (rdVoiceObj != null) {
            devLogLine("语音", "已回退到备用引擎，跳过朗读发音人（那家的 voice 在别家无效）");
        }
        float rdRate = rdEffRate();
        float rdPitch = mapFactor(readerVoice.pitchP);
        try {
            rd.setSpeechRate(rdRate);
            rd.setPitch(rdPitch);
        } catch (Exception ignored) {}
        devLogLine("语音", "正文发句　引擎=" + ttsTag(rd) + "　rate=" + String.format(Locale.US, "%.2f", rdRate)
                + "　pitch=" + String.format(Locale.US, "%.2f", rdPitch)
                + "　发音人=" + (rdVoiceObj == null ? "无" : rdVoiceObj.getName())
                + "　分块=" + chunks.size() + "　首块 " + chunks.get(0).length() + " 字");
        if (chunks.size() > 1) devLogLine("阅读", "长句分成 " + chunks.size() + " 块朗读　共 " + text.length() + " 字");
        for (int i = 0; i < chunks.size(); i++) {
            String id = (i == chunks.size() - 1) ? "rd" : "rdmid";
            rd.speak(chunks.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, id);
        }
        // 句子出去了就挂看门狗：不管用的哪个引擎，不吭声都算它卡死
        // 1.1.47：把引擎标签和实际倍速一起传进去，窗口按「引擎基准 + 字数 ÷ 倍速」折算
        if (rd != null) {
            com.mangjie.reader.speech.TtsWatchdog.arm(text, ttsTag(rd), rdRate, new Runnable() {
                @Override public void run() { onTtsStall(); }
            });
        }
    }

    /** 每次判定「引擎卡死」的计数与重试标记，日志和设置页都看它 */
    private int ttsStallCount = 0;
    // 1.0.65：判死是不是卡在「同一句」。同一句连着判死两次，基本可以断定这句的收尾回调回不来，
    // 再怎么重建都会把上一句重念一遍，只能跳过它，否则永远走不出这一句
    private int sameStallIdx = -1;
    private int sameStallCount = 0;
    private int skipStreak = 0;        // 连续跳过了几句；真有一条通的就归零
    private boolean ttsStallRetried = false;
    private boolean rdFallbackSticky = false;   // 判死阅读引擎后，本会话固定用界面引擎
    private TextToSpeech ttsSys = null;        // 第三级兜底：系统默认引擎（不指定包名）
    private boolean sysReady = false;
    private int resumeMissCount = 0;           // 连续「没回调」的次数，三次就停下不再重发

    /**
     * 引擎拿了句子不出声：先重建一次引擎重发当前句；再不行就退回界面引擎，并说清楚。
     * 只重试一次——反复重建只会让朗读更碎。
     */
    private void onTtsStall() {
        // 在后台时系统会把 TTS 挂起：这时重建引擎多半也念不出声，只会每三秒拆一次引擎，
        // 拆完还得重念当前句——用户听到的就是同一句反复起头。改成只重发、不拆，回前台再说
        if (bgRun) {
            bgStallCount++;
            devLogLine("语音", "后台判死：不拆引擎，直接重发当前句（后台第 " + bgStallCount + " 次）");
            if (bgStallCount <= 3 && autoPlay && top() != null && top().reader) {
                final int bgCur = top().cur;
                ui.postDelayed(new Runnable() { @Override public void run() {
                    if (bgRun) speakSentence(bgCur);
                } }, 800);
            }
            return;
        }
        ttsStallCount++;
        // 整批只开口了第一块：这个引擎吃不下队列，别一次次重发整批，改成一句一送
        if (!ttsOneAtATime && batchChunkTotal > 1 && batchStartCount <= 1) {
            ttsOneAtATime = true;
            devLogLine("语音", "这批只念了第1块就没了（送 " + batchChunkTotal + " 块，开口 "
                    + batchStartCount + " 块），改成一句一送");
        }
        try {
            int curT = (top() != null && top().reader) ? top().cur : -1;
            devLogLine("句", "n=" + (curT < 0 ? "?" : (curT + 1)) + "/" + bookSent.size()
                    + " tok=" + batchSeq + " eng=" + batchEngTag
                    + " 超时 无开口" + (curT >= 0 && curT < bookSent.size()
                            ? " 本句字数=" + bookSent.get(curT).length() : ""));
        } catch (Throwable ignored) {}
        devLogLine("语音", "引擎超时未出声，判定卡死　第" + ttsStallCount + "次"
                + "　光标=" + (top() != null && top().reader ? (top().cur + 1) : -1)
                + "　本句字数=" + (top() != null && top().reader && top().cur < bookSent.size()
                        ? bookSent.get(top().cur).length() : -1));
        boolean inReader = top() != null && top().reader && autoPlay;
        // 同一句连着判死两次：这句的收尾回调回不来，重建也只会把上一句再念一遍。
        // 曾经就是这样卡在第491句上无限重建，用户听到的一直是490、491来回念
        if (inReader) {
            int curNow = top().cur;
            // 这句到底开过口没有。开过口却等到判死，说明它念了却收不了尾，
            // 重建引擎只会把它原样再念一遍（1.0.65 日志里就是 491 念两遍才跳过）
            boolean spokenThis =
                    ("b" + batchSeq + "#" + (curNow + 1)).equals(lastStartTailId)
                    || ("b" + batchSeq + "#" + curNow).equals(lastStartTailId);
            if (curNow == sameStallIdx) sameStallCount++;
            else { sameStallIdx = curNow; sameStallCount = 1; }
            if (sameStallCount >= 2 || spokenThis) {
                if (spokenThis && sameStallCount < 2) {
                    devLogLine("语音", "这句开过口却没收尾，重建没用，直接跳过");
                }
                devLogLine("语音", "同一句连续判死 " + sameStallCount + " 次，跳过第" + (curNow + 1) + "句");
                sameStallIdx = -1;
                sameStallCount = 0;
                ttsStallRetried = false;
                ttsStallCount = 0;
                speak("第" + (curNow + 1) + "句读不出来，已跳过");
                skipStreak++;
                if (skipStreak >= 5) {
                    devLogLine("语音", "连续跳过 " + skipStreak + " 句，停止朗读");
                    skipStreak = 0;
                    autoPlay = false;
                    speak("连续多句读不出来，已停止朗读，请换一个朗读引擎");
                    return;
                }
                if (curNow + 1 < bookSent.size()) {
                    readingNow = true;
                    speakSentence(curNow + 1);
                }
                return;
            }
        }
        if (!ttsStallRetried) {
            ttsStallRetried = true;
            devLogLine("语音", "第一次卡死：重建阅读引擎后重发当前句");
            rebuildRdTts();
            if (inReader) {
                final int cur = top().cur;
                ui.postDelayed(new Runnable() { @Override public void run() {
                    speakSentence(cur);
                } }, 900);     // 等新引擎 onInit 回来，早于它说话只会再失败一次
            }
            return;
        }
        if (ttsStallCount == 2) {
            rdFallbackSticky = true;                 // 判死：本会话不再回那个坏引擎
            devLogLine("语音", "第二次卡死：本次会话固定改用界面引擎");
            speak("朗读引擎没响应，本次改用界面语音");
        } else if (ttsStallCount == 3) {
            ensureSysTts();
            devLogLine("语音", "第三次卡死：改投系统默认引擎");
            speak("朗读引擎还是没响应，已切到系统默认语音");
        } else {
            devLogLine("语音", "第" + ttsStallCount + "次卡死：停止自动重试");    
            speak("朗读引擎没响应，请到语音正文里换一个引擎");
            return;
        }
        if (inReader) {
            final int cur = top().cur;
            ui.postDelayed(new Runnable() { @Override public void run() {
                ttsForStallFallback = false;
                speakSentence(cur);
            } }, 700);
        }
    }

    /** 系统默认引擎：不指定包名，让系统按用户设置的默认走 */
    private void ensureSysTts() {
        if (ttsSys != null && sysReady) return;
        try { if (ttsSys != null) { ttsSys.stop(); ttsSys.shutdown(); } } catch (Exception ignored) {}
        sysReady = false;
        ttsSys = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                sysReady = status == TextToSpeech.SUCCESS;
                devLogLine("语音", "系统默认引擎 init 返回 " + status + (sysReady ? "（可用）" : "（不可用）"));
            }
        });
        try { ttsSys.setOnUtteranceProgressListener(utteranceListener); } catch (Exception ignored) {}
    }

    /** 卡死退路：这一次强制用界面引擎念 */
    private boolean ttsForStallFallback = false;

    /** 一次往引擎队列里排多少句。对齐开源阅读：整页一次排进去，onDone 只推位置，不重新下发 */
    private static final int AHEAD = 15;

    /**
     * 一次只送一句。有些引擎（荣耀 PTP-AN70 上的 org.nobody.multitts 就是）收下整批后
     * 只念批内第一块，后面的 QUEUE_ADD 一声不响，onDone 也不回头——
     * 日志 a11y-20261121-230919：送批 15 块，只有 b1#0 开口，b1#1 之后再没出现。
     * 判出这种情况就自动改成一句一送：下一句由上一句的 onDone 带出来，不依赖队列。
     */
    private boolean ttsOneAtATime = false;
    private int batchChunkTotal = 0;      // 本批一共送了几块
    private int batchStartCount = 0;      // 本批实际开口了几块

    /** 本批最后一句的句子号；-1 表示没有有效批次，残留的 onDone 一律忽略 */
    private int batchLast = -1;

    /** 批次序号：用户跳句后旧批的 onDone 还可能在路上，靠它认出来扔掉 */
    private int batchSeq = 0;

    // 1.1.46：批量测试的日志要靠「一句一条」来算各引擎的真实完成率。
    // 只看「送批/读完」两个总数，推不出是哪台机器的哪个引擎把句子吞了
    private String batchEngTag = "";        // 本批实际用的引擎
    private long batchSentAt = 0;           // 本批发出的时刻，用来算首字延迟
    // 1.0.66：正文被外部打断（读屏抢队列、来电、系统抢焦点）时引擎会回 onError。
    // 以前正文 id 走到这里只撤表、打一行日志，什么都不做——朗读就停在那一句上等人按继续。
    // 现在重发一次，连续两次才跳过
    private int errRetry = 0;

    /** 纯标点、空白、控制符的句子不念（对齐开源阅读的 notReadAloudRegex） */
    private static final java.util.regex.Pattern NOT_READ =
            java.util.regex.Pattern.compile("^[\\s\\p{C}\\p{P}\\p{Z}\\p{S}]+$");

    private boolean notReadable(String s) {
        return s == null || s.isEmpty() || NOT_READ.matcher(s).matches();
    }

    /** 连续跳过的纯标点句数；念到可读句、或播报过一次之后归零 */
    private int unreadableStreak = 0;

    /**
     * 纯标点句统一从这里跳过。
     * 以前是直接 advanceSentence 走掉：日志没有、读屏没有、光标还停在上一句，
     * 用户听到的是「跳过去了」，高亮和句号却对不上。现在三件事一起补：
     * 记一条日志、把光标跟到被跳过的这一句、连续多句时合并播报一次。
     */
    private void skipUnreadable(int idx) {
        unreadableStreak++;
        devLogLine("朗读", "跳过第" + (idx + 1) + "句（纯标点）　连续 " + unreadableStreak + " 句");
        moveCursorTo(idx);
        if (unreadableStreak >= 3) {
            // 逐句念「跳过」比不念还吵，连续三句以上才播报一次并归零
            speak("已跳过 " + unreadableStreak + " 句空白");
            unreadableStreak = 0;
        }
    }

    /**
     * 从 from 起一次排 AHEAD 句进引擎队列。
     * 首块 QUEUE_FLUSH 清掉旧队列，其余 QUEUE_ADD——引擎连续合成，句与句之间没有往返。
     * 每句最后一块的 id 是 "b"+句号，onDone 靠它推进光标。
     */
    private void speakAhead(int from) {
        batchLast = -1;
        if (from < 0 || from >= bookSent.size()) return;
        if (!speechOn) return;
        // 三级兜底：系统默认 → 界面引擎（判死后固定）→ 阅读引擎
        TextToSpeech rd;
        if (ttsStallCount >= 3 && sysReady && ttsSys != null) rd = ttsSys;
        else if (ttsForStallFallback || rdFallbackSticky) rd = tts;
        else rd = (rdReady && rdTts != null) ? rdTts : tts;
        if (rd == null) return;
        if (rd != tts) { try { tts.stop(); } catch (Exception ignored) {} }
        boolean fallbackEngine = (rd == tts || rd == ttsSys);
        if (!fallbackEngine) {
            try { if (rdVoiceObj != null) rd.setVoice(rdVoiceObj); } catch (Exception ignored) {}
        } else if (rdVoiceObj != null) {
            devLogLine("语音", "已回退到备用引擎，跳过朗读发音人（那家的 voice 在别家无效）");
        }
        try {
            rd.setSpeechRate(rdEffRate());
            rd.setPitch(mapFactor(readerVoice.pitchP));
        } catch (Exception ignored) {}

        final int myseq = ++batchSeq;
        batchEngTag = ttsTag(rd);                       // [句] 事件里带出去，按引擎分组统计
        batchSentAt = System.currentTimeMillis();
        if (autoPlay) com.mangjie.reader.speech.SilentWarmup.warm();   // 先把音道焐热，首字不至于发迟
        int last = -1;
        StringBuilder whole = new StringBuilder();
        java.util.List<String> sendText = new java.util.ArrayList<String>();
        java.util.List<String> sendId = new java.util.ArrayList<String>();
        int end = Math.min(bookSent.size(), from + (ttsOneAtATime ? 1 : AHEAD));
        for (int i = from; i < end; i++) {
            String raw = bookSent.get(i);
            if (notReadable(raw)) { devLogLine("朗读", "送批时跳过第" + (i + 1) + "句（纯标点）"); continue; }
            String sp = com.mangjie.reader.reader.SpeakClean.clean(raw);
            String use = (sp != null && !sp.isEmpty()) ? sp : raw;
            List<String> chunks = com.mangjie.reader.reader.SpeakCache.get(i, use);
            if (chunks == null || chunks.isEmpty()) {
                chunks = chunkForTts(use, 400);
                com.mangjie.reader.reader.SpeakCache.put(i, use, chunks);
            }
            for (int k = 0; k < chunks.size(); k++) {
                String id = (k == chunks.size() - 1) ? ("b" + myseq + "#" + i) : ("b" + myseq + "#" + i + "m");
                sendText.add(chunks.get(k));
                sendId.add(id);
            }
            whole.append(use);
            last = i;
        }
        if (last < 0) {
            // 这一页全是标点：跳过整段继续往后找
            if (from + AHEAD < bookSent.size()) speakAhead(from + AHEAD);
            return;
        }
        batchLast = last;
        batchChunkTotal = sendText.size();
        batchStartCount = 0;
        cancelResumeGuard();               // 正文已经要送句了，界面提示那张兜底表不该再留
        prewarmSentence(last + 1);
        // 看门狗必须在送句之前挂：引擎的开口回调比它晚到，才撤得掉，不会三秒后误判卡死
        if (rd == rdTts) {
            com.mangjie.reader.speech.TtsWatchdog.arm(whole.toString(), batchEngTag,
                    rdEffRate(), new Runnable() {
                @Override public void run() { onTtsStall(); }
            });
        }
        devLogLine("语音", "送批 " + myseq + " 第" + (from + 1) + "→" + (last + 1) + " 句，块 " + sendText.size()
                + "，引擎=" + (rd == rdTts ? "阅读" : (rd == tts ? "界面" : "系统"))
                + "，看门狗预计 " + (com.mangjie.reader.speech.TtsWatchdog.estimateMs(whole.toString()) / 1000) + " 秒");
        for (int j = 0; j < sendText.size(); j++) {
            rd.speak(sendText.get(j), j == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, sendId.get(j));
        }
    }

    /** 批内某句念完：推进光标；排到最后一句就续排下一批 */
    private void onBatchDone(int i) {
        ttsStallRetried = false;          // 这批确实读通了，下次真卡死仍给一次重建机会
        ttsStallCount = 0;
        rdFallbackSticky = false;
        skipStreak = 0;                   // 有一句真读通了，连续跳过的计数归零
        if (batchLast < 0 || i > batchLast) return;      // 批次已作废（用户跳走了）
        if (i < bookSent.size()) moveCursorTo(i);
        if (i < batchLast) {
            // 队列里还有下一句，正常情况下马上就该出声；不出声同样按卡死处理
            com.mangjie.reader.speech.TtsWatchdog.arm(bookSent.get(i + 1), batchEngTag,
                    rdEffRate(), new Runnable() {
                @Override public void run() { onTtsStall(); }
            });
            // 关键：下一句的开口回调常常比这里早到（日志里只差 2ms）。arm 里会把「已开口」清掉，
            // 若这句其实正在念，三秒后必然误判卡死→拆引擎→从当前句重念，后台就是靠这一步循环的
            if (("b" + batchSeq + "#" + (i + 1)).equals(lastStartTailId)) {
                com.mangjie.reader.speech.TtsWatchdog.markStarted();
                devLogLine("语音", "下一句已在念，补记已开口 id=" + lastStartTailId);
            }
            return;                                      // 交给引擎自己往下念
        }
        if (i + 1 >= bookSent.size()) { advanceSentence(); return; }
        speakAhead(i + 1);
    }

    /** 后台把下一句的净化与切块先算好，放进缓存 */
    private void prewarmSentence(int next) {
        if (next < 0 || next >= bookSent.size()) return;
        if (com.mangjie.reader.reader.SpeakCache.get(next, bookSent.get(next)) != null) return;
        final int ni = next;
        final String raw = bookSent.get(next);
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            try {
                String sp = com.mangjie.reader.reader.SpeakClean.clean(raw);
                String use = (sp != null && !sp.isEmpty()) ? sp : raw;
                List<String> c = chunkForTts(use, 400);
                com.mangjie.reader.reader.SpeakCache.put(ni, use, c);
            } catch (Throwable ignored) {}
        } });
    }

    /** 按句末/逗号切成不超过 limit 字的块，尽量落在标点上 */

    private void advanceSentence() {
        devLogLine("朗读", "推进：cur=" + top().cur + "　autoPlay=" + autoPlay
                + "　本章句数=" + bookSent.size() + "　本地=" + localMode + "　网文=" + netReading);
        if (!top().reader || !autoPlay) return;
        int next = top().cur + 1;
        if (next >= bookSent.size()) {
            if (localMode && chapCount() > chapterNo + 1) {
                devLogLine("本地", "本章读完，进下一章：" + (chapterNo + 2) + "/" + chapCount());
                openLocalChapter(chapterNo + 1);
                return;
            }
            if (netReading && prefs.getBoolean("nbChain", true) && nb != null && !nb.nbChapters.isEmpty()) {
                int ci = nbChapterIndex();
                if (ci >= 0 && ci + 1 < nb.nbChapters.size()) {
                    devLogLine("网上阅读", "本章读完，接下一章");
                    netChainJump = true;
                    nb.nbOpenChapter(ci + 1, true);
                    return;
                }
            }
            autoPlay = false;
            readingNow = false;
            speak(netReading ? "本章读完" : "全文读完");
            return;
        }
        speakSentence(next);
    }

    /** 当前这本书有多少格（章或段）；不是按章读的返回 0 */
    private int chapCount() {
        if (localIdx != null) return localIdx.chapters.size();
        if (epubIdx != null) return epubIdx.count();
        return 0;
    }

    private String chapTitle(int n) {
        if (localIdx != null && n >= 0 && n < localIdx.chapters.size()) return localIdx.chapters.get(n).title;
        if (epubIdx != null && n >= 0 && n < epubIdx.count()) return epubIdx.titles.get(n);
        return "第 " + (n + 1) + " 章";
    }

    /** 读某一格正文：txt 走字节偏移，epub 走 zip 条目 */
    private String readChap(File f, int n) {
        if (f == null) return null;
        if (localIdx != null) return com.mangjie.reader.reader.LocalText.readChapter(f, localIdx, n);
        if (epubIdx != null) return com.mangjie.reader.reader.EpubBooks.readChapter(f, epubIdx, n);
        return null;
    }

    /** 按章模式：把第 n 章读进来替换当前正文，接着念 */
    private void openLocalChapter(final int n) { openLocalChapter(n, 0); }

    /** startChar > 0 时：切句后按字符偏移定位到那一句（查找跳转用） */
    private void openLocalChapter(final int n, final int startChar) {
        final File f = localFile;
        final boolean txt = localIdx != null;
        final int total = chapCount();
        if (f == null || total <= 0) return;
        if (n < 0 || n >= total) return;
        if (!screenReaderOn) speak(txt ? ("第" + (n + 1) + "章") : chapTitle(n));   // 读屏在场时章节由它念
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            String body = null;
            List<String> ss = new ArrayList<String>();
            List<Integer> pp = new ArrayList<Integer>();
            try {
                body = readChap(f, n);
                if (body != null) {
                    body = com.mangjie.reader.reader.Purify.clean(body).text;
                    if (simplifyOn && com.mangjie.reader.reader.Simplifier.looksTraditional(body)) {
                        body = com.mangjie.reader.reader.Simplifier.toSimplified(body);
                    }
                    ss = com.mangjie.reader.reader.SentenceSplitter.split(body, pp);
                }
            } catch (Throwable ignored) {}
            if (body == null || ss.isEmpty()) ss.add("（这一章没有可读的正文）");
            final List<String> fss = ss;
            final List<Integer> fpp = pp;
            ui.post(new Runnable() { @Override public void run() {
                chapterNo = n;
                bookSent = fss;
                bookParents = fpp;
                batchLast = -1;
                com.mangjie.reader.reader.SpeakCache.clear();
                com.mangjie.reader.reader.ChapterIndex.reset();
                findHits.clear();
                int goIdx = 0;
                if (startChar > 0) {
                    int acc = 0;
                    for (int i = 0; i < bookSent.size(); i++) {
                        if (acc >= startChar) { goIdx = i; break; }
                        acc += bookSent.get(i).length();
                    }
                }
                Screen sc = top();
                if (sc != null && sc.reader) {
                    sc.items.clear();
                    for (String line : bookSent) sc.items.add(new Item(line, "rd"));
                    sc.cur = goIdx;
                    sc.extra = "　" + chapTitle(n) + "（" + (n + 1) + "/" + total + "）";
                    render(true);
                    speakSentence(goIdx);
                }
            } });
        } });
    }

    /** 全书百分比：按章模式下按「章号 + 章内比例」折算，不是章内进度 */
    private int bookPercent(int cur) {
        int total = chapCount();
        if (localMode && total > 1) {
            double per = bookSent.isEmpty() ? 0 : Math.min(1.0, (cur + 1.0) / bookSent.size());
            double p = (chapterNo + per) / total * 100.0;
            int v = (int) Math.round(p);
            if (v < 1) v = 1;
            if (v > 100) v = 100;
            return v;
        }
        return com.mangjie.reader.reader.BookProgress.percent(bookSent.size(), cur);
    }

    private void speakId(String text, String id) {
        if (!speechOn) return;
        if ("rd".equals(id)) {
            TextToSpeech rd = (rdReady && rdTts != null) ? rdTts : tts;
            if (rd != tts) { try { tts.stop(); } catch (Exception ignored) {} }
            try {
                if (rdVoiceObj != null) rd.setVoice(rdVoiceObj);
            } catch (Exception ignored) {}
            try {
                rd.setSpeechRate(rdEffRate());
                rd.setPitch(mapFactor(readerVoice.pitchP));
            } catch (Exception ignored) {}
            if (rdReady && rdTts != null) { rdTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id); }
            else if (ttsReady && tts != null) { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id); }
            return;
        }
        if (!ttsReady || tts == null) return;
        {
            try { if (voiceObj != null) tts.setVoice(voiceObj); } catch (Exception ignored) {}
            tts.setSpeechRate(effRate());
            tts.setPitch(mapFactor(menuVoice.pitchP));
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
    }

    // ---------------------------------------------------------------- 渲染

    private void render(boolean announce) {
        lastHighlightIdx = -1;
        if (coverMini != null) coverMini.setVisibility(View.GONE);   // 先藏起来，详情页末尾再按焦点摆放
        applyThemeSurfaces();
        // 浮层和网格页的行号从 0 起算：阅读页遗留的 rowOffset 必须清掉，
        // 否则在书深处（第几千句）打开菜单，摸第 1 行会被算成第 3003 行，直接越界 → 菜单完全没反应
        if (dialogOn) { rowOffset = 0; renderDialog(); return; }
        if (menuOn) { rowOffset = 0; renderMenuOverlay(); return; }
        rowOffset = 0;
        if (inputOn) return;

        Screen sc = top();
        if (sc.voiceCtl) refreshVoicePanel(sc);
        titleView.setText(sc.extra == null ? sc.title : (sc.title + sc.extra));
        titleView.setContentDescription(sc.title);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) titleView.setAccessibilityHeading(true);
        } catch (Throwable ignored) {}
        clockView.setText(hhmm.format(new Date()));

        if (footerView != null) {
            footerView.setVisibility(View.GONE);
            footerView.setGravity(Gravity.LEFT);
        }
        rowsBox.removeAllViews();
        rowLead = 0;
        audioSeek = null; audioTime = null;
        buildTabs(sc);
        if (sc.player) { addPlayerHeader(sc); rowLead = 1; }
        TextView selectedView = null;
        int firstRow = 0, lastRow = sc.items.size();
        if (sc.reader) {       // 1.6 万句不能一次全建成 View，否则主线程 ANR
            int[] w = com.mangjie.reader.reader.BookWindow.of(sc.items.size(), sc.cur);
            firstRow = w[0];
            lastRow = w[1];
        }
        rowOffset = firstRow;
        for (int i = firstRow; i < lastRow; i++) {
            final int idx = i;
            String key = sc.items.get(i).key;
            String label = sc.items.get(i).label;
            TextView row;
            {
            String rowDesc = sc.reader
                    ? label                                   // 阅读页 1.6 万句，每句都报「第几个」太吵
                    : com.mangjie.reader.reader.PageText.row(label, i, sc.items.size())
                            + (i == sc.cur ? "，当前" : "");     // 选中不能只靠底色，读屏得说出来
            row = makeRow(label, i == sc.cur, rowDesc);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // 读屏双击会直接对获得焦点的这一行发点击：一律执行这一行，
                    // 不能因为程序里的 cur 和读屏焦点错开就只挪焦点（那会表现成「双击没反应」）。
                    Screen s0 = top();
                    if (s0.reader && screenReaderOn) return;   // 正文页的双击归读屏，不进 activate
                    if (idx < 0 || idx >= s0.items.size()) return;
                    s0.cur = idx;
                    highlightSelection();
                    activate();
                }
            });
            row.setLongClickable(true);
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Screen sL = top();
                    if (sL.reader && !dialogOn && !menuOn) { openReaderMenu(); return true; }
                    if (sL.fileMode && !dialogOn && !menuOn) { openFileMenu(); return true; }
                    // 发现·书源页：长按某个源，出这一个源的行菜单（对齐 Legado 的 explore_item）
                    if ("bsrcExplore".equals(sL.key) && !dialogOn && !menuOn) {
                        if (idx >= 0 && idx < sL.items.size()) {
                            String ik = sL.items.get(idx).key;
                            if (ik != null && ik.startsWith("nb:esrc:")) {
                                sL.cur = idx;
                                highlightSelection();
                                openExploreRowMenu(idx);
                                return true;
                            }
                        }
                    }
                    return false;
                }
            });
            }
            bindA11yFocus(row, i);
            // 正文行永远不进无障碍树，跟读屏开没开无关：
            //  - 读屏开着：行留在焦点里，它会把正在念的那一句再念一遍，两个声音叠成一团
            //  - 读屏关着：没人读无障碍树，关掉也没副作用
            // 更重要的是渲染从此不再依赖读屏状态——以前状态一变就要重渲染，重渲染又触发播报，形成回路。
            // 正文页的点击由外层容器接（双击 = 开始/暂停朗读），长按由容器接（打开阅读菜单）。
            if (sc.reader) {
                row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.setFocusable(false);
                row.setFocusableInTouchMode(false);
                row.setClickable(false);
                row.setLongClickable(false);
            }
            if (sc.player && i == 6) {        // 进度行：拖动条 + 时间
                rowsBox.addView(makeSeekRow(), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                continue;
            }
            if (sc.player && i == 0) {        // 播放/暂停：做成大按钮
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp() + 4);
                row.setGravity(Gravity.CENTER);
                row.setBackgroundColor(0xFFD9483F);
                row.setTextColor(0xFFFFFFFF);
                row.setSingleLine(true);
            }
            if (sc.reader) {
                // 阅读页必须显示整句全文：取消单行与省略号，让字自己软换行
                row.setSingleLine(false);
                row.setMaxLines(Integer.MAX_VALUE);
                row.setEllipsize(null);
                row.setGravity(Gravity.TOP | Gravity.LEFT);
                row.setLineSpacing(dp(lineSpaceAddDp()), lineSpaceMult());
                boolean isChap = chapterPattern().matcher(label.trim()).find() && label.length() <= 40;
                // 章节标题：上、下都留出一整行空档，正文不再贴着标题
                row.setPadding(dp(sidePadDp()), isChap ? dp(20) : dp(6), dp(sidePadDp()), isChap ? dp(20) : dp(6));
                Typeface tf = currentTypeface();
                if (tf != null) row.setTypeface(tf, isChap ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                else if (isChap) row.setTypeface(row.getTypeface(), android.graphics.Typeface.BOLD);
                rowsBox.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                rowsBox.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(sc.player && i == 0 ? ROW_PLAY_DP : ROW_DP)));
            }
            if (i == sc.cur) selectedView = row;
        }

        scrollToSelection();
        if (screenReaderOn && selectedView != null && !sc.reader) {
            // 读屏模式下页面重建后焦点会掉到第一个节点；这里把焦点放回本页记住的那一行，
            // 从子页返回就落回刚点到的那一项，不再是总从第一项摸起
            final TextView keep = selectedView;
            keep.post(new Runnable() { @Override public void run() {
                try {
                    keep.performAccessibilityAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
                } catch (Throwable ignored) {}
            } });
        } else {
            focusRow(selectedView);
        }
        lastHighlightIdx = sc.cur;     // 建行时已把这一行涂红，记下来，换行时才擦得掉
        placeCover();
        if (scrollWrap != null) scrollWrap.post(new Runnable() { @Override public void run() { placeCover(); } });
        updateScreenAwake();
        applyReaderTapTarget(sc);
        if (announce) {
            if (!screenReaderOn) speak(sc.title);   // 读屏在场时标题交给它念，本机不再念第二声
            notifyPageChanged(sc);
        }
    }

    /** 朗读时把阅读页收干净：正文以外的东西全退出无障碍树 */
    private void setA11yHidden(View v, boolean hide) {
        if (v == null) return;
        v.setImportantForAccessibility(hide
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    /** 正文页整页做成一个「双击开始 / 双击暂停」的开关。
     *  正文行全部退出了无障碍树，读屏进正文只会聚焦到这一层：它念这句描述、双击它，
     *  正好就是我们要的动作，不用再让读屏摸到任何一行。
     *  朗读进行中把标题、电量、时钟、标签栏、页脚一起退出无障碍树：读屏开着时它们一被摸到
     *  就念，会和正在念的正文叠成两股声音。描述也换成短的，不再带「第N句共M句」，
     *  免得换句时读屏跟着重念一遍。 */
    private void applyReaderTapTarget(Screen sc) {
        if (scroll == null) return;
        if (sc.reader) {
            boolean quiet = readingNow && autoPlay;      // 正在朗读：只留正文一个可访问节点
            setA11yHidden(titleView, quiet);
            setA11yHidden(statusView, quiet);
            setA11yHidden(clockView, quiet);
            setA11yHidden(tabsBox, quiet);
            setA11yHidden(footerView, quiet);
            scroll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            scroll.setClickable(true);
            scroll.setFocusable(true);
            // 正文控件上不放任何文字：没有描述，读屏摸到它就没东西可念。
            // 位置信息（第N句、共M句）不再挂在这上面，靠翻句时的语音和菜单去问。
            scroll.setContentDescription(null);
            // 读屏开着时 App 收不到长按和滑动（dispatchTouchEvent 整条交回系统），
            // 只有无障碍动作还能进来：读屏「操作」里切换一下再双击，就能呼出阅读菜单
            scroll.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host,
                        android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setText(null);                 // 一个字都不给读屏念
                    info.setContentDescription(null);
                    info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                            R.id.act_reader_menu, "打开阅读菜单"));
                    info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                            R.id.act_reader_prev, "上一句"));
                    info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                            R.id.act_reader_next, "下一句"));
                }
                @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                    if (action == R.id.act_reader_menu) {
                        devLogLine("阅读键", "无障碍动作 → 打开阅读菜单");
                        openPageMenu();
                        return true;
                    }
                    if (action == R.id.act_reader_prev) { readerJump(-1); return true; }
                    if (action == R.id.act_reader_next) { readerJump(1); return true; }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
            scroll.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toggleRead("正文双击"); }
            });
            scroll.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (!dialogOn && !menuOn) { openReaderMenu(); return true; }
                    return false;
                }
            });
        } else {
            setA11yHidden(titleView, false);
            setA11yHidden(statusView, false);
            setA11yHidden(clockView, false);
            setA11yHidden(tabsBox, false);
            setA11yHidden(footerView, false);
            scroll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            scroll.setClickable(false);
            scroll.setFocusable(false);
            scroll.setContentDescription(null);
            scroll.setOnClickListener(null);
            scroll.setOnLongClickListener(null);
            scroll.setAccessibilityDelegate(null);
        }
    }

    /** 所有页面统一遵守系统熄屏：不常亮，有按键/触摸时系统自己会续时 */
    private void updateScreenAwake() {
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception ignored) {}
    }

    private void applyThemeSurfaces() {
        if (column != null) column.setBackgroundColor(palBg());
        applyTitleTheme();                 // 标题栏底色与标题文字一起跟主题
        if (statusView != null) statusView.setTextColor(palFg());
        if (clockView != null) clockView.setTextColor(palFg());
        if (separator != null) separator.setBackgroundColor(palFg());
        if (scroll != null) scroll.setBackgroundColor(palBg());
        applyWindowColors();
    }

    private void bindA11yFocus(TextView row, final int index) {
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void sendAccessibilityEvent(View host, int eventType) {
                super.sendAccessibilityEvent(host, eventType);
                if (devLog) {
                    String name;
                    switch (eventType) {
                        case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: name = "无障碍焦点"; break;
                        case AccessibilityEvent.TYPE_VIEW_CLICKED: name = "点击"; break;
                        case AccessibilityEvent.TYPE_VIEW_SCROLLED: name = "滚动"; break;
                        case AccessibilityEvent.TYPE_VIEW_SELECTED: name = "选中"; break;
                        case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: name = "文字变化"; break;
                        default: name = "事件" + eventType;
                    }
                    // 取这一行自己的文字/描述；以前用 top().items.get(index) 取，菜单和弹窗里
                    // index 是浮层行号，对到的是书里的句子，日志全是错的
                    CharSequence desc = host.getContentDescription();
                    CharSequence txt = host instanceof TextView ? ((TextView) host).getText() : null;
                    String label = desc != null && desc.length() > 0
                            ? desc.toString() : (txt == null ? "" : txt.toString());
                    devLogLine("读屏侧外部", name + "　行=" + index + "　文字=" + label);
                }
                if (eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) onA11yFocus(index);
            }
        });
    }

    /** 页面切换通知读屏：窗口状态变化 + 标题与项数播报 */
    private void notifyPageChanged(Screen sc) {
        devLogLine("页面", com.mangjie.reader.reader.PageText.pageLog(sc.title, sc.items.size(), sc.cur));
        try {
            titleView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        } catch (Exception ignored) {}
        if (screenReaderOn) {
            try {
                String say = com.mangjie.reader.reader.PageText.page(sc.title, sc.items.size());
                getWindow().getDecorView().announceForAccessibility(say);
            } catch (Exception ignored) {}
        }
    }

    private void onA11yFocus(int index) {
        devLogLine("读屏焦点", "第" + index + "行　页面=" + (stack.isEmpty() ? "" : top().title)
                + "　当前cur=" + (stack.isEmpty() ? -1 : top().cur));
        if (dialogOn) {
            int i = index - 1;
            if (i < 0 || i >= dialogBtns.length) return;
            if (dialogSel != i) { dialogSel = i; highlightDialog(); }
            return;
        }
        // 菜单/输入页里 index 是浮层行号，不能当阅读页句号用——以前会把 cur 改成 0~14，
        // 阅读位置被读屏一个行一个行地拖走
        if (menuOn) {
            String[] names = menuNames();
            if (index >= 0 && index < names.length && menuSel != index) menuSel = index;
            return;
        }
        if (inputOn) return;
        Screen sc = top();
        if (index < 0 || index >= sc.items.size()) return;
        if (sc.cur != index) {
            devLogLine("自身光标", "读屏把焦点送到第" + index + "行，程序原 cur=" + sc.cur
                    + "，已跟随读屏改为 " + index);
            sc.cur = index;
            highlightSelection();
        } else {
            devLogLine("自身光标", "读屏焦点第" + index + "行，程序中 cur 本来就在这行，未改动");
        }
        // 分类列表滑到底就续下一页，不用再点（Legado 也是滚到底自动加载）
        if (index == sc.items.size() - 1 && sc.items.get(index) != null
                && "nb:catmore".equals(sc.items.get(index).key)) {
            nb.nbCatMore();
        }
    }

    // ---------------------------------------------------------------- 拖动条
    private static final int SLIDER_MIN = 0;
    private static final int SLIDER_MAX = 100;

    /** 0–100 的百分比 → TTS 倍率：0→0.3，50→1.0，100→2.5 */
    /** 界面语音真正使用的倍率：开启了「语音加倍」就在当前语速上再翻一倍 */
    private float effRate() {
        return com.mangjie.reader.speech.VoiceMath.effective(menuVoice.rateP, menuVoice.speedMul);
    }

    private float mapFactor(int p) {
        return com.mangjie.reader.speech.VoiceMath.factor(p);
    }

    private class SliderRow extends TextView {
        String base;
        int pct;
        boolean selected;
        SliderRow(Context c) { super(c); }
        void setValues(String base, int pct) { this.base = base; this.pct = pct; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            // 底色由背景色负责（与普通行同一处理），这里只画文字和轨道
            // 文字
            p.setColor(palFg());
            p.setTextSize(getTextSize());
            float baseY = (getHeight() - (p.descent() + p.ascent())) / 2f;
            cv.drawText(base + "：" + pct + "%", dp(8), baseY, p);

            // 轨道：右侧一段
            float left = getWidth() * 0.52f;
            float right = getWidth() - dp(10);
            float cy = getHeight() / 2f;
            p.setColor(0xFF606060);
            p.setStrokeWidth(dp(4));
            cv.drawLine(left, cy, right, cy, p);

            float ratio = (float) (pct - SLIDER_MIN) / (SLIDER_MAX - SLIDER_MIN);
            float x = left + (right - left) * Math.max(0f, Math.min(1f, ratio));
            p.setColor(palFg() == 0xFFFFFFFF ? 0xFFFFFFFF : palFg());
            cv.drawLine(left, cy, x, cy, p);
            p.setColor(palFg());
            cv.drawCircle(x, cy, dp(6), p);
        }
    }

    private SliderRow makeSlider(String base, int pct, int index, String key) {
        SliderRow row = new SliderRow(this);
        row.base = base;
        row.pct = pct;
        row.selected = index == top().cur;
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp());
        row.setTextColor(palFg());
        if (index == top().cur) paintSelected(row); else row.setBackgroundColor(palBg());
        row.setIncludeFontPadding(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 0);
        row.setFocusable(true);
        row.setContentDescription(base + "，" + pct + "%，可左右拖动，也可按中键输入百分比");
        return row;
    }

    /** 手指在拖动条上的 x 位置 → 百分比 */
    private void sliderFromX(SliderRow row, float x) {
        float left = row.getWidth() * 0.52f;
        float right = row.getWidth() - dp(10);
        if (right <= left) return;
        float ratio = (x - left) / (right - left);
        ratio = Math.max(0f, Math.min(1f, ratio));
        int pct = Math.round(SLIDER_MIN + ratio * (SLIDER_MAX - SLIDER_MIN));
        pct = Math.max(SLIDER_MIN, Math.min(SLIDER_MAX, pct));
        row.pct = pct;
        row.invalidate();
    }

    private void syncSliderSelection() {
        Screen sc = top();
        for (int i = 0; i < rowsBox.getChildCount() && i < sc.items.size(); i++) {
            View v = rowsBox.getChildAt(i);
            if (v instanceof SliderRow) {
                ((SliderRow) v).selected = (i == sc.cur);
                v.invalidate();
            }
        }
    }

    private void commitSlider(SliderRow row, String key) {
        if ("sp:rate".equals(key)) {
            menuVoice.rateP = row.pct;
            applySpeechParams();
            savePrefs();
            speak("语速 " + menuVoice.rateP + "%");
            if (row.pct == 50) speak("语速 正常");
        } else {
            menuVoice.pitchP = row.pct;
            applySpeechParams();
            savePrefs();
            speak("语调 " + menuVoice.pitchP + "%");
        }
    }

    private TextView makeRow(String text, boolean selected, String desc) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextColor(selected ? 0xFFFFFFFF : palFg());
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp());
        row.setTypeface(Typeface.DEFAULT);
        row.setIncludeFontPadding(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), 0, dp(6), 0);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        if (selected) paintSelected(row); else row.setBackgroundColor(palBg());
        row.setContentDescription(desc);
        // 触摸探索（读屏接管手指）时 App 收不到一个 touch 事件，整串被 dispatchTouchEvent 交给系统，
        // 自己那套跟手逻辑一行都跑不到，于是手指在列表上摸，红条钉在原地不动。
        // 这种时候只剩 hover 事件能拿到：摸到哪一行，就把自身光标挪到哪一行。
        row.setOnHoverListener(new View.OnHoverListener() {
            @Override public boolean onHover(View v, MotionEvent e) {
                int act = e.getActionMasked();
                if (act != MotionEvent.ACTION_HOVER_ENTER && act != MotionEvent.ACTION_HOVER_MOVE) return false;
                if (!screenReaderOn || inputOn || menuOn || dialogOn || isReadingBook()) return false;
                if (v.getParent() != rowsBox) return false;          // 菜单浮层里的行不跟（那边有 menuSel）
                int idx = rowsBox.indexOfChild(v);
                Screen sc = top();
                if (idx < 0 || sc == null || idx >= sc.items.size()) return false;
                int before = sc.cur;
                focusToRow(idx);
                if (sc.cur != before) devLogLine("探索", "摸到第" + idx + "行，光标跟随");
                return false;                                        // 事件还给读屏，不抢它的探索
            }
        });
        return row;
    }

    private void focusRow(TextView v) {
        if (v == null || screenReaderOn) return;
        final TextView fv = v;
        fv.post(new Runnable() { @Override public void run() { fv.requestFocus(); } });
    }

    private void scrollToSelection() {
        if (scroll == null) return;
        if (top().reader) {
            final int child = top().cur - rowOffset;
            scroll.post(new Runnable() { @Override public void run() {
                View v = rowsBox.getChildAt(child);
                if (v != null) scroll.scrollTo(0, Math.max(0, v.getTop() - dp(6)));
            } });
            return;
        }
        final int target = top().cur * dp(ROW_DP);
        scroll.post(new Runnable() { @Override public void run() { scroll.smoothScrollTo(0, target); } });
    }

    // ---------------------------------------------------------------- 功能菜单

    private String selectedPath() {
        Screen sc = top();
        if (sc.cur < 0 || sc.cur >= sc.items.size()) return curDir;
        String k = sc.items.get(sc.cur).key;
        if (k == null || k.isEmpty()) return curDir;
        if (k.startsWith("dir:") || k.startsWith("file:")) return k.substring(4);
        return curDir;
    }

    private String menuKind = "file";

    private void openFileMenu() {
        menuKind = "file";
        openFloatMenu("文件菜单", fileMenuNames(), fileMenuKeys());
    }

    /** 文件菜单动作键，与 fileMenuNames() 一一对应 */
    private String[] fileMenuKeys() {
        return new String[]{"fmenu:属性", "fmenu:复制", "fmenu:剪切", "fmenu:粘贴",
                "fmenu:删除", "fmenu:清空", "fmenu:新建文件夹", "fmenu:关闭菜单"};
    }

    private String[] fileMenuNames() {
        return new String[]{"属性", "复制", "剪切", "粘贴", "删除", "清空", "新建文件夹", "关闭菜单"};
    }

    private void openReaderMenu() {
        menuKind = "reader";
        String[] keys = readerMenuKeys();
        String[] withPref = new String[keys.length];
        for (int i = 0; i < keys.length; i++) withPref[i] = "rmenu:" + keys[i];
        openFloatMenu("阅读菜单", readerMenuNames(), withPref);
    }

    private String[] readerMenuNames() {
        List<String> out = new ArrayList<String>();
        out.add(autoPlay ? "暂停朗读" : "继续朗读");
        // 翻章是朗读时最频繁的动作，且没有直达键，放一级。项数固定，避免播放中翻章时焦点位置漂移
        if (localMode && chapCount() > 1) {
            out.add(chapterNo > 0 ? "上一章　" + chapTitle(chapterNo - 1) : "上一章（已到第一章）");
            out.add(chapterNo + 1 < chapCount() ? "下一章　" + chapTitle(chapterNo + 1) : "下一章（已到最后一章）");
        }
        // 百分比并进「目录」这一项，二级不再单占一行
        out.add((netReading ? netChapterTag() : "目录" + currentChapterTag())
                + " · " + bookPercent(top().cur) + "%");
        // 定时停止是听书常用项，放一级；项名带剩余时间，没设定时就是「定时停止」
        out.add(sleepTimerLabel());
        out.add("添加书签");
        out.add("语音：语速 " + readerVoice.rateP + "%，语调 " + readerVoice.pitchP + "%（点开设置）");
        out.add("更多…");
        return out.toArray(new String[0]);
    }

    /** 定时停止这一项的文案：设了就把剩余分钟带上，读屏能直接听到还剩多久 */
    private String sleepTimerLabel() {
        long left = sleepUntil - System.currentTimeMillis();
        if (left <= 0) return "定时停止";
        int min = (int) Math.ceil(left / 60000.0);
        return "定时停止（还剩 " + min + " 分钟，点开可改或取消）";
    }

    /** 一级菜单的动作键，必须与 readerMenuNames() 一一对应（含条件项） */
    private String[] readerMenuKeys() {
        List<String> out = new ArrayList<String>();
        out.add("toggleRead");
        if (localMode && chapCount() > 1) {
            out.add("lcprev");
            out.add("lcnext");
        }
        out.add("toc");
        out.add("timer");
        out.add("addBm");
        out.add("voice");
        out.add("more");
        return out.toArray(new String[0]);
    }

    /** 打开二级「更多」也是浮层 */
    private void openReaderMoreMenu() {
        String[] keys = readerMoreKeys();
        String[] withPref = new String[keys.length];
        for (int i = 0; i < keys.length; i++) withPref[i] = "rmenu:" + keys[i];
        openFloatMenu("更多功能", readerMoreNames(), withPref);
    }

    /** 二级「更多」菜单：基础项 → 按架/按模式追加项 → 最后一项固定「返回阅读菜单」 */
    private String[] readerMoreNames() {
        List<String> out = new ArrayList<String>();
        out.add("查找");
        out.add("摘抄本句");
        out.add("复制本句");
        out.add("书签列表");
        out.add("词典查词");
        out.add(netReading ? "收藏这本书" : (isFav() ? "取消收藏" : "收藏本书"));
        out.add("跳到指定位置");
        out.add("书籍信息");
        out.add("分享本句");
        if (netReading) {
            out.add("整章连播：" + (prefs.getBoolean("nbChain", true) ? "开" : "关") + "，点一下切换");
            out.add("从头朗读这一章");
            out.add("搜全书（搜已缓存的 " + com.mangjie.reader.net.ChapterCache.chapterCount(DATA_DIR,
                    nb == null ? "" : nb.nbBookUrl) + " 章）");
            out.add("换源（用别的书源找这本书）");
            out.add("清除本书缓存（" + com.mangjie.reader.net.ChapterCache.chapterCount(DATA_DIR,
                    nb == null ? "" : nb.nbBookUrl) + " 章）");
        }
        if (localMode && chapCount() > 1) {
            out.add("跳到某章（共 " + chapCount() + " 章）");
        }
        out.add("返回阅读菜单");
        return out.toArray(new String[0]);
    }

    /** 二级动作键，必须与 readerMoreNames() 一一对应（含条件项） */
    private String[] readerMoreKeys() {
        List<String> out = new ArrayList<String>();
        out.add("find");
        out.add("excerpt");
        out.add("copy");
        out.add("bmList");
        out.add("dict");
        out.add("fav");
        out.add("pos");
        out.add("info");
        out.add("share");
        if (netReading) {
            out.add("chain");
            out.add("restart");
            out.add("bookfind");
            out.add("switchSrc");
            out.add("clearCache");
        }
        if (localMode && chapCount() > 1) {
            out.add("lcjump");
        }
        out.add("back");
        return out.toArray(new String[0]);
    }

    /** 网上阅读时「目录」后面挂当前第几章 */
    private String netChapterTag() {
        if (nb == null || nb.nbChapters.isEmpty()) return "目录";
        int i = nbChapterIndex();
        if (i < 0) return "目录（共" + nb.nbChapters.size() + "章）";
        return "目录（第" + (i + 1) + "/" + nb.nbChapters.size() + "章）";
    }

    /** 当前章在 nbChapters 里的序号，从 bookPath（net://<url>#<idx>）解出来 */
    private int nbChapterIndex() {
        if (!netReading || bookPath == null) return -1;
        int p = bookPath.lastIndexOf('#');
        if (p < 0) return -1;
        try { return Integer.parseInt(bookPath.substring(p + 1)); } catch (Exception e) { return -1; }
    }

    private String[] menuNames() {
        if ("readerMore".equals(menuKind)) return readerMoreNames();
        return "reader".equals(menuKind) ? readerMenuNames() : fileMenuNames();
    }

    private void commitReaderMenu() {
        String[] keys = "readerMore".equals(menuKind) ? readerMoreKeys() : readerMenuKeys();
        // 一级/二级项数随模式变（本地有章时多两项），旧位置可能越界；夹到合法位置，别落成「back」再弹同一条菜单
        if (menuSel < 0) menuSel = 0;
        if (menuSel >= keys.length) menuSel = keys.length - 1;
        doReaderMenuAction(keys[menuSel]);
    }

    private void doReaderMenuAction(String key) {
        Screen sc = top();
        if ("more".equals(key)) { openReaderMoreMenu(); return; }
        if ("back".equals(key)) { openReaderMenu(); return; }
        hideMenuOverlay();
        menuSel = 0;
        menuKind = "reader";
        switch (key) {
            case "toggleRead":
                if (autoPlay) { pauseReading(); render(false); speak("已暂停朗读"); }
                else { autoPlay = true; render(false); speakSentence(sc.cur); }
                return;
            case "toc":
                if (netReading && nb != null && !nb.nbChapters.isEmpty()) {
                    pushScreen(nb.nbChapterListScreen());
                    render(true);
                    return;
                }
                stack.add(chapterScreen());
                render(true);
                return;
            case "addBm":
                addBookmark(sc.cur);
                render(false);
                return;
            case "find":
                inputAction = "find";
                inputTitle = "查找";
                openInput("请输入要查找的文字");
                return;
            case "bookfind":
                inputAction = "bookfind";
                inputTitle = "搜全书";
                openInput("输入关键词，搜这本书已缓存的章节");
                return;
            case "excerpt":
                excerptSentence();
                return;
            case "copy":
                copySentence();
                return;
            case "voice":
                stack.add(readerVoicePanelScreen());
                render(true);
                return;
            case "timer":
                inputAction = "timer";
                inputTitle = "定时停止";
                if (sleepUntil > System.currentTimeMillis()) {
                    int left = (int) Math.ceil((sleepUntil - System.currentTimeMillis()) / 60000.0);
                    openInput("已设 " + left + " 分钟，输新的分钟数覆盖，输 0 取消");
                } else {
                    openInput("还要过几分钟停止朗读？1 到 600，输 0 取消");
                }
                return;
            case "bmList":
                stack.add(bookmarkScreen());
                render(true);
                return;
            case "dict":
                inputAction = "mydictlook";
                inputTitle = "本地词库查词";
                openInput("输入要查的词");
                return;
            case "fav":
                if (netReading) {
                    String one = nb.nbBookName + "\u0001" + nb.nbBookUrl + "\u0001" + nb.nbSourceIdx;
                    String store = prefs.getString("nbFav", "");
                    boolean had = store.contains(nb.nbBookUrl);
                    if (had) {
                        StringBuilder sb = new StringBuilder();
                        for (String line : store.split("\n")) {
                            if (line.isEmpty()) continue;
                            if (line.contains(nb.nbBookUrl)) continue;
                            sb.append(line).append("\n");
                        }
                        prefs.edit().putString("nbFav", sb.toString()).apply();
                        speak("已取消收藏");
                    } else {
                        prefs.edit().putString("nbFav", one + "\n" + store).apply();
                        speak("已收藏 " + nb.nbBookName);
                    }
                    render(false);
                    return;
                }
                if (isFav()) {
                    saveBool("fav:" + bookPath, false);
                    removeFromList("favList", bookPath);
                    render(false);
                    speak("已取消收藏");
                    toast("已从我的收藏移除");
                } else {
                    saveBool("fav:" + bookPath, true);
                    addToList("favList", bookPath);
                    render(false);
                    speak("已收藏这本书");
                    toast("已加入我的收藏");
                }
                return;
            case "pos":
                inputAction = "pos";
                inputTitle = "跳到位置";
                openInput("请输入百分比，0 到 100");
                return;
            case "info":
                if (netReading) { openNetDetail(); return; }
                stack.add(bookInfoScreen());
                render(true);
                return;
            case "share":
                shareSentence();
                return;
            case "lcprev":
                if (localMode) {
                    if (chapterNo > 0) openLocalChapter(chapterNo - 1);
                    else speak("已经是第一章了");
                }
                return;
            case "lcnext":
                if (localMode) {
                    if (chapterNo + 1 < chapCount()) openLocalChapter(chapterNo + 1);
                    else speak("已经是最后一章了");
                }
                return;
            case "lcjump":
                if (localMode && chapCount() > 0) {
                    List<Item> ci = new ArrayList<Item>();
                    for (int i = 0; i < chapCount(); i++) {
                        ci.add(new Item((i == chapterNo ? "★ " : "") + (i + 1) + ". "
                                + chapTitle(i), "lc:go:" + i));
                    }
                    pushScreen(new Screen("跳到某章　共 " + chapCount() + " 章", ci));
                    speak("共 " + chapCount() + " 章");
                }
                return;
            case "chain":
                if (netReading) {
                    boolean on = !prefs.getBoolean("nbChain", true);
                    prefs.edit().putBoolean("nbChain", on).apply();
                    devLogLine("网上阅读", "整章连播" + (on ? "开" : "关"));
                    speak("整章连播已" + (on ? "开" : "关"));
                    return;
                }
                break;
            case "restart":
                if (netReading && nb != null) nb.nbOpenChapter(Math.max(nbChapterIndex(), 0), true);
                return;
            case "switchSrc":
                if (netReading && nb != null && !nb.nbChapters.isEmpty()) nb.nbStartSwitch();
                return;
            case "clearCache":
                if (netReading) {
                    if (nb == null || nb.nbBookUrl == null) { speak("现在没有打开网上书"); return; }
                    int cnt = com.mangjie.reader.net.ChapterCache.chapterCount(DATA_DIR, nb.nbBookUrl);
                    com.mangjie.reader.net.ChapterCache.clearBook(DATA_DIR, nb.nbBookUrl);
                    nb.nbClearTextCache();
                    devLogLine("缓存", "清除本书缓存 " + nb.nbBookName + "\u3000" + cnt + " 章");
                    speak("已清除本书缓存 " + cnt + " 章");
                    return;
                }
                break;
            default:
                render(false);
                speak("第" + (sc.cur + 1) + "句，共" + bookSent.size() + "句");
                return;
        }
    }

    /** 二级菜单返回一级，焦点落在「更多…」（一级项数随模式变，必须按键查位置，不能写死下标） */
    private void backToReaderMenu() {
        menuKind = "reader";
        menuOn = true;
        String[] mk = readerMenuKeys();
        int sel = 0;
        for (int i = 0; i < mk.length; i++) if ("more".equals(mk[i])) { sel = i; break; }
        menuSel = sel;
        render(false);
        if (!screenReaderOn) speak("已返回阅读菜单");
    }

    // ------------------------------------------------ 目录 / 查找 / 摘抄 / 定时

    private java.util.regex.Pattern chapterPattern() {
        return com.mangjie.reader.reader.ChapterIndex.PATTERN;
    }

    /** 某句所在章的位置：第N章 / 共M章 */
    private String chapterPosTag(int sentIdx) {
        return com.mangjie.reader.reader.ChapterIndex.posTag(bookSent, sentIdx);
    }

    /** 阅读菜单里给“目录”后面挂上当前章，方便定位 */
    private String currentSentenceText() {
        Screen sc = top();
        if (!sc.reader || bookSent.isEmpty()) return "";
        int i = sc.cur;
        if (i < 0 || i >= bookSent.size()) return "";
        return bookSent.get(i) == null ? "" : bookSent.get(i).trim();
    }

    private void copySentence() {
        String t = currentSentenceText();
        if (t.isEmpty()) { speak("现在没有可复制的句子"); return; }
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText(bookName, t));
            speak("已复制本句");
            toast("已复制本句");
            devLogLine("摘抄", "复制　" + shortText(t));
        } catch (Throwable e) {
            speak("复制失败");
            toast("复制失败");
        }
    }

    private void shareSentence() {
        String t = currentSentenceText();
        if (t.isEmpty()) { speak("现在没有可分享的句子"); return; }
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, bookName);
            send.putExtra(Intent.EXTRA_TEXT, t + "\n——《" + bookName + "》");
            startActivity(Intent.createChooser(send, "分享本句"));
            devLogLine("摘抄", "分享　" + shortText(t));
        } catch (Throwable e) {
            speak("没有可用的分享应用");
            toast("没有可用的分享应用");
        }
    }

    private Screen bookInfoScreen() {
        List<Item> items = new ArrayList<>();
        Screen rd = null;
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).reader) { rd = stack.get(i); break; }
        }
        int cur = rd == null ? 0 : rd.cur;
        int chars = com.mangjie.reader.reader.ChapterIndex.totalChars(bookSent);
        int chNum = com.mangjie.reader.reader.ChapterIndex.indices(bookSent).size();
        int chAt = com.mangjie.reader.reader.ChapterIndex.chapterAt(bookSent, cur);
        items.add(new Item("书名  " + bookName));
        items.add(new Item("字数  " + chars + " 字（" + bookSent.size() + " 句）"));
        if (netReading && nb != null) {
            int ci = nbChapterIndex();
            items.add(new Item("章节  共 " + nb.nbChapters.size() + " 章"
                    + (ci >= 0 ? "（现在第 " + (ci + 1) + " 章）" : "")));
            items.add(new Item("进度  本章 " + com.mangjie.reader.reader.BookProgress.percent(bookSent.size(), cur)
                    + "%（第 " + (cur + 1) + " 句）"));
            items.add(new Item("来源  网上阅读　" + nb.nbBookName));
            int cc = com.mangjie.reader.net.ChapterCache.chapterCount(DATA_DIR, nb.nbBookUrl);
            long cs = com.mangjie.reader.net.ChapterCache.sizeOf(DATA_DIR, nb.nbBookUrl);
            items.add(new Item("缓存  " + cc + " 章　" + fmtSize(cs)));
            items.add(new Item("清除本书缓存", "nb:cacheclear"));
            items.add(new Item("从最近读过的书里移除本书", "nb:recdel"));
        } else {
            items.add(new Item("章节  " + chNum + " 章"
                    + (chAt >= 0 ? "（现在第 " + (chAt + 1) + " 章）" : "")));
            items.add(new Item("进度  " + bookPercent(cur)
                    + "%（第 " + (cur + 1) + " 句）"));
            items.add(new Item("目录规则  " + com.mangjie.reader.reader.TocRules.lastRuleName()));
            File f = new File(bookPath);
            items.add(new Item("文件  " + fmtSize(f.length()) + "　" + bookPath));
        }
        Screen sc = new Screen("书籍信息", items);
        sc.cur = 0;
        return sc;
    }

    private String currentChapterTag() {
        if (!top().reader) return "";
        return com.mangjie.reader.reader.ChapterIndex.currentTag(bookSent, top().cur);
    }

    private Screen chapterScreen() {
        // 按章模式：目录直接读章表，和「跳到某章」用同一份数据，不再走句子级另算一套。
        // 认不出章节、按 1MB 分段的 txt，这里把「章」改成「段」，别让人误以为是章节
        int total = chapCount();
        if (localMode && total > 1) {
            boolean seg = localIdx != null && localIdx.chunked;
            String unit = seg ? "段" : "章";
            List<Item> items = new ArrayList<>();
            int curCh = Math.max(0, Math.min(chapterNo, total - 1));
            for (int i = 0; i < total; i++) {
                items.add(new Item((i == curCh ? "★ " : "") + "第" + (i + 1) + unit + " " + chapTitle(i), "lc:go:" + i));
            }
            Screen sc = new Screen("目录　共 " + items.size() + " " + unit, items);
            sc.cur = curCh;
            devLogLine("阅读", "目录（章表）　共" + items.size() + unit + "　当前第" + (curCh + 1) + unit
                    + "　规则=" + (localIdx != null ? localIdx.rule : "epub 自带章节"));
            return sc;
        }
        Screen sc = com.mangjie.reader.reader.ChapterIndex.screen(bookSent, top().reader ? top().cur : 0);
        devLogLine("阅读", sc.title + "　共" + sc.items.size() + "章　当前第" + (sc.cur + 1) + "章");
        return sc;
    }

    private List<Integer> findHits = new ArrayList<Integer>();
    private int findPos = 0;

    private void performFind(String kw) {
        findHits.clear();
        findPos = 0;
        if (kw == null || kw.isEmpty()) { speak("没有输入查找内容"); return; }
        if (localMode && chapCount() > 1) {
            performFindChapters(kw);
            return;
        }
        String low = kw.toLowerCase(Locale.ROOT);
        for (int i = 0; i < bookSent.size(); i++) {
            if (bookSent.get(i).toLowerCase(Locale.ROOT).contains(low)) findHits.add(i);
        }
        if (findHits.isEmpty()) { speak("没有找到，共 0 处"); toast("没有找到"); return; }
        stack.add(findScreen());
        render(true);
        speak("共找到 " + findHits.size() + " 处");
    }

    /** 网络书搜全书：缓存章节交给 BookFinder 算，这里只管取快照、上屏、校验没换书 */
    private void searchWholeBook(final String kw) {
        if (!netReading || nb == null) { performFind(kw); return; }     // 本地书「查找」本来就是全书
        if (nb.nbChapters == null || nb.nbChapters.isEmpty()) { speak("目录还没取到，先加载目录"); return; }
        final String bookUrl = nb.nbBookUrl == null ? "" : nb.nbBookUrl;
        final List<String[]> snapshot = new ArrayList<String[]>(nb.nbChapters);   // 快照，换源不影响这一批
        speak("正在搜已缓存的章节，请稍等");
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                final com.mangjie.reader.reader.BookFinder.Result r =
                        com.mangjie.reader.reader.BookFinder.search(DATA_DIR, bookUrl, snapshot, kw);
                ui.post(new Runnable() { @Override public void run() {
                    if (nb == null || !bookUrl.equals(nb.nbBookUrl == null ? "" : nb.nbBookUrl)) {
                        speak("书已经换了，这次搜索不算");                       // 搜到一半换源换书，直接丢
                        return;
                    }
                    if (stack.isEmpty() || !top().reader) {                      // 已经退出阅读页，别往书架上盖屏
                        speak("已经不在阅读页了，这次搜索不算");
                        return;
                    }
                    List<Item> items = new ArrayList<Item>();
                    items.add(new Item(com.mangjie.reader.reader.BookFinder.summary(r)));
                    for (String[] h : r.hits) {
                        int idx = Integer.parseInt(h[0]);
                        items.add(new Item("第" + (idx + 1) + "章 " + h[1] + "：" + h[2], "wfch:" + idx));
                    }
                    if (r.hits.isEmpty()) items.add(new Item(com.mangjie.reader.reader.BookFinder.emptyHint(r)));
                    items.add(new Item("返回阅读", "nav:back"));
                    stack.add(new Screen("搜全书　" + kw, items).k("wfch"));
                    render(true);
                    speak(r.hits.isEmpty() ? "没搜到" : ("共 " + r.hits.size() + " 章里有"));
                } });
            }
        });
    }

    /**
     * 按章模式下的查找：逐章读正文、同一套净化后再找，命中记「章号 + 章内字符偏移」。
     * 不用切句，所以搜全书也快；跳转时再按偏移定位到句。
     */
    private void performFindChapters(final String kw) {
        final File f = localFile;
        final int total = chapCount();
        final boolean seg = localIdx != null && localIdx.chunked;
        final String unit = seg ? "段" : "章";
        if (f == null || total <= 0) return;
        final String low = kw.toLowerCase(Locale.ROOT);
        speak("正在搜全书，共 " + total + " " + unit);
        com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
            final List<String[]> hits = new ArrayList<String[]>();
            for (int c = 0; c < total; c++) {
                String raw;
                try { raw = readChap(f, c); } catch (Throwable t) { raw = null; }
                if (raw == null || raw.isEmpty()) continue;
                String body;
                try { body = com.mangjie.reader.reader.Purify.clean(raw).text; }
                catch (Throwable t) { body = raw; }
                String lb = body == null ? "" : body.toLowerCase(Locale.ROOT);
                int at = lb.indexOf(low);
                int guard = 0;
                while (at >= 0 && guard++ < 60) {
                    int from = Math.max(0, at - 8);
                    int to = Math.min(body.length(), at + low.length() + 14);
                    hits.add(new String[]{String.valueOf(c), String.valueOf(at),
                            body.substring(from, to).replace("\n", " ")});
                    at = lb.indexOf(low, at + 1);
                }
            }
            ui.post(new Runnable() { @Override public void run() {
                List<Item> items = new ArrayList<Item>();
                items.add(new Item("共 " + hits.size() + " 处，全书 " + total + " " + unit));
                for (String[] h : hits) {
                    int c = 0;
                    try { c = Integer.parseInt(h[0]); } catch (Exception ignored) {}
                    items.add(new Item("第" + (c + 1) + unit + " " + chapTitle(c) + "：" + h[2],
                            "lc:find:" + h[0] + ":" + h[1]));
                }
                if (hits.isEmpty()) items.add(new Item("全书没有找到「" + kw + "」"));
                items.add(new Item("返回阅读", "nav:back"));
                stack.add(new Screen("查找　" + kw, items));
                render(true);
                speak(hits.isEmpty() ? "全书没有找到" : ("共找到 " + hits.size() + " 处"));
            } });
        } });
    }

    private Screen findScreen() {
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("上一处", "fnp:-1"));
        items.add(new Item("下一处", "fnp:1"));
        for (Integer idx : findHits) {
            String body = bookSent.get(idx);
            if (body.length() > 20) body = body.substring(0, 20) + "…";
            items.add(new Item("第" + (idx + 1) + "句：" + body, "fnd:" + idx));
        }
        return new Screen("查找结果", items);
    }

    private void jumpToHit(int delta) {
        if (findHits.isEmpty()) return;
        findPos = (findPos + delta + findHits.size()) % findHits.size();
        int idx = findHits.get(findPos);
        jumpInReader(idx);
    }

    private void excerptSentence() {
        String line = "《" + new File(bookPath).getName() + "》第" + (top().cur + 1) + "句："
                + bookSent.get(top().cur);
        try {
            File dir = new File(DATA_DIR);
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "摘抄.txt");
            java.io.FileWriter fw = new java.io.FileWriter(out, true);
            fw.write(line + "\n");
            fw.close();
            speak("已摘抄");
            toast("已添加到 摘抄.txt");
        } catch (Exception ex) {
            speak("摘抄失败");
            toast("摘抄失败");
        }
        render(false);
    }

    private void startSleepTimer(int minutes) {
        ui.removeCallbacks(sleepRunnable);
        sleepUntil = System.currentTimeMillis() + minutes * 60L * 1000L;
        ui.postDelayed(sleepRunnable, minutes * 60L * 1000L);
        speak("将在 " + minutes + " 分钟后停止朗读");
        toast("已设定 " + minutes + " 分钟");
        render(false);
    }

    private void cancelSleepTimer() {
        ui.removeCallbacks(sleepRunnable);
        sleepUntil = 0;
        speak("已取消定时停止");
        toast("定时停止已取消");
        render(false);
    }

    private final Runnable sleepRunnable = new Runnable() {
        @Override public void run() {
            sleepUntil = 0;
            autoPlay = false;
            if (tts != null) tts.stop();
            speak("定时时间到，停止朗读");
            render(false);
        }
    };

    private void closeFileMenu() {
        closeFloatMenu();
    }

    private void commitFileMenu() {
        doFileMenuAction(fileMenuNames()[menuSel]);
    }

    private void doFileMenuAction(String sel) {
        String path = selectedPath();
        hideMenuOverlay();
        File f = path == null ? null : new File(path);

        if ("关闭菜单".equals(sel)) { render(false); if (!screenReaderOn) speak("已关闭菜单"); return; }
        if ("属性".equals(sel)) { showAttr(f); return; }
        if ("复制".equals(sel)) {
            if (f == null || !f.exists()) { speak("没有选中文件"); return; }
            clipPath = f.getAbsolutePath(); clipCut = false;
            speak("已复制 " + f.getName()); toast("已复制 " + f.getName());
            render(false);
            return;
        }
        if ("剪切".equals(sel)) {
            if (f == null || !f.exists()) { speak("没有选中文件"); return; }
            clipPath = f.getAbsolutePath(); clipCut = true;
            speak("已剪切 " + f.getName()); toast("已剪切 " + f.getName());
            render(false);
            return;
        }
        if ("粘贴".equals(sel)) { doPaste(); return; }
        if ("删除".equals(sel)) {
            if (f == null || !f.exists()) { speak("没有选中文件"); return; }
            pendingDelete = f;
            showDialog("删除 " + f.getName() + "？", new String[]{"取消", "删除"});
            return;
        }
        if ("清空".equals(sel)) {
            pendingClear = new File(curDir);
            showDialog("清空当前文件夹？", new String[]{"取消", "清空"});
            return;
        }
        if ("新建文件夹".equals(sel)) { askNewFolder(); return; }
        render(false);
    }

    private void showAttr(File f) {
        List<Item> items = new ArrayList<>();
        if (f == null || !f.exists()) {
            items.add(new Item("没有选中文件"));
        } else {
            items.add(new Item("名称  " + f.getName()));
            items.add(new Item("类型  " + (f.isDirectory() ? "文件夹" : fileType(f.getName()))));
            items.add(new Item("位置  " + f.getParent()));
            items.add(new Item("大小  " + (f.isDirectory() ? "—" : sizeText(f.length()))));
            items.add(new Item("修改  " + new SimpleDateFormat("yyyy年M月d日 H:mm", Locale.CHINA)
                    .format(new Date(f.lastModified()))));
        }
        stack.add(new Screen("属性", items));
        render(true);
    }

    private String fileType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".txt")) return "文本文件";
        if (n.endsWith(".mp3") || n.endsWith(".wav")) return "音频文件";
        if (n.endsWith(".epub")) return "电子书";
        if (n.endsWith(".apk")) return "安装包";
        return "文件";
    }


    private int pendingBmIdx = -1;
    private File pendingDelete = null;
    private File pendingClear = null;

    private void doPaste() {
        if (clipPath == null) { speak("剪贴板为空"); toast("剪贴板为空"); return; }
        File src = new File(clipPath);
        File dir = new File(curDir == null ? internalRoot() : curDir);
        if (!src.exists()) { speak("原文件已不存在"); return; }
        File dst = new File(dir, src.getName());
        try {
            if (dst.exists()) { speak("同名文件已存在"); toast("同名文件已存在"); return; }
            if (src.isDirectory()) { copyDir(src, dst); }
            else { copyFile(src, dst); }
            if (clipCut) deleteRec(src);
            String say = (clipCut ? "已移动到 " : "已粘贴到 ") + dir.getName();
            speak(say); toast(say);
            clipPath = null; clipCut = false;
            refreshDir();
        } catch (Exception e) {
            speak("粘贴失败"); toast("粘贴失败：" + e.getMessage());
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(src);
        java.io.OutputStream out = new java.io.FileOutputStream(dst);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally { in.close(); out.close(); }
    }

    private void copyDir(File src, File dst) throws Exception {
        if (!dst.mkdirs()) throw new Exception("无法创建目录");
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) copyDir(k, new File(dst, k.getName()));
            else copyFile(k, new File(dst, k.getName()));
        }
    }

    private void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }

    private void refreshDir() {
        if (curDir == null) { render(false); return; }
        Screen fresh = listDir(curDir);
        stack.set(stack.size() - 1, fresh);
        render(false);
    }

    // ---------------- 屏内输入（新建文件夹 / 重命名用） ----------------
    private FrameLayout scrollWrap;
    private ImageView coverMini;
    private boolean inputOn = false;
    /** 搜索输入页里的「精准搜索」开关那一行 */
    private TextView precisionRowView = null;
    /** 输入页的行（提示 / 输入框 / 精准搜索 / 确定 / 取消），供上下滑动换行用 */
    private final java.util.List<View> inputRows = new ArrayList<View>();
    private int inputCur = 1;
    private int inputStartCur = 1;
    private float inputDownY = 0f;
    private boolean inputSwiping = false;
    private EditText inputBox;
    private String inputAction = "mkdir";
    private String pendingPctKey = null;
    private String inputTitle = "新建文件夹";

    private void askNewFolder() {
        inputAction = "mkdir";
        inputTitle = "新建文件夹";
        openInput("请输入文件夹名称");
    }

    private void openInput(String hint) {
        inputOn = true;
        menuOn = false;
        dialogOn = false;
        titleView.setText(inputTitle);
        clockView.setText(hhmm.format(new Date()));

        rowsBox.removeAllViews();
        inputRows.clear();

        TextView prompt = new TextView(this);
        prompt.setText(hint);
        prompt.setTextColor(palFg());
        prompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp());
        prompt.setIncludeFontPadding(false);
        prompt.setGravity(Gravity.CENTER);
        rowsBox.addView(prompt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
        inputRows.add(prompt);

        inputBox = new EditText(this);
        inputBox.setSingleLine(true);
        inputBox.setBackgroundColor(palBg());
        inputBox.setTextColor(palFg());
        inputBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp());
        inputBox.setPadding(dp(6), 0, dp(6), 0);
        inputBox.setHint("在这里输入");
        inputBox.setHintTextColor(0xFF808080);
        inputBox.setFocusable(true);
        inputBox.setFocusableInTouchMode(true);
        inputBox.setContentDescription("输入框，正在输入" + (inputTitle == null ? "" : "：" + inputTitle));
        rowsBox.addView(inputBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
        inputRows.add(inputBox);

        // 搜索输入页：输入框下面摆「精准搜索」开关，最近搜过放到确定/取消之下
        if ("nbsearch".equals(inputAction)) {
            precisionRowView = makeRow(precisionLabel(), false, precisionLabel());
            precisionRowView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean on = !nb.precisionSearch();
                    nb.setPrecisionSearch(on);
                    if (precisionRowView != null) {
                        precisionRowView.setText(precisionLabel());
                        precisionRowView.setContentDescription(precisionLabel());
                    }
                    speak(on ? "精准搜索已开，只留书名或作者含关键字的" : "精准搜索已关，源返回的都留着");
                    devLogLine("搜索", "精准搜索切到 " + (on ? "开" : "关"));
                }
            });
            rowsBox.addView(precisionRowView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
            inputRows.add(precisionRowView);
        }

        addInputButton("确定");
        addInputButton("取消");

        // 最近搜过的词铺在「确定」「取消」下面（用户要求：放到确定的下边），不挡着输入与提交
        if ("nbsearch".equals(inputAction)) {
            List<String[]> hist = nb.recentSearches();
            int histShown = 0;
            for (int hi = 0; hi < hist.size() && histShown < 5; hi++) {
                final String w = hist.get(hi)[0];
                if (w == null || w.trim().isEmpty()) continue;
                final TextView histRow = makeRow("最近搜过：" + w, false, "最近搜过：" + w + "，点一下按这个词搜");
                histRow.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        devLogLine("搜索", "输入页点了最近搜过的词：" + w);
                        closeInput();
                        nb.nbSearch(w);
                    }
                });
                rowsBox.addView(histRow, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
                inputRows.add(histRow);
                histShown++;
            }
        }

        if (scroll != null) scroll.post(new Runnable() { @Override public void run() { scroll.scrollTo(0, 0); } });
        inputBox.post(new Runnable() {
            @Override public void run() {
                inputBox.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(inputBox, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        // 输入页也有自己的光标：默认停在输入框那一行，上下滑能换行
        inputStartCur = 1;
        inputCur = 1;
        setInputCur(1, false);

        speak(inputTitle + "，" + hint);
    }

    /** 输入页换行：画高亮、给这一行系统焦点、播报这一行 */
    private void setInputCur(int idx, boolean speakNow) {
        if (inputRows.isEmpty()) return;
        int t = Math.max(0, Math.min(inputRows.size() - 1, idx));
        boolean changed = t != inputCur;
        inputCur = t;
        for (int i = 0; i < inputRows.size(); i++) {
            View v = inputRows.get(i);
            if (v == null) continue;
            if (i == t) paintSelected(v); else v.setBackgroundColor(palBg());
        }
        final View fv = inputRows.get(t);
        if (fv != null) fv.post(new Runnable() { @Override public void run() { fv.requestFocus(); } });
        if (changed) devLogLine("自身光标", "输入页第" + t + "行");
        if (speakNow || changed) speak(inputRowLabel(fv));
    }

    private String inputRowLabel(View v) {
        if (v == null) return "";
        if (v instanceof EditText) return "输入框，在这里输入" + (inputTitle == null ? "" : "，标题：" + inputTitle);
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) return cd.toString();
        if (v instanceof TextView) return ((TextView) v).getText().toString();
        return "";
    }

    /** 输入页的上下滑：交给光标；单击仍然交给系统（点输入框能进输入，点确定能提交） */
    private boolean inputTouch(MotionEvent ev) {
        if (inputRows.isEmpty()) return super.dispatchTouchEvent(ev);
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            inputDownY = ev.getY();
            inputStartCur = inputCur;
            inputSwiping = false;
            return super.dispatchTouchEvent(ev);
        }
        if (a == MotionEvent.ACTION_MOVE) {
            float dy = inputDownY - ev.getY();          // 上滑为正
            int step = (int) (Math.abs(dy) / Math.max(1f, dp(ROW_DP) * 0.8f));
            if (step > 0) inputSwiping = true;
            if (inputSwiping) {
                // 跟列表页同一口径：手指往上走，光标就往上一行；手指往下走，光标就往下一行（跟手）。
                // 原来这里是反的：上滑一路往下走到最后一行，日志 a11y-20261121-225221 里
                // 22:51:46.6～22:51:47.3 从第 1 行一路滑到第 5 行，跟列表页「上滑＝上一项」正好相反。
                setInputCur(inputStartCur + (dy > 0 ? -step : step), true);
                return true;
            }
            return super.dispatchTouchEvent(ev);
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (inputSwiping) { inputSwiping = false; return true; }
            return super.dispatchTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 精准搜索开关文案（输入页与搜索菜单共用同一口径） */
    private String precisionLabel() {
        return "精准搜索：" + (nb != null && nb.precisionSearch() ? "开（只留书名或作者含关键字的）" : "关")
                + "，点一下切换";
    }

    private void addInputButton(String text) {
        final String t = text;
        TextView row = makeRow(text, false, text);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onInputButton(t); }
        });
        rowsBox.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
        inputRows.add(row);
    }

    private boolean validRegex(String p) {
        try { Pattern.compile(p); return true; } catch (Throwable e) { return false; }
    }

    /** 净化规则的输入流程（新增三步 / 改名 / 改匹配 / 改替换） */
    private void handlePurInput(String v) {
        if ("pur:new1".equals(inputAction)) {
            if (v.isEmpty()) { speak("名称不能为空"); toast("名称不能为空"); return; }
            ruleName = v; inputAction = "pur:new2";
            openInput("第二步，输入匹配正则");
            return;
        }
        if ("pur:new2".equals(inputAction)) {
            if (v.isEmpty() || !validRegex(v)) { speak("正则不合法，请重新输入"); toast("正则不合法"); return; }
            rulePattern = v; inputAction = "pur:new3";
            openInput("第三步，输入替换内容，留空表示整段删除");
            return;
        }
        if ("pur:new3".equals(inputAction)) {
            ruleRepl = v;
            List<com.mangjie.reader.reader.UserRules.Entry> list = purUserRules();
            list.add(new com.mangjie.reader.reader.UserRules.Entry(ruleName, rulePattern, ruleRepl, true));
            savePurRules(list);
            closeInput();
            if (!stack.isEmpty()) stack.set(stack.size() - 1, purifyScreen());
            render(true);
            speak("已新增净化规则 " + ruleName);
            devLogLine("净化", "新增规则 " + ruleName + "　" + rulePattern);
            return;
        }
        if (inputAction.startsWith("pur:name:")) {
            int i = clampInt(inputAction.substring(9), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> list = purUserRules();
            if (i < list.size() && !v.isEmpty()) { list.get(i).name = v; savePurRules(list); }
            closeInput();
            refreshTop(purRuleDetail(i));
            speak("名称已改");
            return;
        }
        if (inputAction.startsWith("pur:pat:")) {
            int i = clampInt(inputAction.substring(8), 0, 999);
            if (!validRegex(v)) { speak("正则不合法，请重新输入"); toast("正则不合法"); return; }
            List<com.mangjie.reader.reader.UserRules.Entry> list = purUserRules();
            if (i < list.size()) { list.get(i).pattern = v; savePurRules(list); }
            closeInput();
            refreshTop(purRuleDetail(i));
            speak("匹配已改");
            return;
        }
        if (inputAction.startsWith("pur:rep:")) {
            int i = clampInt(inputAction.substring(8), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> list = purUserRules();
            if (i < list.size()) { list.get(i).replacement = v; savePurRules(list); }
            closeInput();
            refreshTop(purRuleDetail(i));
            speak("替换已改");
            return;
        }
    }

    /** 目录规则的输入流程（新增两步 / 改名 / 改匹配） */
    private void handleTocInput(String v) {
        if ("toc:new1".equals(inputAction)) {
            if (v.isEmpty()) { speak("名称不能为空"); toast("名称不能为空"); return; }
            ruleName = v; inputAction = "toc:new2";
            openInput("第二步，输入匹配正则，会按行匹配");
            return;
        }
        if ("toc:new2".equals(inputAction)) {
            if (v.isEmpty() || !validRegex(v)) { speak("正则不合法，请重新输入"); toast("正则不合法"); return; }
            permissionTocRegexWarn(v);
            return;
        }
        if (inputAction.startsWith("toc:name:")) {
            int i = clampInt(inputAction.substring(9), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> list = tocUserRules();
            if (i < list.size() && !v.isEmpty()) { list.get(i).name = v; saveTocRules(list); }
            closeInput();
            refreshTop(tocRuleDetail(i));
            speak("名称已改");
            return;
        }
        if (inputAction.startsWith("toc:pat:")) {
            int i = clampInt(inputAction.substring(8), 0, 999);
            if (!validRegex(v)) { speak("正则不合法，请重新输入"); toast("正则不合法"); return; }
            List<com.mangjie.reader.reader.UserRules.Entry> list = tocUserRules();
            if (i < list.size()) { list.get(i).pattern = v; saveTocRules(list); }
            closeInput();
            refreshTop(tocRuleDetail(i));
            speak("匹配已改，重新打开书生效");
            return;
        }
    }

    /** 新增目录规则最后一步：存盘并重载 */
    private void permissionTocRegexWarn(String pattern) {
        rulePattern = pattern;
        List<com.mangjie.reader.reader.UserRules.Entry> list = tocUserRules();
        list.add(new com.mangjie.reader.reader.UserRules.Entry(ruleName, rulePattern, "", true));
        saveTocRules(list);
        closeInput();
        if (!stack.isEmpty()) stack.set(stack.size() - 1, tocRuleScreen());
        render(true);
        speak("已新增目录规则 " + ruleName + "，重新打开书生效");
        devLogLine("目录", "新增规则 " + ruleName + "　" + rulePattern);
    }

    private void onInputButton(String which) {
        if ("取消".equals(which)) { closeInput(); speak("已取消"); return; }
        String name = inputBox == null ? "" : inputBox.getText().toString().trim();
        if (inputAction != null && inputAction.startsWith("pur:")) { handlePurInput(name); return; }
        if (inputAction != null && inputAction.startsWith("toc:")) { handleTocInput(name); return; }
        if ("shelfurl".equals(inputAction)) { addBookByUrl(name); return; }
        if ("shelfsearch".equals(inputAction)) { doShelfSearch(name); return; }
        if ("note".equals(inputAction)) {
            if (name.isEmpty()) { speak("内容不能为空"); toast("内容不能为空"); return; }
            try {
                File dir = featDir("记事");
                File f = new File(dir, "记事-" + stamp() + ".txt");
                FileWriter w = new FileWriter(f);
                w.write(name);
                w.close();
                closeInput();
                speak("已保存记事");
                devLogLine("记事", "保存 " + f.getName());
            } catch (Exception e) {
                toast("保存失败：" + e.getMessage());
                speak("保存失败");
            }
            return;
        }
        if ("countdown".equals(inputAction)) {
            int mins = InputCheck.minutes(name);
            if (mins < 0) { speak("请输入 1 到 600"); return; }
            startSleepTimer(mins);
            closeInput();
            return;
        }

        if ("davurl".equals(inputAction) || "davuser".equals(inputAction)
                || "davpass".equals(inputAction) || "davdir".equals(inputAction)) {
            String v = name == null ? "" : name.trim();
            String key = "davurl".equals(inputAction) ? "davUrl"
                    : "davuser".equals(inputAction) ? "davUser"
                    : "davpass".equals(inputAction) ? "davPass" : "davDir";
            prefs.edit().putString(key, v).apply();
            applyDavConfig();
            if (!stack.isEmpty()) stack.set(stack.size() - 1, backupDavScreen());
            render(true);
            speak("已保存");
            return;
        }
        if ("threads".equals(inputAction)) {
            int v;
            try { v = Integer.parseInt(name.trim()); } catch (Exception e) { v = -1; }
            if (v < 1 || v > 16) { speak("要 1 到 16 之间的整数"); return; }
            prefs.edit().putInt("searchThreads", v).apply();
            if (!stack.isEmpty()) stack.set(stack.size() - 1, setDataScreen());
            render(true);
            speak("搜索线程数填 " + v + "，生效 " + searchThreads());
            return;
        }
        if ("subimpurl".equals(inputAction)) {
            String u = name;
            closeInput();
            ops.subImportUrl(u);
            return;
        }
        if ("bsrcimpurl".equals(inputAction)) {
            String u = name;
            closeInput();
            ops.bsrcImportUrl(u);
            return;
        }
        if (inputAction != null && inputAction.startsWith("bsrctest:")) {
            int si;
            try { si = Integer.parseInt(inputAction.substring(9)); } catch (Exception e) { return; }
            String kw = name.trim();
            closeInput();
            ops.bsrcValidate(si, kw.isEmpty() ? "我的" : kw);
            return;
        }
        if ("swgroup".equals(inputAction)) {
            String g = name.trim();
            closeInput();
            prefs.edit().putString("swGroup", g).apply();
            speak(g.isEmpty() ? "书源分组改为全部" : "书源分组设为 " + g);
            nb.nbStartSwitch();
            return;
        }
        if ("swkey".equals(inputAction)) {
            String w = name.trim();
            closeInput();
            prefs.edit().putString("swKey", w).apply();
            speak(w.isEmpty() ? "筛选词已清空" : "筛选词设为 " + w);
            nb.nbStartSwitch();
            return;
        }
        if ("tocfilter".equals(inputAction)) {
            String fw = name.trim();
            closeInput();
            prefs.edit().putString("tocFilterWords", fw).apply();
            com.mangjie.reader.reader.TocRules.reload(this);   // 规则重载 + 清掉目录缓存，新过滤词立刻生效
            com.mangjie.reader.reader.ChapterIndex.reset();
            speak(fw.isEmpty() ? "过滤词已清空，重开目录生效" : "过滤词已设为 " + fw + "，重开目录生效");
            return;
        }
        if ("bsrcfind".equals(inputAction)) {
            String q = name.trim();
            closeInput();
            stack.add(nb.bsrcFindScreen(q));
            render(true);
            return;
        }
        if ("mydictlook".equals(inputAction)) {
            String kw = name.trim();
            closeInput();
            if (kw.isEmpty()) { speak("请先输入要查的词"); return; }
            dictLookup(kw);
            return;
        }
        if ("bsrcgrp".equals(inputAction)) {
            String g = name.trim();
            closeInput();
            nb.loadBookSources();
            if (srcGrpIdx >= 0 && srcGrpIdx < nb.nbSources.size()) {
                String nm0 = nb.nbSources.get(srcGrpIdx).optString("bookSourceName", "");
                boolean ok = nb.setSourceGroup(srcGrpIdx, g);
                speak(ok ? ("已把 " + nm0 + " 改到「" + (g.isEmpty() ? "未分组" : g) + "」") : "改分组失败，看日志");
                if (!stack.isEmpty()) stack.set(stack.size() - 1, ops.bsrcDetailScreen(srcGrpIdx));
                render(true);
            }
            return;
        }
        if ("chkword".equals(inputAction)) {
            closeInput();
            int si = srcGrpIdx;
            if (si < 0 || si >= nb.nbSources.size()) return;
            String w = name == null ? "" : name.trim();
            boolean ok = nb.setSourceField(si, "ruleSearch.checkKeyWord", w);
            if (!stack.isEmpty()) stack.set(stack.size() - 1, ops.bsrcDetailScreen(si));
            render(true);
            speak(ok ? ("校验时用「" + (w.isEmpty() ? "全局关键词" : w) + "」") : "保存失败，看日志");
            return;
        }
        if ("chkkey".equals(inputAction)) {
            closeInput();
            String kk = name == null ? "" : name.trim();
            ops.setCheckCfg("chkKey", kk.isEmpty() ? "我的" : kk);
            stack.set(stack.size() - 1, ops.bsrcCheckScreen());
            render(true);
            speak("校验关键词：" + (kk.isEmpty() ? "我的" : kk));
            return;
        }
        if ("chktimeout".equals(inputAction)) {
            closeInput();
            int sec = 180;
            try { sec = Integer.parseInt(name.trim()); } catch (Exception e) { }
            if (sec < 1) sec = 1;
            if (sec > 600) sec = 600;
            ops.setCheckCfg("chkTimeoutSec", String.valueOf(sec));
            stack.set(stack.size() - 1, ops.bsrcCheckScreen());
            render(true);
            speak("超时改为 " + sec + " 秒");
            return;
        }
        if ("expkey".equals(inputAction)) {
            closeInput();
            String v = name == null ? "" : name.trim();
            prefs.edit().putString("exKey", v).apply();
            reopenExplore(v.isEmpty() ? "全部源名" : "只看源名含 " + v);
            return;
        }
        if ("exfind".equals(inputAction)) {
            closeInput();
            String v = name == null ? "" : name.trim();
            if (v.isEmpty()) { speak("没输关键词"); toast("关键词为空"); return; }
            pushScreen(exploreFindScreen(v));
            return;
        }
        if ("srcfld".equals(inputAction)) {
            closeInput();
            String v = name == null ? "" : name;
            boolean ok = nb.setSourceField(srcEditIdx, pendingSrcField, v);
            if (!stack.isEmpty()) stack.set(stack.size() - 1, srcRuleEditScreen());
            render(true);
            speak(ok ? "已保存" : "保存失败，看看日志");
            return;
        }
        if ("dictrule1".equals(inputAction)) {
            closeInput();
            String nm = name, url = "";
            int c1 = name.indexOf(',');
            if (c1 < 0) c1 = name.indexOf('，');
            if (c1 > 0) { nm = name.substring(0, c1).trim(); url = name.substring(c1 + 1).trim(); }
            if (nm.isEmpty() || url.isEmpty()) { speak("名字和网址都要填"); toast("都要填"); return; }
            if (!url.contains("{{key}}")) { speak("网址里得留 {{key}} 占位，不然不知道该把词塞哪"); toast("缺 {{key}}"); return; }
            pendRuleName = nm;
            pendRuleUrl = url;
            inputAction = "dictrule2";
            inputTitle = "规则选择器";
            openInput("填选择器：div.entry 这种按 CSS 取；result.explain 这种带点的按 JSON 路径取；留空就取整页文字");
            return;
        }
        if ("dictrule2".equals(inputAction)) {
            closeInput();
            String sel = name.trim();
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", pendRuleName);
                o.put("url", pendRuleUrl);
                boolean json = !sel.isEmpty() && !sel.startsWith(".") && !sel.startsWith("#")
                        && !sel.contains(">") && sel.contains(".") && !sel.startsWith("div")
                        && !sel.startsWith("span") && !sel.startsWith("p");
                o.put("type", json ? "json" : "html");
                if (json) o.put("path", sel); else o.put("css", sel);
                File dir = dictRuleDir();
                String fn = pendRuleName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".json";
                File f = new File(dir, fn);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                fo.write(o.toString(2).getBytes("UTF-8"));
                fo.close();
                com.mangjie.reader.reader.DictOnline.load(dir);
                devLogLine("词典", "新建在线规则 " + fn + "，现有 "
                        + com.mangjie.reader.reader.DictOnline.count() + " 条");
                while (!stack.isEmpty() && top().key != null
                        && ("dictRules".equals(top().key) || "dictRulesHelp".equals(top().key))) {
                    stack.remove(stack.size() - 1);
                }
                if (!stack.isEmpty()) stack.set(stack.size() - 1, dictRulesScreen());
                render(true);
                speak("规则已加：" + pendRuleName);
            } catch (Exception e) {
                devLogLine("词典", "写规则失败：" + e.getMessage());
                speak("规则没写成");
                toast("失败");
            }
            return;
        }
        if ("bsrcnew".equals(inputAction)) {
            closeInput();
            String nm = name, url = "";
            int comma = name.indexOf(',');
            if (comma < 0) comma = name.indexOf('，');
            if (comma > 0) { nm = name.substring(0, comma).trim(); url = name.substring(comma + 1).trim(); }
            ops.setPendingNew(nm, url);
            // 不直接建：先让用户挑「空源 / 通用兜底 / 嗅探搜索入口」
            pushScreen(ops.bsrcNewWayScreen());
            return;
        }
        if ("nbsearch".equals(inputAction)) {
            String kw = name;
            closeInput();
            nb.nbSearch(kw);
            return;
        }
        if (name.isEmpty()) { speak("名称不能为空"); toast("名称不能为空"); return; }
        if (name.contains("/")) { speak("名称里不能带斜杠"); toast("名称不能带斜杠"); return; }

        if ("find".equals(inputAction)) {
            closeInput();
            performFind(name);
            return;
        }

        if ("bookfind".equals(inputAction)) {
            closeInput();
            if (!netReading || nb == null) { performFind(name); return; }   // 本地书「查找」本来就是全书
            searchWholeBook(name);
            return;
        }

        if ("timer".equals(inputAction)) {
            String t = name == null ? "" : name.trim();
            if ("0".equals(t) || "取消".equals(t)) { closeInput(); cancelSleepTimer(); return; }
            int m = InputCheck.minutes(name);
            if (m < 0) { speak("请输入 1 到 600，输 0 取消定时"); toast("范围 1 到 600，0 为取消"); return; }
            closeInput();
            startSleepTimer(m);
            return;
        }

        if ("pos".equals(inputAction)) {
            int v = InputCheck.percent(name);
            if (v < 0) { speak("请输入 0 到 100"); toast("范围 0 到 100"); return; }
            int idx = com.mangjie.reader.reader.BookProgress.indexAtPercent(bookSent.size(), v);
            closeInput();
            autoPlay = true;
            speakSentence(idx);
            savePos(idx);
            return;
        }

        if ("pct".equals(inputAction)) {
            int v = InputCheck.percent(name);
            if (v < 0) { speak("请输入 0 到 100 之间"); toast("范围 0 到 100"); return; }
            if ("sfx:vol".equals(pendingPctKey)) {
                sfxVol = v;
                toneGen = null;          // 下次创建时按新音量重建
                savePrefs();
                closeInput();
                lastSfxAt = 0;
                sfx(android.media.ToneGenerator.TONE_PROP_BEEP, 40);
                refreshTop("音效反馈音量 " + v + "%");
                return;
            }
            if ("rv:rate".equals(pendingPctKey) || "rv:pitch".equals(pendingPctKey)) {
                if ("rv:rate".equals(pendingPctKey)) readerVoice.rateP = v; else readerVoice.pitchP = v;
                applySpeechParams();
                savePrefs();
                closeInput();
                refreshTop(("rv:rate".equals(pendingPctKey) ? "朗读语速 " : "朗读语调 ") + v + "%");
                return;
            }
            if ("sp:rate".equals(pendingPctKey)) menuVoice.rateP = v; else menuVoice.pitchP = v;
            applySpeechParams();
            savePrefs();
            closeInput();
            refreshTop((("sp:rate".equals(pendingPctKey)) ? "语速 " : "语调 ") + v + "%");   // 重建本页，行文本同步
            return;
        }

        File dir = new File(curDir == null ? internalRoot() : curDir);
        File target = new File(dir, name);
        boolean ok;
        if ("mkdir".equals(inputAction)) {
            try { ok = target.mkdirs(); } catch (Exception e) { ok = false; }
            speak(ok ? "已新建 " + name : "新建失败");
            toast(ok ? "已新建 " + name : "新建失败");
        } else {
            ok = false;
            speak("重命名失败");
        }
        closeInput();
        refreshDir();
    }

    private void closeInput() {
        inputOn = false;
        precisionRowView = null;
        inputRows.clear();
        inputCur = 1;
        inputBox = null;
        if (menuOn || dialogOn) return;
        render(false);
    }

    private void renderDialog() {
        titleView.setText(top().extra == null ? top().title : (top().title + top().extra));
        clockView.setText(hhmm.format(new Date()));
        rowsBox.removeAllViews();

        TextView msg = new TextView(this);
        msg.setText(dialogMsg);
        msg.setTextColor(palFg());
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp());
        msg.setIncludeFontPadding(false);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(dp(6), dp(14), dp(6), dp(14));
        rowsBox.addView(msg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));

        TextView focusView = null;
        for (int i = 0; i < dialogBtns.length; i++) {
            final int idx = i;
            TextView row = makeRow(dialogBtns[i], i == dialogSel,
                    dialogBtns[i] + (i == dialogSel ? "，当前" : ""));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx != dialogSel) { dialogSel = idx; render(false); speak(dialogBtns[idx]); }
                    else commitDialog();
                }
            });
            bindA11yFocus(row, i + 1);
            rowsBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
            if (i == dialogSel) focusView = row;
        }
        if (scroll != null) scroll.post(new Runnable() { @Override public void run() { scroll.scrollTo(0, 0); } });
        focusRow(focusView);
        if (screenReaderOn) {
            // 对话框消息属于结果类提示，走 speak（读屏开着时它也由本机语音念）；
            // 单发 announce 给点明这类只念焦点、不念公告的读屏，等于白念
            speak(dialogMsg);
        }
    }

    /** 当前可视区能放几整行 */
    private int visibleRows() {
        int h = scroll != null && scroll.getHeight() > 0
                ? scroll.getHeight()
                : getResources().getDisplayMetrics().heightPixels - dp(HEADER_DP);
        return Math.max(3, h / dp(ROW_DP));
    }

    // ---------------------------------------------------------------- 触摸

    private void nudge(String k, int d) {
        if ("sp:rate".equals(k)) menuVoice.rateP = Math.max(SLIDER_MIN, Math.min(SLIDER_MAX, menuVoice.rateP + d));
        else menuVoice.pitchP = Math.max(SLIDER_MIN, Math.min(SLIDER_MAX, menuVoice.pitchP + d));
        applySpeechParams();
        savePrefs();
        int v = "sp:rate".equals(k) ? menuVoice.rateP : menuVoice.pitchP;
        refreshTop(("sp:rate".equals(k) ? "语速 " : "语调 ") + v + "%");
    }

    private void cancelFollow() {
        if (followRunnable != null) { ui.removeCallbacks(followRunnable); followRunnable = null; }
    }


    private android.os.Vibrator vib = null;

    private android.media.ToneGenerator toneGen = null;
    private long lastSfxAt = 0;
    private int toneVol = -1;

    /** 短音效（对齐读屏的 earcon）：focus 用单响，execute 用双响 */
    private void sfx(int type, int ms) {
        if (screenReaderOn) return;
        long now = System.currentTimeMillis();
        if (now - lastSfxAt < 45) return;
        lastSfxAt = now;
        if (sfxVol <= 0) return;
        try {
            if (toneGen == null || toneVol != sfxVol) {
                if (toneGen != null) { try { toneGen.release(); } catch (Exception ignored) {} }
                toneGen = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, sfxVol);
                toneVol = sfxVol;
            }
            toneGen.startTone(type, ms);
        } catch (Exception ignored) {}
    }

    /** 执行一项时的一下稍长震动（对齐读屏双击） */
    private void tickOnce() {
        if (screenReaderOn) return;
        if (soundFx) sfx(android.media.ToneGenerator.TONE_PROP_ACK, 110);
        if (hapticOn) buzz(30);
    }

    /** 经过一行给一下短震（对齐读屏：读屏每换一个焦点都会 tick 一下） */
    private long lastTickAt = 0;

    private void tick() {
        if (screenReaderOn) return;      // 读屏开着自己会震，别震两下
        if (!soundFx && !hapticOn) return;
        long now = System.currentTimeMillis();
        if (now - lastTickAt < 55) return;   // 快速划过时别糊成一串
        lastTickAt = now;
        if (soundFx) sfx(android.media.ToneGenerator.TONE_PROP_BEEP, 40);
        if (hapticOn) buzz(15);
    }

    /** 本机是否有线性马达：有振幅控制能力的通常就是 LRA（线性），没有的多为转子/振子马达 */
    private boolean hasLinearMotor() {
        if (hasLinearCache != null) return hasLinearCache;
        boolean r = false;
        try {
            if (vib == null) vib = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()
                    && android.os.Build.VERSION.SDK_INT >= 26) {
                r = vib.hasAmplitudeControl();
            }
        } catch (Exception ignored) {}
        hasLinearCache = r;
        return r;
    }

    /** 强度对应的振幅（0弱 1中 2强） */
    private int hapAmp() {
        return hapLevel == 0 ? 70 : (hapLevel == 2 ? 255 : 140);
    }

    /** 强度对应的时长倍数 */
    private int hapMs(int base) {
        return hapLevel == 0 ? Math.max(8, base / 2) : (hapLevel == 2 ? base + 10 : base);
    }

    /** 短震一下（不判断开关，供开关确认与 tick 调用） */
    private void buzz(int ms) {
        if (screenReaderOn) return;
        try {
            if (vib == null) vib = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                if (hasLinearMotor() || useLinear) {
                    vib.vibrate(android.os.VibrationEffect.createOneShot(hapMs(ms), hapAmp()));
                } else {
                    vib.vibrate(android.os.VibrationEffect.createOneShot(hapMs(ms),
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } else {
                vib.vibrate(hapMs(ms));
            }
        } catch (Exception ignored) {}
    }

    /** 把焦点移到某一行（带滚屏与日志），供触摸探索与单击使用 */
    private void focusToRow(int idx) {
        Screen sc = curScreen();
        if (idx < 0 || idx >= sc.items.size()) return;
        if (sc.cur == idx) {
            // 同一个行号也可能视觉上没落在这一行（列表刚追加过、或上次高亮没刷新），重刷一次再走
            highlightSelection();
            ensureRowVisible(idx);
            return;
        }
        if (sc.cur != idx) {
            int old = sc.cur;
            sc.cur = idx;
            highlightSelection();
            ensureRowVisible(idx);
            devLogLine("自身光标", "触摸聚焦第" + idx + "行，焦点由第" + old + "行跟随");
            tick();
        }
    }

    private boolean exploreFollowed = false;    // 阅读页这一次手势是探索（光标已经跟手）

    /**
     * 探索跟随：只挪光标、刷两行背景、滚到可见，不碰朗读状态、不推进节拍。
     * 不走 focusToRow 是因为那里会 tick()，摸一下就相当于按了一下。
     */
    private void exploreFocusTo(int idx) {
        Screen sc = top();
        if (sc == null || idx < 0 || idx >= sc.items.size()) return;
        if (sc.cur == idx) { highlightSelection(); ensureRowVisible(idx); return; }
        int old = sc.cur;
        sc.cur = idx;
        highlightSelection();
        ensureRowVisible(idx);
        String line = sc.items.get(idx).label;
        speak(line == null ? "" : line);
        devLogLine("探索", "摸到第" + idx + "行，光标由第" + old + "行跟随");
    }

    private Runnable touchSpeakRunnable = null;
    private Runnable followRunnable = null;      // 慢拖时手指停住才跟随的一步
    private boolean exploredInGesture = false;   // 本次手势里焦点已经跟过手指（慢拖）
    private int downIndexAtStart = 0;            // 起手时的焦点行
    private boolean dwellFocused = false;        // 按住 250ms 已经读过这一行
    private boolean followed = false;            // 本次手势里焦点已经跟过手指

    private void cancelTouchSpeak() {
        if (touchSpeakRunnable != null) {
            ui.removeCallbacks(touchSpeakRunnable);
            touchSpeakRunnable = null;
        }
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) { ui.removeCallbacks(longPressRunnable); longPressRunnable = null; }
    }

    private int rowOffset = 0;              // 阅读页只渲染当前句附近一段，行号 = 句号 - rowOffset
    private int lastHighlightIdx = -1;      // 上一次红条在哪一行：只改这两行，不再遍历全列表

    /** 行焦点已搬到 view/RowFocus，主类只留转发与接线（外壳第二刀） */
    private com.mangjie.reader.view.RowFocus rowFocus;
    private com.mangjie.reader.view.RowFocus focus() {
        if (rowFocus == null) rowFocus = new com.mangjie.reader.view.RowFocus(new com.mangjie.reader.view.RowFocus.Host() {
            @Override public Screen curScreen() { return MainActivity.this.curScreen(); }
            @Override public LinearLayout rowsBox() { return rowsBox; }
            @Override public ScrollView scroll() { return scroll; }
            @Override public LinearLayout menuBox() { return menuBox; }
            @Override public ScrollView menuScroll() { return menuScroll; }
            @Override public boolean menuOn() { return menuOn; }
            @Override public int rowOffset() { return rowOffset; }
            @Override public int lastHighlightIdx() { return lastHighlightIdx; }
            @Override public void lastHighlightIdx(int v) { lastHighlightIdx = v; }
            @Override public void paintSelected(View v) { MainActivity.this.paintSelected(v); }
            @Override public int palBg() { return MainActivity.this.palBg(); }
            @Override public void focusRow(TextView v) { MainActivity.this.focusRow(v); }
            @Override public int dp(float v) { return MainActivity.this.dp(v); }
            @Override public void render(boolean reset) { MainActivity.this.render(reset); }
            @Override public void speak(String m) { MainActivity.this.speak(m); }
        });
        return rowFocus;
    }

    private void highlightSelection() { focus().highlight(); }

    /** 优先按 View 树命中：跟「[按下]」用的是同一套 TouchExplorer，滚多远、行高齐不齐都不影响。
     *  命不中就退回下面按行高累加的老办法（菜单浮层没有列在 rowsBox 里，走的是那一条）。 */
    private int rowAt(float x, float y) {
        Screen scNow = curScreen();
        if (scNow != null && !menuOn && !dialogOn && !inputOn && explorer != null) {
            com.mangjie.reader.a11y.TouchExplorer.Hit h = explorer.hit(x, y);
            if (h != null && h.type == com.mangjie.reader.a11y.TouchExplorer.ROW
                    && h.row >= 0 && h.row < scNow.items.size()) return h.row;
        }
        return rowAt(y);
    }

    private int rowAt(float y) {
        LinearLayout hitBox = (menuOn && menuBox != null) ? menuBox : rowsBox;
        if (hitBox == null) return -1;
        int[] loc = new int[2];
        hitBox.getLocationInWindow(loc);
        // getLocationInWindow 返回的是「此刻屏幕上」的位置，滚动位移已经算在里面了：
        // 列表往下滚 200 像素，rowsBox 的窗口 top 就变成 -200，y - loc[1] 天然就是内容坐标。
        // 1.1.13 在这里又加了一次 scrollY，等于把滚动算两遍——滚几行就偏几行。
        // 日志实证（a11y-20261121-121047）：12:10:21 同一点 y=259，按下命中第2行、抬起却算成第4行；
        // 12:10:24 y=296 按下第5行、抬起跳到第9行。手指摸第3项，光标落第5项。
        float yInBox = y - loc[1];
        int n = hitBox.getChildCount();
        if (n == 0) return -1;
        int[] tops = new int[n];
        int[] bottoms = new int[n];
        boolean[] focusable = new boolean[n];
        for (int i = 0; i < n; i++) {
            View c = hitBox.getChildAt(i);
            focusable[i] = c.isFocusable();
            tops[i] = c.getTop();
            bottoms[i] = c.getTop() + c.getHeight();
        }
        // 手指滑出列表上下边界时贴住首行/末行，别让跟随中断
        int off = (menuOn && menuBox != null) ? 0 : rowOffset;
        return com.mangjie.reader.reader.RowHit.hit(yInBox, tops, bottoms, focusable,
                off, dp(8), dp(80));
    }

    private Runnable dragSpeakRunnable;
    private long downTime = 0;
    private int downIndexOfStart = -1;

    private void scheduleDragSpeak() {
        if (dragSpeakRunnable != null) ui.removeCallbacks(dragSpeakRunnable);
        dragSpeakRunnable = new Runnable() {
            @Override public void run() {
                Screen sc = top();
                int i = sc.cur;
                if (i >= 0 && i < sc.items.size()) speakRow(sc.items.get(i).label);
            }
        };
        ui.postDelayed(dragSpeakRunnable, 400);
    }

    private void cancelDragSpeak() {
        if (dragSpeakRunnable != null) { ui.removeCallbacks(dragSpeakRunnable); dragSpeakRunnable = null; }
    }

    private void touchSelect(int row, boolean dragging) {
        if (dialogOn) {
            if (row < 1 || row > dialogBtns.length) return;
            int i = row - 1;
            if (dialogSel != i) { dialogSel = i; highlightDialog(); }
            if (!dragging) speak(dialogBtns[i]);
            return;
        }
        Screen sc = curScreen();
        if (row < 0 || row >= sc.items.size()) return;
        if (sc.cur != row) {
            sc.cur = row;
            highlightSelection();
            ScrollView sv = (menuOn && menuScroll != null) ? menuScroll : scroll;
            LinearLayout box = (menuOn && menuScroll != null) ? menuBox : rowsBox;
            if (sv != null) sv.scrollTo(0, Math.min(row * dp(ROW_DP),
                    Math.max(0, box.getHeight() - sv.getHeight())));
        }
        if (dragging) {
            // 慢拖时一进入新的一项就立刻读，不再等停顿
            if (lastSpokenRow != row) {
                lastSpokenRow = row;
                speakRow(sc.items.get(row).label);
            }
        } else {
            speakRow(sc.items.get(row).label);
        }
    }

    private void highlightDialog() {
        for (int i = 0; i < rowsBox.getChildCount(); i++) {
            View c = rowsBox.getChildAt(i);
            if (i == dialogSel + 1) paintSelected(c); else c.setBackgroundColor(palBg());
            if (c instanceof TextView) ((TextView) c).setTextColor(i == dialogSel + 1 ? 0xFFFFFFFF : palFg());
        }
        if (dialogSel + 1 < rowsBox.getChildCount()) focusRow((TextView) rowsBox.getChildAt(dialogSel + 1));
    }

    private void tapRow(int visualIdx) {
        if (dialogOn) {
            if (visualIdx < 1 || visualIdx > dialogBtns.length) return;
            dialogSel = visualIdx - 1;
            commitDialog();
            return;
        }
        Screen sc = top();
        if (visualIdx < 0 || visualIdx >= sc.items.size()) return;
        sc.cur = visualIdx;
        highlightSelection();
        activate();
    }

    /** 正文自动朗读期间：除了「暂停／播放」手势，其它触摸一律屏蔽 */
    private boolean isReadingBook() {
        // 不再看 autoPlay：暂停后仍停在阅读页，否则会掉进列表触摸浏览，摸一下就念一句
        return top() != null && top().reader && !dialogOn && !menuOn && !inputOn;
    }

    private boolean readingTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                readTouchStartY = ev.getY();
                readTouchStartX = ev.getX();
                dirLock = 0;
                readTouchMoved = false;
                downTime = SystemClock.uptimeMillis();
                exploreFollowed = false;
                readTouchMaxPointers = ev.getPointerCount();
                scanActive = false;
                scanAnchorX = (int) ev.getX();
                scanSpokenIdx = -1;
                scanCursor = buildScanCursor();
                scanAnchorIdx = scanCursor.indexOf(top().cur, 0);
                scanCurIdx = scanAnchorIdx;
                String pageTag = (top().title == null ? "" : top().title) + "/" + top().items.size();
                if (readSel.hasStart() && !pageTag.equals(selPageTag)) { readSel.clear(); }
                selPageTag = pageTag;
                cancelScanTask();
                // 只在不朗读时允许长按扫字，朗读中长按不接
                if (!autoPlay) {
                    scanTask = new Runnable() { @Override public void run() {
                        scanTask = null;
                        scanAnchorIdx = scanCurIdx;
                        enterScanMode();
                    } };
                    ui.postDelayed(scanTask, com.mangjie.reader.input.HoldScan.HOLD_MS);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                readTouchMaxPointers = Math.max(readTouchMaxPointers, ev.getPointerCount());
                cancelScanTask();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (scanActive) {
                    // 已在扫字：手指左右拖，一格一个字，念出当前字
                    int delta = com.mangjie.reader.input.HoldScan.deltaChars(
                            ev.getX() - scanAnchorX, getResources().getDisplayMetrics().density);
                    int target = scanAnchorIdx + delta;
                    if (scanCursor != null && scanCursor.total() > 0)
                        target = Math.max(0, Math.min(scanCursor.total() - 1, target));
                    if (target != scanCurIdx) {
                        scanCurIdx = target;
                        speakScanChar();
                    }
                    return true;
                }
                // 单指按住超过 150 毫秒再移动＝探索：手指摸到哪一句，光标就挪到哪一句。
                // 快滑 150 毫秒内就划过去了，仍走抬手切句，不受影响。
                // 之前这里根本没有跟随，正文页摸半天光标纹丝不动（用户反馈）。
                if (!autoPlay && SystemClock.uptimeMillis() - downTime > 150) {
                    int rowNow = rowAt(ev.getY());
                    if (rowNow >= 0) {
                        exploreFollowed = true;
                        exploreFocusTo(rowNow);
                    }
                }
                float dxNow = ev.getX() - readTouchStartX;
                float dyNow = ev.getY() - readTouchStartY;
                float farPx = dp(com.mangjie.reader.input.HoldScan.CANCEL_MOVE_DP);
                if (Math.abs(dxNow) > farPx || Math.abs(dyNow) > farPx) {
                    readTouchMoved = true;
                    cancelScanTask();      // 动了就不算长按，退出扫字预备
                    // 跟列表页同一套：先左再右＝呼出菜单，先右再左＝返回
                    dirLock = com.mangjie.reader.input.GestureJudge.dirLock(dirLock, dxNow, dyNow, dp(20));
                    String revG = com.mangjie.reader.input.GestureJudge.reversed(dirLock, dxNow, dp(30));
                    if (revG != null) reversedGesture = revG;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                cancelScanTask();
                if (reversedGesture != null) {
                    String g = reversedGesture;
                    reversedGesture = null;
                    scanActive = false;
                    if ("LR".equals(g)) openPageMenu();
                    else goBack();
                    return true;
                }
                if (scanActive) {
                    scanActive = false;
                    commitScanPoint();
                    return true;
                }
                // 这一下是探索（按住摸过去的），光标已经跟到手指那，抬手不该再跳一句
                if (exploreFollowed) {
                    exploreFollowed = false;
                    return true;
                }
                // 上下滑＝上一句／下一句：暂停态只念这一句，朗读中跳到那句续读
                float dyUp = ev.getY() - readTouchStartY;
                float dxUp = ev.getX() - readTouchStartX;
                if (Math.abs(dyUp) > dp(40) && Math.abs(dyUp) > Math.abs(dxUp)) {
                    lastTapTime = 0;
                    sentenceStep(dyUp < 0 ? -1 : 1);
                    return true;
                }
                // 双击＝暂停／继续，有起点时先从起点读
                if (!readTouchMoved) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastTapTime < 320) {
                        lastTapTime = 0;
                        if (!autoPlay && readSel.hasStart() && !readSel.hasEnd()) startFromSelection();
                        else toggleReading();
                    } else {
                        lastTapTime = now;
                    }
                }
                return true;
            }
        }
        return true;
    }

    /** 阅读页上下滑：一次一格句子；暂停只念这句，朗读中从这句续读 */
    private void sentenceStep(int dir) {
        Screen sc = top();
        if (sc == null || sc.items.isEmpty()) return;
        int target = sc.cur + dir;
        if (target < 0) { speak("已经是第一句"); return; }
        if (target >= sc.items.size()) { speak("已经是最后一句"); return; }
        sc.cur = target;
        highlightSelection();
        ensureRowVisible(target);
        if (autoPlay) {
            readingNow = true;
            speakSentence(target);
        } else {
            String line = sc.items.get(target).label;
            speak(line == null ? "" : line);
        }
        devLogLine("阅读", (dir < 0 ? "上滑→上一句" : "下滑→下一句") + "　第" + (target + 1) + "句"
                + (autoPlay ? "（朗读中续读）" : "（暂停，只念）"));
    }

    private void cancelScanTask() {
        if (scanTask != null) { ui.removeCallbacks(scanTask); scanTask = null; }
    }

    /** 把正文页每一句拼成字级光标 */
    private com.mangjie.reader.reader.TextCursor buildScanCursor() {
        List<String> lines = new ArrayList<>();
        Screen sc = top();
        if (sc != null) for (Item it : sc.items) lines.add(it.label == null ? "" : it.label);
        return new com.mangjie.reader.reader.TextCursor(lines);
    }

    private void enterScanMode() {
        scanActive = true;
        buzz(40);
        speak("扫字中，左右拖动选字，抬手定标");
    }

    private void speakScanChar() {
        if (scanCursor == null || scanCurIdx == scanSpokenIdx) return;
        scanSpokenIdx = scanCurIdx;
        String ch = scanCursor.charAt(scanCurIdx);
        if (ch != null && ch.length() > 0) speak(ch);
    }

    /** 抬手定标：第一次定起点，第二次定终点并摘抄 */
    private void commitScanPoint() {
        if (scanCursor == null) return;
        if (readSel.complete()) readSel.clear();      // 上一轮已摘抄完，这次重新标起点
        int[] p = scanCursor.locate(scanCurIdx);
        if (!readSel.hasStart()) {
            readSel.startIdx = scanCurIdx;
            readSel.endIdx = -1;
            buzz(40);
            speak("起点，第 " + (p[0] + 1) + " 句第 " + (p[1] + 1) + " 字。双击从这里读");
            return;
        }
        readSel.endIdx = scanCurIdx;
        boolean swapped = readSel.normalize();
        String text = scanCursor.slice(readSel.startIdx, readSel.endIdx);
        int[] a = scanCursor.locate(readSel.startIdx);
        int[] b = scanCursor.locate(readSel.endIdx);
        int code = com.mangjie.reader.reader.Excerpt.save(this, new File(DATA_DIR),
                bookName == null ? "" : bookName, top() == null ? "" : top().title,
                a[0], b[0], text);
        String what = code == com.mangjie.reader.reader.Excerpt.OK_BOTH
                ? "已复制，并写进摘抄本"
                : (code == com.mangjie.reader.reader.Excerpt.OK_CLIP
                    ? "已复制，摘抄本写入失败"
                    : "复制失败");
        speak("终点，第 " + (b[0] + 1) + " 句第 " + (b[1] + 1) + " 字，共 "
                + readSel.length() + " 字，" + what + (swapped ? "，起止已对调" : ""));
        devLogLine("摘抄", "第" + (a[0] + 1) + "至" + (b[0] + 1) + "句　" + readSel.length() + " 字　结果=" + code);
    }

    /** 从选区起点开始读 */
    private void startFromSelection() {
        scanCursor = buildScanCursor();
        int[] p = scanCursor.locate(readSel.startIdx);
        if (p[0] < 0 || p[0] >= top().items.size()) return;
        top().cur = p[0];
        highlightSelection();
        autoPlay = true;
        readingNow = true;
        speak("从起点开始朗读");
        speakSentence(p[0]);
    }

    /** 1.0.62 诊断用：一行说完朗读此刻的处境，前后台各打一次方便对照 */
    private String readStateText() {
        String cur = (stack.isEmpty() || !top().reader) ? "-" : ("" + (top().cur + 1));
        return "　朗读状态：开播=" + (autoPlay ? "是" : "否") + " 在念=" + (readingNow ? "是" : "否")
                + " 批=" + batchSeq + " 末句=" + (batchLast + 1) + " 光标=" + cur
                + " 看门狗=" + (com.mangjie.reader.speech.TtsWatchdog.watching() ? "挂着" : "空")
                + " 续读表=" + (resumeGuard != null ? "挂着" : "空")
                + " 阅读引擎=" + (readerVoice == null || readerVoice.enginePkg == null ? "系统默认" : readerVoice.enginePkg);
    }

    /**
     * 撤掉界面提示的兜底续读表。
     * 正文一旦自己续上（speakAhead 真送句、界面回调到了），兜底的前提就不成立，
     * 留着它 3.5 秒后会把当前句重读一遍——就是「同一句念两遍」那个毛病。
     * 不动 resumeMissCount：那个数是「连续几次没跟上」，正文真出声时由 onStart 清零。
     */
    private void cancelResumeGuard() {
        if (resumeGuard != null) { ui.removeCallbacks(resumeGuard); resumeGuard = null; }
        resumeReadingAfterSpeak = false;
    }

    /** 界面语音结束回调丢了也不能让正文永久停住：3.5 秒后强制续读当前句 */
    private void armResumeGuard() {
        if (resumeGuard != null) ui.removeCallbacks(resumeGuard);
        resumeGuard = new Runnable() { @Override public void run() {
            resumeGuard = null;
            if (!resumeReadingAfterSpeak) return;
            // 正文那边还挂着看门狗，说明这一批已经送出去了、正在念，不算「没回调」，别重读
            if (com.mangjie.reader.speech.TtsWatchdog.watching()) {
                resumeReadingAfterSpeak = false;
                return;
            }
            if (!autoPlay || !top().reader) { resumeReadingAfterSpeak = false; return; }
            resumeReadingAfterSpeak = false;
            resumeMissCount++;
            if (resumeMissCount >= 3) {
                devLogLine("阅读", "连续 " + resumeMissCount + " 次没回调，停止自动续读");
                speak("朗读引擎连续没响应，已停止重试，请到语音正文里换一个引擎");
                autoPlay = false;
                return;
            }
            readingNow = true;
            devLogLine("阅读", "语音未回调，自动续读第" + (top().cur + 1) + "句（第" + resumeMissCount + "次）");
            speakSentence(top().cur);
        } };
        ui.postDelayed(resumeGuard, 3500);
    }

    // ------------------------------------------------------------ 正文版式：行距 / 页边距 / 简繁
    private static final String[] LINE_SPACE_NAMES = {"紧凑", "标准", "宽松"};
    private static final String[] SIDE_PAD_NAMES = {"窄", "标准", "宽"};
    private int pendingRuleDeletePur = -1;
    private int pendingRuleDeleteToc = -1;
    private String ruleName = "", rulePattern = "", ruleRepl = "";
    private boolean pendingRestore = false;
    private boolean pendingStatClear = false;
    private boolean pendingExClear = false;
    private boolean pendingCacheClear = false;
    private Handler nightHandler = null;
    private boolean nightApplied = false;

    private int lineSpaceIdx = 1;
    private int sidePadIdx = 1;
    private boolean simplifyOn = false;
    private Typeface customFont = null;
    private String customFontPath = null;

    /** 自定义正文体；没设或文件不在就返回 null（走系统字体） */
    private Typeface currentTypeface() {
        String p = prefs == null ? null : prefs.getString("fontPath", null);
        if (p == null || p.isEmpty()) { customFont = null; customFontPath = null; return null; }
        if (p.equals(customFontPath) && customFont != null) return customFont;
        try {
            File f = new File(p);
            if (!f.exists()) return null;
            customFont = Typeface.createFromFile(f);
            customFontPath = p;
            return customFont;
        } catch (Throwable e) {
            toast("字体文件读不了，已用默认字体");
            return null;
        }
    }

    private float lineSpaceMult() {
        return lineSpaceIdx == 0 ? 0.92f : (lineSpaceIdx == 2 ? 1.28f : 1.0f);
    }

    private int lineSpaceAddDp() {
        return lineSpaceIdx == 0 ? 1 : (lineSpaceIdx == 2 ? 6 : 2);
    }

    private int sidePadDp() {
        return sidePadIdx == 0 ? 4 : (sidePadIdx == 2 ? 16 : 8);
    }

    // ============================================================ 音频源播放页

    private com.mangjie.reader.media.AudioBookPlayer audioPlayer;
    private int aIdx = -1;
    private float aRate = 1.0f;

    private com.mangjie.reader.media.AudioBookPlayer audio() {
        if (audioPlayer == null) {
            audioPlayer = new com.mangjie.reader.media.AudioBookPlayer();
            audioPlayer.setListener(new com.mangjie.reader.media.AudioBookPlayer.Listener() {
                @Override public void onState() {
                    ui.post(new Runnable() { @Override public void run() { refreshAudioRows(); } });
                }
                @Override public void onReady(int d) { }
                @Override public void onTick(int pos, int dur) {
                    ui.post(new Runnable() { @Override public void run() { refreshAudioProgress(pos, dur); } });
                }
                @Override public void onError(final String m) {
                    devLogLine("音频", m);
                    ui.post(new Runnable() { @Override public void run() {
                        speak("音频播放出错，这一章可能打不开");
                        toast("播放出错");
                    } });
                }
                @Override public void onEnd() {
                    ui.post(new Runnable() { @Override public void run() { audioNext(); } });
                }
            });
        }
        return audioPlayer;
    }

    private boolean audioPageOn() {
        Screen sc = top();
        return sc != null && "audio".equals(sc.key);
    }

    /** 点音频书的章节：先看本地缓存，没有再去解析地址 */
    private void audioPlayChapter(final int idx) {
        if (idx < 0 || idx >= nb.nbChapters.size()) return;
        aIdx = idx;
        final String[] ch = nb.nbChapters.get(idx);
        speak("正在打开第 " + (idx + 1) + " 章");
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                final File local = audioCacheFile(idx);
                String u = local.exists() && local.length() > 0 ? local.getAbsolutePath() : nb.nbAudioUrlOf(ch[1]);
                final String url = u == null ? "" : u;
                final boolean isLocal = local.exists() && local.length() > 0;
                ui.post(new Runnable() { @Override public void run() {
                    if (url.isEmpty()) {
                        speak("这一章没找到音频地址，可能规则不对");
                        toast("没找到音频地址");
                        return;
                    }
                    openAudioScreen();
                    holdReadFocus();
                    audio().setRate(aRate);
                    audio().play(url, isLocal);
                    devLogLine("音频", "播放 第" + (idx + 1) + "章 " + (isLocal ? "本地" : url));
                    if (!isLocal) cacheAudioInBack(idx, ch[1]);
                } });
            }
        });
    }

    private void openAudioScreen() {
        if (audioPageOn()) { stack.set(stack.size() - 1, audioPlayScreen()); render(true); return; }
        stack.add(audioPlayScreen());
        render(true);
    }

    private Screen audioPlayScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item(audio().isPlaying() ? "暂停" : "播放", "ab:toggle"));
        items.add(new Item("上一章", "ab:prev"));
        items.add(new Item("下一章", "ab:next"));
        items.add(new Item("退 15 秒", "ab:r15"));
        items.add(new Item("进 15 秒", "ab:f15"));
        items.add(new Item("语速：" + aRate + " 倍", "ab:rate"));
        items.add(new Item("进度：" + com.mangjie.reader.media.AudioBookPlayer.fmt(audio().position())
                + " / " + com.mangjie.reader.media.AudioBookPlayer.fmt(audio().duration()), "ab:tell"));
        items.add(new Item("目录（第 " + (aIdx + 1) + "/" + nb.nbChapters.size() + " 章）", "ab:toc"));
        items.add(new Item(nb.nbIsFav(nb.nbBookUrl) ? "★ 已在书架" : "加入书架", "nb:favtoggle"));
        Screen sc = new Screen("播放　" + (nb.nbBookName == null ? "" : nb.nbBookName), items).k("audio");
        sc.player = true;
        // 书封面之前抓过就直接拿来贴在顶上，像普通播放器那样
        try {
            File cf = new File(com.mangjie.reader.net.ChapterCache.bookDir(DATA_DIR, nb.nbBookUrl), "cover.img");
            if (cf.isFile() && cf.length() > 0) sc.coverPath = cf.getAbsolutePath();
        } catch (Throwable ignored) {}
        return sc;
    }

    private SeekBar audioSeek;
    private TextView audioTime;
    private boolean audioDragging = false;

    /** 播放页顶部：大字书名 + 当前章节，像普通播放器那样 */
    private void addPlayerHeader(Screen sc) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(14), dp(12), dp(14), dp(8));
        TextView name = new TextView(this);
        name.setText(nb.nbBookName == null || nb.nbBookName.isEmpty() ? "音频播放" : nb.nbBookName);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp() + 7);
        name.setTextColor(palFg());
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView ch = new TextView(this);
        String cn = (aIdx >= 0 && aIdx < nb.nbChapters.size()) ? nb.nbChapters.get(aIdx)[0] : "";
        ch.setText(cn);
        ch.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp());
        ch.setTextColor(palFg());
        ch.setAlpha(0.75f);
        ch.setSingleLine(true);
        ch.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(ch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        head.setContentDescription((nb.nbBookName == null ? "音频播放" : nb.nbBookName) + "，" + cn);
        rowsBox.addView(head);
    }

    /** 进度行：一条可拖的进度条 + 时间文字；读屏可左右调 */
    private View makeSeekRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), dp(0));
        SeekBar sb = new SeekBar(this);
        sb.setMax(1000);
        sb.setFocusable(true);
        sb.setFocusableInTouchMode(false);
        sb.setContentDescription("播放进度，可拖动或用方向键左右调");
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                int dur = audio().duration();
                if (dur > 0) audio().seekTo((int) ((long) dur * p / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { audioDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { audioDragging = false; }
        });
        audioSeek = sb;
        box.addView(sb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        TextView t = new TextView(this);
        t.setText(com.mangjie.reader.media.AudioBookPlayer.fmt(audio().position())
                + " / " + com.mangjie.reader.media.AudioBookPlayer.fmt(audio().duration()));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp() - 1);
        t.setTextColor(palFg());
        t.setAlpha(0.8f);
        t.setGravity(Gravity.CENTER);
        audioTime = t;
        box.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handleAudioKey("tell"); }
        });
        return box;
    }

    /** 播放/暂停那行和语速行随状态变 */
    private void refreshAudioRows() {
        if (!audioPageOn()) return;
        Screen sc = top();
        if (sc == null || sc.items.size() < 6) return;
        updateSearchRow(0, audio().isPlaying() ? "暂停" : "播放");
        updateSearchRow(5, "语速：" + aRate + " 倍");
    }

    /** 进度行每秒刷新，不打断播报 */
    private void refreshAudioProgress(int pos, int dur) {
        if (audioTime != null) {
            audioTime.setText(com.mangjie.reader.media.AudioBookPlayer.fmt(pos)
                    + " / " + com.mangjie.reader.media.AudioBookPlayer.fmt(dur));
        }
        if (audioSeek != null && !audioDragging && dur > 0) {
            audioSeek.setProgress((int) ((long) pos * 1000 / dur));
        }
        if (!audioPageOn()) return;
    }

    /** 只改 items 的文字，不进渲染（进度每秒变，重画会打断焦点） */
    private void screenItemLabel(Screen sc, int i, String label) {
        if (i < 0 || i >= sc.items.size()) return;
        Item old = sc.items.get(i);
        if (old != null && label.equals(old.label)) return;
        sc.items.set(i, new Item(label, old == null ? "" : old.key));
        if (rowsBox == null) return;
        int at = i - rowOffset + rowLead;
        if (at >= 0 && at < rowsBox.getChildCount()) {
            View v = rowsBox.getChildAt(at);
            if (v instanceof TextView) ((TextView) v).setText(label);
        }
    }

    private void audioToggle() {
        if (audio().isActive()) {
            audio().toggle();
            if (audio().isPlaying()) holdReadFocus(); else dropReadFocus();
            speak(audio().isPlaying() ? "继续播放" : "已暂停");
        } else if (aIdx >= 0) {
            audioPlayChapter(aIdx);
        }
    }

    private void audioNext() {
        if (aIdx + 1 >= nb.nbChapters.size()) { speak("已经是最后一章"); return; }
        audioPlayChapter(aIdx + 1);
    }

    private void audioPrev() {
        if (aIdx - 1 < 0) { speak("已经是第一章"); return; }
        audioPlayChapter(aIdx - 1);
    }

    private void audioStop() {
        try { audio().stop(); } catch (Throwable ignored) {}
        dropReadFocus();
    }

    /** 缓存文件：audio/<书hash>/<章号>.audio */
    private File audioCacheFile(int idx) {
        return new File(nb.nbAudioDir(nb.nbBookUrl), "ch" + idx + ".audio");
    }

    private void cacheAudioInBack(final int idx, final String chapterUrl) {
        com.mangjie.reader.tools.Workers.run(new Runnable() {
            @Override public void run() {
                try {
                    final File out = audioCacheFile(idx);
                    if (out.exists() && out.length() > 0) return;
                    String u = nb.nbAudioUrlOf(chapterUrl);
                    if (u.isEmpty()) return;
                    if (downloadAudio(u, out)) devLogLine("音频", "已缓存第" + (idx + 1) + "章 " + out.length() + " 字节");
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 音频是二进制，不能走 requestUrl（那个会解码成文本） */
    private boolean downloadAudio(String url, File out) {
        java.io.InputStream is = null;
        java.io.OutputStream os = null;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            if (c.getResponseCode() != 200) return false;
            File tmp = new File(out.getAbsolutePath() + ".tmp");
            is = c.getInputStream();
            os = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.close();
            os = null;
            if (tmp.length() <= 0) { tmp.delete(); return false; }
            if (out.exists()) out.delete();
            return tmp.renameTo(out);
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
            try { if (os != null) os.close(); } catch (Throwable ignored) {}
        }
    }

    /** 播放页的键 */
    private boolean handleAudioKey(String k) {
        if ("toggle".equals(k)) { audioToggle(); return true; }
        if ("next".equals(k)) { audioNext(); return true; }
        if ("prev".equals(k)) { audioPrev(); return true; }
        if ("r15".equals(k)) { audio().seekBy(-15000); speak("退了 15 秒，现在 " + com.mangjie.reader.media.AudioBookPlayer.fmt(audio().position())); return true; }
        if ("f15".equals(k)) { audio().seekBy(15000); speak("进了 15 秒，现在 " + com.mangjie.reader.media.AudioBookPlayer.fmt(audio().position())); return true; }
        if ("rate".equals(k)) {
            float[] rs = {1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 0.75f};
            int at = 0;
            for (int i = 0; i < rs.length; i++) if (Math.abs(rs[i] - aRate) < 0.01f) { at = i; break; }
            aRate = rs[(at + 1) % rs.length];
            audio().setRate(aRate);
            speak("语速 " + aRate + " 倍");
            return true;
        }
        if ("tell".equals(k)) {
            speak("第 " + (aIdx + 1) + " 章，" + (audio().isPlaying() ? "正在播放" : "已暂停")
                    + "，已播 " + com.mangjie.reader.media.AudioBookPlayer.fmt(audio().position())
                    + "，总长 " + com.mangjie.reader.media.AudioBookPlayer.fmt(audio().duration()));
            return true;
        }
        if ("toc".equals(k)) {
            Screen sc = nb.nbChapterListScreen();
            if (sc != null) { stack.add(sc); render(true); }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 音频焦点：来电/别的应用放声就暂停朗读
    private AudioManager audioMgr;
    private AudioFocusRequest readFocusReq;
    private boolean readFocusHeld = false;
    private boolean focusPausedReading = false;

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int f) {
            // 1.0.68：按 Legado 把两种丢失分开处理。
            // LOSS_TRANSIENT 是「暂时被抢」——来电、读屏插话、提示音；暂停但**不交出焦点**，
            // 等它说回来再接着念。以前不分，一律放弃焦点，焦点回来就再也收不到回调，于是永远接不上
            if (f == AudioManager.AUDIOFOCUS_LOSS) {
                readFocusHeld = false;
                if (audio().isPlaying()) {
                    audio().pause();
                    ui.post(new Runnable() { @Override public void run() {
                        toast("来电或其它声音占用，音频已暂停");
                        devLogLine("音频", "音频焦点丢失，暂停播放");
                    } });
                    return;
                }
                ui.post(new Runnable() { @Override public void run() {
                    if (!autoPlay) return;
                    focusPausedReading = false;      // 彻底丢失不自动恢复，等用户自己再开
                    pauseReading();
                    toast("声音被其他程序占用，朗读已暂停");
                    devLogLine("朗读", "音频焦点被彻底拿走，暂停并交出焦点");
                } });
                return;
            }
            if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                if (audio().isPlaying()) {
                    audio().pause();
                    ui.post(new Runnable() { @Override public void run() {
                        toast("来电或其它声音占用，音频已暂停");
                        devLogLine("音频", "音频焦点暂时丢失，暂停播放");
                    } });
                    return;
                }
                ui.post(new Runnable() { @Override public void run() {
                    if (!autoPlay) return;
                    focusPausedReading = true;
                    pauseReading(true);              // true：保住焦点，等 GAIN 回来
                    toast("来电或读屏插话，朗读暂停，稍后自动接着读");
                    devLogLine("朗读", "音频焦点暂时丢失，暂停但保留焦点");
                } });
                return;
            }
            if (f == AudioManager.AUDIOFOCUS_GAIN) {
                if (focusPausedReading) {
                    focusPausedReading = false;
                    ui.post(new Runnable() { @Override public void run() {
                        Screen sc = top();
                        if (sc == null || !sc.reader) return;
                        autoPlay = true;
                        readingNow = true;
                        toast("继续朗读");
                        devLogLine("朗读", "音频焦点恢复，从第" + (sc.cur + 1) + "句接着读");
                        speakSentence(sc.cur);
                    } });
                }
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK：只是让压低音量，不打断朗读
        }
    };

    /** 朗读时占住音频焦点，只为感知「被来电/音乐打断」 */
    private void holdReadFocus() {
        if (readFocusHeld) return;
        try {
            if (audioMgr == null) audioMgr = (AudioManager) getSystemService(AUDIO_SERVICE);
            int r;
            if (Build.VERSION.SDK_INT >= 26) {
                if (readFocusReq == null) {
                    readFocusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setOnAudioFocusChangeListener(focusListener)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build())
                            .build();
                }
                r = audioMgr.requestAudioFocus(readFocusReq);
            } else {
                r = audioMgr.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
            readFocusHeld = (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        } catch (Throwable ignored) {
            readFocusHeld = false;
        }
    }

    private void dropReadFocus() {
        if (!readFocusHeld || audioMgr == null) { readFocusHeld = false; return; }
        try {
            if (Build.VERSION.SDK_INT >= 26 && readFocusReq != null) audioMgr.abandonAudioFocusRequest(readFocusReq);
            else audioMgr.abandonAudioFocus(focusListener);
        } catch (Throwable ignored) {
        }
        readFocusHeld = false;
    }

    /** 统一暂停朗读：把触摸状态一并复位，保证暂停后手势立刻可用 */
    private boolean readServiceOn = false;

    /** 第一次朗读时把前台服务拉起来；之后不再重复 start */
    private void ensureReadService() {
        if (readServiceOn) {
            ReadService.updateText(this, "正在朗读");
            return;
        }
        // 通知权限就在这时候要：没有它通知栏就没有按钮，但朗读本身不受影响
        askNotificationPermission();
        ReadService.start(this);
        readServiceOn = true;
        devLogLine("朗读", "前台服务已启动（通知栏可控）");
    }

    /** 通知栏按钮点下来的命令 */
    private void handleReadCmd(String cmd) {
        boolean hasReader = false;
        for (int i = stack.size() - 1; i >= 0; i--) if (stack.get(i).reader) { hasReader = true; break; }
        if (!hasReader) {
            toast("当前不在阅读页");
            ReadService.stop(this);
            readServiceOn = false;
            return;
        }
        while (stack.size() > 1 && !top().reader) stack.remove(stack.size() - 1);
        render(true);
        if (ReadService.CMD_TOGGLE.equals(cmd)) {
            toggleReading();
        } else if (ReadService.CMD_PREV.equals(cmd)) {
            readerJump(-1);
        } else if (ReadService.CMD_NEXT.equals(cmd)) {
            readerJump(1);
        } else if (ReadService.CMD_PREV_CH.equals(cmd) || ReadService.CMD_NEXT_CH.equals(cmd)) {
            boolean next = ReadService.CMD_NEXT_CH.equals(cmd);
            int target = com.mangjie.reader.reader.ChapterIndex.neighborChapter(
                    bookSent, top().cur, next);
            if (target < 0) {
                speak(next ? "已经是最后一章" : "已经是第一章");
                return;
            }
            jumpInReader(target);
            speak(com.mangjie.reader.reader.ChapterIndex.posTag(bookSent, target));
        }
        devLogLine("朗读控制", "通知栏命令：" + cmd);
    }

    /** Android 13 起通知要用户授权，不授权通知栏就没有按钮。
     *  1.1.45 改成按需：第一次真正开前台朗读时才问，启动阶段一概不问。 */
    private void askNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 33) return;
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0) return;
            long last = prefs == null ? 0L : prefs.getLong("askNotifyAt", 0L);
            if (System.currentTimeMillis() - last >= ASK_GAP_MS) {
                if (prefs != null) prefs.edit().putLong("askNotifyAt", System.currentTimeMillis()).apply();
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9001);
                devLogLine("朗读", "已请求通知权限（通知栏控制需要）");
            } else {
                speak("没开通知权限，通知栏按钮用不了，音量键还能用");
            }
        } catch (Throwable ignored) {
        }
    }

    private void pauseReading() { pauseReading(false); }

    /** keepFocus=true：只是暂时被打断，焦点还留着，等它回来接着念（来电、读屏插话） */
    private void pauseReading(boolean keepFocus) {
        nb.nbChainStop();
        autoPlay = false;
        updateScreenAwake();
        readingNow = false;
        resumeReadingAfterSpeak = false;
        if (resumeGuard != null) { ui.removeCallbacks(resumeGuard); resumeGuard = null; }
        if (rdTts != null) { try { rdTts.stop(); } catch (Exception ignored) {} }
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
        swipeCooldownUntil = 0;
        ignoringGesture = false;
        movedFar = false;
        cancelTouchSpeak();
        if (!keepFocus) {
            focusPausedReading = false;
            dropReadFocus();
        }
        if (readServiceOn) ReadService.updateText(this, "已暂停 · 点播放继续");
    }

    private void toggleReading() {
        if (autoPlay) {
            pauseReading();
            speak("已暂停朗读");
            toast("已暂停朗读");
        } else {
            autoPlay = true;
            readingNow = true;
            speak("继续朗读");
            toast("继续朗读");
            speakSentence(top().cur);
        }
    }

    /** 浮层菜单的触摸：摸到哪项读哪项，移动跟着走，双击才执行 */
    private boolean floatMenuTouch(MotionEvent ev) {
        int row = rowAt(ev.getY());
        int n = menuScreen.items.size();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                downX = ev.getX();
                longFired = false;
                if (row >= 0 && row < n) {
                    if (menuScreen.cur != row) { menuScreen.cur = row; render(false); }
                    speak(menuScreen.items.get(row).label);
                    tapAnchorRow = row;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (row >= 0 && row < n && menuScreen.cur != row) {
                    menuScreen.cur = row;
                    render(false);
                    speak(menuScreen.items.get(row).label);
                    tapAnchorRow = row;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                long now = SystemClock.uptimeMillis();
                if (now - lastTapTime < 320 && tapAnchorRow >= 0 && tapAnchorRow < n) {
                    lastTapTime = 0;
                    menuScreen.cur = tapAnchorRow;
                    activate();
                } else {
                    lastTapTime = now;
                    tapAnchorRow = menuScreen.cur;
                }
                return true;
        }
        return true;
    }

    /** 菜单／弹窗里的触摸：摸到哪项读哪项、移动就跟随、双击才执行 */
    private boolean menuTouch(MotionEvent ev) {
        if (menuOn && menuScreen != null) return floatMenuTouch(ev);
        String[] labels = menuOn ? menuNames() : dialogBtns;
        int row = rowAt(ev.getY());
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                downX = ev.getX();
                longFired = false;
                if (row >= 0 && row < labels.length) {
                    if (menuOn) menuSel = row; else dialogSel = row;
                    render(false);
                    speak(labels[row]);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (row >= 0 && row < labels.length) {
                    if (menuOn) {
                        if (menuSel != row) { menuSel = row; render(false); speak(labels[row]); }
                    } else {
                        if (dialogSel != row) { dialogSel = row; render(false); speak(labels[row]); }
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                long now = SystemClock.uptimeMillis();
                if (now - lastTapTime < 320) {
                    lastTapTime = 0;      // 双击：执行第一下点中／当时选中的那一项
                    if (menuOn) {
                        if (menuScreen != null && tapAnchorRow >= 0 && tapAnchorRow < menuScreen.items.size()) {
                            menuScreen.cur = tapAnchorRow;
                        }
                        activate();
                    } else {
                        dialogSel = tapAnchorRow;
                        render(false);
                        commitDialog();
                    }
                } else {
                    lastTapTime = now;
                    tapAnchorRow = menuOn ? menuSel : dialogSel;
                }
                return true;
        }
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 读屏是否接管手指必须实时问，不能读缓存：点明「暂停」后触摸探索当场关掉，
        // 但服务仍注册、缓存还写着「读屏开」，照旧交出去就连光标条都拖不动。
        if (touchExploreNow()) {
            // 读屏接管手指时，点击派发归它，但「按住不动」这一下我们自己留着呼出快捷菜单：
            // 原来这里是一句话直接交回系统，正文页在无障碍树里只剩一个整页节点，菜单就没入口了
            a11yLongPressWatch(ev);
            return super.dispatchTouchEvent(ev);
        }
        // 多指手势要排在覆盖层之前：菜单、对话框、输入页以前在这三行之后，双指一根都进不来。
        // 单指按下 feed 返回 false（只做复位和记录），所以 input/menu 里的单指流程一点不受影响。
        if (gestures != null && gestures.feed(ev)) {
            if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                // 第二指来了：单指那边排队的读取和长按一律掐掉，光标不许跳
                cancelTouchSpeak();
                cancelLongPress();
                movedFar = true;
                dwellFocused = false;
                followed = false;
            }
            return true;
        }
        if (inputOn) return inputTouch(ev);
        if (menuOn || dialogOn) return menuTouch(ev);
        if (isReadingBook()) return readingTouch(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                com.mangjie.reader.a11y.TouchExplorer.Hit hitDown =
                        explorer == null ? null : explorer.hit(ev.getX(), ev.getY());
                int hitType = hitDown == null ? com.mangjie.reader.a11y.TouchExplorer.NONE : hitDown.type;
                downControl = hitType == com.mangjie.reader.a11y.TouchExplorer.CONTROL ? hitDown.view : null;
                downRow = hitType == com.mangjie.reader.a11y.TouchExplorer.ROW ? hitDown.row : -1;
                // hit 只在「落点能顺着 View 树爬到 rowsBox 直系子」时判成行。
                // 搜索结果那种行里套两行文字（书名 + 作者 + 站点）的复杂行、或落点正好在行间缝隙，
                // hit 会返回 NONE → downRow = -1 → 下面「按住就挪」整段被跳过，摸上去光标一动不动。
                // 这里按坐标二次兜底，跟抬起、横滑用的是同一套 RowHit。
                if (downRow < 0 && hitType != com.mangjie.reader.a11y.TouchExplorer.CONTROL) {
                    int rFallback = rowAt(ev.getY());
                    Screen scDown = top();
                    if (rFallback >= 0 && scDown != null && rFallback < scDown.items.size()) {
                        downRow = rFallback;
                        devLogLine("按下", "hit 没认出行，按坐标补成第" + rFallback + "行");
                    }
                }
                downY = ev.getY();
                downX = ev.getX();
                downTime = SystemClock.uptimeMillis();
                downIndexOfStart = top().cur;
                devLogLine("按下", "y=" + (int) ev.getY() + "　行=" + downRow + "　cur=" + top().cur
                        + "　hit=" + (hitType == com.mangjie.reader.a11y.TouchExplorer.ROW ? "行"
                        : hitType == com.mangjie.reader.a11y.TouchExplorer.CONTROL ? "控件" : "无")
                        + "　行数=" + top().items.size() + "　容器=" + rowsBox.getChildCount()
                        + "　偏移=" + rowOffset);
                cancelDragSpeak();
                movedFar = false;
                fastFlick = false;
                exploredInGesture = false;
                downIndexAtStart = top().cur;
                if (vTracker == null) vTracker = VelocityTracker.obtain(); else vTracker.clear();
                vTracker.addMovement(ev);
                longFired = false;
                lastSpokenRow = -1;
                draggingSliderKey = null;
                twoFingerScrolled = false;
                lastMoveY = ev.getY();
                maxPointers = ev.getPointerCount();
                ignoringGesture = false;   // 不再吞手势：之前 500ms 冷却会把连滑的下一手势整个丢掉
                dirLock = 0;
                reversedGesture = null;
                fastSwipe = false;
                lastMoveTime = SystemClock.uptimeMillis();
                if (downRow >= 0 && rowsBox.getChildAt(downRow) instanceof SliderRow) {
                    SliderRow sr = (SliderRow) rowsBox.getChildAt(downRow);
                    String key = top().items.get(downRow).key;
                    draggingSliderKey = key;
                    draggingSlider = sr;
                    if (top().cur != downRow) { top().cur = downRow; highlightSelection(); }
                    sliderFromX(sr, ev.getX());
                    return true;
                }
                downIndexOfStart = top().cur;   // 滑动手势始终以当前焦点为基准，按下不拽焦点
                downIndexAtStart = downIndexOfStart;
                // 摸到一行先等 0.4 秒：这 0.4 秒里如果手指动了，就当滑动处理，不读这一行
                cancelTouchSpeak();
                // 手指按下 100ms 手指没动＝在读这一行（读屏也是按下就读）。
                // 快速滑动在 100ms 内就会动，这个任务会被取消，不会多读一行。
                dwellFocused = false;
                followed = false;
                if (!dialogOn && !menuOn) {
                    final int rowForDwell = downRow;
                    final float dwellY = ev.getY();
                    touchSpeakRunnable = new Runnable() {
                        @Override public void run() {
                            touchSpeakRunnable = null;
                            if (movedFar) return;
                            // 先按行算：搜索结果那种一行套几段文字的复杂行，按 View 树可能只认成「控件」，
                            // 那样按住就只念不挪（观感就是「光标不跟手」）。行算得到就优先按行处理。
                            Screen cur = curScreen();
                            int rr = rowForDwell;
                            if (rr < 0) rr = rowAt(dwellY);
                            if (cur == null || rr < 0 || rr >= cur.items.size()) {
                                if (downControl != null) {
                                    speak(explorer.describe(downControl));
                                    dwellFocused = true;
                                } else {
                                    devLogLine("探索", "按住没算到行　y=" + (int) dwellY + "　hit=" + rowForDwell);
                                }
                                return;
                            }
                            // 手指按着没动：报这一行是什么，光标也挪过来——手指在哪，光标就在哪
                            exploreFocusTo(rr);
                            dwellFocused = true;
                        }
                    };
                    ui.postDelayed(touchSpeakRunnable, 150);   // 双指窗口 200ms，留出余量：第二指一上来就把这句掐掉
                }
                // 按住不动 600ms → 相当于长按，弹功能菜单
                if ((top().fileMode || top().title.equals("书签") || isShelfPage()) && !dialogOn && !menuOn) {
                    final int rowForLong = downRow;
                    longPressRunnable = new Runnable() {
                        @Override public void run() {
                            if (movedFar) return;
                            longFired = true;
                            if (top().title.equals("书签")) {
                                if (rowForLong < 0 || rowForLong >= top().items.size()) return;
                                String key = top().items.get(rowForLong).key;
                                if (key == null || !key.startsWith("bmj:")) return;
                                pendingBmIdx = Integer.parseInt(key.substring(4));
                                dialogMsg = "要删除「" + top().items.get(rowForLong).label + "」这个书签吗？";
                                dialogBtns = new String[]{"删除", "取消"};
                                dialogSel = 1;
                                dialogOn = true;
                                renderDialog();
                                speak(dialogMsg);
                            } else if (isShelfPage()) {
                                if (rowForLong < 0 || rowForLong >= top().items.size()) return;
                                String key = top().items.get(rowForLong).key;
                                if (key != null && key.startsWith("book:")) {
                                    pushScreen(shelfBookMenuScreen(key.substring(5)));
                                } else if (key != null && (key.startsWith("nb:openfav:") || key.startsWith("nb:openrec:"))) {
                                    openNetDetailFromKey(key);      // 长按＝书籍详情（Legado 的做法）
                                }
                            } else {
                                openFileMenu();
                            }
                        }
                    };
                    ui.postDelayed(longPressRunnable, 600);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (draggingSlider != null) { sliderFromX(draggingSlider, ev.getX()); return true; }
                long nowT = SystemClock.uptimeMillis();
                float dtMs = nowT - lastMoveTime;
                float speed = 0f;
                if (dtMs > 0) {
                    speed = Math.abs(ev.getY() - lastMoveY) / dtMs * 1000f;
                    if (speed > 900f) fastSwipe = true;
                }
                lastMoveTime = nowT;
                lastMoveY = ev.getY();
                if (Math.abs(ev.getY() - downY) > dp(14) || Math.abs(ev.getX() - downX) > dp(14)) {
                    if (!movedFar) cancelTouchSpeak();
                    movedFar = true;
                    cancelLongPress();
                }
                // 慢拖但手指停住了（>=150ms 没再移动）＝探索触摸：聚焦到停住的那一行并朗读；
                // 快滑不会停，所以不会被这里抢走，抬手仍然只走一格。
                // 手指划过哪一行就读哪一行（读屏开着的效果）：
                // 手势开始 150ms 之后开始跟随，快速滑动的头 150ms 内跨的行不读，
                // 抬手时若判成快速滑动，只从起手那一行走一格。
                if (!longFired && reversedGesture == null && !twoFingerScrolled
                        && SystemClock.uptimeMillis() - downTime > 150) {
                    float mvY = ev.getY() - downY, mvX = ev.getX() - downX;
                    boolean movedEnough = Math.abs(mvY) > dp(6) || Math.abs(mvX) > dp(6);
                    if (movedEnough || followed) {          // 有明显活动才跟手；跟上了就一直跟到抬手
                        // 只按纵坐标算：搜索结果二十多行，行里套着书名/作者/站点几段文字，
                        // 按 View 树命中会被内层文字和行间缝隙带偏一行，摸 A 念 B。
                        int rowNow = rowAt(ev.getY());
                        Screen scF = top();
                        if (rowNow >= 0 && rowNow < scF.items.size() && rowNow != scF.cur) {
                            exploreFocusTo(rowNow);   // 划过哪行就挪到哪行、念哪行（只念一次，不推进节拍）
                            lastTapTime = 0;
                        }
                        followed = true;
                    }
                }

                // 一次手势只走一项：滑动时不改焦点，抬手时按方向走一格
                // 单指「先左再右」「先右再左」：记首段方向，再看到反向就算折返手势
                if (ev.getPointerCount() == 1 && movedFar) {
                    float dxNow = ev.getX() - downX;
                    float dyNow = ev.getY() - downY;
                    dirLock = GestureJudge.dirLock(dirLock, dxNow, dyNow, dp(12));
                    String revG = GestureJudge.reversed(dirLock, dxNow, dp(18));
                    if (revG != null) reversedGesture = revG;
                }
                return true;
            case MotionEvent.ACTION_UP: {
                if (draggingSlider != null) {
                    commitSlider(draggingSlider, draggingSliderKey);
                    draggingSlider = null; draggingSliderKey = null;
                    return true;
                }
                cancelLongPress();
                cancelDragSpeak();
                cancelFollow();
                if (vTracker != null) { vTracker.recycle(); vTracker = null; }
                float dy = ev.getY() - downY;
                long dur = SystemClock.uptimeMillis() - downTime;
                Screen scUp = top();
                int upRow = rowAt(ev.getX(), ev.getY());

                if (twoFingerScrolled) { twoFingerScrolled = false; return true; }

                // 先左再右＝打开功能菜单；先右再左＝返回上一层
                if (reversedGesture != null) {
                    String g = reversedGesture;
                    reversedGesture = null;
                    if ("LR".equals(g)) {
                        openPageMenu();
                    } else {
                        goBack();
                    }
                    return true;
                }

                // 双指点按＝模拟点击（激活当前项）
                if (maxPointers >= 2 && !movedFar && !longFired) {
                    activate();
                    return true;
                }

                // 单指横向滑动＝下一项 / 上一项（对齐读屏的右滑下一焦点、左滑上一焦点）
                float ddx = ev.getX() - downX;
                float ddy = ev.getY() - downY;
                float durH = SystemClock.uptimeMillis() - downTime;
                if (!twoFingerScrolled && !longFired
                        && GestureJudge.horizontalFlick(ddx, ddy, (long) durH, 300, dp(40))) {
                    top().cur = downIndexAtStart;      // 先把焦点退回落手行，免得拖动中途换了行
                    if (adjustFocusedRow(ddx > 0 ? 5 : -5)) { lastTapTime = 0; return true; }
                    highlightSelection();
                    Screen scH = top();
                    int target = ddx > 0 ? scH.cur + 1 : scH.cur - 1;
                    boolean inRange = target >= 0 && target < scH.items.size();
                    devLogLine("横滑", (ddx > 0 ? "右滑→下一项" : "左滑→上一项")
                            + "　cur=" + scH.cur + (inRange ? (" → " + target) : "，已在" + (ddx > 0 ? "末端" : "首端") + "，不动"));
                    if (inRange) {
                        moveTo(target);
                    } else {
                        speak(ddx > 0 ? "已经是最后一项" : "已经是第一项");
                    }
                    if (scroll != null) scroll.post(new Runnable() { @Override public void run() {
                        // 横滑不涉及纵向滚动，保持不变
                    } });
                    lastTapTime = 0;
                    return true;
                }

                // 单指上下滑＝下一项 / 上一项，一次手势只走一项（与读屏一致）
                long durV = SystemClock.uptimeMillis() - downTime;
                boolean verticalFlick = !followed && GestureJudge.verticalFlick(movedFar, longFired,
                        twoFingerScrolled, ddy, ddx, durV, 300, dp(40));
                if (verticalFlick) {
                    top().cur = downIndexAtStart;      // 快滑退回起手行，只走一格
                    highlightSelection();
                    Screen scV = top();
                    int targetV = ddy > 0 ? scV.cur + 1 : scV.cur - 1;
                    boolean vRange = targetV >= 0 && targetV < scV.items.size();
                    devLogLine("竖滑", (ddy > 0 ? "下滑→下一项" : "上滑→上一项")
                            + "　位移=" + (int) ddy + "　cur=" + scV.cur
                            + (vRange ? (" → " + targetV) : "，已在" + (ddy > 0 ? "末端" : "首端") + "，不动"));
                    if (vRange) moveTo(targetV);
                    else speak(ddy > 0 ? "已经是最后一项" : "已经是第一项");
                    lastTapTime = 0;
                    return true;
                }

                devLogLine("抬起", "y=" + (int) ev.getY() + "　行=" + upRow + "　cur=" + top().cur
                        + "　位移=" + (int) (ev.getY() - downY) + "　耗时=" + (SystemClock.uptimeMillis() - downTime)
                        + "　fast=" + movedFar + "　long=" + longFired);
                // 判断「点」不看 movedFar：手指按下去有个几像素的抖动也会把 movedFar 置真，
                // 日志里 01:54:00.415 那次「位移 25、耗时 461」，就是被抖动判成拖动，点了没反应。
                float dispY = Math.abs(ev.getY() - downY);
                float dispX = Math.abs(ev.getX() - downX);
                boolean tapLike = GestureJudge.tapLike(longFired, twoFingerScrolled,
                        dispY, dispX, SystemClock.uptimeMillis() - downTime, dp(20), 1200);
                if (!tapLike) downControl = null;
                if (tapLike && downControl != null) {
                    View c = downControl;
                    downControl = null;
                    cancelTouchSpeak();
                    long nowC = SystemClock.uptimeMillis();
                    boolean second = lastControlTap != null
                            && (nowC - lastControlTapTime) < 800 && c == lastControlTap;
                    if (second) {                     // 第二下：才真正执行
                        lastControlTap = null;
                        lastControlTapTime = 0;
                        devLogLine("控件", "双击执行「" + explorer.describe(c) + "」");
                        c.performClick();
                    } else {                          // 第一下：只念，不动
                        lastControlTap = c;
                        lastControlTapTime = nowC;
                        devLogLine("控件", "摸到「" + explorer.describe(c) + "」，第二下才执行");
                        speak(explorer.describe(c));
                    }
                    return true;
                }
                // 第二下落在空白处也算「双击」：执行上次摸到的那个控件（与 TalkBack 一致）
                if (tapLike && lastControlTap != null
                        && (SystemClock.uptimeMillis() - lastControlTapTime) < 800) {
                    View c2 = lastControlTap;
                    lastControlTap = null;
                    lastControlTapTime = 0;
                    cancelTouchSpeak();
                    devLogLine("控件", "双击（第二下在空白）执行「" + explorer.describe(c2) + "」");
                    c2.performClick();
                    return true;
                }
                if (tapLike) {
                    boolean pending = touchSpeakRunnable != null;   // 还没到 100ms，说明是快速轻点
                    cancelTouchSpeak();
                    Screen scT = curScreen();
                    if (upRow < 0) {
                        // 空白处：单击不朗读、不动焦点；但双击要执行当前项——
                        // 读屏开着的时候就是这样（双击落点在哪它都执行当前焦点）。
                        long nowB = SystemClock.uptimeMillis();
                        if (lastTapTime > 0 && (nowB - lastTapTime) < 800 && tapAnchorRow >= 0) {
                            devLogLine("双击", "空白处　间隔=" + (nowB - lastTapTime) + "ms　执行当前第" + scT.cur + "行");
                            lastTapTime = 0;
                            tapAnchorRow = -1;
                            highlightSelection();
                            activate();
                        } else {
                            lastTapTime = nowB;
                            tapAnchorRow = scT.cur;
                        }
                        return true;
                    }
                    if (upRow >= 0 && upRow < scT.items.size()) {
                        if (scT.reader && !autoPlay) {
                            // 暂停朗读时按句摸：只念这一句，不动朗读位置；双击才跳过去
                            long nowP = SystemClock.uptimeMillis();
                            boolean dblP = lastTapTime > 0 && (nowP - lastTapTime) < 800
                                    && tapAnchorRow >= 0 && tapAnchorRow < scT.items.size();
                            if (dblP) {
                                int rowToRun = tapAnchorRow;
                                lastTapTime = 0;
                                tapAnchorRow = -1;
                                devLogLine("双击", "暂停中跳句　执行第" + rowToRun + "行");
                                scT.cur = rowToRun;
                                highlightSelection();
                                activate();
                                return true;
                            }
                            speakRow(scT.items.get(upRow).label);
                            lastTapTime = nowP;
                            tapAnchorRow = upRow;
                            return true;
                        }
                        // 读屏的双击是「第一下点中哪一项，第二下落在任何地方都执行那一项」，
                        // 所以第二下不强求同一行，只要求 800ms 内。
                        long now0 = SystemClock.uptimeMillis();
                        boolean dbl = lastTapTime > 0 && (now0 - lastTapTime) < 800
                                && tapAnchorRow >= 0 && tapAnchorRow < scT.items.size();
                        if (dbl) {
                            devLogLine("双击", "执行第" + tapAnchorRow + "行　第二下第" + upRow + "行　间隔=" + (now0 - lastTapTime) + "ms");
                            int rowToRun = tapAnchorRow;
                            lastTapTime = 0;
                            tapAnchorRow = -1;
                            scT.cur = rowToRun;
                            highlightSelection();
                            activate();
                            return true;
                        }
                        if (scT.cur != upRow) focusToRow(upRow);
                        if (pending || !dwellFocused) speakRow(scT.items.get(upRow).label);   // 点哪读哪（450ms 内刚念过会自动跳过）
                        lastTapTime = now0;                          // 单击：选中并朗读，等第二下
                        tapAnchorRow = upRow;
                    }
                    return true;
                }

                // 点明手势：上滑＝下一个焦点，下滑＝上一个焦点；右滑＝下一个，左滑＝上一个
                if (movedFar) {
                    // 只要这次是拖动过的抬手，都给 400ms 判定窗，防止手快速抬落把焦点拽回去
                    swipeCooldownUntil = SystemClock.uptimeMillis() + 120;
                    return true;       // 焦点已跟随手指走过，抬手不再切
                }
                if (false) {
                    float dxTotal = ev.getX() - downX;
                    int dir;
                    if (Math.abs(dy) >= Math.abs(dxTotal)) dir = dy > 0 ? 1 : -1;   // 下滑＝下一个
                    else dir = dxTotal > 0 ? 1 : -1;                                 // 右滑＝下一个
                    int from = scUp.cur;    // 起点永远是当前焦点，不从手指落点算，避免焦点往回跑
                    int target = Math.min(scUp.items.size() - 1, Math.max(0, from + dir));
                    if (target != scUp.cur) {
                        swipeCooldownUntil = SystemClock.uptimeMillis() + 120;
                        scUp.cur = target;
                        highlightSelection();
                        ensureRowVisible(target);
                        speakRow(scUp.items.get(target).label);
                    } else {
                        // 已经在头／尾：读一下提示，避免以为没反应
                        speakRow(scUp.items.get(target).label);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                draggingSlider = null; draggingSliderKey = null;
                cancelLongPress();
                return true;
            default:
                return true;
        }
    }

    // ---------------------------------------------------------------- 按键与激活

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN
                && (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            e.startTracking();     // 不登记就收不到 onKeyLongPress
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();

        if (dialogOn) {
            int dir = com.mangjie.reader.input.Keys.dir(code);
            if (dir != 0) {
                int n = dialogSel + dir;
                if (n >= 0 && n < dialogBtns.length) { dialogSel = n; render(false); speak(dialogBtns[n]); }
                return true;
            }
            if (com.mangjie.reader.input.Keys.isEnter(code)) { commitDialog(); return true; }
            if (code == KeyEvent.KEYCODE_BACK) { closeDialog(); speak("已取消"); return true; }
            return true;
        }

        if (menuOn) {
            int dir = com.mangjie.reader.input.Keys.dir(code);
            if (dir != 0 && menuScreen != null) {
                int n = menuScreen.cur + dir;
                if (n >= 0 && n < menuScreen.items.size()) { menuScreen.cur = n; render(false); speak(menuScreen.items.get(n).label); }
                return true;
            }
            if (com.mangjie.reader.input.Keys.isEnter(code)) { activate(); return true; }
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                closeFileMenu();
                speak("已返回");
                return true;
            }
            return true;
        }

        if (inputOn) {
            if (code == KeyEvent.KEYCODE_BACK) { closeInput(); speak("已取消"); return true; }
            if (com.mangjie.reader.input.Keys.isEnter(code)) {
                // 光标在输入框里时交给输入框，否则当作「确定」
                View focused = getCurrentFocus();
                if (focused instanceof EditText) return super.dispatchKeyEvent(e);
                onInputButton("确定");
                return true;
            }
            return super.dispatchKeyEvent(e);   // 上下键、字符键都交回系统处理
        }


        // 播放页或音频正在播：耳机键全管（播放暂停、上一章、下一章、快进快退），音量键交回系统
        if (!dialogOn && !menuOn && !inputOn && (audioPageOn() || audio().isPlaying())) {
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK
                    || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                audioToggle();
                return true;
            }
            if (code == KeyEvent.KEYCODE_MEDIA_NEXT) { audioNext(); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { audioPrev(); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { audio().seekBy(15000); speak("进了 15 秒"); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_REWIND) { audio().seekBy(-15000); speak("退了 15 秒"); return true; }
        }

        if (!dialogOn && !menuOn && !inputOn && top().reader) {
            // 音量键一律不拦、原样交回系统：TalkBack 和点明读屏都拿音量键当快捷键，
            // 我们截下来就等于把它们的手势吃掉。翻句改用五向键，或读屏操作菜单里的上一句/下一句。
            if (code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                toggleRead("耳机键");
                return true;
            }
            if (code == KeyEvent.KEYCODE_MEDIA_NEXT) { readerJump(1); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { readerJump(-1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                readerJump(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                readerJump(1);
                return true;
            }
            if (com.mangjie.reader.input.Keys.isEnter(code)) {
                toggleRead("回车键");
                return true;
            }
        }
        // 键盘机同一套规则：可调行上左右方向键调值（右加左减），其余行照旧
        if (!dialogOn && !menuOn) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { if (adjustFocusedRow(-5)) return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (adjustFocusedRow(5)) return true; }
        }
        if (!dialogOn && !menuOn) {
            int i = top().cur;
            String k = top().items.get(i).key;
            if ("sp:rate".equals(k) || "sp:pitch".equals(k)) {
                if (com.mangjie.reader.input.Keys.isEnter(code)) {
                    pendingPctKey = k;
                    inputTitle = "sp:rate".equals(k) ? "输入语速百分比" : "输入语调百分比";
                    inputAction = "pct";
                    openInput("请输入 0 到 100 之间的数字");
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && adjustFocusedRow(-5)) return true;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && adjustFocusedRow(5)) return true;
                if (code == KeyEvent.KEYCODE_MINUS) { nudge(k, -5); return true; }
                if (code == KeyEvent.KEYCODE_PLUS || code == KeyEvent.KEYCODE_EQUALS
                        || code == KeyEvent.KEYCODE_STAR) { nudge(k, 5); return true; }
            }
        }

        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_FUNCTION) {
            openPageMenu();
            return true;
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: moveTo(top().cur - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: moveTo(top().cur + 1); return true;
            case KeyEvent.KEYCODE_PAGE_UP: keyboardPage(-1); return true;
            case KeyEvent.KEYCODE_PAGE_DOWN: keyboardPage(1); return true;
            case KeyEvent.KEYCODE_MOVE_HOME:
                if (curScreen().cur == 0) { speak("已经是第一项"); return true; }
                moveTo(0); return true;
            case KeyEvent.KEYCODE_MOVE_END:
                if (curScreen().cur >= curScreen().items.size() - 1) { speak("已经是最后一项"); return true; }
                moveTo(curScreen().items.size() - 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: activate(); return true;
            case KeyEvent.KEYCODE_BACK: goBack(); return true;
            default:
                if (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9) {
                    int n = code - KeyEvent.KEYCODE_1;
                    if (n < top().items.size()) { moveTo(n); return true; }
                }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && !dialogOn && !menuOn) {
            if (isShelfPage()) { openShelfMenu(); return true; }
            openFileMenu();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    /** 只在焦点要出屏幕时才滚，并且幅度刚好把它带进可视区，不做整屏跳 */
    private void ensureRowVisible(int idx) { focus().ensure(idx); }

    /** 从某个焦点出发、按手指位移折算该走到第几行：手指落下在哪行不影响结果 */
    private int rowByDrag(int from, float dy) {
        if (rowsBox == null) return from;
        float dist = Math.abs(dy);
        int idx = from;
        int step = dy > 0 ? 1 : -1;
        for (;;) {
            int nxt = idx + step;
            if (nxt < 0 || nxt >= top().items.size()) return idx;
            View v = rowsBox.getChildAt(idx - rowOffset);
            if (v == null) return idx;
            float h = v.getHeight();
            if (h <= 0) h = dp(ROW_DP);
            if (dist < h) return idx;
            dist -= h;
            idx = nxt;
        }
    }

    /** 光标移动已搬到 shell/Cursor，主类只留转发与接线（外壳第四刀） */
    private com.mangjie.reader.shell.Cursor cursorMove;
    private com.mangjie.reader.shell.Cursor caret() {
        if (cursorMove == null) cursorMove = new com.mangjie.reader.shell.Cursor(new com.mangjie.reader.shell.Cursor.Host() {
            @Override public Screen curScreen() { return MainActivity.this.curScreen(); }
            @Override public void highlightSelection() { MainActivity.this.highlightSelection(); }
            @Override public void ensureRowVisible(int idx) { MainActivity.this.ensureRowVisible(idx); }
            @Override public void log(String tag, String msg) { devLogLine(tag, msg); }
            @Override public void tick() { MainActivity.this.tick(); }
            @Override public void speakRow(String label) { MainActivity.this.speakRow(label); }
            @Override public void speak(String m) { MainActivity.this.speak(m); }
            @Override public boolean autoPlaying() { return autoPlay; }
            @Override public void stopReading() { autoPlay = false; if (tts != null) tts.stop(); }
            @Override public int menuRowStep() { return menuOn ? keyboardPageStep() : 0; }
            @Override public int bodyRowStep() { return keyboardPageStep(); }
        });
        return cursorMove;
    }

    /** 键盘翻一屏：按一屏能放几行算，到边界出声 */
    private int keyboardPageStep() {
        ScrollView sv = (menuOn && menuScroll != null) ? menuScroll : scroll;
        LinearLayout box = (menuOn && menuScroll != null) ? menuBox : rowsBox;
        if (menuOn && box != null && box.getChildCount() > 0) {
            int h0 = box.getChildAt(0).getHeight();
            if (h0 > 0 && sv != null && sv.getHeight() > 0) return Math.max(1, sv.getHeight() / h0);
        }
        if (scroll != null && rowsBox != null && rowsBox.getChildCount() > 0) {
            int h = rowsBox.getChildAt(0).getHeight();
            if (h > 0 && scroll.getHeight() > 0) return Math.max(1, scroll.getHeight() / h);
        }
        return 8;
    }

    private void moveTo(int idx) { caret().moveTo(idx); }

    private void keyboardPage(int dir) { caret().page(dir); }

    private void activate() {
        tickOnce();
        Screen sc = curScreen();
        if (sc.items == null || sc.items.isEmpty()) { speak(sc.title); return; }
        Item it = sc.items.get(sc.cur);
        String k = it.key == null ? "" : it.key;
        devLogLine("操作", "执行「" + it.label + "」　键=" + k + "　页面=" + sc.title);

        if (k.isEmpty()) { speak(it.label); return; }
        // 纯信息行统一占位机制：占位键 none 念一遍就结束，不当未实现
        if ("none".equals(k)) { speak(it.label); return; }
        if (actPre(k, sc, it)) return;
        if (actNav(k, sc, it)) return;
        if (actShelf(k, sc, it)) return;
        if (actSource(k, sc, it)) return;
        if (actReader(k, sc, it)) return;
        if (actSettings(k, sc, it)) return;
        if (actFile(k, sc, it)) return;
        toast("本版本未实现：" + it.label);
        speak("本版本未实现");
    }

    /** 行激活的前两段分派已搬到 shell/RowAct，主类只留转发与接线（外壳第三刀） */
    private com.mangjie.reader.shell.RowAct rowActions;
    private com.mangjie.reader.shell.RowAct act() {
        if (rowActions == null) rowActions = new com.mangjie.reader.shell.RowAct(new com.mangjie.reader.shell.RowAct.Host() {
            @Override public boolean menuOn() { return menuOn; }
            @Override public void adjustColorRow(String k, int sign) { MainActivity.this.adjustColorRow(k, sign); }
            @Override public void adjustFocusedRow(int step) { MainActivity.this.adjustFocusedRow(step); }
            @Override public void handleThemeKey(String k) { MainActivity.this.handleThemeKey(k); }
            @Override public void doFileMenuAction(String rest) { MainActivity.this.doFileMenuAction(rest); }
            @Override public void doReaderMenuAction(String rest) { MainActivity.this.doReaderMenuAction(rest); }
            @Override public void hideMenuOverlay() { MainActivity.this.hideMenuOverlay(); }
            @Override public void closeFloatMenu() { MainActivity.this.closeFloatMenu(); }
            @Override public void speak(String m) { MainActivity.this.speak(m); }
            @Override public void goBack() { MainActivity.this.goBack(); }
            @Override public void showScreen(Screen sc) { stack.add(sc); render(true); }
            @Override public void push(Screen sc) { pushScreen(sc); }
            @Override public Screen aboutScreen() { return MainActivity.this.aboutScreen(); }
            @Override public void tapVersion() { tapVersionItem(); }
            @Override public boolean devMode() { return devMode; }
            @Override public void exitApp() { MainActivity.this.exitApp(); }
            @Override public Screen settingsScreen() { return MainActivity.this.settingsScreen(); }
            @Override public Screen recordsScreen() { return MainActivity.this.recordsScreen(); }
            @Override public Screen bookmarkOverviewScreen() { return MainActivity.this.bookmarkOverviewScreen(); }
            @Override public Screen netBookScreen() { return nb.netBookScreen(); }
            @Override public boolean localMode() { return localMode; }
            @Override public boolean floatMenuOpen() { return menuOn || menuScreen != null; }
            @Override public void dropNonReaderTop() {
                if (!stack.isEmpty() && top() != null && !top().reader) stack.remove(stack.size() - 1);
            }
            @Override public void openLocalChapter(int n) { MainActivity.this.openLocalChapter(n); }
        });
        return rowActions;
    }

    private boolean actPre(String k, Screen sc, Item it) { return act().pre(k, sc, it); }

    private boolean actNav(String k, Screen sc, Item it) { return act().nav(k, sc, it); }

    private boolean actShelf(String k, Screen sc, Item it) {
        if ("shelf:local".equals(k)) { pushScreen(shelfScreen(false)); return true; }
        if ("shelf:net".equals(k)) { pushScreen(shelfScreen(true)); return true; }
        if ("shelf".equals(k) || "favrecent".equals(k)) { pushScreen(shelfScreen(shelfNetTop())); return true; }
        if ("shelf:addurl".equals(k)) {
            inputAction = "shelfurl";
            inputTitle = "添加网址";
            openInput("粘贴小说详情页的网址，例如 http://www.92xs.info/book/862.html");
            return true;
        }
        if ("shelf:search".equals(k)) {
            inputAction = "shelfsearch";
            inputTitle = "搜索书架";
            openInput("输入书名里的字");
            return true;
        }
        if ("shelf:batch".equals(k)) { pushScreen(shelfBatchScreen()); return true; }
        if (k.startsWith("shelf:batchsel:")) {
            String inner = k.substring("shelf:batchsel:".length());
            if (batchSel.contains(inner)) batchSel.remove(inner); else batchSel.add(inner);
            Screen cur2 = top();
            int at = cur2.cur;
            stack.set(stack.size() - 1, shelfBatchScreen());
            int size = top().items.size();
            top().cur = Math.max(0, Math.min(at, size - 1));
            render(true);
            speakRow(top().items.get(top().cur).label);
            return true;
        }
        if ("shelf:batchunfav".equals(k)) {
            if (batchSel.isEmpty()) { speak("还没选书"); return true; }
            int n = 0;
            List<String> sel = new ArrayList<>(batchSel);
            for (String key : sel) {
                if (key.startsWith("book:")) {
                    String path = key.substring(5);
                    boolean rm = false;
                    if (readList("favList").contains(path)) { removeFromList("favList", path); rm = true; }
                    if (readList("recentList").contains(path)) { removeFromList("recentList", path); rm = true; }
                    if (rm) n++;
                } else if (key.startsWith("nb:openfav:")) {
                    n += dropNbFav(key.substring(10).split("\u0001")[0]);
                }
            }
            batchSel.clear();
            stack.set(stack.size() - 1, shelfBatchScreen());
            render(true);
            speak("已移出 " + n + " 本");
            return true;
        }
        if ("shelf:batchclear".equals(k)) {
            batchSel.clear();
            stack.set(stack.size() - 1, shelfBatchScreen());
            render(true);
            speak("已清空选择");
            return true;
        }
        if ("shelf:export".equals(k)) { shelfExport(); return true; }
        if ("shelf:import".equals(k)) { shelfImport(shelfNetTop()); return true; }
        if ("shelf:logexport".equals(k)) { exportLog(); return true; }
        if ("shelf:log".equals(k)) {
            if (stack.get(stack.size() - 1).key != null && "shelfLog".equals(stack.get(stack.size() - 1).key)) {
                stack.set(stack.size() - 1, shelfLogScreen());
                render(true);
            } else {
                pushScreen(shelfLogScreen());
            }
            return true;
        }
        if ("shelf:manage".equals(k)) { stack.add(shelfManageScreen(0)); render(true); return true; }

        if ("shelf:sort".equals(k)) {
            boolean netShelf = shelfNetTop();
            int next = com.mangjie.reader.shelves.ShelfList.nextSort(shelfSortMode(netShelf));
            prefs.edit().putInt(netShelf ? "shelfSortNet" : "shelfSort", next).apply();
            stack.set(stack.size() - 1, shelfScreen(netShelf));
            render(true);
            speak("排序改为 " + com.mangjie.reader.shelves.ShelfList.sortName(next));
            return true;
        }
        if (k.startsWith("bks:findnet:")) {
            String path = k.substring(12);
            String title = localBookTitle(path);
            if (title.isEmpty()) { speak("书名没认出来"); return true; }
            speak("在网上找 " + title);
            goBack();
            nb.nbSearch(title);
            return true;
        }
        if (k.startsWith("bks:")) { handleShelfBook(k.substring(4)); return true; }
        if (k.startsWith("bkn:top:")) { nbTopFirst(k.substring(8)); return true; }
        if (k.startsWith("bkn:unfav:")) {
            String url = k.substring(10);
            int n = dropNb("nbFav", url) + dropNb("nbRecentList", url);
            if (n > 0) speak("已从书架移除"); else speak("这本不在书架上");
            goBack();
            for (int i = stack.size() - 1; i >= 0; i--) {
                Screen sc2 = stack.get(i);
                if (sc2 != null && "shelf".equals(sc2.key)) stack.set(i, shelfScreen(sc2.shelfNet));
            }
            render(true);
            return true;
        }
        if (k.startsWith("set:")) { handleSetGroup(k.substring(4)); return true; }
        return false;
    }

    private boolean actSource(String k, Screen sc, Item it) {
        if ("src:threads".equals(k)) {
            inputAction = "threads";
            inputTitle = "搜索线程数";
            openInput("输入 1 到 16 的整数，超过 9 只按 9 跑");
            return true;
        }
        if (k.equals("nb:cacheallclear")) {
            com.mangjie.reader.net.ChapterCache.clearAll(DATA_DIR);
            speak("章节缓存已清空");
            stack.remove(stack.size() - 1);
            stack.add(cacheManageScreen());
            render(true);
            return true;
        }
        if (k.startsWith("nb:")) { handleNetBook(k.substring(3)); return true; }
        if (k.startsWith("sub:")) {
            String sk = k.substring(4);
            if ("list".equals(sk)) { stack.remove(stack.size() - 1); stack.add(ops.subSourceScreen()); render(true); return true; }
            if ("reload".equals(sk)) {
                ops.loadSubSources();
                stack.remove(stack.size() - 1);
                stack.add(ops.subSourceScreen());
                render(true);
                speak("已载入 " + ops.subSources.size() + " 个订阅源");
                return true;
            }
            if ("impfile".equals(sk)) { stack.add(ops.subPickScreen(internalRoot())); render(true); return true; }
            if ("impurl".equals(sk)) { inputAction = "subimpurl"; inputTitle = "订阅源网址"; openInput("输入订阅源 JSON 的下载地址"); return true; }
            if ("export".equals(sk)) { ops.subExport(); return true; }
            if (sk.startsWith("detail:")) {
                int i;
                try { i = Integer.parseInt(sk.substring(7)); } catch (Exception e) { return true; }
                ops.loadSubSources();
                stack.add(ops.subDetailScreen(i));
                render(true);
                return true;
            }
            if (sk.startsWith("toggle:")) {
                int i;
                try { i = Integer.parseInt(sk.substring(7)); } catch (Exception e) { return true; }
                ops.loadSubSources();
                if (i < 0 || i >= ops.subSources.size()) return true;
                String nm = ops.subName(ops.subSources.get(i));
                boolean on = !ops.subEnabled(ops.subSources.get(i));
                prefs.edit().putBoolean("subOn_" + nm, on).apply();
                refreshTop("订阅源 " + nm + " 已" + (on ? "启用" : "关闭"));
                return true;
            }
            if (sk.startsWith("open:")) {
                int i;
                try { i = Integer.parseInt(sk.substring(5)); } catch (Exception e) { return true; }
                hideMenuOverlay();
                ops.subOpenArticles(i);
                return true;
            }
            if ("allon".equals(sk) || "alloff".equals(sk)) {
                boolean on = "allon".equals(sk);
                ops.loadSubSources();
                for (int i = 0; i < ops.subSources.size(); i++) {
                    String nm = ops.subName(ops.subSources.get(i));
                    prefs.edit().putBoolean("subOn_" + nm, on).apply();
                }
                prefs.edit().apply();
                refreshTop("订阅源已全部" + (on ? "启用" : "关闭"));
                return true;
            }
            if (sk.startsWith("del:")) {
                int i;
                try { i = Integer.parseInt(sk.substring(4)); } catch (Exception e) { return true; }
                ops.subDelete(i);
                stack.remove(stack.size() - 1);
                stack.add(ops.subSourceScreen());
                render(true);
                return true;
            }
            if (sk.startsWith("pickdir:")) { stack.add(ops.subPickScreen(sk.substring(8))); render(true); return true; }
            if (sk.startsWith("imp:")) { ops.subImportFile(sk.substring(4)); return true; }
            return true;
        }
        if ("bsrc:find".equals(k)) {
            inputAction = "bsrcfind";
            inputTitle = "搜索书源";
            openInput("输入书源名称或地址的一部分");
            return true;
        }
        if ("sub:reget".equals(k)) { ops.subRefetchCurrent(); return true; }
        if (k.startsWith("wfch:")) {
            int idx;
            try { idx = Integer.parseInt(k.substring(5)); } catch (Exception e) { return true; }
            while (!stack.isEmpty() && "wfch".equals(stack.get(stack.size() - 1).key)) stack.remove(stack.size() - 1);
            nb.nbOpenChapter(idx, true);
            return true;
        }
        if ("nb:searchagain".equals(k)) {
            inputAction = "nbsearch";
            inputTitle = "搜索书名或作者";
            openInput("输入关键词，多个源同时搜");
            return true;
        }
        if ("bsrc:filter".equals(k)) { stack.add(bsrcFilterScreen()); render(true); return true; }
        if ("bsrc:ftype".equals(k)) {
            int v = prefs.getInt("srcFilterType", -1) + 1;
            if (v > 2) v = -1;
            prefs.edit().putInt("srcFilterType", v).apply();
            String[] nm = {"全部类型", "只看文本", "只看音频", "只看图片"};
            speak(nm[v + 1]);
            stack.remove(stack.size() - 1);
            stack.add(bsrcFilterScreen());
            render(true);
            return true;
        }
        if ("bsrc:fon".equals(k)) {
            int v = prefs.getInt("srcFilterOn", -1) + 1;
            if (v > 1) v = -1;
            prefs.edit().putInt("srcFilterOn", v).apply();
            String[] nm = {"全部状态", "只看启用", "只看关闭"};
            speak(nm[v + 1]);
            stack.remove(stack.size() - 1);
            stack.add(bsrcFilterScreen());
            render(true);
            return true;
        }
        if ("bsrc:fclear".equals(k)) {
            prefs.edit().putInt("srcFilterType", -1).putInt("srcFilterOn", -1).apply();
            speak("筛选已清空");
            stack.remove(stack.size() - 1);
            stack.add(bookSourceScreen());
            render(true);
            return true;
        }
        if ("bsrc:back".equals(k)) {
            if (stack.size() > 1) stack.remove(stack.size() - 1);   // 只弹一层，别再把列表 push 一份
            render(true);
            return true;
        }
        if ("bsrc:sysclean".equals(k)) {
            boolean v = !prefs.getBoolean("srcSyncClean", true);
            prefs.edit().putBoolean("srcSyncClean", v).apply();
            speak("系统书源更新后清空本地旧副本：" + (v ? "开" : "关"));
            if (!stack.isEmpty()) stack.set(stack.size() - 1, bookSourceMenuScreen());
            render(true);
            return true;
        }
        if ("bsrc:sort".equals(k)) {
            int v = (prefs.getInt("srcSort", 0) + 1) % 3;
            prefs.edit().putInt("srcSort", v).apply();
            String[] nm = {"原顺序", "按名称", "按权重"};
            speak("排序改为 " + nm[v]);
            stack.remove(stack.size() - 1);
            stack.add(bookSourceScreen());
            render(true);
            return true;
        }
        if ("bsrc:checkall".equals(k)) {
            pushScreen(ops.bsrcCheckScreen());
            return true;
        }
        if (k.startsWith("bsrc:chkword:")) {
            int i;
            try { i = Integer.parseInt(k.substring("bsrc:chkword:".length())); } catch (Exception e) { return true; }
            if (i < 0 || i >= nb.nbSources.size()) return true;
            srcGrpIdx = i;
            inputAction = "chkword";
            inputTitle = "这个源的校验关键词";
            nb.loadBookSources();
            String cur = nb.getSourceField(i, "ruleSearch.checkKeyWord").trim();
            if (cur.isEmpty()) cur = nb.getSourceField(i, "checkKeyword").trim();
            openInput(cur.isEmpty() ? "留空表示跟全局关键词走" : cur);
            return true;
        }
        if ("bsrc:chkkey".equals(k)) {
            inputAction = "chkkey";
            inputTitle = "校验关键词";
            String cur = ops.checkCfg().keyword;
            openInput("现在是「" + cur + "」。搜书时拿这个词探活；留空恢复默认「我的」；"
                    + "某个站认别的词，就到那个源的详情里单独填");
            return true;
        }
        if ("bsrc:chktimeout".equals(k)) {
            inputAction = "chktimeout";
            inputTitle = "每个源超时（秒）";
            openInput("现在是 " + (ops.checkCfg().timeoutMs / 1000) + " 秒。1 到 600，超了就记「校验超时」");
            return true;
        }
        if ("bsrc:chksearch".equals(k)) { ops.toggleCheck("chkSearch"); return true; }
        if ("bsrc:chkdisc".equals(k)) { ops.toggleCheck("chkDisc"); return true; }
        if ("bsrc:chkinfo".equals(k)) { ops.toggleCheck("chkInfo"); return true; }
        if ("bsrc:chkcat".equals(k)) { ops.toggleCheck("chkCat"); return true; }
        if ("bsrc:chkcont".equals(k)) { ops.toggleCheck("chkCont"); return true; }
        if ("bsrc:chkpause".equals(k)) { ops.pauseCheck(); return true; }
        if ("bsrc:chkresume".equals(k)) { ops.resumeCheck(); return true; }
        if ("bsrc:chkstop".equals(k)) { ops.stopCheck(); return true; }
        if ("bsrc:chkrun".equals(k)) { ops.startCheck(bsrcKeepIdx()); return true; }
        if ("bsrc:chkrefresh".equals(k)) { render(true); ops.speakCheckStat(); return true; }
        if ("bsrc:chkresult".equals(k)) { pushScreen(ops.bsrcCheckResultScreen()); return true; }
        if (k.startsWith("bsrc:log:")) {
            int i;
            try { i = Integer.parseInt(k.substring(10)); } catch (Exception e) { return true; }
            nb.loadBookSources();
            ops.openBsrcLog(i);       // 占位屏先上，日志后台读完替换
            return true;
        }
        if (k.startsWith("bsrc:copy:")) {
            int i;
            try { i = Integer.parseInt(k.substring(11)); } catch (Exception e) { return true; }
            nb.loadBookSources();
            ops.bsrcCopy(i);
            return true;
        }
        if (k.startsWith("bsrc:expone:")) {
            int i;
            try { i = Integer.parseInt(k.substring(13)); } catch (Exception e) { return true; }
            nb.loadBookSources();
            ops.bsrcExportOne(i);
            return true;
        }
        if ("bsrc:new".equals(k)) {
            inputAction = "bsrcnew";
            inputTitle = "新建书源";
            openInput("输入：名称,网址（逗号隔开，地址可以留空）");
            return true;
        }
        if (k.startsWith("bsrc:json:")) {
            int i;
            try { i = Integer.parseInt(k.substring(10)); } catch (Exception e) { return true; }
            nb.loadBookSources();
            stack.add(ops.bsrcJsonScreen(i));
            render(true);
            return true;
        }
        if ("bsrc:allon".equals(k)) { nb.bsrcSetAll(true); return true; }
        if ("bsrc:alloff".equals(k)) { nb.bsrcSetAll(false); return true; }
        if (k.startsWith("bsrc:test:")) {
            int i;
            try { i = Integer.parseInt(k.substring(10)); } catch (Exception e) { return true; }
            inputAction = "bsrctest:" + i;
            inputTitle = "书源校验";
            openInput("输入校验关键词（默认 我的）");
            return true;
        }
        if ("bsrc:groups".equals(k)) { stack.add(sourceGroupScreen()); render(true); return true; }
        if (k.startsWith("bsrc:grp:")) {
            int gi;
            try { gi = Integer.parseInt(k.substring(9)); } catch (Exception e) { return true; }
            stack.add(sourceGroupListScreen(gi));
            render(true);
            return true;
        }
        if (k.startsWith("bsrc:grpd:")) {
            int i;
            try { i = Integer.parseInt(k.substring(10)); } catch (Exception e) { return true; }
            nb.loadBookSources();
            if (i < 0 || i >= nb.nbSources.size()) return true;
            srcGrpIdx = i;
            inputAction = "bsrcgrp";
            inputTitle = "书源分组";
            String cur = nb.nbSources.get(i).optString("bookSourceGroup", "");
            openInput(cur.isEmpty() ? "输入分组名，多个用逗号隔开" : "输入分组名，多个用逗号隔开");
            return true;
        }
        if ("bsrc:markstart".equals(k)) { srcMarkSel.clear(); nb.loadBookSources(); pushScreen(srcMarkScreen()); if (!screenReaderOn) speak("勾选页，点书名勾选"); return true; }
        if (k.startsWith("bsrc:marksel:")) {
            int mi = -1;
            try { mi = Integer.parseInt(k.substring("bsrc:marksel:".length())); } catch (Exception e) { return true; }
            if (mi < 0 || mi >= nb.nbSources.size()) return true;
            String nm = nb.nbSources.get(mi).optString("bookSourceName", "未命名");
            if (srcMarkSel.contains(nm)) srcMarkSel.remove(nm); else srcMarkSel.add(nm);
            Screen cur3 = top();
            int at3 = cur3.cur;
            stack.set(stack.size() - 1, srcMarkScreen());
            top().cur = Math.max(0, Math.min(at3, top().items.size() - 1));
            render(true);
            speak((srcMarkSel.contains(nm) ? "已选 " : "已取消 ") + nm + "，共 " + srcMarkSel.size() + " 个");
            return true;
        }
        if ("bsrc:expmark".equals(k)) {
            if (srcMarkSel.isEmpty()) { speak("还没选书源"); return true; }
            int n = ops.bsrcExportNames(srcMarkSel);
            srcMarkSel.clear();
            if (!stack.isEmpty()) stack.set(stack.size() - 1, srcMarkScreen());
            render(true);
            speak("导出 " + n + " 个完成");
            return true;
        }
        if ("bsrc:markclear".equals(k)) {
            srcMarkSel.clear();
            stack.set(stack.size() - 1, srcMarkScreen());
            render(true);
            speak("已清空选择");
            return true;
        }
        if ("bsrc:expshown".equals(k)) {
            nb.loadBookSources();
            java.util.List<String> names = new ArrayList<String>();
            int ft4 = prefs.getInt("srcFilterType", -1);
            int fo4 = prefs.getInt("srcFilterOn", -1);
            for (int i4 = 0; i4 < nb.nbSources.size(); i4++) {
                JSONObject o4 = nb.nbSources.get(i4);
                if (ft4 >= 0 && nb.srcType(o4) != ft4) continue;
                if (fo4 == 1 && !nb.srcEnabled(o4)) continue;
                if (fo4 == 0 && nb.srcEnabled(o4)) continue;
                names.add(o4.optString("bookSourceName", ""));
            }
            int n4 = ops.bsrcExportNames(names);
            speak("导出当前筛出的 " + n4 + " 个完成");
            return true;
        }
        if (k.startsWith("bsrc:edit:")) {
            try { srcEditIdx = Integer.parseInt(k.substring("bsrc:edit:".length())); } catch (Exception e) { return true; }
            nb.loadBookSources();
            pushScreen(srcRuleEditScreen());
            speak("可以逐项填规则，回车保存");
            return true;
        }
        if (k.startsWith("bsrc:fld:")) {
            int kk = -1;
            try { kk = Integer.parseInt(k.substring("bsrc:fld:".length())); } catch (Exception e) { return true; }
            if (kk < 0 || kk >= SRC_FIELDS.length) return true;
            inputAction = "srcfld";
            inputTitle = SRC_FIELDS[kk][0];
            pendingSrcField = SRC_FIELDS[kk][1];
            String now = nb.getSourceField(srcEditIdx, pendingSrcField);
            openInput("现在：" + (now == null || now.isEmpty() ? "（未填）" : now)
                    + "。输入新的，回车保存");
            return true;
        }
        if ("bsrc:expgroup".equals(k)) { pushScreen(exploreGroupPickScreen()); return true; }
        if ("bsrc:expkey".equals(k)) { inputAction = "expkey"; inputTitle = "按源名筛选"; openInput("输入源名里的字，留空表示全部"); return true; }
        if ("bsrc:exfind".equals(k)) { inputAction = "exfind"; inputTitle = "搜索书源"; openInput("输入书源名字、网址里的字或分组名，回车出结果"); return true; }
        if ("bsrc:expclr".equals(k)) {
            prefs.edit().remove("exGroup").remove("exKey").apply();
            reopenExplore("已清除筛选");
            return true;
        }
        if ("bsrc:exreload".equals(k)) { reopenExplore("已重新载入书源"); return true; }
        if (k.startsWith("exgrp:")) {
            String g = k.substring("exgrp:".length());
            prefs.edit().putString("exGroup", g).apply();
            reopenExplore(g.isEmpty() ? "全部分组" : "只看分组：" + g);
            return true;
        }
        if ("bsrc:neway:empty".equals(k)) {
            ops.newEmptyNow();
            // 建完回书源列表，向导页弹掉
            while (!stack.isEmpty() && top().key != null && "bsrcNewWay".equals(top().key)) stack.remove(stack.size() - 1);
            if (!stack.isEmpty()) stack.set(stack.size() - 1, bookSourceScreen());
            render(true);
            return true;
        }
        if ("bsrc:neway:generic".equals(k)) {
            ops.newGenericNow();
            while (!stack.isEmpty() && top().key != null && "bsrcNewWay".equals(top().key)) stack.remove(stack.size() - 1);
            if (!stack.isEmpty()) stack.set(stack.size() - 1, bookSourceScreen());
            render(true);
            return true;
        }
        if ("bsrc:neway:sniff".equals(k)) {
            ops.sniffNow();
            return true;
        }
        if ("bsrc:sniffbuild".equals(k)) {
            ops.buildFromSniff(true);
            // 嗅探页和向导页都要弹掉，只留下面那一屏换成书源列表
            while (!stack.isEmpty() && top().key != null
                    && ("bsrcSniff".equals(top().key) || "bsrcNewWay".equals(top().key))) {
                stack.remove(stack.size() - 1);
            }
            if (!stack.isEmpty()) stack.set(stack.size() - 1, bookSourceScreen());
            render(true);
            return true;
        }
        if ("bsrc:sniffempty".equals(k)) {
            ops.buildFromSniff(false);
            // 嗅探页和向导页都要弹掉，只留下面那一屏换成书源列表
            while (!stack.isEmpty() && top().key != null
                    && ("bsrcSniff".equals(top().key) || "bsrcNewWay".equals(top().key))) {
                stack.remove(stack.size() - 1);
            }
            if (!stack.isEmpty()) stack.set(stack.size() - 1, bookSourceScreen());
            render(true);
            return true;
        }
        if ("bsrc:sniffagain".equals(k)) {
            ops.sniffNow();
            return true;
        }
        if (k.startsWith("bsrc:exon:")) {
            int ei = -1;
            try { ei = Integer.parseInt(k.substring("bsrc:exon:".length())); } catch (Exception e) { return true; }
            if (ei < 0 || ei >= nb.nbSources.size()) return true;
            boolean now = nb.exploreOn(nb.nbSources.get(ei));
            boolean ok2 = nb.setSourceField(ei, "enabledExplore", String.valueOf(!now));
            stack.set(stack.size() - 1, ops.bsrcDetailScreen(ei));
            render(true);
            speak(ok2 ? ("发现已" + (now ? "关闭" : "开启")) : "保存失败，看看日志");
            return true;
        }
        if (k.startsWith("bsrc:srctop:")) {
            int ti = -1;
            try { ti = Integer.parseInt(k.substring("bsrc:srctop:".length())); } catch (Exception e) { return true; }
            int max = 0;
            for (int q = 0; q < nb.nbSources.size(); q++) max = Math.max(max, nb.nbSources.get(q).optInt("weight", 0));
            boolean ok = nb.setSourceField(ti, "weight", String.valueOf(max + 1));
            speak(ok ? "已置顶，排序选「按权重」时排最前" : "置顶失败，看看日志");
            return true;
        }
        if (k.startsWith("bsrc:exrefresh:")) {
            int ri = -1;
            try { ri = Integer.parseInt(k.substring("bsrc:exrefresh:".length())); } catch (Exception e) { return true; }
            reopenExplore("已重新算这个源的发现分类");
            return true;
        }
        if (k.startsWith("bsrc:detail:")) {
            int i;
            try { i = Integer.parseInt(k.substring(12)); } catch (Exception e) { return true; }
            nb.loadBookSources();
            bsrcDetailIdx = i;
            stack.add(ops.bsrcDetailScreen(i));
            render(true);
            return true;
        }
        if (k.startsWith("bsrc:del:")) {
            int i;
            try { i = Integer.parseInt(k.substring(9)); } catch (Exception e) { return true; }
            ops.bsrcDelete(i);
            stack.remove(stack.size() - 1);
            stack.add(bookSourceScreen());
            render(true);
            return true;
        }
        if (k.startsWith("bsrc:pickdir:")) {
            stack.add(ops.bsrcPickScreen(k.substring(13)));
            render(true);
            return true;
        }
        if (k.startsWith("bsrc:imp:")) {
            ops.bsrcImportFile(k.substring(9));
            return true;
        }
        if (k.startsWith("bsrc:toggle:")) {
            int i;
            try { i = Integer.parseInt(k.substring(12)); } catch (Exception e) { return true; }
            nb.loadBookSources();
            if (i < 0 || i >= nb.nbSources.size()) return true;
            String name = nb.nbSources.get(i).optString("bookSourceName", "未命名");
            boolean on = !nb.srcEnabled(nb.nbSources.get(i));
            prefs.edit().putBoolean("bsrcOn_" + name, on).apply();
            refreshTop("书源 " + name + " 已" + (on ? "启用" : "关闭"));
            return true;
        }
        return false;
    }

    private boolean actReader(String k, Screen sc, Item it) {
        if (k.startsWith("bookbm:")) {
            String path = k.substring(7);
            openBook(new File(path));
            return true;
        }
        if ("chp:prev".equals(k) || "chp:next".equals(k)) {
            Screen rd = null;
            for (int i = stack.size() - 1; i >= 0; i--) {
                if (stack.get(i).reader) { rd = stack.get(i); break; }
            }
            if (rd == null) return true;
            int target = com.mangjie.reader.reader.ChapterIndex.neighborChapter(
                    bookSent, rd.cur, "chp:next".equals(k));
            if (target < 0) {
                speak("chp:next".equals(k) ? "已经是最后一章" : "已经是第一章");
                return true;
            }
            jumpInReader(target);
            speak(com.mangjie.reader.reader.ChapterIndex.posTag(bookSent, target));
            return true;
        }
        if (k.startsWith("chj:")) {
            int idx;
            try { idx = Integer.parseInt(k.substring(4)); } catch (Exception e) { return true; }
            jumpInReader(idx);
            return true;
        }
        if (k.startsWith("fnd:")) {
            int idx;
            try { idx = Integer.parseInt(k.substring(4)); } catch (Exception e) { return true; }
            jumpInReader(idx);
            return true;
        }
        if (k.startsWith("fnp:")) {
            jumpToHit(k.endsWith("-1") ? -1 : 1);
            return true;
        }
        if (k.equals("bmclr")) {
            prefs.edit().remove(bmKey()).apply();
            stack.remove(stack.size() - 1);
            render(true);
            speak("书签已全部清空");
            toast("书签已全部清空");
            return true;
        }
        if (k.startsWith("reng:")) {
            String pkg = k.substring(5);
            readerVoice.enginePkg = "__default".equals(pkg) ? null : pkg;
            readerVoice.voiceName = null; rdVoiceObj = null;
            voiceLoadedFor = null;
            engineVoices.clear();
            savePrefs();
            rebuildRdTts();
            stack.remove(stack.size() - 1);
            render(true);
            final String lbl = "朗读语音库 " + rdEngineLabel();
            ui.postDelayed(new Runnable() { @Override public void run() {
                if (rdReady && rdTts != null) speakId(lbl, "rd"); else speak(lbl);
            } }, 900);
            return true;
        }
        if (k.startsWith("rv:")) {
            String v = k.substring(3);
            boolean isSys = "__sys".equals(v);
            int i = -1;
            try { i = Integer.parseInt(v); } catch (Exception ignored) {}
            // 只有「__sys」和发音人序号才在这里吃掉，其余 rv:xxx 交给后面的分支（加倍／语速／语调等）
            if (isSys || (i >= 0 && i < engineVoices.size())) {
                if (isSys) {
                    readerVoice.voiceName = null; rdVoiceObj = null;
                } else {
                    Voice vo = engineVoices.get(i);
                    readerVoice.voiceName = vo.getName(); rdVoiceObj = vo;
                }
                savePrefs();
                stack.remove(stack.size() - 1);
                render(true);
                final String lbl2 = "朗读发音人 " + rdVoiceLabel();
                ui.postDelayed(new Runnable() { @Override public void run() {
                    if (rdReady && rdTts != null) speakId(lbl2, "rd"); else speak(lbl2);
                } }, 300);
                return true;
            }
        }
        if (k.startsWith("bmj:")) {
            int idx;
            try { idx = Integer.parseInt(k.substring(4)); } catch (Exception e) { return true; }
            stack.remove(stack.size() - 1);
            autoPlay = true;
            speakSentence(idx);
            return true;
        }
        if (k.startsWith("unsupported:")) {
            speak("这个格式本版本还不支持，只支持 txt");
            toast("本版本只支持 txt");
            return true;
        }
        if (k.startsWith("gone:")) {
            String p3 = k.substring(5);
            String nm = new File(p3).getName();
            speak("这本书的文件不在了：" + nm + "。放回原来的位置就能接着读，或者长按从书架移出");
            return true;
        }
        if (k.startsWith("book:")) { openBook(new File(k.substring(5))); return true; }
        if (k.startsWith("rd")) {          // 阅读页：中键 暂停 / 继续朗读
            // 1.1.06 曾按「读屏开着」把它改成重念，结果连五向键中键一起改坏（读屏开着时中键不再能暂停）。
            // 按键归 App，读屏归界面，两边不越界：这里不再看 screenReaderOn。
            toggleRead("阅读键rd");
            return true;
        }
        if ("mydict:look".equals(k)) {
            inputAction = "mydictlook";
            inputTitle = "本地词库查词";
            openInput("输入要查的词");
            return true;
        }
        if ("mydict:imp".equals(k)) { importPick(KIND_DICT); return true; }
        if ("mydict:reload".equals(k)) {
            com.mangjie.reader.reader.Look.load(new File(DATA_DIR, "词典"));
            if (!stack.isEmpty()) stack.set(stack.size() - 1, userDictScreen());
            render(true);
            speak("词库已重新载入，" + com.mangjie.reader.reader.Look.count() + " 条");
            return true;
        }
        if ("mydict:rules".equals(k)) {
            pushScreen(dictRulesScreen());
            return true;
        }
        if ("mydict:rulesreload".equals(k)) {
            int n = com.mangjie.reader.reader.DictOnline.load(dictRuleDir());
            if (!stack.isEmpty()) stack.set(stack.size() - 1, dictRulesScreen());
            render(true);
            speak("在线词典规则 " + n + " 条");
            return true;
        }
        if (k.startsWith("mydict:rmone:")) {
            String nm = k.substring("mydict:rmone:".length());
            com.mangjie.reader.reader.DictOnline.load(dictRuleDir());
            for (com.mangjie.reader.reader.DictOnline.Rule r : com.mangjie.reader.reader.DictOnline.rules()) {
                if (!r.name.equals(nm) || r.file == null) continue;
                boolean ok = r.file.delete();
                devLogLine("词典", "删规则 " + nm + (ok ? " 成功" : " 失败"));
                speak(ok ? "已删除 " + nm : "删除失败");
                break;
            }
            com.mangjie.reader.reader.DictOnline.load(dictRuleDir());
            if (!stack.isEmpty()) stack.set(stack.size() - 1, dictRulesRemoveScreen());
            render(true);
            return true;
        }
        if ("mydict:ruleadd".equals(k)) {
            inputAction = "dictrule1";
            inputTitle = "新建在线词典规则";
            openInput("输入：词典名,网址（网址里用 {{key}} 代替要查的词，比如 https://www.zdic.net/hans/{{key}}）");
            return true;
        }
        if ("mydict:rulerm".equals(k)) {
            pushScreen(dictRulesRemoveScreen());
            return true;
        }
        if ("mydict:ruleshelp".equals(k)) {
            pushScreen(dictRulesHelpScreen());
            return true;
        }
        if ("ex:export".equals(k)) { exportExcerpts(); return true; }
        if ("ex:booktxt".equals(k)) { exportWholeBook(false); return true; }
        if ("ex:bookepub".equals(k)) { exportWholeBook(true); return true; }
        if ("ex:clear".equals(k)) {
            showDialog("清空摘抄.txt？", new String[]{"取消", "清空"});
            pendingExClear = true;
            return true;
        }
        if ("stats:clear".equals(k)) {
            showDialog("清空全部阅读统计？", new String[]{"取消", "清空"});
            pendingStatClear = true;
            return true;
        }
        if (k.startsWith("pur:item:")) {
            stack.add(purRuleDetail(clampInt(k.substring(9), 0, 999)));
            render(true);
            return true;
        }
        if ("pur:new".equals(k)) {
            ruleName = ""; rulePattern = ""; ruleRepl = "";
            inputAction = "pur:new1"; inputTitle = "新增净化规则";
            openInput("第一步，输入规则名称");
            return true;
        }
        if (k.startsWith("pur:editname:")) {
            int i = clampInt(k.substring(13), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> l = purUserRules();
            if (i < l.size()) { ruleName = l.get(i).name; inputAction = "pur:name:" + i;
                inputTitle = "规则名称"; openInput("现在是 " + ruleName); }
            return true;
        }
        if (k.startsWith("pur:editpat:")) {
            int i = clampInt(k.substring(12), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> l = purUserRules();
            if (i < l.size()) { rulePattern = l.get(i).pattern; inputAction = "pur:pat:" + i;
                inputTitle = "匹配正则"; openInput("现在是 " + rulePattern); }
            return true;
        }
        if (k.startsWith("pur:editrep:")) {
            int i = clampInt(k.substring(12), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> l = purUserRules();
            if (i < l.size()) { ruleRepl = l.get(i).replacement; inputAction = "pur:rep:" + i;
                inputTitle = "替换内容"; openInput("现在是「" + ruleRepl + "」，留空即整段删除"); }
            return true;
        }
        if (k.startsWith("pur:toggle:")) {
            int i = clampInt(k.substring(11), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> l = purUserRules();
            if (i < l.size()) {
                l.get(i).enable = !l.get(i).enable;
                savePurRules(l);
                refreshTop(purRuleDetail(i));
                speak("规则已" + (l.get(i).enable ? "启用" : "停用"));
            }
            return true;
        }
        if (k.startsWith("pur:del:")) {
            int i = clampInt(k.substring(8), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> l = purUserRules();
            if (i < l.size()) {
                pendingRuleDeletePur = i;
                showDialog("删除规则「" + l.get(i).name + "」？", new String[]{"取消", "删除"});
            }
            return true;
        }
        if (k.startsWith("toc:item:")) {
            stack.add(tocRuleDetail(clampInt(k.substring(9), 0, 999)));
            render(true);
            return true;
        }
        if ("toc:imp".equals(k)) { importPick(KIND_RULE); return true; }
        if ("toc:new".equals(k)) {
            ruleName = ""; rulePattern = "";
            inputAction = "toc:new1"; inputTitle = "新增目录规则";
            openInput("第一步，输入规则名称");
            return true;
        }
        if (k.startsWith("toc:editname:")) {
            int i = clampInt(k.substring(13), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> l = tocUserRules();
            if (i < l.size()) { ruleName = l.get(i).name; inputAction = "toc:name:" + i;
                inputTitle = "规则名称"; openInput("现在是 " + ruleName); }
            return true;
        }
        if (k.startsWith("toc:editpat:")) {
            int i = clampInt(k.substring(12), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> l = tocUserRules();
            if (i < l.size()) { rulePattern = l.get(i).pattern; inputAction = "toc:pat:" + i;
                inputTitle = "匹配正则"; openInput("现在是 " + rulePattern); }
            return true;
        }
        if (k.startsWith("toc:toggle:")) {
            int i = clampInt(k.substring(11), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> l = tocUserRules();
            if (i < l.size()) {
                l.get(i).enable = !l.get(i).enable;
                saveTocRules(l);
                refreshTop(tocRuleDetail(i));
                speak("规则已" + (l.get(i).enable ? "启用" : "停用"));
            }
            return true;
        }
        if (k.startsWith("toc:del:")) {
            int i = clampInt(k.substring(8), 0, 999);
            List<com.mangjie.reader.reader.UserRules.Entry> l = tocUserRules();
            if (i < l.size()) {
                pendingRuleDeleteToc = i;
                showDialog("删除规则「" + l.get(i).name + "」？", new String[]{"取消", "删除"});
            }
            return true;
        }
        if (k.startsWith("help:sec:")) {
            int hi = -1;
            try { hi = Integer.parseInt(k.substring("help:sec:".length())); } catch (Exception e) { return true; }
            if (hi < 0 || hi >= HELP_SECTIONS.length) return true;
            pushScreen(helpSectionScreen(hi));
            return true;
        }
        if ("tocrule:list".equals(k)) { pushScreen(tocRuleListScreen()); return true; }
        if ("pur:builtin".equals(k)) { pushScreen(purBuiltinScreen()); return true; }
        if ("tocrule:reload".equals(k)) {
            com.mangjie.reader.reader.TocRules.reload(this);
            com.mangjie.reader.reader.ChapterIndex.reset();
            if (!stack.isEmpty()) stack.set(stack.size() - 1, tocRuleScreen());
            render(false);
            speak("已重新载入目录规则，" + com.mangjie.reader.reader.TocRules.count() + " 条");
            return true;
        }
        if ("purify:reload".equals(k)) {
            com.mangjie.reader.reader.Purify.load(new File(DATA_DIR + "/净化"));
            if (!stack.isEmpty()) stack.set(stack.size() - 1, purifyScreen());
            render(false);
            speak("已重新载入净化规则，" + com.mangjie.reader.reader.Purify.ruleCount() + " 条");
            return true;
        }
        if ("rv:engine".equals(k)) { stack.add(readerEngineScreen(0)); render(true); return true; }
        if ("rv:voice".equals(k)) { stack.add(readerVoiceScreen(0)); render(true); return true; }
        if ("rv:double".equals(k)) {
            stack.add(speedMulScreen(readerVoice, "r"));
            render(true);
            return true;
        }
        if ("rv:test".equals(k)) {
            String t = "盲界阅读朗读试听，语速百分之" + readerVoice.rateP + "，语调百分之" + readerVoice.pitchP;
            if (rdReady && rdTts != null) {
                try { rdTts.setSpeechRate(rdEffRate()); rdTts.setPitch(mapFactor(readerVoice.pitchP)); } catch (Exception ignored) {}
                rdTts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "rvtest");
            } else {
                speak(t);
            }
            return true;
        }
        if ("rv:rate".equals(k)) { inputAction = "pct"; pendingPctKey = "rv:rate"; inputTitle = "朗读语速"; openInput("请输入 0 到 100 之间的数字"); return true; }
        if ("rv:pitch".equals(k)) { inputAction = "pct"; pendingPctKey = "rv:pitch"; inputTitle = "朗读语调"; openInput("请输入 0 到 100 之间的数字"); return true; }
        if (k.startsWith("spmul:")) {
            String[] p2 = k.split(":");
            int m = 2;
            try { m = Integer.parseInt(p2[2]); } catch (Exception ignored) {}
            if (m < 1) m = 1;
            if (m > 5) m = 5;
            boolean isReader = p2.length > 1 && "r".equals(p2[1]);
            com.mangjie.reader.speech.VoiceProfile v = isReader ? readerVoice : menuVoice;
            v.speedMul = m;
            savePrefs();
            if (isReader) {
                try { if (rdTts != null) rdTts.setSpeechRate(rdEffRate()); } catch (Exception ignored) {}
            } else {
                applySpeechParams();
            }
            if (!stack.isEmpty()) stack.remove(stack.size() - 1);       // 选完回上一层
            render(true);
            speak("语速加倍，" + m + " 倍");
            return true;
        }
        return false;
    }

    private boolean actSettings(String k, Screen sc, Item it) {
        if (k.startsWith("gsch:")) { pickGestureScheme(k.substring(5)); return true; }
        if (k.startsWith("sfxv:")) {
            int v;
            try { v = Integer.parseInt(k.substring(5)); } catch (Exception e) { v = -1; }
            if (v < 1 || v > 100) return true;
            sfxVol = v;
            toneGen = null;                 // 按新音量重建
            toneVol = -1;
            savePrefs();
            lastSfxAt = 0;
            sfx(android.media.ToneGenerator.TONE_PROP_BEEP, 40);
            goBack();
            speak("音效反馈音量 " + v + "%");
            return true;
        }
        if (k.startsWith("hapl:")) {
            int v;
            try { v = Integer.parseInt(k.substring(5)); } catch (Exception e) { v = -1; }
            if (v < 0 || v > 2) return true;
            hapLevel = v;
            savePrefs();
            buzz(20);
            goBack();
            speak("震动强度 " + hapLevelName());
            return true;
        }
        if (k.startsWith("hap:")) { handleHaptic(k.substring(4)); return true; }
        if (k.startsWith("sfx:")) { handleSfx(k.substring(4)); return true; }
        if (k.startsWith("go:")) { handleGo(k.substring(3)); return true; }
        if (k.startsWith("eng:")) { pickEngine(k.substring(4)); return true; }
        if (k.startsWith("vc:")) { pickVoice(k.substring(3)); return true; }
        if (k.startsWith("sp:fix") || k.equals("sp:fix")) { openTtsSettings(); return true; }
        if (k.startsWith("sp:")) { handleSpeech(k.substring(3)); return true; }
        if (k.startsWith("pick:")) { handlePick(k.substring(5)); return true; }
        if ("font:imp".equals(k)) { importPick(KIND_FONT); return true; }
        if (k.startsWith("fontfile:")) {
            int i = clampInt(k.substring(9), -1, 998);
            File[] kids = fontDir().listFiles();
            List<File> ttf = new ArrayList<>();
            if (kids != null) for (File f : kids) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (n.endsWith(".ttf") || n.endsWith(".otf"))) ttf.add(f);
            }
            if (i <= 0) {
                prefs.edit().remove("fontPath").apply();
                customFont = null; customFontPath = null;
            } else if (i - 1 < ttf.size()) {
                File f = ttf.get(i - 1);
                try {
                    Typeface tf = Typeface.createFromFile(f);
                    customFont = tf; customFontPath = f.getAbsolutePath();
                    prefs.edit().putString("fontPath", f.getAbsolutePath()).apply();
                    devLogLine("字体", "启用 " + f.getName());
                } catch (Throwable e) {
                    speak("这个字体文件读不了");
                    toast("这个字体文件读不了");
                    return true;
                }
            }
            refreshTop(fontFileScreen(i));
            render(true);
            speak("正文字体：" + fontLabel());
            return true;
        }
        if ("backup:export".equals(k)) {
            toast("正在导出备份");
            com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
                String json = com.mangjie.reader.reader.Backup.dump(MainActivity.this, new File(DATA_DIR), prefs);
                File out = null;
                String err = null;
                // 导出物进公有目录；写不进去时 App.exportDir 会自动退回私有，不会白导一场
                try { out = com.mangjie.reader.reader.Backup.save(
                        com.mangjie.reader.App.exportDir(), json); }
                catch (Throwable t) { err = t.getMessage(); }
                final File fo = out; final String fe = err;
                ui.post(new Runnable() { @Override public void run() {
                    if (fo != null) {
                        speakExportDone(fo, "备份");
                        devLogLine("备份", "导出 " + fo.getName() + "　" + fo.length() + " 字节");
                        refreshTop(backupScreen());
                    } else {
                        speak("导出失败 " + fe);
                        toast("导出失败");
                    }
                } });
            } });
            return true;
        }
        if ("backup:download".equals(k)) {
            toast("正在从服务器取备份");
            com.mangjie.reader.reader.BackupFlow.downloadRestore(MainActivity.this, new File(DATA_DIR), prefs,
                    new com.mangjie.reader.reader.BackupFlow.Cb() {
                @Override public void done(String msg, boolean restored) {
                    speak(msg);
                    toast(msg);
                    devLogLine("备份", msg);
                    if (restored) reloadAfterRestore();     // 恢复完必须重读设置，否则退后台时旧值又写回去
                    else if (!stack.isEmpty()) { stack.set(stack.size() - 1, backupScreen()); render(true); }
                }
            });
            return true;
        }
        if ("backup:upload".equals(k)) {
            toast("正在上传备份");
            com.mangjie.reader.reader.BackupFlow.upload(MainActivity.this, new File(DATA_DIR), prefs,
                    new com.mangjie.reader.reader.BackupFlow.Cb() {
                @Override public void done(String msg, boolean restored) {
                    speak(msg);
                    toast(msg);
                    devLogLine("备份", msg);
                }
            });
            return true;
        }
        if ("night:manual".equals(k)) {
            boolean night = !prefs.getBoolean("nightMode", false);
            if (night) {
                prefs.edit().putInt("dayPal", paletteIdx).apply();
                paletteIdx = prefs.getInt("nightPal", 0);
                if (paletteIdx < 0 || paletteIdx >= PAL_NAMES.length) paletteIdx = 0;
            } else {
                paletteIdx = prefs.getInt("dayPal", 0);
                if (paletteIdx >= PAL_NAMES.length) paletteIdx = 0;
            }
            prefs.edit().putBoolean("nightMode", night).putInt("palette", paletteIdx).apply();
            nightApplied = night;
            if (!stack.isEmpty()) stack.set(stack.size() - 1, nightScreen());
            render(true);
            speak(night ? "已切到夜间" : "已切到日间");
            devLogLine("夜间", "手动" + (night ? "夜间" : "日间") + " 配色=" + palName());
            return true;
        }
        if ("night:toggle".equals(k)) {
            boolean on = !prefs.getBoolean("autoNight", false);
            prefs.edit().putBoolean("autoNight", on).apply();
            nightApplied = false;
            applyAutoNight();
            if (!stack.isEmpty()) stack.set(stack.size() - 1, nightScreen());
            render(true);
            speak(on ? "已开启夜间自动切换" : "已关闭夜间自动切换");
            return true;
        }
        if ("night:from".equals(k)) {
            stack.add(nightHourScreen("from", prefs.getInt("nightFrom", 22)));
            render(true);
            return true;
        }
        if ("night:to".equals(k)) {
            stack.add(nightHourScreen("to", prefs.getInt("nightTo", 7)));
            render(true);
            return true;
        }
        if ("night:pal".equals(k)) { stack.add(nightPalScreen()); render(true); return true; }
        if (k.startsWith("night:from:") || k.startsWith("night:to:")) {
            String[] parts = k.split(":");
            int h;
            try { h = Integer.parseInt(parts[parts.length - 1]); } catch (Exception e) { return true; }
            boolean isFrom = k.startsWith("night:from:");
            prefs.edit().putInt(isFrom ? "nightFrom" : "nightTo", h).apply();
            stack.remove(stack.size() - 1);
            if (!stack.isEmpty()) stack.set(stack.size() - 1, nightScreen());
            render(true);
            nightApplied = false;
            applyAutoNight();
            speak((isFrom ? "开始时间 " : "结束时间 ") + h + " 点");
            return true;
        }
        if (k.startsWith("night:palset:")) {
            int i;
            try { i = Integer.parseInt(k.substring(13)); } catch (Exception e) { return true; }
            prefs.edit().putInt("nightPal", i).apply();
            stack.remove(stack.size() - 1);
            if (!stack.isEmpty()) {
                Screen under = top();
                String uk = under.key == null ? "" : under.key;
                if ("readcolor".equals(uk)) stack.set(stack.size() - 1, readColorScreen(under.cur));
                else stack.set(stack.size() - 1, nightScreen());
            }
            render(true);
            nightApplied = false;
            applyAutoNight();
            speak("夜间配色 " + PAL_NAMES[i]);
            return true;
        }
        if ("backup:dav".equals(k)) { pushScreen(backupDavScreen()); return true; }
        if ("backup:davurl".equals(k)) { inputAction = "davurl"; inputTitle = "服务器地址"; openInput("例如 https://dav.example.com:443"); return true; }
        if ("backup:davuser".equals(k)) { inputAction = "davuser"; inputTitle = "服务器账号"; openInput("输入账号"); return true; }
        if ("backup:davpass".equals(k)) { inputAction = "davpass"; inputTitle = "服务器密码"; openInput("输入密码"); return true; }
        if ("backup:davdir".equals(k)) { inputAction = "davdir"; inputTitle = "备份目录"; openInput("服务器上的目录，例如 /备份/"); return true; }
        if ("backup:restore".equals(k)) {
            showDialog("恢复会覆盖现在的设置和规则，继续？", new String[]{"取消", "恢复"});
            pendingRestore = true;
            return true;
        }
        if (k.startsWith("layout:line:")) {
            lineSpaceIdx = clampInt(k.substring(12), 0, 2);
            savePrefs();
            if (!stack.isEmpty()) stack.set(stack.size() - 1, layoutScreen(lineSpaceIdx));
            render(false);
            speak("行距" + LINE_SPACE_NAMES[lineSpaceIdx]);
            return true;
        }
        if (k.startsWith("layout:pad:")) {
            sidePadIdx = clampInt(k.substring(11), 0, 2);
            savePrefs();
            if (!stack.isEmpty()) stack.set(stack.size() - 1, layoutScreen(sidePadIdx));
            render(false);
            speak("页边距" + SIDE_PAD_NAMES[sidePadIdx]);
            return true;
        }
        if ("dev:a11y".equals(k)) { handleDevToggle(); return true; }
        if ("dev:mode".equals(k)) { toggleLogMode(); return true; }
        if ("dev:update".equals(k)) { toggleAutoUpdate(); return true; }
        if ("dev:upload".equals(k)) { logup.uploadLog(true); return true; }
        if ("dev:share".equals(k)) { shareLog(); return true; }
        if ("dev:srcup".equals(k)) { pickSourceFile(); return true; }
        if ("dev:srcclear".equals(k)) { clearServerSources(); return true; }
        if ("dev:clear".equals(k)) { clearLog(); return true; }
        if (k.startsWith("dev:")) { handleDevToggle(); return true; }
        if (k.startsWith("sp:")) { handleSpeech(k.substring(3)); return true; }
        if ("go:changelog".equals(k)) { pushScreen(changelogScreen()); return true; }
        if ("chg:refresh".equals(k)) { upd.fetchChangelog(true); return true; }
        if (k.startsWith("chg:")) {
            int i;
            try { i = Integer.parseInt(k.substring(4)); } catch (Exception e) { return true; }
            if (i < 0 || i >= chgEntries.size()) return true;
            List<Item> items = new ArrayList<>();
            items.add(new Item("版本 " + chgEntries.get(i)[0]));
            for (String line : chgEntries.get(i)[1].split("\n")) if (!line.trim().isEmpty()) items.add(new Item(line));
            pushScreen(new Screen("更新日志　" + chgEntries.get(i)[0], items));
            return true;
        }
        return false;
    }

    private boolean actFile(String k, Screen sc, Item it) {
        if (k.equals("f:eb:internal")) {
            String root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            stack.add(listDir(root, true)); render(true); return true;
        }
        if (k.equals("f:eb:cache")) {
            File dir = new File(DATA_DIR, "网上缓存");
            if (!dir.exists()) dir.mkdirs();
            stack.add(listDir(dir.getAbsolutePath(), true));
            render(true);
            if (dir.listFiles() == null || dir.listFiles().length == 0) speak("网上缓存还是空的");
            return true;
        }
        if (k.equals("f:eb:sd")) {
            String root = sdRoot();
            if (root == null) {
                speak("没有检测到存储卡，请确认已经插入并挂载");
                toast("没有检测到存储卡");
                return true;
            }
            stack.add(listDir(root, true)); render(true); return true;
        }
        if (k.startsWith("dir:")) {
            // 电子书书单里点目录 → 仍按 txt 过滤往下钻
            stack.add(listDir(k.substring(4), sc.txtOnly));
            render(true); return true;
        }
        if (k.equals("todo:fav")) { stack.add(pathListScreen("本地收藏", "favList", "还没有收藏的书")); render(true); return true; }
        if (k.equals("todo:recent")) { stack.add(pathListScreen("本地最近", "recentList", "还没有阅读记录")); render(true); return true; }
        if (k.equals("cachemgr")) { stack.add(cacheManageScreen()); render(true); return true; }
        if (k.equals("cache:clearall")) {
            showDialog("清空旧版整本缓存？", new String[]{"取消", "清空"});
            pendingCacheClear = true;
            return true;
        }
        if (k.startsWith("todo:")) { speak("这个功能还没做"); toast("这个功能还没做"); return true; }
        if (k.startsWith("f:internal")) { stack.add(listDir(internalRoot())); render(true); return true; }
        if (k.startsWith("f:sd")) {
            String root = sdRoot();
            if (root == null) { speak("未插入存储卡"); toast("未插入存储卡"); return true; }
            stack.add(listDir(root)); render(true);
            return true;
        }
        if ("f:grant".equals(k)) { openGrantSettings(); return true; }
        if (k.startsWith("grant:")) { handleGrant(k.substring(6)); return true; }
        if (k.startsWith("file:")) {
            String name = new File(k.substring(5)).getName();
            speak(name);
            toast(name);
            return true;
        }
        if (k.startsWith("sc:")) { speak("存储卡" + sdState()); toast("存储卡" + sdState()); return true; }
        if ("legacy:uninstall".equals(k)) { uninstallLegacy(); return true; }
        if ("legacy:skip".equals(k)) {
            prefs.edit().putBoolean("legacySkip", true).apply();
            goBack();
            speak("已忽略旧版提示");
            return true;
        }
        if (k.startsWith("lc:find:")) {
            String[] p2 = k.split(":");
            int c = -1, off = -1;
            try { c = Integer.parseInt(p2[2]); off = Integer.parseInt(p2[3]); } catch (Exception ignored) {}
            if (c >= 0 && localMode) {
                if (!stack.isEmpty() && top() != null && !top().reader) stack.remove(stack.size() - 1);
                openLocalChapter(c, Math.max(0, off));
            }
            return true;
        }
        return false;
    }



    // ---------------------------------------------------------------- 在线更新
    private static final String DAV_BASE = "https://dav.mdmumu.fnos.net:443";
    private static final String DAV_DIR = com.mangjie.reader.net.DavClient.APP_DIR_NEW;
    private static final String DAV_DIR_OLD = com.mangjie.reader.net.DavClient.APP_DIR_OLD;
    private static final String DAV_LOG_DIR = com.mangjie.reader.net.DavClient.newSub("%E6%97%A5%BF%97");
    private static final String DAV_USER = "mangjie";
    private static final String DAV_PASS = "mangjie1";

    /** 连 WebDAV 查有没有新版本：读根目录里的 version.txt，再找同名或最新的 apk */
    /** 本机是否已经下过这个版本的安装包（校验能解析出版本号才认） */
    private String localApkFor(String ver) {
        try {
            File dir = getExternalFilesDir("apk");
            if (dir == null) dir = new File(DATA_DIR);
            File f = new File(dir, "update-v" + ver + ".apk");
            if (!f.exists() || f.length() < 1024) return null;
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null || pi.versionName == null) return null;
            if (!pi.versionName.equals(ver)) return null;
            return f.getAbsolutePath();
        } catch (Throwable t) {
            devLogLine("更新", "读本地包失败：" + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 启动时清掉比当前版本旧的安装包 */


    private String readAll(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private String[] regexAll(String src, String pattern) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(src);
        List<String> out = new ArrayList<String>();
        while (m.find()) out.add(m.group(1));
        return out.toArray(new String[0]);
    }

    private String DAV_LAST_ERR = null;
    private String DAV_LAST_URL = null;

    private String davGet(String path) {
        String ret = com.mangjie.reader.net.DavClient.getAppAny(path);
        DAV_LAST_ERR = com.mangjie.reader.net.DavClient.lastError();
        DAV_LAST_URL = com.mangjie.reader.net.DavClient.lastUrl();
        return ret;
    }

    private int compareVer(String a, String b) {
        return com.mangjie.reader.net.Version.compare(a, b);
    }



    /** 从 WebDAV 下载安装包到外部存储，再交给系统安装器 */

    private void installApk(String path) {
        // 安装权限按需要：只有真要点安装这一步才去跳设置，不在启动时预先索取
        if (!canInstall()) {
            long last = prefs == null ? 0L : prefs.getLong("askInstallAt", 0L);
            if (System.currentTimeMillis() - last >= ASK_GAP_MS) {
                if (prefs != null) prefs.edit().putLong("askInstallAt", System.currentTimeMillis()).apply();
                speak("新版本已经下好了，允许「安装应用」之后才能装上");
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                    return;
                } catch (Exception ignored) { }
            } else {
                speak("新版本已下好，但没允许安装应用，去设置里允许一下");
                return;
            }
        }
        try {
            File f = new File(path);
            android.net.Uri uri = android.net.Uri.parse("content://" + ApkProvider.authorityOf(this) + f.getAbsolutePath());
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            // 没有 FileProvider 时退回普通路径
            try {
                Intent it = new Intent(Intent.ACTION_VIEW);
                it.setDataAndType(android.net.Uri.fromFile(new File(path)),
                        "application/vnd.android.package-archive");
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            } catch (Exception e2) {
                toast("无法打开安装器，请到文件管理里手动安装 " + path);
                speak("无法打开安装器");
            }
        }
    }


    /** 开发者菜单：日志开关、上传、清空、路径 */
    private Screen devScreen() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("无障碍日志：" + (devLog ? "已开启" : "已关闭"), "dev:a11y"));
        items.add(new Item("日志上传：" + logModeName(), "dev:mode"));
        items.add(new Item("启动自动更新：" + (autoUpdate ? "已开启" : "已关闭"), "dev:update"));
        items.add(new Item("上传日志到服务器", "dev:upload"));
        items.add(new Item("上传书源到服务器", "dev:srcup"));
        items.add(new Item("清空服务器书源", "dev:srcclear"));
        items.add(new Item("清空日志", "dev:clear"));
        items.add(new Item("日志文件：" + logup.logSizeText(), "dev:info"));
        items.add(new Item("日志位置：" + DATA_DIR + "/无障碍日志.txt", "dev:info"));
        items.add(new Item("设备信息：" + deviceShort(), "dev:info"));
        items.add(new Item("服务器目录：/mangjie/日志/", "dev:info"));
        return new Screen("开发者", items).k("dev");
    }

    /** 开发者专用：选一个本地书源文件，传到服务器「书源」目录，并由上传逻辑顺手重建清单.txt */
    private void pickSourceFile() {
        if (!devMode) { speak("开发者模式没开，这个入口不给用"); return; }
        speak("选一个书源文件，json 或者 txt");
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_SOURCE);
        } catch (Throwable t) {
            try {
                Intent g = new Intent(Intent.ACTION_GET_CONTENT);
                g.setType("*/*");
                startActivityForResult(g, REQ_PICK_SOURCE);
            } catch (Throwable t2) {
                speak("这台机器没有文件选择器，传不了");
            }
        }
    }

    /** 清空服务器书源：第一次点只提醒，第二次点才真删，免得误触把线上的源删光 */
    private void clearServerSources() {
        if (!devMode) { speak("开发者模式没开，这个入口不给用"); return; }
        if (!clearSrcArmed) {
            clearSrcArmed = true;
            speak("再点一次就真删服务器上的书源和清单，不删就别再点");
            return;
        }
        clearSrcArmed = false;
        speak("正在清空服务器书源");
        com.mangjie.reader.tools.SourceUploader.clearAll(ui,
                new com.mangjie.reader.tools.SourceUploader.Done() {
            @Override public void onDone(boolean ok, String name, byte[] bytes, int listed, String msg) {
                devLogLine("书源", "清空服务器书源　" + msg);
                speak(msg);
                toast(msg);
                render(true);
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_FONT || req == REQ_PICK_DICT || req == REQ_PICK_RULE) {
            handleImportResult(req, res, data);
            return;
        }
        if (req != REQ_PICK_SOURCE) return;
        if (res != RESULT_OK || data == null || data.getData() == null) { speak("没选文件"); return; }
        speak("正在上传书源");
        com.mangjie.reader.tools.SourceUploader.upload(this, data.getData(), ui,
                new com.mangjie.reader.tools.SourceUploader.Done() {
            @Override public void onDone(boolean ok, String name, byte[] bytes, int listed, String msg) {
                devLogLine("书源", "上传 " + name + " → " + (ok ? "成功" : "失败") + "　" + msg);
                if (!ok) { speak(msg); toast(msg); return; }
                // 本地先落一份系统副本，不用等下次启动同步
                try {
                    File dir = new File(BOOKSRC_DIR);
                    if (!dir.exists()) dir.mkdirs();
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(dir, "系统-" + name));
                    fo.write(bytes);
                    fo.close();
                    nb.loadBookSources();
                    render(true);
                } catch (Throwable ignored) {}
                speak(msg);
                toast(msg);
            }
        });
    }


    /** 把本地日志 PUT 到 WebDAV 的 logs/ 目录，文件名带时间戳 */

    /** 型号 + 品牌 + 系统版本 */

    /** 上传日志的头部信息：机型、系统、屏幕、存储、运行内存 */

    private String fmtSize(long bytes) {
        if (bytes <= 0) return "未知";
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        if (gb >= 1) return String.format(Locale.US, "%.2f GB", gb);
        return String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0);
    }

    private void toggleAutoUpdate() {
        autoUpdate = !autoUpdate;
        prefs.edit().putBoolean("autoUpdate", autoUpdate).apply();
        devLogLine("更新", "启动自动更新=" + (autoUpdate ? "开" : "关"));
        refreshTop("启动自动更新已" + (autoUpdate ? "开启" : "关闭"));
    }

    private String logModeName() {
        if (logMode <= 0) return "已关闭";
        if (logMode == 1) return "正式（仅异常·WiFi）";
        return "开发（每5分钟）";
    }

    private void toggleLogMode() {
        logMode = (logMode + 1) % 3;      // 关闭 → 正式 → 开发 → 关闭
        autoUpload = logMode > 0;
        savePrefs();
        if (logMode == 2) {
            if (!devLog) { devLog = true; prefs.edit().putBoolean("devLog", true).apply(); }
            logup.startAutoUpload();
        } else {
            logup.stopAutoUpload();
        }
        devLogLine("更新", "日志上传档位=" + logMode);
        refreshTop("日志上传：" + logModeName());
    }

    /** 每 5 分钟检查一次：日志比上次上传时长大了就传一份；退出前（onPause）也会补一次 */



    private void clearLog() {
        File f = new File(DATA_DIR, "无障碍日志.txt");
        boolean ok = !f.exists() || f.delete();
        refreshTop(ok ? "日志已清空" : "清空失败");
    }

    private byte[] readFile(File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /** 建集合，已存在（405/301）视为成功 */
    private void davMkcol(String path) {
        try {
            java.net.URL u = new java.net.URL(DAV_BASE + path);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setRequestMethod("MKCOL");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString(
                    (DAV_USER + ":" + DAV_PASS).getBytes("UTF-8"), android.util.Base64.NO_WRAP));
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) {}
    }

    private boolean davPut(String path, byte[] data) {
        boolean ok = com.mangjie.reader.net.DavClient.putApp(path, data);
        DAV_LAST_ERR = com.mangjie.reader.net.DavClient.lastError();
        return ok;
    }

    private void handleDevToggle() {
        devLog = !devLog;
        prefs.edit().putBoolean("devLog", devLog).apply();
        if (devLog) {
            try {
                File dir = new File(DATA_DIR);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "无障碍日志.txt");
                FileWriter fw = new FileWriter(f, true);
                fw.write("\n===== 开始记录 " + sdf.format(new Date()) + " =====\n");
                fw.close();
            } catch (Exception ignored) {}
        }
        refreshTop(devLog ? "开发者功能已开启，开始记录无障碍日志" : "开发者功能已关闭");
        devLogLine("开关", devLog ? "开启" : "关闭");
    }

    /** 开发者日志：只在开关打开时写文件，记录读屏焦点、切行、手势判定 */
    private void devLogLine(String tag, String msg) {
        if (!devLog) return;
        try {
            File dir = new File(DATA_DIR);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "无障碍日志.txt");
            if (f.length() > 1024 * 1024) {
                File bak = new File(dir, "无障碍日志-上一份.txt");
                if (bak.exists()) bak.delete();
                f.renameTo(bak);
            }
            FileWriter fw = new FileWriter(f, true);
            fw.write(sdf.format(new Date()) + "  [" + tag + "] " + msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    /** 把字符串夹到 [lo, hi]，解析失败按 lo 处理 */

    /** 书架页判定：有分栏且不是阅读页 */
    /** 书架页靠 key 判定：页内已经没有分组条，不能再拿 tabs 非空当条件 */
    private boolean isShelfPage() {
        Screen sc = top();
        return sc != null && "shelf".equals(sc.key) && !sc.reader;
    }

    // ================================================================ 手势动作
    /** 手势引擎报到：按当前方案查表，再落到 App 内的动作 */
    private void runGesture(String id) {
        if (!gestureOn) return;
        String scheme = prefs.getString("gestureScheme",
                com.mangjie.reader.gesture.GestureScheme.MANGJIE);
        String act = com.mangjie.reader.gesture.GestureScheme.action(prefs, scheme, id);
        devLogLine("手势", com.mangjie.reader.gesture.GestureScheme.gestureName(id)
                + " → " + com.mangjie.reader.gesture.GestureScheme.actionName(act));
        if (com.mangjie.reader.gesture.GestureScheme.NONE.equals(act)) return;
        doGestureAction(act);
    }

    private void doGestureAction(String act) {
        // 覆盖层先接一层：手势已经能进菜单/对话框/输入页了，动作必须落在各自的焦点上，
        // 不能用 top() 的底层页去执行——那会在菜单背后悄悄把正文翻走
        if (inputOn) { gestureInput(act); return; }
        if (dialogOn) { gestureDialog(act); return; }
        if (menuOn) { gestureMenu(act); return; }
        Screen sc = top();
        if (sc == null) return;
        switch (act) {
            case com.mangjie.reader.gesture.GestureScheme.NAV_NEXT:
            case com.mangjie.reader.gesture.GestureScheme.NAV_PREV: {
                int step = act.equals(com.mangjie.reader.gesture.GestureScheme.NAV_NEXT) ? 1 : -1;
                if (sc.reader) { readerJump(step); return; }
                int n = sc.cur + step;
                if (n < 0) { speak("已经是第一项"); return; }
                if (n >= sc.items.size()) { speak("已经是最后一项"); return; }
                sc.cur = n;
                highlightSelection();
                ensureRowVisible(n);
                speakRow(sc.items.get(n).label);
                return;
            }
            case com.mangjie.reader.gesture.GestureScheme.SCROLL_FWD:
                scrollScreen(1);
                return;
            case com.mangjie.reader.gesture.GestureScheme.SCROLL_BACK:
                scrollScreen(-1);
                return;
            case com.mangjie.reader.gesture.GestureScheme.ACTIVATE:
                activate();
                return;
            case com.mangjie.reader.gesture.GestureScheme.TTS_TOGGLE:
                if (sc.reader) toggleReading(); else speak("现在不在阅读页");
                return;
            case com.mangjie.reader.gesture.GestureScheme.TTS_NEXT: {
                if (!sc.reader) { speak("现在不在阅读页"); return; }
                int n = sc.cur + 1;
                if (n >= sc.items.size()) { speak("已经是最后一句"); return; }
                sc.cur = n;
                savePos(n);
                highlightSelection();
                ensureRowVisible(n);
                autoPlay = true;
                readingNow = true;
                speakSentence(n);
                return;
            }
            case com.mangjie.reader.gesture.GestureScheme.PAGE_NEXT:
            case com.mangjie.reader.gesture.GestureScheme.PAGE_PREV: {
                int step = act.equals(com.mangjie.reader.gesture.GestureScheme.PAGE_NEXT) ? 1 : -1;

                if (sc.reader) { readerPage(step); return; }
                scrollScreen(step);
                return;
            }
            case com.mangjie.reader.gesture.GestureScheme.CHAPTER_NEXT:
            case com.mangjie.reader.gesture.GestureScheme.CHAPTER_PREV: {
                int step = act.equals(com.mangjie.reader.gesture.GestureScheme.CHAPTER_NEXT) ? 1 : -1;
                if (!sc.reader) { speak("现在不在阅读页"); return; }
                readerChapter(step);
                return;
            }
            case com.mangjie.reader.gesture.GestureScheme.BACK:
                goBack();
                return;
            case com.mangjie.reader.gesture.GestureScheme.HOME:
                gestureHome();
                return;
            default:
                return;
        }
    }

    /** 对话框里的双指：上下换按钮、双指双击按当前按钮、右滑等于取消。口径照抄按键那段 */
    private void gestureDialog(String act) {
        switch (act) {
            case com.mangjie.reader.gesture.GestureScheme.NAV_NEXT:
            case com.mangjie.reader.gesture.GestureScheme.SCROLL_FWD:
            case com.mangjie.reader.gesture.GestureScheme.PAGE_NEXT: {
                int n = dialogSel + 1;
                if (n < dialogBtns.length) {
                    dialogSel = n;
                    render(false);
                    speak(dialogBtns[n]);
                } else {
                    speak("已经是最后一个按钮");
                }
                return;
            }
            case com.mangjie.reader.gesture.GestureScheme.NAV_PREV:
            case com.mangjie.reader.gesture.GestureScheme.SCROLL_BACK:
            case com.mangjie.reader.gesture.GestureScheme.PAGE_PREV: {
                int n = dialogSel - 1;
                if (n >= 0) {
                    dialogSel = n;
                    render(false);
                    speak(dialogBtns[n]);
                } else {
                    speak("已经是第一个按钮");
                }
                return;
            }
            case com.mangjie.reader.gesture.GestureScheme.ACTIVATE:
                commitDialog();
                return;
            case com.mangjie.reader.gesture.GestureScheme.BACK:
                closeDialog();
                speak("已取消");
                return;
            default:
                return;
        }
    }

    /** 菜单里的双指：上下换一项、翻屏走一屏、双指双击执行当前项、右滑关菜单。
     *  这里不能走 caret().moveTo：那条路的高亮画在 rowsBox 上，菜单开着时正文是隐藏的，
     *  所以照按键那段的口径——改 menuScreen.cur 之后 render，render 里会把 menuScroll 滚到位 */
    private void gestureMenu(String act) {
        if (menuScreen == null) {
            // 页面级菜单没有行列表，双指只认「返回」这一条，别的都不动
            if (com.mangjie.reader.gesture.GestureScheme.BACK.equals(act)) {
                closeFileMenu();
                speak("已返回");
            }
            return;
        }
        int total = menuScreen.items == null ? 0 : menuScreen.items.size();
        // 滚一屏和首页同口径：只动画面、不动光标、不播报。
        // 以前这里做成「焦点跳一屏」，菜单里听起来是一下跳到别的项，跟首页对不上
        if (act.equals(com.mangjie.reader.gesture.GestureScheme.SCROLL_FWD)
                || act.equals(com.mangjie.reader.gesture.GestureScheme.PAGE_NEXT)) {
            focus().scrollBy(1);
            return;
        }
        if (act.equals(com.mangjie.reader.gesture.GestureScheme.SCROLL_BACK)
                || act.equals(com.mangjie.reader.gesture.GestureScheme.PAGE_PREV)) {
            focus().scrollBy(-1);
            return;
        }
        if (act.equals(com.mangjie.reader.gesture.GestureScheme.NAV_NEXT)
                || act.equals(com.mangjie.reader.gesture.GestureScheme.NAV_PREV)) {
            boolean fwd = act.equals(com.mangjie.reader.gesture.GestureScheme.NAV_NEXT);
            int n = menuScreen.cur + (fwd ? 1 : -1);
            if (n < 0) { speak("已经是第一项"); return; }
            if (n > total - 1) { speak("已经是最后一项"); return; }
            menuScreen.cur = n;
            render(false);
            speak(menuScreen.items.get(n).label);
            return;
        }
        switch (act) {
            case com.mangjie.reader.gesture.GestureScheme.ACTIVATE:
                activate();
                return;
            case com.mangjie.reader.gesture.GestureScheme.BACK:
                closeFileMenu();
                speak("已返回");
                return;
            default:
                return;
        }
    }

    /** 输入页里的双指：只留「确定」和「返回」，别的动作在这里都会把底下的页面动掉 */
    private void gestureInput(String act) {
        switch (act) {
            case com.mangjie.reader.gesture.GestureScheme.ACTIVATE: {
                View focused = getCurrentFocus();
                // 光标在输入框里时，双指双击交回系统（等同于点一下输入框里），不吃成「确定」
                if (focused instanceof EditText) return;
                onInputButton("确定");
                return;
            }
            case com.mangjie.reader.gesture.GestureScheme.BACK:
                closeInput();
                speak("已取消");
                return;
            default:
                return;
        }
    }

    private float scrollCarry = 0f;

    /** 跟手滚动：手指往哪走画面就跟着走多少，抬手即停（不补惯性） */
    private void followScroll(float dy) {
        if (!gestureOn) return;
        if (inputOn || dialogOn) return;          // 输入页/对话框没有可滚内容，别把底下的页面拖走
        Screen sc = top();
        if (sc == null || sc.reader) return;      // 阅读页双指上下仍是上一句/下一句
        ScrollView sv = (menuOn && menuScroll != null) ? menuScroll : scroll;
        if (sv == null) return;
        float acc = scrollCarry - dy;             // 手指上移（dy<0）→ 内容上滚
        int step = (int) acc;
        scrollCarry = acc - step;
        if (step != 0) sv.scrollBy(0, step);
    }

    /** 滚一屏：正数往后（看到下面的内容），负数往前 */
    private void scrollScreen(int dir) { focus().scrollBy(dir); }

    /** 阅读页翻一屏：按可见行数算，翻完从这一屏第一句开始读 */
    private void readerPage(int dir) {
        Screen sc = top();
        if (sc == null || !sc.reader) return;
        int per = 1;
        if (scroll != null && rowsBox != null && rowsBox.getChildCount() > 0) {
            int rowH = rowsBox.getChildAt(0).getHeight();
            if (rowH > 0 && scroll.getHeight() > 0)
                per = Math.max(1, (scroll.getHeight() / rowH) - 1);
        }
        int n = sc.cur + dir * per;
        if (n < 0) n = 0;
        if (n >= sc.items.size()) n = sc.items.size() - 1;
        if (n == sc.cur) {
            speak(dir < 0 ? "已经是第一句" : "已经是最后一句");
            return;
        }
        sc.cur = n;
        savePos(n);
        highlightSelection();
        ensureRowVisible(n);
        if (autoPlay) { readingNow = true; speakSentence(n); }
        else chainPosTag(n);
    }

    private void chainPosTag(int n) {
        if (bookSent == null || bookSent.isEmpty()) return;
        speak(com.mangjie.reader.reader.ChapterIndex.posTag(bookSent, n));
    }

    /** 阅读页换章 */
    private void readerChapter(int dir) {
        if (bookSent == null || bookSent.isEmpty()) return;
        int target = com.mangjie.reader.reader.ChapterIndex.neighborChapter(bookSent, top().cur, dir > 0);
        if (target < 0) {
            speak(dir > 0 ? "已经是最后一章" : "已经是第一章");
            return;
        }
        jumpInReader(target);
        speak(com.mangjie.reader.reader.ChapterIndex.posTag(bookSent, target));
    }

    /** 回 App 主菜单：只砍栈，不退出程序、不碰系统桌面 */
    private void gestureHome() {
        Screen now = top();
        if (now != null && now.reader) {
            savePos(now.cur);
            autoPlay = false;
            if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
        }
        while (stack.size() > 1) stack.remove(stack.size() - 1);
        if (!screenReaderOn) speak("已回到主菜单");
        render(true);
    }

    

    private int parseIntSafe(String v, int def) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

    /** 设置四组：外观 / 阅读 / 其他 / 手势（书源与订阅已拆成独立入口） */
    private void handleSetGroup(String g) {
        if ("appearance".equals(g)) { pushScreen(setAppearanceScreen()); return; }
        if ("read".equals(g)) { pushScreen(setReadScreen()); return; }
        if ("data".equals(g)) { pushScreen(setDataScreen()); return; }
        if ("gesture".equals(g)) { pushScreen(setGestureScreen()); return; }
        speak("未知设置分组");
    }

    private void handleGo(String what) {
        if ("hlstyle".equals(what)) { stack.add(hlStyleScreen(0)); render(true); return; }
        if ("hlcolor".equals(what)) { stack.add(hlColorScreen(0)); render(true); return; }
        if ("readcolor".equals(what)) { stack.add(readColorScreen(0)); render(true); return; }
        if ("custom".equals(what)) { stack.add(themeColorScreen(0)); render(true); return; }
        if ("nightpal".equals(what)) { stack.add(nightPalScreen()); render(true); return; }
        if ("text".equals(what)) { stack.add(textScreen(fontIdx)); render(true); return; }
        if ("fb".equals(what)) { stack.add(feedbackScreen(0)); render(true); return; }
        if ("titlefollow".equals(what)) {
            boolean on = !com.mangjie.reader.view.ThemeColors.titleFollow(prefs);
            prefs.edit().putBoolean("titleFollow", on).apply();
            applyTitleTheme();
            speak(on ? "标题栏跟随主题" : "标题栏固定黑底");
            stack.set(stack.size() - 1, appearanceScreen());
            render(true);
            return;
        }
        if ("gesturescheme".equals(what)) { stack.add(gestureSchemeScreen()); render(true); return; }
        if ("help".equals(what)) { stack.add(helpScreen()); render(true); return; }
        if ("gesturehelp".equals(what)) { stack.add(gestureHelpScreen()); render(true); return; }
        if ("uihelp".equals(what)) { stack.add(interfaceHelpScreen()); render(true); return; }
        if ("gestureengine".equals(what)) {
            String now = gestureEngineKey();
            String next = com.mangjie.reader.gesture.TalkBackGestures.ENGINE.equals(now)
                    ? com.mangjie.reader.gesture.TalkBackGestures.ENGINE_SELF
                    : com.mangjie.reader.gesture.TalkBackGestures.ENGINE;
            prefs.edit().putString("gestureEngine", next).apply();
            if (gestures != null) gestures.cancelPending();
            gestures = makeGestureEngine();
            devLogLine("手势", "引擎切换为 " + com.mangjie.reader.gesture.TalkBackGestures.engineName(next));
            refreshTop("手势引擎已切到" + com.mangjie.reader.gesture.TalkBackGestures.engineName(next));
            return;
        }
        if ("gesturetoggle".equals(what)) {
            gestureOn = !gestureOn;
            prefs.edit().putBoolean("gestureOn", gestureOn).apply();
            refreshTop("双指手势已" + (gestureOn ? "开启" : "关闭"));
            return;
        }
        if ("sfx".equals(what)) { stack.add(sfxScreen(0)); render(true); return; }
        if ("haptic".equals(what)) { stack.add(hapticScreen(0)); render(true); return; }
        if ("speech".equals(what)) { stack.add(speechScreen(0)); render(true); return; }
        if ("appearance".equals(what)) { stack.add(appearanceScreen()); render(true); return; }
        if ("sysinfo".equals(what)) { stack.add(sysInfoScreen()); render(true); return; }
        if ("fontfile".equals(what)) { stack.add(fontFileScreen(0)); render(true); return; }
        if ("subcacheclear".equals(what)) {
            boolean ok = com.mangjie.reader.net.SubCache.clearAll(DATA_DIR);
            if (!stack.isEmpty()) stack.set(stack.size() - 1, setDataScreen());
            render(true);
            speak(ok ? "订阅缓存已清空" : "有文件没删掉，可能被占用，再点一次");
            return;
        }
        if ("backup".equals(what)) { stack.add(backupScreen()); render(true); return; }
        if ("excerpt".equals(what)) { stack.add(excerptScreen()); render(true); return; }
        if ("night".equals(what)) { stack.add(nightScreen()); render(true); return; }
        if ("mydict".equals(what)) { stack.add(userDictScreen()); render(true); return; }
        if ("layout".equals(what)) { stack.add(layoutScreen(lineSpaceIdx)); render(true); return; }
        if ("tocrule".equals(what)) { stack.add(tocRuleScreen()); render(true); return; }
        if ("purify".equals(what)) { stack.add(purifyScreen()); render(true); return; }
        if ("simplify".equals(what)) {
            simplifyOn = !simplifyOn;
            savePrefs();
            speak("简繁转换已" + (simplifyOn ? "开启，下次打开书生效" : "关闭"));
            if (!stack.isEmpty()) stack.set(stack.size() - 1, setReadScreen());
            render(false);
            return;
        }
        if ("dev".equals(what)) { stack.add(devScreen()); render(true); return; }

        if ("booksrc".equals(what)) { pushScreen(bookSourceScreen()); return; }
        if ("subsrc".equals(what)) { pushScreen(ops.subSourceScreen()); return; }
        if ("changelog".equals(what)) {
            stack.add(changelogScreen());
            render(true);
            upd.fetchChangelog(false);
            return;
        }
        if ("update".equals(what)) { upd.checkUpdate(false); return; }
        if ("factory".equals(what)) { confirmFactory(); return; }
    }

    private void handleSpeech(String what) {
        if ("double".equals(what)) {
            stack.add(speedMulScreen(menuVoice, "m"));
            render(true);
            return;
        }
        if ("rate".equals(what) || "pitch".equals(what)) {
            pendingPctKey = "rate".equals(what) ? "sp:rate" : "sp:pitch";
            inputAction = "pct";
            inputTitle = "rate".equals(what) ? "输入语速百分比" : "输入语调百分比";
            openInput("请输入 0 到 100 之间的数字");
            return;
        }
        if ("engine".equals(what)) {
            stack.add(engineScreen(engineIdxOf()));
            render(true);
        } else if ("voice".equals(what)) {
            stack.add(voiceScreen(0));
            render(true);
        } else if ("test".equals(what)) {
            speak("盲界阅读语音试听，语速百分之" + menuVoice.rateP + "，语调百分之" + menuVoice.pitchP);
            if (!ttsReady) toast("系统缺少中文语音，请先在系统设置里下载");
        } else if ("switch".equals(what)) {
            speechOn = !speechOn;
            if (!speechOn) { if (tts != null) tts.stop(); toast("语音提示已关闭"); }
            refreshTop(speechOn ? "语音已开启" : "语音已关闭");
        }
    }

    private int engineIdxOf() {
        if (menuVoice.enginePkg == null) return 0;
        try {
            List<EngineInfo> infos = tts == null ? null : tts.getEngines();
            if (infos != null) {
                for (int i = 0; i < infos.size(); i++) if (infos.get(i).name.equals(menuVoice.enginePkg)) return i + 1;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void pickEngine(String pkg) {
        menuVoice.enginePkg = "__default".equals(pkg) ? null : pkg;
        menuVoice.voiceName = null;                     // 换引擎后语音要重选
        savePrefs();
        rebuildTts();
        stack.remove(stack.size() - 1);        // 退出发音人列表
        stack.remove(stack.size() - 1);        // 退出发引擎列表 → 回到语音提示
        stack.add(speechScreen(0));
        render(false);
        speak("语音引擎 " + engineLabel());
    }

    private void pickVoice(String idxStr) {
        if ("__sys".equals(idxStr)) {
            menuVoice.voiceName = null;
            voiceObj = null;
            savePrefs();
            if (tts != null) {
                try { tts.setLanguage(java.util.Locale.CHINA); } catch (Exception ignored) {}
                applySpeechParams();
            }
            stack.remove(stack.size() - 1);
            stack.remove(stack.size() - 1);
            stack.add(speechScreen(0));
            render(false);
            speak("发音人跟随系统");
            return;
        }
        int i;
        try { i = Integer.parseInt(idxStr); } catch (Exception e) { return; }
        if (i < 0 || i >= engineVoices.size()) return;
        Voice v = engineVoices.get(i);
        menuVoice.voiceName = v.getName();
        voiceObj = v;
        savePrefs();
        if (tts != null) {
            try { tts.setVoice(v); } catch (Exception e) { toast("这个语音不可用"); return; }
            applySpeechParams();
        }
        stack.remove(stack.size() - 1);
        stack.remove(stack.size() - 1);
        stack.add(speechScreen(0));
        render(false);
        speak("发音人 " + v.getName());
    }

    private TextToSpeech.OnInitListener rdInitListener = new TextToSpeech.OnInitListener() {
        @Override public void onInit(int status) {
            devLogLine("语音", "界面语音 init 返回 " + status + (status == TextToSpeech.SUCCESS ? "（可用）" : "（不可用）"));
            if (rdTts == null) return;
            if (status == TextToSpeech.SUCCESS) {
                // 有些语音库（如 MultiTTS）只有一个默认发音人、也不接受强制指定语言，
                // 这时 setLanguage 会返回不支持，但它照样能出声——所以只以「初始化成功」为准
                rdReady = true;
                int langR = -99;
                try { langR = rdTts.setLanguage(java.util.Locale.CHINA); } catch (Exception ignored) {}
                // 重进程序后 rdVoiceObj 是空的，要按保存的发音人名重新在阅读引擎里找回来
                try {
                    if (rdVoiceObj == null && readerVoice.voiceName != null && rdTts != null) {
                        for (Voice v : rdTts.getVoices()) {
                            if (readerVoice.voiceName.equals(v.getName())) { rdVoiceObj = v; break; }
                        }
                    }
                    if (rdVoiceObj != null) rdTts.setVoice(rdVoiceObj);
                } catch (Exception ignored) {}
                try {
                    rdTts.setSpeechRate(rdEffRate());
                    rdTts.setPitch(mapFactor(readerVoice.pitchP));
                } catch (Exception ignored) {}
                devLogLine("语音", "正文引擎就绪　引擎=" + (readerVoice.enginePkg == null ? "系统默认" : readerVoice.enginePkg)
                        + "　setLanguage=" + langR + "（0 正常 / -1 不支持 / -2 缺数据）"
                        + "　rate=" + String.format(Locale.US, "%.2f", rdEffRate())
                        + "　pitch=" + String.format(Locale.US, "%.2f", mapFactor(readerVoice.pitchP))
                        + "　发音人=" + (rdVoiceObj == null ? "无" : rdVoiceObj.getName()));
            } else {
                rdReady = false;
                devLogLine("语音", "正文引擎初始化失败　status=" + status);
            }
        }
    };

    /** 日志里认引擎用：三个实例可能同时存在 */
    private String ttsTag(TextToSpeech inst) {
        if (inst == null) return "无";
        if (inst == ttsSys) return "系统默认";
        if (inst == tts) return "界面(" + (menuVoice.enginePkg == null ? "默认" : menuVoice.enginePkg) + ")";
        return "正文(" + (readerVoice.enginePkg == null ? "系统默认" : readerVoice.enginePkg) + ")";
    }

    private void rebuildRdTts() {
        if (rdTts != null) { try { rdTts.stop(); rdTts.shutdown(); } catch (Exception ignored) {} }
        rdReady = false;
        rdTts = readerVoice.enginePkg == null
                ? new TextToSpeech(this, rdInitListener)
                : new TextToSpeech(this, rdInitListener, readerVoice.enginePkg);
        try { rdTts.setOnUtteranceProgressListener(utteranceListener); } catch (Exception ignored) {}
    }

    /** 换引擎后重建 TTS 实例 */
    private void rebuildTts() {
        if (rdTts == null) rebuildRdTts();
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {} }
        ttsReady = false;
        tts = menuVoice.enginePkg == null
                ? new TextToSpeech(this, ttsInitListener)
                : new TextToSpeech(this, ttsInitListener, menuVoice.enginePkg);
        tts.setOnUtteranceProgressListener(utteranceListener);
        ui.removeCallbacks(healTick);
        ui.postDelayed(healTick, 3000);
    }

    private void handlePick(String what) {
        String[] parts = what.split(":");
        if (parts.length != 2) return;
        int i;
        try { i = Integer.parseInt(parts[1]); } catch (Exception e) { return; }

        if ("color".equals(parts[0])) {
            paletteIdx = (i == paletteIdx) ? (i + 1) % PAL_NAMES.length : i;   // 点当前项＝切下一套，循环
            clearCustomOverlay();
            savePrefs();
            refreshTop("配色 " + palName());
        } else if ("cust".equals(parts[0])) {
            stack.add("bg".equals(parts[1]) ? customBgScreen(0) : customFgScreen(0));
            render(true);
            speak("选择" + ("bg".equals(parts[1]) ? "背景" : "前景") + "颜色");
            return;
        } else if ("cusbg".equals(parts[0])) {
            beginCustomOverlay();
            int ci = (prefs.getInt("cusBgOv", -1) == CUS_COLORS[i]) ? (i + 1) % CUS_COLORS.length : i;
            prefs.edit().putInt("cusBgOv", CUS_COLORS[ci]).apply();
            savePrefs();
            refreshTop("背景 " + CUS_NAMES[i]);
        } else if ("cusfg".equals(parts[0])) {
            beginCustomOverlay();
            int fi = (prefs.getInt("cusFgOv", -1) == CUS_FG[i]) ? (i + 1) % CUS_FG.length : i;
            prefs.edit().putInt("cusFgOv", CUS_FG[fi]).apply();
            savePrefs();
            refreshTop("前景 " + CUS_FG_NAMES[i]);
        } else if ("font".equals(parts[0])) {
            fontIdx = (i == fontIdx) ? (i + 1) % FONT_NAMES.length : i;
            refreshTop("字号 " + FONT_NAMES[i]);
        }
    }

    /** 改完一项：重建当前子页（保留光标位置），刷新界面并朗读结果 */
    /** 设置类页面改完一项后重建自己：按页面 key 分派，不再靠标题字符串比对 */
    private void refreshTop(String sayText) {
        Screen t = top();
        int cur = t.cur;
        Screen rebuilt = t;
        String k = t.key;
        if (k != null) {
            if ("speech".equals(k)) rebuilt = speechScreen(cur);
            else if ("cusBg".equals(k)) rebuilt = customBgScreen(cur);
            else if ("cusFg".equals(k)) rebuilt = customFgScreen(cur);
            else if ("text".equals(k)) rebuilt = textScreen(cur);
            else if ("readcolor".equals(k)) rebuilt = readColorScreen(cur);
            else if ("hlcolor".equals(k)) rebuilt = hlColorScreen(cur);
            else if ("fb".equals(k)) rebuilt = feedbackScreen(cur);
            else if ("shelfManage".equals(k)) rebuilt = shelfManageScreen(cur);
            else if ("records".equals(k)) rebuilt = recordsScreen();
            else if ("settings".equals(k)) rebuilt = settingsScreen();
            else if ("sfx".equals(k)) rebuilt = sfxScreen(cur);
            else if ("sfxVol".equals(k)) rebuilt = sfxVolScreen();
            else if ("haptic".equals(k)) rebuilt = hapticScreen(cur);
            else if ("hapLevel".equals(k)) rebuilt = hapLevelScreen();
            else if ("about".equals(k)) rebuilt = aboutScreen();
            else if ("bsrc".equals(k)) rebuilt = bookSourceScreen();
            else if ("gesture".equals(k)) rebuilt = setGestureScreen();
            else if ("night".equals(k)) rebuilt = nightScreen();
            else if ("dev".equals(k)) rebuilt = devScreen();
            else if ("themecolor".equals(k)) rebuilt = themeColorScreen(cur);
            else if ("hlstyle".equals(k)) rebuilt = hlStyleScreen(cur);
        }
        rebuilt.cur = cur;
        stack.set(stack.size() - 1, rebuilt);
        render(false);
        speak(sayText);
    }

    /** Android 13+ 预测式返回：系统的手势返回直接回调到这里，不再依赖触摸事件 */
    private void initSystemBack() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            devLogLine("返回", "系统版本低于 13，走 onBackPressed");
            return;
        }
        try {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {
                        @Override public void onBackInvoked() {
                            devLogLine("返回", "系统返回回调　当前页=" + (top() == null ? "?" : top().title));
                            goBack();
                        }
                    });
            devLogLine("返回", "已注册预测式返回回调");
        } catch (Throwable t) {
            devLogLine("返回", "注册返回回调失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /** 读屏的「返回」手势、系统返回键最终都落到这里；不重写会直接 finish 掉整个 App */
    @Override public void onBackPressed() {
        devLogLine("返回", "系统返回事件（读屏手势/返回键）　当前页=" + (top() == null ? "?" : top().title));
        goBack();
    }

    private void goBack() { shell().back(); }

    /** 退出不再确认，直接走人；收尾都放在 onDestroy 里 */
    private void exitApp() {
        devLogLine("生命周期", "用户选择退出");
        finish();
    }

    private void confirmFactory() {
        showDialog("还原默认值？\n配色、字体、语音设置回到默认，书和进度不动", new String[]{"取消", "还原"});
        pendingFactory = true;
    }

    private boolean pendingFactory = false;

    private void showDialog(String msg, String[] btns) {
        dialogOn = true;
        dialogSel = 0;
        dialogMsg = msg;
        dialogBtns = btns;
        render(false);
        speak(msg + "，默认选中" + btns[0]);
    }

    private void closeDialog() {
        dialogOn = false;
        dialogSel = 0;
        pendingFactory = false;
        pendingDelete = null;
        pendingClear = null;
        pendingBmIdx = -1;
        pendingRuleDeletePur = -1;
        pendingRuleDeleteToc = -1;
        pendingRestore = false;
        pendingStatClear = false;
        pendingExClear = false;
        pendingCacheClear = false;
        render(false);
    }

    private void commitDialog() {
        String sel = dialogBtns[dialogSel];
        if (ttsFixPending) {
            ttsFixPending = false;
            boolean go = !"取消".equals(sel) && !"稍后".equals(sel);
            closeDialog();
            if (go) openTtsSettings();
            return;
        }
        boolean factory = pendingFactory;
        boolean doRestore = pendingRestore;
        boolean doStatClear = pendingStatClear;
        boolean doExClear = pendingExClear;
        boolean doCacheClear = pendingCacheClear;
        int delPur = pendingRuleDeletePur;
        int delToc = pendingRuleDeleteToc;
        closeDialog();
        if ("清空".equals(sel) && doCacheClear) {
            cacheDeleteAll();
            return;
        }
        if ("清空".equals(sel) && doExClear) {
            try {
                File f = new File(DATA_DIR, "摘抄.txt");
                if (f.exists()) {
                    java.io.FileWriter fw = new java.io.FileWriter(f, false);
                    fw.write("");
                    fw.close();
                }
                speak("摘抄已清空");
                toast("摘抄已清空");
            } catch (Exception e) {
                speak("清空失败");
            }
            if (!stack.isEmpty()) stack.set(stack.size() - 1, excerptScreen());
            render(true);
            return;
        }
        if ("清空".equals(sel) && doStatClear) {
            com.mangjie.reader.reader.ReadStats.clear(prefs);
            if (!stack.isEmpty()) stack.set(stack.size() - 1, recordsScreen());
            render(true);
            speak("阅读统计已清空");
            toast("阅读统计已清空");
            return;
        }
        if ("恢复".equals(sel) && doRestore) {
            com.mangjie.reader.tools.Workers.run(new Runnable() { @Override public void run() {
                File f = com.mangjie.reader.reader.Backup.latest(new File(DATA_DIR));
                String msg;
                if (f == null) {
                    msg = "还没有备份文件";
                } else {
                    try {
                        String json = com.mangjie.reader.reader.TextFile.read(f, 12 * 1024 * 1024);
                        msg = "已恢复：" + com.mangjie.reader.reader.Backup.restore(
                                MainActivity.this, new File(DATA_DIR), prefs, json);
                    } catch (Throwable t) {
                        msg = "恢复失败：" + t.getMessage();
                    }
                }
                final String fm = msg;
                ui.post(new Runnable() { @Override public void run() {
                    reloadAfterRestore();
                    speak(fm);
                    toast(fm);
                    devLogLine("备份", fm);
                } });
            } });
            return;
        }
        if ("删除".equals(sel) && delPur >= 0) {
            List<com.mangjie.reader.reader.UserRules.Entry> list = purUserRules();
            String gone = (delPur < list.size() ? list.get(delPur).name : "");
            if (delPur < list.size()) { list.remove(delPur); savePurRules(list); }
            if (!stack.isEmpty()) stack.set(stack.size() - 1, purifyScreen());
            render(true);
            speak("已删除规则 " + gone);
            return;
        }
        if ("删除".equals(sel) && delToc >= 0) {
            List<com.mangjie.reader.reader.UserRules.Entry> list = tocUserRules();
            String gone = (delToc < list.size() ? list.get(delToc).name : "");
            if (delToc < list.size()) { list.remove(delToc); saveTocRules(list); }
            if (!stack.isEmpty()) stack.set(stack.size() - 1, tocRuleScreen());
            render(true);
            speak("已删除规则 " + gone);
            return;
        }
        if ("删除".equals(sel) && pendingBmIdx >= 0) {
            List<Integer> bms = readBookmarks();
            bms.remove(Integer.valueOf(pendingBmIdx));
            writeBookmarks(bms);
            pendingBmIdx = -1;
            if (top().title.equals("书签")) {
                stack.remove(stack.size() - 1);
                stack.add(bookmarkScreen());
            }
            render(true);
            speak("已删除这个书签");
            toast("已删除这个书签");
            return;
        }
        if ("删除".equals(sel) && pendingDelete != null) {
            File f = pendingDelete;
            pendingDelete = null;
            boolean ok;
            try { deleteRec(f); ok = !f.exists(); } catch (Exception e) { ok = false; }
            speak(ok ? "已删除 " + f.getName() : "删除失败");
            toast(ok ? "已删除 " + f.getName() : "删除失败");
            refreshDir();
            return;
        }
        if ("清空".equals(sel) && pendingClear != null) {
            File d = pendingClear;
            pendingClear = null;
            int n = 0;
            File[] kids = d.listFiles();
            if (kids != null) for (File k : kids) { deleteRec(k); n++; }
            speak("已清空，共 " + n + " 项");
            toast("已清空 " + n + " 项");
            refreshDir();
            return;
        }
        if ("还原".equals(sel) && factory) {
            paletteIdx = 0; fontIdx = 1; speechOn = true;
            menuVoice.enginePkg = null; menuVoice.voiceName = null;
            menuVoice.rateP = 50; menuVoice.pitchP = 50;
            savePrefs();
            stack.clear();
            stack.add(mainScreen());
            render(true);
            speak("已还原默认值");
        } else {
            speak("已取消");
        }
    }

    /** 交给系统浏览器打开；没有能处理的 App 返回 false */
    private boolean openInBrowser(String url) {
        // 不再用 resolveActivity 预判：Android 11+ 的包可见性下它对网页意图恒返回 null，
        // 明明有浏览器也会被判成「没找到」。直接发，真没人接再兜回来
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty()) return false;
        // 书源里的地址常省掉协议头（www.xx.com/...）。没有 scheme 的 Uri 谁都接不住，
        // ACTION_VIEW 只会抛 ActivityNotFoundException，看起来就是「点进去打不开」
        if (!u.contains("://") && !u.startsWith("mailto:") && !u.startsWith("tel:") && !u.startsWith("content:")) {
            u = u.startsWith("//") ? ("https:" + u) : ("https://" + u);
            devLogLine("订阅", "地址缺协议头，补成 " + u);
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            devLogLine("订阅", "这台机器上没有能接网页的应用：" + url);
            return false;
        } catch (Throwable e) {
            devLogLine("订阅", "交给浏览器失败：" + e.getMessage());
            return false;
        }
    }

    /** 把设置里的服务器参数交给 DavClient；没填就是没配，上传取回会直接提示先填 */
    private void applyDavConfig() {
        com.mangjie.reader.net.DavClient.configure(
                prefs.getString("davUrl", ""),
                prefs.getString("davDir", "/mangjie/"),
                prefs.getString("davUser", ""),
                prefs.getString("davPass", ""));
    }

    /** 重开发现页：把栈顶那页换掉再重建，别堆一串发现页 */
    private void reopenExplore(String say) { shell().reopenExplore(say); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------------------------------------------------------- 语音与读屏互斥

    private final TextToSpeech.OnInitListener ttsInitListener = new TextToSpeech.OnInitListener() {
        @Override public void onInit(int status) {
            devLogLine("语音", "界面语音 init 返回 " + status + (status == TextToSpeech.SUCCESS ? "（可用）" : "（不可用）"));
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.CHINA);
                ttsReady = (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED);
                applySpeechParams();
                if (menuVoice.voiceName != null) {
                    refreshZhVoices();
                    for (Voice v : zhVoices) {
                        if (menuVoice.voiceName.equals(v.getName())) {
                            try { tts.setVoice(v); } catch (Exception ignored) {}
                            break;
                        }
                    }
                }
                if (ttsReady && speechOn) {
                    tts.speak("语音已就绪", TextToSpeech.QUEUE_FLUSH, null, "s918-ready");
                }
                if (!ttsReady) promptNoTts();          // 引擎缺中文语音数据／语言不支持
            } else {
                promptNoTts();                          // 一个可用引擎都没有
            }
        }
    };

    /** 正文朗读用的 id：rd（整句）、rdmid（中间块）、b<批次>#<句号>[m]（预排批次） */
    private static boolean isReaderId(String id) {
        if (id == null) return false;
        if ("rd".equals(id) || "rdmid".equals(id)) return true;
        return id.length() > 1 && id.charAt(0) == 'b' && id.indexOf('#') > 0;
    }

    private final UtteranceProgressListener utteranceListener = new UtteranceProgressListener() {
        @Override public void onStart(String id) {
            devLogLine("语音", "引擎开口　id=" + id);
            // 只有正文句子的回调才能动状态：界面提示用的也是同一个 listener，
            // 它一开口就把重试标记清零，兜底就永远走不到第二层
            if (!isReaderId(id)) return;
            com.mangjie.reader.speech.TtsWatchdog.markStarted();   // 本轮开过口，超时也不判死
            com.mangjie.reader.speech.TtsWatchdog.disarm();
            long nowMs = System.currentTimeMillis();
            long gap = (lastProgAt == 0) ? -1 : (nowMs - lastProgAt);
            if (bgAt > 0) bgProg++;
            devLogLine("语音", "引擎出声　id=" + id + "　距上次进展=" + (gap < 0 ? "首次" : gap + "ms"));
            lastProgAt = nowMs;
            lastProgId = id;
            if (id.startsWith("b" + batchSeq + "#")) batchStartCount++;
            if (!id.endsWith("m")) {           // 末块开口才算这一句真开始念
                lastStartTailId = id;
                lastStartTailAt = nowMs;
                // 1.1.46：每句一条结构化事件。只看「送批/正文读完」两个总数，
                // 推不出是哪台机器的哪个引擎把句子吞掉了
                int qh = id.indexOf('#');
                if (qh > 0) {
                    try {
                        int qi = Integer.parseInt(id.substring(qh + 1));
                        devLogLine("句", "n=" + (qi + 1) + "/" + bookSent.size()
                                + " tok=" + batchSeq + " eng=" + batchEngTag
                                + " len=" + (qi >= 0 && qi < bookSent.size() ? bookSent.get(qi).length() : -1)
                                + " 开口 首字=" + (batchSentAt > 0 ? (nowMs - batchSentAt) + "ms" : "?"));
                    } catch (Throwable ignored) {}
                }
            }
            resumeMissCount = 0;                  // 这一句真的开口了，续读计数归零
            ttsStallRetried = false;
            healTries = 0;                        // 有进展，自愈计数归零
        }
        @Override public void onDone(String id) {
            // 1.1.47：先记收尾位，再撤表。收尾位留着，到点也不会再判死
            if (isReaderId(id)) com.mangjie.reader.speech.TtsWatchdog.markDone();
            if ("rd".equals(id)) {
                ttsStallRetried = false;      // 正常读完了，下次再卡死还给它一次重建机会
                ttsStallCount = 0;            // 一次真的读通，卡死计数归零
                rdFallbackSticky = false;
                ui.post(new Runnable() { @Override public void run() {
                    if (!flushQueuedSpeak()) advanceSentence();
                } });
                return;
            }
            if (id != null && id.length() > 1 && id.charAt(0) == 'b'
                    && id.indexOf('#') > 0 && !id.endsWith("m")) {
                int hash = id.indexOf('#');
                int seq = -1, bi = -1;
                try {
                    seq = Integer.parseInt(id.substring(1, hash));
                    bi = Integer.parseInt(id.substring(hash + 1));
                } catch (Exception ignored) {}
                long nowDone = System.currentTimeMillis();
                long gapDone = (lastProgAt == 0) ? -1 : (nowDone - lastProgAt);
                devLogLine("语音", "正文读完　id=" + id + "　距上次进展=" + (gapDone < 0 ? "首次" : gapDone + "ms")
                        + "　批=" + seq + "/当前批=" + batchSeq);
                try {
                    devLogLine("句", "n=" + (bi + 1) + "/" + bookSent.size()
                            + " tok=" + seq + " eng=" + batchEngTag
                            + " len=" + (bi >= 0 && bi < bookSent.size() ? bookSent.get(bi).length() : -1)
                            + " 读完 句时=" + (gapDone < 0 ? "?" : gapDone + "ms"));
                } catch (Throwable ignored) {}
                lastProgAt = nowDone;
                lastProgId = id;
                if (seq != batchSeq) return;            // 旧批次的收尾，扔掉
                final int fbi = bi;
                if (fbi >= 0) ui.post(new Runnable() { @Override public void run() { onBatchDone(fbi); } });
                return;
            }
            if ("qd".equals(id)) {
                ui.post(new Runnable() { @Override public void run() {
                    if (!flushQueuedSpeak()) advanceSentence();     // 队列空了才继续正文
                } });
                return;
            }
            if ("nbc".equals(id)) {
                ui.post(new Runnable() { @Override public void run() {
                    if (nb.nbChainChunks != null) nb.nbChainSpeakNext();
                } });
                return;
            }
            if (resumeReadingAfterSpeak) {
                cancelResumeGuard();       // 回调到了、正文要续上，把 3.5 秒兜底表一起撤掉
                ui.post(new Runnable() { @Override public void run() {
                    if (autoPlay && top().reader) speakSentence(top().cur);
                } });
            }
        }
        @Override public void onError(String id) {
            devLogLine("语音", "朗读出错 id=" + id);
            // 1.1.47：记一下错误位，别让同一轮再被判死一次
            if (isReaderId(id)) com.mangjie.reader.speech.TtsWatchdog.markError();
            if ("rd".equals(id) || "rdmid".equals(id)) {
                com.mangjie.reader.speech.TtsWatchdog.disarm();
                ui.postDelayed(new Runnable() { @Override public void run() { advanceSentence(); } }, 800);
                return;
            }
            if (!isReaderId(id)) {
                com.mangjie.reader.speech.TtsWatchdog.disarm();
                return;
            }
            // 正文被打断：别当成「引擎卡死」去重建，那样只会让同一句反复起头。
            // 先原句重发一次；连续两次被打断，才跳过这句往下走
            int seq = 0;
            int bi = -1;
            try {
                int hash = id.indexOf('#');
                seq = Integer.parseInt(id.substring(1, hash));
                bi = Integer.parseInt(id.substring(hash + 1).replace("m", ""));
            } catch (Exception ignored) {}
            final int fseq = seq;
            final int fbi = bi;
            ui.post(new Runnable() { @Override public void run() {
                if (fseq != batchSeq) return;                       // 旧批的回调，不掺和现在这批
                if (!autoPlay || top() == null || !top().reader) return;
                if (fbi >= 0) moveCursorTo(fbi);
                com.mangjie.reader.speech.TtsWatchdog.disarm();
                // 1.0.68 对齐 Legado：引擎回 onError 就是这句念不出来，重发没用，直接往下走。
                // 真正「被人打断」由音频焦点层提前兜住（暂停、等焦点回来、再接上），轮不到这里救
                int curNow = top().cur;
                devLogLine("语音", "正文朗读出错，跳过第" + (curNow + 1) + "句续读（对齐 Legado）");
                if (curNow + 1 < bookSent.size()) {
                    readingNow = true;
                    speakSentence(curNow + 1);
                }
            } });
        }
    };

    // 1.0.68：静默自愈。Legado 没有这一层——它整章入队、纯靠回调推进，回调一丢就停在那儿。
    // MultiTTS 是转发器，回调可能根本不透传，所以留一个巡检兜底：
    // 朗读开着、没被焦点暂停、在前台阅读页、没有表在等、十二秒没有任何进展 —— 就补发当前句
    private int healTries = 0;
    private final Runnable healTick = new Runnable() {
        @Override public void run() {
            ui.postDelayed(this, 3000);
            try {
                if (!autoPlay || focusPausedReading || bgRun) return;
                if (stack.isEmpty() || top() == null || !top().reader) return;
                if (com.mangjie.reader.speech.TtsWatchdog.watching()) return;
                long now = System.currentTimeMillis();
                if (lastProgAt <= 0 || now - lastProgAt < 12000) return;
                if (healTries >= 3) {
                    devLogLine("语音", "静默自愈连试 3 次仍无进展，停下等用户");
                    autoPlay = false;
                    readingNow = false;
                    toast("朗读好像停住了，已暂停，按继续可重试");
                    return;
                }
                healTries++;
                devLogLine("语音", "静默自愈：十二秒没进展也没表在等，补发第" + (top().cur + 1) + "句（第" + healTries + "次）");
                readingNow = true;
                speakSentence(top().cur);
            } catch (Throwable ignored) { }
        }
    };

    private void initSpeech() {
        // 启动时就把「阅读语音库」实例按上次保存的引擎建好，
        // 否则阅读会一直落到界面引擎（系统默认）上
        rebuildRdTts();
        tts = menuVoice.enginePkg == null
                ? new TextToSpeech(this, ttsInitListener)
                : new TextToSpeech(this, ttsInitListener, menuVoice.enginePkg);
        tts.setOnUtteranceProgressListener(utteranceListener);
    }

    private boolean ttsFixPending = false;
    private boolean ttsPrompted = false;

    /** 系统里没有能用的中文语音：明确弹屏内提示，给「去设置」 */
    private void promptNoTts() {
        toast("没有可用的中文语音");
        if (ttsPrompted) return;
        ttsPrompted = true;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (dialogOn || menuOn || inputOn) return;
                ttsFixPending = true;
                showDialog("系统里没有可用的中文语音。\n可以安装一个语音库（讯飞语记、天坦语音等），"
                        + "或到系统「语音合成」设置里启用一个引擎，再回来使用。",
                        new String[]{"稍后", "去设置"});
            }
        }, 1500);
    }

    private void openTtsSettings() {
        String[] actions = {"com.android.settings.TTS_SETTINGS",
                "android.settings.TTS_SETTINGS", Settings.ACTION_ACCESSIBILITY_SETTINGS};
        for (String a : actions) {
            try {
                startActivity(new Intent(a));
                return;
            } catch (Exception ignored) {}
        }
        try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        catch (Exception e) { toast("打不开系统设置"); }
    }

    private void applySpeechParams() {
        if (tts == null) return;
        tts.setSpeechRate(effRate());
        tts.setPitch(mapFactor(menuVoice.pitchP));
    }

    private boolean calcReaderOn() {
        // 两件事分开问、任一为真即认读屏在场：
        //  - 服务列表：多数 ROM 靠这个；但小米 Android 17 上读屏可以不出现在列表里（日志「注册的服务=无」）
        //  - 触摸探索：读屏真正在接管手指的直接信号
        // 这个布尔只决定「界面的话由谁来念」，判错最多漏一句导航词，不会打断正文。
        if (com.mangjie.reader.a11y.ReaderState.serviceOn(am)) return true;
        return com.mangjie.reader.a11y.ReaderState.touchExploreOn(am, false);
    }

    /** 触摸分流专用：实时问读屏是否在接管手指，查不到就退回上次状态，绝不因为查失败把触摸封死 */
    private boolean touchExploreNow() {
        return com.mangjie.reader.a11y.ReaderState.touchExploreOn(am, screenReaderOn);
    }

    private void refreshScreenReaderState() {
        boolean on = calcReaderOn();
        if (on == screenReaderOn) return;
        screenReaderOn = on;
        // 翻转时一个音节都不出。原来这里会 tts.stop() 或把整页重念一遍，实测代价很大：
        // 读屏只是暂时收手（用户拨一下开关），重念整页会把导航词和正文搅在一起；
        // 真关掉了也不补报——他按第一个键时 speakRow 自然会念出当前行。
        devLogLine("读屏", "状态翻转→" + (on ? "开" : "关"));
    }

    private void initAccessibilityWatch() {
        am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        refreshScreenReaderState();
        devLogLine("读屏", "注册的服务=" + com.mangjie.reader.a11y.ReaderState.serviceIds(am)
                + "　触摸探索=" + (com.mangjie.reader.a11y.ReaderState.touchExploreOn(am, false) ? "开" : "关"));

        // 触摸探索变化：点明「开 → 暂停 → 关」每一步都会走到这里，比盯无障碍总开关准。
        try {
            am.addTouchExplorationStateChangeListener(
                    new AccessibilityManager.TouchExplorationStateChangeListener() {
                        @Override public void onTouchExplorationStateChanged(boolean enabled) {
                            devLogLine("读屏", "触摸探索变化　" + (enabled ? "开" : "关"));
                            ui.postDelayed(new Runnable() {
                                @Override public void run() { refreshScreenReaderState(); }
                            }, 260);
                        }
                    });
        } catch (Throwable ignored) {}

        // 无障碍总开关变化（做了三年也没用上，先留着兜底）
        try {
            am.addAccessibilityStateChangeListener(
                    new AccessibilityManager.AccessibilityStateChangeListener() {
                        @Override public void onAccessibilityStateChanged(boolean enabled) {
                            ui.postDelayed(new Runnable() {
                                @Override public void run() { refreshScreenReaderState(); }
                            }, 260);
                        }
                    });
        } catch (Throwable ignored) {}

        a11yObserver = new ContentObserver(ui) {
            @Override public void onChange(boolean selfChange) {
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        refreshScreenReaderState();
                        // 读屏还在接管触摸时它自己会念当前行，我们不重复；暂停/关掉后才由我们念
                        if (!touchExploreNow() && top() != null && top().cur >= 0
                                && top().cur < top().items.size()) {
                            speak(top().items.get(top().cur).label);
                        }
                    }
                }, 260);
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, a11yObserver);
        // 读屏「服务」开关会写这一项，总开关不动的时候只有它变，缺了这条状态就永远刷不过来
        try {
            getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, a11yObserver);
        } catch (Throwable ignored) {}
    }

    /** 读一个列表项；「读列表项」关闭时静默 */
    /** 读一个列表项：只读条目本身，不读位置 */
    private void speakRow(String label) {
        // 读屏正在接管手指时当前行由它自己念，我们再念一遍就是双声；
        // 点明「暂停」后触摸探索已关、它不念了，这时得我们自己念出来
        if (touchExploreNow()) return;
        focus().sayRow(label);
    }

    // ---------------- 排队播报：进度、翻页这类提示不打断正文 ----------------

    private final List<String> queuedSpeak = new ArrayList<String>();
    private final Object queuedLock = new Object();

    /** 排队播报：正在念正文就等这句念完插播，不在念就直接说 */
    private void speakQueued(String text) {
        if (text == null || text.isEmpty()) return;
        if (!readingNow || rdTts == null || !speechOn) {
            if (speechOn) speak(text);
            return;
        }
        synchronized (queuedLock) {
            queuedSpeak.add(System.currentTimeMillis() + "\u0001" + text);
            while (queuedSpeak.size() > 3) queuedSpeak.remove(0);   // 攒多了丢最旧的
        }
        devLogLine("语音", "排队播报　" + shortText(text));
    }

    /** 取出并播一条队列提示；没有可播的就返回 false，由调用方继续翻句 */
    private boolean flushQueuedSpeak() {
        String item = null;
        synchronized (queuedLock) {
            long now = System.currentTimeMillis();
            while (!queuedSpeak.isEmpty()) {
                String s0 = queuedSpeak.remove(0);
                int p = s0.indexOf('\u0001');
                if (p <= 0) continue;
                long ts;
                try { ts = Long.parseLong(s0.substring(0, p)); } catch (Exception e) { continue; }
                if (now - ts > 5000) continue;      // 过期了：用户早换页了，别再说
                item = s0.substring(p + 1);
                break;
            }
        }
        if (item == null) return false;
        // 三级兜底：系统默认 → 界面引擎（判死后固定）→ 阅读引擎
        TextToSpeech rd;
        if (ttsStallCount >= 3 && sysReady && ttsSys != null) rd = ttsSys;
        else if (ttsForStallFallback || rdFallbackSticky) rd = tts;
        else rd = (rdReady && rdTts != null) ? rdTts : tts;
        if (rd == null) return false;
        if (rd != tts) { try { tts.stop(); } catch (Exception ignored) {} }
        boolean fallbackEngine = (rd == tts || rd == ttsSys);
        if (!fallbackEngine) {
            try { if (rdVoiceObj != null) rd.setVoice(rdVoiceObj); } catch (Exception ignored) {}
        } else if (rdVoiceObj != null) {
            devLogLine("语音", "已回退到备用引擎，跳过朗读发音人（那家的 voice 在别家无效）");
        }
        try {
            rd.setSpeechRate(rdEffRate());
            rd.setPitch(mapFactor(readerVoice.pitchP));
        } catch (Exception ignored) {}
        rd.speak(item, TextToSpeech.QUEUE_FLUSH, null, "qd");
        return true;
    }

    private void speak(String text) {
        // 后台一律不出界面语音：实测退后台后读屏状态抖动仍会把标题和当前句念出来
        if (bgRun) return;
        if (devLog) {     // 记下是谁让它出声的：同一句被连播三遍时靠这行定位
            StackTraceElement[] st = new Throwable().getStackTrace();
            if (st.length > 2) devLogLine("语音", "speak 来源 " + st[2].getMethodName()
                    + "@" + st[2].getLineNumber() + "　" + shortText(text));
        }
        // 朗读进行中：界面提示一律不出声。点明通道念正文时，本机 TTS 一开口就是两股声音叠
        // 在一起（日志里 21:42:56 那句「已暂停朗读」就是这么挤进来的）。菜单、弹窗、输入框里照常念。
        if (readingNow && autoPlay && !dialogOn && !menuOn && !inputOn) {
            devLogLine("语音", "朗读中静默：" + shortText(text));
            return;
        }
        // 走点明通道时，界面提示用它的「插播不打断当前朗读」接口：正文一个字都不会被掐断
        if ((com.mangjie.reader.speech.DianmingVoice.isSpeaking()
                || com.mangjie.reader.speech.DianmingVoice.isConnected())
                && com.mangjie.reader.speech.DianmingVoice.speakAside(text)) {
            return;
        }
        // 正在读书时来了界面提示：先停正文，避免两个语音库同时发声
        if (readingNow && rdTts != null) {
            try { rdTts.stop(); } catch (Exception ignored) {}
            resumeReadingAfterSpeak = autoPlay;
            readingNow = false;
            armResumeGuard();                  // 界面语音的 onDone 可能不回来，兜底续读
        }
        // 语音总闸关着：读屏开着就交给它念一句，否则静默
        if (!speechOn) {
            if (screenReaderOn) announceToReader(text);
            return;
        }
        if (!ttsReady || tts == null) {
            // 本机语音引擎没起来：退回读屏通道，开了读屏至少还能听到这句
            announceToReader(text);
            return;
        }
        // 读屏开着也用自己的引擎念。点明这类第三方读屏只念焦点控件、不念 announce，
        // 全交给它等于没声；行与页面由 speakRow / 页面入口在读屏开着时自行跳过，这里只出结果类提示。
        try { if (voiceObj != null) tts.setVoice(voiceObj); } catch (Exception ignored) {}
        tts.setSpeechRate(effRate());   // 界面提示始终用语音提示里的语速语调（含加倍）
        devLogLine("语音", "播报 " + shortText(text) + "　倍率=" + String.format(Locale.US, "%.2f", effRate())
                + "　界面引擎=" + (menuVoice.enginePkg == null ? "系统默认" : menuVoice.enginePkg)
                + "　朗读引擎=" + (readerVoice.enginePkg == null ? "系统默认" : readerVoice.enginePkg) + "　发音人=" + (menuVoice.voiceName == null ? "默认" : menuVoice.voiceName)
                + (screenReaderOn ? "　读屏开着，本机语音念" : ""));
        tts.setPitch(mapFactor(menuVoice.pitchP));
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "s918");
    }

    /** 交给读屏念一句（本机语音不可用时的兜底通道） */
    private void announceToReader(String text) {
        try {
            getWindow().getDecorView().announceForAccessibility(text);
        } catch (Exception ignored) {}
    }
}
