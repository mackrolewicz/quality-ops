import { formatDuration, formatRelativeTime } from "../../src/lib/time";

describe("formatRelativeTime", () => {
  it("returns 'just now' for very recent times", () => {
    expect(formatRelativeTime(new Date().toISOString())).toBe("just now");
  });

  it("formats minutes, hours and days", () => {
    const now = Date.now();
    expect(
      formatRelativeTime(new Date(now - 5 * 60_000).toISOString()),
    ).toBe("5m ago");
    expect(
      formatRelativeTime(new Date(now - 3 * 3_600_000).toISOString()),
    ).toBe("3h ago");
    expect(
      formatRelativeTime(new Date(now - 2 * 86_400_000).toISOString()),
    ).toBe("2d ago");
  });
});

describe("formatDuration", () => {
  it("formats sub-second values in ms", () => {
    expect(formatDuration(456)).toBe("456ms");
  });

  it("formats values over a second with one decimal", () => {
    expect(formatDuration(1234)).toBe("1.2s");
  });

  it("returns a dash for null", () => {
    expect(formatDuration(null)).toBe("—");
  });
});
