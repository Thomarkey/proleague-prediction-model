import {useCallback, useEffect, useState} from 'react'

const MARKETS = ['1X2', 'over', 'onder', 'btts', 'dubbele kans']
// The value bets are not a market but a cut across one, so they only get a column where the
// profit is tracked -- never in the round itself, where they are marked on the bet instead.
const COLUMNS = [...MARKETS, 'value bet']
const TABS = ['Speeldag', 'Klassement', 'Tracking']
const FORM_LABELS = { W: 'gewonnen', D: 'gelijk', L: 'verloren' }
// Each key is a column set on a standings record, so the table renders one the same as another.
const VIEWS = [
  { key: 'total', label: 'Totaal' },
  { key: 'home', label: 'Thuis' },
  { key: 'away', label: 'Uit' },
]
// Served CORS-open straight off the provider's CDN, so no crest is fetched or stored here.
const crest = (teamId) => `https://img.sofascore.com/api/v1/team/${teamId}/image`

async function call(path, options) {
  const response = await fetch(path, options)
  if (response.status === 404) return null
  const body = await response.json().catch(() => null)
  if (!response.ok) throw new Error(body?.message || `${response.status} ${response.statusText}`)
  return body
}

const money = (value) => (value == null ? '—' : `${value > 0 ? '+' : ''}${value.toFixed(2)}`)
const sign = (value) => (value == null ? '' : value > 0 ? 'up' : value < 0 ? 'down' : '')

export default function App() {
  const [tab, setTab] = useState(TABS[0])
  const [number, setNumber] = useState(1)
  const [round, setRound] = useState(null)
  const [rounds, setRounds] = useState([])
  const [standings, setStandings] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const load = useCallback(async (roundNumber) => {
    setError(null)
    try {
      const [detail, summaries] = await Promise.all([
        call(`/api/rounds/${roundNumber}`),
        call('/api/rounds'),
      ])
      setRound(detail)
      setRounds(summaries || [])
    } catch (failure) {
      setError(failure.message)
    }
  }, [])

  useEffect(() => {
    load(number)
  }, [number, load])

  // Stored server side, so the tab reads what was last fetched rather than fetching again.
  useEffect(() => {
    if (tab === 'Klassement' && !standings) call('/api/standings').then(setStandings).catch(() => {})
  }, [tab, standings])

  async function run(work) {
    setBusy(true)
    setError(null)
    try {
      await work()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  const predict = () =>
    run(async () => {
      setRound(await call(`/api/rounds/${number}/predict`, { method: 'POST' }))
      setRounds((await call('/api/rounds')) || [])
    })

  // The netto moves with the odds, so the profit tab is refetched alongside the round.
  const setOdds = (fixtureId, market, odds) =>
    run(async () => {
      setRound(
        await call(`/api/rounds/${number}/odds`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ fixtureId, market, odds }),
        }),
      )
      setRounds((await call('/api/rounds')) || [])
    })

  const refreshStandings = () =>
    run(async () => setStandings(await call('/api/standings/refresh', { method: 'POST' })))

  return (
    <main>
      <header>
        <h1>Pro League model</h1>
      </header>

      <nav className="tabs">
        {TABS.map((name) => (
          <button key={name} className={name === tab ? 'tab active' : 'tab'} onClick={() => setTab(name)}>
            {name}
          </button>
        ))}
      </nav>

      {error && <p className="error">{error}</p>}

      {tab === 'Speeldag' && (
        <>
          <div className="actions">
            <label>
              Speeldag
              <input
                type="number"
                min="1"
                value={number}
                onChange={(event) => setNumber(Number(event.target.value) || 1)}
              />
            </label>
            <button onClick={predict} disabled={busy}>
              {busy ? 'Bezig…' : 'Voorspel'}
            </button>
          </div>
          {round ? (
            <RoundTable round={round} onOdds={setOdds} />
          ) : (
            <p className="empty">Nog geen data. Klik op voorspel.</p>
          )}
        </>
      )}

      {tab === 'Klassement' && (
        <>
          <div className="actions right">
            <button onClick={refreshStandings} disabled={busy}>
              {busy ? 'Ophalen…' : 'Refresh klassement'}
            </button>
          </div>
          {standings ? (
            <StandingsTable standings={standings} />
          ) : (
            <p className="empty">Nog geen klassement. Klik op refresh.</p>
          )}
        </>
      )}

      {tab === 'Tracking' &&
        (tracked(rounds).length ? (
          <Totals rounds={tracked(rounds)} />
        ) : (
          <p className="empty">Nog geen afgerekende speeldagen.</p>
        ))}
    </main>
  )
}

