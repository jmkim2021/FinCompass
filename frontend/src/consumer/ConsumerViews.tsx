import { FormEvent, ReactNode, useEffect, useState } from 'react'
import { fetchJson, sendJson } from '../api'
import type { Overview } from '../types'
import { SourceBadge } from './SourceBadge'
import { formatKrw, humanize, metricText } from './presentation'
import type { AgentResponse, AgentTrace, FinancialSnapshot, MatchRun, Metric, Opportunity, Scenario, TargetSearchResult } from './types'

function useResource<T>(path: string) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { setData(null); setError(null); fetchJson<T>(path).then(setData).catch((reason: Error) => setError(reason.message)) }, [path])
  return { data, error }
}

function Frame({ eyebrow, title, description, children }: { eyebrow: string; title: string; description: string; children: ReactNode }) {
  return <div className="consumer-view"><header className="consumer-header"><p className="eyebrow">{eyebrow}</p><h1>{title}</h1><p>{description}</p></header>{children}</div>
}

function State({ error, empty }: { error?: string | null; empty?: string }) {
  return <div className={`consumer-state ${error ? 'consumer-error' : ''}`} role={error ? 'alert' : 'status'}>{error ?? empty ?? '데이터를 불러오는 중입니다.'}</div>
}

export function Warnings({ warnings, limitations = [] }: { warnings: string[]; limitations?: string[] }) {
  if (!warnings.length && !limitations.length) return null
  return <div className="warning-stack" aria-label="경고 및 한계">{warnings.map((warning) => <p key={warning}><strong>경고</strong>{warning}</p>)}{limitations.map((limitation) => <p key={limitation}><strong>한계</strong>{limitation}</p>)}</div>
}

export function MetricTile({ label, metric, source = 'DERIVED', onEvidence }: { label: string; metric?: Metric; source?: string; onEvidence?: () => void }) {
  return <article className="consumer-metric"><div><span>{label}</span><SourceBadge value={source} /></div><strong>{metricText(metric)}</strong><small>{metric?.availability === 'AVAILABLE' ? metric.unit : metric?.availability ?? 'INSUFFICIENT DATA'}</small>{onEvidence && <button className="text-button" onClick={onEvidence}>근거 보기</button>}</article>
}

export function ConnectionsView() {
  const snapshot = useResource<FinancialSnapshot>('/api/v1/financial-snapshots/latest')
  const overview = useResource<Overview>('/api/v1/overview')
  if (snapshot.error || overview.error) return <Frame eyebrow="Sources" title="데이터 연결" description="연결 상태를 사실 그대로 표시합니다."><State error={snapshot.error ?? overview.error} /></Frame>
  if (!snapshot.data || !overview.data) return <Frame eyebrow="Sources" title="데이터 연결" description="연결 상태를 사실 그대로 표시합니다."><State /></Frame>
  const refs = snapshot.data.input.sourceRefs
  const open = refs.find((ref) => ref.sourceType === 'OPEN_BANKING_TEST')
  const publicData = refs.find((ref) => ref.sourceType === 'PUBLIC_MYDATA_MOCK')
  return <Frame eyebrow="Sources" title="데이터 연결" description="개인 데이터와 LoanOps 상품 데이터의 출처·모드·최신성을 분리해 확인합니다.">
    <div className="connection-grid">
      <Connection title="Open Banking Test" badge="OPEN_BANKING_TEST" mode={open?.sourceMode ?? 'DEMO_REPLAY'} status={open ? '동기화 완료' : '연결 없음'} observed={open?.asOf} detail="테스트 API 또는 고정 replay 데이터" />
      <Connection title="Public MyData" badge="PUBLIC_MYDATA_MOCK" mode="PUBLIC_MYDATA_MOCK" status={publicData ? '소득·재직 근거 로드' : '근거 없음'} observed={publicData?.asOf} detail="실제 정부 연동이 아닌 명시적 mock" />
      <Connection title="LoanOps Product Demo Fixture" badge="DEMO_PRODUCT_FIXTURE" extraBadge="DEMO_REPLAY" mode="SERVING PROJECTION" status={`${overview.data.servingLoanProductCount}개 상품 제공 중`} observed={overview.data.sources.find((source) => source.publishedAt)?.publishedAt ?? undefined} detail={`합성 원산지 유지 · 동일 LoanOps pipeline · 품질 이슈 ${overview.data.currentQuarantineCount}건 격리`} />
      <Connection title="AI-Hub" badge="AIHUB_OFFLINE" mode="OFFLINE ONLY" status="사용자 연결 데이터 아님" detail="향후 연구용 오프라인 데이터 경계" />
    </div><Warnings warnings={[...snapshot.data.currentAssessment.warnings, ...snapshot.data.result.warnings]} limitations={snapshot.data.result.limitations} />
  </Frame>
}

