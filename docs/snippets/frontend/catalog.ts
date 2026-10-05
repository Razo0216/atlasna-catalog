import { z } from 'zod'

export const localizedTextSchema = z.object({ fr: z.string(), ar: z.string().nullable() })
export const nullableLocalizedSchema = z.object({ fr: z.string().nullable(), ar: z.string().nullable() })

export const CATEGORY_TYPES = ['GROCERY', 'BEAUTY']
export const categoryTypeSchema = z.enum(CATEGORY_TYPES)

export const categoryNodeSchema = z.object({
  id: z.number().int(),
  slug: z.string(),
  type: categoryTypeSchema,
  name: localizedTextSchema,
  sortOrder: z.number().int(),
  get children() { return z.array(categoryNodeSchema) },   // Zod v4 recursive object (no z.lazy needed)
})

export const soldBySchema = z.enum(['UNIT', 'WEIGHT'])
export const availabilitySchema = z.enum(['IN_STOCK', 'LOW_STOCK', 'OUT_OF_STOCK'])

export const productSchema = z.object({
  id: z.number().int(),
  slug: z.string(),
  sku: z.string(),
  barcode: z.string().nullable(),
  name: localizedTextSchema,
  description: nullableLocalizedSchema,
  brand: z.string().nullable(),
  category: z.object({ id: z.number().int(), slug: z.string(), type: categoryTypeSchema, name: localizedTextSchema }),
  price: z.number().positive(),
  compareAtPrice: z.number().positive().nullable(),
  soldBy: soldBySchema,
  quantityStep: z.number().positive(),
  unitWeightGrams: z.number().int().positive(),
  availability: availabilitySchema,
  maxOrderQuantity: z.number().nonnegative(),
  perishable: z.boolean(),
  featured: z.boolean(),
  attributes: z.record(z.string(), z.unknown()),
  images: z.array(z.object({ url: z.string(), alt: nullableLocalizedSchema })),
  createdAt: z.string(),
})

export const pageSchema = <T extends z.ZodType>(item: T) => z.object({
  content: z.array(item),
  page: z.object({ size: z.number().int(), number: z.number().int(), totalElements: z.number().int(), totalPages: z.number().int() }),
})

export const userSchema = z.object({
  id: z.number().int(), fullName: z.string(), email: z.string(), phone: z.string().nullable(),
  role: z.enum(['ADMIN', 'STAFF', 'DRIVER', 'CUSTOMER']),
})
export const authResponseSchema = z.object({
  accessToken: z.string().min(1), tokenType: z.literal('Bearer'), expiresInSeconds: z.number().int().positive(), user: userSchema,
})
export const apiErrorBodySchema = z.object({
  timestamp: z.string(), status: z.number().int(), error: z.string(),
  code: z.string().nullish(), message: z.string(),
  fieldErrors: z.record(z.string(), z.string()).nullish(),
})

export type LocalizedText = z.infer<typeof localizedTextSchema>
export type CategoryNode = z.infer<typeof categoryNodeSchema>
export type Product = z.infer<typeof productSchema>
export type SoldBy = z.infer<typeof soldBySchema>
export type Availability = z.infer<typeof availabilitySchema>
export type User = z.infer<typeof userSchema>
export type AuthResponse = z.infer<typeof authResponseSchema>
export type Page<T> = { content: T[]; page: { size: number; number: number; totalElements: number; totalPages: number } }
