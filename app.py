import streamlit as st
import pandas as pd
import time
from datetime import datetime, timedelta
from sstats_client import SStatsClient
from predictor import (
    analyze_shot_quality,
    calculate_defensive_pressure_index,
    predict_match_lambda_with_tmpr,
    calculate_expected_goals_from_stats,
    compute_result_probs,
    get_top_scores,
    calculate_total_totals,
    get_detailed_corners_analysis,
    format_corners_analysis_for_display,
    poisson_pmf,
    DEFAULT_WEIGHTS,
    COEFFS,
    DEFAULT_CONFIDENCE
)

# Настройка страницы
st.set_page_config(
    page_title="XG Score — Футбольный предиктор",
    page_icon="⚽",
    layout="wide",
    initial_sidebar_state="expanded"
)

# Кастомный CSS для темной темы
st.markdown("""
<style>
    .stApp {
        background-color: black;
    }
    .main-header {
        color: #00FF66;
        font-size: 2.5rem;
        font-weight: bold;
        text-align: center;
        margin-bottom: 1rem;
    }
    .team1-header {
        color: #00FF66;
        font-size: 1.3rem;
        font-weight: bold;
    }
    .team2-header {
        color: #FF6600;
        font-size: 1.3rem;
        font-weight: bold;
    }
    .success-text {
        color: #00FF66;
    }
    .warning-text {
        color: #FFAA00;
    }
    .error-text {
        color: #FF3333;
    }
    .info-box {
        background-color: #111111;
        padding: 1rem;
        border-radius: 5px;
        border-left: 3px solid #00AAFF;
    }
    .stButton>button {
        width: 100%;
        background-color: #00FF33;
        color: black;
        font-weight: bold;
    }
    .stDownloadButton>button {
        background-color: #FF33FF;
        color: white;
        font-weight: bold;
    }
</style>
""", unsafe_allow_html=True)

# Инициализация клиента API
@st.cache_resource
def init_api_client():
    return SStatsClient(api_key="gbi1ldi9446kastj")

client = init_api_client()

# Заголовок
st.markdown('<h1 class="main-header">⚽ XG Score — Футбольный предиктор</h1>', unsafe_allow_html=True)

# Инициализация session state
if 'selected_match' not in st.session_state:
    st.session_state.selected_match = None
if 'team1_data' not in st.session_state:
    st.session_state.team1_data = [{} for _ in range(3)]
if 'team2_data' not in st.session_state:
    st.session_state.team2_data = [{} for _ in range(3)]
if 'matches' not in st.session_state:
    st.session_state.matches = []

