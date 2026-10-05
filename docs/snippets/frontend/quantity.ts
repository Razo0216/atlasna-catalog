/**
 * Quantities are handled as INTEGER MILLI-UNITS (1.5 kg = 1500) so float drift
 * (0.1 + 0.2 !== 0.3) can never reach money. Everything user-facing converts at the edge.
 */
export const toMilli = (quantity: number): number => Math.round(quantity * 1000)
export const fromMilli = (milli: number): number => milli / 1000

/** Snap a requested quantity DOWN to a valid multiple of `step`, within [step, max]. Returns 0 if nothing can be offered. */
export function snapQuantity(requested: number, step: number, max: number): number {
  const stepMilli = toMilli(step)
  const maxMilli = toMilli(max)
  if (maxMilli < stepMilli) return 0
  const snapped = Math.floor(toMilli(requested) / stepMilli) * stepMilli
  const highest = Math.floor(maxMilli / stepMilli) * stepMilli
  return fromMilli(Math.min(Math.max(snapped, stepMilli), highest))
}

/** unitPriceDa is DA per unit (or per kg); quantity in units (or kg). Returns centimes, rounded half-up. */
export function lineTotalCentimes(unitPriceDa: number, quantity: number): number {
  return Math.round((Math.round(unitPriceDa * 100) * toMilli(quantity)) / 1000)
}

export const sumCentimes = (values: readonly number[]): number => values.reduce((a, b) => a + b, 0)
export const fromCentimes = (centimes: number): number => centimes / 100
