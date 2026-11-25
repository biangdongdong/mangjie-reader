package com.mangjie.reader.net;

import android.content.SharedPreferences;
import android.os.Handler;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统书源同步：从自家分发渠道的「书源」目录拉书源，落到本地书源目录里。
 *
 * 服务器上两种放法都认：
 *   1. 目录里有「清单.txt」—— 按清单逐个拉，每行「文件名|字节数」，字节数可省；
 *   2. 没有清单 —— 拉目录里的「系统书源.json」总包。
 *
 * 为什么用清单而不是列目录：Android 的 HttpURLConnection 只允许
 * OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH，发 PROPFIND 会直接抛 ProtocolException
 * （1.0.71 就是这么失败的），所以列目录这条路走不通，改成读清单。
 *
 * 落盘文件名统一带「系统」前缀（总包就是「系统书源.json」），
 * 加载书源时同 bookSourceUrl 以系统文件为准（见 NetBookController.dedupBySysPriority）。
 * 本地和服务器用「文件名 + 字节数」比对，没变的不重新下载，避免每次启动白拉一遍。
 */
public final class SourceSync {

    public static final String BUNDLE = "系统书源.json";
    public static final String LIST = "清单.txt";
    public static final String PREFIX = "系统-";
    private static final String PREF_KEY = "srcSyncState";

    private SourceSync() {}

    public interface Done {
        /** changedFiles 本次实际写入的文件数，totalSources 这些文件里认出来的书源条数 */
        void onDone(int changedFiles, int totalSources, String note);

        /** 本次清掉的旧系统副本个数（开关关着就是 0） */
        default void onCleaned(int removed) {}
    }

