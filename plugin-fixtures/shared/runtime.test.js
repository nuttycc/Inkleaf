"use strict";

// Covers only the paths in runtime.js that fail *silently*: a truncated handle read produces valid
// JSON with fewer entries, and a broken UTF-8 decoder produces readable-looking mojibake. Everything
// else in runtime.js throws loudly when it breaks, so it is left to the source-level tests.

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const bodyHandles = new Map();
let nextHandle = 0;
let closedHandles = 0;
// Deliberately tiny so every fixture forces the multi-read loop rather than a single chunk.
const CHUNK_BYTES = 13;

// A fresh vm context exposes the JS intrinsics but none of Node's globals, so `atob` and
// `TextDecoder` are absent here exactly as they may be absent in a JavaScriptEngine isolate --
// which means these tests exercise the fallback decoders, not the platform ones.
const sandbox = {
  inkleaf: {
    host: {
      http: {
        read: async function (request) {
          const bytes = bodyHandles.get(request.handle);
          assert.ok(bytes, "unknown test body handle");
          const start = Number(request.offset || 0);
          const end = Math.min(bytes.length, start + CHUNK_BYTES);
          return {
            handle: request.handle,
            offset: end,
            bodyBase64: bytes.subarray(start, end).toString("base64"),
            eof: end === bytes.length,
          };
        },
        close: async function (request) {
          if (bodyHandles.delete(request.handle)) closedHandles += 1;
          return { closed: true };
        },
      },
    },
  },
};

const runtimePath = path.join(__dirname, "runtime.js");
vm.runInNewContext(fs.readFileSync(runtimePath, "utf8"), sandbox, { filename: runtimePath });

function chunkedResponse(payload) {
  const bytes = Buffer.from(payload, "utf8");
  const handle = "test-" + ++nextHandle;
  bodyHandles.set(handle, bytes);
  return { statusCode: 200, headers: {}, bodyHandle: handle, bodySizeBytes: bytes.length };
}

async function run() {
  // 1. A body larger than one chunk must come back whole, and the handle must be released.
  //    Multi-byte characters are placed so that at least one of them straddles a chunk boundary.
  const payload = JSON.stringify({
    list: Array.from({ length: 40 }, function (_, index) {
      return { id: index, title: "漫画标题" + index };
    }),
  });
  const before = closedHandles;
  const decoded = await sandbox.responseText(chunkedResponse(payload));
  assert.equal(decoded, payload, "chunked body must be reassembled byte for byte");
  assert.equal(JSON.parse(decoded).list.length, 40, "no entries may be lost in reassembly");
  assert.equal(closedHandles, before + 1, "body handle must be closed after a successful read");

  // 2. A host that stops advancing the offset must raise instead of looping or truncating.
  const stuck = {
    statusCode: 200,
    headers: {},
    bodyHandle: "stuck",
    bodySizeBytes: 100,
  };
  bodyHandles.set("stuck", Buffer.from("x".repeat(100), "utf8"));
  const originalRead = sandbox.inkleaf.host.http.read;
  sandbox.inkleaf.host.http.read = async function (request) {
    return { handle: request.handle, offset: 0, bodyBase64: "", eof: false };
  };
  await assert.rejects(sandbox.responseText(stuck), function (error) {
    return error && error.code === "PLUGIN_PROTOCOL";
  });
  sandbox.inkleaf.host.http.read = originalRead;

  // 3. The fallback decoders must agree with the platform ones, since the isolate may expose
  //    neither atob nor TextDecoder and we would never notice a mismatch at runtime.
  const sample = '{"t":"再漫画 · ASCII · 𝄞 emoji 🎼"}';
  const base64 = Buffer.from(sample, "utf8").toString("base64");
  assert.equal(
    sandbox.decodeUtf8(sandbox.decodeBase64(base64)),
    sample,
    "fallback base64+utf8 decoding must round-trip multi-byte and surrogate-pair text"
  );

  process.stdout.write("shared runtime unit tests passed\n");
}

run().catch(function (error) {
  console.error(error);
  process.exitCode = 1;
});
