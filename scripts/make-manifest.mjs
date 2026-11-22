import { readFile, writeFile } from "node:fs/promises";
import { createHash, createPrivateKey, createPublicKey, sign, verify } from "node:crypto";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const apkPath = resolve(process.argv[2] ?? "app/build/outputs/apk/release/app-release.apk");
const destination = resolve(process.argv[3] ?? "artifacts/update-manifest.json");
const metadata = JSON.parse(await readFile(resolve(dirname(apkPath), "output-metadata.json"), "utf8"));
const build = metadata.elements[0];
if (metadata.applicationId !== "com.codexlauncher.companion" || !Number.isSafeInteger(build.versionCode)) throw new Error("Unexpected Android build metadata");
if (process.env.GITHUB_REF_TYPE === "tag" && process.env.GITHUB_REF_NAME !== `v${build.versionName}`) throw new Error("The tag must match the Android versionName");
const key = createPrivateKey({ key: Buffer.from(process.env.UPDATE_SIGNING_KEY ?? "", "base64"), format: "der", type: "pkcs8" });
const publicKey = createPublicKey(key);
const trusted = (await readFile(resolve(root, "release/update-public-key.txt"), "utf8")).trim();
if (publicKey.export({ type: "spki", format: "der" }).toString("base64") !== trusted) throw new Error("Update signing key does not match the key embedded in the APK");
const apk = await readFile(apkPath);
const repo = process.env.GITHUB_REPOSITORY ?? "MisterGaohohoho/Codex-Launcher-Mobile";
if (!/^[\w-]+\/[\w.-]+$/.test(repo)) throw new Error("Invalid repository");
const payload = Buffer.from(JSON.stringify({
  versionCode: build.versionCode, versionName: build.versionName,
  apkUrl: `https://github.com/${repo}/releases/download/v${build.versionName}/launcher-mobile.apk`,
  sha256: createHash("sha256").update(apk).digest("hex"), size: apk.length, minSdk: 26,
  publishedAt: new Date().toISOString(), releaseNotes: process.env.RELEASE_NOTES ?? "Mobile workbench, live PC configuration, session control, and verified app updates.",
}));
const signature = sign("sha256", payload, key);
if (!verify("sha256", payload, publicKey, signature)) throw new Error("Manifest signature self-check failed");
await writeFile(destination, JSON.stringify({ payload: payload.toString("base64"), signature: signature.toString("base64") }, null, 2) + "\n");
console.log(`Signed update metadata for ${build.versionName} (${build.versionCode}); SHA-256 ${createHash("sha256").update(apk).digest("hex")}`);
