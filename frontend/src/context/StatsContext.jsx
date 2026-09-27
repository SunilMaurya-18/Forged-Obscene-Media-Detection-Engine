import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { fetchStats } from '../api/client'
import { DEFAULT_THRESHOLDS } from '../lib/decision'

const POLL_MS = 15000

const StatsContext = createContext(null)

/** Polls the public /api/stats endpoint: API status, queue size, and decision thresholds. */
export function StatsProvider({ children }) {
  const [stats, setStats] = useState(null)
  const [online, setOnline] = useState(null)

  const refresh = useCallback(async () => {
    try {
      setStats(await fetchStats())
      setOnline(true)
    } catch {
      setOnline(false)
    }
  }, [])

  useEffect(() => {
    refresh()
    const timer = setInterval(refresh, POLL_MS)
    return () => clearInterval(timer)
  }, [refresh])

  return (
    <StatsContext.Provider
      value={{
        stats,
        online,
        refresh,
        reviewCount: stats?.byDecision?.REVIEW ?? 0,
        thresholds: { ...DEFAULT_THRESHOLDS, ...(stats?.thresholds || {}) },
      }}
    >
      {children}
    </StatsContext.Provider>
  )
}

export function useStats() {
  const ctx = useContext(StatsContext)
  if (!ctx) throw new Error('useStats requires StatsProvider')
  return ctx
}
