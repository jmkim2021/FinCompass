const STATUS_DISPLAY: Record<string, string> = {
  SUCCESS: '성공',
  FAILED: '실패',
  PROMOTED: '반영 완료',
  REJECTED_QUALITY: '품질 기준 미충족',
  NO_CHANGE: '변경 없음',
  MAPPED: '매핑 완료',
  UNMAPPED: '미매핑',
  ADDED: '신규',
  UPDATED: '변경',
  RUNNING: '처리 중',
  NOT_AVAILABLE: '정보 없음',
}

export function formatStatus(value: string | null): string {
  const status = value ?? 'NOT_AVAILABLE'
  return STATUS_DISPLAY[status] ?? status.replaceAll('_', ' ')
}

export function formatTime(
  value: string | null,
  options: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' },
): string {
  return value ? new Intl.DateTimeFormat('ko-KR', options).format(new Date(value)) : '—'
}
