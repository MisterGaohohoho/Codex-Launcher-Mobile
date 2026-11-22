const headers = { "Content-Type": "application/json; charset=utf-8", "X-Content-Type-Options": "nosniff", "Cache-Control": "no-store" };

export async function handle(request, env, upstream = fetch) {
  if (!["GET", "HEAD"].includes(request.method)) return new Response(null, { status: 405, headers: { ...headers, Allow: "GET, HEAD" } });
  const repo = env.GITHUB_REPOSITORY;
  if (!/^[\w-]+\/[\w.-]+$/.test(repo ?? "")) return Response.json({ error: "Update service is not configured" }, { status: 503, headers });
  const url = new URL(request.url);
  const release = `https://github.com/${repo}/releases/latest`;
  if (url.pathname === "/" || url.pathname === "/releases") return Response.redirect(release, 302);
  if (url.pathname === "/download") return Response.redirect(`${release}/download/launcher-mobile.apk`, 302);
  if (url.pathname === "/healthz") return Response.json({ ok: true, repository: repo }, { headers });
  if (url.pathname !== "/v1/android/stable") return Response.json({ error: "Not found" }, { status: 404, headers });
  try {
    const response = await upstream(`${release}/download/update-manifest.json`, { headers: { Accept: "application/json", "User-Agent": "Launcher-Mobile-Updates" }, redirect: "follow", cf: { cacheTtl: 60, cacheEverything: true }, signal: AbortSignal.timeout(10000) });
    if (!response.ok || Number(response.headers.get("content-length")) > 128 * 1024) throw new Error("No signed release");
    const reader = response.body.getReader();
    const chunks = []; let size = 0;
    try {
      while (true) {
        const { value, done } = await reader.read(); if (done) break;
        size += value.byteLength;
        if (size > 128 * 1024) { await reader.cancel(); throw new Error("Manifest too large"); }
        chunks.push(value);
      }
    } finally { reader.releaseLock(); }
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    const text = new TextDecoder().decode(bytes);
    const manifest = JSON.parse(text);
    // The APK verifies the detached ECDSA signature against its embedded public key.
    // Relay the original bytes; never reserialize the signed payload.
    if (typeof manifest.payload !== "string" || typeof manifest.signature !== "string" || !manifest.payload || !manifest.signature) throw new Error("Invalid signed manifest");
    return new Response(request.method === "HEAD" ? null : text, { headers: { ...headers, "Cache-Control": "public, max-age=60, stale-if-error=300", ETag: response.headers.get("etag") ?? `"${size}"` } });
  } catch {
    return Response.json({ error: "No signed Android release is available yet. Please try again later." }, { status: 503, headers: { ...headers, "Retry-After": "60" } });
  }
}
export default { fetch: (request, env) => handle(request, env) };
