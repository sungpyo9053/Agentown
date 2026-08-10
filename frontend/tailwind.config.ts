import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{js,ts,jsx,tsx,mdx}"],
  theme: {
    extend: {
      colors: {
        ink: "#18181b",
        cream: "#f7f7f6",
        leaf: "#16a34a",
        coral: "#4f46e5"
      },
      boxShadow: { card: "0 1px 2px rgba(15,15,15,.04), 0 8px 24px rgba(15,15,15,.06)" }
    }
  },
  plugins: []
};
export default config;

