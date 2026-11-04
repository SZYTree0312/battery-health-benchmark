package com.tree0312.bhbench;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 直接读内核电源节点 —— 能读到就别猜。
 *
 * 充电协议在 Android 层没有 API，但在驱动层是有的：Type-C 控制器和充电器
 * 协商完，结果会落在 /sys/class/power_supply/ 下面。高通平台常见
 * usb/real_type 直接给 SDP/CDP/DCP/PD/HVDCP，usb/voltage_now 给 VBUS。
 *
 * 问题是这些节点**默认只有 root 能读**。小米 17 上实测 8 个字段全被封
 * （SELinux 挡在 App 进程外面），所以本机只能退回功率反推。但换个没封的
 * 机型、或者用户自己 root 了，同一份代码就能直接输出真答案 —— 不写这段，
 * 就永远只能给区间。
 *
 * 设计原则：**一个路径读不到不许报错**。每个候选路径独立 try，全失败就
 * 返回 available=false，调用方走反推。绝不因为读不到就崩或者卡住。
 */
public final class SysfsProbe {

    private SysfsProbe() {}

    public static class Info {
        public boolean available = false;
        /** VBUS 输入电压（V）。这是最有价值的一个 —— 有它就能直接区分 9V/20V。 */
        public double vbusV = -1;
        /** 输入电流上限（A）。 */
        public double ibusMaxA = -1;
        /** 平台报的充电器类型，如 SDP/CDP/DCP/PD/HVDCP。 */
        public String chargerType = "";
        /** PD 是否处于活动状态（true/false，读不到为 null）。 */
        public Boolean pdActive = null;
        /** 厂商私有的快充档位原值，各机型含义不同，原样展示不解释。 */
        public String quickChargeType = "";
        /** 读成功的节点数量、尝试过的数量。 */
        public int okCount = 0;
        public int triedCount = 0;
        /** 记录哪些路径成功读了，方便排查和机型适配。 */
        public final List<String> okPaths = new ArrayList<>();

        public boolean hasTypeCInfo() {
            return !chargerType.isEmpty() || pdActive != null;
        }
    }

    /** 候选路径。按「信息量从高到低」排，全试一遍也就几十毫秒。 */
    private static final String[][] VBUS = {
            {"/sys/class/power_supply/usb/voltage_now", "1000"},       // µV
            {"/sys/class/power_supply/usb/voltage_max", "1000"},
            {"/sys/class/power_supply/charger/voltage_now", "1000"},
            {"/sys/class/power_supply/main/voltage_now", "1000"},
            {"/sys/class/power_supply/typec/voltage_now", "1000"},
    };

    private static final String[][] IBUS_MAX = {
            {"/sys/class/power_supply/usb/current_max", "1000"},       // µA
            {"/sys/class/power_supply/usb/input_current_limit", "1000"},
            {"/sys/class/power_supply/charger/current_max", "1000"},
    };

    private static final String[] CHARGER_TYPE = {
            "/sys/class/power_supply/usb/real_type",
            "/sys/class/power_supply/usb/type",
            "/sys/class/power_supply/usb/charger_type",
            "/sys/class/power_supply/charger/real_type",
            "/sys/class/power_supply/battery/charge_type",
            "/sys/class/power_supply/battery/charger_type",
    };

    private static final String[] PD_ACTIVE = {
            "/sys/class/power_supply/usb/pd_active",
            "/sys/class/power_supply/usb/pd_verifed",   // 部分平台就是这个拼写
            "/sys/class/power_supply/typec/pd_active",
    };

    private static final String[] QUICK_CHARGE = {
            "/sys/class/power_supply/charger/quick_charge_type",
            "/sys/class/power_supply/battery/quick_charge_type",
            "/sys/class/power_supply/usb/quick_charge_type",
            "/sys/class/power_supply/battery/fast_charge_type",
    };

