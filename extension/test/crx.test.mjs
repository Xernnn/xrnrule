// The signed .crx that scripts/release.mjs publishes, read back the way a browser checks it, and
// the manifest wiring the extension's self-update depends on. Run: node --test extension/test
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash, createPublicKey, createVerify, generateKeyPairSync } from "node:crypto";
import { inflateRawSync } from "node:zlib";
import { fileURLToPath } from "node:url";
import {
  packageFiles, withUpdateUrl, zip, crx3, extensionId, crxIdBytes, updateManifest, compareVersions, nextVersion, CRX3_SIGNED_PREFIX,
} from "../../scripts/crx.mjs";

const EXTENSION = fileURLToPath(new URL("..", import.meta.url));
const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });

// Just enough protobuf for a CRX3 header: every field in it is length-delimited.
function fields(buf) {
  const out = [];
  let i = 0;
  const varint = () => {
    let n = 0;
    for (let shift = 0; ; shift += 7) {
      const b = buf[i++];
      n += (b & 127) * 2 ** shift;
      if (b < 128) return n;
    }
  };
  while (i < buf.length) {
    const key = varint();
    const len = varint();
    out.push([key >>> 3, buf.subarray(i, i + len)]);
    i += len;
  }
  return out;
}
const one = (buf, num) => fields(buf).find(([n]) => n === num)?.[1];

// The zip's entries, from its local headers (the writer leaves no extra fields).
function unzip(buf) {
  const files = {};
  let i = 0;
  while (buf.readUInt32LE(i) === 0x04034b50) {
    const packed = buf.readUInt32LE(i + 18);
    const nameLen = buf.readUInt16LE(i + 26);
    const name = buf.subarray(i + 30, i + 30 + nameLen).toString();
    const start = i + 30 + nameLen;
    files[name] = inflateRawSync(buf.subarray(start, start + packed));
    i = start + packed;
  }
  return files;
}

test("the .crx is signed the way a browser checks it, under the key's ID", () => {
  const zipBuf = zip(withUpdateUrl(packageFiles(EXTENSION), "https://example.org/xrnrule/update/updates.xml"));
  const crx = crx3(zipBuf, privateKey);
  assert.equal(crx.subarray(0, 4).toString(), "Cr24");
  assert.equal(crx.readUInt32LE(4), 3);
  const headerLen = crx.readUInt32LE(8);
  const header = crx.subarray(12, 12 + headerLen);
  assert.deepEqual(crx.subarray(12 + headerLen), zipBuf);

  const proof = one(header, 2);
  const publicKey = one(proof, 1);
  const signature = one(proof, 2);
  const signedData = one(header, 10000);
  assert.deepEqual(one(signedData, 1), crxIdBytes(privateKey));
  const len = Buffer.alloc(4);
  len.writeUInt32LE(signedData.length);
  const verify = createVerify("sha256").update(Buffer.concat([CRX3_SIGNED_PREFIX, len, signedData, zipBuf]));
  assert.ok(verify.verify(createPublicKey({ key: publicKey, format: "der", type: "spki" }), signature));

  const id = extensionId(privateKey);
  assert.match(id, /^[a-p]{32}$/);
  const fromKey = [...createHash("sha256").update(publicKey).digest().subarray(0, 16).toString("hex")]
    .map((c) => String.fromCharCode(97 + parseInt(c, 16))).join("");
  assert.equal(id, fromKey);
});

test("only the packaged manifest names the update address, and tests aren't packaged", () => {
  const files = packageFiles(EXTENSION);
  const names = files.map(([n]) => n);
  for (const needed of ["manifest.json", "background.js", "content.js", "shared.js", "block.html", "icons/icon-128.png"]) {
    assert.ok(names.includes(needed), needed);
  }
  assert.ok(!names.some((n) => n.startsWith("test/")));
  const packed = unzip(zip(withUpdateUrl(files, "https://example.org/xrnrule/update/updates.xml")));
  assert.equal(JSON.parse(packed["manifest.json"]).update_url, "https://example.org/xrnrule/update/updates.xml");
  assert.deepEqual(packed["background.js"], readFileSync(new URL("../background.js", import.meta.url)));
  const repo = JSON.parse(readFileSync(new URL("../manifest.json", import.meta.url), "utf8"));
  assert.equal(repo.update_url, undefined);
});

test("the update manifest names the version and pins the .crx's hash", () => {
  const crx = Buffer.from("not really a crx");
  const xml = updateManifest({ id: "abcdefghijklmnopabcdefghijklmnop", version: "2.1.0", codebase: "https://example.org/x.crx", crx });
  assert.match(xml, /appid='abcdefghijklmnopabcdefghijklmnop'/);
  assert.match(xml, /version='2\.1\.0'/);
  assert.ok(xml.includes(`hash_sha256='${createHash("sha256").update(crx).digest("hex")}'`));
});

test("versions compare by number and bump their last part", () => {
  assert.ok(compareVersions("2.1.0", "2.0.13") > 0);
  assert.ok(compareVersions("2.0.9", "2.0.10") < 0);
  assert.equal(compareVersions("2.0", "2.0.0"), 0);
  assert.equal(nextVersion("2.0.9"), "2.0.10");
  assert.equal(nextVersion("3"), "4");
});

test("the extension can update itself and re-inject open tabs", () => {
  const manifest = JSON.parse(readFileSync(new URL("../manifest.json", import.meta.url), "utf8"));
  assert.ok(manifest.permissions.includes("scripting"));
  const background = readFileSync(new URL("../background.js", import.meta.url), "utf8");
  assert.ok(background.includes("onUpdateAvailable"));
  assert.ok(background.includes("requestUpdateCheck"));
  const options = readFileSync(new URL("../options.html", import.meta.url), "utf8");
  assert.ok(options.includes('id="update-check"'));
});
