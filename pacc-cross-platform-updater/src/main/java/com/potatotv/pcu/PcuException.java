package com.potatotv.pcu;

/**
 * PCU 运行期异常：配置错误、网络失败、校验不通过、差分/替换失败等统一走这里。
 *
 * <p>不区分受检/非受检的取舍：更新流程由 {@link UpdateOrchestrator} 统一兜底，
 * 失败都必须能落到「回滚 + 上报」这条路径上，所以用非受检异常避免每层都写 try。</p>
 */
public class PcuException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PcuException(String message) {
        super(message);
    }

    public PcuException(String message, Throwable cause) {
        super(message, cause);
    }
}