-- PACC 动态规则：USB 外设 / 宏设备
-- 高回报低风险的外设宏（键鼠宏）通常走 USB 上报
return {
  id = "usb_device",
  name = "外设宏设备检测",
  enabled = true,
  evaluate = function(ctx)
    if ctx.event_type ~= "usb_device" then
      return { hit = false }
    end
    local score = 5
    local detail = string.lower(ctx.detail or "")
    if string.find(detail, "macro") or string.find(detail, "script") then
      score = score + 6
    end
    return { hit = true, score = score, reason = "检测到可疑 USB 外设" }
  end
}
