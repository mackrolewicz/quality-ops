---
paths:
  - "**/*.ts"
  - "**/*.tsx"
---
# React / TypeScript Rules

- Strict TypeScript: no `any` without a comment justifying it.
- Functional components only. Named exports, no default exports.
- TanStack Query for ALL server state. No manual `useEffect` + `fetch`.
- Tailwind CSS for styling. No CSS modules, no styled-components.
- Use `clsx` for conditional class names.
- Custom hooks for reusable logic, extract early.
- `data-testid` attributes on interactive elements for Playwright.
- Forms use React Hook Form.
- Import order: React → third-party → local hooks/api → local components → types.
- Components over 100 lines should be split.
- No `dangerouslySetInnerHTML` without sanitization.
