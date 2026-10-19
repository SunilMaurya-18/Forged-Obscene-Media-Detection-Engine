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

  const roles = user?.authenticated ? user.roles || [] : []
  const isModerator = roles.includes('ROLE_MODERATOR')
  const isAdmin = roles.includes('ROLE_ADMIN')

  return (
    <AuthContext.Provider
      value={{
        user,
        loading,
        roles,
        isSignedIn: Boolean(user?.authenticated),
        // Moderator screens gate on this; investigators (Phase 28) sign in without it
        isAuthenticated: isModerator,
        isModerator,
        isAdmin,
        isSenior: roles.includes('ROLE_SENIOR_MODERATOR') || isAdmin,
        isInvestigator: roles.includes('ROLE_INVESTIGATOR'),
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
