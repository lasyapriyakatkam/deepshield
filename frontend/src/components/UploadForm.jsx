import React, { useState } from 'react'
import axios from 'axios'

// Allows overriding the API origin for static previews or non-proxied servers.
const API_BASE = (typeof import.meta !== 'undefined' && import.meta.env && import.meta.env.VITE_API_BASE) ? import.meta.env.VITE_API_BASE : ''

export default function UploadForm({ onJobCreated }) {
  const [file, setFile] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [previewSrc, setPreviewSrc] = useState(null)

  async function submit(e) {
    e.preventDefault()
    setError(null)
    if (!file) return setError('Choose a file to upload')
    setLoading(true)
    const form = new FormData()
    form.append('file', file)
    try {
      const resp = await axios.post(`${API_BASE}/api/scan/upload`, form, {
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

  function onFileChange(e) {
    const f = e.target.files?.[0] ?? null
    setFile(f)
    if (!f) {
      setPreviewSrc(null)
      return
    }
    // Show local preview for common image types
    if (f.type && f.type.startsWith('image/')) {
      const reader = new FileReader()
      reader.onload = () => setPreviewSrc(reader.result)
      reader.readAsDataURL(f)
    } else {
      setPreviewSrc(null)
    }
  }

  return (
    <form className="upload-form" onSubmit={submit}>
      <h2>Upload a file</h2>
      <input type="file" accept="image/*,video/*" onChange={onFileChange} />
      {previewSrc && (
        <div style={{marginTop:8}} className="upload-preview-wrap">
          <div style={{fontSize:12,color:'#666',marginBottom:6}}>Preview</div>
          <div className="upload-preview">
            <img src={previewSrc} alt="preview" style={{width:'100%',height:'100%',objectFit:'cover'}} />
          </div>
        </div>
      )}
      <button type="submit" disabled={loading}>{loading ? 'Uploading...' : 'Upload'}</button>
      {error && <div className="error">{error}</div>}
    </form>
  )
}
