from pathlib import Path
import json

ROOT = Path(__file__).resolve().parent
GOLD = json.loads((ROOT / 'match-gold.json').read_text(encoding='utf-8'))
THRESHOLDS = GOLD['thresholds']
MATCH_T, PARTIAL_T = THRESHOLDS['match'], THRESHOLDS['partial']


def score(user, required, bonus, projects, years, level, education):
    """精确复刻后端 com.zhitu.engine.MatchingScoreEngine.score 的打分公式。"""
    total = 0.0
    hit = 0.0
    matched, missing = set(), set()
    for k, v in required.items():
        total += v
        if k in user:
            hit += v
            matched.add(k)
        else:
            missing.add(k)

    skill = 0.0 if total == 0 else 100.0 * hit / total
    bonus_hit = sum(1 for k in bonus if k in user)
    skill = min(100.0, skill + min(8, bonus_hit * 2))

    projects_l = projects.lower() if projects else ''
    project_hits = sum(1 for s in user if s.lower() in projects_l)
    project = min(100.0, 35.0 + project_hits * 10.0)

    stacks = len({x.split(' ')[0] for x in user})
    stack = min(100.0, 45.0 + stacks * 3.0)

    lv = level or ''
    expected = 5 if '高' in lv else 3 if '中' in lv else 1
    level_score = min(100.0, 60.0 + min(years, expected) / expected * 40.0)

    if education is None:
        edu = 60.0
    elif '硕士' in education or '博士' in education:
        edu = 95.0
    elif '本科' in education:
        edu = 88.0
    else:
        edu = 72.0

    overall = 0.48 * skill + 0.18 * project + 0.14 * stack + 0.12 * level_score + 0.08 * edu
    return round(overall, 1), round(skill, 1), matched, missing


def label_from_coverage(coverage):
    if coverage >= 0.8:
        return 'match'
    if coverage <= 0.2:
        return 'mismatch'
    return 'partial'


def predict(overall):
    if overall >= MATCH_T:
        return 'match'
    if overall >= PARTIAL_T:
        return 'partial'
    return 'mismatch'


def run():
    roles = GOLD['roles']
    resumes = GOLD['resumes']
    cases = []
    for role in roles:
        required = {s: 1.0 for s in role['required']}
        bonus = {s: 1.0 for s in role.get('bonus', [])}
        for r in resumes:
            user = set(r['skills'])
            coverage = len(user & required.keys()) / len(required) if required else 0.0
            overall, skill, matched, missing = score(
                user, required, bonus,
                r['projects'], r['experienceYears'], role['level'], r['education']
            )
            truth = label_from_coverage(coverage)
            pred = predict(overall)
            cases.append({
                'resume': r['name'], 'role': role['name'],
                'coverage': round(coverage, 3), 'overall': overall, 'skill': skill,
                'truth': truth, 'pred': pred
            })

    # 二分类：match vs mismatch（排除 partial）
    binary = [c for c in cases if c['truth'] != 'partial']
    tp = sum(1 for c in binary if c['truth'] == 'match' and c['pred'] == 'match')
    tn = sum(1 for c in binary if c['truth'] == 'mismatch' and c['pred'] == 'mismatch')
    fp = sum(1 for c in binary if c['truth'] == 'mismatch' and c['pred'] == 'match')
    fn = sum(1 for c in binary if c['truth'] == 'match' and c['pred'] == 'mismatch')
    bin_acc = (tp + tn) / len(binary) if binary else 0.0
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0

    # 三分类准确率（含 partial）
    tri_correct = sum(1 for c in cases if c['truth'] == c['pred'])
    tri_acc = tri_correct / len(cases) if cases else 0.0

    # MAE（预测分 vs 标签档位中点：match=85, partial=62.5, mismatch=27.5）
    midpoint = {'match': 85.0, 'partial': 62.5, 'mismatch': 27.5}
    mae = sum(abs(c['overall'] - midpoint[c['truth']]) for c in cases) / len(cases) if cases else 0.0

    confusion = {'TP': tp, 'TN': tn, 'FP': fp, 'FN': fn}
    tri_confusion = {}
    for t in ('match', 'partial', 'mismatch'):
        tri_confusion[t] = {p: sum(1 for c in cases if c['truth'] == t and c['pred'] == p)
                            for p in ('match', 'partial', 'mismatch')}

    wrong = [c for c in cases if c['truth'] != c['pred']]

    result = {
        'binary_accuracy': round(bin_acc, 4),
        'precision': round(precision, 4),
        'recall': round(recall, 4),
        'f1': round(f1, 4),
        'three_band_accuracy': round(tri_acc, 4),
        'mae': round(mae, 2),
        'binary_cases': len(binary),
        'total_cases': len(cases),
        'confusion_matrix': confusion,
        'three_band_confusion': tri_confusion,
        'misclassified': [{'resume': c['resume'], 'role': c['role'], 'truth': c['truth'],
                           'pred': c['pred'], 'overall': c['overall'], 'coverage': c['coverage']}
                          for c in wrong],
        'note': '匹配准确率=二分类准确率；标签由“技能覆盖率”规则生成（≥80% 匹配 / ≤20% 不匹配），'
                '打分公式复刻 MatchingScoreEngine，阈值 overall≥70 判定为匹配。'
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    (ROOT / 'match-evaluation-result.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    run()
