import math
from typing import List, Dict, Optional, Tuple

# ========== КОНСТАНТЫ ==========
HOME_ADVANTAGE = {
    'home_xg_boost': 0.22,
    'away_xg_penalty': -0.12,
    'home_corners_boost': 1.05,
    'away_corners_penalty': 0.97
}

LAMBDA_TO_XG_COEFFS = {
    'base_coef': 1.5,
    'possession_factor_mult': 0.7,
    'efficiency_base': 0.95,
    'max_xg': 3.0,
    'clearances_factor': 0.0002
}

ENHANCEMENT_FACTORS = {
    'shots_on_target_weight': 0.04,
    'goal_chance_weight': 0.12,
    'total_shots_factor': 0.005,
    'max_enhancement_per_match': 1.15,
    'min_accuracy_for_bonus': 0.25,
    'clearances_attack_factor': 0.005,
    'clearances_defense_penalty': 0.003,
    'panic_clearance_threshold': 0.35,
    'max_clearances_impact': 0.15
}

TMPR_CONFIG = {
    'min_value': 100.0,
    'max_value': 500.0,
    'neutral_point': 300.0,
    'scaling_factor': 0.001,
    'max_boost_percentage': 30.0,
    'max_penalty_percentage': 25.0,
    'home_boost': 0.02,
    'away_penalty': -0.02,
    'new_metrics_impact': {
        'clearances': {
            'elite_threshold': 12.0,
            'crisis_threshold': 28.0,
            'elite_bonus': 1.0,
            'crisis_penalty': -0.8,
            'panic_ratio_penalty': -0.4
        }
    },
    'form_effect': {
        'elite_threshold': 400.0,
        'good_threshold': 350.0,
        'poor_threshold': 250.0,
        'crisis_threshold': 200.0,
        'elite_bonus': 0.0002,
        'good_bonus': 0.0001,
        'poor_penalty': -0.00015,
        'crisis_penalty': -0.00025
    },
    'confidence_threshold': 350.0,
    'crisis_threshold': 250.0,
    'sensitivity_levels': {
        'low': {'threshold': 30, 'factor': 0.0003},
        'medium': {'threshold': 80, 'factor': 0.0007},
        'high': {'threshold': 150, 'factor': 0.0012},
        'extreme': {'factor': 0.0020}
    }
}

DEFAULT_WEIGHTS = [0.5, 0.33, 0.17]
COEFFS = {
    'alpha_box': 0.12,
    'alpha_out': 0.02,
    'alpha_touches': 0.015,
    'pos_baseline': 50.0
}
DEFAULT_CONFIDENCE = 55.0

# ========== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ==========
def safe_float(s):
    try:
        if s is None:
            return None
        st = str(s).strip().replace(',', '.')
        if st == '':
            return None
        return float(st)
    except:
        return None

def poisson_pmf(k: int, mu: float) -> float:
    if mu <= 0:
        return 1.0 if k == 0 else 0.0
    return math.exp(-mu) * (mu ** k) / math.factorial(k)

def poisson_cdf(k: int, mu: float) -> float:
    return sum(poisson_pmf(i, mu) for i in range(0, k + 1))

def prob_ge(k: int, mu: float) -> float:
    if k <= 0:
        return 1.0
    return 1.0 - poisson_cdf(k - 1, mu)

def prob_le(k: int, mu: float) -> float:
    if k < 0:
        return 0.0
    return poisson_cdf(k, mu)

def weighted_avg(values: List[float], weights: Optional[List[float]] = None) -> float:
    if not values:
        return 0.0
    if weights is None or len(weights) != len(values):
        weights = [1.0 / len(values)] * len(values)
    total = sum(weights)
    if total == 0:
        return 0.0
    return sum(v * w for v, w in zip(values, weights)) / total

# ========== ФУНКЦИИ ДЛЯ РАСЧЕТА ТОТАЛОВ ==========
def calculate_individual_totals(xg: float) -> Dict[str, float]:
    totals = {}
    totals['ТБ 0.5'] = prob_ge(1, xg) * 100
    totals['ТБ 1.5'] = prob_ge(2, xg) * 100
    totals['ТБ 2.5'] = prob_ge(3, xg) * 100
    totals['ТБ 3.5'] = prob_ge(4, xg) * 100
    totals['ТМ 0.5'] = 100 - totals['ТБ 0.5']
    totals['ТМ 1.5'] = 100 - totals['ТБ 1.5']
    totals['ТМ 2.5'] = 100 - totals['ТБ 2.5']
    totals['ТМ 3.5'] = 100 - totals['ТБ 3.5']
    return totals

def calculate_total_totals(xg_home: float, xg_away: float) -> Dict[str, float]:
    totals = {}
    total_goals = xg_home + xg_away
    totals['ТБ 0.5'] = prob_ge(1, total_goals) * 100
    totals['ТБ 1.5'] = prob_ge(2, total_goals) * 100
    totals['ТБ 2.5'] = prob_ge(3, total_goals) * 100
    totals['ТБ 3.5'] = prob_ge(4, total_goals) * 100
    totals['ТМ 0.5'] = 100 - totals['ТБ 0.5']
    totals['ТМ 1.5'] = 100 - totals['ТБ 1.5']
    totals['ТМ 2.5'] = 100 - totals['ТБ 2.5']
    totals['ТМ 3.5'] = 100 - totals['ТБ 3.5']
    return totals

# ========== ФУНКЦИИ ДЛЯ УГЛОВЫХ ==========
def calculate_corners_prediction(avg_corners: float, avg_possession: float, is_home: bool = True) -> float:
    base_multiplier = 0.9 if is_home else 0.8
    base_prediction = avg_corners * base_multiplier

    if avg_possession > 60:
        base_prediction = min(base_prediction + 1, 12)
    elif avg_possession < 40:
        base_prediction = max(base_prediction - 1, 1)

    base_prediction = max(1, min(base_prediction, 10))

    if avg_corners > 7.0:
        base_prediction = min(8, base_prediction)
    elif avg_corners < 3.0:
        base_prediction = max(2, base_prediction)

    return base_prediction

