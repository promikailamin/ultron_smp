#!/usr/bin/env python3
"""Measure the GitHub runner -> Minekube Connect tunnel path.

The client -> tunnel leg can look fine while the server (GitHub runner) ->
tunnel leg is slow. This diagnostic separates the two minekube endpoints that
matter for the server side:

* watch-connect.minekube.net:443 - the long-lived control/watch websocket.
* connect.minekube.net:443 - the Connect edge metadata/anycast endpoint.
* <endpoint>.play.minekube.net:25565 - the public address, including the
  full edge -> connector -> Paper path when the server is online.

It records DNS, TCP connect, TLS handshake and (for Minecraft ports) a status
ping, then prints a short interpretation. It never reads tokens or secrets.

Usage:
    python3 scripts/tunnel-latency.py --endpoint mikailamin
    python3 scripts/tunnel-latency.py --samples 10 --json-out .agent-work/outputs/tunnel.json
"""
from __future__ import annotations

import argparse
import json
import socket
import ssl
import statistics
import struct
import sys
import time
from typing import Any


def write_varint(value: int) -> bytes:
    out = b""
    value &= 0xFFFFFFFF
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out += bytes([byte | 0x80])
        else:
            return out + bytes([byte])


def read_varint(sock: socket.socket) -> int:
    value = 0
    for shift in range(0, 35, 7):
        data = sock.recv(1)
        if not data:
            raise EOFError("socket closed while reading varint")
        byte = data[0]
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value
    raise ValueError("varint too long")


def read_packet(sock: socket.socket) -> bytes:
    length = read_varint(sock)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise EOFError("socket closed while reading packet")
        data += chunk
    return data


def minecraft_status(host: str, port: int, timeout: float) -> dict[str, Any]:
    started = time.perf_counter()
    with socket.create_connection((host, port), timeout=timeout) as sock:
        host_bytes = host.encode()
        handshake = (
            write_varint(0)
            + write_varint(769)
            + write_varint(len(host_bytes))
            + host_bytes
            + struct.pack(">H", port)
            + write_varint(1)
        )
        sock.sendall(write_varint(len(handshake)) + handshake)
        sock.sendall(write_varint(1) + write_varint(0))
        packet = read_packet(sock)
        if not packet or packet[0] != 0:
            raise ValueError("unexpected Minecraft status packet")
        data = packet[1:]
        index = 0
        length = 0
        shift = 0
        while True:
            byte = data[index]
            index += 1
            length |= (byte & 0x7F) << shift
            shift += 7
            if not byte & 0x80:
                break
        status = json.loads(data[index:index + length].decode())
    elapsed = (time.perf_counter() - started) * 1000
    version = status.get("version", {}).get("name", "unknown")
    players = status.get("players", {})
    return {
        "ms": elapsed,
        "version": version,
        "online": players.get("online"),
        "max": players.get("max"),
    }


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * fraction)))
    return ordered[index]


def summarize(values: list[float], failures: int, attempts: int) -> dict[str, Any]:
    if not values:
        return {
            "attempts": attempts,
            "samples": 0,
            "failures": failures,
            "loss_percent": round(100.0 * failures / attempts, 1) if attempts else 0.0,
        }
    return {
        "attempts": attempts,
        "samples": len(values),
        "failures": failures,
        "loss_percent": round(100.0 * failures / attempts, 1) if attempts else 0.0,
        "min_ms": round(min(values), 2),
        "avg_ms": round(statistics.mean(values), 2),
        "p95_ms": round(percentile(values, 0.95), 2),
        "max_ms": round(max(values), 2),
        "jitter_ms": round(statistics.pstdev(values), 2) if len(values) > 1 else 0.0,
    }


def resolve(host: str, port: int) -> dict[str, Any]:
    started = time.perf_counter()
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError as exc:
        return {"error": str(exc), "dns_ms": round((time.perf_counter() - started) * 1000, 2)}
    dns_ms = (time.perf_counter() - started) * 1000
    addresses: list[str] = []
    for info in infos:
        address = info[4][0]
        if address not in addresses:
            addresses.append(address)
    return {"dns_ms": round(dns_ms, 2), "addresses": addresses}


