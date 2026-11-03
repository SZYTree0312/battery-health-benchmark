// bhbench.cpp — 安卓电池放电采样器（native）
//
// 为什么是 C++ 而不是 Kotlin：
//   1. 无 ART/JVM，没有 GC 停顿，采样间隔抖动可控在微秒级；
//   2. 直接 read(2) sysfs，不走 BatteryManager 的 Binder 往返（那条路单次约 1 ms）；
//   3. 静态链接单文件，adb push 即跑，不需要 APK、签名、安装；
//   4. 100 Hz 采样才能捕捉电流阶跃（内阻测量），Java 层轮询做不到这个稳定性。
//
// 编译（NDK r26+）：
//   $NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang++ \
//       -std=c++17 -O2 -static -o bhbench bhbench.cpp
//
// 使用：
//   adb push bhbench /data/local/tmp/ && adb shell chmod +x /data/local/tmp/bhbench
//   adb shell /data/local/tmp/bhbench --probe
//   adb shell /data/local/tmp/bhbench --interval 1 --out /data/local/tmp/session.csv
//   adb pull /data/local/tmp/session.csv
//
// 单位约定：全部输出到 CSV 时统一为 mA / mV / °C / mAh，放电为正。

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>

#ifdef _WIN32
// 仅本机语法校验用；安卓构建走 POSIX 分支。
// NOMINMAX 必须早于 windows.h，否则 max() 宏会把 std::max 拆坏。
#define NOMINMAX
#include <windows.h>
#define popen _popen
#define pclose _pclose
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace {

volatile std::sig_atomic_t g_stop = 0;

void on_signal(int) { g_stop = 1; }

double now_s() {
#ifdef _WIN32
    static LARGE_INTEGER freq = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return f;
    }();
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return static_cast<double>(c.QuadPart) / static_cast<double>(freq.QuadPart);
#else
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) / 1e9;
#endif
}

void sleep_until(double target) {
    double remain = target - now_s();
    if (remain <= 0) return;
#ifdef _WIN32
    Sleep(static_cast<DWORD>(remain * 1000.0));
#else
    timespec req{};
    req.tv_sec = static_cast<time_t>(remain);
    req.tv_nsec = static_cast<long>((remain - static_cast<double>(req.tv_sec)) * 1e9);
    nanosleep(&req, nullptr);
#endif
}

// ---------------------------------------------------------------- sysfs 读取

bool read_first_line(const char* const* candidates, char* buf, size_t cap,
                     const char** used) {
    for (const char* const* p = candidates; *p != nullptr; ++p) {
        FILE* fh = std::fopen(*p, "r");
        if (fh == nullptr) continue;
        size_t n = std::fread(buf, 1, cap - 1, fh);
        std::fclose(fh);
        if (n == 0) continue;
        buf[n] = '\0';
        // 去掉尾部空白，sysfs 的值普遍带换行
        while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r' || buf[n - 1] == ' ')) {
            buf[--n] = '\0';
        }
        if (n == 0) continue;
        if (used != nullptr) *used = *p;
        return true;
    }
    return false;
}

bool read_number(const char* const* candidates, double* out, const char** used) {
    char buf[64];
    if (!read_first_line(candidates, buf, sizeof(buf), used)) return false;
    char* end = nullptr;
    errno = 0;
    double v = std::strtod(buf, &end);
    if (end == buf || errno != 0) return false;
    *out = v;
    return true;
}

// 各厂商驱动字段名不统一，按平台常见顺序探测。
// 高通/三星/小米多数走 battery/，联发科部分机型挂 bms/ 或 Battery_* 前缀。
const char* const kCurrentPaths[] = {
    "/sys/class/power_supply/battery/current_now",
    "/sys/class/power_supply/bms/current_now",
    "/sys/class/power_supply/battery/Battery_Current",
    "/sys/class/power_supply/battery/current_avg",
    nullptr,
};

const char* const kVoltagePaths[] = {
    "/sys/class/power_supply/battery/voltage_now",
    "/sys/class/power_supply/bms/voltage_now",
    "/sys/class/power_supply/battery/Battery_Voltage",
    nullptr,
};

const char* const kCapacityPaths[] = {
    "/sys/class/power_supply/battery/capacity",
    "/sys/class/power_supply/bms/capacity",
    nullptr,
};

const char* const kTempPaths[] = {
    "/sys/class/power_supply/battery/temp",
    "/sys/class/power_supply/bms/temp",
    "/sys/class/power_supply/battery/Battery_Temp",
    nullptr,
};

