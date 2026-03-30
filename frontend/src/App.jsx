import React, { useState } from 'react'
import UrlForm from './components/UrlForm'
import UploadForm from './components/UploadForm'
import ResultsDashboard from './components/ResultsDashboard'
import ErrorBoundary from './components/ErrorBoundary'
import Logo from './assets/logo.svg'

export default function App() {
  const [jobId, setJobId] = useState(null)

  return (
    <div className="app-root">
      <header className="app-header container">
        <div className="brand">
          <img src={Logo} alt="DeepShield logo" className="brand-logo" />
        </div>
        <div className="tagline">Fast deepfake detection — URL, image or video</div>
      </header>

      <main className="container">
        <div className="forms hero-cards">
          <UrlForm onJobCreated={setJobId} />
          <UploadForm onJobCreated={setJobId} />
        </div>

        <ErrorBoundary>
          <ResultsDashboard jobId={jobId} />
        </ErrorBoundary>
      </main>
    </div>
  )
}
