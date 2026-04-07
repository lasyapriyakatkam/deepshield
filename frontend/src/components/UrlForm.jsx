import React, { useState } from 'react'

export default function UrlForm({ onSubmit }) {
  const [url, setUrl] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e) => {
    e.preventDefault()
    if (!url.trim()) return
    setLoading(true)
    await onSubmit(url.trim())
    setLoading(false)
  }

  return (
      <div className="input-card">
        <h2>Scan a URL</h2>
        <form onSubmit={handleSubmit}>
          <div className="url-input-wrapper">
            <input
                type="text"
                className="url-input"
                placeholder="Paste YouTube, Instagram, or TikTok link..."
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                disabled={loading}
            />
            <button type="submit" className="btn-primary" disabled={loading || !url.trim()}>
              {loading ? 'Analyzing...' : 'Analyze'}
            </button>
          </div>
        </form>
      </div>
  )
}