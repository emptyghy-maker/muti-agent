-- ============================================================
-- t_usage_record 审计升级：新增 model / channel / question / answer 列
-- 老库执行一次即可；重复执行会报"列已存在"，忽略即可（不影响使用）。
-- ============================================================
ALTER TABLE t_usage_record
  ADD COLUMN model VARCHAR(64) NULL AFTER agent,
  ADD COLUMN channel VARCHAR(32) NULL AFTER model,
  ADD COLUMN question VARCHAR(1024) NULL AFTER channel,
  ADD COLUMN answer TEXT NULL AFTER question,
  ADD KEY idx_model (model),
  ADD KEY idx_channel (channel),
  ADD KEY idx_username (username);
