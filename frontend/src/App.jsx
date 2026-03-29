import React, { useState } from 'react'
import UrlForm from './components/UrlForm'
import UploadForm from './components/UploadForm'
import ResultsDashboard from './components/ResultsDashboard'
import ErrorBoundary from './components/ErrorBoundary'

export default function App() {
  const [jobId, setJobId] = useState(null)

  return (
    <div className="container">
      <h1>DeepShield</h1>
      <div className="forms">
        <UrlForm onJobCreated={setJobId} />
        <UploadForm onJobCreated={setJobId} />
      </div>
      <ErrorBoundary>
        <ResultsDashboard jobId={jobId} />
      </ErrorBoundary>
    </div>
  )
}
