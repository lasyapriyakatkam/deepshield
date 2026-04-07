import React, { useState } from 'react'
import axios from 'axios'
import UrlForm from './components/UrlForm'
import UploadForm from './components/UploadForm'
import ResultsDashboard from './components/ResultsDashboard'
import './styles.css'

export default function App() {
    const [jobId, setJobId] = useState(null)

    const handleUrlSubmit = async (url) => {
        try {
            const resp = await axios.post('/api/scan/url', { url })
            setJobId(resp.data.id)
        } catch (err) {
            alert('Failed to submit URL: ' + (err.response?.data?.message || err.message))
        }
    }

    const handleUpload = async (file) => {
        try {
            const form = new FormData()
            form.append('file', file)
            const resp = await axios.post('/api/scan/upload', form)
            setJobId(resp.data.id)
        } catch (err) {
            alert('Upload failed: ' + (err.response?.data?.message || err.message))
        }
    }

    return (
        <div>
            <nav className="navbar">
                <div className="navbar-brand">
                    <span>Deep</span>Shield
                </div>
                <div className="navbar-tagline">Fast deepfake detection — URL, image or video</div>
            </nav>

            <div className="app-container">
                <div className="input-section">
                    <UrlForm onSubmit={handleUrlSubmit} />
                    <UploadForm onUpload={handleUpload} />
                </div>

                <ResultsDashboard jobId={jobId} />
            </div>
        </div>
    )
}