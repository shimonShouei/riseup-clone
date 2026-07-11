import type { UploadRequest, UploadResult, Uploader } from "./upload.js";

/**
 * Dropbox implementation of the [Uploader] seam — the ONE concrete cloud backend
 * shipped by default. It uses only the global `fetch` (Node >=18), so importing this
 * module pulls in no heavy dependency and does nothing until `upload()` is called.
 *
 * How the STABLE URL is produced:
 *  1. Upload the CSV bytes to a FIXED path with `mode: "overwrite"`, so every run
 *     replaces the file in place (the path never changes).
 *  2. Ensure a shared link exists for that path — create it once, or reuse the
 *     existing one on later runs (Dropbox keeps the same link across overwrites).
 *  3. Rewrite the link's `dl=0` to `dl=1` so it is a DIRECT download the app can GET.
 *
 * Because the path and its shared link are stable, the user pastes the URL into the
 * app exactly once; subsequent uploads refresh the content behind the same URL.
 *
 * The pure helpers (path normalization, arg building, URL rewriting, error mapping)
 * are exported and unit-tested; the network calls are runtime-only.
 */

/** Default fixed path for the statement in the user's Dropbox. */
export const DEFAULT_DROPBOX_PATH = "/discount-statement.csv";

/** Dropbox upload configuration, sourced from `.env`. The token is a secret. */
export interface DropboxConfig {
  readonly token: string;
  readonly path: string;
}

/** Ensure a Dropbox path is absolute (leading `/`); fall back to the default. */
export function normalizeDropboxPath(raw: string | undefined): string {
  const trimmed = raw?.trim();
  if (!trimmed) return DEFAULT_DROPBOX_PATH;
  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}

/**
 * Read Dropbox config from `env`. Requires `DROPBOX_TOKEN`; `DROPBOX_PATH` overrides
 * the default path. Throws a clear, secret-free message if the token is missing —
 * the token value itself is never included in any message.
 */
export function loadDropboxConfig(env: NodeJS.ProcessEnv = process.env): DropboxConfig {
  const token = env.DROPBOX_TOKEN?.trim();
  if (!token) {
    throw new Error(
      "Missing DROPBOX_TOKEN in .env. Create a Dropbox app + access token and set DROPBOX_TOKEN " +
        "(and optionally DROPBOX_PATH). See scraper-cli/README.md, 'Upload mode'.",
    );
  }
  return { token, path: normalizeDropboxPath(env.DROPBOX_PATH) };
}

/** Build the `Dropbox-API-Arg` header value for the content-upload endpoint. */
export function dropboxUploadArg(path: string): string {
  // `overwrite` keeps the path (and therefore the shared link) stable across runs.
  return JSON.stringify({ path, mode: "overwrite", mute: true });
}

/**
 * Rewrite a Dropbox shared link into a stable DIRECT-download URL by forcing
 * `dl=1` (Dropbox hands out `dl=0` "preview" links). Preserves the rest of the URL,
 * including the `rlkey` query param that authorizes the link.
 */
export function toDirectDownloadUrl(sharedUrl: string): string {
  const url = new URL(sharedUrl);
  url.searchParams.set("dl", "1");
  return url.toString();
}

/**
 * Map a Dropbox HTTP status to a clear, actionable, secret-free message. We never
 * echo the raw response body (it can reflect the request) — only the status drives
 * the message.
 */
export function mapDropboxError(status: number): string {
  switch (status) {
    case 400:
      return "Dropbox rejected the request (400). Check DROPBOX_PATH is a valid absolute path.";
    case 401:
      return "Dropbox rejected the token (401). Regenerate DROPBOX_TOKEN in your Dropbox app and update .env.";
    case 403:
      return "Dropbox denied access (403). Check the app's scopes: files.content.write and sharing.write.";
    case 409:
      return "Dropbox reported a conflict (409) it could not resolve.";
    case 429:
      return "Dropbox rate-limited the upload (429). Wait a moment and retry.";
    default:
      if (status >= 500) return `Dropbox had a server error (${status}). Try again later.`;
      return `Dropbox upload failed (HTTP ${status}).`;
  }
}

const CONTENT_UPLOAD_URL = "https://content.dropboxapi.com/2/files/upload";
const CREATE_SHARED_LINK_URL =
  "https://api.dropboxapi.com/2/sharing/create_shared_link_with_settings";
const LIST_SHARED_LINKS_URL = "https://api.dropboxapi.com/2/sharing/list_shared_links";

/** Dropbox [Uploader]: upload bytes to a fixed path, return a stable `dl=1` link. */
export class DropboxUploader implements Uploader {
  constructor(private readonly config: DropboxConfig) {}

  async upload(req: UploadRequest): Promise<UploadResult> {
    await this.putContent(req);
    const sharedUrl = await this.ensureSharedLink();
    return { downloadUrl: toDirectDownloadUrl(sharedUrl) };
  }

  /** POST the file bytes to the content endpoint, overwriting the fixed path. */
  private async putContent(req: UploadRequest): Promise<void> {
    const res = await fetch(CONTENT_UPLOAD_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.token}`,
        "Dropbox-API-Arg": dropboxUploadArg(this.config.path),
        "Content-Type": "application/octet-stream",
      },
      body: req.content,
    });
    if (!res.ok) {
      throw new Error(mapDropboxError(res.status));
    }
  }

  /**
   * Return a shared link for the fixed path, creating it on first run and reusing
   * the existing one thereafter (Dropbox returns 409 when a link already exists).
   */
  private async ensureSharedLink(): Promise<string> {
    const created = await fetch(CREATE_SHARED_LINK_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ path: this.config.path }),
    });
    if (created.ok) {
      const data = (await created.json()) as { url?: string };
      if (data.url) return data.url;
      throw new Error("Dropbox created a shared link but returned no URL.");
    }
    // A link for this path already exists — Dropbox signals this with 409; reuse it.
    if (created.status === 409) {
      return this.existingSharedLink();
    }
    throw new Error(mapDropboxError(created.status));
  }

  /** Fetch the already-created shared link for the fixed path. */
  private async existingSharedLink(): Promise<string> {
    const res = await fetch(LIST_SHARED_LINKS_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ path: this.config.path, direct_only: true }),
    });
    if (!res.ok) {
      throw new Error(mapDropboxError(res.status));
    }
    const data = (await res.json()) as { links?: Array<{ url?: string }> };
    const url = data.links?.[0]?.url;
    if (!url) {
      throw new Error("Dropbox reported the shared link exists but did not return it.");
    }
    return url;
  }
}
