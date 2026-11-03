package com.tree0312.bhbench;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 采样服务。
 *
 * 跑在前台，带常驻通知，是为了让系统知道"这个进程正在做重要的事"，
 * 不被 LMK 杀掉、也不被国产 ROM 的省电策略清掉。一次放电测试要几小时，
 * 中途被杀就等于白测。
 *
 * 数据源只用 BatteryManager 和电池广播，全部是公开 API，不需要 root、
 * 不需要 adb、不需要电脑。这一点是整个方案能成立的前提。
 */
public class SamplerService extends Service {

    public static final String ACTION_START = "com.tree0312.bhbench.START";
    public static final String ACTION_STOP  = "com.tree0312.bhbench.STOP";
    public static final String EXTRA_DESIGN = "design_mah";
    public static final String EXTRA_INTERVAL = "interval_s";

    /** 库仑计大约 20–25 秒才跳一次，采太密没有意义，默认 5 秒。 */
    private static final long DEFAULT_INTERVAL_MS = 5_000L;

    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "sampling";

    // 供界面读取的运行态。同进程静态字段足够，不必上 Binder。
    public static volatile boolean running = false;
    public static volatile String currentFile = null;
    public static volatile long startedAt = 0L;
    public static volatile int lastSoc = -1;
    public static volatile long lastCounterUah = 0L;
    public static volatile int sampleCount = 0;

    private Handler handler;
    private BatteryManager bm;
    private BufferedWriter out;
    private long t0;
    private long intervalMs = DEFAULT_INTERVAL_MS;
    private double designMah = 0;

    private final Runnable sampleTask = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                sampleOnce();
            } catch (Exception ignored) {
                // 单次采样失败不该中断整场测试
            }
            handler.postDelayed(this, intervalMs);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSampling();
            return START_NOT_STICKY;
        }
        if (!running) {
            double design = intent != null ? intent.getDoubleExtra(EXTRA_DESIGN, 0) : 0;
            long secs = intent != null ? intent.getLongExtra(EXTRA_INTERVAL, 5) : 5;
            startSampling(design, Math.max(1, secs) * 1000L);
        }
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "放电采样", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("电池容量测量期间保持采样");
        nm.createNotificationChannel(ch);
    }

    private void startSampling(double design, long interval) {
        designMah = design;
        intervalMs = interval;
        t0 = System.currentTimeMillis();
        startedAt = t0;
        sampleCount = 0;

        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File sessions = new File(dir, "sessions");
        if (!sessions.exists() && !sessions.mkdirs()) {
            stopSelf();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(sessions, "session_" + stamp + ".csv");

        try {
            out = new BufferedWriter(new FileWriter(file, false), 1 << 14);
            out.write("t_s,current_ma,voltage_mv,soc_pct,temp_c,status,"
                    + "charge_full_mah,design_mah,cycle_count\n");
            out.flush();
        } catch (IOException e) {
            stopSelf();
            return;
        }

        running = true;
        currentFile = file.getAbsolutePath();

        Notification n = buildNotification("正在记录…", "点击返回应用");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }

        handler.post(sampleTask);
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void sampleOnce() throws IOException {
        long now = System.currentTimeMillis();
        double tSec = (now - t0) / 1000.0;

        long counterUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        int currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);

        Intent batt = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batt == null) return;

        int level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int soc = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
        int voltageMv = batt.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int tempTenth = batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250);
        int statusCode = batt.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);

        double currentMa = 0;
        if (currentUa != Integer.MIN_VALUE && currentUa != 0) {
            currentMa = currentUa / 1000.0;
            if (statusCode == BatteryManager.BATTERY_STATUS_DISCHARGING) {
                currentMa = Math.abs(currentMa);
            } else if (statusCode == BatteryManager.BATTERY_STATUS_CHARGING) {
                currentMa = -Math.abs(currentMa);
            }
        }

        String status = statusName(statusCode);

        out.write(String.format(Locale.US, "%.2f,%.1f,%d,%d,%.2f,%s,%.0f,%.0f,0%n",
                tSec, currentMa, voltageMv, soc, tempTenth / 10.0,
                status, counterUah / 1000.0, designMah));
        out.flush();

        lastSoc = soc;
        lastCounterUah = counterUah;
        sampleCount++;

        if (sampleCount % 12 == 0) {
            String text = soc + "%  " + (counterUah / 1000) + " mAh  "
                    + (status.equals("discharging") ? "放电中" : status);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification("正在记录…", text));
        }
    }

    private static String statusName(int code) {
        switch (code) {
            case BatteryManager.BATTERY_STATUS_CHARGING:     return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:  return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL:         return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not-charging";
            default:                                          return "unknown";
        }
    }

    private void stopSampling() {
        running = false;
        handler.removeCallbacks(sampleTask);
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        out = null;
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (running) stopSampling();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
