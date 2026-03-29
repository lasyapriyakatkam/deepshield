import React, { useEffect, useState } from 'react'
import axios from 'axios'

// Allows overriding the API origin for static previews or non-proxied servers.
const API_BASE = (typeof import.meta !== 'undefined' && import.meta.env && import.meta.env.VITE_API_BASE) ? import.meta.env.VITE_API_BASE : ''

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
  // hoverPreviewSrc must be declared unconditionally to keep hook order stable
  const [hoverPreviewSrc, setHoverPreviewSrc] = useState(null)

  useEffect(() => {
    if (!jobId) return
    let mounted = true
    setError(null)
    setLoading(true)
    setResult(null)
    setShowPreview(false)

    async function poll() {
      try {
        const resp = await axios.get(`${API_BASE}/api/scan/${jobId}`)
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

  const aggregated = result.aggregatedResult || {}
  // Backend currently returns verdict/confidence at top-level; prefer top-level and fall back to aggregatedResult
  const verdict = (result.verdict !== undefined && result.verdict !== null) ? result.verdict : (aggregated.verdict ?? 'N/A')
  const confidence = (result.confidenceScore !== undefined && result.confidenceScore !== null) ? result.confidenceScore : (aggregated.confidenceScore ?? 'N/A')
  // heatmap may be supplied at job-level or inside aggregated result
  const heatmapBase64 = result.heatmapBase64 || aggregated.heatmapBase64 || null
  // Resolve backend origin in dev so relative /uploads URLs point to backend:8080
  // If API_BASE is configured (for static preview builds), use that as the backend origin
  const backendOrigin = API_BASE || ((typeof import.meta !== 'undefined' && import.meta.env && import.meta.env.MODE === 'development') ? 'http://localhost:8080' : '')
  const heatmapSrc = heatmapBase64 ? `data:image/png;base64,${heatmapBase64}` : null

  // per-frame confidence timeline (optional)
  const frameScores = result.frameScores || aggregated.frameScores || null
  const frameTimestamps = result.frameTimestamps || aggregated.frameTimestamps || null
  const faceHeatmaps = result.faceHeatmaps || aggregated.faceHeatmaps || []

  // Normalize frameScores for the chart: if backend returned null/empty, synthesize a neutral series
  let normalizedFrameScores = frameScores
  if (!normalizedFrameScores || normalizedFrameScores.length === 0) {
    // prefer frameTimestamps length, then faceHeatmaps length, else default to single neutral point
    const n = (frameTimestamps && frameTimestamps.length) ? frameTimestamps.length : (faceHeatmaps && faceHeatmaps.length ? faceHeatmaps.length : 1)
    normalizedFrameScores = new Array(n).fill(0.5)
  }

  // breakdown array of { name, score, note/description, status }
  const breakdown = result.breakdown || aggregated.breakdown || []

  function SmallChart({ scores = [] , width=600, height=120 }){
    if (!scores || scores.length === 0) return <div style={{color:'#666'}}>No frame-level scores available</div>
    // Handle single-value arrays so division by zero doesn't occur and visualize a point/line
    const max = Math.max(...scores, 1)
    const min = Math.min(...scores, 0)
    const denom = (scores.length > 1) ? (scores.length - 1) : 1
    let pts = ''
    if (scores.length === 1) {
      const s = scores[0]
      const y = height - ((s - min)/(max - min || 1)) * height
      // draw a horizontal line across the chart for single-value series so it is visible
      pts = `0,${y} ${width},${y}`
    } else {
      pts = scores.map((s,i)=>{
        const x = (i/denom) * width
        const y = height - ((s - min)/(max - min || 1)) * height
        return `${x},${y}`
      }).join(' ')
    }
    return (
      <svg width={width} height={height} style={{background:'#fafafa',border:'1px solid #eee'}}>
        <polyline fill="none" stroke="#1976d2" strokeWidth={2} points={pts} />
        {/* baseline 0.5 */}
        <line x1={0} y1={height - ((0.5-min)/(max-min||1))*height} x2={width} y2={height - ((0.5-min)/(max-min||1))*height} stroke="#ccc" strokeDasharray="4 4" />
      </svg>
    )
  }

  function HeatmapThumbnails({ items = [] }){
    if (!items || items.length === 0) return <div style={{color:'#666'}}>No face heatmaps available.</div>
    return (
      <div style={{display:'flex',gap:8,flexWrap:'wrap'}}>
        {items.map((f,idx)=>{
          let src = null
          if (f.heatmapBase64) src = `data:image/png;base64,${f.heatmapBase64}`
          else if (f.heatmapUrl) src = (f.heatmapUrl.startsWith('/uploads') && backendOrigin) ? `${backendOrigin}${f.heatmapUrl}` : f.heatmapUrl
          return (
            <div key={idx} style={{width:80,height:80,border:'1px solid #eee',overflow:'hidden',cursor: src ? 'pointer' : 'default'}}
                 onMouseEnter={() => src && setHoverPreviewSrc(src)}
                 onMouseLeave={() => setHoverPreviewSrc(null)}>
              {src ? <img src={src} alt={`heatmap-${idx}`} style={{width:'100%',height:'100%',objectFit:'cover'}} /> : <div style={{color:'#999',padding:8}}>no heatmap</div>}
            </div>
          )
        })}
      </div>
    )
  }

  return (
    <div className="dashboard">
      <h2>Results for job {jobId}</h2>
      <div style={{display:'flex',gap:20}}>
        <div style={{flex:'1 1 420px'}}>
          <div style={{marginBottom:8}}><strong>Status:</strong> {result.status}</div>
          <div style={{marginBottom:8}}><strong>Verdict:</strong> {verdict}</div>
          <div style={{marginBottom:8}}><strong>Confidence:</strong> {typeof confidence === 'number' ? confidence : confidence}</div>

          <div style={{marginTop:12}}>
            <h4>Heatmap</h4>
            {heatmapSrc ? (
              <div style={{position:'relative',display:'inline-block',border:'1px solid #ddd'}}>
                {/* If original image is available we could show it under heatmap; for now show heatmap alone */}
                <img src={heatmapSrc} alt="heatmap" style={{maxWidth:'100%', display:'block'}} />
              </div>
            ) : (
              <div style={{color:'#666'}}>No heatmap available for this job.</div>
            )}
            <div style={{marginTop:10}}>
              <h5>Face heatmaps</h5>
              <HeatmapThumbnails items={faceHeatmaps} />
              {hoverPreviewSrc && (
                <div style={{position:'fixed',right:20,top:80,border:'1px solid #ccc',background:'#fff',padding:8,zIndex:999}}>
                  <img src={hoverPreviewSrc} alt="preview" style={{width:240,height:240,objectFit:'contain'}} />
                </div>
              )}
            </div>
          </div>

        </div>

        <div style={{flex:'1 1 320px'}}>
          <h4>Analysis breakdown</h4>
          {breakdown.length === 0 ? (
            <div style={{color:'#666'}}>No breakdown available.</div>
          ) : (
            <table style={{width:'100%',borderCollapse:'collapse'}}>
              <thead>
                <tr>
                  <th style={{textAlign:'left',borderBottom:'1px solid #eee',padding:'6px'}}>Check</th>
                  <th style={{textAlign:'right',borderBottom:'1px solid #eee',padding:'6px'}}>Score</th>
                  <th style={{textAlign:'left',borderBottom:'1px solid #eee',padding:'6px'}}>Status</th>
                </tr>
              </thead>
              <tbody>
                {breakdown.map((b, idx)=> (
                  <tr key={idx}>
                    <td style={{padding:'6px',verticalAlign:'top'}}>{b.name || b.checkName || b.title}</td>
                    <td style={{padding:'6px',verticalAlign:'top',textAlign:'right'}}>{(b.score ?? b.confidence ?? 0).toFixed(2)}</td>
                    <td style={{padding:'6px',verticalAlign:'top'}}>{b.status || b.note || b.description || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      <div style={{marginTop:18}}>
        <h4>Per-frame confidence timeline</h4>
  <SmallChart scores={normalizedFrameScores} />
      </div>
    </div>
  )
}
