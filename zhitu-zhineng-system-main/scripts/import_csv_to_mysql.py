#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
将生成的岗位 CSV 导入 MySQL `career_data_governance.dataset_job_raw`。

字段命名对齐后端 RawJobSchemaService 的自动识别：
  title / company / description / city / education / experience /
  source_platform / industry / salary_min / salary_max / posting_date / posting_year

content_hash 采用 title + description + company 的 SHA-256（与后端 TextUtils.sha256 一致），
既能去重完全相同的记录，又保留「不同公司抄袭同一份 JD」的近重复样本。

用法：python scripts/import_csv_to_mysql.py [csv路径]
"""
import csv
import hashlib
import re
import sys
import os

import pymysql

DB_CONFIG = {
    "host": "127.0.0.1",
    "port": 3306,
    "user": "root",
    "password": "123456",
    "database": "career_data_governance",
    "charset": "utf8mb4",
    "local_infile": True,
}

SOURCE_DATASET = "generated-jobs-2026"

CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS `dataset_job_raw` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_dataset` varchar(200) NOT NULL,
  `source_record_id` varchar(200) DEFAULT NULL,
  `posting_year` int DEFAULT NULL,
  `posting_date` varchar(50) DEFAULT NULL,
  `title` varchar(1000) DEFAULT NULL,
  `company` varchar(500) DEFAULT NULL,
  `city` varchar(500) DEFAULT NULL,
  `description` longtext,
  `education` varchar(100) DEFAULT NULL,
  `experience` varchar(100) DEFAULT NULL,
  `industry` varchar(1000) DEFAULT NULL,
  `salary_min` decimal(15,2) DEFAULT NULL,
  `salary_max` decimal(15,2) DEFAULT NULL,
  `source_platform` varchar(300) DEFAULT NULL,
  `work_area` varchar(500) DEFAULT NULL,
  `work_location` varchar(1000) DEFAULT NULL,
  `company_location` varchar(1000) DEFAULT NULL,
  `job_count` varchar(100) DEFAULT NULL,
  `recruitment_category` varchar(300) DEFAULT NULL,
  `ai_keyword` varchar(1000) DEFAULT NULL,
  `closing_date` varchar(50) DEFAULT NULL,
  `closing_year` int DEFAULT NULL,
  `source_reference` varchar(2000) DEFAULT NULL,
  `content_hash` char(64) NOT NULL,
  `governance_status` varchar(30) DEFAULT 'PENDING',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dataset_content` (`source_dataset`,`content_hash`),
  KEY `idx_dataset_year` (`posting_year`),
  KEY `idx_dataset_title` (`title`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
"""

# CSV 列 -> 表列
COLUMN_MAP = {
    "企业名称": "company",
    "招聘岗位": "title",
    "工作城市": "city",
    "工作区域": "work_area",
    "职位描述": "description",
    "学历要求": "education",
    "要求经验": "experience",
    "招聘人数": "job_count",
    "招聘类别": "recruitment_category",
    "初级分类": "industry",
    "来源平台": "source_platform",
    "公司地点": "company_location",
    "工作地点": "work_location",
    "招聘发布日期": "posting_date",
    "招聘结束日期": "closing_date",
    "招聘发布年份": "posting_year",
    "招聘结束年份": "closing_year",
    "来源": "source_reference",
    "人工智能关键词": "ai_keyword",
    "最低月薪": "salary_min",
    "最高月薪": "salary_max",
}

# 表列（按插入顺序）
TABLE_COLUMNS = [
    "source_dataset", "source_record_id", "posting_year", "posting_date", "title", "company",
    "city", "description", "education", "experience", "industry", "salary_min", "salary_max",
    "source_platform", "work_area", "work_location", "company_location", "job_count",
    "recruitment_category", "ai_keyword", "closing_date", "closing_year", "source_reference",
    "content_hash", "governance_status",
]


def normalize(text):
    """对齐后端 TextUtils.normalize 的简化版：小写 + 折叠空白 + 去首尾空格。"""
    if text is None:
        return ""
    return re.sub(r"\s+", " ", text.lower()).strip()


def content_hash(title, description, company):
    return hashlib.sha256(
        normalize(f"{title}\n{description}\n{company}").encode("utf-8")
    ).hexdigest()