def measure_tcp(host: str, port: int, samples: int, timeout: float, pause: float) -> dict[str, Any]:
    values: list[float] = []
    failures = 0
    peers: list[str] = []
    for _ in range(samples):
        started = time.perf_counter()
        try:
            with socket.create_connection((host, port), timeout=timeout) as sock:
                elapsed = (time.perf_counter() - started) * 1000
                values.append(elapsed)
                peer = sock.getpeername()[0]
                if peer not in peers:
                    peers.append(peer)
        except OSError:
            failures += 1
        if pause:
            time.sleep(pause)
    result = summarize(values, failures, samples)
    result["peers"] = peers
    return result


def measure_tls(host: str, port: int, samples: int, timeout: float, pause: float) -> dict[str, Any]:
    context = ssl.create_default_context()
    values: list[float] = []
    failures = 0
    for _ in range(samples):
        started = time.perf_counter()
        try:
            with socket.create_connection((host, port), timeout=timeout) as raw:
                with context.wrap_socket(raw, server_hostname=host) as tls:
                    tls.do_handshake()
                    values.append((time.perf_counter() - started) * 1000)
        except (OSError, ssl.SSLError):
            failures += 1
        if pause:
            time.sleep(pause)
    return summarize(values, failures, samples)


def measure_minecraft(host: str, port: int, samples: int, timeout: float, pause: float) -> dict[str, Any]:
    values: list[float] = []
    failures = 0
    last: dict[str, Any] = {}
    for _ in range(samples):
        try:
            last = minecraft_status(host, port, timeout)
            values.append(float(last["ms"]))
        except (OSError, ValueError, EOFError, json.JSONDecodeError):
            failures += 1
        if pause:
            time.sleep(pause)
    result = summarize(values, failures, samples)
    for key in ("version", "online", "max"):
        if key in last:
            result[key] = last[key]
    return result


