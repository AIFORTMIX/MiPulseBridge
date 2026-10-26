package com.mipulsebridge.hook;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import com.mipulsebridge.BpmProtocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 运行在被 Hook 进程(小米运动健康)内的 BPM 推送器。
 *
 * 维护一条到本模块进程 BridgeServerThread 的 LocalSocket 连接;
 * 断线时按指数退避重连,相同 BPM 在去重窗口内不重复发送。
 */
public final class HrSender {

    private static final String TAG = "MiPulseBridgeHook";

    private static final long DEDUP_MS = 2000L;      // 相同 BPM 去重窗口
    private static final long MAX_RECONNECT_MS = 10000L;

    private static HrSender instance;

    private LocalSocket socket;
    private OutputStream out;
    private int lastBpm = -1;
    private long lastSendTs = 0L;
    private long backoffMs = 100L;

    private HrSender() {
    }

    public static synchronized HrSender get() {
        if (instance == null) {
            instance = new HrSender();
        }
        return instance;
    }

    /**
     * 推送一条心率。
     *
     * @return true 表示已送入网络栈;false 表示连接不可用(会触发重连)
     */
    public synchronized boolean sendBpm(int bpm) {
        if (bpm < 30 || bpm > 250) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (bpm == lastBpm && now - lastSendTs < DEDUP_MS) {
            return false; // 值相同且刚发过,跳过
        }

        if (!ensureConnected()) {
            scheduleReconnect();
            return false;
        }

        try {
            String line = BpmProtocol.encodeBpm(bpm);
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
            lastBpm = bpm;
            lastSendTs = now;
            backoffMs = 100L;
            return true;
        } catch (IOException e) {
            closeQuietly();
            scheduleReconnect();
            return false;
        }
    }

    private boolean ensureConnected() {
        if (socket != null && socket.isConnected()) {
            return true;
        }
        try {
            closeQuietly();
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(BpmProtocol.SOCKET_NAME));
            socket = s;
            out = s.getOutputStream();
            backoffMs = 100L;
            Log.i(TAG, "已连接 MiPulseBridge IPC:" + BpmProtocol.SOCKET_NAME);
            return true;
        } catch (IOException e) {
            closeQuietly();
            return false;
        }
    }

    private void scheduleReconnect() {
        // 不在此线程内 sleep:心率回调频率高(运动模式下约 1 次/秒),
        // 下一次 sendBpm 会自然重试,避免阻塞被 Hook App 的回调线程。
        Log.d(TAG, "IPC 不可用,将在下次心率回调时重连(backoff=" + backoffMs + "ms)");
        backoffMs = Math.min(backoffMs * 2, MAX_RECONNECT_MS);
    }

    private void closeQuietly() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
        out = null;
    }
}
