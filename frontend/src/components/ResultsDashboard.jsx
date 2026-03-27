import React, { useEffect, useState } from 'react'
import axios from 'axios'

const SAMPLE_RESULT = {
  status: 'COMPLETE',
  aggregatedResult: {
    verdict: 'LIKELY_REAL',
    confidenceScore: 0.42,
    breakdown: [
      { name: 'CNN', score: 0.5, note: 'Warning: borderline' },
      { name: 'Metadata', score: 0.12, note: 'EXIF incomplete' },
      { name: 'Temporal', score: 0.0, note: 'Not enough frames' }
    ]
  },
  originalFile: 'sample.jpg',
}

export default function ResultsDashboard({ jobId }) {
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [showPreview, setShowPreview] = useState(true)

  useEffect(() => {
    if (!jobId) return
    let mounted = true
    setError(null)
    setLoading(true)
    setResult(null)
    setShowPreview(false)

    async function poll() {
      try {
        const resp = await axios.get(`/api/scan/${jobId}`)
        if (!mounted) return
        setResult(resp.data)
        if (resp.data?.status === 'PENDING' || resp.data?.status === 'PROCESSING') {
          setTimeout(poll, 2000)
        } else {
          setLoading(false)
        }
      } catch (err) {
        if (!mounted) return
        setError(err.response?.data?.message || err.message)
        setLoading(false)
      }
    }

    poll()
    return () => { mounted = false }
  }, [jobId])

  // If no jobId and preview enabled, show sample result
  if (!jobId && showPreview) {
    return (
      <div className="dashboard">
        <h2>Sample Result Preview</h2>
        <div><strong>Status:</strong> {SAMPLE_RESULT.status}</div>
        <div><strong>Verdict:</strong> {SAMPLE_RESULT.aggregatedResult.verdict}</div>
        <div><strong>Confidence:</strong> {SAMPLE_RESULT.aggregatedResult.confidenceScore}</div>
        <h3>Breakdown</h3>
        <pre className="breakdown">{JSON.stringify(SAMPLE_RESULT.aggregatedResult.breakdown, null, 2)}</pre>
        <div style={{ marginTop: 8 }}>
          <button onClick={() => setShowPreview(false)}>Hide preview</button>
        </div>
      </div>
    )
  }

  if (!jobId) return <div className="dashboard">Submit a URL or upload a file to see results (or show the preview above)</div>
  if (error) return <div className="dashboard error">Error: {error}</div>
  if (loading) return <div className="dashboard">Loading results for job {jobId}...</div>
  if (!result) return <div className="dashboard">Waiting for job {jobId}...</div>

  return (
    <div className="dashboard">
      <h2>Results for job {jobId}</h2>
      <div><strong>Status:</strong> {result.status}</div>
      <div><strong>Verdict:</strong> {result.aggregatedResult?.verdict ?? 'N/A'}</div>
      <div><strong>Confidence:</strong> {result.aggregatedResult?.confidenceScore ?? 'N/A'}</div>
      <h3>Breakdown</h3>
      <pre className="breakdown">{JSON.stringify(result.aggregatedResult?.breakdown || result, null, 2)}</pre>
    </div>
  )
}
