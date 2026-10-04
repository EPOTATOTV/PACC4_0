package com.potatotv.paccclient.probe;

/**
 * 顶层窗口信息（文档 §4.1，仅标题与归属进程，不截屏）。
 *
 * @param title       窗口标题
 * @param processName 所属进程名
 * @param pid         所属进程号
 */
public record WindowInfo(String title, String processName, int pid) {
}