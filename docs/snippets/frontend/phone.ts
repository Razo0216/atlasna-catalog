/**
 * Algerian MOBILE numbers only (05 / 06 / 07). Must stay byte-for-byte equivalent to the backend's PhoneNumbers.java.
 * Canonical form: "+213" + 9 digits. Returns null when invalid.
 */
const SEPARATORS = /[\s.\-()]/g
const ALGERIAN_MOBILE = /^(?:\+213|00213|0)([567]\d{8})$/

export function normalizeAlgerianMobile(raw: string): string | null {
  const match = ALGERIAN_MOBILE.exec(raw.replace(SEPARATORS, ''))
  return match ? `+213${match[1]}` : null
}

export const isAlgerianMobile = (raw: string): boolean => normalizeAlgerianMobile(raw) !== null
