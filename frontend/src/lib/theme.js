export const THEME_KEY = 'fmd_theme'
export const THEMES = ['dark', 'light']

const CANVAS = { dark: '#0f1114', light: '#eceef1' }

export function initialTheme() {
  try {
    const saved = localStorage.getItem(THEME_KEY)
    if (THEMES.includes(saved)) return saved
  } catch {
    /* storage blocked: fall through to the system preference */
  }
  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

/** Mirrors the inline script in index.html, which runs before first paint. */
export function applyTheme(theme) {
  const root = document.documentElement
  root.dataset.theme = theme
  root.style.colorScheme = theme
  root.style.backgroundColor = CANVAS[theme]
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', CANVAS[theme])
}

export function saveTheme(theme) {
  try {
    localStorage.setItem(THEME_KEY, theme)
  } catch {
    /* the choice still applies for this visit */
  }
}
