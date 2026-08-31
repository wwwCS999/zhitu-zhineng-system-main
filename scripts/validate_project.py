#!/usr/bin/env python3
"""无需安装前后端依赖的交付包基础校验。"""
from __future__ import annotations
import csv, json, re, subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
required = [
    'backend/pom.xml','backend/src/main/resources/schema.sql','frontend/package.json',
    'docker-compose.yml','data/sample-jd-120.csv','data/gold-extractions.json',
    'data/test-cases.json','docs/API.md','docs/TEST_PLAN.md'
]
errors=[]
for rel in required:
    if not (ROOT/rel).is_file(): errors.append(f'缺少文件: {rel}')
json_count=0
for p in (ROOT/'data').glob('*.json'):
    try: json.loads(p.read_text(encoding='utf-8')); json_count+=1
    except Exception as e: errors.append(f'JSON 无效 {p.name}: {e}')
with (ROOT/'data/sample-jd-120.csv').open(encoding='utf-8-sig',newline='') as f:
    rows=list(csv.DictReader(f))
if len(rows)<100: errors.append(f'岗位 JD 少于 100 条: {len(rows)}')
headers=set(rows[0]) if rows else set()
for h in ['招聘岗位','职位描述','来源平台','招聘发布日期']:
    if h not in headers: errors.append(f'CSV 缺少字段: {h}')
vue_count=0
for p in (ROOT/'frontend/src').rglob('*.vue'):
    text=p.read_text(encoding='utf-8'); vue_count+=1
    if '<template>' not in text or '</template>' not in text: errors.append(f'Vue 模板不完整: {p.relative_to(ROOT)}')
java_count=len(list((ROOT/'backend/src/main/java').rglob('*.java')))
metric_proc=subprocess.run([sys.executable,str(ROOT/'data/evaluate_metrics.py')],capture_output=True,text=True)
metrics={}
if metric_proc.returncode:
    errors.append('指标脚本执行失败: '+metric_proc.stderr.strip())
else:
    try: metrics=json.loads(metric_proc.stdout)
    except Exception as e: errors.append(f'指标输出不是 JSON: {e}')
result={
    'status':'PASS' if not errors else 'FAIL','jd_count':len(rows),'json_files':json_count,
    'java_files':java_count,'vue_files':vue_count,'metrics':metrics,'errors':errors
}
print(json.dumps(result,ensure_ascii=False,indent=2))
sys.exit(1 if errors else 0)
