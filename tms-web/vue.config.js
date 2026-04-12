const { defineConfig } = require('@vue/cli-service')

module.exports = defineConfig({
  transpileDependencies: true,
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
