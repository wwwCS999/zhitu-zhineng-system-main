-- ============================================================
-- 职途智配：百万历史岗位年度预测 / 回测扩展
-- 目标数据库：career_data_governance
-- 安全性：只新增 zhitu_* 表，不删除、不修改 dataset_job_raw 原始记录。
-- ============================================================

USE career_data_governance;

CREATE TABLE IF NOT EXISTS zhitu_temporal_holdout (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    raw_job_id BIGINT NOT NULL,
    holdout_year INT NOT NULL,
    sample_seed VARCHAR(100) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_zhitu_holdout_raw(raw_job_id),
    INDEX idx_zhitu_holdout_year(holdout_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zhitu_forecast_run (
    run_id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    train_year INT NOT NULL,
    test_year INT NOT NULL,
    train_rows BIGINT DEFAULT 0,
    test_rows BIGINT DEFAULT 0,
    prediction_count INT DEFAULT 0,
    actual_emerging_count INT DEFAULT 0,
    matched_count INT DEFAULT 0,
    precision_score DECIMAL(10,6) DEFAULT 0,
    recall_score DECIMAL(10,6) DEFAULT 0,
    f1_score DECIMAL(10,6) DEFAULT 0,
    avg_similarity DECIMAL(10,6) DEFAULT 0,
    calibration_score DECIMAL(10,6) DEFAULT 0,
    trust_score DECIMAL(10,6) DEFAULT 0,
    top_k INT DEFAULT 30,
    test_scope VARCHAR(40),
    config_json JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_zhitu_forecast_batch(batch_id),
    INDEX idx_zhitu_forecast_year(train_year, test_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zhitu_forecast_candidate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(36) NOT NULL,
    rank_no INT NOT NULL,
    predicted_title VARCHAR(500) NOT NULL,
    normalized_title VARCHAR(500),
    train_count BIGINT DEFAULT 0,
    previous_count BIGINT DEFAULT 0,
    company_count BIGINT DEFAULT 0,
    h1_count BIGINT DEFAULT 0,
    h2_count BIGINT DEFAULT 0,
    novelty_score DECIMAL(10,6) DEFAULT 0,
    momentum_score DECIMAL(10,6) DEFAULT 0,
    forecast_score DECIMAL(10,6) DEFAULT 0,
    confidence DECIMAL(10,6) DEFAULT 0,
    actual_title VARCHAR(500),
    actual_count DECIMAL(18,4) DEFAULT 0,
    similarity DECIMAL(10,6) DEFAULT 0,
    hit_flag TINYINT(1) DEFAULT 0,
    evidence_json JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_zhitu_candidate_run(run_id),
    INDEX idx_zhitu_candidate_hit(run_id, hit_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 性能建议（百万级数据强烈建议）：
-- 先执行 SHOW INDEX FROM dataset_job_raw; 查看 published_at 是否已有索引。
-- 如果没有，可在空闲时间手动执行下面一条。MySQL 8 通常可在线建索引，但仍会占用 IO 和磁盘空间。
-- ALTER TABLE dataset_job_raw ADD INDEX idx_zhitu_published_at (published_at);

-- 2026 固定 1000 条测试集由后端接口自动、确定性抽取。
-- 不建议手工 INSERT，以免种子和系统配置不一致。
