-- PACC 动态规则：自动点击（连点器）
-- 端侧行为检测上报 autoclicker 时，结合端侧评分
return {
  id = "auto_clicker",
  name = "自动点击检测",
  enabled = true,
  evaluate = function(ctx)
    if ctx.event_type ~= "autoclicker" then
      return { hit = false }
    end
    local score = 6 + (ctx.client_risk or 0) * 0.05
    if ctx.client_risk and ctx.client_risk >= 80 then
      score = score + 4
    end
    return { hit = true, score = score, reason = "连点频率异常" }
  end
}
