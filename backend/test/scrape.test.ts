import { afterEach, describe, expect, it, vi } from "vitest";
import { buildApp } from "../src/app.js";
import { makeDiscountScrape } from "../src/scraper/discount.js";
import { mapResult } from "../src/scraper/mapper.js";
import type { LibraryResult, ScrapeFn } from "../src/scraper/types.js";

const TOKEN = "test-token-0123456789abcdef0123456789abcdef";
// A distinctive value we can grep logs/responses for — it must NEVER leak.
const SECRET_PW = "SENTINEL_SECRET_pw_zZ9";
const creds = { id: "012345678", password: SECRET_PW, num: "42" };
const validBody = { companyId: "discount" as const, credentials: creds };

/** A representative `israeli-bank-scrapers` success result. */
const librarySuccess: LibraryResult = {
  success: true,
  accounts: [
    {
      accountNumber: "12-345-6789",
      cardType: "bankIssued",
      txns: [
        {
          identifier: 987654,
          type: "normal",
          date: "2026-01-15T00:00:00.000Z",
          originalAmount: -50.25,
          chargedAmount: -50.25,
          description: "SUPER PHARM  TEL AVIV",
          status: "completed",
          category: "Health",
        },
        {
          type: "normal",
          date: "2026-02-01T00:00:00.000Z",
          originalAmount: 1000,
          chargedAmount: 1000,
          description: "SALARY",
          status: "completed",
        },
      ],
    },
  ],
};

const expectedDto = {
  accounts: [
    { externalId: "12-345-6789", label: "12-345-6789", institution: "Discount Bank", rawType: "bankIssued" },
  ],
  transactions: [
    {
      accountExternalId: "12-345-6789",
      rawDate: "2026-01-15T00:00:00.000Z",
      rawMerchant: "SUPER PHARM  TEL AVIV",
      rawAmount: "-50.25",
      rawDirection: null,
      rawCategory: "Health",
      reference: "987654",
    },
    {
      accountExternalId: "12-345-6789",
      rawDate: "2026-02-01T00:00:00.000Z",
      rawMerchant: "SALARY",
      rawAmount: "1000",
      rawDirection: null,
      rawCategory: null,
      reference: null,
    },
  ],
};

function authHeaders() {
  return { authorization: `Bearer ${TOKEN}`, "content-type": "application/json" };
}

describe("mapResult -> app DTO contract", () => {
  it("maps a library result to the exact ScrapedAccount/ScrapedTransaction shape", () => {
    expect(mapResult(librarySuccess)).toEqual(expectedDto);
  });

  it("emits a signed rawAmount with rawDirection null (mapper fallback)", () => {
    const dto = mapResult(librarySuccess);
    expect(dto.transactions[0]?.rawAmount).toBe("-50.25");
    expect(dto.transactions[0]?.rawDirection).toBeNull();
  });
});

