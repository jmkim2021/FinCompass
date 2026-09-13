import type { ReactNode } from 'react'

export function LoadingState() {
  return <div className="state-panel">운영 데이터를 불러오는 중…</div>
}

export function ErrorState({ error }: { error: string }) {
  return <div className="state-panel state-error">데이터를 불러올 수 없습니다: {error}</div>
}

export function EmptyState({ children = '조회된 레코드가 없습니다.' }: { children?: ReactNode }) {
  return <div className="state-panel">{children}</div>
}
