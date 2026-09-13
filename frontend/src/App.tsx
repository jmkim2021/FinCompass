import { useState } from 'react'
import { DashboardView } from './views/DashboardView'
import { DataQualityView } from './views/DataQualityView'
import { ServingDataView } from './views/ServingDataView'
import { SourceRunsView } from './views/SourceRunsView'

type View = 'dashboard' | 'runs' | 'quality' | 'serving'

const navigation: Array<{ id: View; label: string; marker: string }> = [
  { id: 'dashboard', label: 'Dashboard', marker: 'OV' },
  { id: 'runs', label: 'Source Runs', marker: 'SR' },
  { id: 'quality', label: 'Data Quality', marker: 'DQ' },
  { id: 'serving', label: 'Serving Data', marker: 'SD' },
]

export default function App() {
  const [view, setView] = useState<View>('dashboard')

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">LO</div>
          <div><strong>LoanOps</strong><span>Operations Console</span></div>
        </div>
        <nav aria-label="Primary navigation">
          {navigation.map((item) => (
            <button key={item.id} className={view === item.id ? 'active' : ''} onClick={() => setView(item.id)}>
              <span>{item.marker}</span>{item.label}
            </button>
          ))}
        </nav>
        <div className="sidebar-footer">
          <span className="environment-dot" />
          <div><strong>Local environment</strong><small>Read-only interface</small></div>
        </div>
      </aside>
      <main className="main-content">
        <div className="topbar"><span>Financial data integration &amp; operations</span><div><span className="read-only-pill">GET only</span><span>API v1</span></div></div>
        {view === 'dashboard' && <DashboardView />}
        {view === 'runs' && <SourceRunsView />}
        {view === 'quality' && <DataQualityView />}
        {view === 'serving' && <ServingDataView />}
      </main>
    </div>
  )
}
