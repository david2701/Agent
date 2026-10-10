type ClassValue = string | false | null | undefined | 0

/** Une clases condicionales. */
export function cn(...values: ClassValue[]) {
  return values.filter(Boolean).join(' ')
}
