#!/usr/bin/env python3
"""Measure GitHub runner network speed, latency and stability.

The script is intentionally dependency-free so it can run on a stock
ubuntu-latest runner. It measures:

  * runner/system facts and the public egress address,
  * download and upload throughput (Cloudflare's speed endpoint by default),
  * TCP connect latency, jitter and loss to public and Twingate hosts,
  * a short soak test to expose stalls and intermittent failures,
  * optionally the Twingate Connector state through the Admin API and Docker.

It never reads or prints Twingate token values. The JSON report is useful for
comparing runs; the human-readable output is written to the workflow log.

The Twingate Connector carries traffic for remote Twingate clients. It does not
route the runner's own speed test traffic, so the throughput numbers describe
the runner's internet egress, not a client-to-connector game session.
"""

import argparse
import base64
import datetime
import json
import os
import platform
import random
import re
import shutil
import socket
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes={bytes}"
DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up"
DEFAULT_LATENCY_HOSTS = (
    "1.1.1.1:443,8.8.8.8:443,github.com:443,api.github.com:443,"
    "speed.cloudflare.com:443,twingate.com:443"
)
USER_AGENT = "ultron-smp-network-diagnostic/1.0"
ONLINE_CONNECTOR_STATES = {"ALIVE", "ONLINE", "HEALTHY", "CONNECTED"}


def env(name, default=""):
    value = os.environ.get(name, default)
    return value.strip() if isinstance(value, str) else value


def env_int(name, default):
    try:
        return int(env(name, str(default)))
    except (TypeError, ValueError):
        return default


def env_float(name, default):
    try:
        return float(env(name, str(default)))
    except (TypeError, ValueError):
        return default


