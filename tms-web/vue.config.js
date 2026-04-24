const { defineConfig } = require('@vue/cli-service')

module.exports = defineConfig({
  transpileDependencies: true,
  devServer: {
    client: {
      overlay: {
        runtimeErrors: (error) => {
          if (error.message && error.message.includes('ResizeObserver loop')) return false
          return true
        }
      }
    }
  },
  configureWebpack: {
    resolve: {
      fallback: {
        "stream": false,
        "dgram": false,
        "fs": false,
        "net": false,
        "tls": false,
        "child_process": false,
        "crypto": false,
        "buffer": false,
        "process": false
      }
    }
  }
})
