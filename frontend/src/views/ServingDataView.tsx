import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fetchJson, queryString } from '../api'
import { Pagination } from '../components/Pagination'
import { StatusBadge } from '../components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '../components/UiState'
import type { PageResponse, ServingLoanProduct, ServingLoanProductDetail, ServingLoanRateObservation } from '../types'

type Tab = 'products' | 'seoul'

export function ServingDataView() {
  const [tab, setTab] = useState<Tab>('products')
  const [products, setProducts] = useState<PageResponse<ServingLoanProduct> | null>(null)
  const [observations, setObservations] = useState<PageResponse<ServingLoanRateObservation> | null>(null)
  const [productDetail, setProductDetail] = useState<ServingLoanProductDetail | null>(null)
  const [institutionInput, setInstitutionInput] = useState('')
  const [productInput, setProductInput] = useState('')
  const [filters, setFilters] = useState({ institution: '', productName: '' })
  const [page, setPage] = useState(0)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (tab === 'products') {
      fetchJson<PageResponse<ServingLoanProduct>>(`/api/v1/serving/loan-products${queryString({ ...filters, page, size: 20 })}`)
        .then(setProducts).catch((reason: Error) => setError(reason.message))
    } else {
      fetchJson<PageResponse<ServingLoanRateObservation>>(`/api/v1/serving/loan-rate-observations${queryString({ page, size: 20 })}`)
        .then(setObservations).catch((reason: Error) => setError(reason.message))
    }
  }, [tab, filters, page])

  const submitSearch = (event: FormEvent) => {
    event.preventDefault()
    setFilters({ institution: institutionInput, productName: productInput })
    setPage(0)
  }

  const selectProduct = (id: number) => {
    fetchJson<ServingLoanProductDetail>(`/api/v1/serving/loan-products/${id}`)
      .then(setProductDetail).catch((reason: Error) => setError(reason.message))
  }

  const switchTab = (next: Tab) => {
    setTab(next)
    setPage(0)
    setProductDetail(null)
    setError(null)
  }

  return (
    <div className="view-stack">
      <header className="view-header"><div><p className="eyebrow">게시된 현재 상태</p><h1>서비스 데이터</h1><p>품질 검증을 통과한 레코드만 현재 서비스 포인터를 통해 제공됩니다.</p></div></header>
      <div className="tabs" role="tablist"><button className={tab === 'products' ? 'active' : ''} onClick={() => switchTab('products')}>FSS 대출 상품</button><button className={tab === 'seoul' ? 'active' : ''} onClick={() => switchTab('seoul')}>서울시 대출실행 통계</button></div>
      {error && <ErrorState error={error} />}
      {tab === 'products' && (
        <>
          <form className="filter-bar" onSubmit={submitSearch}><label>금융기관<input value={institutionInput} onChange={(event) => setInstitutionInput(event.target.value)} placeholder="금융기관 검색" /></label><label>상품명<input value={productInput} onChange={(event) => setProductInput(event.target.value)} placeholder="상품명 검색" /></label><button className="primary-button" type="submit">필터 적용</button></form>
          {!products && !error ? <LoadingState /> : products && <section className="panel"><div className="panel-heading"><div><p className="eyebrow">FSS 현재 조회</p><h2>{products.totalElements}개 상품</h2></div></div>{products.content.length === 0 ? <EmptyState /> : <div className="table-wrap"><table className="interactive-table"><thead><tr><th>금융기관</th><th>상품</th><th>상품 코드</th><th>공시월</th><th>약정</th><th>실행</th></tr></thead><tbody>{products.content.map((product) => <tr key={product.id} onClick={() => selectProduct(product.id)}><td><strong>{product.canonicalInstitutionName}</strong><small>{product.sourceInstitutionCode}</small></td><td>{product.productName}</td><td className="mono">{product.sourceProductId}</td><td>{product.disclosureMonth}</td><td>{product.termCount}</td><td className="mono">#{product.sourceRunId}</td></tr>)}</tbody></table></div>}<Pagination page={products.page} totalPages={products.totalPages} onChange={setPage} /></section>}
          {productDetail && <section className="detail-panel"><div className="panel-heading"><div><p className="eyebrow">현재 상품</p><h2>{productDetail.product.productName}</h2></div><span className="count-label">{productDetail.terms.length}건 약정</span></div><div className="detail-grid"><div><span>금융기관</span><strong>{productDetail.product.canonicalInstitutionName}</strong></div><div><span>SourceRun</span><strong>#{productDetail.product.sourceRunId}</strong></div><div><span>정규화 레코드</span><strong>#{productDetail.product.currentNormalizedRecordId}</strong></div><div><span>RawSnapshot</span><strong>#{productDetail.product.rawSnapshotId}</strong></div></div><div className="term-grid">{productDetail.terms.map((term) => <article className="term-card" key={term.id}><div><strong>{term.lendingRateTypeName}</strong><small>{term.mortgageTypeName} · {term.repaymentTypeName}</small></div><div className="rate-row"><span><small>최저</small><strong>{term.minimumRate.toFixed(2)}%</strong></span><span><small>평균</small><strong>{term.averageRate == null ? '—' : `${term.averageRate.toFixed(2)}%`}</strong></span><span><small>최고</small><strong>{term.maximumRate.toFixed(2)}%</strong></span></div></article>)}</div></section>}
        </>
      )}
      {tab === 'seoul' && (!observations && !error ? <LoadingState /> : observations && <section className="panel"><div className="panel-heading"><div><p className="eyebrow">서울시 공공 API</p><h2>서울시 정책연계 시중은행 대출실행 통계</h2></div><span className="count-label">{observations.totalElements}개 금융기관</span></div><p className="panel-note">일반 시장금리가 아닌, 정책연계 대출실행 통계입니다.</p>{observations.content.length === 0 ? <EmptyState /> : <div className="table-wrap"><table><thead><tr><th>금융기관</th><th>매핑</th><th>기준 기간</th><th>대출 건수</th><th>최저 금리</th><th>평균 금리</th><th>최고 금리</th><th>실행</th></tr></thead><tbody>{observations.content.map((observation) => <tr key={observation.id}><td><strong>{observation.sourceInstitutionName}</strong><small>{observation.canonicalInstitutionName ?? '정규 매핑 없음'}</small></td><td><StatusBadge value={observation.mappingStatus} /></td><td>{observation.referenceStartDate} — {observation.referenceEndDate}</td><td>{observation.loanCount.toLocaleString('ko-KR')}</td><td>{observation.minimumLoanRate.toFixed(2)}%</td><td>{observation.averageLoanRate.toFixed(2)}%</td><td>{observation.maximumLoanRate.toFixed(2)}%</td><td className="mono">#{observation.sourceRunId}</td></tr>)}</tbody></table></div>}<Pagination page={observations.page} totalPages={observations.totalPages} onChange={setPage} /></section>)}
    </div>
  )
}
