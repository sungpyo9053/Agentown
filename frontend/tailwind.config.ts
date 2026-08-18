import type { Config } from "tailwindcss";

// Nike-style system (see DESIGN-nike.md): near-monochrome chrome, pill CTAs,
// zero elevation. Color is reserved for photography and semantic signal only.
// NOTE: legacy token names (ink / cream / leaf / coral) are kept so existing
// pages don't need a mass rename — only their values changed.
const config: Config = {
  content: ["./src/**/*.{js,ts,jsx,tsx,mdx}"],
  theme: {
    extend: {
      colors: {
        ink: "#111111",          // Nike black — the brand's only "color"
        cream: "#f5f5f5",        // soft-cloud, the universal surface gray
        cloud: "#f5f5f5",
        coral: "#111111",        // accent slot collapses to ink (no decorative color)
        leaf: "#007d48",         // success
        sale: "#d30005",
        charcoal: "#39393b",
        ash: "#4b4b4d",
        mute: "#707072",
        stone: "#9e9ea0",
        hairline: "#cacacb",
        "hairline-soft": "#e5e5e5",
      },
      borderRadius: { pill: "30px" },
      // Flat by design: the system has no drop-shadow elevation in its chrome.
      boxShadow: { card: "none", hairline: "inset 0 -1px 0 #e5e5e5" },
    },
  },
  plugins: [],
};
export default config;
