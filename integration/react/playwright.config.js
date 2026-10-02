export default {
  testDir: './tests',
  use: { baseURL: 'http://127.0.0.1:4179', headless: true },
  webServer: { command: 'npx vite --force --host 127.0.0.1 --port 4179 --strictPort', url: 'http://127.0.0.1:4179', reuseExistingServer: false },
};
