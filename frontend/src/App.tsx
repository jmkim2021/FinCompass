import { useState } from 'react'
import { DashboardView } from './views/DashboardView'
import { DataQualityView } from './views/DataQualityView'
import { ServingDataView } from './views/ServingDataView'
import { SourceRunsView } from './views/SourceRunsView'

type View = 'dashboard' | 'runs' | 'quality' | 'serving'

const navigation: Array<{ id: View; label: string; marker: string }> = [
  { id: 'dashboard', label: '운영 현황', marker: 'OV' },
  { id: 'runs', label: '수집 이력', marker: 'SR' },
  { id: 'quality', label: '데이터 품질', marker: 'DQ' },
  { id: 'serving', label: '서비스 데이터', marker: 'SD' },
]

export default function App() {
  const [view, setView] = useState<View>('dashboard')

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">LO</div>
          <div><strong>LoanOps</strong><span>운영 콘솔</span></div>
        </div>
        <nav aria-label="주요 메뉴">
          {navigation.map((item) => (
            <button key={item.id} className={view === item.id ? 'active' : ''} onClick={() => setView(item.id)}>
              <span>{item.marker}</span>{item.label}
            </button>
          ))}
        </nav>
        <div className="sidebar-footer">
          <span className="environment-dot" />
          <div><strong>로컬 환경</strong><small>조회 전용 인터페이스</small></div>
        </div>
      </aside>
      <main className="main-content">
        <div className="topbar"><span>금융 데이터 통합 및 운영</span><div><span className="read-only-pill">조회 전용</span><span>API v1</span></div></div>
        {view === 'dashboard' && <DashboardView />}
        {view === 'runs' && <SourceRunsView />}
        {view === 'quality' && <DataQualityView />}
        {view === 'serving' && <ServingDataView />}
      </main>
    </div>
  )
}