const char* const kStatusPaths[] = {
    "/sys/class/power_supply/battery/status",
    "/sys/class/power_supply/bms/status",
    nullptr,
};

const char* const kChargeFullPaths[] = {
    "/sys/class/power_supply/battery/charge_full",
    "/sys/class/power_supply/bms/charge_full",
    "/sys/class/power_supply/battery/charge_counter",
    nullptr,
};

const char* const kDesignPaths[] = {
    "/sys/class/power_supply/battery/charge_full_design",
    "/sys/class/power_supply/bms/charge_full_design",
    nullptr,
};

const char* const kCyclePaths[] = {
    "/sys/class/power_supply/battery/cycle_count",
    "/sys/class/power_supply/bms/cycle_count",
    nullptr,
};

struct Sample {
    bool has_current = false;
    bool has_voltage = false;
    bool has_soc = false;
    bool has_temp = false;
    bool has_status = false;
    bool has_charge_full = false;
    bool has_design = false;
    bool has_cycle = false;

    double current_ma = 0;
    double voltage_mv = 0;
    double soc = -1;
    double temp_c = 25;
    char status[24] = "unknown";
    double charge_full_mah = 0;
    double design_mah = 0;
    double cycle = 0;
};

bool starts_with_ci(const char* s, const char* prefix) {
    while (*prefix != '\0') {
        char a = *s++;
        char b = *prefix++;
        if (a >= 'A' && a <= 'Z') a = static_cast<char>(a - 'A' + 'a');
        if (b >= 'A' && b <= 'Z') b = static_cast<char>(b - 'A' + 'a');
        if (a != b) return false;
    }
    return true;
}

// ---------------------------------------------------------------- dumpsys 数据源
//
// 小米 HyperOS 等系统把 /sys/class/power_supply 对 shell 整个封死（连目录都不可读），
// 但 `dumpsys battery` 走 BatteryService，shell 一直有权限。
// 它不给实时电流，给的是库仑计累计值 Charge counter（µAh）——
// 对容量测量反而更直接：首尾相减就是放出的电荷，不需要积分，也不累积采样误差。
//
// 这条通道的价值还在于：二进制在手机上自己跑，不需要 USB 连着电脑，
// 否则线一插就在供电，手机根本不会放电。

bool snapshot_dumpsys(Sample& s) {
    FILE* pipe = popen("dumpsys battery 2>/dev/null", "r");
    if (pipe == nullptr) return false;

    char line[192];
    bool have_counter = false;
    int status_code = 0;

    while (std::fgets(line, sizeof(line), pipe) != nullptr) {
        char* colon = std::strchr(line, ':');
        if (colon == nullptr) continue;
        *colon = '\0';

        char* key = line;
        while (*key == ' ' || *key == '\t') ++key;
        char* key_end = key + std::strlen(key);
        while (key_end > key && (key_end[-1] == ' ' || key_end[-1] == '\t')) {
            *--key_end = '\0';
        }

        char* val = colon + 1;
        while (*val == ' ') ++val;
        char* val_end = val + std::strlen(val);
        while (val_end > val &&
               (val_end[-1] == '\n' || val_end[-1] == '\r' || val_end[-1] == ' ')) {
            *--val_end = '\0';
        }

        if (std::strcmp(key, "Charge counter") == 0) {
            s.charge_full_mah = std::atof(val) / 1000.0;  // µAh → mAh
            s.has_charge_full = true;
            have_counter = true;
        } else if (std::strcmp(key, "level") == 0) {
            s.soc = std::atof(val);
            s.has_soc = true;
        } else if (std::strcmp(key, "voltage") == 0) {
            s.voltage_mv = std::atof(val);
            s.has_voltage = true;
        } else if (std::strcmp(key, "temperature") == 0) {
            const double t = std::atof(val);
            s.temp_c = (std::fabs(t) > 200.0) ? t / 10.0 : t;
            s.has_temp = true;
        } else if (std::strcmp(key, "status") == 0) {
            status_code = std::atoi(val);
        }
    }
    pclose(pipe);

    if (!have_counter) return false;

    const char* name = "unknown";
    switch (status_code) {
        case 2: name = "charging"; break;
        case 3: name = "discharging"; break;
        case 4: name = "not-charging"; break;
        case 5: name = "full"; break;
        default: name = "unknown"; break;
    }
    std::snprintf(s.status, sizeof(s.status), "%s", name);
    s.has_status = true;
    s.has_current = false;   // 这条通道拿不到电流
    s.current_ma = 0.0;
    return true;
}

