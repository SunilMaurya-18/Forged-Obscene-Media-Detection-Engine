import { createContext, useCallback, useContext, useRef, useState } from 'react'
import { AlertCircle, CheckCircle2, X } from 'lucide-react'
import { cn } from '../lib/format'

const ToastContext = createContext(null)

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])
  const nextId = useRef(0)

  const dismiss = useCallback((id) => setToasts((list) => list.filter((t) => t.id !== id)), [])

  const toast = useCallback(
    (message, { tone = 'success', duration = 3500 } = {}) => {
      const id = ++nextId.current
      setToasts((list) => [...list.slice(-3), { id, message, tone }])
      setTimeout(() => dismiss(id), duration)
    },
    [dismiss],
  )

  return (
    <ToastContext.Provider value={toast}>
      {children}
      <div aria-live="polite" className="fixed bottom-5 right-5 z-50 flex flex-col gap-2 w-[min(92vw,360px)]">
        {toasts.map((t) => {
          const Icon = t.tone === 'error' ? AlertCircle : CheckCircle2
          return (
            <div
              key={t.id}
              className="card flex items-start gap-3 px-4 py-3 bg-surface-2/95 backdrop-blur-xl animate-fade-up"
            >
              <Icon className={cn('size-4 mt-0.5 shrink-0', t.tone === 'error' ? 'text-rose-300' : 'text-emerald-300')} />
              <p className="text-sm flex-1">{t.message}</p>
              <button
                type="button"
                onClick={() => dismiss(t.id)}
                aria-label="Dismiss"
                className="text-subtle hover:text-fg transition"
              >
                <X className="size-4" />
              </button>
            </div>
          )
        })}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast requires ToastProvider')
  return ctx
}
