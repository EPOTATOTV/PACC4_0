package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.detection.df.federated.FederatedLearningService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * DF §4.1.2 联邦学习「玩家侧」端点：端侧上报梯度、下取聚合模型。
 *
 * <p><b>为什么另开一组：</b>{@link DfFederatedController} 把联邦端点放在 {@code /api/admin/**}，
 * 由 AdminKeyFilter 与 {@code @RequirePermission} 保护。但「上报梯度」的主体是玩家客户端，
 * 它拿不到管理员凭据；若强行让端侧持有管理员 Key，等于把管理端全权下发给每一台玩家机器。
 * 因此这里补一组玩家侧等价端点，鉴权走玩家 JWT（过滤器把 PTEID 放进请求属性 {@code pteid}），
 * 与其它 {@code /api/player/**} 端点完全一致。</p>
 *
 * <p><b>防冒用：</b>客户端标识一律取认证后的 PTEID，<b>忽略请求体里的 {@code clientId}</b>。
 * 这样 A 账号无法冒 B 的身份上报梯度，也无法单个账号伪造成大量「客户端」去刷轮次权重——
 * FedAvg 按样本数加权，若能克隆身份就能操纵聚合结果。</p>
 *
 * <p><b>隐私：</b>请求体只含客户端标识、梯度向量与样本数，不含任何原始采集数据；
 * 与设计文档「原始数据不出设备」一致。</p>
 */
@RestController
@RequestMapping("/api/player/df/federated")
@RequiredArgsConstructor
@SuppressWarnings("null") // 请求体 Map 泛型解析的 null 分析误报
public class DfFederatedPlayerController {

    private final FederatedLearningService federatedLearningService;

    /**
     * 上报本机梯度（模型增量）。
     *
     * <p>请求体：{@code {"sampleCount": 120, "gradient": [0.1, -0.2, ...], "loss": 0.42}}。
     * {@code roundId} 可省略，服务层取当前开放轮次；{@code gradient} 兼容数值数组与逗号分隔字符串
     * （端侧 Json 编码器不支持数组，故支持字符串形态）。</p>
     *
     * <p>校验失败返回 HTTP 200 且 {@code accepted=false}＋{@code reason}，便于端侧按原因自愈；
     * 没有开放轮次返回 404，轮次状态异常返回 400。</p>
     */
    @PostMapping("/updates")
    public ResponseEntity<?> submitUpdate(@RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "未认证的玩家令牌"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请求体不能为空"));
        }
        try {
            return ResponseEntity.ok(federatedLearningService.submitUpdate(
                    body.get("roundId") == null ? null : body.get("roundId").toString(),
                    pteid,                                            // 客户端标识强制取认证主体
                    DfFederatedController.gradientOf(body.get("gradient")),
                    DfFederatedController.intOf(body.get("sampleCount")),
                    DfFederatedController.doubleOf(body.get("loss"))));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 下取当前聚合模型（含权重），供端侧热替换本地推理模型。
     *
     * <p>响应字段：{@code version}、{@code feature_dim}、{@code weights}（逗号分隔浮点串）、
     * {@code weights_sha256}——与端侧 {@code FederatedModelDownlink} 的载荷契约一致，
     * 端侧据此校验后包成 {@code .paccm} 交给既有推理层，失败则保留旧模型。</p>
     *
     * <p>尚无聚合产物时返回 404，端侧按「无新模型」处理即可，不视为错误。</p>
     */
    @GetMapping("/model/latest")
    public ResponseEntity<?> latestModel(HttpServletRequest req) {
        if (pteidOf(req).isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "未认证的玩家令牌"));
        }
        Map<String, Object> model = federatedLearningService.latestModelWithWeights();
        if (model == null) {
            return ResponseEntity.status(404).body(Map.of("error", "尚无联邦聚合模型"));
        }
        return ResponseEntity.ok(model);
    }

    /** 玩家 JWT 过滤器写入的 PTEID；缺失即未认证。 */
    private static String pteidOf(HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        return v == null ? "" : v.toString();
    }
}