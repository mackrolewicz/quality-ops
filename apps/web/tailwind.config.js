/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        canvas: "#0F1117",
        surface: { DEFAULT: "#1A1D27", raised: "#22252F" },
        line: { DEFAULT: "#2A2D3A", strong: "#3A3E4C" },
        primary: "#F3F4F6",
        secondary: "#C7CAD1",
        muted: "#9CA3AF",
        subtle: "#6B7280",
        accent: {
          DEFAULT: "#6366F1",
          hover: "#7C7FF2",
          subtle: "rgba(99,102,241,0.10)",
        },
        "accent-from": "#6366F1",
        "accent-to": "#8B5CF6",
        status: {
          passed: "#22C55E",
          failed: "#EF4444",
          running: "#F59E0B",
          pending: "#6B7280",
          skipped: "#3B82F6",
          flaky: "#F59E0B",
          cancelled: "#6B7280",
        },
        role: {
          owner: "#8B5CF6",
          admin: "#3B82F6",
          member: "#22C55E",
          viewer: "#6B7280",
        },
      },
      borderRadius: { md: "6px", lg: "8px" },
      fontFamily: {
        sans: ["Inter", "Geist", "system-ui", "sans-serif"],
        mono: ['"JetBrains Mono"', "ui-monospace", "SFMono-Regular", "monospace"],
      },
      backgroundImage: {
        "gradient-accent": "linear-gradient(135deg, #6366F1, #8B5CF6)",
      },
    },
  },
  plugins: [],
};
