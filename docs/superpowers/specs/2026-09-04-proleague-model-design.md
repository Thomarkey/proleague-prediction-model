# Pro League Model — Design

Port of a Google Sheets Belgian Pro League prediction and betting model to a
Kotlin Spring Boot application.

## Purpose

The model predicts scorelines for each Jupiler Pro League matchday from
home/away scoring rates, a rank-derived strength multiplier and recent form.
From the predicted scoreline it derives bets across five markets, records the
odds, and tracks profit per round.

Today this lives in a spreadsheet where every input is typed by hand. The
application replaces the manual entry with API-Football, keeps the arithmetic
identical, and makes the bet-selection rules explicit instead of held in the
operator's head.

## Scope

Season 2025/26 onward, starting empty. No import of prior rounds or seasons.

In scope: predicted scores, bet selection, gains tracking, React UI.
Out of scope: historical back-fill, multi-league support, live odds, staking
strategies other than one unit per game per market.

## Fidelity

The calculation is an exact port. Output must match the spreadsheet cell for
cell. Known oddities in the sheet are preserved, not corrected:

- The home leg scales form by `formH / (formH + formA) * 2.1` — normalised and
  bounded. The away leg scales by `formA * formH` — an unbounded product. This
  asymmetry is almost certainly unintended but is retained.

Two parts of the sheet are dropped because no formula reads them:

- `VALUES` column A and `RANKING` column B, the 5..0 strength bucket. Only the
  multiplier in column C is referenced.
- `FIXTURES` column K, which multiplies form twice on home rows
  (`IF(E="H", ...*I, ...) * I`) and does not feed the scoreline.

### Verification baseline

The formula reading was confirmed by recomputing the sheet's cached outputs
from raw inputs. All seven cached fixtures matched to two decimals:

| Fixture | Predicted |
|---|---|
| Dender v Gent | 0.44 - 2.74 |
| Zulte-Waregem v Charleroi | 0.82 - 1.00 |
| Standard v Westerlo | 1.00 - 1.26 |
| RAAL v Genk | 0.34 - 1.12 |
| OH Leuven v Antwerp | 0.53 - 0.70 |
| Club Brugge v KV Mechelen | 1.63 - 1.33 |
| STVV v Union | 0.58 - 0.96 |

This snapshot is the golden test. The port is wrong if it does not reproduce
these numbers.

## Architecture

Hexagonal, following the layering used across the other projects.

```
domain/       Team, TeamRecord, Standings, Form, Fixture,
              Prediction, Scoreline, Market, Pick, Selection, Settlement
application/  PredictionService, BetSelectionService, SettlementService, RefreshService
port/         FootballDataPort, RoundRepositoryPort, StandingsRepositoryPort
adapter/      apifootball/  ApiFootballClient and from() mappers
              mongo/        documents and repositories
presentation/ RoundController, DTOs
```

Dependencies flow inward. Adapters map to domain types with `from()` on the
adapter type; domain types never reference infrastructure. DTOs may depend on
domain types via `from()`, never the reverse. No business logic on DTOs —
enrichment happens on domain models and maps to DTOs at the controller.

### Domain

`TeamRecord` holds home and away played/for/against and exposes the four rates
as functions, not computed properties:

```kotlin
data class TeamRecord(
    val team: Team,
    val rank: Int,
    val homePlayed: Int, val homeFor: Int, val homeAgainst: Int,
    val awayPlayed: Int, val awayFor: Int, val awayAgainst: Int,
) {
    fun xgHomeFor(): Double = homeFor.toDouble() / homePlayed
    fun xgHomeAgainst(): Double = homeAgainst.toDouble() / homePlayed
    fun xgAwayFor(): Double = awayFor.toDouble() / awayPlayed
    fun xgAwayAgainst(): Double = awayAgainst.toDouble() / awayPlayed
}
```

`Standings` owns the ordered records, the four league averages, and the
rank-to-multiplier lookup.

`Form` wraps the last three results and exposes `value()`:

```
W * (2/5) + D * (1/3) + L * (4/15), or 0.0 when fewer than three results
```

### Calculation

```kotlin
fun homeGoals(home: TeamRecord, away: TeamRecord, formH: Double, formA: Double) =
    (home.xgHomeFor() / avg.homeFor) * home.multiplier *
    ((away.xgAwayAgainst() / avg.awayAgainst) / away.multiplier) *
    (formH / (formH + formA) * config.formGoalScale)

fun awayGoals(away: TeamRecord, home: TeamRecord, formA: Double, formH: Double) =
    (away.xgAwayFor() / avg.awayFor) * away.multiplier *
    ((home.xgHomeAgainst() / avg.homeAgainst) / home.multiplier) *
    formA * formH
```

Both results are capped at `config.goalCap` and rounded to integers for the
scoreline. The unrounded values are retained for display.

### Configuration

Tuning knobs, not constants. All in `application.yml`:

| Key | Value | Meaning |
|---|---|---|
| `model.form-goal-scale` | 2.1 | Home-leg form scale, tuned down previously |
| `model.goal-cap` | 5.0 | Per-team cap on predicted goals |
| `model.min-odds` | 1.16 | Below this, the bet is not worth placing |
| `model.max-odds` | 5.5 | Above this, the model is not trusted to call it |
| `model.min-double-chance-odds` | 1.4 | Stricter floor for double chance |
| `model.rank-multipliers` | 1.31 … 0.64 | 16 values, indexed by rank |

