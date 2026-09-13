import type { ReactNode } from 'react'

export function LoadingState() {
  return <div className="state-panel">Loading operational data…</div>
}

export function ErrorState({ error }: { error: string }) {
  return <div className="state-panel state-error">Unable to load data: {error}</div>
}

export function EmptyState({ children = 'No records found.' }: { children?: ReactNode }) {
  return <div className="state-panel">{children}</div>
}
