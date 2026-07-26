"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

let registration;
let router;
let nextHandle = 0;
let closedHandles = 0;
const bodyHandles = new Map();

function response(payload, statusCode, chunked) {
  const bytes = Buffer.from(typeof payload === "string" ? payload : JSON.stringify(payload), "utf8");
  if (!chunked) {
    return {
      statusCode: statusCode || 200,
      headers: {},
      bodyBase64: bytes.toString("base64"),
      bodySizeBytes: bytes.length
    };
  }
  const handle = "test-" + (++nextHandle);
  bodyHandles.set(handle, bytes);
  return {
    statusCode: statusCode || 200,
    headers: {},
    bodyHandle: handle,
    bodySizeBytes: bytes.length
  };
}

global.inkleaf = {
  register: function (value) { registration = value; },
  host: {
    http: {
      request: async function (request) { return router(request); },
      read: async function (request) {
        const bytes = bodyHandles.get(request.handle);
        assert.ok(bytes, "unknown test body handle");
        const start = Number(request.offset || 0);
        const end = Math.min(bytes.length, start + 13);
        return {
          handle: request.handle,
          offset: end,
          bodyBase64: bytes.subarray(start, end).toString("base64"),
          eof: end === bytes.length
        };
      },
      close: async function (request) {
        if (bodyHandles.delete(request.handle)) closedHandles += 1;
        return { closed: true };
      }
    }
  }
};

const sourcePath = path.join(__dirname, "main.js");
vm.runInThisContext(fs.readFileSync(sourcePath, "utf8"), { filename: sourcePath });

async function run() {
  assert.ok(registration, "plugin must register itself");
  assert.deepEqual(await registration.describe(), {
    schemaVersion: 1,
    actions: [],
    filters: [],
    settings: []
  });

  router = function (request) {
    assert.equal(request.headers.version, "2025.02.12");
    assert.match(request.url, /\/search\/comic\?/);
    return response({
      code: 200,
      results: {
        total: 2,
        list: [{
          path_word: "fixture-comic",
          name: "Fixture 漫画",
          cover: "https://img.example/cover.webp",
          author: [{ name: "作者" }],
          status: { display: "连载中" },
          theme: [{ name: "冒险" }],
          last_chapter_name: "第 2 话"
        }]
      }
    }, 200, true);
  };
  const search = await registration.search({ query: "测试", cursor: null, limit: 1 }, {});
  assert.equal(search.items[0].sourceId, "fixture-comic");
  assert.equal(search.items[0].title, "Fixture 漫画");
  assert.deepEqual(search.items[0].tags, ["冒险"]);
  assert.equal(search.nextCursor, "1");
  assert.equal(closedHandles, 1, "chunked response must be closed");

  router = function (request) {
    assert.match(request.url, /\/comic2\/fixture-comic\?/);
    return response({
      code: 200,
      results: {
        comic: {
          name: "Fixture 漫画",
          brief: "简介",
          cover: "https://img.example/cover.webp",
          author: [{ name: "作者" }],
          theme: [{ name: "冒险" }],
          status: { display: "连载中" },
          groups: { ignored: { path_word: "fallback", name: "备用" } }
        },
        groups: { default: { path_word: "main", name: "正篇" } }
      }
    });
  };
  const detail = await registration.detail({ sourceId: "fixture-comic" }, {});
  assert.equal(detail.description, "简介");
  assert.deepEqual(detail.opaqueContext.groups, [{ id: "main", name: "正篇" }]);

  router = function (request) {
    assert.match(request.url, /\/group\/main\/chapters\?/);
    return response({
      code: 200,
      results: {
        total: 2,
        list: [
          { uuid: "chapter-1", name: "第一话", datetime_created: "2026-01-01" },
          { uuid: "chapter-2", name: "第二话", datetime_created: "2026-01-02" }
        ]
      }
    });
  };
  const chapters = await registration.chapters({
    sourceId: "fixture-comic",
    opaqueContext: detail.opaqueContext
  }, {});
  assert.equal(chapters.chapters.length, 2);
  assert.equal(chapters.chapters[0].title, "正篇 - 第一话");
  assert.equal(chapters.chapters[1].number, 2);

  const pageRequests = [];
  router = function (request) {
    pageRequests.push(request.url);
    if (/\/chapter\/chapter-1\?/.test(request.url)) {
      return response("not found", 404);
    }
    assert.match(request.url, /\/chapter2\/chapter-1\?/);
    return response({
      code: 200,
      results: {
        chapter: {
          contents: [
            { url: "https://img.example/page-a.webp" },
            { url: "https://img.example/page-b.webp" }
          ],
          words: [2, 1]
        }
      }
    });
  };
  const pages = await registration.pages({
    sourceId: "fixture-comic",
    chapterId: "chapter-1",
    revision: "r1"
  }, {});
  assert.equal(pageRequests.length, 2, "404 must try chapter2");
  assert.equal(pages.pages[0].url, "https://img.example/page-b.webp");
  assert.equal(pages.pages[1].index, 1);
  assert.equal(pages.revision, "r1");

  process.stdout.write("copycomic fixture unit tests passed\n");
}

run().catch(function (error) {
  console.error(error);
  process.exitCode = 1;
}).finally(function () {
  delete global.inkleaf;
});
