package com.mangjie.reader.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 备份与恢复：一个 json 装走所有能迁移的东西。
 *
 * 内容 = 全部设置项（prefs）＋ 配置目录里的 json/txt（书源、订阅源、目录规则、净化、字体列表说明）。
 * 正文与阅读进度：进度本身存在 prefs 里（pos:<路径>），所以跟着走；
 * 书文件太大不打包，换机后路径仍然指向存储卡，通常还在。
 */
public final class Backup {

    public static final String DIR_NAME = "备份";

    private Backup() {}

    /** 要打包的配置子目录 */
    private static final String[] SUB_DIRS = {"书源", "订阅源", "目录规则", "净化"};

    /** 这些键是密码或凭据，备份包里一律不带 */
    private static boolean isSecretKey(String k) {
        if (k == null) return false;
        String l = k.toLowerCase(java.util.Locale.US);
        return l.equals("davpass") || l.contains("password") || l.contains("passwd")
                || l.contains("token") || l.contains("secret") || l.contains("apikey");
    }

    public static File dirIn(File dataDir) {
        File d = new File(dataDir, DIR_NAME);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date());
    }

    /** 生成备份 json 文本 */
    public static String dump(Context ctx, File dataDir, SharedPreferences prefs) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "盲界阅读");
            root.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
            // 1. 设置项
            JSONObject p = new JSONObject();
            if (prefs != null) {
                for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                    Object v = e.getValue();
                    if (v == null) continue;
                    if (isSecretKey(e.getKey())) continue;    // 密码类不进备份包
                    p.put(e.getKey(), String.valueOf(v));
                }
            }
            root.put("prefs", p);
            // 2. 配置文件
            JSONObject files = new JSONObject();
            int count = 0;
            for (String sub : SUB_DIRS) {
                File d = new File(dataDir, sub);
                if (!d.isDirectory()) continue;
                File[] kids = d.listFiles();
                if (kids == null) continue;
                for (File f : kids) {
                    if (!f.isFile()) continue;
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (!(n.endsWith(".json") || n.endsWith(".txt"))) continue;
                    String text = readAll(f);
                    if (text == null) continue;
                    files.put(sub + "/" + f.getName(), text);
                    count++;
                }
            }
            root.put("files", files);
            root.put("fileCount", count);
        } catch (Throwable ignored) {
        }
        return root.toString();
    }

    /** 把备份写到存储卡「备份」目录，返回文件 */
    public static File save(File dataDir, String json) throws Exception {
        File f = new File(dirIn(dataDir), "备份-" + stamp() + ".json");
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        w.write(json);
        w.close();
        return f;
    }

    /** 最近一个备份文件；没有返回 null */
    public static File latest(File dataDir) {
        File d = dirIn(dataDir);
        File[] kids = d.listFiles();
        if (kids == null) return null;
        File best = null;
        for (File f : kids) {
            if (!f.isFile() || !f.getName().startsWith("备份-")) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    /** 恢复：返回「设置项 N 条，文件 M 个」的说明；失败抛异常 */
    public static String restore(Context ctx, File dataDir, SharedPreferences prefs, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        int prefN = 0, fileN = 0;
        JSONObject p = root.optJSONObject("prefs");
        if (p != null && prefs != null) {
            SharedPreferences.Editor ed = prefs.edit();
            java.util.Iterator<String> it = p.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = p.optString(k, "");
                if (!assign(ed, k, v)) continue;
                prefN++;
            }
            ed.apply();
        }
        JSONObject files = root.optJSONObject("files");
        if (files != null) {
            java.util.Iterator<String> it = files.keys();
            List<String> failed = new ArrayList<String>();
            while (it.hasNext()) {
                String rel = it.next();
                if (rel.contains("..")) continue;
                File out = new File(dataDir, rel);
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try {
                    OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
                    w.write(files.optString(rel, ""));
                    w.close();
                    fileN++;
                } catch (Throwable e) {
                    failed.add(rel);
                }
            }
            if (!failed.isEmpty()) {
                return "设置 " + prefN + " 条，文件 " + fileN + " 个，失败 " + failed.size() + " 个";
            }
        }
        return "设置 " + prefN + " 条，文件 " + fileN + " 个";
    }

    /** 按值的字面形状还原类型；bool/int 解析不了就当字符串 */
    private static boolean assign(SharedPreferences.Editor ed, String k, String v) {
        if ("true".equals(v) || "false".equals(v)) { ed.putBoolean(k, "true".equals(v)); return true; }
        try { ed.putInt(k, Integer.parseInt(v)); return true; } catch (Throwable ignored) {}
        try { ed.putLong(k, Long.parseLong(v)); return true; } catch (Throwable ignored) {}
        ed.putString(k, v);
        return true;
    }

    private static String readAll(File f) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }
}
