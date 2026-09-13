import { formatStatus } from '../displayText'

interface StatusBadgeProps {
  value: string | null
}

export function StatusBadge({ value }: StatusBadgeProps) {
  const status = value ?? 'NOT_AVAILABLE'
  const tone = ['SUCCESS', 'PROMOTED', 'MAPPED'].includes(status)
    ? 'positive'
    : ['FAILED', 'REJECTED_QUALITY', 'UNMAPPED'].includes(status)
      ? 'negative'
      : ['RUNNING'].includes(status)
        ? 'pending'
        : 'neutral'

  return <span className={`status-badge status-${tone}`}>{formatStatus(value)}</span>
}
