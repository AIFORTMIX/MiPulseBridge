package com.mipulsebridge;

/**
 * 桥接进程(被 Hook 的小米运动健康)与服务进程(本模块)之间的 IPC 协议。
 *
 * 通道:Android LocalServerSocket(abstract namespace,无需文件)
 * 协议:每行一条消息,文本 UTF-8
 *   BPM|<value>   推送一条心率值
 *   PING          连通性探测(服务端回 PONG)
 */
public final class BpmProtocol {

    /** LocalServerSocket 抽象名称,两端必须一致 */
    public static final String SOCKET_NAME = "mipulsebridge_ipc";

    public static final String MSG_BPM_PREFIX = "BPM|";
    public static final String MSG_PING = "PING";
    public static final String MSG_PONG = "PONG";

    private BpmProtocol() {
    }

    /** 编码一条 BPM 消息 */
    public static String encodeBpm(int bpm) {
        return MSG_BPM_PREFIX + bpm;
    }

    /**
     * 解析一行消息。
     *
     * @return 有效 BPM 值(30..250)返回整数;无效返回 -1
     */
    public static int parseBpm(String line) {
        if (line == null) {
            return -1;
        }
        String t = line.trim();
        if (!t.startsWith(MSG_BPM_PREFIX)) {
            return -1;
        }
        try {
            int bpm = Integer.parseInt(t.substring(MSG_BPM_PREFIX.length()).trim());
            if (bpm >= 30 && bpm <= 250) {
                return bpm;
            }
        } catch (NumberFormatException ignored) {
            // 非法格式,忽略
        }
        return -1;
    }
}
