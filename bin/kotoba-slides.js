#!/usr/bin/env node
const { spawnSync } = require("node:child_process");
const { dirname, resolve } = require("node:path");
const cwd = resolve(dirname(__filename), "..");
const result = spawnSync("clojure", ["-M:cli", ...process.argv.slice(2)], {
  cwd,
  stdio: "inherit",
});
process.exit(result.status ?? 1);
