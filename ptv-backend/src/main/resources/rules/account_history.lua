-- PACC 动态规则：账号历史劣迹
-- 历史作弊次数多（信誉低）的账号触发时额外加分
return {
  id = "account_history",
  name = "账号历史劣迹",
  enabled = true,
  evaluate = function(ctx)
    local reputation = tonumber(ctx.reputation) or 100
    local history_factor = (100 - reputation) / 100
    -- 仅在确实存在检测事件且历史风险较高时加分
    if history_factor < 0.3 then
      return { hit = false }
    end
    local score = history_factor * 8
    return { hit = true, score = score, reason = "账号历史劣迹加成" }
  end
}
