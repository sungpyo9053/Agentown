import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{js,ts,jsx,tsx,mdx}"],
  theme: {
    extend: {
      colors: {
        ink: "#2d2926",
        cream: "#fff9ec",
        leaf: "#276749",
        coral: "#f06b54"
      },
      boxShadow: { card: "0 18px 50px rgba(73, 55, 38, 0.12)" }
    }
  },
  plugins: []
};
export default config;

