import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.js";
import { tokensMatch } from "../src/auth.js";
import type { ScrapeFn } from "../src/scraper/types.js";

// A dummy, non-secret token that satisfies the length rule. NOT a real credential.
const TOKEN = "test-token-0123456789abcdef0123456789abcdef";

// A fake scraper so /scrape never touches Chrome/network in these tests. The
// detailed mapping/security assertions live in scrape.test.ts.
const fakeScrape: ScrapeFn = async () => ({ success: true, accounts: [] });

const validBody = {
  companyId: "discount",
  credentials: { id: "012345678", password: "hunter2", num: "42" },
};

describe("bearer auth + validation (SECURITY.md R1, R4)", () => {
  const app = buildApp({ authToken: TOKEN, scrape: fakeScrape });

  beforeAll(async () => {
    await app.ready();
  });
  afterAll(async () => {
    await app.close();
  });

  it("GET /health needs no auth and reveals nothing sensitive", async () => {
    const res = await app.inject({ method: "GET", url: "/health" });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ status: "ok" });
  });

  it("POST /scrape without a token -> 401", async () => {
    const res = await app.inject({ method: "POST", url: "/scrape", payload: validBody });
    expect(res.statusCode).toBe(401);
    expect(res.json()).toEqual({ error: "unauthorized" });
  });

  it("POST /scrape with a wrong token -> 401", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/scrape",
      headers: { authorization: "Bearer definitely-not-the-token" },
      payload: validBody,
    });
    expect(res.statusCode).toBe(401);
  });

  it("POST /scrape with a malformed Authorization header -> 401", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/scrape",
      headers: { authorization: TOKEN }, // missing "Bearer " prefix
      payload: validBody,
    });
    expect(res.statusCode).toBe(401);
  });

  it("POST /scrape with the right token + valid body -> 200 (mapped result)", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/scrape",
      headers: { authorization: `Bearer ${TOKEN}` },
      payload: validBody,
    });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ accounts: [], transactions: [] });
  });

  it("auth runs BEFORE validation: no token + bad body -> 401 (not 400)", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/scrape",
      payload: { junk: true },
    });
    expect(res.statusCode).toBe(401);
  });

  it("authenticated but non-allow-listed companyId -> 400 (R4)", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/scrape",
      headers: { authorization: `Bearer ${TOKEN}` },
      payload: { companyId: "hapoalim", credentials: { id: "1", password: "2", num: "3" } },
    });
    expect(res.statusCode).toBe(400);
  });

  it("authenticated but body with extra properties -> 400 (R4 additionalProperties)", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/scrape",
      headers: { authorization: `Bearer ${TOKEN}` },
      payload: { ...validBody, evilUrl: "http://attacker.example" },
    });
    expect(res.statusCode).toBe(400);
  });
});

describe("constant-time token comparison", () => {
  it("matches identical tokens", () => {
    expect(tokensMatch(TOKEN, TOKEN)).toBe(true);
  });
  it("rejects different tokens", () => {
    expect(tokensMatch(TOKEN, `${TOKEN}x`)).toBe(false);
    expect(tokensMatch("", TOKEN)).toBe(false);
  });
});
