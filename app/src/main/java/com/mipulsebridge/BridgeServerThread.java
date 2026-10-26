package com.mipulsebridge;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 本模块进程内的 LocalServerSocket 服务端。
 *
 * 被 LSPosed Hook 的小米运动健康进程(com.mi.health)作为 client 连接此 socket,
 * 按 BpmProtocol 逐行推送实时心率;本线程解析后交给 BleHeartRateService 对外广播。
 *
 * 小米运动健康进程被杀重启后会自动重连,因此 accept 循环持续运行。
 */
public class BridgeServerThread extends Thread {

    private static final String TAG = "MiPulseBridge";

    private final BleHeartRateService service;
    private volatile LocalServerSocket serverSocket;
    private volatile boolean running = true;

    public BridgeServerThread(BleHeartRateService service) {
        super("MiPulseBridge-BridgeServer");
        this.service = service;
    }

    @Override
    public void run() {
        try {
            serverSocket = new LocalServerSocket(BpmProtocol.SOCKET_NAME);
            Log.i(TAG, "IPC 服务端已监听 " + BpmProtocol.SOCKET_NAME);
        } catch (IOException e) {
            Log.e(TAG, "创建 LocalServerSocket 失败:" + e.getMessage());
            return;
        }

        while (running) {
            try {
                LocalSocket client = serverSocket.accept();
                Log.i(TAG, "Hook 进程已连接:" + client.getFileDescriptor());
                handleClient(client);
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept 中断:" + e.getMessage());
                }
            }
        }
    }

    private void handleClient(final LocalSocket client) {
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (BpmProtocol.MSG_PING.equals(line.trim())) {
                        continue; // 服务端不需要回应,PING 由对端自行判断
                    }
                    int bpm = BpmProtocol.parseBpm(line);
                    if (bpm > 0) {
                        service.onBpmReceived(bpm);
                    }
                }
            } catch (IOException e) {
                // client 断开或读失败,退出该连接线程等待重连
                Log.d(TAG, "Hook 连接已断开:" + e.getMessage());
            } finally {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
            }
        }, "MiPulseBridge-HookReader");
        reader.start();
    }

    /** 停止监听并关闭 socket */
    public void shutdown() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