def get_detailed_corners_analysis(team1_data: List[Dict], team2_data: List[Dict],
                                  is_home_team1: bool = True, confidence_level: float = 55.0) -> Dict[str, any]:
    team1_avg_corners = sum([d.get('corners', 0) for d in team1_data]) / 3
    team2_avg_corners = sum([d.get('corners', 0) for d in team2_data]) / 3
    team1_avg_possession = sum([d.get('pos', 50) for d in team1_data]) / 3
    team2_avg_possession = sum([d.get('pos', 50) for d in team2_data]) / 3

    team1_prediction = calculate_corners_prediction(team1_avg_corners, team1_avg_possession, is_home=is_home_team1)
    team2_prediction = calculate_corners_prediction(team2_avg_corners, team2_avg_possession, is_home=not is_home_team1)
    total_corners_prediction = team1_prediction + team2_prediction

    analysis = {
        'total_corners': {
            'prediction': round(total_corners_prediction, 1),
            'team1_prediction': round(team1_prediction, 1),
            'team2_prediction': round(team2_prediction, 1),
            'team1_average': round(team1_avg_corners, 1),
            'team2_average': round(team2_avg_corners, 1)
        },
        'match_type': '',
        'risk_level': '',
        'recommendations': {
            'total': {},
            'team1': {},
            'team2': {},
            'best_overall': ''
        },
        'all_options': []
    }

    if total_corners_prediction < 6.0:
        analysis['match_type'] = 'НИЗКОУГЛОВОЙ матч'
        analysis['risk_level'] = 'НИЗКИЙ риск'
    elif total_corners_prediction < 9.0:
        analysis['match_type'] = 'СРЕДНЕУГЛОВОЙ матч'
        analysis['risk_level'] = 'УМЕРЕННЫЙ риск'
    else:
        analysis['match_type'] = 'ВЫСОКОУГЛОВОЙ матч'
        analysis['risk_level'] = 'ВЫСОКИЙ риск'

    thresholds = [4.5, 5.5, 6.5, 7.5, 8.5, 9.5]
    best_threshold = None
    best_prob = 0
    best_type = None

    for threshold in thresholds:
        prob_over = (1 - poisson_cdf(int(threshold), total_corners_prediction)) * 100
        prob_under = poisson_cdf(int(threshold) - 1, total_corners_prediction) * 100

        analysis['all_options'].append({
            'threshold': threshold,
            'over_prob': prob_over,
            'under_prob': prob_under,
            'over_text': f'ТБ {threshold} {prob_over:.1f}%',
            'under_text': f'ТМ {threshold} {prob_under:.1f}%'
        })

        if prob_over >= confidence_level and prob_over > best_prob:
            best_prob = prob_over
            best_threshold = threshold
            best_type = 'over'
        elif prob_under >= confidence_level and prob_under > best_prob:
            best_prob = prob_under
            best_threshold = threshold
            best_type = 'under'

    if best_threshold:
        bet_type = 'ТБ' if best_type == 'over' else 'ТМ'
        analysis['recommendations']['total'] = {
            'recommended_bet': f'{bet_type} {best_threshold} ({best_prob:.1f}%)',
            'probability': best_prob,
            'confidence': 'ВЫСОКАЯ уверенность' if best_prob >= 70 else 'СРЕДНЯЯ уверенность',
            'justification': f'Ожидается матч со {analysis["match_type"].split()[0].lower()} количеством угловых. Прогноз общего тотала {total_corners_prediction:.1f}'
        }

    return analysis

def format_corners_analysis_for_display(analysis: Dict[str, any]) -> List[str]:
    lines = []
    lines.append('🎯 ДЕТАЛЬНЫЙ АНАЛИЗ УГЛОВЫХ')
    lines.append('=' * 60)
    lines.append('📊 ПРОГНОЗИРУЕМЫЕ УГЛОВЫЕ')
    lines.append(
        f'   • К1: {analysis["total_corners"]["team1_prediction"]} (среднее {analysis["total_corners"]["team1_average"]})')
    lines.append(
        f'   • К2: {analysis["total_corners"]["team2_prediction"]} (среднее {analysis["total_corners"]["team2_average"]})')
    lines.append(f'   • Всего: {analysis["total_corners"]["prediction"]}')
    lines.append(f'   • Тип матча: {analysis["match_type"]}')
    lines.append(f'   • Уровень риска: {analysis["risk_level"]}')
    lines.append('')
    if analysis['recommendations']['total']:
        rec = analysis['recommendations']['total']
        lines.append('⭐ ОСНОВНАЯ РЕКОМЕНДАЦИЯ (общий тотал)')
        lines.append(f'   • {rec["recommended_bet"]}')
        lines.append(f'   • Уверенность: {rec["confidence"]}')
        lines.append(f'   • Обоснование: {rec["justification"]}')
    return lines

