CREATE TABLE IF NOT EXISTS source_document (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, source_type VARCHAR(32), source_name VARCHAR(255), source_url VARCHAR(1000), content TEXT, content_hash VARCHAR(64), quality_score DOUBLE, stale_score DOUBLE, duplicate_group VARCHAR(64), status VARCHAR(32), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS job_posting (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, document_id BIGINT, job_title VARCHAR(255), company VARCHAR(255), city VARCHAR(100), level_name VARCHAR(50), tech_stack VARCHAR(100), salary_min INT, salary_max INT, description TEXT, posted_at DATE, parsed BOOLEAN DEFAULT FALSE, parse_confidence DOUBLE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS skill (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, canonical_name VARCHAR(120) UNIQUE, aliases VARCHAR(1000), tech_stack VARCHAR(100), category VARCHAR(80), description VARCHAR(1000)
);
CREATE TABLE IF NOT EXISTS job_role (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, role_name VARCHAR(255) UNIQUE, normalized_name VARCHAR(255), tech_stack VARCHAR(100), level_name VARCHAR(50), definition TEXT, responsibilities TEXT, scenarios TEXT, status VARCHAR(32), confidence DOUBLE, version INT DEFAULT 1, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS role_skill (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, role_id BIGINT, skill_id BIGINT, requirement_type VARCHAR(30), importance DOUBLE, confidence DOUBLE, evidence_count INT, source_count INT, first_seen DATE, last_seen DATE, status VARCHAR(32), UNIQUE(role_id, skill_id, requirement_type)
);
CREATE TABLE IF NOT EXISTS evidence (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, target_type VARCHAR(50), target_id BIGINT, document_id BIGINT, excerpt TEXT, source_name VARCHAR(255), source_url VARCHAR(1000), support_score DOUBLE, freshness_score DOUBLE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS emerging_candidate (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, candidate_name VARCHAR(255), cluster_key VARCHAR(255), definition TEXT, responsibilities TEXT, required_skills TEXT, bonus_skills TEXT, scenarios TEXT, sample_size INT, source_count INT, growth_rate DOUBLE, novelty_score DOUBLE, confidence DOUBLE, hallucination_risk DOUBLE, status VARCHAR(32), training_year INT, target_year INT, forecast_method VARCHAR(80), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS evolution_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, role_id BIGINT, role_name VARCHAR(255), skill_name VARCHAR(120), change_type VARCHAR(30), old_value VARCHAR(255), new_value VARCHAR(255), explanation TEXT, evidence_count INT, confidence DOUBLE, status VARCHAR(32), period_from DATE, period_to DATE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS resume_profile (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, file_name VARCHAR(255), person_name VARCHAR(120), raw_text TEXT, skills TEXT, projects TEXT, education VARCHAR(500), experience_years DOUBLE, parse_confidence DOUBLE, education_detail TEXT, internships TEXT, project_detail TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS match_report (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, resume_id BIGINT, role_id BIGINT, overall_score DOUBLE, skill_score DOUBLE, internship_score DOUBLE, project_score DOUBLE, stack_score DOUBLE, level_score DOUBLE, education_score DOUBLE, matched_skills TEXT, missing_skills TEXT, suggestions TEXT, explanation TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS learning_path (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, match_id BIGINT, title VARCHAR(255), weeks INT, objective TEXT, steps_json TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS audit_record (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, target_type VARCHAR(50), target_id BIGINT, action VARCHAR(30), reviewer VARCHAR(120), comment TEXT, before_json TEXT, after_json TEXT, risk_score DOUBLE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS agent_run (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_name VARCHAR(80), task_name VARCHAR(120), status VARCHAR(30), input_summary TEXT, output_summary TEXT, duration_ms BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS agent_chat_message (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(80) NOT NULL, role VARCHAR(20) NOT NULL, content TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
