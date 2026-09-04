import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'asia.potatotv.pacc.mobile',
  appName: 'PACC 移动端',
  webDir: '../ptv-frontend/dist',
  server: {
    // 本地联调热更新：npm run dev 在 ptv-frontend 起 5173；
    // 若需走真机调试再打开，发布版本请移除 server 段。
    url: 'http://localhost:5173',
    cleartext: true,
  },
  plugins: {
    PushNotifications: {
      // Android 用 FCM，iOS 用 APNs；需在各自平台配置服务文件
    },
  },
}

export default config