# ========== САЙДБАР ==========
with st.sidebar:
    st.markdown("## 📅 Выбор даты")
    
    # Календарь
    selected_date = st.date_input(
        "Выберите дату матча",
        value=datetime.now(),
        min_value=datetime.now() - timedelta(days=30),
        max_value=datetime.now() + timedelta(days=7)
    )
    
    # Кнопка загрузки матчей
    if st.button("🔍 Загрузить матчи", use_container_width=True):
        with st.spinner("Загрузка матчей..."):
            date_str = selected_date.strftime("%Y-%m-%d")
            matches = client.get_matches_by_date(date_str)
            if matches:
                st.session_state.matches = matches
                st.success(f"✅ Загружено {len(matches)} матчей")
            else:
                st.error("❌ Нет матчей на выбранную дату")
    
    st.markdown("---")
    
    # Отображение загруженных матчей
    if st.session_state.matches:
        st.markdown("### 📋 Матчи на дату")
        
        # Создаем список для выбора
        match_options = []
        match_dict = {}
        
        for match in st.session_state.matches:
            # Получаем названия команд
            if isinstance(match.get('homeTeam'), dict):
                home = match['homeTeam'].get('name', 'Unknown')
                home_id = match['homeTeam'].get('id')
            else:
                home = str(match.get('homeTeam', 'Unknown'))
                home_id = match.get('homeTeamId')
                
            if isinstance(match.get('awayTeam'), dict):
                away = match['awayTeam'].get('name', 'Unknown')
                away_id = match['awayTeam'].get('id')
            else:
                away = str(match.get('awayTeam', 'Unknown'))
                away_id = match.get('awayTeamId')
            
            display = f"{home} vs {away}"
            match_options.append(display)
            match_dict[display] = {
                'id': match.get('id'),
                'home_team': home,
                'home_id': home_id,
                'away_team': away,
                'away_id': away_id
            }
        
        selected_display = st.selectbox(
            "Выберите матч",
            options=match_options,
            key="match_selector"
        )
        
        st.session_state.selected_match = match_dict.get(selected_display)
        
        # Кнопка загрузки статистики
        if st.button("📥 Загрузить статистику команд", use_container_width=True):
            if st.session_state.selected_match:
                with st.spinner("Загрузка статистики..."):
                    # Загружаем статистику для хозяев
                    home_matches = client.get_team_last_matches(
                        st.session_state.selected_match['home_id'], 
                        limit=3
                    )
                    if home_matches:
                        for i, match in enumerate(home_matches[:3]):
                            stats = client.get_match_detailed_stats(match['id'])
                            if stats:
                                team_stats = client.extract_team_stats_from_match(
                                    stats, 
                                    st.session_state.selected_match['home_id']
                                )
                                if i < len(st.session_state.team1_data):
                                    st.session_state.team1_data[i] = team_stats
                    
                    # Загружаем статистику для гостей
                    away_matches = client.get_team_last_matches(
                        st.session_state.selected_match['away_id'], 
                        limit=3
                    )
                    if away_matches:
                        for i, match in enumerate(away_matches[:3]):
                            stats = client.get_match_detailed_stats(match['id'])
                            if stats:
                                team_stats = client.extract_team_stats_from_match(
                                    stats, 
                                    st.session_state.selected_match['away_id']
                                )
                                if i < len(st.session_state.team2_data):
                                    st.session_state.team2_data[i] = team_stats
                    
                    st.success("✅ Статистика загружена")
                    st.rerun()
            else:
                st.warning("⚠️ Сначала выберите матч")
    
    st.markdown("---")
    st.markdown(f"🔑 **API Key:** gbi1ldi9446kastj")
    st.markdown("📊 **Метрики:** Выносы (оборонительный показатель)")

# ========== ОСНОВНОЙ КОНТЕНТ ==========

# Информация о выбранном матче
if st.session_state.selected_match:
    st.markdown(f"""
    <div class="info-box">
        <b>Выбранный матч:</b> {st.session_state.selected_match['home_team']} vs {st.session_state.selected_match['away_team']}
    </div>
    """, unsafe_allow_html=True)

# Создаем две колонки для ввода данных
col1, col2 = st.columns(2)

