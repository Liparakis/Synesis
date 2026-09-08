module.exports = {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: "var(--color-ink)",
        panel: "var(--color-panel)",
        raised: "var(--color-raised)",
        line: "var(--color-line)",
        copy: "var(--color-copy)",
        muted: "var(--color-muted)",
        accent: "var(--color-accent)",
      },
      borderRadius: {
        control: "var(--radius-control)",
        panel: "var(--radius-panel)",
        pill: "var(--radius-pill)",
      },
    },
  },
  plugins: [],
};
