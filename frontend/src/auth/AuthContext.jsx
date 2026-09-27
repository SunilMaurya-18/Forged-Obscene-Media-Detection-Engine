import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { clearAuth, hasStoredAuth, login as apiLogin, apiFetch } from '../api/client'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    if (!hasStoredAuth()) {
      setUser(null)
      setLoading(false)
      return
    }
    try {
      const me = await apiFetch('/api/auth/me')
      setUser(me)
    } catch {
      clearAuth()
      setUser(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const login = useCallback(async (username, password) => {
    const me = await apiLogin(username, password)
    setUser(me)
    return me
  }, [])

  const logout = useCallback(() => {
    clearAuth()
    setUser(null)
  }, [])

  return (
    <AuthContext.Provider
      value={{
        user,
        loading,
        isAuthenticated: Boolean(user?.authenticated),
        login,
        logout,
        refresh,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth requires AuthProvider')
  return ctx
}
