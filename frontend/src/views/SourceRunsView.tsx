import { useEffect, useState } from 'react'
import { fetchJson, queryString } from '../api'
import { Pagination } from '../components/Pagination'
import { StatusBadge } from '../components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '../components/UiState'
import { formatTime } from '../displayText'
import type { PageResponse, SourceRun, SourceRunDetail } from '../types'

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
      <header className="view-header"><div><p className="eyebrow">수집 이력</p><h1>수집 이력</h1><p>수집 요청, 원본 응답, 스냅샷, 후속 처리 결과를 단계별로 확인합니다.</p></div></header>
      <section className="filter-bar">
        <label>소스<select value={source} onChange={(event) => { setSource(event.target.value); setPage(0) }}><option value="">전체 소스</option><option>FSS_FINLIFE</option><option>SEOUL_POLICY_LOAN_RATE</option></select></label>
        <label>상태<select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}><option value="">전체 상태</option><option>SUCCESS</option><option>FAILED</option><option>RUNNING</option></select></label>
      </section>
      {error && <ErrorState error={error} />}
      {!data && !error && <LoadingState />}
      {data && (
        <section className="panel">
          <div className="panel-heading"><div><p className="eyebrow">수집 이력</p><h2>수집 {data.totalElements}건</h2></div></div>
          {data.content.length === 0 ? <EmptyState /> : (
            <div className="table-wrap"><table className="interactive-table"><thead><tr><th>수집</th><th>소스</th><th>상태</th><th>HTTP</th><th>시작</th><th>종료</th><th>스냅샷</th></tr></thead>
              <tbody>{data.content.map((run) => <tr key={run.id} onClick={() => selectRun(run.id)} tabIndex={0} onKeyDown={(event) => event.key === 'Enter' && selectRun(run.id)}><td className="mono">#{run.id}</td><td>{run.sourceCode}</td><td><StatusBadge value={run.status} /></td><td className="mono">{run.httpStatus ?? '—'}</td><td>{formatTime(run.startedAt, { dateStyle: 'short', timeStyle: 'medium' })}</td><td>{formatTime(run.endedAt, { dateStyle: 'short', timeStyle: 'medium' })}</td><td>{run.rawSnapshotCount}</td></tr>)}</tbody>
            </table></div>
          )}
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </section>
      )}
      {detail && (
        <section className="detail-panel">
          <div className="panel-heading"><div><p className="eyebrow">수집 상세</p><h2>SourceRun #{detail.sourceRun.id}</h2></div><StatusBadge value={detail.sourceRun.status} /></div>
          <div className="detail-grid">
            <div><span>소스</span><strong>{detail.dataSource.name}</strong></div><div><span>HTTP 상태</span><strong>{detail.sourceRun.httpStatus ?? '—'}</strong></div>
            <div><span>오류 유형</span><strong>{detail.sourceRun.errorType ?? '—'}</strong></div><div><span>서비스 반영</span><StatusBadge value={detail.publication?.status ?? null} /></div>
            <div><span>품질 검증 이슈</span><strong>{detail.qualityIssueCount}</strong></div><div><span>격리</span><strong>{detail.quarantineCount}</strong></div>
          </div>
          {detail.sourceRun.errorMessage && <div className="notice negative-notice"><strong>오류 내역</strong><span>{detail.sourceRun.errorMessage}</span></div>}
          <div className="normalized-strip"><span><strong>{detail.normalizedRecordCounts.loanProducts}</strong> 상품</span><span><strong>{detail.normalizedRecordCounts.productTerms}</strong> 금리 조건</span><span><strong>{detail.normalizedRecordCounts.loanRateObservations}</strong> 관측</span></div>
          <h3>원본 스냅샷</h3>
          {detail.rawSnapshots.map((snapshot) => <div className="snapshot-row" key={snapshot.id}><span className="mono">#{snapshot.id}</span><span>{snapshot.contentType}</span><code title={snapshot.payloadHash}>{snapshot.payloadHash.slice(0, 16)}…</code><span>{snapshot.payloadSize.toLocaleString('ko-KR')} bytes</span><span>{formatTime(snapshot.fetchedAt, { dateStyle: 'short', timeStyle: 'medium' })}</span></div>)}
        </section>
      )}
    </div>
  )
}
