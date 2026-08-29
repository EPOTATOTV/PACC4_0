-- PACC 动态规则：Java 版 Mod / 注入
-- Java 版玩家端上报 java_mod / injection 时触发（基岩版无此事件）
return {
  id = "java_mod",
  name = "Java 版 Mod 篡改检测",
  enabled = true,
  evaluate = function(ctx)
    if ctx.event_type ~= "java_mod" and ctx.event_type ~= "injection" then
      return { hit = false }
    end
    local score = 9
    -- 命中已知作弊 Mod 特征
    if ctx.signature_hit and ctx.signature_hit ~= "" then
      score = score + 4
    end
    if ctx.edition == "JAVA" then
      score = score + 2
    end
    return { hit = true, score = score, reason = "Java 版 Mod 篡改: " .. (ctx.signature_hit or "unknown") }
  end
}
