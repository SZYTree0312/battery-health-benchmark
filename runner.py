"""电池放电流水线编排器（PC 侧）。

把「探测 → 采集 → 拉回 → 算容量」串成一条命令，省掉手敲 adb。

两种采集模式
------------
shell   纯 adb shell + cat 轮询，零编译、开箱即用。1 Hz 采样够用，
        单次往返约 100–300 ms，实测精度损失 < 1%。
native  用 collector-native/bhbench.cpp 编出的二进制，可上 20–200 Hz，
        能抓电流阶跃测内阻。需要先装 Android NDK 编译。

    python runner.py probe                 # 探测机型与可读节点
    python runner.py run --design 5000     # 自动选模式，采完直接出报告

    python runner.py run --design 5000 --mode native --interval 0.05
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEVICE_BIN = "/data/local/tmp/bhbench"
DEVICE_CSV = "/data/local/tmp/session.csv"
LOCAL_BIN = ROOT / "collector-native" / "bhbench"
DATA_DIR = ROOT / "data"

PS = "/sys/class/power_supply/battery"
PS_ALT = "/sys/class/power_supply/bms"

# 字段 → 候选路径。和 collector-native/bhbench.cpp 保持同一套顺序。
NODE_CANDIDATES: dict[str, list[str]] = {
    "current_now": [
        f"{PS}/current_now",
        f"{PS_ALT}/current_now",
        f"{PS}/Battery_Current",
        f"{PS}/current_avg",
    ],
    "voltage_now": [
        f"{PS}/voltage_now",
        f"{PS_ALT}/voltage_now",
        f"{PS}/Battery_Voltage",
    ],
    "capacity": [f"{PS}/capacity", f"{PS_ALT}/capacity"],
    "temp": [f"{PS}/temp", f"{PS_ALT}/temp", f"{PS}/Battery_Temp"],
    "status": [f"{PS}/status", f"{PS_ALT}/status"],
    "charge_full": [
        f"{PS}/charge_full",
        f"{PS_ALT}/charge_full",
        f"{PS}/charge_counter",
    ],
    "charge_full_design": [f"{PS}/charge_full_design", f"{PS_ALT}/charge_full_design"],
    "cycle_count": [f"{PS}/cycle_count", f"{PS_ALT}/cycle_count"],
}

CSV_HEADER = ("t_s,current_ma,voltage_mv,soc_pct,temp_c,status,"
              "charge_full_mah,design_mah,cycle_count")


# ---------------------------------------------------------------- adb 基础

def find_adb() -> str:
    found = shutil.which("adb")
    if found:
        return found
    candidates = [
        Path("C:/Android/adb.exe"),
        Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe",
        Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb.exe",
        Path("/usr/lib/android-sdk/platform-tools/adb"),
    ]
    for cand in candidates:
        if cand.is_file():
            return str(cand)
    raise SystemExit("找不到 adb。把 platform-tools 加进 PATH，或放到 C:/Android 下。")


def adb(adb_path: str, *args: str, serial: str | None = None,
        capture: bool = True) -> subprocess.CompletedProcess:
    cmd = [adb_path]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    return subprocess.run(cmd, capture_output=capture, text=True,
                          encoding="utf-8", errors="replace")


def pick_device(adb_path: str, serial: str | None) -> str:
    out = adb(adb_path, "devices").stdout or ""
    entries = {}
    for line in out.splitlines()[1:]:
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) >= 2:
            entries[parts[0]] = parts[1]

    ready = [s for s, state in entries.items() if state == "device"]
    unauthorized = [s for s, state in entries.items() if state == "unauthorized"]

    if unauthorized:
        raise SystemExit(
            f"设备 {unauthorized[0]} 未授权。请在手机屏幕上点「允许 USB 调试」，"
            "勾选「始终允许」后重跑。"
        )
    if not ready:
        raise SystemExit("没有可用设备。确认 USB 调试已开、数据线是数据线不是充电线。")
    if serial:
        if serial not in ready:
            raise SystemExit(f"设备 {serial} 不在可用列表里：{ready}")
        return serial
    if len(ready) > 1:
        raise SystemExit(f"连着多台设备，用 --serial 指定：{ready}")
    return ready[0]


# ---------------------------------------------------------------- 探测

def shell_probe(adb_path: str, serial: str) -> dict[str, str]:
    """逐个尝试候选路径，返回「字段 → 实际可用路径」。"""
    resolved: dict[str, str] = {}
    for field, paths in NODE_CANDIDATES.items():
        for path in paths:
            got = adb(adb_path, "shell", f"cat {path} 2>/dev/null", serial=serial)
            value = (got.stdout or "").strip()
            if value:
                resolved[field] = path
                print(f"  [可读] {field:<18} {path:<48} = {value}")
                break
        else:
            print(f"  [缺失] {field:<18} 所有候选路径都读不到")
    return resolved


def cmd_probe(adb_path: str, serial: str) -> int:
    def prop(name: str) -> str:
        return (adb(adb_path, "shell", "getprop", name, serial=serial).stdout or "").strip()

    print(f"设备：{prop('ro.product.brand')} {prop('ro.product.model')}"
          f"（{prop('ro.build.version.release')} / SDK {prop('ro.build.version.sdk')}）")
    uid = (adb(adb_path, "shell", "id -u", serial=serial).stdout or "").strip()
    print(f"shell uid：{uid}{'（root）' if uid == '0' else '（非 root）'}\n")

    resolved = shell_probe(adb_path, serial)
    print(f"\nsysfs 可读 {len(resolved)}/{len(NODE_CANDIDATES)} 个字段")

    snap = dumpsys_snapshot(adb_path, serial)
    counter = snap.get("Charge counter", "").strip()
    print(f"dumpsys  Charge counter = {counter or '（无）'}"
          f"   level = {snap.get('level', '?')}%"
          f"   voltage = {snap.get('voltage', '?')} mV"
          f"   status = {STATUS_MAP.get(snap.get('status', ''), '?')}")

    print()
    if "current_now" in resolved:
        print("走 sysfs 直读：可做电流积分，若用 native 高频采样还能测内阻。")
        print("下一步：python runner.py run --design <标称容量>")
        return 0
    if counter:
        print("sysfs 被系统权限挡住，但库仑计可读 → 走 dumpsys 通道。")
        print("这条通道没有实时电流，用计数器首尾差算容量，精度取决于厂商标定。")
        print("下一步：python runner.py run --design <标称容量> --source dumpsys --interval 5")
        return 0
    print("两条通道都不通：既读不到 sysfs，也没有 Charge counter。")
    print("这台机器只能用「满电到关机的时长 × 平均功耗」粗估，本工具帮不上。")
    return 1


# ---------------------------------------------------------------- shell 采集

def _read_all(adb_path: str, serial: str, resolved: dict[str, str]) -> dict[str, str]:
    """一次 shell 往返读全部字段。"""
    script = "; ".join(f'echo "{f}=$(cat {p} 2>/dev/null)"'
                       for f, p in resolved.items())
    out = adb(adb_path, "shell", script, serial=serial).stdout or ""
    values: dict[str, str] = {}
    for line in out.splitlines():
        if "=" in line:
            key, _, val = line.partition("=")
            values[key.strip()] = val.strip()
    return values


def _to_float(raw: str, default: float = 0.0) -> float:
    try:
        return float(raw)
    except (TypeError, ValueError):
        return default


def shell_collect(adb_path: str, serial: str, resolved: dict[str, str],
                  interval: float, duration: float, out_path: Path) -> int:
    t0 = time.monotonic()
    count = 0
    missing = 0
    next_at = 0.0

    print(f"开始采样：间隔 {interval}s，"
          f"{'限时 %ds' % duration if duration > 0 else 'Ctrl+C 停止'}\n", flush=True)

    with open(out_path, "w", encoding="utf-8", newline="") as fh:
        fh.write(CSV_HEADER + "\n")
        try:
            while True:
                now = time.monotonic() - t0
                if duration > 0 and now >= duration:
                    break

                v = _read_all(adb_path, serial, resolved)
                if "current_now" not in v or not v["current_now"]:
                    missing += 1

                current_ma = _to_float(v.get("current_now", "")) / 1000.0
                voltage_mv = _to_float(v.get("voltage_now", "")) / 1000.0
                soc = _to_float(v.get("capacity", ""), -1.0)
                raw_temp = _to_float(v.get("temp", ""), 250.0)
                temp_c = raw_temp / 10.0 if abs(raw_temp) > 200 else raw_temp
                status = v.get("status", "unknown") or "unknown"

                # 方向统一：放电为正，充电为负。各家驱动符号相反，
                # 以 status 为准，不信符号本身。
                if status.lower().startswith("discharg"):
                    current_ma = abs(current_ma)
                elif status.lower().startswith("charg"):
                    current_ma = -abs(current_ma)

                charge_full = _to_float(v.get("charge_full", "")) / 1000.0
                design = _to_float(v.get("charge_full_design", "")) / 1000.0
                cycle = _to_float(v.get("cycle_count", ""), 0.0)

                fh.write(f"{now:.2f},{current_ma:.1f},{voltage_mv:.0f},{soc:.1f},"
                         f"{temp_c:.2f},{status},{charge_full:.0f},{design:.0f},"
                         f"{cycle:.0f}\n")
                count += 1
                if count % 5 == 0:
                    fh.flush()

                if count % 10 == 0 or count == 1:
                    print(f"\r已采样 {count} 点  SOC={soc:.0f}%  "
                          f"{current_ma:.0f} mA  {voltage_mv:.0f} mV", end="", flush=True)

                next_at += interval
                sleep_for = next_at - (time.monotonic() - t0)
                if sleep_for > 0:
                    time.sleep(sleep_for)
                elif sleep_for < -5 * interval:
                    # 落后太多，重设基准，别补跑空转
                    next_at = time.monotonic() - t0 + interval
        except KeyboardInterrupt:
            print("\n已中断采样")
            return 130

    elapsed = time.monotonic() - t0
    print(f"\n\n采样结束：{count} 点 / {elapsed:.0f}s / "
          f"有效 {count / elapsed:.1f} Hz / 电流缺失 {missing}")
    return 0


# ---------------------------------------------------------------- dumpsys 采集
#
# 小米 HyperOS / 部分 Android 14+ 机型对 shell 做了 SELinux 收紧，
# /sys/class/power_supply/ 整个目录都读不了（连 ls 都会 Permission denied）。
# 但 `dumpsys battery` 走的是 BatteryService，shell 一直有权限，含：
#   Charge counter  —— 库仑计当前剩余电荷（µAh），实测约 20-25 秒跳一次
#   voltage / temperature / level / status
# 没有实时电流，因此改用「计数器首尾差」算容量，比电流积分更准。

STATUS_MAP = {"1": "unknown", "2": "charging", "3": "discharging",
              "4": "not-charging", "5": "full"}


def dumpsys_snapshot(adb_path: str, serial: str) -> dict[str, str]:
    out = adb(adb_path, "shell", "dumpsys battery", serial=serial).stdout or ""
    values: dict[str, str] = {}
    for line in out.splitlines():
        if ":" not in line:
            continue
        key, _, val = line.partition(":")
        values[key.strip()] = val.strip()
    return values


def dumpsys_supported(adb_path: str, serial: str) -> bool:
    snap = dumpsys_snapshot(adb_path, serial)
    return bool(snap.get("Charge counter", "").strip())


def dumpsys_collect(adb_path: str, serial: str, interval: float,
                    duration: float, out_path: Path) -> int:
    t0 = time.monotonic()
    count = 0
    next_at = 0.0

    print(f"数据源：dumpsys battery（库仑计差值）  间隔 {interval}s，"
          f"{'限时 %ds' % duration if duration > 0 else 'Ctrl+C 停止'}\n", flush=True)

    with open(out_path, "w", encoding="utf-8", newline="") as fh:
        fh.write(CSV_HEADER + "\n")
        try:
            while True:
                now = time.monotonic() - t0
                if duration > 0 and now >= duration:
                    break

                snap = dumpsys_snapshot(adb_path, serial)
                level = _to_float(snap.get("level", ""), -1.0)
                voltage = _to_float(snap.get("voltage", ""), 0.0)
                raw_temp = _to_float(snap.get("temperature", ""), 250.0)
                temp_c = raw_temp / 10.0 if abs(raw_temp) > 200 else raw_temp
                counter_uah = _to_float(snap.get("Charge counter", ""), 0.0)
                status = STATUS_MAP.get(snap.get("status", ""), "unknown")

                # 电流拿不到，写 0；分析器会据此自动切换到计数器通道。
                fh.write(f"{now:.2f},0.0,{voltage:.0f},{level:.1f},"
                         f"{temp_c:.2f},{status},{counter_uah / 1000.0:.0f},0,0\n")
                count += 1
                if count % 3 == 0:
                    fh.flush()

                if count % 6 == 0 or count == 1:
                    print(f"\r已采样 {count} 点  {level:.0f}%  "
                          f"{voltage:.0f} mV  {status}  库仑计 {counter_uah / 1000.0:.0f} mAh",
                          end="", flush=True)

                next_at += interval
                sleep_for = next_at - (time.monotonic() - t0)
                if sleep_for > 0:
                    time.sleep(sleep_for)
                elif sleep_for < -5 * interval:
                    next_at = time.monotonic() - t0 + interval
        except KeyboardInterrupt:
            print("\n已中断采样")
            return 130

    elapsed = time.monotonic() - t0
    print(f"\n\n采样结束：{count} 点 / {elapsed:.0f}s / "
          f"有效 {count / elapsed:.2f} Hz")
    return 0


# ---------------------------------------------------------------- native 采集

def native_collect(adb_path: str, serial: str, interval: float,
                   duration: float, out_path: Path) -> int:
    if not LOCAL_BIN.is_file():
        raise SystemExit(
            f"本地没有 {LOCAL_BIN}。先到 collector-native/ 用 NDK 编译，"
            "或改用 --mode shell。"
        )
    adb(adb_path, "push", str(LOCAL_BIN), DEVICE_BIN, serial=serial)
    adb(adb_path, "shell", "chmod 755 " + DEVICE_BIN, serial=serial)

    shell_cmd = f"{DEVICE_BIN} --interval {interval} --out {DEVICE_CSV}"
    if duration > 0:
        shell_cmd += f" --duration {duration:.0f}"

    rc = subprocess.run([adb_path, "-s", serial, "shell", shell_cmd]).returncode
    pulled = adb(adb_path, "pull", DEVICE_CSV, str(out_path), serial=serial,
                 capture=False)
    if pulled.returncode != 0 or not out_path.is_file():
        raise SystemExit("拉取 CSV 失败")
    return rc


# ---------------------------------------------------------------- 离线采集
#
# 关键约束：手机插着 USB 就在充电，根本不会放电。
# 所以采集必须能在拔线后继续跑 —— 靠 bhbench 的 --daemon 自守护实现
# （adb shell 结束会回收进程组，nohup / setsid 实测都拦不住）。

STATE_FILE = ROOT / ".session"


def push_native(adb_path: str, serial: str) -> None:
    if not LOCAL_BIN.is_file():
        raise SystemExit(
            f"没有 {LOCAL_BIN}。到 collector-native/ 下执行：\n"
            "  ANDROID_NDK_HOME=/path/to/ndk ./build.sh"
        )
    adb(adb_path, "push", str(LOCAL_BIN), DEVICE_BIN, serial=serial)
    adb(adb_path, "shell", f"chmod 755 {DEVICE_BIN}", serial=serial)


def cmd_start(adb_path: str, serial: str, args: argparse.Namespace) -> int:
    push_native(adb_path, serial)

    stamp = time.strftime("%Y%m%d_%H%M%S")
    remote = f"/data/local/tmp/session_{stamp}.csv"

    cmd = (f"{DEVICE_BIN} --daemon --source {args.source} "
           f"--interval {args.interval} --out {remote}")
    adb(adb_path, "shell", cmd, serial=serial)

    time.sleep(1.5)  # 等子进程落盘第一行
    check = adb(adb_path, "shell", f"wc -l {remote} 2>/dev/null; "
                                   f"ps -A | grep -c '[b]hbench'", serial=serial).stdout
    if "bhbench" in (check or "") or not check:
        pass

    STATE_FILE.write_text(remote, encoding="utf-8")

    print("已在设备上启动后台采集。")
    print(f"  设备端文件：{remote}")
    print(f"  采样间隔：{args.interval}s  数据源：{args.source}")
    print()
    print("现在可以拔掉数据线了。放电结束后（手机自动关机）再插回来，执行：")
    print("  python runner.py fetch --design <标称容量>")
    return 0


def cmd_stop(adb_path: str, serial: str) -> int:
    adb(adb_path, "shell", "pkill -f 'bhbench --daemon'", serial=serial)
    print("已停止设备上的采集进程。")
    return 0


def cmd_fetch(adb_path: str, serial: str, args: argparse.Namespace) -> int:
    if not STATE_FILE.is_file():
        raise SystemExit("没有进行中的会话，先执行 python runner.py start")
    remote = STATE_FILE.read_text(encoding="utf-8").strip()

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    local = DATA_DIR / Path(remote).name
    pulled = adb(adb_path, "pull", remote, str(local), serial=serial, capture=False)
    if pulled.returncode != 0 or not local.is_file():
        raise SystemExit(f"拉取失败：{remote}")

    print(f"已拉回 {local}（{local.stat().st_size} 字节）")

    design = args.design
    if design is None:
        design = _ask_design()
        if design is None:
            print(f"跳过分析。可用：python analyzer/cli.py {local} --design 7000")
            return 0

    print()
    sys.path.insert(0, str(ROOT))
    from analyzer.cli import main as analyze_main  # noqa: E402

    analyze_main([str(local), "--design", str(design)])
    return 0


# ---------------------------------------------------------------- 主流程

def cmd_run(adb_path: str, serial: str, args: argparse.Namespace) -> int:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d_%H%M%S")
    local_csv = Path(args.out) if args.out else DATA_DIR / f"session_{stamp}.csv"

    source = args.source
    if source == "auto":
        if LOCAL_BIN.is_file():
            source = "native"
            print("自动选择：native（已找到编译好的二进制）\n")
        else:
            probe = adb(adb_path, "shell", f"cat {PS}/current_now 2>/dev/null",
                        serial=serial).stdout or ""
            if probe.strip():
                source = "sysfs"
                print("自动选择：sysfs（shell 能直接读电源节点）\n")
            elif dumpsys_supported(adb_path, serial):
                source = "dumpsys"
                print("自动选择：dumpsys（sysfs 被 SELinux 挡住，改走库仑计）\n")
            else:
                raise SystemExit(
                    "这台机器两条路都不通：sysfs 读不到，dumpsys 也没有 Charge counter。\n"
                    "先跑 python runner.py probe 看看具体情况。"
                )

    if source == "native":
        rc = native_collect(adb_path, serial, args.interval, args.duration, local_csv)
    elif source == "sysfs":
        resolved = shell_probe(adb_path, serial)
        if "current_now" not in resolved:
            raise SystemExit("sysfs 读不到 current_now，改用 --source dumpsys 试试。")
        print()
        rc = shell_collect(adb_path, serial, resolved, args.interval,
                           args.duration, local_csv)
    else:
        rc = dumpsys_collect(adb_path, serial, args.interval, args.duration, local_csv)

    if not local_csv.is_file():
        raise SystemExit("没有产出 CSV")

    print(f"\n数据在 {local_csv}（{local_csv.stat().st_size} 字节）")

    design = args.design
    if design is None:
        design = _ask_design()
        if design is None:
            print(f"跳过分析。可用：python analyzer/cli.py {local_csv} --design 5000")
            return rc

    print()
    sys.path.insert(0, str(ROOT))
    from analyzer.cli import main as analyze_main  # noqa: E402

    analyze_main([str(local_csv), "--design", str(design)])
    return rc


def _ask_design() -> float | None:
    raw = input("厂商标称容量（mAh，直接回车跳过分析）：").strip()
    if not raw:
        return None
    try:
        return float(raw)
    except ValueError:
        print("不是数字，跳过分析")
        return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="电池健康度采集编排器")
    parser.add_argument("--serial", help="多设备时指定序列号")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("probe", help="探测机型与可读 sysfs 节点")

    run_p = sub.add_parser("run", help="采集并立即分析")
    run_p.add_argument("--source", choices=["auto", "sysfs", "dumpsys", "native"],
                       default="auto",
                       help="sysfs=直读电源节点；dumpsys=走库仑计（系统收紧权限时用）；"
                            "native=预编译二进制")
    run_p.add_argument("--interval", type=float, default=1.0,
                       help="采样间隔秒；dumpsys 模式建议 5（计数器 20s 才跳一次）")
    run_p.add_argument("--duration", type=int, default=0,
                       help="采样秒数，0 表示手动停止")
    run_p.add_argument("--design", type=float, help="设计容量 mAh")
    run_p.add_argument("--out", help="CSV 输出路径")

    start_p = sub.add_parser("start", help="启动设备端后台采集，之后可以拔线")
    start_p.add_argument("--source", choices=["auto", "sysfs", "dumpsys"],
                         default="auto", help="默认 auto：sysfs 不通就走库仑计")
    start_p.add_argument("--interval", type=float, default=5.0,
                         help="采样间隔秒，库仑计 20s 才跳一次，5s 足够")

    sub.add_parser("stop", help="停止设备端采集进程")

    fetch_p = sub.add_parser("fetch", help="拉回数据并出报告")
    fetch_p.add_argument("--design", type=float, help="设计容量 mAh")

    args = parser.parse_args(argv)
    adb_path = find_adb()
    serial = pick_device(adb_path, args.serial)

    if args.cmd == "probe":
        return cmd_probe(adb_path, serial)
    if args.cmd == "start":
        return cmd_start(adb_path, serial, args)
    if args.cmd == "stop":
        return cmd_stop(adb_path, serial)
    if args.cmd == "fetch":
        return cmd_fetch(adb_path, serial, args)
    return cmd_run(adb_path, serial, args)


if __name__ == "__main__":
    raise SystemExit(main())