function Connection({ title, badge, extraBadge, mode, status, observed, detail }: { title: string; badge: string; extraBadge?: string; mode: string; status: string; observed?: string; detail: string }) {
  return <article className="connection-card"><div className="connection-head"><div><h2>{title}</h2><p>{detail}</p></div><div className="badge-stack"><SourceBadge value={badge} />{extraBadge&&<SourceBadge value={extraBadge}/>}</div></div><dl><div><dt>모드</dt><dd>{mode}</dd></div><div><dt>상태</dt><dd>{status}</dd></div><div><dt>마지막 관측</dt><dd>{observed ? new Date(observed).toLocaleString('ko-KR') : '해당 없음'}</dd></div></dl></article>
}

export function FinancialDashboard({ navigate }: { navigate: (route: string) => void }) {
  const { data, error } = useResource<FinancialSnapshot>('/api/v1/financial-snapshots/latest')
  if (error || !data) return <Frame eyebrow="Financial Twin" title="재무 대시보드" description="검증된 현재 상태를 확인합니다."><State error={error} /></Frame>
  const metrics = data.result.metrics
  const evidence = () => navigate('evidence')
  return <Frame eyebrow="Financial Twin" title="나의 재무 나침반" description={`기준시각 ${new Date(data.asOf).toLocaleString('ko-KR')} · 계산 규칙 ${data.calculationRuleVersion}`}>
    {data.currentAssessment.freshness !== 'FRESH' && <div className="stale-banner" role="alert">최신 데이터가 아닐 수 있습니다. 근거 시각과 경고를 확인하세요.</div>}
    <section className="consumer-metric-grid primary-metrics"><MetricTile label="순자산" metric={metrics.NET_WORTH} onEvidence={evidence} /><MetricTile label="월 여유자금" metric={metrics.MONTHLY_FREE_CASHFLOW} onEvidence={evidence} /><MetricTile label="총부채" metric={metrics.TOTAL_LIABILITIES} onEvidence={evidence} /><MetricTile label="비상자금" metric={metrics.EMERGENCY_FUND_MONTHS} onEvidence={evidence} /></section>
    <section className="consumer-panel"><div className="section-title"><div><p className="eyebrow">Cashflow semantics</p><h2>현금흐름 구성</h2></div><SourceBadge value="DERIVED" /></div><div className="consumer-metric-grid secondary-metrics"><MetricTile label="관측 거래 월유출" metric={metrics.OBSERVED_MONTHLY_OUTFLOW} source="OPEN_BANKING_TEST" onEvidence={evidence} /><MetricTile label="모델 고정의무" metric={metrics.MODELED_FIXED_OBLIGATIONS} source="USER_INPUT" onEvidence={evidence} /><MetricTile label="모델 총 월유출" metric={metrics.MODELED_MONTHLY_OUTFLOW} onEvidence={evidence} /><MetricTile label="비상자금 분모: 월 필수지출" metric={metrics.MONTHLY_ESSENTIAL_EXPENSE} source="USER_INPUT" onEvidence={evidence} /></div><p className="semantic-note">관측 거래 월유출은 생활비와 거래에서 관측된 부채상환을 포함합니다. 모델 총 월유출은 생활비 + 예정 부채상환 + 별도 고정의무이며, 비상자금은 명시한 월 필수지출로 나눕니다.</p></section>
    <section className="consumer-panel"><div className="section-title"><div><p className="eyebrow">Ratios & income</p><h2>상세 지표</h2></div><SourceBadge value="DERIVED" /></div><div className="consumer-metric-grid secondary-metrics"><MetricTile label="검증 연소득" metric={{ value: data.result.verifiedAnnualIncome, availability: data.result.verifiedAnnualIncome === null ? 'INSUFFICIENT_DATA' : 'AVAILABLE', unit: 'KRW', warnings: [] }} source="PUBLIC_MYDATA_MOCK" onEvidence={evidence} /><MetricTile label="관측 월유입" metric={metrics.OBSERVED_MONTHLY_INFLOW} source="OPEN_BANKING_TEST" onEvidence={evidence} /><MetricTile label="분석용 부채상환 부담" metric={metrics.DEBT_SERVICE_BURDEN} onEvidence={evidence} /><MetricTile label="부채/자산" metric={metrics.DEBT_TO_ASSET_RATIO} onEvidence={evidence} /><MetricTile label="유동자산/자산" metric={metrics.LIQUID_ASSET_RATIO} onEvidence={evidence} /></div></section>
    <Warnings warnings={[...data.currentAssessment.warnings, ...data.result.warnings]} limitations={data.result.limitations} />
  </Frame>
}