with col1:
    st.markdown('<p class="team1-header">🏠 КОМАНДА 1 (Домашняя)</p>', unsafe_allow_html=True)
    
    team1_data = []
    for i in range(3):
        with st.expander(f"📊 Матч {i+1}", expanded=i==0):
            col_pos, col_ts, col_sot = st.columns(3)
            with col_pos:
                pos = st.number_input(
                    "Владение %",
                    min_value=0.0, max_value=100.0, value=50.0,
                    key=f"t1_pos_{i}", step=1.0
                )
            with col_ts:
                total_shots = st.number_input(
                    "Всего ударов",
                    min_value=0, value=12,
                    key=f"t1_ts_{i}", step=1
                )
            with col_sot:
                shots_on_target = st.number_input(
                    "В створ",
                    min_value=0, value=4,
                    key=f"t1_sot_{i}", step=1
                )
            
            col_gc, col_cor, col_sib = st.columns(3)
            with col_gc:
                goal_chances = st.number_input(
                    "Голевые моменты",
                    min_value=0, value=2,
                    key=f"t1_gc_{i}", step=1
                )
            with col_cor:
                corners = st.number_input(
                    "Угловые",
                    min_value=0, value=5,
                    key=f"t1_cor_{i}", step=1
                )
            with col_sib:
                shots_in_box = st.number_input(
                    "Удары в штрафной",
                    min_value=0, value=8,
                    key=f"t1_sib_{i}", step=1
                )
            
            col_sob, col_tib, col_cle = st.columns(3)
            with col_sob:
                shots_out_box = st.number_input(
                    "Удары из-за штрафной",
                    min_value=0, value=4,
                    key=f"t1_sob_{i}", step=1
                )
            with col_tib:
                touches_in_box = st.number_input(
                    "Касания в штрафной",
                    min_value=0, value=15,
                    key=f"t1_tib_{i}", step=1
                )
            with col_cle:
                clearances = st.number_input(
                    "Выносы",
                    min_value=0, value=10,
                    key=f"t1_cle_{i}", step=1
                )
            
            # Сохраняем данные
            team1_data.append({
                'pos': pos,
                'total_shots': total_shots,
                'shots_on_target': shots_on_target,
                'goal_scoring_chances': goal_chances,
                'corners': corners,
                'shots_in_box': shots_in_box,
                'shots_out_box': shots_out_box,
                'touches_in_box': touches_in_box,
                'clearances': clearances
            })

with col2:
    st.markdown('<p class="team2-header">✈️ КОМАНДА 2 (Гостевая)</p>', unsafe_allow_html=True)
    
    team2_data = []
    for i in range(3):
        with st.expander(f"📊 Матч {i+1}", expanded=i==0):
            col_pos, col_ts, col_sot = st.columns(3)
            with col_pos:
                pos = st.number_input(
                    "Владение %",
                    min_value=0.0, max_value=100.0, value=50.0,
                    key=f"t2_pos_{i}", step=1.0
                )
            with col_ts:
                total_shots = st.number_input(
                    "Всего ударов",
                    min_value=0, value=12,
                    key=f"t2_ts_{i}", step=1
                )
            with col_sot:
                shots_on_target = st.number_input(
                    "В створ",
                    min_value=0, value=4,
                    key=f"t2_sot_{i}", step=1
                )
            
            col_gc, col_cor, col_sib = st.columns(3)
            with col_gc:
                goal_chances = st.number_input(
                    "Голевые моменты",
                    min_value=0, value=2,
                    key=f"t2_gc_{i}", step=1
                )
            with col_cor:
                corners = st.number_input(
                    "Угловые",
                    min_value=0, value=5,
                    key=f"t2_cor_{i}", step=1
                )
            with col_sib:
                shots_in_box = st.number_input(
                    "Удары в штрафной",
                    min_value=0, value=8,
                    key=f"t2_sib_{i}", step=1
                )
            
            col_sob, col_tib, col_cle = st.columns(3)
            with col_sob:
                shots_out_box = st.number_input(
                    "Удары из-за штрафной",
                    min_value=0, value=4,
                    key=f"t2_sob_{i}", step=1
                )
            with col_tib:
                touches_in_box = st.number_input(
                    "Касания в штрафной",
                    min_value=0, value=15,
                    key=f"t2_tib_{i}", step=1
                )
            with col_cle:
                clearances = st.number_input(
                    "Выносы",
                    min_value=0, value=10,
                    key=f"t2_cle_{i}", step=1
                )
            
            # Сохраняем данные
            team2_data.append({
                'pos': pos,
                'total_shots': total_shots,
                'shots_on_target': shots_on_target,
                'goal_scoring_chances': goal_chances,
                'corners': corners,
                'shots_in_box': shots_in_box,
                'shots_out_box': shots_out_box,
                'touches_in_box': touches_in_box,
                'clearances': clearances
            })

# ========== TMPR И НАСТРОЙКИ ==========
st.markdown("---")
tmpr_col1, tmpr_col2, algo_col = st.columns([1, 1, 2])

