import { useEffect, useState } from 'react'
import type { AgentResponse } from './types'
import { AgentView, AlternativesView, CandidatesView, ConnectionsView, EvidenceView, FinancialDashboard, OpportunitiesView, ScenarioView, StressView } from './ConsumerViews'

export type ConsumerRoute = 'connections' | 'dashboard' | 'agent' | 'scenario' | 'candidates' | 'stress' | 'alternatives' | 'opportunities' | 'evidence'
const navigation: Array<{ id: ConsumerRoute; label: string; marker: string }> = [
  { id:'dashboard',label:'재무 대시보드',marker:'FD' },{ id:'agent',label:'Agent에게 묻기',marker:'AI' },{ id:'scenario',label:'시나리오 결과',marker:'SC' },
  { id:'candidates',label:'대출 검토 후보',marker:'LC' },{ id:'stress',label:'스트레스 테스트',marker:'ST' },{ id:'alternatives',label:'대안 비교',marker:'AL' },
  { id:'opportunities',label:'기회와 위험',marker:'OP' },{ id:'connections',label:'데이터 연결',marker:'DC' },{ id:'evidence',label:'근거·계산 추적',marker:'EV' },
]
const valid=new Set(navigation.map((item)=>item.id))
function routeFromHash():ConsumerRoute {const value=window.location.hash.replace('#/fincompass/','') as ConsumerRoute;return valid.has(value)?value:'dashboard'}

export function ConsumerApp(){
 const [route,setRoute]=useState<ConsumerRoute>(routeFromHash());const [lastRun,setLastRun]=useState<AgentResponse|null>(null)
 useEffect(()=>{const update=()=>setRoute(routeFromHash());window.addEventListener('hashchange',update);return()=>window.removeEventListener('hashchange',update)},[])
 const navigate=(next:string)=>{window.location.hash=`#/fincompass/${next}`}
 return <div className="consumer-shell"><aside className="consumer-sidebar"><div className="consumer-brand"><div className="compass-mark">FC</div><div><strong>FinCompass</strong><span>Agent · Demo Mode</span></div></div><div className="demo-mode"><span/>DEMO REPLAY</div><nav aria-label="FinCompass 메뉴">{navigation.map((item)=><button key={item.id} className={route===item.id?'active':''} onClick={()=>navigate(item.id)}><span>{item.marker}</span>{item.label}</button>)}</nav><div className="consumer-sidebar-footer"><SourceSummary/><button onClick={()=>{window.location.hash='#/admin'}}>LoanOps 운영 콘솔</button></div></aside><main className="consumer-main"><div className="consumer-topbar"><div><span className="live-dot"/>Golden Home Buyer</div><div><span>개인정보 보호 데모</span><SourcePills/></div></div>{route==='connections'&&<ConnectionsView/>}{route==='dashboard'&&<FinancialDashboard navigate={navigate}/>} {route==='agent'&&<AgentView onRun={setLastRun} navigate={navigate}/>} {route==='scenario'&&<ScenarioView/>}{route==='candidates'&&<CandidatesView/>}{route==='stress'&&<StressView/>}{route==='alternatives'&&<AlternativesView/>}{route==='opportunities'&&<OpportunitiesView/>}{route==='evidence'&&<EvidenceView runId={lastRun?.agentRunId??null}/>}</main></div>
}
function SourceSummary(){return <div><strong>Offline-ready</strong><small>외부 자격증명 불필요</small></div>}
function SourcePills(){return <span className="top-source-pill">Tool-grounded</span>}
