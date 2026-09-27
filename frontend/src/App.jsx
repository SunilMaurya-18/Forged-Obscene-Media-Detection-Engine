import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthContext'
import Layout from './components/Layout'
import { ToastProvider } from './components/Toast'
import { StatsProvider } from './context/StatsContext'
import Compliance from './pages/Compliance'
import Dashboard from './pages/Dashboard'
import Demo from './pages/Demo'
import Login from './pages/Login'
import ModerationQueue from './pages/ModerationQueue'
import Verify from './pages/Verify'

export default function App() {
  return (
    <AuthProvider>
      <StatsProvider>
        <ToastProvider>
          <BrowserRouter>
            <Routes>
              <Route element={<Layout />}>
                <Route index element={<Demo />} />
                <Route path="queue" element={<ModerationQueue />} />
                <Route path="compliance" element={<Compliance />} />
                <Route path="dashboard" element={<Dashboard />} />
                <Route path="verify" element={<Verify />} />
                <Route path="verify/:id" element={<Verify />} />
                <Route path="login" element={<Login />} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Route>
            </Routes>
          </BrowserRouter>
        </ToastProvider>
      </StatsProvider>
    </AuthProvider>
  )
}
