# Performance de la vue Année (VOL-9 / VOL-24)

Critère : un graphique d'un an s'ouvre en **moins de 300 ms** et le zoom/défilement tient **60 i/s**, sur l'historique `two_years_history` (deux ans d'heures, BMS + MPPT + shunt, 9 séries chacun).

## Ce qui est mesuré, et où

| Mesure | Où | Statut |
|---|---|---|
| Temps de `loadStats` par période | `StatsPerformanceTest` (JVM, SQLite en mémoire, médiane de 5 passes, lignes `PERF` dans la sortie du test) | automatisé |
| Taille de fenêtre au zoom maximal | `ChartModelTest` (`atMaxZoom…`) | automatisé |
| 60 i/s au zoom et au défilement | téléphone réel | **à mesurer sur téléphone** (procédure ci-dessous) |

Les chiffres JVM ne sont pas ceux d'un téléphone : ils bornent le coût des requêtes et du calcul, pas le dessin.

## Résultats (JVM, poste de build ; une exécution isolée du test)

| Période | `loadStats` |
|---|---|
| Jour | ~7 ms |
| Semaine | ~20 ms |
| Mois | ~45 ms |
| Année, graphique et totaux (premier affichage) | ~110–135 ms |
| Année complète, avec comparaisons à l'année précédente | ~500–800 ms |

Avant optimisation, la vue Année complète coûtait ~1,3 s.

### Ce qui a changé pour tenir 300 ms

- Lectures **par plage** (`SystemStats.solarProduction(days, …)` / `consumption(days, …)`) : une requête par série pour toute la plage au lieu d'une par jour. Équivalence jour par jour vérifiée par `SystemStatsRangeTest`.
- Chargement **en deux temps** pour l'Année : graphique et totaux d'abord (`comparisons = false`), comparaisons ensuite. Le critère de 300 ms porte sur le premier affichage ; les comparaisons arrivent après, sans bloquer.
- `loadStats` tourne sur `Dispatchers.Default`, plus sur le fil principal.

## Zoom et `getXStep` (constat de revue, PR #9)

Le pas de l'axe était fixé à 1 minute y compris pour les séries horaires et journalières, avec un zoom maximal de 8 pas. Sur une semaine de points horaires ou un an de points journaliers, le zoom maximal n'affichait donc qu'environ 1 point.

Maintenant (`xStepOf` = médiane des écarts dans un segment) le zoom maximal affiche `max(12 pas, 60 min)` :

| Série | Points visibles au zoom maximal |
|---|---|
| Semaine, 1 point par heure | 12 (avant : ~1) |
| Année, 1 point par jour | 12 (avant : ~1) |
| Jour, 1 point par minute | 60 |

L'état de zoom est mémorisé (`rememberLineZoomState`) : un rechargement des données ne remet plus le zoom à zéro.

La mesure de ces tailles dans un vrai rendu Vico (Robolectric) s'est révélée instable (le dessin n'est pas déterministe sous Robolectric) et n'a pas été conservée ; la vérification visuelle est à faire sur téléphone.

## Procédure 60 i/s sur téléphone

1. Installer le build de la PR, mode **Démo**. L'historique de démo ne couvre que 60 jours (`DEMO_HISTORY_DAYS`) : `two_years_history` est un jeu de test JVM, pas un scénario de l'application. La vue Année montre donc 2–3 mois, ce qui suffit pour juger la fluidité du zoom et du défilement, mais pas le temps d'ouverture sur deux ans (mesuré seulement sur JVM ci-dessus).
2. Onglet Statistiques → **Année** → remettre les compteurs à zéro (`adb shell dumpsys gfxinfo app.voltdeck reset`), ouvrir l'onglet et chronométrer l'apparition du graphique (enregistrement d'écran, lecture image par image).
3. Zoomer à deux doigts puis faire défiler le graphique pendant ~10 s, puis relever `dumpsys gfxinfo app.voltdeck` : *Janky frames* et percentiles 90/95/99 (un percentile 95 sous 16,6 ms vaut 60 i/s).
4. Noter modèle du téléphone, version Android, build, et les chiffres.