def recommendations(report: dict[str, Any]) -> list[str]:
    tips: list[str] = []
    watch = report["targets"].get("watch") or {}
    edge = report["targets"].get("edge") or {}
    public = report["targets"].get("public") or {}

    watch_avg = watch.get("tcp", {}).get("avg_ms")
    lookups = [watch.get("dns", {}).get("dns_ms"), edge.get("dns", {}).get("dns_ms")]
    lookups = [value for value in lookups if isinstance(value, (int, float))]

    if watch.get("tcp", {}).get("loss_percent", 0) > 0:
        tips.append("The runner -> watch service path lost TCP connections. The runner's egress route "
                    "is unstable; retry the run or use a self-hosted runner/VPS near the tunnel edge.")
    if isinstance(watch_avg, (int, float)) and watch_avg > 150:
        tips.append("Runner -> watch service latency is high (>150 ms). The tunnel control traffic and "
                    "connector reconnects will be slow; a runner/VPS closer to the Connect edge helps.")
    if isinstance(watch_avg, (int, float)) and watch_avg <= 150:
        tips.append("Runner -> watch service latency is acceptable. If gameplay is still slow, the "
                    "bottleneck is downstream (edge <-> connector <-> Paper), not connector registration.")
    if lookups and max(lookups) > 250:
        tips.append("DNS resolution is slow. Configure the runner to use a faster resolver to shorten "
                    "connector startup and reconnection time.")
    if public.get("minecraft", {}).get("failures", 0) > 0:
        tips.append("The public Minecraft status ping failed. Either Paper/Connect is not ready yet, or "
                    "the edge -> connector path is broken. Compare this result with the server log.")
    elif isinstance(public.get("minecraft", {}).get("avg_ms"), (int, float)):
        status_avg = public["minecraft"]["avg_ms"]
        if status_avg > 350:
            tips.append(f"Public status round-trip is {status_avg:.0f} ms. That includes client-edge, "
                        "edge-tunnel and tunnel-Paper; try the Gate connector or a nearer runner region.")
        else:
            tips.append(f"Public status round-trip is {status_avg:.0f} ms, which is usable for the "
                        "runner -> edge -> Paper path measured from the runner itself.")
    tips.append("Apply scripts/network-tune.sh (BBR + fq + shallow send queue) before starting Paper to "
                "remove kernel-side stalls on long or lossy routes.")
    return tips


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", default="mikailamin",
                        help="Minekube endpoint name (default: mikailamin)")
    parser.add_argument("--samples", type=int, default=8, help="TCP samples per target (default: 8)")
    parser.add_argument("--timeout", type=float, default=5.0, help="Per-connection timeout in seconds")
    parser.add_argument("--pause", type=float, default=0.15, help="Pause between samples in seconds")
    parser.add_argument("--soak-seconds", type=float, default=0,
                        help="Extra seconds of public status pings after the main run")
    parser.add_argument("--label", default="runner", help="Label stored in the JSON report")
    parser.add_argument("--json-out", default="", help="Write the full report as JSON to this path")
    args = parser.parse_args()

    endpoint_host = f"{args.endpoint}.play.minekube.net"
    targets = {
        "watch": ("watch-connect.minekube.net", 443, "tls"),
        "edge": ("connect.minekube.net", 443, "tls"),
        "public": (endpoint_host, 25565, "minecraft"),
    }

    report: dict[str, Any] = {
        "label": args.label,
        "endpoint": args.endpoint,
        "timestamp_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "samples": args.samples,
        "targets": {},
    }

    for name, (host, port, extra) in targets.items():
        entry: dict[str, Any] = {
            "host": host,
            "port": port,
            "dns": resolve(host, port),
            "tcp": measure_tcp(host, port, args.samples, args.timeout, args.pause),
        }
        if extra == "tls":
            entry["tls"] = measure_tls(host, port, args.samples, args.timeout, args.pause)
        elif extra == "minecraft":
            entry["minecraft"] = measure_minecraft(host, port, args.samples, args.timeout, args.pause)
        report["targets"][name] = entry

    if args.soak_seconds > 0:
        report["soak"] = measure_minecraft(
            endpoint_host, 25565, max(1, int(args.soak_seconds)), args.timeout, 1.0
        )

    report["recommendations"] = recommendations(report)

    for name, entry in report["targets"].items():
        tcp = entry["tcp"]
        label = f"{name} ({entry['host']}:{entry['port']})"
        if tcp.get("samples"):
            print(f"{label}: tcp avg={tcp['avg_ms']:.1f}ms p95={tcp['p95_ms']:.1f}ms "
                  f"jitter={tcp['jitter_ms']:.1f}ms loss={tcp['loss_percent']:.1f}%")
        else:
            print(f"{label}: tcp FAILED ({tcp.get('failures', 0)}/{tcp.get('attempts', 0)})")
        dns = entry["dns"]
        if "error" in dns:
            print(f"  dns: FAILED ({dns['error']})")
        else:
            print(f"  dns: {dns['dns_ms']:.1f}ms addresses={','.join(dns['addresses'])}")
        if "tls" in entry and entry["tls"].get("samples"):
            tls = entry["tls"]
            print(f"  tls: avg={tls['avg_ms']:.1f}ms p95={tls['p95_ms']:.1f}ms "
                  f"jitter={tls['jitter_ms']:.1f}ms")
        if "minecraft" in entry:
            mc = entry["minecraft"]
            if mc.get("samples"):
                print(f"  minecraft status: avg={mc['avg_ms']:.1f}ms version={mc.get('version')} "
                      f"players={mc.get('online')}/{mc.get('max')}")
            else:
                print(f"  minecraft status: FAILED ({mc.get('failures', 0)}/{mc.get('attempts', 0)})")

    print("\nInterpretation:")
    for tip in report["recommendations"]:
        print(f"- {tip}")

    if args.json_out:
        import os
        directory = os.path.dirname(args.json_out)
        if directory:
            os.makedirs(directory, exist_ok=True)
        with open(args.json_out, "w", encoding="utf-8") as stream:
            json.dump(report, stream, indent=2, sort_keys=True)
            stream.write("\n")
        print(f"\nJSON report: {args.json_out}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
