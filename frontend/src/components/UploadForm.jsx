import React, { useState } from 'react'
import axios from 'axios'

export default function UploadForm({ onJobCreated }) {
  const [file, setFile] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  async function submit(e) {
    e.preventDefault()
    setError(null)
    if (!file) return setError('Choose a file to upload')
    setLoading(true)
    const form = new FormData()
    form.append('file', file)
    try {
      const resp = await axios.post('/api/scan/upload', form, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
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
    <form className="upload-form" onSubmit={submit}>
      <h2>Upload a file</h2>
      <input type="file" onChange={e => setFile(e.target.files?.[0] ?? null)} />
      <button type="submit" disabled={loading}>{loading ? 'Uploading...' : 'Upload'}</button>
      {error && <div className="error">{error}</div>}
    </form>
  )
}
