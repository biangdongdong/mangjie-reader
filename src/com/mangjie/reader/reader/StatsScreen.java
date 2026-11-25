package com.mangjie.reader.reader;

import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;

import com.mangjie.reader.model.Item;
import com.mangjie.reader.model.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 阅读统计：统计页本身，以及那个 30 秒一跳的计时器。
 *
 * 从 MainActivity 搬出来的（滚动式模块化，2026-11-18）。计时口径没变：只在阅读页、
 * 且正在自动朗读或这轮朗读没停的时候才累加，读屏不算。
 */
public class StatsScreen {

    public interface Host {
        SharedPreferences prefs();
        android.os.Handler ui();
        /** 现在是不是「在阅读页 + 正在读」这两条同时成立 */
        boolean isReadingNow();
        /** 当前这本书的路径，用来记这本书读了多少分钟 */
        String currentBookPath();
    }

    private final Host h;
    private Handler timer = null;
    private int sec = 0;

    public StatsScreen(Host h) {
        this.h = h;
    }

    /** 统计页 */
    public Screen recordsScreen() {
        List<Item> items = new ArrayList<>();
        SharedPreferences p = h.prefs();
        items.add(new Item("今天  " + ReadStats.hm(ReadStats.todayMin(p))));
        items.add(new Item("累计  " + ReadStats.hm(ReadStats.totalMin(p))));
        String bp = h.currentBookPath();
        if (bp != null && !bp.isEmpty()) {
            items.add(new Item("这本书  " + ReadStats.hm(ReadStats.bookMin(p, bp))));
        }
        List<String> d7 = ReadStats.d7Lines(p);
        if (d7.isEmpty()) {
            items.add(new Item("近七天  还没有记录"));
        } else {
            for (String line : d7) items.add(new Item("近七天  " + line));
        }
        items.add(new Item("清空统计", "stats:clear"));
        return new Screen("阅读统计", items).k("records");
    }

    /** 30 秒一跳；已经在跑就不再起第二个 */
    public void startTimer() {
        if (timer != null) return;
        timer = new Handler(Looper.getMainLooper());
        timer.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (h.isReadingNow()) sec += 30;
                    while (sec >= 60) {
                        sec -= 60;
                        ReadStats.addMinute(h.prefs(), h.currentBookPath());
                    }
                } catch (Throwable ignored) {
                }
                if (timer != null) timer.postDelayed(this, 30000);
            }
        }, 30000);
    }

    /** 退出或换进程时把回调清干净 */
    public void stopTimer() {
        if (timer != null) {
            timer.removeCallbacksAndMessages(null);
            timer = null;
        }
        sec = 0;
    }
}
