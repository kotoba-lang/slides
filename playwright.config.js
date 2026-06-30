const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './test/e2e',
  timeout: 30000,
  use: {
    baseURL: 'http://127.0.0.1:4173'
  },
  webServer: {
    command: 'python3 -m http.server 4173 --directory docs',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 10000
  }
});