describe("POST /scrape handler (mocked scraper — no Chrome/network)", () => {
  it("(1) a successful mock maps to the exact DTO JSON", async () => {
    const scrape: ScrapeFn = vi.fn(async () => librarySuccess);
    const app = buildApp({ authToken: TOKEN, scrape });
    await app.ready();
    try {
      const res = await app.inject({ method: "POST", url: "/scrape", headers: authHeaders(), payload: validBody });
      expect(res.statusCode).toBe(200);
      expect(res.json()).toEqual(expectedDto);
    } finally {
      await app.close();
    }
  });

  it("passes a default ~12-month startDate and the credentials to the seam", async () => {
    const scrape = vi.fn<ScrapeFn>(async () => librarySuccess);
    const app = buildApp({ authToken: TOKEN, scrape });
    await app.ready();
    try {
      await app.inject({ method: "POST", url: "/scrape", headers: authHeaders(), payload: validBody });
      expect(scrape).toHaveBeenCalledTimes(1);
      const arg = scrape.mock.calls[0]?.[0];
      expect(arg?.credentials).toEqual(creds);
      expect(arg?.startDate).toBeInstanceOf(Date);
      const monthsBack = (Date.now() - (arg?.startDate.getTime() ?? 0)) / (1000 * 60 * 60 * 24 * 30);
      expect(monthsBack).toBeGreaterThan(11);
      expect(monthsBack).toBeLessThan(13);
    } finally {
      await app.close();
    }
  });

  it("honours an explicit startDate from the request", async () => {
    const scrape = vi.fn<ScrapeFn>(async () => librarySuccess);
    const app = buildApp({ authToken: TOKEN, scrape });
    await app.ready();
    try {
      await app.inject({
        method: "POST",
        url: "/scrape",
        headers: authHeaders(),
        payload: { ...validBody, startDate: "2025-03-01" },
      });
      expect(scrape.mock.calls[0]?.[0]?.startDate.toISOString()).toBe("2025-03-01T00:00:00.000Z");
    } finally {
      await app.close();
    }
  });

  it("(3) maps invalid credentials to 401 invalid_credentials", async () => {
    const scrape: ScrapeFn = vi.fn(async () => ({ success: false, errorType: "INVALID_PASSWORD" }));
    const app = buildApp({ authToken: TOKEN, scrape });
    await app.ready();
    try {
      const res = await app.inject({ method: "POST", url: "/scrape", headers: authHeaders(), payload: validBody });
      expect(res.statusCode).toBe(401);
      expect(res.json()).toEqual({ error: "invalid_credentials" });
    } finally {
      await app.close();
    }
  });

  it("(3) maps a bank/network error to 502 network", async () => {
    const scrape: ScrapeFn = vi.fn(async () => ({ success: false, errorType: "GENERIC" }));
    const app = buildApp({ authToken: TOKEN, scrape });
    await app.ready();
    try {
      const res = await app.inject({ method: "POST", url: "/scrape", headers: authHeaders(), payload: validBody });
      expect(res.statusCode).toBe(502);
      expect(res.json()).toEqual({ error: "network" });
    } finally {
      await app.close();
    }
  });

  it("maps a 2FA requirement to a distinct 409 otp_required", async () => {
    const scrape: ScrapeFn = vi.fn(async () => ({ success: false, errorType: "TWO_FACTOR_RETRIEVER_MISSING" }));
    const app = buildApp({ authToken: TOKEN, scrape });
    await app.ready();
    try {
      const res = await app.inject({ method: "POST", url: "/scrape", headers: authHeaders(), payload: validBody });
      expect(res.statusCode).toBe(409);
      expect(res.json()).toEqual({ error: "otp_required" });
    } finally {
      await app.close();
    }
  });

  it("maps an unexpected throw (e.g. Chrome launch crash) to a coarse 500", async () => {
    const scrape: ScrapeFn = vi.fn(async () => {
      throw new Error("boom: /path/to/chrome not found");
    });
    const app = buildApp({ authToken: TOKEN, scrape });
    await app.ready();
    try {
      const res = await app.inject({ method: "POST", url: "/scrape", headers: authHeaders(), payload: validBody });
      expect(res.statusCode).toBe(500);
      expect(res.json()).toEqual({ error: "internal_error" });
    } finally {
      await app.close();
    }
  });

  it("enforces an overall scrape timeout (504) so a hung scrape can't wedge", async () => {
    const scrape: ScrapeFn = () => new Promise(() => {}); // never resolves
    const app = buildApp({ authToken: TOKEN, scrape, scrapeTimeoutMs: 30 });
    await app.ready();
    try {
      const res = await app.inject({ method: "POST", url: "/scrape", headers: authHeaders(), payload: validBody });
      expect(res.statusCode).toBe(504);
      expect(res.json()).toEqual({ error: "timeout" });
    } finally {
      await app.close();
    }
  });
});

describe("(2) credentials never leak to logs or error bodies", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("never writes the password to stdout logs nor into any response body", async () => {
    const writes: string[] = [];
    vi.spyOn(process.stdout, "write").mockImplementation((chunk: unknown): boolean => {
      writes.push(String(chunk));
      return true;
    });

    // Exercise both a success and a failure path (both log).
    const okApp = buildApp({ authToken: TOKEN, scrape: async () => librarySuccess });
    const failApp = buildApp({ authToken: TOKEN, scrape: async () => ({ success: false, errorType: "INVALID_PASSWORD" }) });
    await okApp.ready();
    await failApp.ready();
    try {
      const okRes = await okApp.inject({ method: "POST", url: "/scrape", headers: authHeaders(), payload: validBody });
      const failRes = await failApp.inject({ method: "POST", url: "/scrape", headers: authHeaders(), payload: validBody });

      const allLogs = writes.join("");
      // Guard against a vacuous assertion: we must actually have captured logs.
      expect(writes.length).toBeGreaterThan(0);
      expect(allLogs).not.toContain(SECRET_PW);
      expect(allLogs).not.toContain(creds.id);
      expect(allLogs).not.toContain(TOKEN);
      expect(okRes.body).not.toContain(SECRET_PW);
      expect(failRes.body).not.toContain(SECRET_PW);
    } finally {
      await okApp.close();
      await failApp.close();
    }
  });
});

describe("(4) browser teardown always runs (SECURITY.md R6)", () => {
  it("closes the browser on a successful scrape", async () => {
    let closed = 0;
    const fn = makeDiscountScrape({
      launch: async () => ({ close: async () => void closed++ }),
      createScraper: () => ({ scrape: async () => ({ success: true, accounts: [] }) }),
    });
    await fn({ credentials: creds, startDate: new Date() });
    expect(closed).toBe(1);
  });

  it("closes the browser even when the scrape throws", async () => {
    let closed = 0;
    const fn = makeDiscountScrape({
      launch: async () => ({ close: async () => void closed++ }),
      createScraper: () => ({
        scrape: async () => {
          throw new Error("mid-scrape failure");
        },
      }),
    });
    await expect(fn({ credentials: creds, startDate: new Date() })).rejects.toThrow("mid-scrape failure");
    expect(closed).toBe(1);
  });

  it("still closes the browser when createScraper itself throws", async () => {
    let closed = 0;
    const fn = makeDiscountScrape({
      launch: async () => ({ close: async () => void closed++ }),
      createScraper: () => {
        throw new Error("scraper init failed");
      },
    });
    await expect(fn({ credentials: creds, startDate: new Date() })).rejects.toThrow("scraper init failed");
    expect(closed).toBe(1);
  });
});
