import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
import { loadServeConfig } from "../src/config.js";
import {
  createStatementServer,
  pickLanIpv4s,
  reachableStatementUrls,
  routeRequest,
} from "../src/serve.js";

/**
 * All network-free except the loopback smoke test at the bottom, which binds
 * 127.0.0.1:0 (an ephemeral port) and fetches over the loopback interface — no
 * bank, no credentials, no Chrome.
 */

describe("routeRequest", () => {
  const csv = "Date,Description,Debit,Credit,Balance,Reference\r\n";

  it("serves the CSV snapshot on GET /statement.csv as text/csv", () => {
    const res = routeRequest("GET", "/statement.csv", csv);
    expect(res.status).toBe(200);
    expect(res.contentType).toContain("text/csv");
    expect(res.body).toBe(csv);
  });

  it("ignores a query string on the statement route", () => {
    const res = routeRequest("GET", "/statement.csv?t=123", csv);
    expect(res.status).toBe(200);
    expect(res.body).toBe(csv);
  });

  it("returns ok JSON on GET /health", () => {
    const res = routeRequest("GET", "/health", csv);
    expect(res.status).toBe(200);
    expect(res.contentType).toContain("application/json");
    expect(res.body).toBe('{"status":"ok"}');
  });

  it("404s any other path", () => {
    expect(routeRequest("GET", "/", csv).status).toBe(404);
    expect(routeRequest("GET", "/statement", csv).status).toBe(404);
    expect(routeRequest("GET", "/../etc/passwd", csv).status).toBe(404);
  });

  it("405s a non-GET/HEAD method", () => {
    expect(routeRequest("POST", "/statement.csv", csv).status).toBe(405);
    expect(routeRequest("DELETE", "/health", csv).status).toBe(405);
  });

  it("allows HEAD (routes like GET)", () => {
    expect(routeRequest("HEAD", "/statement.csv", csv).status).toBe(200);
  });
});

describe("pickLanIpv4s", () => {
  it("keeps non-internal IPv4s and drops loopback + IPv6 (modern family strings)", () => {
    const ifaces = {
      lo: [{ family: "IPv4", internal: true, address: "127.0.0.1" }],
      eth0: [
        { family: "IPv4", internal: false, address: "192.168.1.23" },
        { family: "IPv6", internal: false, address: "fe80::1" },
      ],
      wlan0: [{ family: "IPv4", internal: false, address: "10.0.0.5" }],
    };
    expect(pickLanIpv4s(ifaces)).toEqual(["192.168.1.23", "10.0.0.5"]);
  });

  it("handles the legacy numeric family (4) representation", () => {
    const ifaces = {
      eth0: [{ family: 4, internal: false, address: "192.168.0.42" }],
    };
    expect(pickLanIpv4s(ifaces)).toEqual(["192.168.0.42"]);
  });

  it("tolerates undefined interface entries", () => {
    expect(pickLanIpv4s({ ghost: undefined })).toEqual([]);
  });
});

describe("reachableStatementUrls", () => {
  it("lists every LAN IPv4 when bound to the wildcard host", () => {
    expect(reachableStatementUrls("0.0.0.0", 8788, ["192.168.1.23", "10.0.0.5"])).toEqual([
      "http://192.168.1.23:8788/statement.csv",
      "http://10.0.0.5:8788/statement.csv",
    ]);
  });

  it("falls back to loopback when no LAN IP is available", () => {
    expect(reachableStatementUrls("0.0.0.0", 8788, [])).toEqual([
      "http://127.0.0.1:8788/statement.csv",
    ]);
  });

  it("uses the concrete host verbatim when not a wildcard", () => {
    expect(reachableStatementUrls("192.168.5.5", 9000, ["192.168.1.23"])).toEqual([
      "http://192.168.5.5:9000/statement.csv",
    ]);
  });
});

describe("loadServeConfig", () => {
  it("defaults to 0.0.0.0:8788", () => {
    expect(loadServeConfig({})).toEqual({ host: "0.0.0.0", port: 8788 });
  });

  it("reads SERVE_HOST / SERVE_PORT overrides", () => {
    expect(loadServeConfig({ SERVE_HOST: "127.0.0.1", SERVE_PORT: "9999" })).toEqual({
      host: "127.0.0.1",
      port: 9999,
    });
  });

  it("rejects a malformed port", () => {
    expect(() => loadServeConfig({ SERVE_PORT: "0" })).toThrow();
    expect(() => loadServeConfig({ SERVE_PORT: "70000" })).toThrow();
    expect(() => loadServeConfig({ SERVE_PORT: "abc" })).toThrow();
  });
});

describe("createStatementServer (loopback smoke test)", () => {
  const csv = "Date,Description,Debit,Credit,Balance,Reference\r\n01/02/2026,SALARY,,14000.00,,\r\n";
  let close: (() => Promise<void>) | undefined;

  afterEach(async () => {
    if (close) {
      await close();
      close = undefined;
    }
  });

  async function start(): Promise<string> {
    const server = createStatementServer(csv);
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
    close = () => new Promise<void>((resolve) => server.close(() => resolve()));
    const { port } = server.address() as AddressInfo;
    return `http://127.0.0.1:${port}`;
  }

  it("serves the CSV over HTTP with a text/csv content type", async () => {
    const base = await start();
    const res = await fetch(`${base}/statement.csv`);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("text/csv");
    expect(await res.text()).toBe(csv);
  });

  it("serves the health check and 404s unknown paths", async () => {
    const base = await start();
    const health = await fetch(`${base}/health`);
    expect(health.status).toBe(200);
    expect(await health.json()).toEqual({ status: "ok" });

    const missing = await fetch(`${base}/nope`);
    expect(missing.status).toBe(404);
  });
});
