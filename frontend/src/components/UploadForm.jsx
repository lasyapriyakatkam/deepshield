import React, { useState, useRef } from 'react'

export default function UploadForm({ onUpload }) {
  const [file, setFile] = useState(null)
  const [loading, setLoading] = useState(false)
  const [dragOver, setDragOver] = useState(false)
  const inputRef = useRef()

  const handleFile = (f) => {
    if (f) setFile(f)
  }

  const handleSubmit = async () => {
    if (!file) return
    setLoading(true)
    await onUpload(file)
    setLoading(false)
  }

  const handleDrop = (e) => {
    e.preventDefault()
    setDragOver(false)
    const f = e.dataTransfer.files[0]
    handleFile(f)
  }

  return (
      <div className="input-card">
        <h2>Upload a file</h2>
        <div
            className="upload-zone"
            style={dragOver ? { borderColor: '#3b82f6', background: '#161822' } : {}}
            onClick={() => inputRef.current?.click()}
            onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
            onDragLeave={() => setDragOver(false)}
            onDrop={handleDrop}
        >
          <div className="upload-zone-icon">📁</div>
          <div>Drop image or video here</div>
          <p>or click to browse - JPG, PNG, MP4, MOV</p>
        </div>
        <input
            ref={inputRef}
            type="file"
            accept="image/*,video/*"
            style={{ display: 'none' }}
            onChange={(e) => handleFile(e.target.files[0])}
        />
        {file && (
            <div className="file-info">
              <span>{file.name} ({(file.size / 1024 / 1024).toFixed(1)} MB)</span>
              <button className="btn-primary" onClick={handleSubmit} disabled={loading} style={{ padding: '8px 18px', fontSize: 13 }}>
                {loading ? 'Analyzing...' : 'Analyze'}
              </button>
            </div>
        )}
      </div>
  )
}