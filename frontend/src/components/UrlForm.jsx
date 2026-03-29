import React, { useState } from 'react'
import axios from 'axios'

// Allows overriding the API origin for static previews or non-proxied servers.
const API_BASE = (typeof import.meta !== 'undefined' && import.meta.env && import.meta.env.VITE_API_BASE) ? import.meta.env.VITE_API_BASE : ''

export default function UrlForm({ onJobCreated }) {
  const [url, setUrl] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  function normalize(input) {
    if (!input) return input
    if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(input)) {
      return 'https://' + input
    }
    return input
  }

  async function submit(e) {
    e.preventDefault()
    setError(null)
    const normalized = normalize(url.trim())
    if (!normalized) return setError('Please enter a URL')
    setLoading(true)
    try {
  const resp = await axios.post(`${API_BASE}/api/scan/url`, { url: normalized })
      if (resp && resp.data && resp.data.id) {
        onJobCreated(resp.data.id)
      }
    } catch (err) {
      setError(err.response?.data?.message || err.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <form className="url-form" onSubmit={submit}>
      <h2>Scan a URL</h2>
      <input value={url} onChange={e => setUrl(e.target.value)} placeholder="YouTube or video URL" />
      <button type="submit" disabled={loading}>{loading ? 'Submitting...' : 'Submit'}</button>
      {error && <div className="error">{error}</div>}
    </form>
  )
}
