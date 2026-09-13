interface PaginationProps {
  page: number
  totalPages: number
  onChange: (page: number) => void
}

export function Pagination({ page, totalPages, onChange }: PaginationProps) {
  if (totalPages <= 1) return null
  return (
    <div className="pagination" aria-label="페이지 이동">
      <button type="button" onClick={() => onChange(page - 1)} disabled={page === 0}>이전</button>
      <span>{page + 1} / {totalPages} 페이지</span>
      <button type="button" onClick={() => onChange(page + 1)} disabled={page + 1 >= totalPages}>다음</button>
    </div>
  )
}