const prompts = ['7억 아파트를 사도 내 생활이 유지될까?', '월 여유자금 50만원을 유지하려면 집값은 얼마까지?', '지금 내 대출부담은 어느 정도야?', '금리가 2%p 오르면 어떻게 돼?', '기존 대출을 먼저 갚으면 어떻게 달라져?', '현재 내 대출금리는 비교 상품보다 높은 편이야?']
const inputLabels: Record<string,string> = { housePrice:'주택가격 (원)',cashContribution:'현금기여액 (원)',mortgageAmount:'주담대금액 (원)',annualInterestRate:'연이율 (소수)',termYears:'상환기간 (년)',targetMonthlyFreeCashflow:'목표 최소 월 여유자금 (원)',searchMinHousePrice:'검색 최소 주택가격 (원)',searchMaxHousePrice:'검색 최대 주택가격 (원)',searchPrecision:'검색 정밀도 (원)' }

export function AgentView({ onRun, navigate }: { onRun: (response: AgentResponse) => void; navigate: (route: string) => void }) {
  const [question, setQuestion] = useState(prompts[0]);const [answer, setAnswer] = useState<AgentResponse | null>(null);const [error, setError] = useState<string | null>(null);const [busy, setBusy] = useState(false)
  const [inputs, setInputs] = useState({ housePrice: '700000000', cashContribution: '110000000', mortgageAmount: '590000000', annualInterestRate: '0.045', termYears: '30', targetMonthlyFreeCashflow:'500000', searchMinHousePrice:'110000000', searchMaxHousePrice:'700000000', searchPrecision:'100000' })
  const submit = (event: FormEvent) => { event.preventDefault();setBusy(true);setError(null);sendJson<AgentResponse>('/api/v1/agent/chat',{ question, ...Object.fromEntries(Object.entries(inputs).map(([key,value]) => [key,value === '' ? null : Number(value)])) }).then((result) => {setAnswer(result);onRun(result)}).catch((reason: Error) => setError(reason.message)).finally(() => setBusy(false)) }
  return <Frame eyebrow="Grounded guidance" title="FinCompass Agent에게 묻기" description="에이전트는 계산하지 않습니다. 검증된 데이터와 결정론적 도구 결과를 설명합니다.">
    <div className="prompt-chips" aria-label="추천 질문">{prompts.map((prompt) => <button key={prompt} onClick={() => setQuestion(prompt)}>{prompt}</button>)}</div>
    <form className="agent-composer" onSubmit={submit}><label><span>질문</span><textarea value={question} onChange={(event) => setQuestion(event.target.value)} rows={3} /></label><fieldset><legend>명시적 시뮬레이션 입력</legend>{Object.entries(inputs).map(([key,value]) => <label key={key}><span>{inputLabels[key]}</span><input aria-label={inputLabels[key]} inputMode="decimal" value={value} onChange={(event) => setInputs((current) => ({ ...current, [key]: event.target.value }))} /></label>)}</fieldset><button className="consumer-primary" disabled={busy}>{busy ? '도구 실행 중…' : '근거 기반 분석 실행'}</button></form>
    {error && <State error={error} />}{answer && <article className="agent-answer"><div className="answer-label"><SourceBadge value="DERIVED" /><span>{answer.status}</span></div><p>{answer.answer}</p>{answer.assumptions.length > 0 && <div><strong>가정</strong><ul>{answer.assumptions.map((item) => <li key={item}>{item}</li>)}</ul></div>}<Warnings warnings={answer.warnings} limitations={answer.limitations} /><div className="answer-actions"><button onClick={() => navigate('scenario')}>시나리오 보기</button><button onClick={() => navigate('evidence')}>실행 추적 보기</button></div></article>}
  </Frame>
}

