import { test } from "node:test";
import assert from "node:assert/strict";
import { formatBytes, formatDate, straightLineDistance } from "./viewer-format.ts";

test("formatBytes scales through KB/MB/GB", () => {
  assert.equal(formatBytes(512), "512 B");
  assert.equal(formatBytes(2048), "2.0 KB");
  assert.equal(formatBytes(5 * 1024 * 1024), "5.0 MB");
  assert.equal(formatBytes(3 * 1024 * 1024 * 1024), "3.0 GB");
});

test("formatDate falls back to the raw string for an unparseable value", () => {
  assert.equal(formatDate("not-a-date"), "not-a-date");
  assert.equal(formatDate(new Date(2026, 0, 1).toISOString()), new Date(new Date(2026, 0, 1).toISOString()).toLocaleString());
});

test("straightLineDistance is the real 3D Euclidean distance, not a 2D approximation", () => {
  const a = { x: 0, y: 0, z: 0 };
  const b = { x: 3, y: 4, z: 0 };
  assert.equal(straightLineDistance(a, b), 5);
  const c = { x: 1, y: 2, z: 2 };
  assert.equal(straightLineDistance({ x: 0, y: 0, z: 0 }, c), 3);
});
