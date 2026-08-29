-- PACC 动态规则：调试器 / 反调试
-- 环境检测：调试器附加属于高风险环境行为
return {
  id = "debugger",
  name = "调试器检测",
  enabled = true,
  evaluate = function(ctx)
    if ctx.event_type ~= "debugger" then
      return { hit = false }
    end
    local score = 12
    if ctx.client_risk and ctx.client_risk >= 70 then
      score = score + 3
    end
    return { hit = true, score = score, reason = "检测到调试器附加，存在动态分析风险" }
  end
}
