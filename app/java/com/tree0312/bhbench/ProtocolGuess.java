package com.tree0312.bhbench;

import java.util.Locale;

/**
 * 充电协议推断 —— 只能说「大概是哪一档」，不能说「这是 PD」。
 *
 * Android 没有任何公开接口能读出充电协议。USB-C 的 PD 协商发生在 Type-C
 * 控制器和充电器之间，驱动层知道，但不往上暴露；QC/SCP/VOOC 这些更是各家私有。
 * 想读出来只有两条路：root 后读 PMIC 寄存器，或者拆机挂逻辑分析仪。
 *
 * 能做的是反推：
 *
 *   1. 量到的是电池侧功率 P_bat
 *   2. 充电管理芯片效率大约 85–92%，取 88% 反推输入功率 P_in ≈ P_bat / 0.88
 *   3. P_in 落在哪个区间，对应哪几组「电压×电流」组合
 *   4. 起始阶段有没有功率台阶 —— 有台阶说明发生过协议协商，没有多半是
 *      5V 基础档或者充电器压根没谈成
 *
 * 第 3 步天然给不出唯一答案：55W 可能是 20V/2.75A(PD) 也可能是 11V/5A(SCP)，
 * 电池侧看不到 VBUS。所以置信度最高只给「中」。这不是偷懒，是这个测量方法的
 * 上限 —— 标「高」就是骗人。
 */
public final class ProtocolGuess {

    private ProtocolGuess() {}

    /** 充电管理芯片估算效率。取区间中间值，两端 85–92%。 */
    private static final double EFFICIENCY = 0.88;

    public static class Guess {
        /** 档位描述，例如「9V/2A 级」「20V/3.25A 级」。 */
        public String tier = "";
        /** 可能的协议名，用 / 分隔。 */
        public String candidates = "";
        /** 高 / 中 / 低。按本方法的物理上限，最高只到「中」。 */
        public String confidence = "低";
        /** 判断依据，一句话。 */
        public String reason = "";
        /** 由电池侧功率反推的输入功率（W）。 */
        public double inputW = 0;
        /** 给用户的一句话结论。 */
        public String verdict = "";
    }

    public static Guess from(ChargerProbe.Result r, boolean charging) {
        Guess g = new Guess();
        if (r == null || !r.valid) {
            g.verdict = "没采到有效的充电数据，判断不了";
            return g;
        }
        if (!charging) {
            g.verdict = "这段时间不在充电，协议无从谈起";
            return g;
        }

        g.inputW = r.peakW / EFFICIENCY;

        // 用峰值定档位，用稳态判断"能不能维持"
        double w = g.inputW;
        if (w < 7.5) {
            g.tier = "5V/1.5A 级";
            g.candidates = "BC1.2 基础档 / 电脑 USB 口";
            g.confidence = "中";
        } else if (w < 12.5) {
            g.tier = "5V/2A 级";
            g.candidates = "BC1.2 上限 / Apple 2.4A / 5V 直充";
            g.confidence = "中";
        } else if (w < 20) {
            g.tier = "9V/2A 级";
            g.candidates = "QC2 / PD 15–18W";
            g.confidence = "中";
        } else if (w < 30) {
            g.tier = "9V/3A 级";
            g.candidates = "QC3 / PD 27W";
            g.confidence = "中";
        } else if (w < 45) {
            g.tier = "11V/3A 或 15V/2A 级";
            g.candidates = "PD 30–40W / SCP 33W";
            g.confidence = "低";
        } else if (w < 65) {
            g.tier = "20V/2.25A 或 11V/5A 级";
            g.candidates = "PD 45W / SCP 55W";
            g.confidence = "低";
        } else if (w < 100) {
            g.tier = "20V/3.25A 或 10V/6.5A 级";
            g.candidates = "PD 65W / SCP 66W";
            g.confidence = "低";
        } else {
            g.tier = "20V/5A 以上";
            g.candidates = "PD 100W+ / 私有大电流协议";
            g.confidence = "低";
        }

        // 台阶：有协商动作，说明走的不是 5V 基础档
        StringBuilder why = new StringBuilder();
        why.append(String.format(Locale.US,
                "电池侧峰值 %.1f W，按 88%% 效率反推输入约 %.1f W", r.peakW, g.inputW));
        if (r.steps > 0) {
            why.append(String.format(Locale.US,
                    "；起始阶段检测到 %d 次功率台阶，说明发生过协商", r.steps));
            if (g.inputW < 12.5) {
                // 有协商却仍在 5V 区间 —— 最可能是谈崩了退回基础档
                g.confidence = "中";
                why.append("，但功率停在 5V 区间，可能是协商没谈成");
            }
        } else {
            why.append("；没有检测到台阶，可能是 5V 基础档，或采样开始得太晚错过了协商");
            if (g.inputW >= 20) g.confidence = "低";
        }
        g.reason = why.toString();

        // 给用户的一句话
        if (r.peakEarly) {
            g.verdict = String.format(Locale.US,
                    "峰值 %.1f W 出现在第 %.0f 秒，之后掉到 %.1f W —— 冲得上去但压不住，"
                            + "多半是温度或输入功率限制介入",
                    r.peakW, r.peakAtMs / 1000.0, r.steadyW);
        } else {
            g.verdict = String.format(Locale.US,
                    "峰值 %.1f W，稳态 %.1f W，能维持在峰值附近",
                    r.peakW, r.steadyW);
        }
        return g;
    }

    /**
     * 充电器体检的额外判词：稳定性、能量、温度。
     * 和协议无关，单独一段，避免和推断结论混在一起。
     */
    public static String healthNote(ChargerProbe.Result r) {
        if (r == null || !r.valid) return "";

        StringBuilder sb = new StringBuilder();
        if (r.stabilityPct > 0) {
            if (r.stabilityPct < 8) {
                sb.append(String.format(Locale.US, "输出很稳（稳态波动 %.1f%%）", r.stabilityPct));
            } else if (r.stabilityPct < 20) {
                sb.append(String.format(Locale.US, "输出有起伏（稳态波动 %.1f%%）", r.stabilityPct));
            } else {
                sb.append(String.format(Locale.US,
                        "输出抖得厉害（稳态波动 %.1f%%），可能是线材压降或充电器保护在反复动作",
                        r.stabilityPct));
            }
        }
        if (r.energyWh > 0) {
            if (sb.length() > 0) sb.append("；");
            sb.append(String.format(Locale.US, "%.2f 分钟充进 %.3f Wh",
                    r.durationMs / 60000.0, r.energyWh));
        }
        if (r.peakTemp > 0) {
            if (sb.length() > 0) sb.append("；");
            sb.append(String.format(Locale.US, "电池温度 %.1f °C（峰值 %.1f °C）",
                    r.avgTemp, r.peakTemp));
        }
        return sb.toString();
    }
}
