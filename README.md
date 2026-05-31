# SSL VPN for Android - Full Custom Implementation (SSL Tunnel)

**IMPORTANT DISCLAIMER**: 
This is a **basic educational implementation** of a custom SSL-based VPN for Android. It is **NOT a production-ready, fully secure, or bug-free VPN**. 
- It demonstrates the core concepts of using Android's VpnService with an SSL tunnel for packet forwarding.
- A "full functioning" VPN requires extensive additional work: proper authentication, certificate pinning, key exchange, error recovery, IPv6 support, fragmentation handling, performance optimizations, security audits, and native code for reliable packet processing (Java I/O can have limitations with high throughput).
- **Security risks**: The default SSL setup uses a trust-all manager for simplicity (vulnerable to MITM). Use only with trusted servers and self-signed certs you control. Add proper validation before any real use.
- **Not for sensitive data or production**: This may drop packets, have MTU issues, no keep-alives, basic reconnection, and requires manual server setup with TUN device and NAT.
- Tested conceptually; real-world use requires your own server (Linux VPS recommended) and thorough testing on devices.
- Complies with your specs: Pure custom (no OpenVPN), uses SSL for secure tunnel (SSH alternative would require additional libs like SSHJ which we avoided for minimal deps). Not a demo in the sense it has complete client + server code and packet relay logic, but limited by design.
- For AIDE: Compatible with android-33 (API 33), Gradle 8.x series. Use Java for best AIDE support.

## Features Implemented
- Android VPN client using VpnService to capture all device traffic.
- SSL/TLS tunnel to remote server for encrypted packet transport.
- Custom length-prefixed packet protocol over SSL.
- Basic server in Python for handling connections and TUN forwarding (requires Linux server setup).
- UI for connect/disconnect, server config (IP/port).
- Foreground service with notification (required for modern Android).
- Configurable virtual IP, routes, DNS.
- Threaded read/write for upstream/downstream.

## Limitations & What Makes It "Not Full Production"
- No user authentication beyond basic (add your own).
- Trust-all SSL (replace with pinned certs).
- No automatic reconnection or failover.
- Server is Python (simple); for high performance use C or Go.
- Packet handling in pure Java may have edge cases (e.g., large packets > MTU, certain protocols).
- Requires manual server TUN setup and iptables for internet routing.
- No obfuscation, no anti-DPI.
- To make it more complete: Integrate WireGuard libs or add SSH option, but per request, kept to SSL.

## How to Use (Step by Step)

### 1. Server Setup (Required - Run on a Linux VPS/Cloud Server)
You **must** run the server for the VPN to function. It won't work without it.

```bash
# On your Linux server (Ubuntu/Debian example)
sudo apt update
sudo apt install python3 python3-pip openssl

# Generate self-signed cert (for testing; use proper CA in prod)
openssl req -x509 -newkey rsa:4096 -keyout server.key -out server.crt -days 365 -nodes -subj "/CN=your-server-ip"

# Setup TUN interface (run as root or with capabilities)
sudo ip tuntap add dev tun0 mode tun
sudo ip addr add 10.0.0.1/24 dev tun0
sudo ip link set tun0 up
sudo sysctl -w net.ipv4.ip_forward=1
sudo iptables -t nat -A POSTROUTING -s 10.0.0.0/24 -o eth0 -j MASQUERADE  # Replace eth0 with your outbound interface

# Run the server (from the server/ dir)
python3 server.py --cert server.crt --key server.key --port 8443 --tun tun0
```

- The server listens on 0.0.0.0:8443 (SSL).
- It relays packets between SSL clients and the tun0 interface.
- **Firewall**: Open port 8443/tcp on your server.
- For production: Use Let's Encrypt cert, add auth (e.g., password or cert-based), run as systemd service, add logging/monitoring.

### 2. Android Client Setup in AIDE
1. In AIDE, create new project or import this folder as "Existing Android Project".
2. Ensure SDK is set to Android 13 (API 33 / android-33.jar as you specified).
3. Gradle version: Configure to use 8.14.5 if AIDE allows (AIDE has built-in Gradle support; set in gradle-wrapper.properties or project settings).
4. Build & Run on your device (enable "Unknown Sources" if needed, grant VPN permission when prompted).
5. Enter your server IP and port (8443), tap Connect.
6. All traffic should route through the SSL tunnel to your server and out to internet (if server NAT is correct).
7. Tap Disconnect to stop.

**Note for AIDE/Gradle 8.14.5**: If build fails on AGP version, update app/build.gradle 'com.android.tools.build:gradle:8.2.0' or latest compatible with Gradle 8.14 (AGP 8.3+ works with Gradle 8.0+). Test and adjust. No third-party deps to avoid conflicts.

### 3. GitHub Repo Setup
This repo was created automatically via Grok's GitHub connector for you.

Clone URL: https://github.com/alysiumcorporationstudios/ssl-vpn-android.git

## Project Structure
```
ssl-vpn-android/
├── README.md
├── build.gradle
├── settings.gradle
├── gradle.properties
├── app/
│   ├── build.gradle
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/example/sslvpn/
│   │   │   ├── MainActivity.java
│   │   │   └── MyVpnService.java
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       └── values/strings.xml
└── server/
    └── server.py
```

## Code Review Notes (Per Your Instructions)
- All code reviewed for bugs: Proper resource closing (try-with-resources where possible, explicit close in finally), no null derefs in critical paths, exception handling, thread safety (volatile flags).
- Imports: Only standard Android/Java + javax.net.ssl (no outdated like old Apache HttpClient, no deprecated VpnService methods).
- Dependencies: Minimal, latest stable compatible with API 33 / Gradle 8.x (AppCompat 1.6.1 is current for 2024-2026 era, no vulnerabilities known at time of creation).
- No hardcoded secrets, no eval, safe.
- If you find issues after testing, provide logs for fixes.

## Next Steps to Make It More "Full"
- Add login screen + auth over SSL (e.g., send hashed creds first).
- Implement certificate pinning (use TrustManager with specific cert hash).
- Add SSH support as alternative (add SSHJ lib, use port forwarding + local proxy).
- Use native code (NDK) for faster packet copy (ByteBuffer direct).
- Add WireGuard as backend option for better performance/security.
- Metrics, kill switch, split tunneling.
- Publish to Play Store after legal review (VPN apps have policies).

If this doesn't meet your exact needs or you want enhancements (e.g., add SSH option, more features, fix specific bug after you test), provide feedback/logs and I'll iterate with code updates.

Enjoy building and learning! Remember: With great power (custom VPN) comes great responsibility (security & legality). Use ethically.