import { reportBootDiagnostic } from './utils/bootDiagnostics'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './styles/global.css'
import App from './App.tsx'
import { ErrorBoundary } from './components/ErrorBoundary'

const rootEl = document.getElementById('root')!
reportBootDiagnostic({ event: 'react-mount-start', rootElFound: !!rootEl })

createRoot(rootEl).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)

reportBootDiagnostic({ event: 'react-render-called' })