## Bet selection

Derived from the rounded scoreline. Odds are captured at selection time.

| Market | Pick | Placed when |
|---|---|---|
| 1X2 | `1` if home > away, `2` if away > home, else `X` | odd >= 1.16 |
| over | line = total − 0.5 | odd >= 1.16 |
| onder | line = total + 0.5 | odd >= 1.16 |
| btts | `YES` if both >= 1, else `NO` | odd >= 1.16 |
| dubbele kans | `1X` if home > away, `X2` if away > home | prediction is not a draw, and odd >= 1.4 |

A 3-1 prediction yields: `1`, over 3.5, under 4.5, btts YES, `1X`.
A 2-2 prediction yields: `X`, over 3.5, under 4.5, btts YES, and no double
chance — a predicted draw has no favoured side to back.

This supersedes the sheet's `if X : degene met meer doelp in score, evenveel
= 1x` note. There is no unrounded tie-break; a predicted draw simply skips
the market.

Both odds floors are inclusive. Only an odd of exactly 1.16 or 1.40 is
affected, and both are configuration values.

### Settlement

| Market | Wins when |
|---|---|
| 1X2 | actual result matches the pick |
| over | actual total > line |
| onder | actual total < line |
| btts | YES and both scored, or NO and not both |
| dubbele kans | `1X` and home win or draw; `X2` and away win or draw |

Netto per market is the sum of winning odds minus the number of stakes
actually placed. This differs from the sheet, which subtracts 8 unconditionally
and so charges a unit for bets the 1.16 filter skipped.

Staking is one unit per game per market. Treating a round as a single eight-unit
stake is a presentation-level divide, not a model change.

## Data ingestion

API-Football v3, base `https://v3.football.api-sports.io`, Jupiler Pro League.
League id and season are configuration. Refresh is on demand from a UI button —
no scheduler, to stay inside the free tier's daily quota.

Endpoints used: `/standings`, `/fixtures` (by league, season and round; and by
team with `last=3` for form), `/odds` (by fixture).

Exact request and response shapes are to be read from the provider's
documentation and verified against a live call before the mapper is written.
They are deliberately not specified here.

Refresh sequence:

1. Fetch standings, derive `TeamRecord` per team and the four league averages.
2. Fetch the round's fixtures.
3. Fetch each team's last three finished fixtures, derive `Form`.
4. Compute predictions.
5. Fetch odds per fixture, apply the selection rules, persist the round.

A later refresh finds fixtures at full time, settles the stored selections and
computes netto per market plus the running total.

### Constraints

- `/odds` serves only the last seven days and cannot be back-filled. A round
  must be refreshed before kickoff or its odds are lost permanently. The UI
  flags any round holding selections without odds.
- Odds coverage is a per-league flag on the provider; it is checked at startup.
- The Belgian season splits into Play-offs I, II and III, so `/standings`
  returns several tables. Take the table containing all sixteen teams, falling
  back to the first. Play-off tables reorder teams and therefore change the
  rank multiplier.
- Team names from the provider will not match the spreadsheet's short names
  (`STVV`, `RAAL`, `Union`). Teams are keyed by the provider's numeric team id;
  display names are a separate field.

## Persistence

MongoDB, two collections.

`rounds` — one document per matchday: round number, season, fixtures with
predicted and actual scores, selections with market, pick, odds and settlement,
netto per market.

`standings` — one document per refresh: the ordered team records and the
computed averages, so a round's prediction can be traced to the table it was
built from.

## Presentation

React SPA against a JSON REST API. Endpoints:

- `GET /api/rounds` — list with netto per market and running total
- `GET /api/rounds/{round}` — fixtures, predictions, selections, settlement
- `POST /api/rounds/{round}/refresh` — trigger ingestion for a round
- `GET /api/standings` — current table with rates and multipliers

`POST` bodies use command objects; the DTO owns `toXxxCommand()`. No request
body on any `GET`.

## Error handling

Provider failures surface as a failed refresh with the reason; the previously
persisted round is left untouched rather than partially overwritten. A refresh
is idempotent — re-running it for the same round replaces that round's
prediction but never its already-settled selections, so a late standings change
cannot rewrite history.

A team with zero played matches home or away yields a division by zero. Early
in a season this is real: the refresh rejects the round with a clear message
rather than emitting `Infinity` or `NaN`.

## Testing

Test-driven. Randomised test data from a `DomainMother`, following the
conventions in the existing projects.

- Golden test: the verified snapshot above, asserting all seven scorelines to
  two decimals. This is the proof the port is faithful.
- Form parsing table test: `WDW` → 1.1333, `LLL` → 0.8, `WWW` → 1.2, `WD` → 0.0.
- Selection rules table test, one case per market, including a predicted draw
  skipping double chance and both odds floors at their exact boundary.
- Settlement table test, one case per market for win and loss.
- Adapter test against recorded provider responses, so a schema change fails
  loudly rather than silently producing nulls.
