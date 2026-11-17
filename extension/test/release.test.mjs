// What stops scripts/release.mjs before it builds or uploads anything (scripts/release-guard.mjs).
// Run: node --test extension/test
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { treeRefusals, signingKey } from "../../scripts/release-guard.mjs";

test("a release is made from main with everything committed, or not at all", () => {
  // release.mjs published whatever the working tree held, a branch's or a half-done edit's.
  const repo = mkdtempSync(join(tmpdir(), "xrnrule-release-"));
  try {
    const git = (...args) => execFileSync("git", ["-C", repo, "-c", "user.name=t", "-c", "user.email=t@example.invalid", ...args], { stdio: "pipe" });
    git("init", "-q", "-b", "main");
    writeFileSync(join(repo, "a.txt"), "1");
    git("add", "a.txt");
    git("commit", "-q", "-m", "one");
    assert.deepEqual(treeRefusals(repo), []);
    writeFileSync(join(repo, "a.txt"), "2");
    assert.equal(treeRefusals(repo).length, 1, "a change not committed");
    git("checkout", "-q", "-b", "topic");
    assert.equal(treeRefusals(repo).length, 2, "and a branch other than main");
    git("checkout", "-q", "--", "a.txt");
    assert.match(treeRefusals(repo).join(), /topic/);
    git("checkout", "-q", "main");
    writeFileSync(join(repo, "new.js"), "");
    assert.equal(treeRefusals(repo).length, 1, "a file not added would be packaged too");
  } finally {
    rmSync(repo, { recursive: true, force: true });
  }
});

test("the extension's signing key is never made because the file is missing", () => {
  // A new key is a new extension ID: every installed copy would stop taking updates.
  const dir = mkdtempSync(join(tmpdir(), "xrnrule-key-"));
  try {
    const file = join(dir, "extension.pem");
    assert.throws(() => signingKey(file), /extension\.pem/);
    assert.equal(existsSync(file), false);
    const made = signingKey(file, true); // a first release, asked for
    assert.equal(statSync(file).mode & 0o777, 0o600);
    assert.equal(signingKey(file).export({ type: "pkcs8", format: "pem" }), made.export({ type: "pkcs8", format: "pem" }));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
