package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.detection.df.federated.FederatedLearningService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §4.1.2 玩家侧联邦端点：鉴权与防冒用。
 *
 * <p>这一组的核心不变量是「客户端标识只能等于认证主体」——FedAvg 按样本数加权，
 * 若允许请求体自报 {@code clientId}，单个账号就能克隆出大量虚假客户端来操纵聚合结果，
 * 也能以他人身份上报把嫌疑引到别的账号上。</p>
 */
class DfFederatedPlayerControllerTest {

    private static final String REAL_PTEID = "PTE-REAL-OWNER";

    private HttpServletRequest requestWithPteid(String pteid) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("pteid")).thenReturn(pteid);
        return req;
    }

    /** 请求体自报 clientId 时，必须以认证主体覆盖，不能透传。 */
    @Test
    void clientIdIsForcedToAuthenticatedPteid() {
        FederatedLearningService service = mock(FederatedLearningService.class);
        when(service.submitUpdate(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("accepted", true, "round_id", "r-1"));
        DfFederatedPlayerController controller = new DfFederatedPlayerController(service);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", "PTE-SOMEONE-ELSE");   // 冒用他人身份
        body.put("sampleCount", 120);
        body.put("gradient", "0.1,-0.2,0.3");
        body.put("loss", 0.42);

        ResponseEntity<?> resp = controller.submitUpdate(body, requestWithPteid(REAL_PTEID));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        // roundId 省略时交服务层取当前开放轮次，故为 null；clientId 必须是认证主体
        verify(service).submitUpdate(isNull(), eq(REAL_PTEID),
                eq(List.of(0.1, -0.2, 0.3)), eq(120), eq(0.42));
    }

    /** 梯度兼容数值数组形态（端侧 Json 编码器不支持数组时用逗号串，两种都要能收）。 */
    @Test
    void acceptsNumericArrayGradient() {
        FederatedLearningService service = mock(FederatedLearningService.class);
        when(service.submitUpdate(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("accepted", true));
        DfFederatedPlayerController controller = new DfFederatedPlayerController(service);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roundId", "r-9");
        body.put("sampleCount", 8);
        body.put("gradient", List.of(1.5, 2.5));

        controller.submitUpdate(body, requestWithPteid(REAL_PTEID));

        verify(service).submitUpdate(eq("r-9"), eq(REAL_PTEID), eq(List.of(1.5, 2.5)), eq(8), isNull());
    }

    /** 无玩家令牌：401，且不得触达服务层。 */
    @Test
    void rejectsUnauthenticatedUpdate() {
        FederatedLearningService service = mock(FederatedLearningService.class);
        DfFederatedPlayerController controller = new DfFederatedPlayerController(service);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sampleCount", 10);
        body.put("gradient", "0.1");

        ResponseEntity<?> resp = controller.submitUpdate(body, requestWithPteid(null));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verify(service, never()).submitUpdate(any(), any(), any(), any(), any());
    }

    /** 空请求体：400，且不得触达服务层。 */
    @Test
    void rejectsEmptyBody() {
        FederatedLearningService service = mock(FederatedLearningService.class);
        DfFederatedPlayerController controller = new DfFederatedPlayerController(service);

        ResponseEntity<?> resp = controller.submitUpdate(null, requestWithPteid(REAL_PTEID));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(service, never()).submitUpdate(any(), any(), any(), any(), any());
    }

    /** 无令牌取模型：401。 */
    @Test
    void rejectsUnauthenticatedModelDownload() {
        FederatedLearningService service = mock(FederatedLearningService.class);
        DfFederatedPlayerController controller = new DfFederatedPlayerController(service);

        ResponseEntity<?> resp = controller.latestModel(requestWithPteid(""));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verify(service, never()).latestModelWithWeights();
    }

    /** 尚无聚合产物：404，端侧按「无新模型」处理。 */
    @Test
    void reports404WhenNoAggregatedModelYet() {
        FederatedLearningService service = mock(FederatedLearningService.class);
        when(service.latestModelWithWeights()).thenReturn(null);
        DfFederatedPlayerController controller = new DfFederatedPlayerController(service);

        ResponseEntity<?> resp = controller.latestModel(requestWithPteid(REAL_PTEID));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }
}