package com.potatotv.paccclient.probe;

/**
 * 指定进程的注入痕迹检测结果（文档 §4.3）。
 *
 * <p>全部为用户态可得数据：远程线程（起始地址不在已加载模块内的线程）、可执行读写内存区域
 * （非镜像的 PAGE_EXECUTE_READWRITE）、可疑句柄与待处理 APC。内核能力不可用时
 * {@link #supported()} 为 false，各计数保持 0，由调用方区分「没检出」与「查不了」。</p>
 *
 * @param supported              本次检测是否真的完成（false = 探针不可用 / 权限不足）
 * @param remoteThreadCount      远程线程数
 * @param executableRwRegionCount 可执行读写内存区域数
 * @param pendingApc             是否存在待处理 APC（用户态通常不可见，恒 false）
 * @param suspiciousHandleCount  可疑句柄数（外部进程持有的写内存 / 建线程句柄）
 * @param note                   降级说明（可为 null）
 */
public record InjectionReport(boolean supported, int remoteThreadCount, int executableRwRegionCount,
                              boolean pendingApc, int suspiciousHandleCount, String note) {

    /** 探针不可用：全部计数为 0 并附原因。 */
    public static InjectionReport unsupported(String note) {
        return new InjectionReport(false, 0, 0, false, 0, note);
    }

    /** 是否检出注入痕迹（远程线程 / 可执行读写区 / 可疑句柄任一非零）。 */
    public boolean hasInjectionEvidence() {
        return remoteThreadCount > 0 || executableRwRegionCount > 0 || suspiciousHandleCount > 0;
    }
}