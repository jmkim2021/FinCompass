import { useEffect, useState } from 'react'
import { fetchJson, queryString } from '../api'
import { Pagination } from '../components/Pagination'
import { StatusBadge } from '../components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '../components/UiState'
import type { PageResponse, Publication, PublicationDetail, QualityIssue } from '../types'

export function DataQualityView() {
  const [publications, setPublications] = useState<PageResponse<Publication> | null>(null)
  const [issues, setIssues] = useState<PageResponse<QualityIssue> | null>(null)
  const [publicationDetail, setPublicationDetail] = useState<PublicationDetail | null>(null)
  const [runFilter, setRunFilter] = useState('')
  const [ruleFilter, setRuleFilter] = useState('')
  const [issuePage, setIssuePage] = useState(0)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchJson<PageResponse<Publication>>('/api/v1/publications?size=20').then(setPublications).catch((reason: Error) => setError(reason.message))
  }, [])

  useEffect(() => {
    fetchJson<PageResponse<QualityIssue>>(`/api/v1/quality-issues${queryString({ sourceRunId: runFilter, ruleCode: ruleFilter, page: issuePage, size: 15 })}`)
      .then(setIssues).catch((reason: Error) => setError(reason.message))
  }, [runFilter, ruleFilter, issuePage])

  const selectPublication = (id: number) => {
    fetchJson<PublicationDetail>(`/api/v1/publications/${id}`).then(setPublicationDetail).catch((reason: Error) => setError(reason.message))
  }

  if (error) return <ErrorState error={error} />
  if (!publications || !issues) return <LoadingState />

  return (
    <div className="view-stack">
      <header className="view-header"><div><p className="eyebrow">품질 검증</p><h1>데이터 품질</h1><p>품질 규칙 위반, 격리 데이터, 서비스 반영 판정 결과를 확인합니다.</p></div></header>
      <section className="panel">
        <div className="panel-heading"><div><p className="eyebrow">서비스 반영 이력</p><h2>서비스 반영 결과</h2></div><span className="count-label">총 {publications.totalElements}건</span></div>
        <div className="table-wrap"><table className="interactive-table"><thead><tr><th>반영</th><th>수집</th><th>소스</th><th>결과</th><th>정책</th><th>이슈</th><th>격리</th><th>변경</th></tr></thead>
          <tbody>{publications.content.map((publication) => <tr key={publication.id} onClick={() => selectPublication(publication.id)}><td className="mono">#{publication.id}</td><td className="mono">#{publication.sourceRunId}</td><td>{publication.sourceCode}</td><td><StatusBadge value={publication.status} /></td><td className="mono">{publication.qualityPolicyVersion}</td><td>{publication.issueCount}</td><td>{publication.quarantineCount}</td><td>{publication.changeCount}</td></tr>)}</tbody>
        </table></div>
      </section>
      {publicationDetail && (
        <section className="detail-panel compact-detail">
          <div className="panel-heading"><div><p className="eyebrow">판정 근거</p><h2>Publication #{publicationDetail.publication.id}</h2></div><StatusBadge value={publicationDetail.publication.status} /></div>
          <div className="normalized-strip"><span><strong>{publicationDetail.qualityIssues.length}</strong> 이슈</span><span><strong>{publicationDetail.quarantineRecords.length}</strong> 격리</span><span><strong>{publicationDetail.dataChanges.length}</strong> 변경</span></div>
        </section>
      )}
      <section className="panel">
        <div className="panel-heading"><div><p className="eyebrow">품질 검증</p><h2>품질 이슈 목록</h2></div></div>
        <div className="filter-bar embedded">
          <label>SourceRun ID<input inputMode="numeric" value={runFilter} onChange={(event) => { setRunFilter(event.target.value); setIssuePage(0) }} placeholder="전체 수집" /></label>
          <label>규칙<select value={ruleFilter} onChange={(event) => { setRuleFilter(event.target.value); setIssuePage(0) }}><option value="">전체 규칙</option><option>REQUIRED_FIELD_MISSING</option><option>INVALID_DATE_RANGE</option><option>NEGATIVE_RATE</option><option>INVALID_RATE_ORDER</option><option>NEGATIVE_COUNT</option></select></label>
        </div>
        {issues.content.length === 0 ? <EmptyState>조건에 맞는 품질 검증 이슈가 없습니다.</EmptyState> : <div className="table-wrap"><table><thead><tr><th>이슈</th><th>수집</th><th>데이터</th><th>규칙</th><th>필드</th><th>관측값</th><th>메시지</th></tr></thead><tbody>{issues.content.map((issue) => <tr key={issue.id}><td className="mono">#{issue.id}</td><td className="mono">#{issue.sourceRunId}</td><td>{issue.recordType} #{issue.recordId}</td><td><StatusBadge value={issue.ruleCode} /></td><td>{issue.fieldName ?? '—'}</td><td className="mono clipped">{issue.observedValue ?? '—'}</td><td>{issue.message}</td></tr>)}</tbody></table></div>}
        <Pagination page={issues.page} totalPages={issues.totalPages} onChange={setIssuePage} />
      </section>
    </div>
  )
}
