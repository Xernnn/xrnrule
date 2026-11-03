// Publishes a release to the home server, where installed copies update themselves from:
//
//   node scripts/release.mjs extension   sign dist/xrnrule-<version>.crx and publish it with
//                                        updates.xml; a browser that installed the .crx (Quetta)
//                                        fetches it on its own
//   node scripts/release.mjs app         build the sideload release APK and publish it with
//                                        app.json; the phone app installs it on its own
//   node scripts/release.mjs             both
//
// A browser or phone only takes a version newer than the one it has, so a number the server
// already has is bumped first: manifest.json's version, app/build.gradle.kts's versionCode.
//
// Reads ~/.config/xrnrule/release.json:
//   { "server": "https://<host>/xrnrule", "ssh": ["<ssh-host>", "<another way in>"] }
// and uses the first ssh host that answers. The extension is signed with
// ~/.config/xrnrule/extension.pem, made on the first release. Neither belongs in the repo: the
// address never enters it, and the key is what makes an update the same extension. Lose it and
// every browser has to install afresh under a new ID. The APK is signed by Gradle with the key in
// ../xrn-signing.properties, the one the installed app carries.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "fs";
import { join } from "path";
import { homedir } from "os";
import { execFileSync, spawnSync } from "child_process";
import { createHash, generateKeyPairSync, createPrivateKey } from "crypto";
import { packageFiles, withUpdateUrl, zip, crx3, extensionId, updateManifest, compareVersions, nextVersion } from "./crx.mjs";

const ROOT = new URL("..", import.meta.url).pathname;
const EXTENSION = join(ROOT, "extension");
const MANIFEST = join(EXTENSION, "manifest.json");
const GRADLE = join(ROOT, "app", "build.gradle.kts");
const APK_DIR = join(ROOT, "app", "build", "outputs", "apk", "sideload", "release");
const DIST = join(ROOT, "dist");
const CONFIG_DIR = join(homedir(), ".config", "xrnrule");
const REMOTE_DIR = "xrnrule-sync/data/update"; // the sync server's --data, from the ssh login's home

const what = process.argv[2] || "all";
if (!["extension", "app", "all"].includes(what)) {
  console.error("usage: node scripts/release.mjs [extension|app]");
  process.exit(2);
}

const { server, host } = readConfig();
mkdirSync(DIST, { recursive: true });
const ssh = (command, input) =>
  execFileSync("ssh", ["-o", "BatchMode=yes", host, command], { input, stdio: [input ? "pipe" : "ignore", "pipe", "inherit"] });
// Written beside its final name and moved into place, so nothing ever reads a half-written file.
const put = (name, data) => ssh(`cat > ${REMOTE_DIR}/${name}.part && mv ${REMOTE_DIR}/${name}.part ${REMOTE_DIR}/${name}`, data);
// The stable name a first install starts from, as a copy of the versioned file already there.
const alias = (name, as) => ssh(`cd ${REMOTE_DIR} && cp -f ${name} ${as}.part && mv ${as}.part ${as}`);
ssh(`mkdir -p ${REMOTE_DIR}`);

if (what !== "app") await releaseExtension();
if (what !== "extension") await releaseApp();

function readConfig() {
  const file = join(CONFIG_DIR, "release.json");
  if (!existsSync(file)) {
    console.error(`No ${file}. Create it with the sync server's public address and its ssh host:
  { "server": "https://<host>/xrnrule", "ssh": ["<ssh-host>"] }`);
    process.exit(1);
  }
  const config = JSON.parse(readFileSync(file, "utf8"));
  const server = String(config.server || "").replace(/\/+$/, "");
  const hosts = [config.ssh].flat().filter(Boolean);
  if (!/^https:\/\/[^/]+/.test(server) || !hosts.length) throw new Error(`${file} needs "server" (https://…) and "ssh"`);
  const host = hosts.find((h) => spawnSync("ssh", ["-o", "BatchMode=yes", "-o", "ConnectTimeout=10", h, "true"]).status === 0);
  if (!host) throw new Error(`none of ${hosts.join(", ")} answers over ssh`);
  return { server, host };
}

