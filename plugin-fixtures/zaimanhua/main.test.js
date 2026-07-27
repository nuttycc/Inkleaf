"use strict";

// Offline unit tests for the 再漫画 source. No network: every response comes from a recorded
// fixture (see docs/sources/zaimanhua.md for how to re-record them).
//
//   node plugin-fixtures/zaimanhua/main.test.js
//
// Scope is deliberately narrow -- the parsing layer, one structural-failure case, and one check
// that settings actually reach the wire. Live-site behaviour is smoke.js's job, not this file's.

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const runtimeSource = fs.readFileSync(path.join(__dirname, "..", "shared", "runtime.js"), "utf8");
const pluginSource = fs.readFileSync(path.join(__dirname, "source.js"), "utf8");

function fixture(name) {
  return JSON.parse(fs.readFileSync(path.join(__dirname, "fixtures", name), "utf8"));
}

// Values built inside the vm have that realm's prototypes, so deepStrictEqual rejects them even
// when the contents match. Round-tripping through JSON is also what the host does to them anyway.
function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

const FIXTURES = {
  search: fixture("search.json"),
  filter: fixture("filter.json"),
  update: fixture("update.json"),
  detail: fixture("detail.json"),
  chapter: fixture("chapter.json"),
};

/**
 * Builds a fresh plugin instance. Each call is its own vm context, which mirrors the host: settings
 * are read once per isolate, and the host rebuilds the isolate after the settings screen closes.
 */
function createPlugin(options) {
  const settings = Object.assign(
    {
      apiBase: "",
      appVersion: "",
      userAgent: "",
      imageReferer: "",
      authToken: "",
      hdImages: "true",
      pageSize: "20",
    },
    (options && options.settings) || {}
  );
  const requests = [];
  const respond = (options && options.respond) || defaultRespond;

  const sandbox = {
    console: console,
    URL: URL,
    Date: Date,
    Promise: Promise,
    inkleaf: {
      register: function (value) {
        sandbox.registration = value;
      },
      host: {
        http: {
          request: async function (request) {
            requests.push(request);
            const payload = respond(request);
            const bytes = Buffer.from(
              JSON.stringify(payload.body === undefined ? payload : payload.body),
              "utf8"
            );
            return {
              statusCode: payload.statusCode || 200,
              headers: {},
              bodyBase64: bytes.toString("base64"),
              bodySizeBytes: bytes.length,
            };
          },
          read: async function () {
            throw new Error("fixtures are always returned inline");
          },
          close: async function () {
            return { closed: true };
          },
        },
        settings: {
          get: async function (id) {
            return settings[id] ?? null;
          },
        },
      },
    },
  };

  vm.runInNewContext(
    '(function () { "use strict";\n' + runtimeSource + "\n" + pluginSource + "\n})();",
    sandbox,
    { filename: "zaimanhua-bundle.js" }
  );
  return { plugin: sandbox.registration, requests: requests };
}

function defaultRespond(request) {
  const url = request.url;
  if (url.includes("/search/index")) return FIXTURES.search;
  if (url.includes("/comic/filter/list")) return FIXTURES.filter;
  if (url.includes("/comic/update/list")) return FIXTURES.update;
  if (url.includes("/comic/detail/")) return FIXTURES.detail;
  if (url.includes("/comic/chapter/")) return FIXTURES.chapter;
  throw new Error("unrouted request: " + url);
}