function Crest({ teamId, name }) {
  return <img className="crest" src={crest(teamId)} alt="" title={name} loading="lazy" />
}

function status(round) {
  if (!round.predicted) return 'nog niet voorspeld'
  if (round.settled) return 'afgerekend'
  if (round.matches.every((match) => new Date(match.kickoff).getTime() > Date.now())) {
    return 'nog niet gestart'
  }
  return 'loopt nog'
}

function RoundTable({ round, onOdds }) {
  return (
    <section>
      <h2>
        Speeldag {round.number} <small>{status(round)}</small>
      </h2>
      <table>
        <thead>
          <tr>
            <th className="name" colSpan="3">
              Match
            </th>
            <th>Voorspelling</th>
            <th>Uitslag</th>
            {MARKETS.map((market) => (
              <th key={market}>{market}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {round.matches.map((match) => (
            <tr key={match.fixtureId}>
              <td className="name">
                <span className="side">
                  <Crest teamId={match.homeId} name={match.home} />
                  {match.home}
                </span>
              </td>
              <td className="vs">–</td>
              <td className="name away">
                <span className="side">
                  <Crest teamId={match.awayId} name={match.away} />
                  {match.away}
                </span>
              </td>
              <td
                title={
                  match.predictedScore
                    ? `${match.predictedHomeGoals.toFixed(2)} – ${match.predictedAwayGoals.toFixed(2)}`
                    : ''
                }
              >
                {match.predictedScore || '—'}
              </td>
              <td>{match.actualScore || '—'}</td>
              {MARKETS.map((market) => (
                <td key={market}>
                  <Bet
                    selection={match.selections.find((s) => s.market === market)}
                    onOdds={(odds) => onOdds(match.fixtureId, market, odds)}
                  />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th className="name" colSpan="5">
              netto
            </th>
            {MARKETS.map((market) => (
              <th key={market} className={sign(round.netto[market])}>
                {money(round.netto[market])}
              </th>
            ))}
          </tr>
        </tfoot>
      </table>
    </section>
  )
}

/**
 * A skipped pick still shows, so it is visible why nothing was staked — and still colours
 * won or lost, so the odds floor can be judged on what it turned down.
 *
 * The odd itself is an input, because the provider's price is regularly not the one on offer.
 * It is uncontrolled and keyed on the stored value, so a save or a re-prediction reseeds it
 * while typing over it does not fight the round that is still in flight.
 */
function Bet({ selection, onOdds }) {
  if (!selection) return <span className="skipped">—</span>
  const status = selection.won == null ? '' : selection.won ? 'won' : 'lost'
  const save = (event) => {
    const odds = Number(event.target.value)
    if (odds >= 1 && odds !== selection.odds) onOdds(odds)
    else event.target.value = selection.odds ?? ''
  }
  return (
    <span
      className={['bet', selection.placed ? '' : 'skipped', status].filter(Boolean).join(' ')}
      title={selection.placed ? '' : 'niet gespeeld'}
    >
      {selection.pick} @
      <input
        key={selection.odds}
        className={selection.edited ? 'odd edited' : 'odd'}
        type="number"
        step="0.01"
        min="1"
        defaultValue={selection.odds ?? ''}
        placeholder="geen"
        title={selection.edited ? 'handmatig aangepast' : 'odd van de provider'}
        onKeyDown={(event) => event.key === 'Enter' && event.target.blur()}
        onBlur={save}
      />
      {selection.valueBet && (
        <i className="value" title="value bet: dubbele kans die nog 1.4 of meer betaalt, op een match met een voorspelde winnaar">
          ◆
        </i>
      )}
    </span>
  )
}

/**
 * Speeldagen the model never bet on still get a row, so the season reads 1 to n rather than
 * starting halfway. They carry no netto, so they add nothing to the running total.
 */
function withGaps(rounds) {
  const last = rounds[rounds.length - 1].number
  return Array.from(
    { length: last },
    (_, index) => rounds.find((round) => round.number === index + 1) || { number: index + 1, netto: {} },
  )
}

/**
 * A speeldag that has been predicted but not yet played has no profit to report, so it is left
 * out until its first result lands rather than sitting at the foot of the table as a row of
 * dashes. It carries nothing into the running total either way, so the totals do not move.
 */
function tracked(rounds) {
  return rounds.filter((round) => Object.values(round.netto).some((profit) => profit != null))
}

function Totals({ rounds }) {
  const last = rounds[rounds.length - 1]
  return (
    <section>
      <h2>Tracking</h2>
      <table className="even">
        <thead>
          <tr>
            <th>Speeldag</th>
            {COLUMNS.map((column) => (
              <th key={column}>{column}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {withGaps(rounds).map((summary) => (
            <tr key={summary.number}>
              <td>{summary.number}</td>
              {COLUMNS.map((column) => (
                <td key={column} className={sign(summary.netto[column])}>
                  {money(summary.netto[column])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th>totaal</th>
            {COLUMNS.map((column) => (
              <th key={column} className={sign(last.runningTotal[column])}>
                {money(last.runningTotal[column])}
              </th>
            ))}
          </tr>
        </tfoot>
      </table>
    </section>
  )
}

/** Oldest first, so the rightmost pill is the most recent match. */
function FormStrip({ code }) {
  if (!code) return <span className="muted">—</span>
  return (
    <span className="form">
      {[...code].map((outcome, index) => (
        <i key={index} className={`pill ${outcome}`} title={FORM_LABELS[outcome]}>
          {outcome}
        </i>
      ))}
    </span>
  )
}

function StandingsTable({ standings }) {
  const [view, setView] = useState(VIEWS[0])
  // The provider only ranks the overall table, so the split ones are ordered here on the
  // same tiebreakers the league uses.
  const records =
    view.key === 'total'
      ? standings.records
      : [...standings.records].sort(
          (a, b) =>
            b[view.key].points - a[view.key].points ||
            b[view.key].goalDifference - a[view.key].goalDifference ||
            b[view.key].goalsFor - a[view.key].goalsFor,
        )

  return (
    <section>
      <h2>
        Klassement <small> bijgewerkt {new Date(standings.fetchedAt).toLocaleString('nl-BE')}</small>
      </h2>
      <nav className="tabs">
        {VIEWS.map((option) => (
          <button
            key={option.key}
            className={option.key === view.key ? 'tab active' : 'tab'}
            onClick={() => setView(option)}
          >
            {option.label}
          </button>
        ))}
      </nav>
      <table>
        <thead>
          <tr>
            <th>#</th>
            <th className="name">Ploeg</th>
            <th title="gespeeld">M</th>
            <th title="gewonnen">W</th>
            <th title="gelijk">G</th>
            <th title="verloren">V</th>
            <th title="doelpunten voor">DV</th>
            <th title="doelpunten tegen">DT</th>
            <th title="doelsaldo">DS</th>
            <th title="punten">PT</th>
            <th title="laatste 5 wedstrijden, oudste eerst">Vorm</th>
          </tr>
        </thead>
        <tbody>
          {records.map((record, index) => (
            <tr key={record.team}>
              <td>{view.key === 'total' ? record.rank : index + 1}</td>
              <td className="name">
                <span className="side">
                  <Crest teamId={record.teamId} name={record.team} />
                  {record.team}
                </span>
              </td>
              <td>{record[view.key].played}</td>
              <td>{record[view.key].wins}</td>
              <td>{record[view.key].draws}</td>
              <td>{record[view.key].losses}</td>
              <td>{record[view.key].goalsFor}</td>
              <td>{record[view.key].goalsAgainst}</td>
              <td className={sign(record[view.key].goalDifference)}>
                {record[view.key].goalDifference > 0
                  ? `+${record[view.key].goalDifference}`
                  : record[view.key].goalDifference}
              </td>
              <td>
                <strong>{record[view.key].points}</strong>
              </td>
              <td>
                <FormStrip code={record.form} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