    public static Info read() {
        Info info = new Info();

        for (String[] p : VBUS) {
            info.triedCount++;
            Double v = readNumber(p[0], Double.parseDouble(p[1]));
            if (v != null && v > 0.5 && v < 60) {   // 0.5–60 V，滤掉明显异常值
                info.vbusV = v;
                info.available = true;
                info.okCount++;
                info.okPaths.add(p[0]);
                break;
            }
        }

        for (String[] p : IBUS_MAX) {
            info.triedCount++;
            Double v = readNumber(p[0], Double.parseDouble(p[1]));
            if (v != null && v > 0 && v < 15) {
                info.ibusMaxA = v;
                info.available = true;
                info.okCount++;
                info.okPaths.add(p[0]);
                break;
            }
        }

        for (String path : CHARGER_TYPE) {
            info.triedCount++;
            String s = readText(path);
            if (s != null && !s.isEmpty() && s.length() < 32) {
                info.chargerType = s;
                info.available = true;
                info.okCount++;
                info.okPaths.add(path);
                break;
            }
        }

        for (String path : PD_ACTIVE) {
            info.triedCount++;
            String s = readText(path);
            if (s == null || s.isEmpty()) continue;
            info.pdActive = s.equals("1") || s.equalsIgnoreCase("true")
                    || s.equalsIgnoreCase("yes");
            info.available = true;
            info.okCount++;
            info.okPaths.add(path);
            break;
        }

        for (String path : QUICK_CHARGE) {
            info.triedCount++;
            String s = readText(path);
            if (s != null && !s.isEmpty() && s.length() < 16) {
                info.quickChargeType = s;
                info.available = true;
                info.okCount++;
                info.okPaths.add(path);
                break;
            }
        }

        return info;
    }

    /** 一句话描述直读结果，给界面和报告用。 */
    public static String describe(Info info) {
        if (info == null || !info.available) {
            return "读不到充电器节点（App 没 root、或被 SELinux 封了），只能从功率反推";
        }
        StringBuilder sb = new StringBuilder();
        if (info.vbusV > 0) {
            sb.append(String.format(Locale.US, "VBUS %.2f V", info.vbusV));
        }
        if (info.ibusMaxA > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.US, "输入上限 %.2f A", info.ibusMaxA));
        }
        if (!info.chargerType.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("类型 ").append(info.chargerType.toUpperCase(Locale.US));
        }
        if (info.pdActive != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("PD ").append(info.pdActive ? "已协商" : "未协商");
        }
        if (!info.quickChargeType.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("快充档位 ").append(info.quickChargeType);
        }
        if (sb.length() == 0) return "节点存在但值为空，只能从功率反推";
        return sb.toString();
    }

    /**
     * 直读到 VBUS 时，用它替代功率反推 —— 电压是确定的，就不用猜档位了。
     * 返回 null 表示没有直读数据可用。
     */
    public static String directVerdict(Info info, ChargerProbe.Result r) {
        if (info == null || !info.available || r == null || !r.valid) return null;

        StringBuilder sb = new StringBuilder();
        if (info.vbusV > 0) {
            // 常见 PD 固定档，就近靠拢但也把实测值写出来
            String gear;
            double v = info.vbusV;
            if (Math.abs(v - 5) < 0.5) gear = "5V 档";
            else if (Math.abs(v - 9) < 0.7) gear = "9V 档";
            else if (Math.abs(v - 12) < 0.8) gear = "12V 档";
            else if (Math.abs(v - 15) < 0.9) gear = "15V 档";
            else if (Math.abs(v - 20) < 1.2) gear = "20V 档";
            else gear = String.format(Locale.US, "非标准电压 %.1f V（PD PPS 连续调压？）", v);
            sb.append("直读 VBUS ").append(String.format(Locale.US, "%.2f V → ", v)).append(gear);
            if (info.ibusMaxA > 0) {
                sb.append(String.format(Locale.US, "，输入上限 %.2f A，理论 %.0f W",
                        info.ibusMaxA, v * info.ibusMaxA));
            }
        }
        if (!info.chargerType.isEmpty()) {
            if (sb.length() > 0) sb.append("；");
            sb.append("充电器类型 ").append(info.chargerType.toUpperCase(Locale.US));
        }
        if (info.pdActive != null) {
            if (sb.length() > 0) sb.append("；");
            sb.append(info.pdActive ? "PD 协商已生效" : "PD 未协商");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ------------------------------------------------------------ IO

    private static String readText(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            return line == null ? null : line.trim();
        } catch (Exception e) {
            // 权限不足、节点不存在、SELinux 拒绝 —— 都当"读不到"，不抛
            return null;
        }
    }

    private static Double readNumber(String path, double scale) {
        String s = readText(path);
        if (s == null || s.isEmpty()) return null;
        try {
            return Double.parseDouble(s) / scale;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
