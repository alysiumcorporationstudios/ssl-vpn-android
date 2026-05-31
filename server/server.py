#!/usr/bin/env python3
"""
SSL VPN Server - Companion to Android Client
Basic packet relay over SSL to a TUN interface.

Requirements:
- Python 3.8+
- Run with root/sudo for TUN access and iptables
- pip install pytun  (optional, for easier TUN; fallback to raw /dev/net/tun)

Usage:
  python3 server.py --cert server.crt --key server.key --port 8443 --tun tun0

This is a starting point. For production:
- Add authentication (e.g., client cert or password handshake)
- Use proper logging, rate limiting, multiple clients
- Consider async (asyncio) or C extension for performance
- Add IPv6, better error recovery

WARNING: This is educational. Review for security before exposing to internet.
"""

import argparse
import os
import socket
import ssl
import struct
import threading
from typing import Optional

# Try to use pytun if available, else raw TUN
try:
    from pytun import TunTapDevice
    USE_PYTUN = True
except ImportError:
    USE_PYTUN = False
    print("pytun not installed. Using raw /dev/net/tun (requires CAP_NET_ADMIN)")

class SSLVPNServer:
    def __init__(self, certfile: str, keyfile: str, port: int = 8443, tun_name: str = "tun0"):
        self.certfile = certfile
        self.keyfile = keyfile
        self.port = port
        self.tun_name = tun_name
        self.tun_fd: Optional[int] = None
        self.tun = None
        self.clients = {}  # conn -> thread
        self.running = False

    def setup_tun(self):
        """Setup TUN interface (assumes pre-created or creates if pytun)."""
        if USE_PYTUN:
            self.tun = TunTapDevice(name=self.tun_name, flags=pytun.IFF_TUN | pytun.IFF_NO_PI)
            self.tun.addr = '10.0.0.1'
            self.tun.netmask = '255.255.255.0'
            self.tun.mtu = 1400
            self.tun.up()
            self.tun_fd = self.tun.fileno()
            print(f"TUN {self.tun_name} created via pytun at 10.0.0.1/24")
        else:
            # Raw TUN - user must pre-create: ip tuntap add dev tun0 mode tun; ip addr add 10.0.0.1/24 dev tun0; ip link set up
            TUN_PATH = f"/dev/net/{self.tun_name}"
            if not os.path.exists(TUN_PATH):
                raise RuntimeError(f"TUN device {TUN_PATH} not found. Create it manually first.")
            self.tun_fd = os.open(TUN_PATH, os.O_RDWR)
            print(f"Using pre-existing TUN at {TUN_PATH}")

        # Enable forwarding (user should have done sysctl + iptables)
        print("Ensure IP forwarding and NAT are enabled on host (see README)")

    def handle_client(self, conn: ssl.SSLSocket, addr):
        """Handle one client: relay packets between SSL and TUN."""
        print(f"New client connected from {addr}")
        try:
            # Simple handshake check
            hello = conn.recv(1024)
            if b"VPN_CLIENT_HELLO" not in hello:
                print("Invalid client hello, closing")
                return

            # Send server hello
            conn.send(b"VPN_SERVER_READY")

            while True:
                # Read length-prefixed from client
                len_data = conn.recv(4)
                if not len_data or len(len_data) < 4:
                    break
                pkt_len = struct.unpack("!I", len_data)[0]
                if pkt_len > 2000 or pkt_len < 0:
                    print(f"Invalid packet len {pkt_len} from {addr}")
                    break

                packet = b""
                while len(packet) < pkt_len:
                    chunk = conn.recv(pkt_len - len(packet))
                    if not chunk:
                        raise ConnectionError("Client disconnected mid-packet")
                    packet += chunk

                # Write to TUN
                if self.tun_fd:
                    os.write(self.tun_fd, packet)

                # Read from TUN and send back (this is the relay loop)
                # Note: In real impl, use select() or threads per direction for better perf
                try:
                    tun_packet = os.read(self.tun_fd, 1600)
                    if tun_packet:
                        out_len = struct.pack("!I", len(tun_packet))
                        conn.send(out_len + tun_packet)
                except BlockingIOError:
                    pass  # No packet ready
                except Exception as e:
                    print(f"TUN read error: {e}")

        except Exception as e:
            print(f"Client {addr} error: {e}")
        finally:
            conn.close()
            print(f"Client {addr} disconnected")

    def start(self):
        self.running = True
        self.setup_tun()

        context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
        context.load_cert_chain(certfile=self.certfile, keyfile=self.keyfile)
        context.minimum_version = ssl.TLSVersion.TLSv1_2

        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("0.0.0.0", self.port))
            sock.listen(5)
            print(f"SSL VPN Server listening on 0.0.0.0:{self.port}")

            while self.running:
                try:
                    conn, addr = sock.accept()
                    ssl_conn = context.wrap_socket(conn, server_side=True)
                    thread = threading.Thread(
                        target=self.handle_client,
                        args=(ssl_conn, addr),
                        daemon=True
                    )
                    thread.start()
                    self.clients[ssl_conn] = thread
                except KeyboardInterrupt:
                    break
                except Exception as e:
                    print(f"Accept error: {e}")

    def stop(self):
        self.running = False
        for conn in list(self.clients.keys()):
            try:
                conn.close()
            except:
                pass
        if self.tun_fd:
            os.close(self.tun_fd)
        print("Server stopped")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="SSL VPN Server")
    parser.add_argument("--cert", required=True, help="Path to server.crt")
    parser.add_argument("--key", required=True, help="Path to server.key")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--tun", default="tun0")
    args = parser.parse_args()

    server = SSLVPNServer(args.cert, args.key, args.port, args.tun)
    try:
        server.start()
    except KeyboardInterrupt:
        server.stop()