async function run() {
  // 1. search maps the list and derives a cursor from total vs consumed.
  {
    const { plugin } = createPlugin();
    const page = await plugin.search({ query: "火影", limit: 40 }, {});
    assert.equal(page.items.length, 3);
    const first = page.items[0];
    // comic_id is 0 on search rows, so the id has to come from the `id` field.
    assert.equal(first.sourceId, String(FIXTURES.search.data.list[0].id));
    assert.equal(first.title, FIXTURES.search.data.list[0].title);
    assert.ok(first.cover.url.startsWith("https://"));
    assert.deepEqual(
      plain(first.tags),
      FIXTURES.search.data.list[0].types.split(/[/,，]/)
    );
    assert.equal(page.nextCursor, "2", "20 consumed of 29 total means there is a second page");

    const last = await plugin.search({ query: "火影", cursor: "2" }, {});
    assert.equal(last.nextCursor, null, "40 consumed of 29 total is the end");
  }

  // 2. detail unwraps the doubly-nested payload and flattens the tag objects.
  {
    const { plugin } = createPlugin();
    const detail = await plugin.detail({ sourceId: "86949" }, {});
    assert.equal(detail.sourceId, "86949");
    assert.equal(detail.title, "明魔录");
    assert.equal(detail.status, "连载中");
    assert.deepEqual(plain(detail.tags), ["冒险", "热血", "战争", "武侠"]);
    assert.match(detail.subtitle, /李凌风/);
    assert.ok(detail.description.length > 0);
    // Chapters are intentionally absent from opaqueContext: a long series would blow the host's
    // 64 KiB budget, so `chapters` refetches instead of round-tripping them.
    assert.deepEqual(plain(detail.opaqueContext), { comicId: "86949" });
  }

  // 3. chapters flips the site's newest-first order and reads availability from canRead.
  {
    const { plugin } = createPlugin();
    const result = await plugin.chapters({ sourceId: "86949" }, {});
    assert.equal(result.sourceId, "86949");
    assert.deepEqual(
      plain(result.chapters).map(function (chapter) {
        return chapter.title;
      }),
      ["12话", "13话", "14话"],
      "chapters must read oldest-first"
    );
    assert.deepEqual(
      plain(result.chapters).map(function (chapter) {
        return chapter.number;
      }),
      [1, 2, 3]
    );
    // updatetime is in seconds, not milliseconds; misreading it lands the date in 1970.
    assert.equal(result.chapters[0].publishedAt, "2026-07-14");
    assert.ok(
      result.chapters.every(function (chapter) {
        return chapter.available;
      })
    );
    // A single group must not stamp its name onto every row.
    assert.ok(!result.chapters[0].title.includes("连载 - "));
  }

  // 4. pages produces contiguous indexes and carries image headers.
  {
    const { plugin } = createPlugin();
    const result = await plugin.pages({ sourceId: "86949", chapterId: "183982" }, {});
    assert.equal(result.pages.length, 3);
    assert.deepEqual(
      plain(result.pages).map(function (page) {
        return page.index;
      }),
      [0, 1, 2],
      "index must be contiguous from 0 or the host rejects the response"
    );
    assert.equal(result.pages[0].pageId, "183982-1");
    assert.ok(result.pages[0].headers["User-Agent"], "image requests must carry the source's UA");
    assert.equal(result.pages[0].referer, undefined, "no Referer unless one is configured");
  }

  // 5. A non-zero errno is a structural failure and must not read as "no results".
  {
    const { plugin } = createPlugin({
      respond: function () {
        return { errno: 2, errmsg: "漫画不存在或已被删除", data: null };
      },
    });
    await assert.rejects(plugin.detail({ sourceId: "86949" }, {}), function (error) {
      return error.code === "HTTP" && /漫画不存在/.test(error.message);
    });
  }

  // 6. Every setting must reach the wire. Settings that silently fail to apply are the most common
  //    and least visible defect in a source plugin -- a UA that never made it onto image requests
  //    is invisible until the site starts blocking you.
  {
    const { plugin, requests } = createPlugin({
      settings: {
        apiBase: "https://example.invalid/app/v1/",
        appVersion: "9.9.9",
        userAgent: "test-agent/1.0",
        imageReferer: "https://referer.example/",
        authToken: "token-abc",
        hdImages: "false",
        pageSize: "50",
      },
    });

    await plugin.search({ query: "火影" }, {});
    const searchRequest = requests[0];
    assert.ok(
      searchRequest.url.startsWith("https://example.invalid/app/v1/search/index?"),
      "apiBase must be used and its trailing slash trimmed"
    );
    assert.match(searchRequest.url, /[?&]size=50(&|$)/, "pageSize must reach the query");
    assert.match(searchRequest.url, /[?&]_v=9\.9\.9(&|$)/, "appVersion must reach every request");
    assert.equal(searchRequest.headers["User-Agent"], "test-agent/1.0");
    assert.equal(searchRequest.headers.Authorization, "Bearer token-abc");

    const pages = await plugin.pages({ sourceId: "86949", chapterId: "183982" }, {});
    assert.equal(pages.pages[0].referer, "https://referer.example/");
    assert.equal(
      pages.pages[0].headers["User-Agent"],
      "test-agent/1.0",
      "the UA must reach image requests, not just API requests"
    );
    // hdImages=false prefers page_url; the fixture's two arrays are identical, so assert the
    // selection through length rather than value.
    assert.equal(pages.pages.length, FIXTURES.chapter.data.data.page_url.length);
  }

  process.stdout.write("zaimanhua unit tests passed\n");
}

run().catch(function (error) {
  console.error(error);
  process.exitCode = 1;
});
