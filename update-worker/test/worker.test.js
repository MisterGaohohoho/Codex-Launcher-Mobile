import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
const env = { GITHUB_REPOSITORY: "example/mobile" };
const request = (path, method = "GET") => new Request(`https://updates.example.test${path}`, { method });
test("relays signed bytes and only fetches the configured public release", async () => {
  const body = '{"payload":"fixture","signature":"fixture-signature"}';
  const response = await handle(request("/v1/android/stable?repository=attacker/repo"), env, async url => {
    assert.equal(url, "https://github.com/example/mobile/releases/latest/download/update-manifest.json");
    return new Response(body);
  });
  assert.equal(response.status, 200); assert.equal(await response.text(), body);
});
test("does not publish an unsigned, unavailable, or oversized release", async () => {
  for (const upstream of [new Response("not found", { status: 404 }), new Response('{"versionCode":4}'), new Response(" ".repeat(140 * 1024))]) {
    assert.equal((await handle(request("/v1/android/stable"), env, async () => upstream)).status, 503);
  }
});
test("rejects writes and unknown routes", async () => {
  assert.equal((await handle(request("/v1/android/stable", "POST"), env)).status, 405);
  assert.equal((await handle(request("/arbitrary"), env)).status, 404);
  assert.equal((await handle(request("/download"), env)).headers.get("Location"), "https://github.com/example/mobile/releases/latest/download/launcher-mobile.apk");
});
