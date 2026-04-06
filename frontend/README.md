# DeepShield Frontend

This is a small Vite + React frontend that provides:

- URL input (POST /api/scan/url)
- File upload (POST /api/scan/upload)
- Results dashboard (polls GET /api/scan/{id})

Proxy: Vite dev server proxies `/api` to `http://localhost:8080` per `vite.config.js`.

Quick start:

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:3000