with tmpr_col1:
    tmpr1 = st.number_input(
        "🏆 К1 TMPR",
        min_value=100.0, max_value=500.0, value=300.0,
        step=1.0, format="%.1f"
    )

with tmpr_col2:
    tmpr2 = st.number_input(
        "🏆 К2 TMPR",
        min_value=100.0, max_value=500.0, value=300.0,
        step=1.0, format="%.1f"
    )

with algo_col:
    algorithm = st.selectbox(
        "🎯 Алгоритм расчета",
        options=["Оба алгоритма", "Только традиционный", "Только λ-алгоритм"],
        index=0
    )
    algo_map = {
        "Оба алгоритма": "both",
        "Только традиционный": "traditional", 
        "Только λ-алгоритм": "lambda"
    }

# ========== КНОПКИ ДЕЙСТВИЙ ==========
button_col1, button_col2, button_col3 = st.columns(3)

with button_col1:
    calculate_clicked = st.button("🧮 РАССЧИТАТЬ", use_container_width=True)

with button_col2:
    if st.button("🔄 СБРОС", use_container_width=True):
        for key in list(st.session_state.keys()):
            if key.startswith(('t1_', 't2_')):
                del st.session_state[key]
        st.session_state.team1_data = [{} for _ in range(3)]
        st.session_state.team2_data = [{} for _ in range(3)]
        st.rerun()

with button_col3:
    save_clicked = st.button("💾 СОХРАНИТЬ ОТЧЕТ", use_container_width=True)

