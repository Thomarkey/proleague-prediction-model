# Pro League model

The Jupiler Pro League prediction sheet, ported to Kotlin. Predicts a score per fixture from the
league table and recent form, picks the five markets the sheet bets, and tracks the running profit.

The port is exact: `GoalModelTest` recomputes the seven fixtures the sheet had cached and asserts
every one to two decimals, home/away form asymmetry included.

## Running

Needs a MongoDB on `localhost:27017`. No API key: the data comes from the same public JSON the
SofaScore site reads.

```sh
cd frontend && npm install && cd ..
./gradlew bootRun
```

Then open http://localhost:8080 and press **refresh** for a round.

For frontend work, `cd frontend && npm run dev` serves on 5173 and proxies `/api` to 8080.

## Correcting odds

Every odd in a speeldag is an input. The provider's price is only a proxy for what is actually
on offer, so typing over one saves it and marks it as hand-typed, which is what keeps it: the
next **voorspel** re-fetches the book but lays the typed odds back over it. The bet is restaked
against the same floor as any other, so a correction can also place a bet the provider's price
had skipped.

An edit is remembered against the pick it was typed for, not the market. If a re-prediction
moves the score from 3-1 to 2-1 the line moves with it, and a price typed for over 3.5 is
rightly not reused for over 2.5.

## Configuration

Everything the sheet hard-coded lives in `application.yml`: the form scale (2.1), the goal cap (5),
the odds floor (1.16) and the rank multipliers. Provider settings take environment overrides —
`SOFASCORE_TOURNAMENT`, `SOFASCORE_SEASON`, `MONGO_URI`.

## Value bets

The double chance used to answer to its own floor of 1.4 and skip everything under it. It now
takes the same 1.16 as every other market, and 1.4 stays on as `double-chance-value-odds`: a
diamond after the odd. The market covers two of the three results, so one still paying that much
is the book rating the favoured side well below what the model makes it.

Tracking carries them as a sixth column, so the flag can be judged on what it made rather than
taken on faith. It is a cut across the dubbele kans column, not money beside it: a value bet is
one stake, counted in both.

`SOFASCORE_SEASON` is SofaScore's own season id, not the calendar year: 77040 is
2025/26. `GET /unique-tournament/38/seasons` lists them.

## Rank multipliers

The sheet's sixteen multipliers were a symmetric ladder, read as offsets from 1.00: 0.01 either
side of the middle, then a 0.05 step outward, with only its last entry hanging an extra step off
the bottom. `rank-multipliers` is that same ladder rebuilt for eighteen teams. An even table has no
middle rank, so the centre falls between 9 and 10 and every mirrored pair -- (1,18), (2,17) ...
(9,10) -- sums to 2.00, which puts the mean back on 1.00 exactly.

Holding the 0.05 step means the ladder grows outward rather than compressing, so the ends now reach
1.41 and 0.59 where the sixteen-team sheet stopped at 1.31 and 0.64. Every position is therefore
worth a little more or less than it was, not just the two new ones: the step is what was tuned, the
endpoints follow from it.

## Known gap

Early in a season the table holds real zeros -- a side that has not conceded at home after three
games -- and the model multiplies the four rates together, so one zero would drive a fixture to
0-0 and void every market on it. `TeamRecord` floors each rate at 0.01 to stop that. It is a
stand-in for a missing prior: the honest fix is to shrink each rate toward the league average by
matches played, which would also stop a 1-in-1 record reading as strongly as a 30-in-30 one.
