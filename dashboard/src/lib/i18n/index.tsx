import { createContext, Fragment, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { en } from './en'
import { es } from './es'

/** Supported UI languages. English is the default and the fallback. */
export type Lang = 'en' | 'es'
/** What the user chose: a fixed language, or follow the browser. */
export type LangPref = Lang | 'system'
export type TKey = keyof typeof en
type Params = Record<string, string | number>

const STORAGE_KEY = 'kairo.lang'
const DICTS: Record<Lang, Record<TKey, string>> = { en, es }

/** Base of the keys that have `.one` and `.other` variants (see `tn`). */
export type PluralKey = { [K in TKey]: K extends `${infer B}.one` ? B : never }[TKey]

/* ------------------------------------------------------------ language resolution */

export function readLangPref(): LangPref {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v === 'en' || v === 'es' || v === 'system') return v
  } catch {
    /* storage blocked */
  }
  return 'system'
}

function writeLangPref(pref: LangPref) {
  try {
    if (pref === 'system') localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, pref)
  } catch {
    /* no storage */
  }
}

function browserLanguage(): string {
  if (typeof navigator === 'undefined') return ''
  return navigator.languages?.[0] ?? navigator.language ?? ''
}

/** Explicit choice -> browser language (`es*` is Spanish) -> English. */
export function resolveLang(pref: LangPref): Lang {
  if (pref === 'en' || pref === 'es') return pref
  return browserLanguage().toLowerCase().startsWith('es') ? 'es' : 'en'
}

/* ------------------------------------------------------------ active language (usable outside React) */

let active: Lang = resolveLang(readLangPref())

export function getLang(): Lang {
  return active
}

/** BCP 47 tag for `Intl`: keeps the browser's region when it matches the UI language. */
export function intlLocale(lang: Lang = active): string {
  const nav = browserLanguage()
  if (nav.toLowerCase().startsWith(lang)) return nav
  return lang === 'es' ? 'es-ES' : 'en-US'
}

/** Language tag for the Web Speech API (`SpeechRecognition.lang`). */
export function speechLang(lang: Lang = active): string {
  return lang === 'es' ? 'es-ES' : 'en-US'
}

/* ------------------------------------------------------------ translation */

function interpolate(text: string, params?: Params): string {
  if (!params) return text
  return text.replace(/\{(\w+)\}/g, (m, name: string) => (name in params ? String(params[name]) : m))
}

/** Looks up `key` in the active language (falls back to English) and fills `{name}` placeholders. */
export function t(key: TKey, params?: Params): string {
  return interpolate(DICTS[active][key] ?? en[key] ?? key, params)
}

/** Plural helper: uses `<base>.one` / `<base>.other` (per `Intl.PluralRules`). `{count}` is filled automatically. */
export function tn(base: PluralKey, count: number, params?: Params): string {
  const form = new Intl.PluralRules(intlLocale()).select(count) === 'one' ? 'one' : 'other'
  return t(`${base}.${form}` as TKey, { count, ...params })
}

/* ------------------------------------------------------------ React */

interface I18nValue {
  lang: Lang
  pref: LangPref
  setPref: (p: LangPref) => void
  t: typeof t
  tn: typeof tn
}

const I18nContext = createContext<I18nValue | null>(null)

export function I18nProvider({ children }: { children: ReactNode }) {
  const [pref, setPrefState] = useState<LangPref>(readLangPref)
  // Re-resolve when the browser language changes while following the system.
  const [systemTick, setSystemTick] = useState(0)
  useEffect(() => {
    const on = () => setSystemTick((n) => n + 1)
    window.addEventListener('languagechange', on)
    return () => window.removeEventListener('languagechange', on)
  }, [])

  // eslint-disable-next-line react-hooks/exhaustive-deps -- systemTick only forces a re-read of navigator.language
  const lang = useMemo(() => resolveLang(pref), [pref, systemTick])
  // Set synchronously so `t()` is already correct for the children rendered below.
  active = lang

  useEffect(() => {
    document.documentElement.lang = lang
  }, [lang])

  const setPref = useCallback((p: LangPref) => {
    writeLangPref(p)
    setPrefState(p)
  }, [])

  const value = useMemo<I18nValue>(() => ({ lang, pref, setPref, t, tn }), [lang, pref, setPref])
  // Keyed by language: module-level `t()` calls (dates, toasts, constants) re-evaluate on a switch.
  return (
    <I18nContext.Provider value={value}>
      <Fragment key={lang}>{children}</Fragment>
    </I18nContext.Provider>
  )
}

export function useI18n(): I18nValue {
  const ctx = useContext(I18nContext)
  if (!ctx) throw new Error('useI18n outside I18nProvider')
  return ctx
}

/** `const t = useT()`: the translate function, re-rendering the component when the language changes. */
export function useT(): typeof t {
  return useI18n().t
}