def to_decimal(v):
    """薪资字符串 -> Decimal / None（处理空、'面议' 等噪音）。"""
    v = (v or "").strip()
    if not v or v == "面议" or not re.search(r"\d", v):
        return None
    try:
        return float(re.sub(r"[^\d.]", "", v))
    except ValueError:
        return None


def to_int(v):
    v = (v or "").strip()
    if not v or not v.isdigit():
        return None
    return int(v)


def main():
    csv_path = sys.argv[1] if len(sys.argv) > 1 else os.path.normpath(
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "generated-jobs-10000.csv"))

    conn = pymysql.connect(**DB_CONFIG)
    cur = conn.cursor()
    cur.execute("DROP TABLE IF EXISTS `dataset_job_raw`")
    cur.execute(CREATE_TABLE_SQL)
    conn.commit()

    inserted = 0
    skipped_dup = 0
    with open(csv_path, encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        batch = []
        for i, row in enumerate(reader, 1):
            title = (row.get("招聘岗位") or "").strip()
            description = (row.get("职位描述") or "").strip()
            company = (row.get("企业名称") or "").strip()
            record = {
                "source_dataset": SOURCE_DATASET,
                "source_record_id": "gen-%06d" % i,
                "posting_year": to_int(row.get("招聘发布年份")),
                "posting_date": (row.get("招聘发布日期") or "").strip() or None,
                "title": title,
                "company": company,
                "city": (row.get("工作城市") or "").strip() or None,
                "description": description,
                "education": (row.get("学历要求") or "").strip() or None,
                "experience": (row.get("要求经验") or "").strip() or None,
                "industry": (row.get("初级分类") or "").strip() or None,
                "salary_min": to_decimal(row.get("最低月薪")),
                "salary_max": to_decimal(row.get("最高月薪")),
                "source_platform": (row.get("来源平台") or "").strip() or None,
                "work_area": (row.get("工作区域") or "").strip() or None,
                "work_location": (row.get("工作地点") or "").strip() or None,
                "company_location": (row.get("公司地点") or "").strip() or None,
                "job_count": (row.get("招聘人数") or "").strip() or None,
                "recruitment_category": (row.get("招聘类别") or "").strip() or None,
                "ai_keyword": (row.get("人工智能关键词") or "").strip() or None,
                "closing_date": (row.get("招聘结束日期") or "").strip() or None,
                "closing_year": to_int(row.get("招聘结束年份")),
                "source_reference": (row.get("来源") or "").strip() or None,
                "content_hash": content_hash(title, description, company),
                "governance_status": "PENDING",
            }
            batch.append(record)
            if len(batch) >= 500:
                inserted, skipped_dup = do_insert(cur, batch, inserted, skipped_dup)
                batch = []
        if batch:
            inserted, skipped_dup = do_insert(cur, batch, inserted, skipped_dup)
    conn.commit()

    cur.execute("SELECT COUNT(*) FROM `dataset_job_raw`")
    total = cur.fetchone()[0]
    cur.execute("SELECT posting_year, COUNT(*) FROM `dataset_job_raw` GROUP BY posting_year ORDER BY posting_year")
    years = cur.fetchall()
    cur.close()
    conn.close()

    print("导入完成：")
    print("  读取 CSV 行数:", inserted + skipped_dup)
    print("  成功插入:", inserted)
    print("  因 content_hash 重复跳过:", skipped_dup)
    print("  表内总行数:", total)
    print("  年份分布:", dict(years))


def do_insert(cur, batch, inserted, skipped_dup):
    cols = ",".join("`%s`" % c for c in TABLE_COLUMNS)
    placeholders = ",".join(["%s"] * len(TABLE_COLUMNS))
    sql = f"INSERT IGNORE INTO `dataset_job_raw` ({cols}) VALUES ({placeholders})"
    before = len(batch)
    cur.executemany(sql, [tuple(r.get(c) for c in TABLE_COLUMNS) for r in batch])
    after_affected = cur.rowcount
    inserted += after_affected
    skipped_dup += before - after_affected
    return inserted, skipped_dup


if __name__ == "__main__":
    main()
