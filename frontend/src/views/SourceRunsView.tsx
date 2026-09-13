import { useEffect, useState } from 'react'
import { fetchJson, queryString } from '../api'
import { Pagination } from '../components/Pagination'
import { StatusBadge } from '../components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '../components/UiState'
import type { PageResponse, SourceRun, SourceRunDetail } from '../types'

function formatTime(value: string | null) {
  return value ? new Intl.DateTimeFormat('en-GB', { dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value)) : '—'
}

export function SourceRunsView() {
  const [source, setSource] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<PageResponse<SourceRun> | null>(null)
  const [detail, setDetail] = useState<SourceRunDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setError(null)
    fetchJson<PageResponse<SourceRun>>(`/api/v1/source-runs${queryString({ source, status, page, size: 10 })}`)
      .then(setData)
      .catch((reason: Error) => setError(reason.message))
  }, [source, status, page])

  const selectRun = (id: number) => {
    setDetail(null)
    fetchJson<SourceRunDetail>(`/api/v1/source-runs/${id}`)
      .then(setDetail)
      .catch((reason: Error) => setError(reason.message))
  }

  return (
    <div className="view-stack">
      <header className="view-header"><div><p className="eyebrow">Execution history</p><h1>Source runs</h1><p>Transport, upstream outcome, raw evidence, and downstream lifecycle remain separate.</p></div></header>
      <section className="filter-bar">
        <label>Source<select value={source} onChange={(event) => { setSource(event.target.value); setPage(0) }}><option value="">All sources</option><option>FSS_FINLIFE</option><option>SEOUL_POLICY_LOAN_RATE</option></select></label>
        <label>Status<select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}><option value="">All statuses</option><option>SUCCESS</option><option>FAILED</option><option>RUNNING</option></select></label>
      </section>
      {error && <ErrorState error={error} />}
      {!data && !error && <LoadingState />}
      {data && (
        <section className="panel">
          <div className="panel-heading"><div><p className="eyebrow">Immutable log</p><h2>{data.totalElements} executions</h2></div></div>
          {data.content.length === 0 ? <EmptyState /> : (
            <div className="table-wrap"><table className="interactive-table"><thead><tr><th>Run</th><th>Source</th><th>Status</th><th>HTTP</th><th>Started</th><th>Ended</th><th>Snapshots</th></tr></thead>
              <tbody>{data.content.map((run) => <tr key={run.id} onClick={() => selectRun(run.id)} tabIndex={0} onKeyDown={(event) => event.key === 'Enter' && selectRun(run.id)}><td className="mono">#{run.id}</td><td>{run.sourceCode}</td><td><StatusBadge value={run.status} /></td><td className="mono">{run.httpStatus ?? '—'}</td><td>{formatTime(run.startedAt)}</td><td>{formatTime(run.endedAt)}</td><td>{run.rawSnapshotCount}</td></tr>)}</tbody>
            </table></div>
          )}
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </section>
      )}
      {detail && (
        <section className="detail-panel">
          <div className="panel-heading"><div><p className="eyebrow">Run detail</p><h2>SourceRun #{detail.sourceRun.id}</h2></div><StatusBadge value={detail.sourceRun.status} /></div>
          <div className="detail-grid">
            <div><span>Source</span><strong>{detail.dataSource.name}</strong></div><div><span>HTTP status</span><strong>{detail.sourceRun.httpStatus ?? '—'}</strong></div>
            <div><span>Error type</span><strong>{detail.sourceRun.errorType ?? '—'}</strong></div><div><span>Publication</span><StatusBadge value={detail.publication?.status ?? null} /></div>
            <div><span>Quality issues</span><strong>{detail.qualityIssueCount}</strong></div><div><span>Quarantined</span><strong>{detail.quarantineCount}</strong></div>
          </div>
          {detail.sourceRun.errorMessage && <div className="notice negative-notice"><strong>Persisted failure</strong><span>{detail.sourceRun.errorMessage}</span></div>}
          <div className="normalized-strip"><span><strong>{detail.normalizedRecordCounts.loanProducts}</strong> products</span><span><strong>{detail.normalizedRecordCounts.productTerms}</strong> terms</span><span><strong>{detail.normalizedRecordCounts.loanRateObservations}</strong> observations</span></div>
          <h3>Raw snapshot metadata</h3>
          {detail.rawSnapshots.map((snapshot) => <div className="snapshot-row" key={snapshot.id}><span className="mono">#{snapshot.id}</span><span>{snapshot.contentType}</span><code title={snapshot.payloadHash}>{snapshot.payloadHash.slice(0, 16)}…</code><span>{snapshot.payloadSize.toLocaleString()} bytes</span><span>{formatTime(snapshot.fetchedAt)}</span></div>)}
        </section>
      )}
    </div>
  )
}
