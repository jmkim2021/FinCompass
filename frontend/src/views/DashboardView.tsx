import { useEffect, useState } from 'react'
import { fetchJson } from '../api'
import { MetricCard } from '../components/MetricCard'
import { StatusBadge } from '../components/StatusBadge'
import { ErrorState, LoadingState } from '../components/UiState'
import { formatTime } from '../displayText'
import type { DataChange, Overview, PageResponse, Publication } from '../types'

const LIFECYCLE_STEPS = ['수집', '정규화', '품질 검증', '게시', '서비스'] as const

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
          <p className="eyebrow">운영 개요</p>
          <h1>한눈에 보는 데이터 신뢰성</h1>
          <p>현재 서비스 상태와 이를 뒷받침하는 불변 실행 이력입니다.</p>
        </div>
        <span className="freshness">실시간 DB 조회</span>
      </header>

      <section className="lifecycle" aria-label="LoanOps 처리 단계">
        {LIFECYCLE_STEPS.map((step, index) => (
          <div key={step} className="lifecycle-step">
            <span>{String(index + 1).padStart(2, '0')}</span>
            <strong>{step}</strong>
          </div>
        ))}
      </section>

      <section className="metric-grid">
        <MetricCard label="서비스 상품" value={overview.servingLoanProductCount} detail={`현재 약정 ${overview.servingProductTermCount}건`} />
        <MetricCard label="서울시 대출실행 통계" value={overview.servingSeoulObservationCount} detail="정책연계 대출실행 통계" />
        <MetricCard label="품질 이슈" value={overview.totalQualityIssueCount} detail={`격리 레코드 ${overview.currentQuarantineCount}건`} />
        <MetricCard label="최근 변경" value={overview.recentDataChangeCount} detail="최근 7일 기록" />
      </section>

      <section className="panel-grid two-columns">
        <article className="panel">
          <div className="panel-heading">
            <div><p className="eyebrow">소스</p><h2>소스별 최신 처리 상태</h2></div>
            <span className="count-label">{overview.sourceCount}개 설정됨</span>
          </div>
          <div className="source-cards">
            {overview.sources.map((source) => (
              <div className="source-card" key={source.sourceCode}>
                <div className="source-title">
                  <div className="source-mark">{source.sourceCode === 'FSS_FINLIFE' ? 'FS' : 'SO'}</div>
                  <div><strong>{source.sourceCode}</strong><small>최신 실행 #{source.latestSourceRunId ?? '—'}</small></div>
                </div>
                <div className="status-row"><span>수집</span><StatusBadge value={source.ingestionStatus} /></div>
                <div className="status-row"><span>게시</span><StatusBadge value={source.publicationStatus} /></div>
                <div className="source-meta">
                  <span>정책 {source.qualityPolicyVersion ?? '—'}</span>
                  <span>{formatTime(source.publishedAt)}</span>
                </div>
              </div>
            ))}
          </div>
        </article>

        <article className="panel">
          <div className="panel-heading"><div><p className="eyebrow">게시</p><h2>최근 게시 결과</h2></div></div>
          <div className="compact-list">
            {publications.map((publication) => (
              <div className="compact-row" key={publication.id}>
                <div><strong>실행 #{publication.sourceRunId}</strong><small>{publication.sourceCode}</small></div>
                <StatusBadge value={publication.status} />
                <span className="mono">{publication.qualityPolicyVersion}</span>
              </div>
            ))}
          </div>
        </article>
      </section>

      <section className="panel">
        <div className="panel-heading"><div><p className="eyebrow">추적성</p><h2>최근 데이터 변경</h2></div></div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>실행</th><th>레코드 유형</th><th>변경</th><th>현재 레코드</th><th>변경 필드</th><th>기록 시각</th></tr></thead>
            <tbody>
              {changes.map((change) => (
                <tr key={change.id}>
                  <td className="mono">#{change.sourceRunId}</td><td>{change.recordType.replaceAll('_', ' ')}</td>
                  <td><StatusBadge value={change.changeType} /></td><td className="mono">#{change.currentRecordId}</td>
                  <td>{change.changedFields ?? '신규 레코드'}</td><td>{formatTime(change.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  )
}
