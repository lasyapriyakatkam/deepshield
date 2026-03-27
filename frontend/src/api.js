// small wrapper if you want to centralize base settings later
import axios from 'axios'

const client = axios.create({
  baseURL: '/', // proxied by vite to backend
  timeout: 20_000,
})

export default client