def iso_now():
    return (
        datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def safe_label(value):
    cleaned = re.sub(r"[^A-Za-z0-9_.-]+", "-", value.strip()).strip("-")
    return cleaned or "runner"


def normalize_network(value):
    value = (value or "").strip()
    if "://" in value:
        value = value.split("://", 1)[1]
    value = value.split("/", 1)[0].rstrip(".")
    if value.endswith(".twingate.com"):
        value = value[: -len(".twingate.com")]
    return value.strip(".").replace("_", "-")


def derive_network_from_token(token):
    parts = (token or "").split(".")
    if len(parts) < 2:
        return ""
    payload = parts[1] + "=" * (-len(parts[1]) % 4)
    try:
        claims = json.loads(base64.urlsafe_b64decode(payload.encode("ascii")))
    except Exception:
        return ""
    audience = claims.get("aud")
    if isinstance(audience, list):
        audience = next((item for item in audience if isinstance(item, str) and item), None)
    if not isinstance(audience, str):
        return ""
    return normalize_network(audience)


def twingate_tenant():
    network = normalize_network(env("TWINGATE_NETWORK"))
    if network:
        return network
    for name in ("TWINGATE_ACCESS_TOKEN", "TWINGATE_API_TOKEN"):
        network = derive_network_from_token(env(name))
        if network:
            return network
    return ""


def run_command(args, timeout=15):
    try:
        result = subprocess.run(
            args,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        output = (result.stdout or "").strip()
        if result.returncode != 0 and not output:
            output = (result.stderr or "").strip()
        return {"ok": result.returncode == 0, "output": output}
    except (OSError, subprocess.SubprocessError) as exc:
        return {"ok": False, "output": str(exc)}


def public_ip(timeout=10):
    urls = (
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://www.cloudflare.com/cdn-cgi/trace",
    )
    for url in urls:
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = response.read(4096).decode("utf-8", errors="replace").strip()
            if "cdn-cgi/trace" in url:
                for line in body.splitlines():
                    if line.startswith("ip="):
                        return line[3:].strip()
            elif body and len(body) < 200 and "\n" not in body:
                return body
        except Exception:
            continue
    return "unknown"


def memory_total_bytes():
    try:
        with open("/proc/meminfo", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("MemTotal:"):
                    return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    return None


def system_info(skip_public_ip=False):
    disk = shutil.disk_usage(os.getcwd())
    info = {
        "timestamp": iso_now(),
        "hostname": socket.gethostname(),
        "platform": platform.platform(),
        "kernel": platform.release(),
        "machine": platform.machine(),
        "python": platform.python_version(),
        "cpu_count": os.cpu_count(),
        "memory_bytes": memory_total_bytes(),
        "disk_free_bytes": disk.free,
        "public_ip": "skipped" if skip_public_ip else public_ip(),
        "github_run_id": env("GITHUB_RUN_ID"),
        "github_runner_name": env("RUNNER_NAME"),
        "github_runner_os": env("RUNNER_OS"),
        "github_runner_arch": env("RUNNER_ARCH"),
        "default_route": run_command(["ip", "route", "show", "default"]).get("output", ""),
        "addresses": run_command(["ip", "-brief", "-4", "addr", "show", "scope", "global"]).get(
            "output", ""
        ),
        "tcp_congestion_control": run_command(
            ["sysctl", "-n", "net.ipv4.tcp_congestion_control"], timeout=5
        ).get("output", ""),
        "default_qdisc": run_command(["sysctl", "-n", "net.core.default_qdisc"], timeout=5).get(
            "output", ""
        ),
    }
    link_speeds = {}
    try:
        for name in sorted(os.listdir("/sys/class/net")):
            if name == "lo":
                continue
            try:
                with open(f"/sys/class/net/{name}/speed", encoding="utf-8") as handle:
                    link_speeds[name] = handle.read().strip()
            except (OSError, ValueError):
                pass
    except OSError:
        pass
    info["link_speeds_mbps"] = link_speeds
    return info


def parse_host(value):
    value = value.strip()
    if not value:
        return None
    if value.startswith("["):
        host, _, port = value[1:].partition("]:")
        return host, int(port or 443)
    if value.count(":") == 1:
        host, port = value.rsplit(":", 1)
        try:
            return host, int(port)
        except ValueError:
            return host, 443
    return value, 443


def latency_hosts(base_hosts, extra_hosts):
    hosts = []
    for item in str(base_hosts or "").split(",") + list(extra_hosts or []):
        parsed = parse_host(item)
        if parsed and parsed not in hosts:
            hosts.append(parsed)
    tenant = twingate_tenant()
    if tenant:
        parsed = (f"{tenant}.twingate.com", 443)
        if parsed not in hosts:
            hosts.append(parsed)
    return hosts


def tcp_latency_ms(host, port, timeout=5.0):
    infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    infos.sort(key=lambda item: 0 if item[0] == socket.AF_INET else 1)
    last_error = None
    for family, socktype, proto, _, sockaddr in infos:
        sock = socket.socket(family, socktype, proto)
        sock.settimeout(timeout)
        started = time.perf_counter()
        try:
            sock.connect(sockaddr)
            return (time.perf_counter() - started) * 1000.0
        except OSError as exc:
            last_error = exc
        finally:
            sock.close()
    raise last_error or OSError("could not connect")


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(round((len(ordered) - 1) * fraction))))
    return ordered[index]


def summarize_samples(samples):
    values = [item for item in samples if item is not None]
    total = len(samples)
    if not values:
        return {
            "samples": total,
            "successful": 0,
            "loss_percent": 100.0 if total else 0.0,
            "min_ms": None,
            "avg_ms": None,
            "median_ms": None,
            "p95_ms": None,
            "max_ms": None,
            "jitter_ms": None,
        }
    deltas = [abs(values[index] - values[index - 1]) for index in range(1, len(values))]
    return {
        "samples": total,
        "successful": len(values),
        "loss_percent": round(100.0 * (total - len(values)) / total, 2) if total else 0.0,
        "min_ms": round(min(values), 2),
        "avg_ms": round(statistics.mean(values), 2),
        "median_ms": round(statistics.median(values), 2),
        "p95_ms": round(percentile(values, 0.95), 2),
        "max_ms": round(max(values), 2),
        "jitter_ms": round(statistics.mean(deltas), 2) if deltas else 0.0,
    }


def measure_latency(host, port, probes, interval, timeout):
    try:
        tcp_latency_ms(host, port, timeout=timeout)
    except Exception:
        pass

    samples = []
    for index in range(probes):
        try:
            samples.append(tcp_latency_ms(host, port, timeout=timeout))
        except Exception:
            samples.append(None)
        if interval > 0 and index + 1 < probes:
            time.sleep(interval)

    result = summarize_samples(samples)
    result["host"] = host
    result["port"] = port
    try:
        resolution_started = time.perf_counter()
        addresses = sorted(
            {item[4][0] for item in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)}
        )
        result["dns_ms"] = round((time.perf_counter() - resolution_started) * 1000.0, 2)
        result["addresses"] = addresses
    except Exception as exc:
        result["dns_ms"] = None
        result["addresses"] = []
        result["dns_error"] = str(exc)
    return result


