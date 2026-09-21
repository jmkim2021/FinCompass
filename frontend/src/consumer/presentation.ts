import type { Metric } from './types'

export function formatKrw(value: number | null | undefined): string {
  if (value === null || value === undefined) return '확인 불가'
  return `${new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }).format(value)}원`
}

export function formatRatio(value: number | null | undefined): string {
  if (value === null || value === undefined) return '확인 불가'
  return `${new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }).format(value * 100)}%`
}

export function metricText(metric: Metric | undefined): string {
  if (!metric || metric.availability !== 'AVAILABLE' || metric.value === null) return '확인 불가'
  if (metric.unit === 'FRACTION') return formatRatio(metric.value)
  if (metric.unit === 'MONTHS') return `${new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }).format(metric.value)}개월`
  return formatKrw(metric.value)
}

export function humanize(value: string): string {
  return value.toLowerCase().replaceAll('_', ' ').replace(/(^|\s)\S/g, (letter) => letter.toUpperCase())
}
