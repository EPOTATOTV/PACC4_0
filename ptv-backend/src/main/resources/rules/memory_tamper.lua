-- PACC 动态规则：内存篡改
-- 端侧底层检测上报 memory_tamper 时，依据严重度与端侧评分加权
return {
  id = "memory_tamper",
  name = "内存篡改检测",
  enabled = true,
  evaluate = function(ctx)
    if ctx.event_type ~= "memory_tamper" then
      return { hit = false }
    end
    local sev = {
      low = 4, medium = 8, high = 12, critical = 15
    }
    local base = sev[ctx.severity] or 6
    local score = base + (ctx.client_risk or 0) * 0.05
    return { hit = true, score = score, reason = "内存区域被篡改: " .. (ctx.memory_region or "unknown") }
  end
}
