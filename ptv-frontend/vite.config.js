import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
// PTV 管理后台前端。开发时通过代理将 /api 转发到后端 (http://localhost:8080)。
export default defineConfig({
    plugins: [react()],
    server: {
        port: 5173,
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
});