Sample take_sample(bool use_dumpsys) {
    Sample s;

    if (use_dumpsys) {
        snapshot_dumpsys(s);
        return s;
    }

    double raw = 0;
    if (read_number(kCurrentPaths, &raw, nullptr)) {
        s.has_current = true;
        s.current_ma = raw / 1000.0;  // µA → mA
    }

    if (read_number(kVoltagePaths, &raw, nullptr)) {
        s.has_voltage = true;
        s.voltage_mv = raw / 1000.0;  // µV → mV
    }

    if (read_number(kCapacityPaths, &raw, nullptr)) {
        s.has_soc = true;
        s.soc = raw;
    }

    if (read_number(kTempPaths, &raw, nullptr)) {
        s.has_temp = true;
        // 主流驱动用 0.1 °C，个别直接用 °C，靠量级判断。
        s.temp_c = (std::fabs(raw) > 200.0) ? raw / 10.0 : raw;
    }

    char buf[24];
    if (read_first_line(kStatusPaths, buf, sizeof(buf), nullptr)) {
        s.has_status = true;
        std::snprintf(s.status, sizeof(s.status), "%s", buf);
    }

    if (read_number(kChargeFullPaths, &raw, nullptr)) {
        s.has_charge_full = true;
        s.charge_full_mah = raw / 1000.0;  // µAh → mAh
    }

    if (read_number(kDesignPaths, &raw, nullptr)) {
        s.has_design = true;
        s.design_mah = raw / 1000.0;
    }

    if (read_number(kCyclePaths, &raw, nullptr)) {
        s.has_cycle = true;
        s.cycle = raw;
    }

    // 符号统一：不同驱动放电时的电流正负相反，以 status 为准。
    // 放电一律输出正值，充电负值，下游积分就不用再猜方向。
    if (s.has_status && starts_with_ci(s.status, "discharg")) {
        s.current_ma = std::fabs(s.current_ma);
    } else if (s.has_status) {
        s.current_ma = std::fabs(s.current_ma);
    }

    return s;
}

// ---------------------------------------------------------------- 输出

class Writer {
public:
    explicit Writer(FILE* fh) : fh_(fh) {
        // 64 KB 缓冲：1 kHz 采样时把 write 系统调用从每秒上千次压到几十次。
        std::setvbuf(fh_, buf_, _IOFBF, sizeof(buf_));
    }

    void header() {
        std::fputs("t_s,current_ma,voltage_mv,soc_pct,temp_c,status,"
                   "charge_full_mah,design_mah,cycle_count\n", fh_);
    }

    void row(double t, const Sample& s) {
        char line[192];
        int n = std::snprintf(
            line, sizeof(line), "%.2f,%.1f,%.0f,%.1f,%.2f,%s,%.0f,%.0f,%.0f\n",
            t,
            s.has_current ? s.current_ma : 0.0,
            s.has_voltage ? s.voltage_mv : 0.0,
            s.has_soc ? s.soc : -1.0,
            s.has_temp ? s.temp_c : 25.0,
            s.has_status ? s.status : "unknown",
            s.has_charge_full ? s.charge_full_mah : 0.0,
            s.has_design ? s.design_mah : 0.0,
            s.has_cycle ? s.cycle : 0.0);
        if (n > 0) std::fwrite(line, 1, static_cast<size_t>(n), fh_);
    }

    void flush() { std::fflush(fh_); }

private:
    FILE* fh_;
    char buf_[1 << 16] = {};
};

// ---------------------------------------------------------------- 守护化
//
// adb shell 每条命令结束都会回收整个进程组，nohup / setsid 都拦不住
// （实测两者都会让子进程立刻消失）。所以必须由程序自己 fork + setsid，
// 让父进程先退出，子进程彻底脱离 adb 的会话。
//
// 只有这样，才能拔掉数据线、让手机真正放电、同时又持续记录。

int daemonize(const char* log_path) {
#ifdef _WIN32
    (void)log_path;
    return 0;   // 本机调试不需要守护
#else
    const pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid > 0) _exit(0);   // 父进程立刻退出，adb shell 得以返回

    if (setsid() < 0) return -1;   // 脱离控制终端

    const int devnull = open("/dev/null", O_RDONLY);
    if (devnull >= 0) {
        dup2(devnull, STDIN_FILENO);
        if (devnull > 2) close(devnull);
    }

    // 进度日志落到 CSV 旁边，方便事后看采集是否正常。
    int logfd;
    if (log_path != nullptr) {
        logfd = open(log_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    } else {
        logfd = open("/dev/null", O_WRONLY);
    }
    if (logfd >= 0) {
        dup2(logfd, STDOUT_FILENO);
        dup2(logfd, STDERR_FILENO);
        if (logfd > 2) close(logfd);
    }
    return 0;
#endif
}