export function ScenarioView() {
  const { data, error } = useResource<Scenario[]>('/api/v1/scenarios');const scenario=data?.find((item) => item.kind === 'HOME' && item.homeResult)
  const [target,setTarget]=useState<TargetSearchResult|null>(null);const [targetError,setTargetError]=useState<string|null>(null);const [searching,setSearching]=useState(false)
  const searchTarget=()=>{if(!scenario)return;setSearching(true);setTargetError(null);sendJson<TargetSearchResult>('/api/v1/scenarios/home-purchase/target-search',{financialSnapshotId:scenario.financialSnapshotId,annualInterestRate:0.045,termMonths:360,cashContribution:110000000,repaymentType:'AMORTIZING',minHousePrice:110000000,maxHousePrice:700000000,searchPrecision:100000,minMonthlyFreeCashflow:500000,minEmergencyFundMonths:null,maxDebtServiceBurden:null}).then(setTarget).catch((reason:Error)=>setTargetError(reason.message)).finally(()=>setSearching(false))}
  if (error || !data) return <Frame eyebrow="Before / After" title="시나리오 결과" description="구매 전후를 같은 지표로 비교합니다."><State error={error} /></Frame>
  if (!scenario?.homeResult) return <Frame eyebrow="Before / After" title="시나리오 결과" description="구매 전후를 같은 지표로 비교합니다."><State empty="아직 주택 구매 시나리오가 없습니다. Agent 화면에서 명시적 입력으로 분석을 실행하세요." /></Frame>
  const keys=[['LIQUID_ASSETS','유동자산'],['TOTAL_LIABILITIES','총부채'],['MONTHLY_FREE_CASHFLOW','월 여유자금'],['EMERGENCY_FUND_MONTHS','비상자금'],['DEBT_SERVICE_BURDEN','부채부담']] as const
  return <Frame eyebrow="Before / After" title="시나리오 결과" description={`Snapshot ${scenario.financialSnapshotId.slice(0,8)} · ${scenario.simulationRuleVersion}`}><div className="comparison-table" role="table"><div className="comparison-row heading"><span>지표</span><strong>구매 전</strong><strong>구매 후</strong></div>{keys.map(([key,label]) => <div className="comparison-row" key={key}><span>{label}</span><strong>{metricText(scenario.homeResult!.before[key])}</strong><strong>{metricText(scenario.homeResult!.after[key])}</strong></div>)}</div><section className="consumer-panel target-search"><div className="section-title"><div><p className="eyebrow">조건을 맞추려면?</p><h2>Target Scenario · Simulation Boundary</h2></div><code>TARGET_HOME_PRICE_SEARCH_V1</code></div><p>명시적 데모 제약: 구매 후 월 여유자금 500,000원 이상 · 현금기여 110,000,000원 · 연 4.5% · 30년 · 검색 정밀도 100,000원.</p><button className="consumer-primary" onClick={searchTarget} disabled={searching}>{searching?'경계 검색 중…':'시뮬레이션 경계 계산'}</button>{targetError&&<State error={targetError}/>} {target&&<div className="target-result"><strong>{target.feasible?formatKrw(target.maximumTestedFeasibleHousePrice):'범위 내 충족 지점 없음'}</strong><span>테스트된 최대 주택가격 경계</span>{target.feasible&&<dl><div><dt>필요 주담대</dt><dd>{formatKrw(target.requiredMortgage)}</dd></div><div><dt>월 상환액</dt><dd>{formatKrw(target.monthlyPayment)}</dd></div><div><dt>구매 후 월 여유자금</dt><dd>{formatKrw(target.postPurchaseMonthlyFreeCashflow)}</dd></div><div><dt>결정 제약</dt><dd>{humanize(target.bindingConstraint)}</dd></div></dl>}<Warnings warnings={target.warnings}/><small>공식 구매가능가격, 대출 승인 또는 금융 자문이 아닌 결정론적 시나리오 검색 결과입니다.</small></div>}</section><Warnings warnings={scenario.warnings} limitations={scenario.homeResult.limitations} /></Frame>
}

