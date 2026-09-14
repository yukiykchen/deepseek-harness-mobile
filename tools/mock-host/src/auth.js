// Browser-session authentication modeled on @deepseek-ai/dsh-client-connection
// (DSH >= 0.1.2): `GET /?token=<launch token>` mints an authority-bound cookie,
// every /api request and the /api/remote.mux upgrade must carry that cookie.
import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";

const COOKIE_PREFIX = "dsh-auth-";
const DAY_MS = 24 * 60 * 60 * 1000;

function base64url(buffer) {
  return Buffer.from(buffer).toString("base64").replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

function fromBase64url(value) {
  const padding = "=".repeat((4 - (value.length % 4)) % 4);
  return Buffer.from(value.replaceAll("-", "+").replaceAll("_", "/") + padding, "base64");
}

export function requestAuthority(headers) {
  const host = headers.host;
  if (!host) return undefined;
  try {
    return new URL(`http://${host}`).host;
  } catch {
    return undefined;
  }
}

export class BrowserAuth {
  constructor({ launchToken, maxAgeDays = 30 } = {}) {
    this.launchToken = launchToken ?? base64url(randomBytes(32));
    this.secret = randomBytes(32);
    this.maxAgeMs = maxAgeDays * DAY_MS;
  }

  authenticatedUrl(baseUrl) {
    const url = new URL(baseUrl);
    url.pathname = "/";
    url.search = `?token=${this.launchToken}`;
    return url.toString();
  }

  cookieName(authority) {
    return COOKIE_PREFIX + base64url(createHash("sha256").update(authority).digest());
  }

  mintCookie(authority) {
    const issuedAt = Date.now();
    const payload = { version: 1, authority, issuedAt, expiresAt: issuedAt + this.maxAgeMs };
    const body = base64url(Buffer.from(JSON.stringify(payload), "utf8"));
    const signature = base64url(createHmac("sha256", this.secret).update(body).digest());
    const name = this.cookieName(authority);
    const value = `v1.${body}.${signature}`;
    return {
      name,
      value,
      header: `${name}=${value}; Max-Age=${Math.floor(this.maxAgeMs / 1000)}; Path=/; HttpOnly; SameSite=Strict`,
    };
  }

  /** Handle `GET /?token=`; returns true when the response was written. */
  authorizeIndex(req, res) {
    const url = new URL(req.url ?? "/", "http://mock.internal");
    const tokens = url.searchParams.getAll("token");
    const authority = requestAuthority(req.headers);
    if (tokens.length === 1 && authority && this.tokenMatches(tokens[0])) {
      const cookie = this.mintCookie(authority);
      res.writeHead(303, {
        "cache-control": "no-store",
        "set-cookie": cookie.header,
        location: "/",
        "referrer-policy": "no-referrer",
      });
      res.end();
      return true;
    }
    if (this.isAuthenticated(req.headers)) {
      res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
      res.end("<!doctype html><title>dsh mock host</title><h1>dsh mock host</h1><p>authenticated</p>");
      return true;
    }
    res.writeHead(401, { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" });
    res.end("dsh web authentication required; reopen the URL printed by dsh web.\n");
    return true;
  }

  tokenMatches(actual) {
    const a = Buffer.from(actual, "utf8");
    const b = Buffer.from(this.launchToken, "utf8");
    return a.byteLength === b.byteLength && timingSafeEqual(a, b);
  }

  isAuthenticated(headers) {
    const authority = requestAuthority(headers);
    const rawCookie = headers.cookie;
    if (!authority || !rawCookie) return false;
    const name = this.cookieName(authority);
    let value;
    for (const segment of rawCookie.split(";")) {
      const at = segment.indexOf("=");
      if (at === -1 || segment.slice(0, at).trim() !== name) continue;
      value = segment.slice(at + 1).trim();
    }
    if (!value) return false;
    const [version, body, signature] = value.split(".");
    if (version !== "v1" || !body || !signature) return false;
    const expected = createHmac("sha256", this.secret).update(body).digest();
    const actual = fromBase64url(signature);
    if (actual.byteLength !== expected.byteLength || !timingSafeEqual(actual, expected)) return false;
    let payload;
    try {
      payload = JSON.parse(fromBase64url(body).toString("utf8"));
    } catch {
      return false;
    }
    const now = Date.now();
    return payload.authority === authority && payload.issuedAt <= now && payload.expiresAt > now;
  }
}
