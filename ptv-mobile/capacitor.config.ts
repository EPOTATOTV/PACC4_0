import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'asia.potatotv.pacc.mobile',
  appName: 'PACC 移动端',
  webDir: '../ptv-frontend/dist',
  // 发布版加载打包产物（webDir），不指向本地开发服务器。本地联调请改用真机 local network 地址另行配置。
  plugins: {
    PushNotifications: {
      // Android 用 FCM，iOS 用 APNs；需在各自平台配置服务文件
    },
  },
}

export default config