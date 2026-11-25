package com.mangjie.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 朗读保活用的前台服务：把进程钉在前台，熄屏/切后台不回收，
 * 同时给通知栏一组按钮（上一章 / 上一句 / 播放暂停 / 下一句 / 下一章）。
 *
 * 朗读本身仍在 MainActivity 里（TTS 实例在那边），这个服务只做两件事：
 * 1. startForeground 保活；2. 把通知栏按钮点下的命令转发给 MainActivity。
 */
public class ReadService extends Service {

    public static final String EXTRA_CMD = "cmd";
    public static final String CMD_SHOW = "show";
    public static final String CMD_TOGGLE = "toggle";
    public static final String CMD_PREV = "prev";
    public static final String CMD_NEXT = "next";
    public static final String CMD_PREV_CH = "prevch";
    public static final String CMD_NEXT_CH = "nextch";
    public static final String CMD_STOP = "stop";

    private static final String CHANNEL_ID = "blind_read_ctrl";
    private static final int NOTIF_ID = 1001;

    /** 谁在听命令（MainActivity 注册自己） */
    public interface Ctl { void onReadCmd(String cmd); }

    private static Ctl ctl;

    public static void setCtl(Ctl c) { ctl = c; }

    /** 息屏长读要 CPU 一直醒着，不然 TTS 和定时器会断断续续 */
    private android.os.PowerManager.WakeLock wl;

    public ReadService() {}

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        ensureChannel();
        startForeground(NOTIF_ID, buildNotification("正在朗读"));
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "mangjie:read");
                wl.setReferenceCounted(false);
                wl.acquire();
            }
        } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) {}
        wl = null;
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = intent == null ? null : intent.getStringExtra(EXTRA_CMD);
        if (CMD_STOP.equals(cmd)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (cmd != null && !CMD_SHOW.equals(cmd)) {
            if (ctl != null) ctl.onReadCmd(cmd);
            updateNotification(cmd);
        }
        return START_NOT_STICKY;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "朗读控制", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent pending(String cmd) {
        Intent i = new Intent(this, ReadService.class);
        i.putExtra(EXTRA_CMD, cmd);
        int req = cmd.hashCode();
        return PendingIntent.getService(this, req, i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        int icon = getApplicationInfo().icon;
        b.setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_media_play)
                .setContentTitle("盲界阅读")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(android.R.drawable.ic_media_previous, "上一章", pending(CMD_PREV_CH))
                .addAction(android.R.drawable.ic_media_previous, "上一句", pending(CMD_PREV))
                .addAction(android.R.drawable.ic_media_play, "播放暂停", pending(CMD_TOGGLE))
                .addAction(android.R.drawable.ic_media_next, "下一句", pending(CMD_NEXT))
                .addAction(android.R.drawable.ic_media_next, "下一章", pending(CMD_NEXT_CH));
        if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_PUBLIC);
        return b.build();
    }

    private void updateNotification(String cmd) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String text = "正在朗读";
        if (CMD_PREV.equals(cmd)) text = "上一句";
        else if (CMD_NEXT.equals(cmd)) text = "下一句";
        else if (CMD_PREV_CH.equals(cmd)) text = "上一章";
        else if (CMD_NEXT_CH.equals(cmd)) text = "下一章";
        else if (CMD_TOGGLE.equals(cmd)) text = "播放暂停";
        try { nm.notify(NOTIF_ID, buildNotification(text)); } catch (Throwable ignored) {}
    }

    /** 朗读/暂停状态反映到通知文字上（服务没起来就什么都不做） */
    public static void updateText(Context ctx, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(ctx, CHANNEL_ID) : new Notification.Builder(ctx);
            int icon = ctx.getApplicationInfo().icon;
            b.setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_media_play)
                    .setContentTitle("盲界阅读")
                    .setContentText(text)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .addAction(android.R.drawable.ic_media_previous, "上一章", remote(ctx, CMD_PREV_CH))
                    .addAction(android.R.drawable.ic_media_previous, "上一句", remote(ctx, CMD_PREV))
                    .addAction(android.R.drawable.ic_media_play, "播放暂停", remote(ctx, CMD_TOGGLE))
                    .addAction(android.R.drawable.ic_media_next, "下一句", remote(ctx, CMD_NEXT))
                    .addAction(android.R.drawable.ic_media_next, "下一章", remote(ctx, CMD_NEXT_CH))
                    .setVisibility(Notification.VISIBILITY_PUBLIC);
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent remote(Context ctx, String cmd) {
        Intent i = new Intent(ctx, ReadService.class);
        i.putExtra(EXTRA_CMD, cmd);
        return PendingIntent.getService(ctx, cmd.hashCode(), i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ---------------------------------------------------------------- 启动 / 停止
    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, ReadService.class);
            i.putExtra(EXTRA_CMD, CMD_SHOW);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, ReadService.class)); } catch (Throwable ignored) {}
    }
}
