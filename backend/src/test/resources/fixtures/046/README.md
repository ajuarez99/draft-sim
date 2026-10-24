# Spec 046 fixtures

Fetched 2026-10-23 (read-only GETs):

- auction-draft.json: GET https://api.sleeper.app/v1/draft/1415422395308609536 (Legacy Ball Knowers, complete $200 auction)
- auction-picks.json: its /picks (230 picks, sum of metadata.amount = 1,982)
- sleeper-nba-2026-season.json: GET https://api.sleeper.app/projections/nba/2026?season_type=regular with position[]=C,PF,PG,SF,SG (the params SleeperProjectionClient sends)
- yahoo-nba-page-0.json, yahoo-nba-page-175.json: pub-api-ro nba.l.public players sort=DA_AP out=draft_analysis format=json_f, start=0 and start=175
