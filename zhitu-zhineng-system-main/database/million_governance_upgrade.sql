-- 职途智配 V4：百万 JD 连续治理派生表
-- 安全原则：不修改、不删除 dataset_job_raw；所有结果写入 zhitu_* 表。

CREATE TABLE IF NOT EXISTS zhitu_temporal_holdout (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  raw_job_id BIGINT NOT NULL,
  holdout_year INT NOT NULL,
  sample_seed VARCHAR(100) NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_zhitu_holdout_raw(raw_job_id),
  INDEX idx_zhitu_holdout_year(holdout_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zhitu_governed_job (
  raw_job_id BIGINT PRIMARY KEY,
  run_id VARCHAR(36),
  title_raw VARCHAR(500),
  title_standard VARCHAR(500),
  company VARCHAR(300),
  city VARCHAR(150),
  industry VARCHAR(200),
  salary_min DECIMAL(12,2),
  salary_max DECIMAL(12,2),
  education VARCHAR(150),
  experience_text VARCHAR(300),
  source_name VARCHAR(300),
  description_clean MEDIUMTEXT,
  published_at DATE,
  published_year INT,
  tech_stack VARCHAR(120),
  level_name VARCHAR(80),
  content_hash CHAR(64),
  template_hash CHAR(64),
  quality_score DECIMAL(10,6) DEFAULT 0,
  stale_score DECIMAL(10,6) DEFAULT 0,
  duplicate_group VARCHAR(80),
  duplicate_weight DECIMAL(10,6) DEFAULT 1,
  skill_count INT DEFAULT 0,
  valid_for_analysis TINYINT(1) DEFAULT 0,
  governance_status VARCHAR(40),
  governed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_zhitu_gov_year(published_year),
  INDEX idx_zhitu_gov_date(published_at),
  INDEX idx_zhitu_gov_title(title_standard(120)),
  INDEX idx_zhitu_gov_template(template_hash),
  INDEX idx_zhitu_gov_valid(valid_for_analysis,published_year),
  INDEX idx_zhitu_gov_company(company(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zhitu_governed_job_skill (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  raw_job_id BIGINT NOT NULL,
  skill_name VARCHAR(200) NOT NULL,
  tech_stack VARCHAR(120),
  category VARCHAR(120),
  requirement_type VARCHAR(40),
  confidence DECIMAL(10,6),
  evidence_text VARCHAR(1000),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_zhitu_gov_skill(raw_job_id,skill_name),
  INDEX idx_zhitu_gov_skill_name(skill_name),
  INDEX idx_zhitu_gov_skill_raw(raw_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zhitu_governance_issue (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  raw_job_id BIGINT NOT NULL,
  run_id VARCHAR(36),
  issue_type VARCHAR(80),
  field_name VARCHAR(100),
  severity VARCHAR(30),
  issue_message VARCHAR(1200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_zhitu_issue(raw_job_id,issue_type,field_name),
  INDEX idx_zhitu_issue_raw(raw_job_id),
  INDEX idx_zhitu_issue_type(issue_type,severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zhitu_governance_run (
  run_id VARCHAR(36) PRIMARY KEY,
  status VARCHAR(30),
  total_target BIGINT DEFAULT 0,
  processed_count BIGINT DEFAULT 0,
  success_count BIGINT DEFAULT 0,
  failed_count BIGINT DEFAULT 0,
  valid_count BIGINT DEFAULT 0,
  duplicate_count BIGINT DEFAULT 0,
  last_raw_id BIGINT DEFAULT 0,
  current_stage VARCHAR(300),
  batch_size INT,
  error_message VARCHAR(2000),
  started_at DATETIME,
  finished_at DATETIME,
  INDEX idx_zhitu_gov_run_time(started_at),
  INDEX idx_zhitu_gov_run_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zhitu_duplicate_cluster (
  template_hash CHAR(64) PRIMARY KEY,
  root_raw_job_id BIGINT,
  member_count BIGINT,
  INDEX idx_zhitu_dup_root(root_raw_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
