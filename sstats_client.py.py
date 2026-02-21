import requests
from typing import List, Dict, Optional

class SStatsClient:
    def __init__(self, api_key: str, base_url: str = "https://api.sstats.net"):
        self.api_key = api_key
        self.base_url = base_url
    
    def _make_request(self, endpoint: str, params: Optional[Dict] = None) -> Dict:
        """Базовый метод для запросов к API"""
        if params is None:
            params = {}
        params['apikey'] = self.api_key
        
        try:
            response = requests.get(f"{self.base_url}{endpoint}", params=params, timeout=10)
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as e:
            print(f"Ошибка API: {e}")
            return {}
    
    def get_matches_by_date(self, date: str) -> List[Dict]:
        """Получить все матчи на дату с пагинацией"""
        all_matches = []
        offset = 0
        limit = 1000
        
        while True:
            data = self._make_request('/Games/list', {
                'Date': date,
                'TimeZone': 3,
                'Order': -1,
                'Limit': limit,
                'Offset': offset
            })
            
            matches = data.get('data', []) if isinstance(data, dict) else data
            if not matches:
                break
                
            all_matches.extend(matches)
            offset += limit
            
            if len(matches) < limit:
                break
        
        return all_matches
    
    def get_team_last_matches(self, team_id: int, limit: int = 3) -> List[Dict]:
        """Получить последние матчи команды"""
        data = self._make_request('/Games/list', {
            'Team': team_id,
            'Ended': 'true',
            'Order': -1,
            'Limit': limit
        })
        
        return data.get('data', []) if isinstance(data, dict) else data
    
    def get_match_detailed_stats(self, match_id: int) -> Dict:
        """Получить детальную статистику матча"""
        data = self._make_request(f'/Games/{match_id}')
        return data.get('data', {}) if isinstance(data, dict) else data
    
    def extract_team_stats_from_match(self, match_data: Dict, team_id: int) -> Dict:
        """Извлекает статистику для команды из данных матча"""
        stats = {
            'pos': 50.0,
            'total_shots': 0,
            'shots_on_target': 0,
            'goal_scoring_chances': 0,
            'corners': 0,
            'shots_in_box': 0,
            'shots_out_box': 0,
            'touches_in_box': 0,
            'clearances': 0
        }

        # Определяем, хозяева или гости
        game_data = match_data.get('game', {})
        home_team = game_data.get('homeTeam', {})
        
        if isinstance(home_team, dict):
            is_home = (home_team.get('id') == team_id)
        else:
            is_home = False

        if match_data.get('statistics'):
            team_stats = match_data['statistics']

            if is_home:
                # Владение мячом
                if 'ballPossessionHome' in team_stats and team_stats['ballPossessionHome'] is not None:
                    stats['pos'] = float(team_stats['ballPossessionHome'])

                # Всего ударов
                if 'totalShotsHome' in team_stats and team_stats['totalShotsHome'] is not None:
                    stats['total_shots'] = int(team_stats['totalShotsHome'])

                # Удары в створ
                if 'shotsOnGoalHome' in team_stats and team_stats['shotsOnGoalHome'] is not None:
                    stats['shots_on_target'] = int(team_stats['shotsOnGoalHome'])

                # Голевые моменты
                if 'bigChancesHome' in team_stats and team_stats['bigChancesHome'] is not None:
                    stats['goal_scoring_chances'] = int(team_stats['bigChancesHome'])

                # Угловые
                if 'cornerKicksHome' in team_stats and team_stats['cornerKicksHome'] is not None:
                    stats['corners'] = int(team_stats['cornerKicksHome'])

                # Удары в штрафной
                if 'shotsInsideBoxHome' in team_stats and team_stats['shotsInsideBoxHome'] is not None:
                    stats['shots_in_box'] = int(team_stats['shotsInsideBoxHome'])

                # Удары из-за штрафной
                if 'shotsOutsideBoxHome' in team_stats and team_stats['shotsOutsideBoxHome'] is not None:
                    stats['shots_out_box'] = int(team_stats['shotsOutsideBoxHome'])

                # Касания в штрафной соперника
                if 'touchesInOppositionBoxHome' in team_stats and team_stats['touchesInOppositionBoxHome'] is not None:
                    stats['touches_in_box'] = int(team_stats['touchesInOppositionBoxHome'])

                # Выносы
                if 'clearancesHome' in team_stats and team_stats['clearancesHome'] is not None:
                    stats['clearances'] = int(team_stats['clearancesHome'])

            else:  # Гостевая команда
                # Владение мячом
                if 'ballPossessionAway' in team_stats and team_stats['ballPossessionAway'] is not None:
                    stats['pos'] = float(team_stats['ballPossessionAway'])

                # Всего ударов
                if 'totalShotsAway' in team_stats and team_stats['totalShotsAway'] is not None:
                    stats['total_shots'] = int(team_stats['totalShotsAway'])

                # Удары в створ
                if 'shotsOnGoalAway' in team_stats and team_stats['shotsOnGoalAway'] is not None:
                    stats['shots_on_target'] = int(team_stats['shotsOnGoalAway'])

                # Голевые моменты
                if 'bigChancesAway' in team_stats and team_stats['bigChancesAway'] is not None:
                    stats['goal_scoring_chances'] = int(team_stats['bigChancesAway'])

                # Угловые
                if 'cornerKicksAway' in team_stats and team_stats['cornerKicksAway'] is not None:
                    stats['corners'] = int(team_stats['cornerKicksAway'])

                # Удары в штрафной
                if 'shotsInsideBoxAway' in team_stats and team_stats['shotsInsideBoxAway'] is not None:
                    stats['shots_in_box'] = int(team_stats['shotsInsideBoxAway'])

                # Удары из-за штрафной
                if 'shotsOutsideBoxAway' in team_stats and team_stats['shotsOutsideBoxAway'] is not None:
                    stats['shots_out_box'] = int(team_stats['shotsOutsideBoxAway'])

                # Касания в штрафной соперника
                if 'touchesInOppositionBoxAway' in team_stats and team_stats['touchesInOppositionBoxAway'] is not None:
                    stats['touches_in_box'] = int(team_stats['touchesInOppositionBoxAway'])

                # Выносы
                if 'clearancesAway' in team_stats and team_stats['clearancesAway'] is not None:
                    stats['clearances'] = int(team_stats['clearancesAway'])

        return stats