# ========== ФУНКЦИИ ДЛЯ АНАЛИЗА КАЧЕСТВА АТАКИ ==========
def analyze_shot_quality(team_data: List[Dict]) -> Dict[str, any]:
    total_shots_on_target = sum([d.get('shots_on_target', 0) for d in team_data])
    total_goal_chances = sum([d.get('goal_scoring_chances', 0) for d in team_data])
    total_shots = sum([d.get('total_shots', 0) for d in team_data])

    avg_shots = total_shots / 3
    avg_shots_on_target = total_shots_on_target / 3
    avg_goal_chances = total_goal_chances / 3

    accuracy = (total_shots_on_target / total_shots * 100) if total_shots > 0 else 0

    if accuracy > 40 and avg_goal_chances > 2:
        quality = 'ВЫСОКАЯ'
        description = 'Опасные атаки с хорошей точностью'
    elif accuracy > 30 and avg_goal_chances > 1:
        quality = 'СРЕДНЯЯ'
        description = 'Умеренная угроза ворот'
    else:
        quality = 'НИЗКАЯ'
        description = 'Слабые или неточные атаки'

    return {
        'accuracy_percentage': round(accuracy, 1),
        'avg_shots_per_match': round(avg_shots, 1),
        'avg_shots_on_target': round(avg_shots_on_target, 1),
        'avg_goal_chances': round(avg_goal_chances, 1),
        'quality_rating': quality,
        'quality_description': description,
        'danger_index': round((avg_shots_on_target * 1.0) + (avg_goal_chances * 1.5), 1)
    }

def get_match_analysis(p1g, pd, p2g, lambda1, lambda2, xg1, xg2):
    favorite = None
    if p1g >= 45:
        favorite = 'К1'
    elif p2g >= 45:
        favorite = 'К2'

    analysis_lines = []

    if favorite:
        if favorite == 'К1':
            if p1g >= 60:
                analysis_lines.append('✅ К1 - ЯВНЫЙ ФАВОРИТ')
                analysis_lines.append(f'   • Вероятность победы К1: {p1g:.1f}%')
            elif p1g >= 50:
                analysis_lines.append('⚡ К1 - ЛЕГКИЙ ФАВОРИТ')
                analysis_lines.append(f'   • Вероятность победы К1: {p1g:.1f}%')
            else:
                analysis_lines.append('📊 К1 - НЕЗНАЧИТЕЛЬНЫЙ ФАВОРИТ')
                analysis_lines.append(f'   • Вероятность победы К1: {p1g:.1f}%')
        else:
            if p2g >= 60:
                analysis_lines.append('✅ К2 - ЯВНЫЙ ФАВОРИТ')
                analysis_lines.append(f'   • Вероятность победы К2: {p2g:.1f}%')
            elif p2g >= 50:
                analysis_lines.append('⚡ К2 - ЛЕГКИЙ ФАВОРИТ')
                analysis_lines.append(f'   • Вероятность победы К2: {p2g:.1f}%')
            else:
                analysis_lines.append('📊 К2 - НЕЗНАЧИТЕЛЬНЫЙ ФАВОРИТ')
                analysis_lines.append(f'   • Вероятность победы К2: {p2g:.1f}%')
    else:
        if pd >= 40:
            analysis_lines.append('⚖️ ВЫСОКАЯ ВЕРОЯТНОСТЬ НИЧЬЕЙ')
            analysis_lines.append(f'   • Вероятность ничьей: {pd:.1f}%')
        else:
            analysis_lines.append('🎯 СБАЛАНСИРОВАННЫЙ МАТЧ БЕЗ ЯВНОГО ФАВОРИТА')
            analysis_lines.append(f'   • П1: {p1g:.1f}%, Ничья: {pd:.1f}%, П2: {p2g:.1f}%')

    if xg1 + xg2 > 3.0:
        analysis_lines.append('⚽ ОЖИДАЕТСЯ ЗРЕЛИЩНЫЙ МАТЧ С БОЛЬШИМ КОЛИЧЕСТВОМ ГОЛОВ')
    elif xg1 + xg2 < 1.5:
        analysis_lines.append('🛡️ ВЕРОЯТЕН НИЗКОВАЗЯЗНЫЙ МАТЧ С МАЛЫМ КОЛИЧЕСТВОМ ГОЛОВ')

    return '\n'.join(analysis_lines)

def get_match_recommendation(p1_prob: float, p2_prob: float, draw_prob: float,
                             team1_quality: Dict, team2_quality: Dict,
                             home_advantage: bool = False) -> str:
    recommendations = []

    if p1_prob > p2_prob and p1_prob > draw_prob:
        favorite = 'К1'
        favorite_prob = p1_prob
        underdog_prob = p2_prob
    elif p2_prob > p1_prob and p2_prob > draw_prob:
        favorite = 'К2'
        favorite_prob = p2_prob
        underdog_prob = p1_prob
    else:
        favorite = 'НИЧЬЯ'
        favorite_prob = draw_prob

    quality_diff = team1_quality['danger_index'] - team2_quality['danger_index']

    if favorite == 'К1':
        if home_advantage:
            recommendations.append('🏠 К1: домашняя команда с преимуществом поля')
        if team1_quality['quality_rating'] == 'ВЫСОКАЯ':
            recommendations.append('🎯 К1: показывает ВЫСОКУЮ эффективность в атаке')
        if quality_diff > 3:
            recommendations.append('⚡ К1: значительно опаснее в атаке')
    elif favorite == 'К2':
        if not home_advantage:
            recommendations.append('🏃 К2: гостевая команда (преодолевает гостевой фактор)')
        if team2_quality['quality_rating'] == 'ВЫСОКАЯ':
            recommendations.append('🎯 К2: демонстрирует ВЫСОКУЮ результативность')
        if abs(quality_diff) > 3:
            recommendations.append('⚡ К2: имеет явное преимущество в качестве атак')
    else:
        recommendations.append('⚖️ Сбалансированный матч без явного фаворита')
        if abs(quality_diff) < 1:
            recommendations.append('📊 Команды имеют схожее качество атак')

    return ' '.join(recommendations)

