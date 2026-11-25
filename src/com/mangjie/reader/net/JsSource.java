package com.mangjie.reader.net;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 书源对象在 JS 里的替身。
 *
 * Legado 的规则里可以直接写 source.getKey()、source.getVariable("x")、source.bookSourceName，
 * 一部分源（比如番茄系的「优+++」系列）把发现分类、翻页状态全存在源变量里，缺了这几个方法就 ReferenceError。
 * 这里按 Bean 的规矩给出同名 getter，Rhino 会自动把它当属性/方法用。
 */
public final class JsSource {

    /** 每个源一份变量表（对应 Legado 的 putVariable/getVariable），进程内保存，按 bookSourceUrl 区分 */
    private static final Map<String, Map<String, String>> VARS = new HashMap<String, Map<String, String>>();

    private final JSONObject src;
    private final Map<String, String> vars;

    public JsSource(JSONObject src) {
        this.src = src == null ? new JSONObject() : src;
        this.vars = varsFor(getKey());
    }

    private static synchronized Map<String, String> varsFor(String url) {
        Map<String, String> m = VARS.get(url);
        if (m == null) { m = new HashMap<String, String>(); VARS.put(url, m); }
        return m;
    }

    public String getKey() { return src.optString("bookSourceUrl", ""); }

    public String getBookSourceUrl() { return getKey(); }

    public String getBookSourceName() { return src.optString("bookSourceName", ""); }

    public String getBookSourceGroup() { return src.optString("bookSourceGroup", ""); }

    public String getBookSourceComment() { return src.optString("bookSourceComment", ""); }

    public String getJsLib() { return src.optString("jsLib", ""); }

    public String getExploreUrl() { return src.optString("exploreUrl", ""); }

    /** 整串变量的占位键（Legado 的 getVariable 无参时返回整串） */
    private static final String BLOB = "__blob__";

    // ---- 对齐 Legado 的 BaseSource 变量三件套：整串存取 ----

    /** Legado 的 getVariable()：没有就返回空串（不是 null） */
    public String getVariable() {
        String v = vars.get(BLOB);
        return v == null ? "" : v;
    }

    /** Legado 的 putVariable(String)：整串覆盖；传 null 就删 */
    public void putVariable(String variable) {
        if (variable == null) vars.remove(BLOB);
        else vars.put(BLOB, variable);
    }

    /** Legado 的 setVariable(String)：和 putVariable 同效 */
    public void setVariable(String variable) {
        putVariable(variable);
    }

    // ---- 键值接口（Legado 的 get(key) / put(key, value)） ----

    public String get(String key) {
        if (key == null) return "";
        String v = vars.get("k:" + key);
        return v == null ? "" : v;
    }

    public String put(String key, String value) {
        if (key != null && value != null) vars.put("k:" + key, value);
        return value;
    }

    // ---- 兼容我们的老写法（部分规则会带参数用） ----

    /** 没存过返回 null，规则里通常写 if(!v) 兜底 */
    public String getVariable(String key) {
        if (key == null) return null;
        return vars.get("k:" + key);
    }

    public void putVariable(String key, String value) {
        if (key == null) return;
        if (value == null) vars.remove("k:" + key);
        else vars.put("k:" + key, value);
    }

    // ---- 源上其他常用属性（Legado 里是 Kotlin 属性，JS 直接 source.xxx） ----

    public int getBookSourceType() {
        try { return src.optInt("bookSourceType", 0); } catch (Throwable t) { return 0; }
    }

    public boolean getEnabled() { return src.optBoolean("enabled", true); }

    public String getSearchUrl() { return src.optString("searchUrl", ""); }

    public String getBookUrlPattern() { return src.optString("bookUrlPattern", ""); }

    public String getHeader() { return src.optString("header", ""); }

    public String getConcurrentRate() { return src.optString("concurrentRate", ""); }

    // ---- 登录信息（Legado BaseSource 同名方法，源脚本里 {{fqFilter(source.getLoginInfo())}} 会用） ----
    public String getLoginInfo() { return src.optString("loginInfo", ""); }
    public String getLoginJs() { return src.optString("loginJs", ""); }
    public String getLoginUrl() { return src.optString("loginUrl", ""); }
    public String getLoginHeader() { return src.optString("loginHeader", ""); }
    public java.util.Map<String, String> getLoginInfoMap() {
        java.util.Map<String, String> m = new java.util.HashMap<String, String>();
        String t = src.optString("loginInfo", "").trim();
        if (t.startsWith("{")) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(t);
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) { String k = it.next(); m.put(k, o.optString(k, "")); }
            } catch (Exception ignored) { }
        }
        return m;
    }
    public java.util.Map<String, String> getLoginHeaderMap() {
        java.util.Map<String, String> m = new java.util.HashMap<String, String>();
        String t = src.optString("loginHeader", "").trim();
        if (t.startsWith("{")) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(t);
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) { String k = it.next(); m.put(k, o.optString(k, "")); }
            } catch (Exception ignored) { }
        }
        return m;
    }
    public String getTag() { return src.optString("bookSourceGroup", ""); }
    public String getSortUrl() { return src.optString("sortUrl", ""); }
    public boolean isEnabled() { return src.optBoolean("enabled", true); }
    public String getEnabledExplore() { return String.valueOf(src.optBoolean("enabledExplore", true)); }

    /** 刷新发现分类：我们这边对应清掉分类表缓存，交给上层做，这里只占位 */
    public void refreshExplore() { }
}
