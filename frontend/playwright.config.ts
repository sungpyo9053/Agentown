import { defineConfig, devices } from "@playwright/test";

const externalBaseUrl = process.env.PLAYWRIGHT_BASE_URL;
const mockedUi = process.env.PLAYWRIGHT_MOCKED_UI === "true";
const localBaseUrl = "http://127.0.0.1:3100";
const localBackendUrl = "http://127.0.0.1:8180";

const frontendServer = {
  command: "npm run start:e2e:mocked",
  url: localBaseUrl,
  reuseExistingServer: false,
  timeout: 300_000,
  stdout: "pipe" as const,
  stderr: "pipe" as const,
};

const fullStackServers = [
  {
    command: "cd .. && exec env DB_URL=jdbc:postgresql://127.0.0.1:5433/agent_village DB_USERNAME=agent_village DB_PASSWORD=agent_village_local LLM_MASTER_KEY=VGhpcy1pcy1hLXRlc3Qta2V5LWZvci1hZXMtMjU2ISE= EMAIL_PROVIDER=stub EMAIL_EXPOSE_DEVELOPMENT_VALUES=true CORS_ALLOWED_ORIGINS=http://127.0.0.1:3100 BUILDER_META_AGENT_MODE=mock RATE_LIMIT_ENABLED=false ./gradlew :backend:bootRun --args='--server.address=127.0.0.1 --server.port=8180'",
    url: `${localBackendUrl}/actuator/health`,
    reuseExistingServer: false,
    timeout: 180_000,
  },
  frontendServer,
];

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  expect: { timeout: 15_000 },
  use: {
    baseURL: mockedUi ? localBaseUrl : externalBaseUrl ?? localBaseUrl,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  webServer: mockedUi ? frontendServer : externalBaseUrl ? undefined : fullStackServers,
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
