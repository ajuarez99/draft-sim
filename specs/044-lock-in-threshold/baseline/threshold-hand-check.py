# Spec 044 T030: the Lock/Wait thresholds recomputed independently of the Java, pooled over 2024 + 2025.
#   V1 = (1-p)*E[m] + p*(-1);  V(k+1) = E[max(m, Vk)];  p = missed / (rodeOut + missed), pooled.
# Two populations, both printed:
#   per-game : every played game (sp>0, All-Star dropped) of a starter's final multi-game week that has >= 5 prior
#              games and a positive stand-in. This is what LockInService does.
#   weeks    : stopping.py's rule, which drops a WHOLE week if any of its games lacks a stand-in.
# usage: python thresholds_pooled.py <scratchpad dir>
import json, sys, collections, statistics as st
d = sys.argv[1]
exec(open(d + '/measure_v2.py').read().split('names=')[0])   # loads L, games, score(), MINPRIOR

per_game = {}; weeks_mode = {}; counts = {}
for season in (2024, 2025):
    sc = L[season]['scoring']
    pg = []; wm = []; rode = 0; missed = 0
    for l in open(d + '/rwp.jsonl'):
        r = json.loads(l)
        if r['season'] != season or not r['starters'] or not r['players_points']: continue
        for pid in r['starters']:
            allg = sorted(games.get((season, pid), []), key=lambda g: (g['game_date'], g['game_id']))
            wk = [g for g in allg if g['week'] == r['week']]
            c = r['players_points'].get(pid)
            if len(wk) >= 2:
                s = [score(sc, g['stats']) for g in wk]
                row = []
                for g in wk:
                    prior = [score(sc, h['stats']) for h in allg if h['game_date'] < g['game_date']]
                    if len(prior) < MINPRIOR or st.mean(prior) <= 0:
                        row.append(None); continue
                    b = st.mean(prior); m = (score(sc, g['stats']) - b) / b
                    row.append(m); pg.append(m)
                if all(x is not None for x in row): wm.extend(row)
                if c is not None:
                    idx = [i for i, x in enumerate(s) if abs(x - c) < 0.005]
                    if not idx and c == 0: missed += 1
                    elif len(idx) == 1 and idx[0] == len(wk) - 1: rode += 1
    per_game[season] = pg; weeks_mode[season] = wm; counts[season] = (rode, missed)

def fit(ms, p, cap=4):
    V = [None, (1 - p) * st.mean(ms) + p * -1.0]
    for k in range(2, cap + 1): V.append(st.mean(max(m, V[-1]) for m in ms))
    return V[1:]

for name, pools in (('per-game', per_game), ('weeks (stopping.py rule)', weeks_mode)):
    print('==', name)
    for season in (2024, 2025):
        ms = pools[season]; rode, missed = counts[season]
        print(' ', season, 'n', len(ms), 'p(season) %.5f' % (missed / (rode + missed)),
              'no-p', [round(v, 3) for v in fit(ms, 0.0)], 'with-p', [round(v, 3) for v in fit(ms, missed / (rode + missed))])
    allm = per_game[2024] + per_game[2025] if name == 'per-game' else weeks_mode[2024] + weeks_mode[2025]
    rode = sum(counts[s][0] for s in counts); missed = sum(counts[s][1] for s in counts)
    p = missed / (rode + missed)
    print('  POOLED n', len(allm), 'rodeOut', rode, 'missed', missed, 'pMissed %.17g' % p)
    print('  POOLED thresholds', ['%.17g' % v for v in fit(allm, p)])
