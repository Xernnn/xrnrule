// What stops scripts/release.mjs before it builds or uploads anything. Tested in
// extension/test/release.test.mjs.
import { execFileSync } from "child_process";
import { existsSync, readFileSync, writeFileSync } from "fs";
import { createPrivateKey, generateKeyPairSync } from "crypto";

/** The branch releases are made from. */
export const RELEASE_BRANCH = "main";

/**
 * Why the working tree at [root] isn't one to release from; empty if it is. What's published
 * is what the folders hold, so it must be main with everything committed: a file not added
 * would go into the package as well.
 */
export function treeRefusals(root) {
  const git = (...args) => execFileSync("git", ["-C", root, ...args], { encoding: "utf8" }).trim();
  const out = [];
  const branch = git("rev-parse", "--abbrev-ref", "HEAD");
  if (branch !== RELEASE_BRANCH) out.push(`on ${branch}, not ${RELEASE_BRANCH}`);
  const changed = git("status", "--porcelain");
  if (changed) out.push(`not committed:\n${changed}`);
  return out;
}

/**
 * The extension's signing key, which is its ID: an installed copy only takes an update signed
 * with it. Made only when [make] says so (a first release), never because the file is missing:
 * on a machine without it, that published a new extension under a new ID, and every browser with
 * the old one stopped updating.
 */
export function signingKey(file, make = false) {
  if (!existsSync(file)) {
    if (!make) {
      throw new Error(`no ${file}. Restore it from the key backup: a new key is a new extension ID, and installed copies stop updating. Only for a first release: --new-key`);
    }
    const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
    writeFileSync(file, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600, flag: "wx" });
    console.log(`made a signing key: ${file}. Back it up: every update needs this exact key`);
  }
  return createPrivateKey(readFileSync(file));
}
