import { lazy } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthContext'
import Layout from './components/Layout'
import { ToastProvider } from './components/Toast'
import { DecisionProvider } from './context/DecisionContext'
import { StatsProvider } from './context/StatsContext'
import { ThemeProvider } from './context/ThemeContext'
import CaseStatus from './pages/CaseStatus'
import Demo from './pages/Demo'
import Landing from './pages/Landing'
import Login from './pages/Login'
import ReportWizard from './pages/ReportWizard'

const ApiAccess = lazy(() => import('./pages/ApiAccess'))
const Compliance = lazy(() => import('./pages/Compliance'))
const Dashboard = lazy(() => import('./pages/Dashboard'))
const Explained = lazy(() => import('./pages/Explained'))
const ModerationQueue = lazy(() => import('./pages/ModerationQueue'))
const Privacy = lazy(() => import('./pages/Privacy'))
const Terms = lazy(() => import('./pages/Terms'))
const Verify = lazy(() => import('./pages/Verify'))

export default function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
        <StatsProvider>
          <ToastProvider>
            <DecisionProvider>
              <BrowserRouter>
                <Routes>
                  <Route element={<Layout />}>
                    <Route index element={<Landing />} />
                    <Route path="analyze" element={<Demo />} />
                    <Route path="report" element={<ReportWizard />} />
                    <Route path="status/:caseNumber?" element={<CaseStatus />} />
                    <Route path="queue" element={<ModerationQueue />} />
                    <Route path="compliance" element={<Compliance />} />
                    <Route path="dashboard" element={<Dashboard />} />
                    <Route path="verify" element={<Verify />} />
                    <Route path="verify/:id" element={<Verify />} />
                    <Route path="api-access" element={<ApiAccess />} />
                    <Route path="explained" element={<Explained />} />
                    <Route path="terms" element={<Terms />} />
                    <Route path="privacy" element={<Privacy />} />
                    <Route path="login" element={<Login />} />
                    <Route path="*" element={<Navigate to="/" replace />} />
                  </Route>
                </Routes>
              </BrowserRouter>
            </DecisionProvider>
          </ToastProvider>
        </StatsProvider>
      </AuthProvider>
    </ThemeProvider>
  )
}