async function releaseExtension() {
  const keyFile = join(CONFIG_DIR, "extension.pem");
  if (!existsSync(keyFile)) {
    const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
    writeFileSync(keyFile, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 });
    console.log(`made a signing key: ${keyFile}. Back it up: every update needs this exact key`);
  }
  const key = createPrivateKey(readFileSync(keyFile));
  const id = extensionId(key);

  const manifestVersion = () => JSON.parse(readFileSync(MANIFEST, "utf8")).version;
  const live = await liveExtensionVersion();
  if (live && compareVersions(live, manifestVersion()) >= 0) {
    const next = nextVersion(live);
    writeFileSync(MANIFEST, readFileSync(MANIFEST, "utf8").replace(/("version"\s*:\s*")[^"]*(")/, `$1${next}$2`));
    console.log(`the server already has ${live}: manifest.json is now ${next}`);
  }
  const version = manifestVersion();

  const updateUrl = `${server}/update/updates.xml`;
  const crx = crx3(zip(withUpdateUrl(packageFiles(EXTENSION), updateUrl)), key);
  const crxName = `xrnrule-${version}.crx`;
  writeFileSync(join(DIST, crxName), crx);
  const xml = updateManifest({ id, version, codebase: `${server}/update/${crxName}`, crx });
  writeFileSync(join(DIST, "updates.xml"), xml);
  console.log(`dist/${crxName}: ${Math.round(crx.length / 1024)} KB, extension ID ${id}`);

  // The .crx first and updates.xml last, so the manifest never names a file that isn't there.
  put(crxName, crx);
  alias(crxName, "xrnrule.crx");
  put("updates.xml", Buffer.from(xml));
  ssh(`cd ${REMOTE_DIR} && for f in xrnrule-*.crx; do [ "$f" = ${crxName} ] || rm -f "$f"; done`);

  const now = await liveExtensionVersion();
  if (now !== version) throw new Error(`uploaded, but ${updateUrl} says ${now || "nothing"}: is the server's /update/ route deployed?`);
  console.log(`published extension ${version}. Installed copies pick it up within hours, or at once from its options → Check now.
First install in a new browser: ${server}/update/xrnrule.crx`);
}

async function liveExtensionVersion() {
  const res = await fetch(`${server}/update/updates.xml`, { cache: "no-store" });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`${server}/update/updates.xml answered ${res.status}`);
  const m = /updatecheck[^>]*\sversion='([^']+)'/.exec(await res.text());
  return m ? m[1] : null;
}

async function releaseApp() {
  if (!existsSync(join(ROOT, "..", "xrn-signing.properties"))) {
    throw new Error("no ../xrn-signing.properties: the APK would carry this machine's debug key, and the phone only takes an update signed like the app it has");
  }
  // app.json needs the login over https; ssh doesn't.
  const liveText = ssh(`cat ${REMOTE_DIR}/app.json 2>/dev/null || true`).toString().trim();
  const live = liveText ? JSON.parse(liveText) : null;
  const gradle = readFileSync(GRADLE, "utf8");
  let versionCode = Number(/versionCode\s*=\s*(\d+)/.exec(gradle)?.[1]);
  if (!versionCode) throw new Error(`no versionCode in ${GRADLE}`);
  if (live && live.versionCode >= versionCode) {
    versionCode = live.versionCode + 1;
    writeFileSync(GRADLE, gradle.replace(/(versionCode\s*=\s*)\d+/, `$1${versionCode}`));
    console.log(`the server already has build ${live.versionCode}: app/build.gradle.kts is now versionCode ${versionCode}`);
  }

  const build = spawnSync("./gradlew", [":app:assembleSideloadRelease"], { cwd: ROOT, stdio: "inherit" });
  if (build.status !== 0) throw new Error("the build failed");
  const [built] = JSON.parse(readFileSync(join(APK_DIR, "output-metadata.json"), "utf8")).elements;
  if (built.versionCode !== versionCode) throw new Error(`built versionCode ${built.versionCode}, expected ${versionCode}`);
  const apk = readFileSync(join(APK_DIR, built.outputFile));
  const name = `xrnrule-${versionCode}.apk`;
  const info = {
    versionCode,
    versionName: built.versionName,
    apk: name,
    size: apk.length,
    sha256: createHash("sha256").update(apk).digest("hex"),
  };
  console.log(`${name}: ${(apk.length / 1048576).toFixed(1)} MB, version ${built.versionName} (${versionCode})`);

  // The APK first and app.json last, so the phone never hears of a file that isn't there.
  put(name, apk);
  alias(name, "xrnrule.apk");
  put("app.json", Buffer.from(JSON.stringify(info, null, 2) + "\n"));
  ssh(`cd ${REMOTE_DIR} && for f in xrnrule-*.apk; do [ "$f" = ${name} ] || rm -f "$f"; done`);

  // Without the login it must say 401: proof the route is there and guarded.
  const res = await fetch(`${server}/update/app.json`, { cache: "no-store" });
  if (res.status !== 401) throw new Error(`${server}/update/app.json answered ${res.status} without the login, not 401: is the server's /update/ route deployed?`);
  console.log(`published the app, build ${versionCode}. The phone installs it the next time its screen goes off after it looks
(every few hours), or at once from Settings → Updates → Check now.
First install on a phone without it: ${server}/update/xrnrule.apk (with the sync login)`);
}
