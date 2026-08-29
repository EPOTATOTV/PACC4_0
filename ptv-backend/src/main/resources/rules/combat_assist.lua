-- PACC 动态规则：自瞄 / 杀戮光环（战斗辅助）
-- 端侧行为检测上报 killaura / aimbot / reach 时触发
return {
  id = "combat_assist",
  name = "战斗辅助检测",
  enabled = true,
  evaluate = function(ctx)
    local assist = {
      killaura = true, aimbot = true, reach = true, scaffold = true
    }
    if not assist[ctx.event_type] then
      return { hit = false }
    end
    local sev = { low = 5, medium = 9, high = 13, critical = 16 }
    local score = (sev[ctx.severity] or 7) + (ctx.client_risk or 0) * 0.04
    return { hit = true, score = score, reason = "战斗辅助特征: " .. ctx.event_type }
  end
}
