-- PACC 动态规则：进程注入
-- 检测到可疑 DLL / 代码注入进程时触发
return {
  id = "process_injection",
  name = "进程注入检测",
  enabled = true,
  evaluate = function(ctx)
    if ctx.event_type ~= "process_injection" then
      return { hit = false }
    end
    local score = 10
    -- 高危注入进程名（常见作弊注入器）
    local suspicious = {
      "injector.exe", "cheatloader.exe", "x64dbg.exe",
      "extremeinjector.exe", "processhacker.exe"
    }
    local proc = string.lower(ctx.process_name or "")
    for _, s in ipairs(suspicious) do
      if proc == s then
        score = score + 5
        break
      end
    end
    return { hit = true, score = score, reason = "检测到进程注入: " .. (ctx.process_name or "unknown") }
  end
}