def build_url(template, size=None, cache_bust=True):
    url = template
    if size is not None:
        if "{bytes}" in url:
            url = url.replace("{bytes}", str(size))
        else:
            separator = "&" if "?" in url else "?"
            url = f"{url}{separator}bytes={size}"
    if cache_bust:
        separator = "&" if "?" in url else "?"
        url = f"{url}{separator}r={random.randrange(1, 1 << 31)}"
    return url


def measure_download(url, timeout):
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept-Encoding": "identity",
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
        },
    )
    started = time.perf_counter()
    total = 0
    with urllib.request.urlopen(request, timeout=timeout) as response:
        status = getattr(response, "status", 200)
        if status != 200:
            raise RuntimeError(f"HTTP {status}")
        while True:
            chunk = response.read(65536)
            if not chunk:
                break
            total += len(chunk)
    elapsed = time.perf_counter() - started
    if total <= 0 or elapsed <= 0:
        raise RuntimeError("empty response")
    return {
        "ok": True,
        "bytes": total,
        "seconds": round(elapsed, 6),
        "mbps": round(total * 8.0 / elapsed / 1_000_000.0, 2),
    }


def measure_upload(url, size, timeout):
    payload = b"\0" * size
    request = urllib.request.Request(
        url,
        data=payload,
        method="POST",
        headers={
            "User-Agent": USER_AGENT,
            "Content-Type": "application/octet-stream",
            "Content-Length": str(size),
            "Cache-Control": "no-cache",
        },
    )
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        status = getattr(response, "status", 200)
        if status < 200 or status >= 300:
            raise RuntimeError(f"HTTP {status}")
        response.read(4096)
    elapsed = time.perf_counter() - started
    if elapsed <= 0:
        raise RuntimeError("invalid timing")
    return {
        "ok": True,
        "bytes": size,
        "seconds": round(elapsed, 6),
        "mbps": round(size * 8.0 / elapsed / 1_000_000.0, 2),
    }


def summarize_rounds(rounds):
    successful = [item for item in rounds if item.get("ok")]
    speeds = [item["mbps"] for item in successful]
    result = {
        "rounds": rounds,
        "rounds_total": len(rounds),
        "rounds_ok": len(successful),
        "rounds_failed": len(rounds) - len(successful),
    }
    if speeds:
        result.update(
            {
                "mbps_min": round(min(speeds), 2),
                "mbps_avg": round(statistics.mean(speeds), 2),
                "mbps_median": round(statistics.median(speeds), 2),
                "mbps_max": round(max(speeds), 2),
            }
        )
    else:
        result.update(
            {
                "mbps_min": None,
                "mbps_avg": None,
                "mbps_median": None,
                "mbps_max": None,
            }
        )
    return result


def run_bandwidth(download_url, upload_url, download_bytes, upload_bytes, rounds, timeout):
    download_rounds = []
    for index in range(rounds):
        try:
            result = measure_download(build_url(download_url, download_bytes), timeout)
            result["round"] = index + 1
        except Exception as exc:
            result = {"round": index + 1, "ok": False, "error": str(exc)}
        download_rounds.append(result)

    upload_rounds = []
    for index in range(rounds):
        try:
            result = measure_upload(build_url(upload_url, cache_bust=True), upload_bytes, timeout)
            result["round"] = index + 1
        except Exception as exc:
            result = {"round": index + 1, "ok": False, "error": str(exc)}
        upload_rounds.append(result)

    return {
        "download": summarize_rounds(download_rounds),
        "upload": summarize_rounds(upload_rounds),
    }