export function CandidatesView() {
  const { data, error }=useResource<MatchRun[]>('/api/v1/loan-product-matches');const run=data?.[0]
  if(error||!data)return <Frame eyebrow="Trusted products" title="대출 검토 후보" description="LoanOps serving 데이터와 재현 가능한 필터·점수를 보여줍니다."><State error={error}/></Frame>
  if(!run||!run.candidates.length)return <Frame eyebrow="Trusted products" title="대출 검토 후보" description="LoanOps serving 데이터와 재현 가능한 필터·점수를 보여줍니다."><State empty="현재 검토할 제공 상품이 없습니다. 빈 결과를 승인이나 추천으로 해석하지 않습니다."/></Frame>
  const complete=[...run.candidates].filter(candidate=>candidate.rank!==null).sort((left,right)=>left.rank!-right.rank!)
  const incomplete=run.candidates.filter(candidate=>candidate.rank===null)
  const cards=(candidates:typeof run.candidates)=><div className="candidate-grid">{candidates.map((candidate,index)=><article className="candidate-card" key={`${candidate.product.productName}-${index}`}><div className="candidate-title"><div><p>{candidate.rank===null?'Unranked · data unavailable':`Review Candidate ${candidate.rank}`}</p><h2>{candidate.product.institution} · {candidate.product.productName}</h2></div><span className={`candidate-status status-${candidate.status.toLowerCase()}`}>{humanize(candidate.status)}</span></div><div className="candidate-numbers"><div><span>Match score</span><strong>{candidate.score?.total ?? '산정 불가'}</strong></div><div><span>관측 금리 범위</span><strong>{candidate.product.minRatePercent}%–{candidate.product.maxRatePercent}%</strong></div><div><span>월 상환액</span><strong>{formatKrw(candidate.loanResult?.regularMonthlyPayment)}</strong></div><div><span>총이자</span><strong>{formatKrw(candidate.loanResult?.totalInterest)}</strong></div></div>{candidate.score&&<div className="score-breakdown" aria-label="점수 구성">{candidate.score.components.map(component=><span key={component.name}>{humanize(component.name)} {component.weightedScore}</span>)}</div>}<div className="badge-stack"><SourceBadge value={candidate.product.sourceType}/><SourceBadge value={candidate.product.sourceMode}/></div><Warnings warnings={candidate.warnings} limitations={candidate.limitations}/></article>)}</div>
  return <Frame eyebrow="Trusted products" title="대출 검토 후보" description={`Match run ${run.matchRunId.slice(0,8)} · ${run.scoreRuleVersion}`}><section className="candidate-demo"><div className="section-title"><div><p className="eyebrow">Demo B · complete synthetic data</p><h2>전체 매칭 파이프라인</h2></div><SourceBadge value="DEMO_PRODUCT_FIXTURE"/></div>{cards(complete)}</section><section className="candidate-demo"><div className="section-title"><div><p className="eyebrow">Demo A · incomplete source structure</p><h2>데이터 부족을 숨기지 않는 매칭</h2></div><span>score/rank 없음</span></div>{cards(incomplete)}</section></Frame>
}

export function StressView() {
  const { data,error }=useResource<Scenario[]>('/api/v1/scenarios');const stress=data?.find((item)=>item.kind==='STRESS')
  if(error||!data)return <Frame eyebrow="Stress grid" title="스트레스 테스트" description="금리·소득·지출 충격을 결정론적으로 비교합니다."><State error={error}/></Frame>
  if(!stress)return <Frame eyebrow="Stress grid" title="스트레스 테스트" description="금리·소득·지출 충격을 결정론적으로 비교합니다."><State empty="아직 스트레스 결과가 없습니다. 주택 구매 분석을 먼저 실행하세요."/></Frame>
  return <Frame eyebrow="Stress grid" title="스트레스 테스트" description="각 행은 저장된 계산 결과이며 UI가 금융 수치를 다시 계산하지 않습니다."><div className="stress-list">{stress.stressResults.map((item)=><div className="stress-row" key={item.shock.label}><strong>{item.shock.label}</strong><span>월 여유자금 {metricText(item.result.after.MONTHLY_FREE_CASHFLOW)}</span><span>부채부담 {metricText(item.result.after.DEBT_SERVICE_BURDEN)}</span></div>)}</div><Warnings warnings={stress.warnings}/></Frame>
}

