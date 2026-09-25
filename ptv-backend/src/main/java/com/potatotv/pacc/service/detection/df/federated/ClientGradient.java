package com.potatotv.pacc.service.detection.df.federated;

/**
 * DF §4.1.2 单个客户端本轮上传的梯度（模型增量）：原始数据不出设备，云端只见梯度与样本数。
 *
 * @param clientId    客户端标识（匿名设备/玩家 id）
 * @param gradient    梯度向量（与服务端全局参数同维）
 * @param sampleCount 本轮参与训练的样本数，作 FedAvg 权重
 * @param loss        客户端上报的本轮本地损失；未上报为 null
 */
public record ClientGradient(String clientId, double[] gradient, int sampleCount, Double loss) {
}