    /**
     * 后台同步，结果回到主线程。onDone 一定回调一次，note 为空表示正常。
     * 不改用户自己的书源文件，只写「系统」前缀的那几个。
     */
    public static void sync(final File bookSrcDir, final SharedPreferences prefs,
                            final Handler ui, final Done done) {
        new Thread(new Runnable() {
            @Override public void run() {
                int changed = 0;
                int total = 0;
                int cleaned = 0;
                String note = "";
                try {
                    final String sub = java.net.URLEncoder.encode("书源", "UTF-8") + "/";
                    // 新目录优先，读不到再退旧根目录（服务器搬家过渡期两边都有）
                    String baseNew = DavClient.newSub(sub);
                    String baseOld = "/mangjie/" + sub;
                    String state = prefs.getString(PREF_KEY, "");
                    StringBuilder newState = new StringBuilder();

                    if (bookSrcDir == null) throw new IllegalStateException("书源目录不可用");
                    if (!bookSrcDir.exists()) bookSrcDir.mkdirs();

                    List<String[]> todo = new ArrayList<>();
                    // 本次"确认存在"的系统文件：清单里点名的 + 总包。清理时只保留这些，其余系统副本删掉
                    java.util.LinkedHashSet<String> keep = new java.util.LinkedHashSet<>();
                    boolean bundleWritten = false;
                    long bundleLen = -1;
                    String listEnc = java.net.URLEncoder.encode(LIST, "UTF-8");
                    String base = baseNew;
                    String list = DavClient.getApp(baseNew + listEnc);
                    if (list == null || list.trim().isEmpty()) {
                        base = baseOld;                     // 新目录没有清单，退旧位置再读一次
                        list = DavClient.getApp(baseOld + listEnc);
                    }
                    if (list != null && !list.trim().isEmpty()) {
                        // 有清单：按清单逐个拉
                        for (String line : list.split("\n")) {
                            String t = line.trim();
                            if (t.isEmpty() || t.startsWith("#")) continue;
                            String name;
                            long size = -1;
                            int bar = t.lastIndexOf('|');
                            if (bar > 0) {
                                name = t.substring(0, bar).trim();
                                try { size = Long.parseLong(t.substring(bar + 1).trim()); } catch (Exception ignored) {}
                            } else {
                                name = t;
                            }
                            String low = name.toLowerCase();
                            if (name.isEmpty()) continue;
                            if (!low.endsWith(".json") && !low.endsWith(".txt")) continue;
                            if (LIST.equals(name)) continue;
                            todo.add(new String[]{name, String.valueOf(size)});
                        }
                        if (todo.isEmpty()) note = "清单里没有可用条目（每行写 文件名|字节数）";
                    }
                    if (todo.isEmpty()) {
                        // 没有清单（或清单里没有可用条目）：试总包
                        String bundleEnc = java.net.URLEncoder.encode(BUNDLE, "UTF-8");
                        String bundle = DavClient.getApp(baseNew + bundleEnc);
                        if (bundle == null || bundle.trim().isEmpty())
                            bundle = DavClient.getApp(baseOld + bundleEnc);
                        if (bundle != null && !bundle.trim().isEmpty()) {
                            keep.add(BUNDLE);
                            java.io.File bf = new File(bookSrcDir, BUNDLE);
                            bundleLen = writeFile(bf, bundle);
                            changed++;
                            bundleWritten = true;
                            note = "";
                            todo.add(new String[]{BUNDLE, String.valueOf(bundle.length())});
                        } else if (note.isEmpty()) {
                            note = "服务器「书源」目录里既没有 " + LIST + "，也没有 " + BUNDLE;
                        }
                    }

                    for (String[] f : todo) {
                        String name = f[0];
                        long size = -1;
                        try { size = Long.parseLong(f[1]); } catch (Exception ignored) {}
                        String localName = BUNDLE.equals(name) ? BUNDLE : (PREFIX + name);
                        keep.add(localName);
                        // 记号三段：本地文件名|服务器清单字节数|本地实际写入字节数。
                        // 以前只比「文件名|清单字节数」，可清单记的是服务器原始字节，
                        // 我们落盘是先解码文本再按 UTF-8 编码，字节数常差几字节，
                        // 判断永远不成立，于是每次启动都把 6MB 总包白拉一遍。
                        String key = localName + "|" + size;
                        File out = new File(bookSrcDir, localName);
                        if (BUNDLE.equals(name) && bundleWritten) {
                            total += countSources(out);      // 总包刚写过
                            newState.append(stateEntry(key, bundleLen));
                            continue;
                        }
                        long marked = markedLen(state, key);
                        if (marked >= 0 && out.exists() && out.length() == marked) {
                            total += countSources(out);      // 没变，用本地那份
                            newState.append(stateEntry(key, marked));
                            continue;
                        }
                        String text = DavClient.getApp(base + java.net.URLEncoder.encode(name, "UTF-8"));
                        if (text == null || text.trim().isEmpty()) {
                            // 这次拉不到就不清它的记号，下次还会再试
                            if (marked >= 0) newState.append(stateEntry(key, marked));
                            continue;
                        }
                        long wrote = writeFile(out, text);
                        changed++;
                        total += countSources(out);
                        newState.append(stateEntry(key, wrote));
                    }
                    prefs.edit().putString(PREF_KEY, newState.toString()).apply();
                    // 更新下载成功之后清空本地目录里"上次更新"留下的旧系统副本，
                    // 免得新旧两份同时在列表里打架。用户自己导入的文件不碰。
                    // keep 为空说明这次一个条目都没认出来（服务器读不到），那就一个都不删。
                    if (!keep.isEmpty() && prefs.getBoolean("srcSyncClean", true)) {
                        cleaned = cleanStaleSys(bookSrcDir, keep);
                    }
                } catch (Throwable t) {
                    note = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : "：" + t.getMessage());
                }
                final int c = changed;
                final int tot = total;
                final int cl = cleaned;
                final String nt = note;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (cl > 0) done.onCleaned(cl);
                        done.onDone(c, tot, nt);
                    }
                });
            }
        }, "src-sync").start();
    }

    /**
     * 删掉本地书源目录里不属于本次清单的系统副本（系统书源.json / 系统-*.json）。
     * 只碰「系统」这一类文件，用户自己导入的源一个不动。
     */
    private static int cleanStaleSys(File dir, java.util.Set<String> keep) {
        if (dir == null) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            String nm = f.getName();
            boolean isSys = nm.equals(BUNDLE) || nm.startsWith(PREFIX);
            if (!isSys || keep.contains(nm)) continue;
            if (f.delete()) n++;
        }
        return n;
    }

    /** 写文件，返回实际写入的字节数（落盘字节与服务器原始字节常有几字节差，判断"已是最新"要用这个） */
    private static long writeFile(File out, String text) throws Exception {
        byte[] bs = text.getBytes("UTF-8");
        FileOutputStream fo = new FileOutputStream(out);
        fo.write(bs);
        fo.close();
        return bs.length;
    }

    /** 记号一条：「文件名|清单字节数|本地实际字节数」，前面统一带分号，便于直接拼 */
    private static String stateEntry(String key, long localLen) {
        return ";" + key + "|" + localLen;
    }

    /** 从上次的记号里取这个文件名对应的本地实际字节数，没有就 -1 */
    private static long markedLen(String state, String key) {
        if (state == null || state.isEmpty()) return -1;
        for (String s : state.split(";")) {
            if (s.isEmpty()) continue;
            int bar = s.lastIndexOf('|');
            if (bar <= 0) continue;
            if (!s.substring(0, bar).equals(key)) continue;
            try { return Long.parseLong(s.substring(bar + 1).trim()); } catch (Exception ignored) { return -1; }
        }
        return -1;
    }

    private static boolean containsMark(String state, String mark) {
        if (state == null || state.isEmpty()) return false;
        for (String s : state.split(";")) {
            if (s.equals(mark)) return true;
        }
        return false;
    }

    /** 数一个书源文件里有多少条，格式认不出就按 0 记 */
    private static int countSources(File f) {
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 8 * 1024 * 1024)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r <= 0) break;
                off += r;
            }
            in.close();
            String t = new String(buf, 0, off, "UTF-8").trim();
            if (t.startsWith("[")) return new org.json.JSONArray(t).length();
            org.json.JSONObject o = new org.json.JSONObject(t);
            if (o.has("bookSourceName")) return 1;
            org.json.JSONArray arr = o.optJSONArray("sources");
            return arr == null ? 0 : arr.length();
        } catch (Throwable t) {
            return 0;
        }
    }
}
