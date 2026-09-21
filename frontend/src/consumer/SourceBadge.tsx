const LABELS: Record<string, string> = {
  REAL_PRODUCT_DATA: 'REAL PRODUCT DATA',
  DEMO_PRODUCT_FIXTURE: 'DEMO PRODUCT FIXTURE',
  OPEN_BANKING_TEST: 'OPEN BANKING TEST',
  PUBLIC_MYDATA_MOCK: 'PUBLIC MYDATA MOCK',
  USER_INPUT: 'USER INPUT',
  DERIVED: 'DERIVED',
  DEMO_REPLAY: 'DEMO REPLAY',
  AIHUB_OFFLINE: 'AI-HUB OFFLINE',
}

export function SourceBadge({ value }: { value: string }) {
  return <span className={`source-badge source-${value.toLowerCase().replaceAll('_', '-')}`}>{LABELS[value] ?? value.replaceAll('_', ' ')}</span>
}
