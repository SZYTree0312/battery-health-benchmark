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
    /** 电量纹丝不动时最多放到这么疏 —— 一晚上就是一条平线，5 秒一个点纯属浪费电。 */
    private static final long MAX_INTERVAL_MS = 30_000L;
    /** 充电时本来就很慢，起步就比放电疏一点。 */
    private static final long CHARGE_INTERVAL_MS = 15_000L;

    private static final int NOTIF_ID = 1001;
    private static final int NOTIF_ID_ALERT = 1002;
    private static final String CHANNEL_ID = "sampling";

    // 供界面读取的运行态。同进程静态字段足够，不必上 Binder。
    public static volatile boolean running = false;
    public static volatile String currentFile = null;
    public static volatile long startedAt = 0L;
    public static volatile int lastSoc = -1;
    public static volatile long lastCounterUah = 0L;
    public static volatile int sampleCount = 0;
    /** 本次记录里放电 / 充电各累计了多少个点。界面用它告诉用户"两段都在记"。 */
    public static volatile int dischargePoints = 0;
    public static volatile int chargePoints = 0;
    /** 放电 / 充电各累计了多少秒（按相邻采样点的时间差累加，含 Doze 空档）。 */
    public static volatile double dischargeSeconds = 0;
    public static volatile double chargeSeconds = 0;
    /** 当前实际采样间隔（秒），界面上直接显示，让人知道 App 在省电。 */
    public static volatile long currentIntervalS = 5;

    private Handler handler;
    private BatteryManager bm;
    private BufferedWriter out;
    private long t0;
    /** 基础间隔（放电）。充电时用 CHARGE_INTERVAL_MS 打底。 */
    private long baseIntervalMs = DEFAULT_INTERVAL_MS;
    /** 当前实际间隔，随电量变化快慢上下浮动。 */
    private long currentIntervalMs = DEFAULT_INTERVAL_MS;
    private double designMah = 0;
    private boolean lowBatteryNotified = false;
    /** 上一次采样的时间轴读数，用来累加两段的秒数。 */
    private double lastSampleT = -1;
    private double lastCounterMah = -1;

    private final Runnable sampleTask = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                sampleOnce();
            } catch (Exception ignored) {
                // 单次采样失败不该中断整场测试
            }
            handler.postDelayed(this, currentIntervalMs);
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
            // 没有 design 说明这是系统按 START_STICKY 把我们拉起来的（intent 为 null），
            // 不是用户点开始。这种情况下开一个新会话只会造出一堆空 CSV，
            // 历史里全是「空文件·没有数据」。直接退出，等用户手动开始。
            if (design <= 0) {
                stopSelf();
                return START_NOT_STICKY;
            }
            startSampling(design, Math.max(1, secs) * 1000L);
        }
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "电池采样", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("电池容量测量期间保持采样，充放电都记");
        nm.createNotificationChannel(ch);
    }

    private void startSampling(double design, long interval) {
        designMah = design;
        baseIntervalMs = interval;
        currentIntervalMs = interval;
        currentIntervalS = interval / 1000L;
        t0 = System.currentTimeMillis();
        startedAt = t0;
        sampleCount = 0;
        dischargePoints = 0;
        chargePoints = 0;
        dischargeSeconds = 0;
        chargeSeconds = 0;
        lastSampleT = -1;
        lastCounterMah = -1;
        lowBatteryNotified = false;

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
            // 第 7 列是 BATTERY_PROPERTY_CHARGE_COUNTER —— 当前**剩余电荷**，
            // 随放电递减。v0.1 误命名为 charge_full_mah（字面像"满电容量常数"），
            // 已改。analyzer 仍兼容旧列名，手机上的历史会话照样能读。
            out.write("t_s,current_ma,voltage_mv,soc_pct,temp_c,status,"
                    + "charge_counter_mah,design_mah,cycle_count\n");
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

        int prevSoc = lastSoc;
        double prevCounterMah = lastCounterMah;

        lastSoc = soc;
        lastCounterUah = counterUah;
        sampleCount++;

        double counterMahNow = counterUah / 1000.0;
        double dt = lastSampleT < 0 ? 0 : (tSec - lastSampleT);
        if (dt < 0) dt = 0;

        // 充放电两段都记，分析时再挑能用的那一段。这里只是给界面一个进度感：
        // 用户看到"充电已记 32 分钟"，就不会误以为 App 只肯测耗电。
        boolean charging = "charging".equals(status) || "full".equals(status);
        if ("discharging".equals(status)) {
            dischargePoints++;
            dischargeSeconds += dt;
        } else if (charging) {
            chargePoints++;
            chargeSeconds += dt;
        }

        // ---- 自适应采样间隔
        //
        // 电量百分比和库仑计都没动，说明现在几乎不耗电（息屏、待机），这时候
        // 5 秒一个点纯属浪费 —— 曲线还是平的。逐步把间隔翻倍到 30 秒。
        // 只要有任何一个动了，立刻回到基础间隔，把变化过程抓细。
        //
        // 前提是安全的：最终容量取库仑计首尾差，是硬件累计量，跟采样频率无关；
        // 采样疏只会让曲线粗一点，不会让算出来的 mAh 变。
        boolean flat = prevCounterMah >= 0
                && soc == prevSoc
                && Math.abs(counterMahNow - prevCounterMah) < 2.0;
        if (flat) {
            currentIntervalMs = Math.min(currentIntervalMs * 2, MAX_INTERVAL_MS);
        } else {
            currentIntervalMs = charging
                    ? Math.max(baseIntervalMs, CHARGE_INTERVAL_MS)
                    : baseIntervalMs;
        }
        currentIntervalS = currentIntervalMs / 1000L;

        lastSampleT = tSec;
        lastCounterMah = counterMahNow;

        // 充到满时留一条快照：这是最省事的那种数据，用户什么都不用做
        SnapshotStore.maybeRecord(this, soc, counterUah, tempTenth / 10.0);

        // 电量低时提醒插电，而不是让用户等到关机。
        // 关机之后 App 不会自己起来，重启、解锁、再点开始，中间已经充进去几个点了。
        // 留 10-15% 插电，折算跨度还有 85-90%，误差比"关机重开"小得多。
        if (!lowBatteryNotified && "discharging".equals(status) && soc > 0 && soc <= 15) {
            lowBatteryNotified = true;
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                Notification alert = new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("电量 " + soc + "%，可以插充电器了")
                        .setContentText("记录仍在继续，请不要点停止")
                        .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                        .setAutoCancel(true)
                        .build();
                nm.notify(NOTIF_ID_ALERT, alert);
            }
        }

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