def run_soak(download_url, soak_bytes, duration, interval, host, port, timeout):
    if duration <= 0:
        return {"enabled": False, "reason": "duration is zero"}

    deadline = time.monotonic() + duration
    samples = []
    index = 0
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break

        sample = {"sample": index + 1, "timestamp": iso_now()}
        try:
            download = measure_download(
                build_url(download_url, soak_bytes),
                timeout=min(timeout, max(5, remaining + 5)),
            )
            sample["download_mbps"] = download["mbps"]
            sample["download_bytes"] = download["bytes"]
            sample["ok"] = True
        except Exception as exc:
            sample["download_mbps"] = None
            sample["ok"] = False
            sample["error"] = str(exc)

        try:
            sample["latency_ms"] = round(tcp_latency_ms(host, port, timeout=5.0), 2)
        except Exception as exc:
            sample["latency_ms"] = None
            sample["latency_error"] = str(exc)

        samples.append(sample)
        index += 1

        remaining = deadline - time.monotonic()
        if remaining > 0 and interval > 0:
            time.sleep(min(interval, remaining))

    speeds = [item["download_mbps"] for item in samples if item.get("download_mbps")]
    latencies = [item["latency_ms"] for item in samples if item.get("latency_ms") is not None]
    failed = [item for item in samples if not item.get("ok")]
    return {
        "enabled": True,
        "duration_seconds": duration,
        "interval_seconds": interval,
        "samples": len(samples),
        "failed_samples": len(failed),
        "success_percent": round(100.0 * (len(samples) - len(failed)) / len(samples), 2)
        if samples
        else 0.0,
        "download_mbps_min": round(min(speeds), 2) if speeds else None,
        "download_mbps_avg": round(statistics.mean(speeds), 2) if speeds else None,
        "download_mbps_max": round(max(speeds), 2) if speeds else None,
        "latency_ms_min": round(min(latencies), 2) if latencies else None,
        "latency_ms_avg": round(statistics.mean(latencies), 2) if latencies else None,
        "latency_ms_max": round(max(latencies), 2) if latencies else None,
        "latency": summarize_samples(latencies),
        "details": samples,
    }


def docker_state(container):
    if not shutil.which("docker"):
        return {"available": False, "error": "docker is not installed"}
    result = run_command(
        ["docker", "inspect", "--format", "{{json .State}}", container],
        timeout=20,
    )
    if not result["ok"]:
        return {"available": True, "running": False, "error": result["output"]}
    try:
        state = json.loads(result["output"])
    except json.JSONDecodeError:
        return {"available": True, "running": False, "error": "could not parse docker state"}
    return {
        "available": True,
        "running": bool(state.get("Running")),
        "status": state.get("Status"),
        "restart_count": state.get("RestartCount"),
        "started_at": state.get("StartedAt"),
        "error": "",
    }


def admin_connector_states():
    if not (env("TWINGATE_API_TOKEN") or env("TWINGATE_ACCESS_TOKEN")):
        return [], "no Twingate Admin API token configured"
    script = os.path.join(SCRIPT_DIR, "twingate-admin.py")
    if not os.path.exists(script):
        return [], "twingate-admin.py is missing"
    result = run_command(
        [sys.executable, script, "status"],
        timeout=90,
    )
    if not result["ok"]:
        return [], "Twingate Admin API status failed"
    states = []
    in_connectors = False
    for line in result["output"].splitlines():
        if line.startswith("Connectors ("):
            in_connectors = True
            continue
        if not in_connectors:
            continue
        if line.startswith("  - "):
            match = re.search(r"state=([^|]+)", line)
            name = line[4:].split("|", 1)[0].strip()
            states.append(
                {
                    "name": name,
                    "state": match.group(1).strip() if match else "?",
                }
            )
        elif line and not line.startswith(" "):
            break
    return states, ""


def monitor_twingate(duration, interval, container):
    if duration <= 0:
        return {"enabled": False, "reason": "monitor duration is zero"}

    deadline = time.monotonic() + duration
    samples = []
    index = 0
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        states, error = admin_connector_states()
        sample = {
            "sample": index + 1,
            "timestamp": iso_now(),
            "connectors": states,
            "admin_error": error,
            "docker": docker_state(container),
        }
        samples.append(sample)
        index += 1
        remaining = deadline - time.monotonic()
        if remaining > 0 and interval > 0:
            time.sleep(min(interval, remaining))

    states_seen = [item["state"] for sample in samples for item in sample["connectors"]]
    online = sum(1 for state in states_seen if state.upper() in ONLINE_CONNECTOR_STATES)
    running = sum(1 for sample in samples if sample["docker"].get("running"))
    errors = sum(1 for sample in samples if sample.get("admin_error"))
    return {
        "enabled": True,
        "duration_seconds": duration,
        "interval_seconds": interval,
        "samples": len(samples),
        "state_samples": len(states_seen),
        "online_samples": online,
        "online_percent": round(100.0 * online / len(states_seen), 2) if states_seen else None,
        "docker_running_samples": running,
        "admin_error_samples": errors,
        "states": sorted(set(states_seen)),
        "details": samples,
    }