// ---------------------------------------------------------------- 探测模式

int probe() {
    std::printf("可用节点探测\n");
    std::printf("--------------------------------------------------------------\n");

    struct Item {
        const char* label;
        const char* const* paths;
        double scale_note;  // 仅用于说明单位，不参与计算
    };
    const Item items[] = {
        {"current_now ", kCurrentPaths, 0.001},
        {"voltage_now ", kVoltagePaths, 0.001},
        {"capacity    ", kCapacityPaths, 1.0},
        {"temp        ", kTempPaths, 0.1},
        {"status      ", kStatusPaths, 1.0},
        {"charge_full ", kChargeFullPaths, 0.001},
        {"design      ", kDesignPaths, 0.001},
        {"cycle_count ", kCyclePaths, 1.0},
    };

    int ok = 0;
    for (const Item& item : items) {
        char buf[64];
        const char* used = nullptr;
        if (read_first_line(item.paths, buf, sizeof(buf), &used)) {
            std::printf("  [可读] %s %-52s = %s\n", item.label, used, buf);
            ++ok;
        } else {
            std::printf("  [缺失] %s (所有候选路径都读不到)\n", item.label);
        }
    }

    std::printf("--------------------------------------------------------------\n");
    std::printf("sysfs 可读 %d/%zu\n", ok, sizeof(items) / sizeof(items[0]));

    Sample ds;
    const bool dumpsys_ok = snapshot_dumpsys(ds);
    if (dumpsys_ok) {
        std::printf("dumpsys 库仑计 = %.0f mAh   level=%.0f%%   %.0f mV   status=%s\n",
                    ds.charge_full_mah, ds.soc, ds.voltage_mv, ds.status);
    } else {
        std::printf("dumpsys 库仑计读不到\n");
    }

    std::printf("--------------------------------------------------------------\n");
    if (ok >= 3) {
        std::printf("→ 走 sysfs：电流积分可用，高频采样还能测内阻。\n");
        return 0;
    }
    if (dumpsys_ok) {
        std::printf("→ 走 dumpsys：sysfs 被系统权限挡住，改用库仑计首尾差算容量。\n"
                    "  这条通道没有实时电流，测不了内阻，但容量精度更高。\n");
        return 0;
    }
    std::printf("两条通道都不通。确认 adb 是 shell 权限，或换台机器。\n");
    return 1;
}

void usage() {
    std::printf(
        "bhbench — 安卓电池放电采样器\n"
        "\n"
        "  --out PATH        输出 CSV 路径，默认 stdout\n"
        "  --interval SEC    采样间隔，默认 1.0\n"
        "  --duration SEC    采样时长，0 表示直到手动停止，默认 0\n"
        "  --source MODE     数据源 auto / sysfs / dumpsys，默认 auto\n"
        "                    sysfs=直读电源节点（有电流）；dumpsys=走库仑计（无电流）\n"
        "  --daemon          自我守护：fork 后脱离 adb 会话，拔线继续记录（需 --out）\n"
        "  --probe           只探测两条通道是否可用并退出\n"
        "  --quiet           不打印进度\n"
        "  -h, --help        显示本帮助\n"
        "\n"
        "离线采集（充满、拔线、静置后执行，然后就可以把线拔了）：\n"
        "  /data/local/tmp/bhbench --daemon --source dumpsys --interval 5 \\\n"
        "        --out /data/local/tmp/session.csv\n");
}

}  // namespace

