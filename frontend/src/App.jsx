import React, { useState } from 'react'
import UrlForm from './components/UrlForm'
import UploadForm from './components/UploadForm'
import ResultsDashboard from './components/ResultsDashboard'

export default function App() {
  const [jobId, setJobId] = useState(null)

  return (
    <div className="container">
      <h1>DeepShield</h1>
      <div className="forms">
        <UrlForm onJobCreated={setJobId} />
        <UploadForm onJobCreated={setJobId} />
      </div>
      <ResultsDashboard jobId={jobId} />
    </div>
  )
}