def verdict(report):
    download = report.get("bandwidth", {}).get("download", {})
    upload = report.get("bandwidth", {}).get("upload", {})
    download_avg = download.get("mbps_avg")
    upload_avg = upload.get("mbps_avg")

    latency_results = report.get("latency", [])
    losses = [item["loss_percent"] for item in latency_results if item.get("loss_percent") is not None]
    jitters = [item["jitter_ms"] for item in latency_results if item.get("jitter_ms") is not None]
    max_loss = max(losses) if losses else None
    max_jitter = max(jitters) if jitters else None

    if download_avg is None:
        bandwidth = "unavailable"
    elif download_avg >= 100 and (upload_avg or 0) >= 20:
        bandwidth = "good"
    elif download_avg >= 25 and (upload_avg or 0) >= 5:
        bandwidth = "fair"
    else:
        bandwidth = "poor"

    if max_loss is None:
        stability = "unavailable"
    elif max_loss <= 1 and (max_jitter or 0) <= 30:
        stability = "good"
    elif max_loss <= 3 and (max_jitter or 0) <= 75:
        stability = "fair"
    else:
        stability = "poor"

    twingate = report.get("twingate_monitor", {})
    if not twingate.get("enabled"):
        connector = "not monitored"
    elif twingate.get("online_percent") is None:
        connector = "unknown"
    elif twingate["online_percent"] >= 90 and twingate.get("docker_running_samples", 0) > 0:
        connector = "healthy"
    elif twingate.get("online_samples", 0) > 0:
        connector = "intermittent"
    else:
        connector = "offline"

    return {
        "bandwidth": bandwidth,
        "stability": stability,
        "twingate_connector": connector,
        "max_loss_percent": max_loss,
        "max_jitter_ms": max_jitter,
    }


def print_latency(item):
    if item.get("avg_ms") is None:
        print(
            "  {host}:{port}  unreachable (loss {loss}%)".format(
                host=item["host"],
                port=item["port"],
                loss=item.get("loss_percent", 100),
            )
        )
        return
    print(
        "  {host}:{port}  avg={avg} ms  median={median} ms  p95={p95} ms  "
        "jitter={jitter} ms  loss={loss}%".format(
            host=item["host"],
            port=item["port"],
            avg=item["avg_ms"],
            median=item["median_ms"],
            p95=item["p95_ms"],
            jitter=item["jitter_ms"],
            loss=item["loss_percent"],
        )
    )