def get_final_recommendation_text(pick: str, probability: float,
                                  team1_quality: Dict, team2_quality: Dict,
                                  is_home_team: bool = False) -> str:
    if pick == 'П1':
        if is_home_team:
            base = 'Домашняя команда с преимуществом поля'
        else:
            base = 'Команда 1'
        if probability >= 60:
            strength = 'явный фаворит'
        elif probability >= 50:
            strength = 'легкий фаворит'
        else:
            strength = 'незначительный фаворит'
        if team1_quality['quality_rating'] == 'ВЫСОКАЯ':
            quality = 'имеет опасную атаку'
        elif team1_quality['quality_rating'] == 'СРЕДНЯЯ':
            quality = 'показывает умеренную эффективность'
        else:
            quality = 'несмотря на слабую атаку'
        return f'{base} {strength} ({probability:.1f}%) {quality}'
    elif pick == 'П2':
        if not is_home_team:
            base = 'Гостевая команда преодолевает фактор поля'
        else:
            base = 'Команда 2'
        if probability >= 60:
            strength = 'явный фаворит'
        elif probability >= 50:
            strength = 'легкий фаворит'
        else:
            strength = 'незначительный фаворит'
        if team2_quality['quality_rating'] == 'ВЫСОКАЯ':
            quality = 'демонстрирует высокую результативность'
        elif team2_quality['quality_rating'] == 'СРЕДНЯЯ':
            quality = 'показывает стабильную игру'
        else:
            quality = 'несмотря на скромные показатели'
        return f'{base} {strength} ({probability:.1f}%) {quality}'
    else:
        if probability >= 40:
            strength = 'высокая вероятность'
        elif probability >= 30:
            strength = 'умеренная вероятность'
        else:
            strength = 'возможная'
        diff = abs(team1_quality['danger_index'] - team2_quality['danger_index'])
        if diff < 2:
            balance = 'сбалансированный матч'
        else:
            balance = 'команды близки по силе'
        return f'{strength} ничьей ({probability:.1f}%) - {balance}'

# ========== ФУНКЦИИ ДЛЯ НОВЫХ МЕТРИК ==========
def calculate_enhanced_xg_with_new_metrics(base_xg: float, team_data: List[Dict],
                                           opp_data: List[Dict], is_home: bool = True) -> float:
    enhanced_xg = base_xg

    avg_shots_on_target = sum([d.get('shots_on_target', 0) for d in team_data]) / 3
    avg_goal_chances = sum([d.get('goal_scoring_chances', 0) for d in team_data]) / 3
    avg_total_shots = sum([d.get('total_shots', 0) for d in team_data]) / 3

    avg_clearances = sum([d.get('clearances', 0) for d in team_data]) / 3
    avg_opp_clearances = sum([d.get('clearances', 0) for d in opp_data]) / 3

    sot_bonus = avg_shots_on_target * ENHANCEMENT_FACTORS['shots_on_target_weight']
    sot_bonus = min(sot_bonus, 0.4)

    gsc_bonus = avg_goal_chances * ENHANCEMENT_FACTORS['goal_chance_weight']
    gsc_bonus = min(gsc_bonus, 0.6)

    total_shots_bonus = 0
    if avg_total_shots > 0:
        accuracy = (avg_shots_on_target / avg_total_shots)
        if accuracy > ENHANCEMENT_FACTORS['min_accuracy_for_bonus']:
            total_shots_bonus = avg_total_shots * ENHANCEMENT_FACTORS['total_shots_factor']
            total_shots_bonus = min(total_shots_bonus, 0.2)

    if avg_opp_clearances > 0:
        clearances_attack_bonus = avg_opp_clearances * ENHANCEMENT_FACTORS['clearances_attack_factor']
        clearances_attack_bonus = min(clearances_attack_bonus, 0.15)
        enhanced_xg += clearances_attack_bonus

    if avg_clearances > 15:
        clearances_defense_penalty = (avg_clearances - 15) * ENHANCEMENT_FACTORS['clearances_defense_penalty']
        clearances_defense_penalty = min(clearances_defense_penalty, 0.10)
        enhanced_xg -= clearances_defense_penalty

    total_enhancement = sot_bonus + gsc_bonus + total_shots_bonus
    total_enhancement = min(total_enhancement, ENHANCEMENT_FACTORS['max_enhancement_per_match'])

    enhanced_xg += total_enhancement
    enhanced_xg = max(enhanced_xg, 0.1)

    max_limit = 3.5 if is_home else 3.0
    enhanced_xg = min(enhanced_xg, max_limit)

    return round(enhanced_xg, 2)

def calculate_defensive_pressure_index(team_data: List[Dict]) -> Dict[str, any]:
    avg_clearances = sum([d.get('clearances', 0) for d in team_data]) / 3

    pressure_index = 0

    if avg_clearances > 25:
        pressure_index += (avg_clearances - 25) * 0.3
    if avg_clearances < 12:
        pressure_index -= (12 - avg_clearances) * 0.4

    if pressure_index <= -10:
        defense_quality = 'ЭЛИТНАЯ оборона'
        description = 'Отличные показатели выносов и контроля'
    elif pressure_index <= -5:
        defense_quality = 'ХОРОШАЯ оборона'
        description = 'Надежные оборонительные действия'
    elif pressure_index <= 5:
        defense_quality = 'СРЕДНЯЯ оборона'
        description = 'Стандартные оборонительные показатели'
    elif pressure_index <= 15:
        defense_quality = 'СЛАБАЯ оборона'
        description = 'Проблемы в оборонительных действиях'
    else:
        defense_quality = 'КРИЗИС обороны'
        description = 'Системные проблемы в защите'

    return {
        'pressure_index': round(pressure_index, 1),
        'defense_quality': defense_quality,
        'defense_description': description,
        'avg_clearances': round(avg_clearances, 1)
    }

