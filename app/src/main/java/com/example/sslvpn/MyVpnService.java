package com.example.sslvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MyVpnService extends VpnService {

    private static final String TAG = "MyVpnService";
    private static final String CHANNEL_ID = "ssl_vpn_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int MTU = 1400;  // Safe MTU to avoid fragmentation issues

    private ParcelFileDescriptor vpnInterface;
    private SSLSocket sslSocket;
    private InputStream sslInput;
    private OutputStream sslOutput;
    private FileInputStream vpnInput;
    private FileOutputStream vpnOutput;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private ExecutorService executorService;
    private Thread upstreamThread;
    private Thread downstreamThread;

    private String serverIp;
    private int serverPort;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_VPN".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        if (isRunning.get()) {
            return START_STICKY;
        }

        serverIp = intent.getStringExtra("server_ip");
        serverPort = intent.getIntExtra("server_port", 8443);

        if (serverIp == null || serverIp.isEmpty()) {
            Log.e(TAG, "No server IP provided");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification());
        establishVpn();
        startTunnel();

        return START_STICKY;
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SSL VPN Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.vpn_notification_title))
                .setContentText(getString(R.string.vpn_notification_text))
                .setSmallIcon(android.R.drawable.ic_lock_lock)  // Default icon; replace with custom in prod
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void establishVpn() {
        Builder builder = new Builder();
        builder.setSession("SSL VPN Session");
        builder.addAddress("10.0.0.2", 24);  // Client IP in the tunnel subnet
        builder.addRoute("0.0.0.0", 0);      // Route all traffic
        builder.addDnsServer("8.8.8.8");
        builder.addDnsServer("8.8.4.4");
        builder.setMtu(MTU);
        builder.setBlocking(false);  // Non-blocking for better performance

        try {
            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                throw new IOException("Failed to establish VPN interface");
            }
            vpnInput = new FileInputStream(vpnInterface.getFileDescriptor());
            vpnOutput = new FileOutputStream(vpnInterface.getFileDescriptor());
            Log.i(TAG, "VPN interface established successfully");
        } catch (IOException e) {
            Log.e(TAG, "Failed to establish VPN", e);
            stopVpn();
        }
    }

    private void startTunnel() {
        isRunning.set(true);
        executorService = Executors.newFixedThreadPool(2);

        try {
            // Create SSL context with trust-all for demo (REPLACE IN PRODUCTION with proper pinning!)
            SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, null);

            Socket rawSocket = new Socket();
            rawSocket.connect(new InetSocketAddress(serverIp, serverPort), 10000);
            sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(rawSocket, serverIp, serverPort, true);
            sslSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            sslSocket.startHandshake();

            sslInput = sslSocket.getInputStream();
            sslOutput = sslSocket.getOutputStream();

            Log.i(TAG, "SSL tunnel established to " + serverIp + ":" + serverPort);

            // Send initial handshake / auth placeholder (expand for real auth)
            sslOutput.write("VPN_CLIENT_HELLO".getBytes());
            sslOutput.flush();

            // Start threads
            upstreamThread = new Thread(this::upstreamLoop, "UpstreamThread");
            downstreamThread = new Thread(this::downstreamLoop, "DownstreamThread");
            upstreamThread.start();
            downstreamThread.start();

            sendStatusBroadcast("Connected to " + serverIp + ":" + serverPort);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start SSL tunnel", e);
            sendStatusBroadcast("Connection failed: " + e.getMessage());
            stopVpn();
        }
    }

    private void upstreamLoop() {
        byte[] buffer = new byte[MTU + 100];  // Extra for safety
        while (isRunning.get() && vpnInput != null && sslOutput != null) {
            try {
                int length = vpnInput.read(buffer);
                if (length > 0) {
                    // Send length-prefixed packet over SSL
                    ByteBuffer lenBuf = ByteBuffer.allocate(4);
                    lenBuf.putInt(length);
                    sslOutput.write(lenBuf.array());
                    sslOutput.write(buffer, 0, length);
                    sslOutput.flush();
                } else if (length < 0) {
                    break;
                }
            } catch (IOException e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Upstream error", e);
                }
                break;
            }
        }
        if (isRunning.get()) {
            stopVpn();
        }
    }

    private void downstreamLoop() {
        byte[] lenBytes = new byte[4];
        while (isRunning.get() && sslInput != null && vpnOutput != null) {
            try {
                int read = sslInput.read(lenBytes);
                if (read < 4) {
                    if (read < 0) break;
                    continue;
                }
                int packetLength = ByteBuffer.wrap(lenBytes).getInt();
                if (packetLength <= 0 || packetLength > MTU * 2) {
                    Log.w(TAG, "Invalid packet length: " + packetLength);
                    continue;
                }

                byte[] packet = new byte[packetLength];
                int totalRead = 0;
                while (totalRead < packetLength) {
                    int r = sslInput.read(packet, totalRead, packetLength - totalRead);
                    if (r < 0) {
                        throw new IOException("Connection closed during read");
                    }
                    totalRead += r;
                }

                vpnOutput.write(packet);
                vpnOutput.flush();
            } catch (IOException e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Downstream error", e);
                }
                break;
            }
        }
        if (isRunning.get()) {
            stopVpn();
        }
    }

    private void stopVpn() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }

        Log.i(TAG, "Stopping VPN service...");

        // Interrupt threads
        if (upstreamThread != null) upstreamThread.interrupt();
        if (downstreamThread != null) downstreamThread.interrupt();

        if (executorService != null) {
            executorService.shutdownNow();
        }

        // Close resources safely
        closeQuietly(sslInput);
        closeQuietly(sslOutput);
        closeQuietly(sslSocket);
        closeQuietly(vpnInput);
        closeQuietly(vpnOutput);
        closeQuietly(vpnInterface);

        stopForeground(true);
        stopSelf();

        sendStatusBroadcast("Disconnected");

        Log.i(TAG, "VPN service stopped");
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void sendStatusBroadcast(String status) {
        Intent intent = new Intent("com.example.sslvpn.VPN_STATUS");
        if ("Disconnected".equals(status)) {
            intent.putExtra("status", getString(R.string.status_disconnected));
        } else if (status.startsWith("Connection failed")) {
            intent.putExtra("status", getString(R.string.error_connection, status));
        } else {
            // For connected, format the string properly
            intent.putExtra("status", getString(R.string.status_connected, serverIp, serverPort));
        }
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }

    // Trust-all manager for demo ONLY - DO NOT USE IN PRODUCTION
    private static class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }
}