# Observability 迁移说明

- `001_core_schema.sql`：obs_run / obs_span / obs_event / obs_payload / obs_usage_attempt / obs_audit（DEV-03）。
- `002_tasks_experiments.sql`：obs_optimization_task / obs_change_revision / obs_run_link / obs_annotation / obs_experiment / obs_experiment_result（DEV-07/08）。

执行：`mysql -uroot -p… --default-character-set=utf8mb4 data_agent < 001_core_schema.sql`，再执行 002。
回退：`DROP TABLE` 上述 12 张表即可（业务表与本目录无关）。
所有表均为 CREATE TABLE IF NOT EXISTS，可重复执行；单位约定见各文件头注释。
