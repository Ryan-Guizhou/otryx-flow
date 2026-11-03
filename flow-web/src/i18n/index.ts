import { createI18n } from 'vue-i18n'
import { en } from './en'
import { zhCN } from './zh-CN'

export type AppLocale = 'en' | 'zh-CN'

const STORAGE_KEY = 'flow.locale'

export function readLocale(): AppLocale {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'en' || saved === 'zh-CN') return saved
  } catch {
    /* 隐私模式可能禁止访问 localStorage。 */
  }
  return navigator.language.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en'
}

export const i18n = createI18n({
  legacy: false,
  locale: readLocale(),
  fallbackLocale: 'en',
  messages: { en, 'zh-CN': zhCN },
})

export function setLocale(next: AppLocale): void {
  i18n.global.locale.value = next
  try { localStorage.setItem(STORAGE_KEY, next) } catch { /* 忽略无法写入的存储。 */ }
  applyDocumentLocale()
}

export function applyDocumentLocale(): void {
  const current = i18n.global.locale.value === 'zh-CN' ? 'zh-CN' : 'en'
  document.documentElement.lang = current
  document.title = String(i18n.global.t('meta.title'))
}

export function formatWhen(value: string, locale: string, withTime: boolean): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(locale, withTime
    ? { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }
    : { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date)
}