def calculate_tmpr_with_new_metrics(tmpr_base: float, team_data: List[Dict]) -> float:
    tmpr_adjusted = tmpr_base

    avg_clearances = sum([d.get('clearances', 0) for d in team_data]) / 3

    impact = TMPR_CONFIG['new_metrics_impact']

    clearances_impact = impact['clearances']
    if avg_clearances >= clearances_impact['elite_threshold']:
        tmpr_adjusted += (clearances_impact['elite_threshold'] - avg_clearances) * clearances_impact['elite_bonus']
    elif avg_clearances >= clearances_impact['crisis_threshold']:
        tmpr_adjusted -= (avg_clearances - clearances_impact['crisis_threshold']) * clearances_impact['crisis_penalty']

    tmpr_adjusted = max(TMPR_CONFIG['min_value'], min(TMPR_CONFIG['max_value'], tmpr_adjusted))

    return round(tmpr_adjusted, 1)

def apply_tmpr_effect(base_value: float, tmpr_self: float, tmpr_opponent: float,
                      is_home: bool = True, value_type: str = 'lambda') -> Tuple[float, Dict]:
    tmpr_self = max(TMPR_CONFIG['min_value'], min(TMPR_CONFIG['max_value'], tmpr_self))
    tmpr_opponent = max(TMPR_CONFIG['min_value'], min(TMPR_CONFIG['max_value'], tmpr_opponent))

    diff = tmpr_self - tmpr_opponent
    abs_diff = abs(diff)

    sensitivity = TMPR_CONFIG['sensitivity_levels']

    if abs_diff <= sensitivity['low']['threshold']:
        base_multiplier = 1.0 + (diff * sensitivity['low']['factor'])
    elif abs_diff <= sensitivity['medium']['threshold']:
        low_range = sensitivity['low']['threshold'] * sensitivity['low']['factor']
        medium_diff = abs_diff - sensitivity['low']['threshold']
        effect = low_range + (medium_diff * sensitivity['medium']['factor'])
        base_multiplier = 1.0 + (effect if diff > 0 else -effect)
    elif abs_diff <= sensitivity['high']['threshold']:
        low_range = sensitivity['low']['threshold'] * sensitivity['low']['factor']
        medium_range = (sensitivity['medium']['threshold'] - sensitivity['low']['threshold']) * sensitivity['medium']['factor']
        high_diff = abs_diff - sensitivity['medium']['threshold']
        effect = low_range + medium_range + (high_diff * sensitivity['high']['factor'])
        base_multiplier = 1.0 + (effect if diff > 0 else -effect)
    else:
        low_range = sensitivity['low']['threshold'] * sensitivity['low']['factor']
        medium_range = (sensitivity['medium']['threshold'] - sensitivity['low']['threshold']) * sensitivity['medium']['factor']
        high_range = (sensitivity['high']['threshold'] - sensitivity['low']['threshold']) * sensitivity['high']['factor']
        extreme_diff = abs_diff - sensitivity['high']['threshold']
        effect = low_range + medium_range + high_range + (extreme_diff * sensitivity['extreme']['factor'])
        base_multiplier = 1.0 + (effect if diff > 0 else -effect)

    form_multiplier = 1.0
    form_effect = TMPR_CONFIG['form_effect']

    if tmpr_self >= form_effect['elite_threshold']:
        elite_bonus = (tmpr_self - form_effect['elite_threshold']) * form_effect['elite_bonus']
        form_multiplier += elite_bonus
    elif tmpr_self >= form_effect['good_threshold']:
        good_bonus = (tmpr_self - form_effect['good_threshold']) * form_effect['good_bonus']
        form_multiplier += good_bonus

    if tmpr_self <= form_effect['crisis_threshold']:
        crisis_penalty = (form_effect['crisis_threshold'] - tmpr_self) * form_effect['crisis_penalty']
        form_multiplier += crisis_penalty
    elif tmpr_self <= form_effect['poor_threshold']:
        poor_penalty = (form_effect['poor_threshold'] - tmpr_self) * form_effect['poor_penalty']
        form_multiplier += poor_penalty

    if is_home:
        venue_multiplier = 1.0 + TMPR_CONFIG['home_boost']
    else:
        venue_multiplier = 1.0 + TMPR_CONFIG['away_penalty']

    final_multiplier = base_multiplier * form_multiplier * venue_multiplier

    max_boost = 1.0 + (TMPR_CONFIG['max_boost_percentage'] / 100.0)
    min_penalty = 1.0 - (TMPR_CONFIG['max_penalty_percentage'] / 100.0)

    if final_multiplier > max_boost:
        excess = final_multiplier - max_boost
        final_multiplier = max_boost + (excess * 0.3)
    elif final_multiplier < min_penalty:
        deficit = min_penalty - final_multiplier
        final_multiplier = min_penalty - (deficit * 0.3)

    if value_type == 'xg':
        enhanced_multiplier = 1.0 + ((final_multiplier - 1.0) * 1.3)
        enhanced_value = base_value * enhanced_multiplier
    else:
        enhanced_value = base_value * final_multiplier

    if value_type == 'xg':
        enhanced_value = round(enhanced_value, 2)
    else:
        enhanced_value = round(enhanced_value, 3)

    effect_info = {
        'tmpr_self': tmpr_self,
        'tmpr_opponent': tmpr_opponent,
        'diff': diff,
        'base_effect_percent': (base_multiplier - 1.0) * 100,
        'form_effect_percent': (form_multiplier - 1.0) * 100,
        'venue_effect_percent': (venue_multiplier - 1.0) * 100,
        'total_effect_percent': (final_multiplier - 1.0) * 100,
        'enhanced_effect_percent': (enhanced_multiplier - 1.0) * 100 if value_type == 'xg' else (final_multiplier - 1.0) * 100,
        'base_value': base_value,
        'enhanced_value': enhanced_value,
        'is_home': is_home,
        'value_type': value_type,
        'final_multiplier': final_multiplier
    }

    return enhanced_value, effect_info

