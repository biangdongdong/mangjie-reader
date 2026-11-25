package com.mangjie.reader.reader;

import com.mangjie.reader.model.Item;
import com.mangjie.reader.model.Screen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 摘抄与整本导出：摘抄页、导出 Markdown、把当前这本书整本导成 TXT / EPUB。
 *
 * 从 MainActivity 搬出来的（滚动式模块化，2026-11-18）。抓章节这件事本身还得靠网书控制器，
 * 所以这里用 Host 把「当前这本书」的目录、书名、抓正文能力要过来，不直接摸控制器。
 */
public class ExportScreen {

    public interface Host {
        String dataDir();
        void speak(String msg);
        void toast(String msg);
        void log(String tag, String msg);
        android.os.Handler ui();
        void render(boolean reset);
        /** 网上收藏的原始串（书名\u0001网址 每行一条），导 md 时一起带上 */
        String nbFavRaw();
        /** 当前这本书有没有目录；没有就不许导出整本 */
        boolean hasToc();
        List<String[]> chapters();
        String bookName();
        String bookAuthor();
        /** 按章节地址抓正文，抓不到返回空串 */
        String fetchChapter(String url);
    }

    private final Host h;

    public ExportScreen(Host h) {
        this.h = h;
    }

    /** 摘抄页 */
    public Screen excerptScreen() {
        int n = MdExport.parseExcerpts(readExcerptRaw()).size();
        List<Item> items = new ArrayList<>();
        items.add(new Item("已有摘抄  " + n + " 条"));
        items.add(new Item("导出为 Markdown", "ex:export"));
        items.add(new Item("整本导出为 TXT（当前这本书）", "ex:booktxt"));
        items.add(new Item("整本导出为 EPUB（当前这本书）", "ex:bookepub"));
        items.add(new Item("清空摘抄", "ex:clear"));
        items.add(new Item("导出目录  " + com.mangjie.reader.App.exportDir().getAbsolutePath()));
        return new Screen("摘抄导出", items);
    }

    /** 读 摘抄.txt，UTF-8 不成再按 GB18030 试一次 */
    public String readExcerptRaw() {
        try {
            File f = new File(h.dataDir(), "摘抄.txt");
            if (!f.exists()) return "";
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            String t = new String(b, "UTF-8");
            if (t.indexOf('\uFFFD') >= 0) t = new String(b, "GB18030");
            return t;
        } catch (Exception e) {
            h.log("摘抄", "读摘抄失败：" + e.getMessage());
            return "";
        }
    }

    /**
     * 整本导出：按当前目录逐章抓正文，TXT 或 EPUB 落 盲界阅读/导出。
     * 抓不到的章跳过（EPUB 里不写空页），最后报实际写进去几章。
     */
    public void exportWholeBook(final boolean epub) {
        if (!h.hasToc()) {
            h.speak("先打开一本书的目录，再导出整本");
            h.toast("没有目录");
            return;
        }
        final ExportBook.Fetcher f = new ExportBook.Fetcher() {
            @Override public String fetch(String url) {
                try {
                    return h.fetchChapter(url);
                } catch (Throwable t) { return ""; }
            }
        };
        runExport(epub, f, h.bookName(), h.bookAuthor());
    }

    private void runExport(final boolean epub, final ExportBook.Fetcher f,
                           final String title, final String author) {
        final List<String[]> chs = new ArrayList<>(h.chapters());
        // 整本导出是给用户拿走的，一律落公有 /sdcard/盲界阅读导出；没有权限时 App 会自动退回私有
        final File dir = com.mangjie.reader.App.exportDir();
        if (!dir.exists()) dir.mkdirs();
        String safeTitle = (title == null || title.trim().isEmpty() ? "未命名" : title.trim())
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        final File out = new File(dir, "整本-" + safeTitle + (epub ? ".epub" : ".txt"));
        h.speak(epub ? "开始导出 EPUB，" + chs.size() + " 章，边抓边写"
                     : "开始导出 TXT，" + chs.size() + " 章，边抓边写");
        h.log("导出", "整本导出开始 格式=" + (epub ? "epub" : "txt") + " 章=" + chs.size());
        new Thread(new Runnable() {
            @Override public void run() {
                ExportBook.Progress cb = new ExportBook.Progress() {
                    private int lastSay = 0;
                    @Override public void step(int done, int total, String ch) {
                        if (done - lastSay >= 10 || done == total) {
                            lastSay = done;
                            final int d = done;
                            final int t = total;
                            h.ui().post(new Runnable() { @Override public void run() {
                                h.speak("导出 " + d + "/" + t);
                            } });
                        }
                    }
                };
                final int ok;
                if (epub) {
                    ok = ExportBook.exportEpub(out, title, author, chs, f, cb);
                } else {
                    ok = ExportBook.exportTxt(out, title, author, chs, f, cb);
                }
                h.log("导出", "整本导出结束 写进 " + ok + "/" + chs.size() + " 章 路径=" + out.getAbsolutePath());
                h.ui().post(new Runnable() { @Override public void run() {
                    if (ok <= 0) {
                        h.speak("整本导出失败，一章都没抓到");
                        h.toast("导出失败");
                    } else {
                        h.speak("导出完成，写进 " + ok + " 章，文件在 导出的 " + out.getName());
                        h.toast("已导出：" + out.getName());
                    }
                    h.render(true);
                } });
            }
        }, "export-book").start();
    }

    /** 摘抄 + 网上收藏 合并成一份 md，存 盲界阅读/导出 */
    public void exportExcerpts() {
        try {
            List<String[]> entries = MdExport.parseExcerpts(readExcerptRaw());
            List<String> favs = new ArrayList<>();
            String nbFav = h.nbFavRaw();
            if (nbFav != null && !nbFav.isEmpty()) {
                for (String line : nbFav.split("\n")) {
                    String[] p = line.split("\u0001");
                    if (p.length >= 2) favs.add(p[0] + "　" + p[1]);
                }
            }
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date());
            File dir = com.mangjie.reader.App.exportDir();
            if (!dir.exists()) dir.mkdirs();
            String fileStamp = stamp.replace(":", "").replace(" ", "-");
            File out = new File(dir, "摘抄-" + fileStamp + ".md");
            java.io.FileWriter fw = new java.io.FileWriter(out, false);
            fw.write(MdExport.buildMd(entries, stamp, favs));
            fw.close();
            h.log("摘抄", "导出完成 条=" + entries.size() + " 收藏=" + favs.size() + " 路径=" + out.getAbsolutePath());
            h.speak("已导出 " + entries.size() + " 条摘抄到 " + out.getAbsolutePath());
            h.toast("已导出：" + out.getName());
        } catch (Exception e) {
            h.log("摘抄", "导出失败：" + e.getMessage());
            h.speak("导出失败");
            h.toast("导出失败");
        }
    }
}
