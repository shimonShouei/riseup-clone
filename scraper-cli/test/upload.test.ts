import { describe, expect, it } from "vitest";
import type { CliConfig } from "../src/config.js";
import { resultToCsv } from "../src/csv.js";
import {
  DEFAULT_DROPBOX_PATH,
  dropboxUploadArg,
  loadDropboxConfig,
  mapDropboxError,
  normalizeDropboxPath,
  toDirectDownloadUrl,
} from "../src/dropbox.js";
import type { LibraryResult, ScrapeFn } from "../src/types.js";
import { CSV_CONTENT_TYPE, runUpload, type UploadRequest, type Uploader } from "../src/upload.js";

/**
 * All pure/injected — no real Dropbox API and no real bank. `runUpload` is exercised
 * with a fake scrape (library-shaped result) and a fake [Uploader] that records what
 * it was handed. The real `DropboxUploader.upload()` (network) is runtime-only.
 */

const librarySuccess: LibraryResult = {
  success: true,
  accounts: [
    {
      accountNumber: "123",
      balance: 15234.56,
      txns: [
        { identifier: "a1", date: "2026-01-15", chargedAmount: -50.25, description: "SUPER PHARM" },
        { identifier: "a2", date: "2026-02-01", chargedAmount: 14000, description: "SALARY" },
      ],
    },
  ],
};

/** A stub CliConfig so `runUpload` needs no `.env`/credentials. */
const fakeConfig: CliConfig = {
  credentials: { id: "x", password: "y", num: "z" },
  startDate: new Date("2025-01-01T00:00:00.000Z"),
  outPath: "./out.csv",
};

/** An [Uploader] that records the request and returns a canned URL (or throws). */
class FakeUploader implements Uploader {
  lastRequest: UploadRequest | undefined;
  callCount = 0;
  constructor(
    private readonly url = "https://www.dropbox.com/scl/fi/abc/f.csv?rlkey=k&dl=1",
    private readonly error?: Error,
  ) {}
  async upload(req: UploadRequest): Promise<{ downloadUrl: string }> {
    this.callCount++;
    this.lastRequest = req;
    if (this.error) throw this.error;
    return { downloadUrl: this.url };
  }
}

const okScrape: ScrapeFn = async () => librarySuccess;

describe("runUpload", () => {
  it("hands the uploader the CSV bytes with the csv content type and prints the URL", async () => {
    const uploader = new FakeUploader();
    const logs: string[] = [];

    const code = await runUpload(okScrape, {
      loadConfigFn: () => fakeConfig,
      makeUploader: () => uploader,
      log: (m) => logs.push(m),
      logError: () => {},
    });

    expect(code).toBe(0);
    expect(uploader.callCount).toBe(1);
    // The uploaded bytes decode back to exactly the CSV the csv module builds.
    const sent = new TextDecoder().decode(uploader.lastRequest!.content);
    expect(sent).toBe(resultToCsv(librarySuccess));
    expect(uploader.lastRequest!.contentType).toBe(CSV_CONTENT_TYPE);
    // The stable URL is printed for the user to paste into the app.
    expect(logs.join("\n")).toContain("https://www.dropbox.com/scl/fi/abc/f.csv?rlkey=k&dl=1");
  });

  it("returns 1 and never uploads when the scrape fails", async () => {
    const uploader = new FakeUploader();
    const failScrape: ScrapeFn = async () => ({ success: false, errorType: "INVALID_PASSWORD" });

    const code = await runUpload(failScrape, {
      loadConfigFn: () => fakeConfig,
      makeUploader: () => uploader,
      log: () => {},
      logError: () => {},
    });

    expect(code).toBe(1);
    expect(uploader.callCount).toBe(0);
  });

  it("returns 1 when the uploader throws (mapped error)", async () => {
    const uploader = new FakeUploader(undefined, new Error("Dropbox rejected the token (401)."));
    const errors: string[] = [];

    const code = await runUpload(okScrape, {
      loadConfigFn: () => fakeConfig,
      makeUploader: () => uploader,
      log: () => {},
      logError: (m) => errors.push(m),
    });

    expect(code).toBe(1);
    expect(errors.join("\n")).toContain("Dropbox rejected the token (401).");
  });

  it("returns 1 and never scrapes when building the uploader fails (bad config)", async () => {
    let scraped = false;
    const trackingScrape: ScrapeFn = async () => {
      scraped = true;
      return librarySuccess;
    };

    const code = await runUpload(trackingScrape, {
      loadConfigFn: () => fakeConfig,
      makeUploader: () => {
        throw new Error("Missing DROPBOX_TOKEN in .env.");
      },
      log: () => {},
      logError: () => {},
    });

    expect(code).toBe(1);
    expect(scraped).toBe(false);
  });
});

