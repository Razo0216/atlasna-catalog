export type Lang = 'fr' | 'ar'
export type Localized = { readonly fr: string | null; readonly ar: string | null }

/** Rule: Arabic UI falls back to French; French UI never falls back to Arabic. Returns null if nothing usable. */
export function pickText(text: Localized, lang: Lang): string | null {
  const value = lang === 'ar' ? (text.ar?.trim() || text.fr) : text.fr
  return value && value.trim() !== '' ? value : null
}
