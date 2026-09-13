import { useEffect, useState } from 'react'
import { fetchJson } from '../api'
import { MetricCard } from '../components/MetricCard'
import { StatusBadge } from '../components/StatusBadge'
import { ErrorState, LoadingState } from '../components/UiState'
import type { DataChange, Overview, PageResponse, Publication } from '../types'

function formatTime(value: string | null) {
  return value ? new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}

export function DashboardView() {
  const [overview, setOverview] = useState<Overview | null>(null)
  const [publications, setPublications] = useState<Publication[]>([])
  const [changes, setChanges] = useState<DataChange[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    Promise.all([
      fetchJson<Overview>('/api/v1/overview'),
      fetchJson<PageResponse<Publication>>('/api/v1/publications?size=5'),
      fetchJson<PageResponse<DataChange>>('/api/v1/data-changes?size=6'),
    ])
      .then(([overviewData, publicationData, changeData]) => {
        setOverview(overviewData)
        setPublications(publicationData.content)
        setChanges(changeData.content)
      })
      .catch((reason: Error) => setError(reason.message))
  }, [])

  if (error) return <ErrorState error={error} />
  if (!overview) return <LoadingState />

  return (
    <div className="view-stack">
      <header className="view-header">
        <div>
          <p className="eyebrow">Operational overview</p>
          <h1>Data reliability at a glance</h1>
          <p>Current serving state and the immutable execution history behind it.</p>
        </div>
        <span className="freshness">Live database view</span>
      </header>

      <section className="lifecycle" aria-label="LoanOps lifecycle">
        {['Ingestion', 'Normalization', 'Quality', 'Publication', 'Serving'].map((step, index) => (
          <div key={step} className="lifecycle-step">
            <span>{String(index + 1).padStart(2, '0')}</span>
            <strong>{step}</strong>
          </div>
        ))}
      </section>

      <section className="metric-grid">
        <MetricCard label="Serving products" value={overview.servingLoanProductCount} detail={`${overview.servingProductTermCount} current terms`} />
        <MetricCard label="Seoul observations" value={overview.servingSeoulObservationCount} detail="Policy-linked execution statistics" />
        <MetricCard label="Quality issues" value={overview.totalQualityIssueCount} detail={`${overview.currentQuarantineCount} quarantined records`} />
        <MetricCard label="Recent changes" value={overview.recentDataChangeCount} detail="Recorded in the last 7 days" />
      </section>

      <section className="panel-grid two-columns">
        <article className="panel">
          <div className="panel-heading">
            <div><p className="eyebrow">Sources</p><h2>Latest lifecycle state</h2></div>
            <span className="count-label">{overview.sourceCount} configured</span>
          </div>
          <div className="source-cards">
            {overview.sources.map((source) => (
              <div className="source-card" key={source.sourceCode}>
                <div className="source-title">
                  <div className="source-mark">{source.sourceCode === 'FSS_FINLIFE' ? 'FS' : 'SO'}</div>
                  <div><strong>{source.sourceCode}</strong><small>Latest run #{source.latestSourceRunId ?? '—'}</small></div>
                </div>
                <div className="status-row"><span>Ingestion</span><StatusBadge value={source.ingestionStatus} /></div>
                <div className="status-row"><span>Publication</span><StatusBadge value={source.publicationStatus} /></div>
                <div className="source-meta">
                  <span>Policy {source.qualityPolicyVersion ?? '—'}</span>
                  <span>{formatTime(source.publishedAt)}</span>
                </div>
              </div>
            ))}
          </div>
        </article>

        <article className="panel">
          <div className="panel-heading"><div><p className="eyebrow">Publication</p><h2>Recent decisions</h2></div></div>
          <div className="compact-list">
            {publications.map((publication) => (
              <div className="compact-row" key={publication.id}>
                <div><strong>Run #{publication.sourceRunId}</strong><small>{publication.sourceCode}</small></div>
                <StatusBadge value={publication.status} />
                <span className="mono">{publication.qualityPolicyVersion}</span>
              </div>
            ))}
          </div>
        </article>
      </section>

      <section className="panel">
        <div className="panel-heading"><div><p className="eyebrow">Traceability</p><h2>Recent data changes</h2></div></div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Run</th><th>Record type</th><th>Change</th><th>Current record</th><th>Changed fields</th><th>Recorded</th></tr></thead>
            <tbody>
              {changes.map((change) => (
                <tr key={change.id}>
                  <td className="mono">#{change.sourceRunId}</td><td>{change.recordType.replaceAll('_', ' ')}</td>
                  <td><StatusBadge value={change.changeType} /></td><td className="mono">#{change.currentRecordId}</td>
                  <td>{change.changedFields ?? 'New record'}</td><td>{formatTime(change.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  )
}
