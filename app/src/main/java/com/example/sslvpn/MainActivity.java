package com.example.sslvpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private EditText etServerIp;
    private EditText etServerPort;
    private Button btnConnect;
    private Button btnDisconnect;
    private TextView tvStatus;

    private static final int VPN_REQUEST_CODE = 1001;
    private String serverIp;
    private int serverPort;

    private final BroadcastReceiver vpnStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            if (status != null) {
                tvStatus.setText(status);
                if (status.contains("Connected")) {
                    btnConnect.setEnabled(false);
                    btnDisconnect.setEnabled(true);
                } else if (status.contains("Disconnected")) {
                    btnConnect.setEnabled(true);
                    btnDisconnect.setEnabled(false);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServerIp = findViewById(R.id.etServerIp);
        etServerPort = findViewById(R.id.etServerPort);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        tvStatus = findViewById(R.id.tvStatus);

        btnConnect.setOnClickListener(v -> startVpn());
        btnDisconnect.setOnClickListener(v -> stopVpn());

        // Register for status updates from service
        IntentFilter filter = new IntentFilter("com.example.sslvpn.VPN_STATUS");
        registerReceiver(vpnStatusReceiver, filter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(vpnStatusReceiver);
    }

    private void startVpn() {
        serverIp = etServerIp.getText().toString().trim();
        String portStr = etServerPort.getText().toString().trim();

        if (serverIp.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_fields, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            serverPort = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        } else {
            startVpnService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService();
        } else if (requestCode == VPN_REQUEST_CODE) {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, MyVpnService.class);
        intent.putExtra("server_ip", serverIp);
        intent.putExtra("server_port", serverPort);
        startService(intent);
        tvStatus.setText(getString(R.string.status_connecting));
        btnConnect.setEnabled(false);
    }

    private void stopVpn() {
        Intent intent = new Intent(this, MyVpnService.class);
        intent.setAction("STOP_VPN");
        startService(intent);
        btnConnect.setEnabled(true);
        btnDisconnect.setEnabled(false);
        tvStatus.setText(R.string.status_disconnected);
    }
}