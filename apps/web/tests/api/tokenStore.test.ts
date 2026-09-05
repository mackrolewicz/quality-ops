import { tokenStore } from "../../src/api/tokenStore";

describe("tokenStore", () => {
  it("round-trips set / get / clear", () => {
    tokenStore.set({ accessToken: "a", refreshToken: "r" });
    expect(tokenStore.getAccessToken()).toBe("a");
    expect(tokenStore.getRefreshToken()).toBe("r");

    tokenStore.clear();
    expect(tokenStore.getAccessToken()).toBeNull();
    expect(tokenStore.getRefreshToken()).toBeNull();
  });

  it("never touches web storage", () => {
    tokenStore.set({ accessToken: "a", refreshToken: "r" });
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });
});