def get_tmpr_interpretation(tmpr_value: float) -> str:
    if tmpr_value >= 450:
        return '🔥 ПИК ФОРМЫ - выдающиеся показатели'
    elif tmpr_value >= 400:
        return '⭐ ЭЛИТНАЯ ФОРМА - доминирующие выступления'
    elif tmpr_value >= 350:
        return '📈 ХОРОШАЯ ФОРМА - уверенные выступления'
    elif tmpr_value >= 300:
        return '✅ СРЕДНИЙ УРОВЕНЬ - стабильные показатели'
    elif tmpr_value >= 250:
        return '⚠️ НЕСТАБИЛЬНО - проблемы с реализацией'
    elif tmpr_value >= 200:
        return '📉 ПЛОХАЯ ФОРМА - системные проблемы'
    else:
        return '🔥 КРИЗИС - глубокая негативная динамика'

def format_tmpr_analysis(tmpr1: float, tmpr2: float,
                         lambda_effect1: Dict, lambda_effect2: Dict,
                         xg_effect1: Dict, xg_effect2: Dict) -> List[str]:
    lines = []
    diff = tmpr1 - tmpr2

    lines.append('\n🏆 TEAM MOMENTUM PERFORMANCE RANKING')
    lines.append('=' * 70)
    lines.append('📊 РЕЙТИНГИ ФОРМЫ')
    lines.append(f'   • К1 (Дома): {tmpr1:.1f} - {get_tmpr_interpretation(tmpr1)}')
    lines.append(f'   • К2 (Гости): {tmpr2:.1f} - {get_tmpr_interpretation(tmpr2)}')
    lines.append(f'   • Разница: {diff:+.1f} баллов ({abs(diff):.1f} абс.)')

    return lines

# ========== ТРАДИЦИОННЫЙ АЛГОРИТМ ==========
def calculate_lambda_traditional(team_stats):
    ud = team_stats['ud']
    kas = team_stats['kas']
    vlad = team_stats['vlad']
    ugl = team_stats['ugl']

    ud_norm = ud / 15.0
    kas_norm = kas / 40.0
    vlad_norm = vlad / 70.0
    ugl_norm = ugl / 10.0

    lambda_base = (0.7 * ud_norm + 0.3 * kas_norm + 0.1 * vlad_norm + 0.05 * ugl_norm)

    ud_kas_ratio = ud / kas if kas > 0 else 0
    correction = 1 - 0.48 * ud_kas_ratio
    correction = max(correction, 0.1)

    lambda_final = lambda_base * correction
    return lambda_final

def calculate_expected_goals_from_stats(team_last3: List[Dict], opp_last3: List[Dict],
                                        weights: Optional[List[float]] = None, coeffs: Optional[Dict] = None) -> float:
    if coeffs is None:
        coeffs = COEFFS
    if weights is None:
        weights = DEFAULT_WEIGHTS

    def wavg(list_of_dicts, field):
        vals = [float(d.get(field, 0) or 0.0) for d in list_of_dicts]
        return weighted_avg(vals, weights)

    avg_pos = wavg(team_last3, 'pos')
    avg_sh_in = wavg(team_last3, 'shots_in_box')
    avg_sh_out = wavg(team_last3, 'shots_out_box')
    avg_touches = wavg(team_last3, 'touches_in_box')
    opp_avg_sh_in = wavg(opp_last3, 'shots_in_box')

    xg_attack = (coeffs['alpha_box'] * avg_sh_in + coeffs['alpha_out'] * avg_sh_out + coeffs['alpha_touches'] * avg_touches)
    pos_factor = (avg_pos / coeffs['pos_baseline']) if coeffs['pos_baseline'] else 1.0
    xg_attack *= pos_factor

    opp_def_proxy = coeffs['alpha_box'] * opp_avg_sh_in
    xg = max(0.0, 0.5 * (xg_attack + opp_def_proxy))

    xg = max(xg, 0.1)
    xg = min(xg, 4.0)

    return xg

def compute_result_probs(xg_home: float, xg_away: float, max_k: int = 12):
    p1 = pd = p2 = 0.0
    probs_h = [poisson_pmf(k, xg_home) for k in range(max_k + 1)]
    probs_a = [poisson_pmf(k, xg_away) for k in range(max_k + 1)]
    rem_h = max(0.0, 1.0 - sum(probs_h))
    rem_a = max(0.0, 1.0 - sum(probs_a))
    probs_h[-1] += rem_h
    probs_a[-1] += rem_a

    for i, ph in enumerate(probs_h):
        for j, pa in enumerate(probs_a):
            p = ph * pa
            if i > j:
                p1 += p
            elif i == j:
                pd += p
            else:
                p2 += p

    s = p1 + pd + p2
    if s > 0:
        p1 /= s
        pd /= s
        p2 /= s

    return p1, pd, p2

def get_top_scores(xg_home: float, xg_away: float, top_n: int = 6):
    scores = []
    max_goals = 7

    for i in range(max_goals + 1):
        for j in range(max_goals + 1):
            p_home = poisson_pmf(i, xg_home)
            p_away = poisson_pmf(j, xg_away)
            scores.append(((i, j), p_home * p_away))

    scores.sort(key=lambda x: x[1], reverse=True)

    total = sum(prob for _, prob in scores[:top_n])
    if total > 0:
        return [((h, a), round(prob / total * 100, 2)) for (h, a), prob in scores[:top_n]]
    return [((h, a), 0.0) for (h, a), _ in scores[:top_n]]

