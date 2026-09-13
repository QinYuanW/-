// 设备列表导出（CSV，UTF-8 BOM 保证 Excel 中文不乱码）

function escapeCell(value) {
  const s = String(value ?? '')
  if (/[",\n\r]/.test(s)) {
    return `"${s.replace(/"/g, '""')}"`
  }
  return s
}

export function downloadCsv(filename, headers, rows) {
  const lines = [
    headers.map(escapeCell).join(','),
    ...rows.map((row) => headers.map((h) => escapeCell(row[h] ?? '')).join(','))
  ]
  const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

export function formatTime(s) {
  if (!s) return '-'
  return String(s).replace('T', ' ').slice(0, 19)
}
