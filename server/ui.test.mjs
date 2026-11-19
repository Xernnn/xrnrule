// What both web pages share (server/web/ui.js) that isn't drawing: the line saying what a block covers.
//
//     node --test server        (from the repo root)
import { test } from "node:test";
import assert from "node:assert/strict";

const { coverText } = await import("./web/ui.js");

test("what a block covers reads as one line of plain text, not a chip each", () => {
  assert.equal(coverText(["reddit.com", "x.com"], 2), "reddit.com, x.com, 2 phone apps");
  assert.equal(coverText(["youtube.com"], 1, ["music.youtube.com"]), "youtube.com, 1 phone app · except music.youtube.com");
  assert.equal(coverText(["steam", "Discord"]), "steam, Discord");
  assert.equal(coverText([], 3), "3 phone apps");
  assert.equal(coverText([], 0, ["a.example"]), "except a.example");
  assert.equal(coverText([]), "");
});