# ========== РАСЧЕТ И ОТОБРАЖЕНИЕ РЕЗУЛЬТАТОВ ==========
if calculate_clicked:
    with st.spinner("🔄 Выполняется расчет..."):
        time.sleep(0.5)  # Небольшая задержка для визуального эффекта
        
        # Анализ качества атаки
        team1_quality = analyze_shot_quality(team1_data)
        team2_quality = analyze_shot_quality(team2_data)
        
        # Анализ обороны
        team1_defense = calculate_defensive_pressure_index(team1_data)
        team2_defense = calculate_defensive_pressure_index(team2_data)
        
        # Формируем строку с результатами
        result_lines = []
        result_lines.append('=' * 90)
        result_lines.append('ФУТБОЛЬНЫЙ ПРЕДИКТОР - АНАЛИЗ МАТЧА')
        result_lines.append('=' * 90)
        result_lines.append('')
        
        # Данные команд
        result_lines.append('📊 КАЧЕСТВО АТАКИ')
        result_lines.append(f'   К1: {team1_quality["quality_rating"]} - {team1_quality["quality_description"]}')
        result_lines.append(f'      Точность: {team1_quality["accuracy_percentage"]}% | '
                           f'Уд. в створ: {team1_quality["avg_shots_on_target"]:.1f}')
        result_lines.append(f'   К2: {team2_quality["quality_rating"]} - {team2_quality["quality_description"]}')
        result_lines.append(f'      Точность: {team2_quality["accuracy_percentage"]}% | '
                           f'Уд. в створ: {team2_quality["avg_shots_on_target"]:.1f}')
        result_lines.append('')
        
        result_lines.append('🛡️ АНАЛИЗ ОБОРОНЫ')
        result_lines.append(f'   К1: {team1_defense["defense_quality"]}')
        result_lines.append(f'      Выносы: {team1_defense["avg_clearances"]:.1f}')
        result_lines.append(f'   К2: {team2_defense["defense_quality"]}')
        result_lines.append(f'      Выносы: {team2_defense["avg_clearances"]:.1f}')
        result_lines.append('')
        
        # Традиционный алгоритм
        if algo_map[algorithm] in ['both', 'traditional']:
            result_lines.append('─' * 45)
            result_lines.append('📈 ТРАДИЦИОННЫЙ АЛГОРИТМ')
            result_lines.append('─' * 45)
            
            xg1_trad = calculate_expected_goals_from_stats(team1_data, team2_data, DEFAULT_WEIGHTS, COEFFS)
            xg2_trad = calculate_expected_goals_from_stats(team2_data, team1_data, DEFAULT_WEIGHTS, COEFFS)
            p1g, pd, p2g = compute_result_probs(xg1_trad, xg2_trad)
            
            result_lines.append(f'   xG: К1={xg1_trad:.2f}  К2={xg2_trad:.2f}  Тотал={xg1_trad+xg2_trad:.2f}')
            result_lines.append(f'   П1: {p1g*100:.1f}%  Ничья: {pd*100:.1f}%  П2: {p2g*100:.1f}%')
            
            total_totals = calculate_total_totals(xg1_trad, xg2_trad)
            result_lines.append(f'   ТБ 2.5: {total_totals["ТБ 2.5"]:.1f}%')
            result_lines.append('')
        
        # λ-алгоритм
        if algo_map[algorithm] in ['both', 'lambda']:
            result_lines.append('─' * 45)
            result_lines.append('🔬 λ-АЛГОРИТМ (С TMPR)')
            result_lines.append('─' * 45)
            result_lines.append(f'   TMPR: К1={tmpr1:.1f}  К2={tmpr2:.1f}  Разница={tmpr1-tmpr2:+.1f}')
            
            from predictor import _prepare_lambda_data
            
            team1_lambda = _prepare_lambda_data(team1_data)
            team2_lambda = _prepare_lambda_data(team2_data)
            
            prediction = predict_match_lambda_with_tmpr(team1_lambda, team2_lambda, tmpr1, tmpr2)
            
            xg1_final = prediction['expected_goals']['final'][0]
            xg2_final = prediction['expected_goals']['final'][1]
            p1g, pd, p2g = compute_result_probs(xg1_final, xg2_final)
            
            result_lines.append(f'   xG: К1={xg1_final:.2f}  К2={xg2_final:.2f}  Тотал={xg1_final+xg2_final:.2f}')
            result_lines.append(f'   П1: {p1g*100:.1f}%  Ничья: {pd*100:.1f}%  П2: {p2g*100:.1f}%')
            
            total_totals = calculate_total_totals(xg1_final, xg2_final)
            result_lines.append(f'   ТБ 2.5: {total_totals["ТБ 2.5"]:.1f}%')
            
            # Точный счет
            top_scores = prediction['top_scores']
            result_lines.append('')
            result_lines.append('   Топ-3 точных счета:')
            for i, ((h, a), prob) in enumerate(top_scores[:3], 1):
                result_lines.append(f'   {i}. {h}:{a} - {prob:.1f}%')
        
        result_lines.append('')
        result_lines.append(f'📅 Анализ выполнен: {datetime.now().strftime("%d.%m.%Y %H:%M:%S")}')
        
        # Сохраняем результат в session state
        st.session_state.result_text = '\n'.join(result_lines)

# Отображение результатов
if 'result_text' in st.session_state:
    st.markdown("---")
    st.markdown("### 📊 РЕЗУЛЬТАТЫ АНАЛИЗА")
    
    # Отображаем результаты в текстовом поле
    st.code(st.session_state.result_text, language="text")
    
    # Кнопка для скачивания
    if save_clicked:
        # Формируем имя файла
        if st.session_state.selected_match:
            filename = f"{st.session_state.selected_match['home_team']}_vs_{st.session_state.selected_match['away_team']}".replace(' ', '_')
        else:
            filename = "match_analysis"
        filename += f"_{datetime.now().strftime('%Y%m%d_%H%M%S')}.txt"
        
        st.download_button(
            label="📥 Скачать отчет",
            data=st.session_state.result_text,
            file_name=filename,
            mime="text/plain",
            use_container_width=True
        )

# Футер
st.markdown("---")
st.markdown(
    "<p style='text-align: center; color: #666;'>⚽ XG Score — Футбольный предиктор с интеграцией SStats.net</p>",
    unsafe_allow_html=True
)
