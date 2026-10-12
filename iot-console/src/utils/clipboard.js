// 剪贴板工具
// navigator.clipboard 只在「安全上下文」（https / localhost）可用；
// 局域网 http 访问（如 http://192.168.x.x:5173）下它会是 undefined，
// 此时降级到 document.execCommand('copy')，避免"点了复制没反应"。

export async function copyText(text) {
  const s = String(text ?? '')
  if (!s) return false

  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(s)
      return true
    }
  } catch {
    // 继续走降级方案
  }

  try {
    const ta = document.createElement('textarea')
    ta.value = s
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.top = '-1000px'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
