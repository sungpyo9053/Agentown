/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone",
  turbopack: { root: process.cwd() },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${process.env.API_INTERNAL_URL ?? "http://localhost:8080"}/api/:path*` }];
  },
};

export default nextConfig;
