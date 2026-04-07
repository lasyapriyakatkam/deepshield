import React, { useEffect, useState } from 'react'
import axios from 'axios'

export default function ResultsDashboard({ jobId }) {
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [selectedHeatmapIdx, setSelectedHeatmapIdx] = useState(0)

  useEffect(() => {
    if (!jobId) return
    let mounted = true
    setError(null)
    setLoading(true)
    setResult(null)

    async function poll() {
      try {
        const resp = await axios.get(`/api/scan/${jobId}`)
        if (!mounted) return
        setResult(resp.data)
        if (['PENDING', 'DOWNLOADING', 'PROCESSING', 'ANALYZING'].includes(resp.data?.status)) {
          setTimeout(poll, 2000)
        } else {
          // COMPLETE or FAILED — stop polling
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

  if (!jobId) return null
  if (error) return <div className="error-container">Error: {error}</div>

  if (loading) return (
      <div className="loading-container">
        <div className="loading-spinner" />
        <div className="loading-text">
          Analyzing{result?.status === 'DOWNLOADING' ? ' — downloading video...' :
            result?.status === 'PROCESSING' ? ' — extracting frames & detecting faces...' :
                result?.status === 'ANALYZING' ? ' — running CNN classification...' :
                    '...'}
        </div>
      </div>
  )

  if (!result) return null

  // Handle FAILED status
  if (result.status === 'FAILED') {
    return (
        <div className="error-container" style={{ textAlign: 'left', padding: 24 }}>
          <h3 style={{ marginBottom: 12, color: '#ef4444' }}>Analysis Failed</h3>
          <p style={{ color: '#ccc', fontSize: 14, lineHeight: 1.6 }}>
            {result.errorMessage || 'An unknown error occurred during analysis.'}
          </p>
          <p style={{ color: '#888', fontSize: 13, marginTop: 12 }}>
            Tip: If this is an Instagram image post, try saving the image and using the "Upload a file" option instead.
          </p>
        </div>
    )
  }

  const verdict = result.verdict || 'N/A'
  const confidence = result.confidenceScore
  const heatmapBase64 = result.heatmapBase64
  const heatmapSrc = heatmapBase64 ? `data:image/png;base64,${heatmapBase64}` : null
  const breakdown = result.breakdown || []
  const faceHeatmaps = result.faceHeatmaps || []
  const frameScores = result.frameScores || []
  const explanation = result.explanation

  // Selected heatmap for the main preview
  const selectedHeatmapSrc = faceHeatmaps.length > 0 && faceHeatmaps[selectedHeatmapIdx]?.heatmapBase64
      ? `data:image/png;base64,${faceHeatmaps[selectedHeatmapIdx].heatmapBase64}`
      : heatmapSrc
  const selectedLabel = faceHeatmaps.length > 0 ? faceHeatmaps[selectedHeatmapIdx]?.label : null
  const selectedConf = faceHeatmaps.length > 0 ? faceHeatmaps[selectedHeatmapIdx]?.fakeConfidence : null

  const confidenceClass = confidence < 0.4 ? 'confidence-low' : confidence < 0.65 ? 'confidence-medium' : 'confidence-high'
  const verdictClass = verdict === 'LIKELY_FAKE' ? 'verdict-fake' : verdict === 'LIKELY_REAL' ? 'verdict-real' : 'verdict-uncertain'

  function getStatusClass(status) {
    if (!status) return 'status-na'
    const s = status.toUpperCase()
    if (s === 'FAKE' || s === 'FAIL') return 'status-fake'
    if (s === 'WARN') return 'status-warn'
    if (s === 'PASS') return 'status-pass'
    return 'status-na'
  }

  function TimelineChart({ scores = [], width = 700, height = 120 }) {
    if (!scores || scores.length === 0) return <div style={{ color: '#555' }}>No frame-level data available</div>
    const max = Math.max(...scores, 1)
    const min = Math.min(...scores, 0)
    const pad = 10
    const chartW = width - pad * 2
    const chartH = height - pad * 2
    const denom = scores.length > 1 ? scores.length - 1 : 1

    const pts = scores.map((s, i) => {
      const x = pad + (i / denom) * chartW
      const y = pad + chartH - ((s - min) / (max - min || 1)) * chartH
      return `${x},${y}`
    }).join(' ')

    const baselineY = pad + chartH - ((0.5 - min) / (max - min || 1)) * chartH

    return (
        <svg width={width} height={height}>
          <line x1={pad} y1={baselineY} x2={width - pad} y2={baselineY} stroke="#333" strokeDasharray="4 4" />
          <text x={pad + 2} y={baselineY - 4} fill="#555" fontSize="10">50%</text>
          <polyline fill="none" stroke="#3b82f6" strokeWidth={2} points={pts} strokeLinejoin="round" />
          {scores.map((s, i) => {
            if (s < 0.65) return null
            const x = pad + (i / denom) * chartW
            const y = pad + chartH - ((s - min) / (max - min || 1)) * chartH
            return <circle key={i} cx={x} cy={y} r={3} fill="#ef4444" />
          })}
        </svg>
    )
  }

  return (
      <div className="dashboard">
        <div className="results-header">
          <h2>Scan Results</h2>
        </div>

        {/* IO Overview */}
        <div className="io-overview">
          <div className="io-card">
            <div className="io-title">Input</div>
            <div style={{ fontSize: 13, color: '#aaa', wordBreak: 'break-all' }}>
              {result.inputSource || 'Uploaded file'}
            </div>
            {faceHeatmaps.length > 0 && faceHeatmaps[0].heatmapBase64 && (
                <div className="io-heatmap" style={{ marginTop: 12 }}>
                  <img src={`data:image/png;base64,${faceHeatmaps[0].heatmapBase64}`} alt="input preview" />
                </div>
            )}
          </div>

          <div className="io-card">
            <div className="io-title">Output</div>
            <div className={`verdict-badge ${verdictClass}`}>{verdict.replace('_', ' ')}</div>
            <div className="output-metrics">
              <div className="small-muted" style={{ marginTop: 14 }}>Manipulation likelihood</div>
              <div className={`confidence-value ${confidenceClass}`}>
                {typeof confidence === 'number' ? (confidence * 100).toFixed(1) + '%' : 'N/A'}
              </div>
            </div>
            {heatmapSrc && (
                <div style={{ marginTop: 14 }}>
                  <div className="small-muted">Face heatmap</div>
                  <div className="io-heatmap" style={{ marginTop: 6, width: 140, height: 140 }}>
                    <img src={heatmapSrc} alt="heatmap" />
                  </div>
                </div>
            )}
            {breakdown.length > 0 && (
                <div style={{ marginTop: 14 }}>
                  <div className="small-muted">Analysis</div>
                  {breakdown.map((b, i) => (
                      <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6 }}>
                        <span style={{ fontSize: 12, color: '#ccc' }}>{b.checkName || b.name}</span>
                        <span className={`status-tag ${getStatusClass(b.status)}`}>{b.status}</span>
                      </div>
                  ))}
                </div>
            )}
          </div>
        </div>

        {/* Heatmap + Breakdown Grid */}
        <div className="results-grid">
          <div className="results-panel">
            <h4>Heatmap</h4>
            {selectedHeatmapSrc ? (
                <div>
                  <div className="heatmap-main" style={{ maxWidth: 360 }}>
                    <img src={selectedHeatmapSrc} alt="heatmap" style={{ width: '100%', display: 'block' }} />
                  </div>
                  {selectedLabel && (
                      <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span className={`status-tag ${getStatusClass(selectedLabel === 'FAKE' ? 'FAKE' : 'PASS')}`}>{selectedLabel}</span>
                        {selectedConf !== null && (
                            <span style={{ fontSize: 12, color: '#888' }}>{(selectedConf * 100).toFixed(1)}% fake confidence</span>
                        )}
                      </div>
                  )}
                </div>
            ) : (
                <div style={{ color: '#555' }}>No heatmap available</div>
            )}

            {faceHeatmaps.length > 0 && (
                <>
                  <h4 style={{ marginTop: 20 }}>Face heatmaps <span style={{ fontSize: 11, color: '#555', fontWeight: 400 }}>— click to preview</span></h4>
                  <div className="heatmap-thumbnails">
                    {faceHeatmaps.map((f, idx) => {
                      const src = f.heatmapBase64 ? `data:image/png;base64,${f.heatmapBase64}` : null
                      const isSelected = idx === selectedHeatmapIdx
                      return (
                          <div
                              key={idx}
                              className="heatmap-thumb"
                              style={isSelected ? { borderColor: '#3b82f6', borderWidth: 2, transform: 'scale(1.05)' } : {}}
                              onClick={() => setSelectedHeatmapIdx(idx)}
                          >
                            {src ? <img src={src} alt={`face-${idx}`} /> : <div style={{ color: '#555', padding: 8, fontSize: 10 }}>N/A</div>}
                          </div>
                      )
                    })}
                  </div>
                </>
            )}

            <div className="heatmap-scale-container">
              <div className="heatmap-scale-label">Heatmap scale</div>
              <div style={{ flex: 1 }}>
                <div className="heatmap-scale-bar" />
                <div className="heatmap-scale-labels">
                  <span>Low suspicion</span>
                  <span>High suspicion</span>
                </div>
              </div>
            </div>
          </div>

          <div className="results-panel">
            <h4>Analysis breakdown</h4>
            {breakdown.length === 0 ? (
                <div style={{ color: '#555' }}>No breakdown available</div>
            ) : (
                <table className="breakdown-table">
                  <thead>
                  <tr>
                    <th>Check</th>
                    <th style={{ textAlign: 'right', paddingRight: 16 }}>Score</th>
                    <th>Status</th>
                  </tr>
                  </thead>
                  <tbody>
                  {breakdown.map((b, idx) => (
                      <tr key={idx}>
                        <td className="check-name">{b.checkName || b.name}</td>
                        <td className="check-score">{(b.score ?? 0).toFixed(2)}</td>
                        <td><span className={`status-tag ${getStatusClass(b.status)}`}>{b.status}</span></td>
                      </tr>
                  ))}
                  </tbody>
                </table>
            )}

            {/* Description details */}
            {breakdown.filter(b => b.description).length > 0 && (
                <div style={{ marginTop: 16 }}>
                  {breakdown.map((b, idx) => (
                      b.description && (
                          <div key={idx} style={{ marginBottom: 8 }}>
                            <span style={{ fontSize: 11, color: '#888', fontWeight: 600 }}>{b.checkName || b.name}:</span>
                            <span style={{ fontSize: 12, color: '#aaa', marginLeft: 6 }}>{b.description}</span>
                          </div>
                      )
                  ))}
                </div>
            )}
          </div>
        </div>

        {/* Timeline Chart */}
        <div className="chart-container">
          <h4>Per-frame confidence timeline</h4>
          <TimelineChart scores={frameScores} />
        </div>

        {/* Explanation */}
        {explanation && (
            <div className="explanation-panel">
              <h4>Plain-English explanation</h4>
              <div className="explanation-text">{explanation}</div>
            </div>
        )}
      </div>
  )
}