def print_report(report):
    info = report["system"]
    bandwidth = report.get("bandwidth", {})
    summary = report["verdict"]

    print("=" * 78)
    print("Network diagnostic: {}".format(report["label"]))
    print("=" * 78)
    print("Time:            {}".format(info["timestamp"]))
    print("Public IP:       {}".format(info["public_ip"]))
    print("Host / platform: {} / {}".format(info["hostname"], info["platform"]))
    print("CPU / memory:    {} vCPU / {:.1f} GiB".format(
        info.get("cpu_count") or 0,
        (info.get("memory_bytes") or 0) / (1024 ** 3),
    ))
    print("Default route:   {}".format(info.get("default_route") or "unknown"))
    print("TCP tuning:      congestion={} qdisc={}".format(
        info.get("tcp_congestion_control") or "?",
        info.get("default_qdisc") or "?",
    ))
    if info.get("link_speeds_mbps"):
        print("Link speeds:     {}".format(info["link_speeds_mbps"]))
    print()

    for direction in ("download", "upload"):
        item = bandwidth.get(direction, {})
        if item.get("mbps_avg") is None:
            print("{}: no successful rounds ({} failed)".format(
                direction.capitalize(), item.get("rounds_failed", 0)
            ))
        else:
            print(
                "{}: avg {avg} Mbps (min {min}, max {max}) over {ok}/{total} rounds".format(
                    direction.capitalize(),
                    avg=item["mbps_avg"],
                    min=item["mbps_min"],
                    max=item["mbps_max"],
                    ok=item["rounds_ok"],
                    total=item["rounds_total"],
                )
            )
            for sample in item.get("rounds", []):
                if sample.get("ok"):
                    print(
                        "  round {}: {:.3f} MiB in {:.3f}s = {} Mbps".format(
                            sample["round"],
                            sample["bytes"] / (1024 ** 2),
                            sample["seconds"],
                            sample["mbps"],
                        )
                    )
                else:
                    print("  round {}: FAILED - {}".format(
                        sample.get("round", "?"), sample.get("error", "unknown error")
                    ))
    print()

    print("TCP connect latency ({} probes each):".format(report["settings"]["latency_probes"]))
    for item in report.get("latency", []):
        print_latency(item)
    print()

    soak = report.get("soak", {})
    if soak.get("enabled"):
        print(
            "Soak ({}s, {} samples): success={}%  download avg={} Mbps "
            "(min {}/max {})  latency avg={} ms (min {}/max {})".format(
                soak["duration_seconds"],
                soak["samples"],
                soak["success_percent"],
                soak["download_mbps_avg"],
                soak["download_mbps_min"],
                soak["download_mbps_max"],
                soak["latency_ms_avg"],
                soak["latency_ms_min"],
                soak["latency_ms_max"],
            )
        )
        if soak["failed_samples"]:
            print("  failed soak samples: {}".format(soak["failed_samples"]))
    else:
        print("Soak: skipped ({})".format(soak.get("reason", "disabled")))
    print()

    monitor = report.get("twingate_monitor", {})
    if monitor.get("enabled"):
        print(
            "Twingate connector monitor ({}s, {} samples): "
            "online={}%  docker_running={}/{}  states={}  admin_errors={}".format(
                monitor["duration_seconds"],
                monitor["samples"],
                monitor["online_percent"],
                monitor["docker_running_samples"],
                monitor["samples"],
                ", ".join(monitor["states"]) or "none",
                monitor["admin_error_samples"],
            )
        )
    else:
        print("Twingate connector monitor: skipped ({})".format(
            monitor.get("reason", "disabled")
        ))
    print()

    print(
        "Verdict: bandwidth={}  stability={}  twingate_connector={}".format(
            summary["bandwidth"],
            summary["stability"],
            summary["twingate_connector"],
        )
    )
    if report.get("json_output"):
        print("JSON report: {}".format(report["json_output"]))
    print("=" * 78)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--label", default=env("DIAG_LABEL", "runner"))
    parser.add_argument("--download-url", default=env("DIAG_DOWNLOAD_URL", DEFAULT_DOWNLOAD_URL))
    parser.add_argument("--upload-url", default=env("DIAG_UPLOAD_URL", DEFAULT_UPLOAD_URL))
    parser.add_argument(
        "--download-bytes", type=int, default=env_int("DIAG_DOWNLOAD_BYTES", 10_000_000)
    )
    parser.add_argument(
        "--upload-bytes", type=int, default=env_int("DIAG_UPLOAD_BYTES", 5_000_000)
    )
    parser.add_argument(
        "--bandwidth-rounds", type=int, default=env_int("DIAG_BANDWIDTH_ROUNDS", 3)
    )
    parser.add_argument(
        "--latency-probes", type=int, default=env_int("DIAG_LATENCY_PROBES", 20)
    )
    parser.add_argument(
        "--latency-interval",
        type=float,
        default=env_float("DIAG_LATENCY_INTERVAL", 0.25),
    )
    parser.add_argument(
        "--soak-seconds", type=int, default=env_int("DIAG_SOAK_SECONDS", 60)
    )
    parser.add_argument(
        "--soak-interval",
        type=float,
        default=env_float("DIAG_SOAK_INTERVAL", 5.0),
    )
    parser.add_argument(
        "--soak-bytes", type=int, default=env_int("DIAG_SOAK_BYTES", 1_000_000)
    )
    parser.add_argument(
        "--soak-host", default=env("DIAG_SOAK_HOST", "1.1.1.1")
    )
    parser.add_argument(
        "--soak-port", type=int, default=env_int("DIAG_SOAK_PORT", 443)
    )
    parser.add_argument(
        "--twingate-monitor-seconds",
        type=int,
        default=env_int("DIAG_TWINGATE_MONITOR_SECONDS", 0),
    )
    parser.add_argument(
        "--twingate-monitor-interval",
        type=float,
        default=env_float("DIAG_TWINGATE_MONITOR_INTERVAL", 10.0),
    )
    parser.add_argument(
        "--twingate-container",
        default=env("TWINGATE_CONNECTOR_NAME", "twingate-mccon"),
    )
    parser.add_argument(
        "--latency-hosts",
        default=env("DIAG_LATENCY_HOSTS", DEFAULT_LATENCY_HOSTS),
        help="Comma-separated host:port list to probe.",
    )
    parser.add_argument(
        "--latency-host",
        action="append",
        default=[],
        help="Extra host:port to probe (repeatable).",
    )
    parser.add_argument(
        "--timeout", type=float, default=env_float("DIAG_TIMEOUT", 120.0)
    )
    parser.add_argument(
        "--json-output",
        default=env("DIAG_JSON_OUTPUT", ""),
        help="Write the full JSON report to this path.",
    )
    parser.add_argument("--skip-bandwidth", action="store_true")
    parser.add_argument("--skip-latency", action="store_true")
    parser.add_argument("--skip-soak", action="store_true")
    parser.add_argument("--skip-public-ip", action="store_true")
    args = parser.parse_args()

    label = safe_label(args.label)
    json_output = args.json_output
    if not json_output:
        json_output = os.path.join(".agent-work", "outputs", f"network-diagnostic-{label}.json")

    report = {
        "label": label,
        "system": system_info(skip_public_ip=args.skip_public_ip),
        "settings": {
            "download_url": args.download_url,
            "upload_url": args.upload_url,
            "download_bytes": args.download_bytes,
            "upload_bytes": args.upload_bytes,
            "bandwidth_rounds": args.bandwidth_rounds,
            "latency_probes": args.latency_probes,
            "latency_interval": args.latency_interval,
            "soak_seconds": args.soak_seconds,
            "soak_interval": args.soak_interval,
            "soak_bytes": args.soak_bytes,
            "soak_host": args.soak_host,
            "soak_port": args.soak_port,
            "twingate_monitor_seconds": args.twingate_monitor_seconds,
            "twingate_monitor_interval": args.twingate_monitor_interval,
        },
        "bandwidth": {},
        "latency": [],
        "soak": {"enabled": False, "reason": "disabled by --skip-soak"},
        "twingate_monitor": {"enabled": False, "reason": "disabled by default"},
        "json_output": json_output,
    }

    if args.skip_bandwidth:
        report["bandwidth"] = {
            "download": {"rounds_total": 0, "rounds_ok": 0, "rounds_failed": 0},
            "upload": {"rounds_total": 0, "rounds_ok": 0, "rounds_failed": 0},
        }
    else:
        report["bandwidth"] = run_bandwidth(
            args.download_url,
            args.upload_url,
            args.download_bytes,
            args.upload_bytes,
            max(1, args.bandwidth_rounds),
            args.timeout,
        )

    if not args.skip_latency:
        hosts = latency_hosts(args.latency_hosts, args.latency_host)
        print("Probing {} hosts...".format(len(hosts)), file=sys.stderr)
        report["latency"] = [
            measure_latency(
                host,
                port,
                max(1, args.latency_probes),
                max(0.0, args.latency_interval),
                min(10.0, args.timeout),
            )
            for host, port in hosts
        ]

    if not args.skip_soak:
        report["soak"] = run_soak(
            args.download_url,
            args.soak_bytes,
            args.soak_seconds,
            args.soak_interval,
            args.soak_host,
            args.soak_port,
            args.timeout,
        )

    report["twingate_monitor"] = monitor_twingate(
        args.twingate_monitor_seconds,
        args.twingate_monitor_interval,
        args.twingate_container,
    )
    report["verdict"] = verdict(report)

    if json_output:
        os.makedirs(os.path.dirname(os.path.abspath(json_output)), exist_ok=True)
        with open(json_output, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2, sort_keys=True)
            handle.write("\n")

    print_report(report)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