int main(int argc, char** argv) {
    double interval = 1.0;
    double duration = 0.0;
    const char* out_path = nullptr;
    const char* source = "auto";
    bool do_probe = false;
    bool quiet = false;
    bool daemon_mode = false;

    for (int i = 1; i < argc; ++i) {
        const char* a = argv[i];
        auto need = [&](const char* name) -> const char* {
            if (i + 1 >= argc) {
                std::fprintf(stderr, "%s 缺少参数\n", name);
                std::exit(2);
            }
            return argv[++i];
        };
        if (std::strcmp(a, "--out") == 0) {
            out_path = need("--out");
        } else if (std::strcmp(a, "--interval") == 0) {
            interval = std::atof(need("--interval"));
        } else if (std::strcmp(a, "--duration") == 0) {
            duration = std::atof(need("--duration"));
        } else if (std::strcmp(a, "--source") == 0) {
            source = need("--source");
        } else if (std::strcmp(a, "--daemon") == 0) {
            daemon_mode = true;
        } else if (std::strcmp(a, "--probe") == 0) {
            do_probe = true;
        } else if (std::strcmp(a, "--quiet") == 0) {
            quiet = true;
        } else if (std::strcmp(a, "-h") == 0 || std::strcmp(a, "--help") == 0) {
            usage();
            return 0;
        } else {
            std::fprintf(stderr, "未知参数：%s\n", a);
            usage();
            return 2;
        }
    }

    if (do_probe) return probe();

    if (daemon_mode) {
        if (out_path == nullptr) {
            std::fprintf(stderr, "--daemon 必须同时给 --out\n");
            return 2;
        }
        char log_path[512];
        std::snprintf(log_path, sizeof(log_path), "%s.log", out_path);
        if (daemonize(log_path) != 0) {
            std::fprintf(stderr, "守护化失败\n");
            return 3;
        }
    }

    // 选数据源：sysfs 能读到电流就走电流积分，否则退到库仑计。
    bool use_dumpsys = false;
    if (std::strcmp(source, "dumpsys") == 0) {
        use_dumpsys = true;
    } else if (std::strcmp(source, "sysfs") == 0) {
        use_dumpsys = false;
    } else if (std::strcmp(source, "auto") == 0) {
        const Sample test = take_sample(false);
        use_dumpsys = !test.has_current;
    } else {
        std::fprintf(stderr, "未知数据源：%s（可选 auto / sysfs / dumpsys）\n", source);
        return 2;
    }

    if (!quiet) {
        std::fprintf(stderr, "数据源：%s\n",
                     use_dumpsys ? "dumpsys（库仑计差值）" : "sysfs（电流积分）");
    }

    if (interval < 0.005) interval = 0.005;  // 200 Hz 上限，再快 sysfs 也跟不上
    if (interval > 60.0) interval = 60.0;

    FILE* fh = stdout;
    if (out_path != nullptr) {
        fh = std::fopen(out_path, "w");
        if (fh == nullptr) {
            std::fprintf(stderr, "无法写 %s: %s\n", out_path, std::strerror(errno));
            return 3;
        }
    }

    std::signal(SIGINT, on_signal);
    std::signal(SIGTERM, on_signal);

    Writer writer(fh);
    writer.header();

    const double t0 = now_s();
    double next = 0.0;
    long long count = 0;
    long long missing_current = 0;
    double worst_gap = 0.0;
    double gap_sum = 0.0;
    double last_stamp = 0.0;

    if (!quiet) {
        std::fprintf(stderr, "开始采样：间隔 %.3f s，%s\n", interval,
                     duration > 0 ? "限时模式" : "手动停止模式（Ctrl+C）");
    }

    while (!g_stop) {
        const double now = now_s() - t0;
        if (duration > 0 && now >= duration) break;

        Sample s = take_sample(use_dumpsys);
        writer.row(now, s);
        ++count;
        if (!s.has_current) ++missing_current;

        if (count > 1) {
            double gap = now - last_stamp;
            gap_sum += gap;
            if (gap > worst_gap) worst_gap = gap;
        }
        last_stamp = now;

        // 每 5 秒落盘一次：既保证断电不丢太多，又不让 IO 拖累采样。
        if (count % static_cast<long long>(std::max(1.0, 5.0 / interval)) == 0) {
            writer.flush();
            if (!quiet) {
                std::fprintf(stderr, "\r已采样 %lld 点  SOC=%.0f%%  %.0f mA  %.0f mV",
                             count, s.has_soc ? s.soc : -1.0, s.current_ma, s.voltage_mv);
            }
        }

        next += interval;
        // 落后太多说明负载把采样线程饿死了，重设基准避免补跑一堆空转。
        if (now > next + 5.0 * interval) next = now + interval;
        sleep_until(t0 + next);
    }

    writer.flush();
    if (fh != stdout) std::fclose(fh);

    if (!quiet) {
        const double elapsed = now_s() - t0;
        const double mean_gap = count > 1 ? gap_sum / static_cast<double>(count - 1) : 0.0;
        std::fprintf(stderr,
                     "\n\n采样结束\n"
                     "  总点数      %lld\n"
                     "  时长        %.0f s\n"
                     "  平均间隔    %.4f s（目标 %.3f）\n"
                     "  最大间隔    %.4f s\n"
                     "  电流缺失    %lld\n",
                     count, elapsed, mean_gap, interval, worst_gap, missing_current);
        if (elapsed > 0) {
            std::fprintf(stderr, "  有效采样率  %.1f Hz\n",
                         static_cast<double>(count) / elapsed);
        }
    }
    return 0;
}
