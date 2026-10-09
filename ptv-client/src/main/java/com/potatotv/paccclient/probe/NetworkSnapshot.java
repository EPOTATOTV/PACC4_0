package com.potatotv.paccclient.probe;

import java.util.List;

/**
 * 网络连接快照（文档 §4.8 / §5.2，本地连接表只读采样，不抓包）。
 *
 * @param connections 连接列表
 */
public record NetworkSnapshot(List<ConnectionInfo> connections) {

    public NetworkSnapshot {
        connections = connections == null ? List.of() : List.copyOf(connections);
    }

    public static NetworkSnapshot empty() {
        return new NetworkSnapshot(List.of());
    }

    /** 连接状态。 */
    public enum ConnectionState {
        ESTABLISHED,
        LISTEN,
        TIME_WAIT,
        CLOSE_WAIT,
        SYN_SENT,
        OTHER
    }

    /**
     * 单条连接。
     *
     * @param protocol    协议（TCP / UDP）
     * @param localAddress 本地地址
     * @param localPort   本地端口
     * @param remoteHost  远端地址（监听态为空）
     * @param remotePort  远端端口
     * @param state       连接状态
     * @param pid         持有连接的进程号（不可得置 0）
     * @param processName 进程名（不可得为空串）
     */
    public record ConnectionInfo(
            String protocol,
            String localAddress,
            int localPort,
            String remoteHost,
            int remotePort,
            ConnectionState state,
            int pid,
            String processName) {
    }
}