import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { fetchDemo } from '../api/client'

const DemoContext = createContext(null)

/** Phase 25: whether this instance runs the one-click demo (and in public mode), plus its current story. */
export function DemoProvider({ children }) {
  const [demo, setDemo] = useState(null)

  const refresh = useCallback(async () => {
    try {
      const next = await fetchDemo()
      setDemo(next)
      return next
    } catch {
      setDemo((d) => d || { enabled: false })
      return null
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  return (
    <DemoContext.Provider
      value={{
        demo,
        setDemo,
        refresh,
        enabled: Boolean(demo?.enabled),
        publicMode: Boolean(demo?.enabled && demo?.publicMode),
        samples: demo?.enabled ? demo.samples || [] : [],
      }}
    >
      {children}
    </DemoContext.Provider>
  )
}

export function useDemo() {
  const ctx = useContext(DemoContext)
  if (!ctx) throw new Error('useDemo requires DemoProvider')
  return ctx
}
