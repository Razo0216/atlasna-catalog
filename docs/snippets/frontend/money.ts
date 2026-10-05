import type { Lang } from './i18nText'

// Let Intl format money: fr-DZ -> "4 900,50 DA", ar-DZ -> "4.900,50 د.ج." (Latin digits). Never hand-roll symbols.
const LOCALES: Record<Lang, string> = { fr: 'fr-DZ', ar: 'ar-DZ-u-nu-latn' }
const formatters = new Map<Lang, Intl.NumberFormat>()

export function formatDzd(amountDa: number, lang: Lang): string {
  let formatter = formatters.get(lang)
  if (!formatter) {
    formatter = new Intl.NumberFormat(LOCALES[lang], { style: 'currency', currency: 'DZD' })
    formatters.set(lang, formatter)
  }
  return formatter.format(amountDa)
}