# ========== λ-АЛГОРИТМ ==========
def calculate_lambda_basic(vlad, ud, kas, ugl):
    ud_norm = ud / 15.0
    kas_norm = kas / 40.0
    vlad_norm = vlad / 70.0
    ugl_norm = ugl / 10.0

    lambda_base = (0.7 * ud_norm + 0.3 * kas_norm + 0.1 * vlad_norm + 0.05 * ugl_norm)

    if kas > 0:
        ud_kas_ratio = ud / kas
    else:
        ud_kas_ratio = 0

    correction = 1 - 0.48 * ud_kas_ratio
    correction = max(correction, 0.1)

    lambda_final = lambda_base * correction
    return lambda_final

def calculate_lambda_with_enhancement(vlad_values, ud_in_shtraf, ud_iz_za_shtraf, ugl_values, kas_values,
                                      clearances_values=None):
    vlad_avg = sum(vlad_values) / 3
    ud_total_avg = sum([ud_in_shtraf[i] + ud_iz_za_shtraf[i] for i in range(3)]) / 3
    kas_avg = sum(kas_values) / 3
    ugl_avg = sum(ugl_values) / 3

    lambda_base = calculate_lambda_basic(vlad_avg, ud_total_avg, kas_avg, ugl_avg)

    lambda_enhanced = lambda_base

    third_scores = []
    for i in range(3):
        score = ud_in_shtraf[i] + kas_values[i] * 0.5
        third_scores.append((score, i))

    best_third_idx = max(third_scores, key=lambda x: x[0])[1]

    vlad_best = vlad_values[best_third_idx]
    ud_best = ud_in_shtraf[best_third_idx] + ud_iz_za_shtraf[best_third_idx]
    kas_best = kas_values[best_third_idx]
    ugl_best = ugl_values[best_third_idx]

    lambda_best = calculate_lambda_basic(vlad_best, ud_best, kas_best, ugl_best)
    lambda_enhanced = lambda_base * 0.7 + lambda_best * 0.3

    ud_total = sum(ud_in_shtraf) + sum(ud_iz_za_shtraf)
    ud_iz_za_total = sum(ud_iz_za_shtraf)

    if ud_total > 0:
        long_shot_ratio = ud_iz_za_total / ud_total
        if long_shot_ratio > 0.4:
            lambda_enhanced *= (1 - (long_shot_ratio - 0.4))

    corners_bonus = min(ugl_avg / 5.0, 0.3)
    lambda_enhanced *= (1 + corners_bonus)

    if kas_avg > 0:
        efficiency = ud_total_avg / kas_avg
        balance = (vlad_avg / 100.0) * efficiency
        if balance < 0.6:
            lambda_enhanced *= 0.8

    if clearances_values:
        avg_clearances = sum(clearances_values) / 3
        if avg_clearances < 15:
            clearances_bonus = (15 - avg_clearances) * LAMBDA_TO_XG_COEFFS['clearances_factor']
            lambda_enhanced *= (1 + min(clearances_bonus, 0.04))

    return lambda_enhanced

def lambda_to_xg_with_home_advantage(lambda_val, vlad, ud_total, kas, is_home=True):
    base_coef = 1.5
    possession_factor = 1.0 + ((vlad - 50) / 100.0) * 0.7
    possession_factor = max(0.85, min(possession_factor, 1.15))

    if kas > 3:
        efficiency = 0.95 + (ud_total / kas * 0.3)
        efficiency = max(0.75, min(efficiency, 1.25))
    else:
        efficiency = 0.95

    xg_neutral = lambda_val * base_coef * possession_factor * efficiency
    xg_neutral = min(xg_neutral, 3.0)

    if is_home:
        xg = xg_neutral + 0.22
    else:
        xg = xg_neutral - 0.12

    xg = max(xg, 0.1)
    xg = min(xg, 3.2)

    return round(xg, 2), round(xg_neutral, 2)

def _prepare_lambda_data(team_data):
    """Подготовка данных для λ-алгоритма"""
    return {
        'vlad': [d['pos'] for d in team_data],
        'ud_in_shtraf': [d['shots_in_box'] for d in team_data],
        'ud_iz_za_shtraf': [d['shots_out_box'] for d in team_data],
        'ugl': [d['corners'] for d in team_data],
        'kas': [d['touches_in_box'] for d in team_data],
        'clearances': [d.get('clearances', 0) for d in team_data]
    }

