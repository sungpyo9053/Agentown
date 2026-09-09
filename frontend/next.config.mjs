/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone",
  // Multi-role runtime calls can outlive Next's 30-second rewrite proxy default.
  // Keep a bounded deadline while the backend completes its own per-call limits.
  experimental: { proxyTimeout: 600_000 },
  turbopack: { root: process.cwd() },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${process.env.API_INTERNAL_URL ?? "http://localhost:8080"}/api/:path*` }];
  },
};

export default nextConfig;
