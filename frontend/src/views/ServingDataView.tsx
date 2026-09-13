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
      <header className="view-header"><div><p className="eyebrow">Published current state</p><h1>Serving data</h1><p>Only quality-accepted records reached through current serving pointers.</p></div></header>
      <div className="tabs" role="tablist"><button className={tab === 'products' ? 'active' : ''} onClick={() => switchTab('products')}>FSS loan products</button><button className={tab === 'seoul' ? 'active' : ''} onClick={() => switchTab('seoul')}>Seoul loan execution statistics</button></div>
      {error && <ErrorState error={error} />}
      {tab === 'products' && (
        <>
          <form className="filter-bar" onSubmit={submitSearch}><label>Institution<input value={institutionInput} onChange={(event) => setInstitutionInput(event.target.value)} placeholder="Search institution" /></label><label>Product name<input value={productInput} onChange={(event) => setProductInput(event.target.value)} placeholder="Search product" /></label><button className="primary-button" type="submit">Apply filters</button></form>
          {!products && !error ? <LoadingState /> : products && <section className="panel"><div className="panel-heading"><div><p className="eyebrow">FSS current view</p><h2>{products.totalElements} products</h2></div></div>{products.content.length === 0 ? <EmptyState /> : <div className="table-wrap"><table className="interactive-table"><thead><tr><th>Institution</th><th>Product</th><th>Product code</th><th>Disclosure</th><th>Terms</th><th>Run</th></tr></thead><tbody>{products.content.map((product) => <tr key={product.id} onClick={() => selectProduct(product.id)}><td><strong>{product.canonicalInstitutionName}</strong><small>{product.sourceInstitutionCode}</small></td><td>{product.productName}</td><td className="mono">{product.sourceProductId}</td><td>{product.disclosureMonth}</td><td>{product.termCount}</td><td className="mono">#{product.sourceRunId}</td></tr>)}</tbody></table></div>}<Pagination page={products.page} totalPages={products.totalPages} onChange={setPage} /></section>}
          {productDetail && <section className="detail-panel"><div className="panel-heading"><div><p className="eyebrow">Current product</p><h2>{productDetail.product.productName}</h2></div><span className="count-label">{productDetail.terms.length} terms</span></div><div className="detail-grid"><div><span>Institution</span><strong>{productDetail.product.canonicalInstitutionName}</strong></div><div><span>SourceRun</span><strong>#{productDetail.product.sourceRunId}</strong></div><div><span>Normalized record</span><strong>#{productDetail.product.currentNormalizedRecordId}</strong></div><div><span>RawSnapshot</span><strong>#{productDetail.product.rawSnapshotId}</strong></div></div><div className="term-grid">{productDetail.terms.map((term) => <article className="term-card" key={term.id}><div><strong>{term.lendingRateTypeName}</strong><small>{term.mortgageTypeName} · {term.repaymentTypeName}</small></div><div className="rate-row"><span><small>Minimum</small><strong>{term.minimumRate.toFixed(2)}%</strong></span><span><small>Average</small><strong>{term.averageRate == null ? '—' : `${term.averageRate.toFixed(2)}%`}</strong></span><span><small>Maximum</small><strong>{term.maximumRate.toFixed(2)}%</strong></span></div></article>)}</div></section>}
        </>
      )}
      {tab === 'seoul' && (!observations && !error ? <LoadingState /> : observations && <section className="panel"><div className="panel-heading"><div><p className="eyebrow">Seoul public API</p><h2>Policy-linked commercial-bank loan execution statistics</h2></div><span className="count-label">{observations.totalElements} institutions</span></div><p className="panel-note">These are policy-linked loan execution statistics, not generic market rates.</p>{observations.content.length === 0 ? <EmptyState /> : <div className="table-wrap"><table><thead><tr><th>Institution</th><th>Mapping</th><th>Reference period</th><th>Loans</th><th>Min rate</th><th>Avg rate</th><th>Max rate</th><th>Run</th></tr></thead><tbody>{observations.content.map((observation) => <tr key={observation.id}><td><strong>{observation.sourceInstitutionName}</strong><small>{observation.canonicalInstitutionName ?? 'No canonical mapping'}</small></td><td><StatusBadge value={observation.mappingStatus} /></td><td>{observation.referenceStartDate} — {observation.referenceEndDate}</td><td>{observation.loanCount.toLocaleString()}</td><td>{observation.minimumLoanRate.toFixed(2)}%</td><td>{observation.averageLoanRate.toFixed(2)}%</td><td>{observation.maximumLoanRate.toFixed(2)}%</td><td className="mono">#{observation.sourceRunId}</td></tr>)}</tbody></table></div>}<Pagination page={observations.page} totalPages={observations.totalPages} onChange={setPage} /></section>)}
    </div>
  )
}