describe("normalizeDropboxPath", () => {
  it("defaults when empty or missing", () => {
    expect(normalizeDropboxPath(undefined)).toBe(DEFAULT_DROPBOX_PATH);
    expect(normalizeDropboxPath("   ")).toBe(DEFAULT_DROPBOX_PATH);
  });

  it("adds a leading slash to a relative path", () => {
    expect(normalizeDropboxPath("statements/discount.csv")).toBe("/statements/discount.csv");
  });

  it("keeps an already-absolute path (trimmed)", () => {
    expect(normalizeDropboxPath("  /a/b.csv  ")).toBe("/a/b.csv");
  });
});

describe("loadDropboxConfig", () => {
  it("throws a secret-free error when the token is missing", () => {
    expect(() => loadDropboxConfig({})).toThrow(/DROPBOX_TOKEN/);
  });

  it("reads the token and normalizes the path", () => {
    expect(loadDropboxConfig({ DROPBOX_TOKEN: "tok", DROPBOX_PATH: "x.csv" })).toEqual({
      token: "tok",
      path: "/x.csv",
    });
  });

  it("defaults the path when only the token is set", () => {
    expect(loadDropboxConfig({ DROPBOX_TOKEN: "tok" })).toEqual({
      token: "tok",
      path: DEFAULT_DROPBOX_PATH,
    });
  });
});

describe("dropboxUploadArg", () => {
  it("targets the given path and overwrites in place", () => {
    expect(JSON.parse(dropboxUploadArg("/discount-statement.csv"))).toEqual({
      path: "/discount-statement.csv",
      mode: "overwrite",
      mute: true,
    });
  });
});

describe("toDirectDownloadUrl", () => {
  it("flips dl=0 to dl=1, preserving rlkey", () => {
    expect(
      toDirectDownloadUrl("https://www.dropbox.com/scl/fi/abc/f.csv?rlkey=key123&dl=0"),
    ).toBe("https://www.dropbox.com/scl/fi/abc/f.csv?rlkey=key123&dl=1");
  });

  it("adds dl=1 when absent", () => {
    expect(toDirectDownloadUrl("https://www.dropbox.com/scl/fi/abc/f.csv?rlkey=key123")).toBe(
      "https://www.dropbox.com/scl/fi/abc/f.csv?rlkey=key123&dl=1",
    );
  });
});

describe("mapDropboxError", () => {
  it("maps common statuses to clear, secret-free messages", () => {
    expect(mapDropboxError(401)).toContain("token");
    expect(mapDropboxError(403)).toContain("scopes");
    expect(mapDropboxError(429)).toContain("rate-limited");
    expect(mapDropboxError(500)).toContain("server error");
    expect(mapDropboxError(418)).toContain("HTTP 418");
  });

  it("detects a missing-scope 400 from the body and names the real fix", () => {
    const body =
      'Error in call to API function "files/upload": Your app (ID: 123) is not ' +
      "permitted to access this endpoint because it does not have the required " +
      "scope 'files.content.write'.";
    const msg = mapDropboxError(400, body);
    expect(msg).toContain("files.content.write");
    expect(msg).toContain("regenerate DROPBOX_TOKEN");
    // Never echoes the raw body (which can reflect the request).
    expect(msg).not.toContain("API function");
  });
});
