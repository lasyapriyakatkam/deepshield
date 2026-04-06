import React from 'react'

export default class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error }
  }

  componentDidCatch(error, info) {
    // eslint-disable-next-line no-console
    console.error('ErrorBoundary caught', error, info)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{padding:16,background:'#fff',border:'1px solid #f5c6cb',color:'#721c24'}}>
          <h3>Something went wrong</h3>
          <div style={{fontSize:12,whiteSpace:'pre-wrap'}}>{String(this.state.error)}</div>
        </div>
      )
    }
    return this.props.children
  }
}
