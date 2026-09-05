/* eslint-env node */
module.exports = {
  root: true,
  parser: "@typescript-eslint/parser",
  parserOptions: {
    ecmaVersion: 2022,
    sourceType: "module",
    project: false,
    ecmaFeatures: { jsx: true },
  },
  env: {
    browser: true,
    es2022: true,
  },
  extends: [
    "eslint:recommended",
    "plugin:@typescript-eslint/recommended",
    "plugin:react-hooks/recommended",
  ],
  plugins: ["react-refresh"],
  ignorePatterns: ["dist", "coverage", "playwright-report", "test-results"],
  rules: {
    "@typescript-eslint/no-explicit-any": "error",
    "no-console": ["warn", { allow: ["error", "warn"] }],
    "react-refresh/only-export-components": [
      "warn",
      { allowConstantExport: true },
    ],
    "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
  },
  overrides: [
    {
      files: ["tests/**", "e2e/**"],
      env: { node: true },
      globals: {
        vi: "readonly",
        describe: "readonly",
        it: "readonly",
        test: "readonly",
        expect: "readonly",
        beforeEach: "readonly",
        afterEach: "readonly",
        beforeAll: "readonly",
        afterAll: "readonly",
      },
      rules: {
        "@typescript-eslint/no-explicit-any": "warn",
        "react-refresh/only-export-components": "off",
      },
    },
  ],
};
