package com.potatotv.pcu;

/**
 * 下载进度回调：0.0~1.0，总长度未知时 total 为 -1、percent 为 -1。
 *
 * @param downloaded 已下载字节数
 * @param total      总字节数；服务端未给出 Content-Length 时为 -1
 */
@FunctionalInterface
public interface ProgressListener {

    void onProgress(long downloaded, long total);

    /** 默认实现：什么都不做，供测试与静默场景使用。 */
    ProgressListener NOOP = (downloaded, total) -> {
    };

    default double percent(long downloaded, long total) {
        return total > 0 ? (double) downloaded / total : -1d;
    }
}