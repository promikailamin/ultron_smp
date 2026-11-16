#!/usr/bin/env python3
"""Query a Minecraft Java server's status through host:port."""
import json
import socket
import struct
import sys
import time


def write_varint(value):
    out = b""
    value &= 0xFFFFFFFF
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out += bytes([byte | 0x80])
        else:
            out += bytes([byte])
            return out


def read_varint(sock):
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


def read_packet(sock):
    length = read_varint(sock)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise EOFError("socket closed while reading packet")
        data += chunk
    return data


def main():
    host, port = sys.argv[1], int(sys.argv[2])
    started = time.perf_counter()
    with socket.create_connection((host, port), timeout=15) as sock:
        connect_ms = (time.perf_counter() - started) * 1000
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
        packet_id = packet[0]
        if packet_id != 0:
            raise SystemExit(f"unexpected packet id {packet_id}")
        data = packet[1:]
        length = 0
        shift = 0
        index = 0
        while True:
            byte = data[index]
            index += 1
            length |= (byte & 0x7F) << shift
            shift += 7
            if not byte & 0x80:
                break
        status = json.loads(data[index:index + length].decode())
    print(f"connect={connect_ms:.0f}ms version={status['version']['name']} motd={status['description']} players={status['players']['online']}/{status['players']['max']}")


if __name__ == "__main__":
    main()