export function AlternativesView() {
  const { data,error }=useResource<Scenario[]>('/api/v1/scenarios');const homes=data?.filter((item)=>item.kind==='HOME'&&item.homeResult).slice(0,4)??[]
  if(error||!data)return <Frame eyebrow="Explicit assumptions" title="대안 시나리오" description="동일한 지표 스키마로 저장된 대안을 비교합니다."><State error={error}/></Frame>
  if(!homes.length)return <Frame eyebrow="Explicit assumptions" title="대안 시나리오" description="동일한 지표 스키마로 저장된 대안을 비교합니다."><State empty="저장된 대안 시나리오가 없습니다."/></Frame>
  return <Frame eyebrow="Explicit assumptions" title="대안 시나리오" description="Buy now · Lower price · Repay first · Delay purchase 값은 각 시나리오의 명시적 입력에서만 옵니다."><div className="alternative-grid">{homes.map((scenario,index)=><article key={scenario.scenarioId}><span>저장 시나리오 {String.fromCharCode(65+index)}</span><strong>{metricText(scenario.homeResult!.after.MONTHLY_FREE_CASHFLOW)}</strong><small>구매 후 월 여유자금</small><dl><div><dt>총부채</dt><dd>{metricText(scenario.homeResult!.after.TOTAL_LIABILITIES)}</dd></div><div><dt>비상자금</dt><dd>{metricText(scenario.homeResult!.after.EMERGENCY_FUND_MONTHS)}</dd></div></dl></article>)}</div></Frame>
}

export function OpportunitiesView() {
  const { data,error }=useResource<Opportunity[]>('/api/v1/opportunities')
  if(error||!data)return <Frame eyebrow="Review events" title="기회와 위험 신호" description="정책 규칙과 근거를 검토합니다."><State error={error}/></Frame>
  if(!data.length)return <Frame eyebrow="Review events" title="기회와 위험 신호" description="정책 규칙과 근거를 검토합니다."><State empty="현재 활성 검토 이벤트가 없습니다."/></Frame>
  return <Frame eyebrow="Review events" title="기회와 위험 신호" description="행동 지시가 아닌 검토 신호입니다."><div className="opportunity-list">{data.map((event)=><article key={event.opportunityEventId}><div><span>{humanize(event.type)}</span><small>{new Date(event.detectedAt).toLocaleString('ko-KR')}</small></div><p>{humanize(event.evidence.metric)}: {event.evidence.actual ?? '확인 불가'} · 기준 {event.evidence.threshold ?? '해당 없음'}</p><footer><code>{event.ruleVersion}</code><button onClick={()=>{window.location.hash='#/fincompass/evidence'}}>근거 검사</button></footer></article>)}</div></Frame>
}

export function EvidenceView({ runId }: { runId: string | null }) {
  const snapshot=useResource<FinancialSnapshot>('/api/v1/financial-snapshots/latest');const trace=useResource<AgentTrace>(runId?`/api/v1/agent/runs/${runId}/trace`:'/api/v1/financial-snapshots/latest')
  const agentTrace=runId?trace.data:null
  if(snapshot.error||trace.error)return <Frame eyebrow="Auditability" title="근거와 계산 추적" description="출처 ID와 규칙 버전을 확인합니다."><State error={snapshot.error??trace.error}/></Frame>
  if(!snapshot.data||!trace.data)return <Frame eyebrow="Auditability" title="근거와 계산 추적" description="출처 ID와 규칙 버전을 확인합니다."><State/></Frame>
  return <Frame eyebrow="Auditability" title="근거와 계산 추적" description="민감한 원문 대신 참조와 해시, 사용된 규칙을 노출합니다."><section className="consumer-panel"><div className="section-title"><div><p className="eyebrow">Financial snapshot</p><h2>{snapshot.data.financialSnapshotId}</h2></div><code>{snapshot.data.calculationRuleVersion}</code></div><div className="evidence-list">{snapshot.data.input.sourceRefs.map((ref)=><div key={`${ref.recordId}-${ref.purpose}`}><SourceBadge value={ref.sourceType}/><div><strong>{ref.purpose}</strong><small>{ref.recordId} · {new Date(ref.asOf).toLocaleString('ko-KR')}</small></div><code>{ref.contentHash.slice(0,16)}…</code></div>)}</div></section>{agentTrace&&<section className="consumer-panel"><div className="section-title"><div><p className="eyebrow">Agent trace</p><h2>{agentTrace.modelProvider} · {agentTrace.modelVersion}</h2></div><span>{agentTrace.status}</span></div><div className="tool-timeline">{agentTrace.toolCalls.map((call)=><div key={call.ordinal}><span>{call.ordinal+1}</span><strong>{call.toolName}</strong><small>{call.status}</small><code>{call.resultHash?.slice(0,12)??'no result'}…</code></div>)}</div><Warnings warnings={agentTrace.warnings} limitations={agentTrace.limitations}/></section>}
  </Frame>
}