def predict_match_lambda_with_tmpr(team1_data, team2_data, tmpr1, tmpr2):
    lambda1_raw = calculate_lambda_with_enhancement(
        team1_data['vlad'],
        team1_data['ud_in_shtraf'],
        team1_data['ud_iz_za_shtraf'],
        team1_data['ugl'],
        team1_data['kas'],
        team1_data.get('clearances')
    )

    lambda2_raw = calculate_lambda_with_enhancement(
        team2_data['vlad'],
        team2_data['ud_in_shtraf'],
        team2_data['ud_iz_za_shtraf'],
        team2_data['ugl'],
        team2_data['kas'],
        team2_data.get('clearances')
    )

    lambda1_tmpr, tmpr_effect1 = apply_tmpr_effect(
        lambda1_raw, tmpr1, tmpr2, is_home=True, value_type='lambda'
    )

    lambda2_tmpr, tmpr_effect2 = apply_tmpr_effect(
        lambda2_raw, tmpr2, tmpr1, is_home=False, value_type='lambda'
    )

    vlad1_avg = sum(team1_data['vlad']) / 3
    ud1_total_avg = sum([team1_data['ud_in_shtraf'][i] + team1_data['ud_iz_za_shtraf'][i] for i in range(3)]) / 3
    kas1_avg = sum(team1_data['kas']) / 3

    vlad2_avg = sum(team2_data['vlad']) / 3
    ud2_total_avg = sum([team2_data['ud_in_shtraf'][i] + team2_data['ud_iz_za_shtraf'][i] for i in range(3)]) / 3
    kas2_avg = sum(team2_data['kas']) / 3

    xg1_raw, xg1_neutral = lambda_to_xg_with_home_advantage(
        lambda1_raw, vlad1_avg, ud1_total_avg, kas1_avg, is_home=True
    )

    xg2_raw, xg2_neutral = lambda_to_xg_with_home_advantage(
        lambda2_raw, vlad2_avg, ud2_total_avg, kas2_avg, is_home=False
    )

    xg1_tmpr_direct, xg_tmpr_effect1 = apply_tmpr_effect(
        xg1_raw, tmpr1, tmpr2, is_home=True, value_type='xg'
    )

    xg2_tmpr_direct, xg_tmpr_effect2 = apply_tmpr_effect(
        xg2_raw, tmpr2, tmpr1, is_home=False, value_type='xg'
    )

    xg1_from_lambda = lambda1_tmpr * 1.5
    xg2_from_lambda = lambda2_tmpr * 1.5

    xg1_combined = (xg1_tmpr_direct * 0.6) + (xg1_from_lambda * 0.4)
    xg2_combined = (xg2_tmpr_direct * 0.6) + (xg2_from_lambda * 0.4)

    team1_match_data = [
        {
            'shots_on_target': 4.0,
            'goal_scoring_chances': 2.0,
            'total_shots': 14.7,
            'clearances': team1_data.get('clearances', [10, 10, 10])[0]
        },
        {
            'shots_on_target': 4.0,
            'goal_scoring_chances': 2.0,
            'total_shots': 14.7,
            'clearances': team1_data.get('clearances', [10, 10, 10])[1]
        },
        {
            'shots_on_target': 4.0,
            'goal_scoring_chances': 2.0,
            'total_shots': 14.7,
            'clearances': team1_data.get('clearances', [10, 10, 10])[2]
        }
    ]

    team2_match_data = [
        {
            'shots_on_target': 4.3,
            'goal_scoring_chances': 2.3,
            'total_shots': 13.0,
            'clearances': team2_data.get('clearances', [10, 10, 10])[0]
        },
        {
            'shots_on_target': 4.3,
            'goal_scoring_chances': 2.3,
            'total_shots': 13.0,
            'clearances': team2_data.get('clearances', [10, 10, 10])[1]
        },
        {
            'shots_on_target': 4.3,
            'goal_scoring_chances': 2.3,
            'total_shots': 13.0,
            'clearances': team2_data.get('clearances', [10, 10, 10])[2]
        }
    ]

    xg1_final = calculate_enhanced_xg_with_new_metrics(xg1_combined, team1_match_data, team2_match_data, is_home=True)
    xg2_final = calculate_enhanced_xg_with_new_metrics(xg2_combined, team2_match_data, team1_match_data, is_home=False)

    p1g, pd, p2g = compute_result_probs(xg1_final, xg2_final)

    if p1g > p2g and p1g > pd:
        recommended_outcome = 'П1'
    elif p2g > p1g and p2g > pd:
        recommended_outcome = 'П2'
    else:
        recommended_outcome = 'НичьяX'

    top_scores = get_top_scores(xg1_final, xg2_final, top_n=6)

    total_goals = xg1_final + xg2_final
    over_2_5_prob = round(sum(poisson_pmf(i, xg1_final) * poisson_pmf(j, xg2_final)
                              for i in range(8) for j in range(8) if i + j > 2.5) * 100, 1)
    over_1_5_prob = round(sum(poisson_pmf(i, xg1_final) * poisson_pmf(j, xg2_final)
                              for i in range(8) for j in range(8) if i + j > 1.5) * 100, 1)
    under_2_5_prob = 100 - over_2_5_prob
    under_1_5_prob = 100 - over_1_5_prob

    p0_1 = poisson_pmf(0, xg1_final)
    p0_2 = poisson_pmf(0, xg2_final)
    btts_prob = max(0.0, 1.0 - p0_1 - p0_2 + p0_1 * p0_2) * 100

    return {
        'lambdas': {
            'raw': (round(lambda1_raw, 3), round(lambda2_raw, 3)),
            'tmpr_enhanced': (round(lambda1_tmpr, 3), round(lambda2_tmpr, 3))
        },
        'expected_goals': {
            'raw': (round(xg1_raw, 2), round(xg2_raw, 2)),
            'tmpr_direct': (round(xg1_tmpr_direct, 2), round(xg2_tmpr_direct, 2)),
            'combined': (round(xg1_combined, 2), round(xg2_combined, 2)),
            'final': (round(xg1_final, 2), round(xg2_final, 2))
        },
        'tmpr_effects': {
            'team1_lambda': tmpr_effect1,
            'team2_lambda': tmpr_effect2,
            'team1_xg': xg_tmpr_effect1,
            'team2_xg': xg_tmpr_effect2
        },
        'outcomes': {'home_win': round(p1g * 100, 1), 'draw': round(pd * 100, 1), 'away_win': round(p2g * 100, 1)},
        'recommended_outcome': recommended_outcome,
        'top_scores': top_scores,
        'exact_score_prediction': f"{top_scores[0][0][0]}:{top_scores[0][0][1]}",
        'total_goals': round(total_goals, 2),
        'over_2_5_prob': over_2_5_prob,
        'over_1_5_prob': over_1_5_prob,
        'under_2_5_prob': under_2_5_prob,
        'under_1_5_prob': under_1_5_prob,
        'btts_prob': round(btts_prob, 1),
        'tmpr_values': (tmpr1, tmpr2),
        'tmpr_diff': round(tmpr1 - tmpr2, 1),
        'home_advantage_applied': True
    }