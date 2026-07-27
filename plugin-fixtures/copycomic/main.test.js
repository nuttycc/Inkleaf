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
const storedApiBase = process.env.COPYCOMIC_TEST_API_BASE || "https://api.manga2025.com/api/v3";
const testApiBase = process.env.COPYCOMIC_EXPECTED_API_BASE || storedApiBase;
const testOriginalImage = process.env.COPYCOMIC_TEST_ORIGINAL_IMAGE === "true";
const testApiHost = testApiBase.replace(/^https?:\/\//, "").split("/")[0].toLowerCase();
const isHotMangaProfile = testApiHost.includes("hotmanga") ||
  testApiHost === "api.manga2025.com" || testApiHost.includes("fgjfghkk");
const storedSettings = {
  apiDomain: storedApiBase,
  originalImage: String(testOriginalImage)
};

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
    },
    settings: {
      get: async function (id) { return storedSettings[id] ?? null; }
    }
  }
};

const sourcePath = path.join(__dirname, "main.js");
vm.runInThisContext(fs.readFileSync(sourcePath, "utf8"), { filename: sourcePath });

async function run() {
  assert.ok(registration, "plugin must register itself");
  const descriptor = await registration.describe();
  assert.equal(descriptor.schemaVersion, 1);
  assert.deepEqual(descriptor.feeds.map(function (feed) { return feed.id; }), [
    "recommend", "newest", "rank", "discover"
  ]);
  assert.deepEqual(
    descriptor.feeds.find(function (feed) { return feed.id === "rank"; }).filters
      .map(function (filter) { return filter.id; }),
    ["audience", "period", "kind"]
  );
  const routeSetting = descriptor.settings.find(function (setting) {
    return setting.id === "apiDomain";
  });
  assert.ok(routeSetting, "API route setting must be present");
  assert.equal(routeSetting.options.length, 13);
  assert.equal(routeSetting.defaultValue, "https://api.manga2025.com/api/v3");
  assert.deepEqual(descriptor.settings.map(function (setting) { return setting.id; }), [
    "apiDomain", "originalImage"
  ]);
  assert.deepEqual(routeSetting.options.map(function (option) { return option.id; }), [
    "https://api.manga2025.com/api/v3",
    "https://api.2026copy.com/api/v3",
    "https://mapi.copy20.com/api/v3",
    "https://mapi.copy2000.site/api/v3",
    "https://api.2025copy.com/api/v3",
    "https://api.mangacopy.com/api/v3",
    "https://api.copy2000.online/api/v3",
    "https://mapi.hotmangasd.com/api/v3",
    "https://mapi.hotmangasf.com/api/v3",
    "https://mapi.hotmangasg.com/api/v3",
    "https://mapi.elfgjfghkk.club/api/v3",
    "https://mapi.fgjfghkk.club/api/v3",
    "https://mapi.fgjfghkkcenter.club/api/v3"
  ]);

  router = function (request) {
    assert.ok(request.url.startsWith(testApiBase + "/"));
    assert.equal(request.headers.version, isHotMangaProfile ? "2025.02.12" : "2025.05.09");
    assert.equal(
      request.headers.Origin,
      isHotMangaProfile ? "https://m.relamanhua.org" : "https://2025copy.com"
    );
    assert.equal(request.headers.webp, isHotMangaProfile ? "1" : "0");
    assert.equal(request.headers.region, isHotMangaProfile ? undefined : "0");
    assert.equal(request.headers.platform, "1");
    assert.equal(request.headers.Authorization, undefined);
    assert.equal(request.headers["sec-fetch-mode"], undefined);
    assert.match(request.url, /\/search\/comic\?/);
    assert.match(request.url, /(?:\?|&)limit=21(?:&|$)/);
    assert.match(request.url, /(?:\?|&)platform=1(?:&|$)/);
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
  const search = await registration.search({ query: "测试", cursor: null, limit: 40 }, {});
  assert.equal(search.items[0].sourceId, "fixture-comic");
  assert.equal(search.items[0].title, "Fixture 漫画");
  assert.deepEqual(search.items[0].tags, ["冒险"]);
  assert.equal(search.nextCursor, "1");
  assert.equal(closedHandles, 1, "chunked response must be closed");

  router = function (request) {
    assert.match(request.url, /\/comics\?/);
    assert.match(request.url, /(?:\?|&)platform=3(?:&|$)/);
    return response({ code: 200, results: { total: 0, list: [] } });
  };
  const probeSuccess = await registration.invokeAction({ actionId: "probeDomain" }, {});
  assert.match(probeSuccess.message, /^线路可用：/);

  router = function () { return response("unavailable", 500); };
  const probeFailure = await registration.invokeAction({ actionId: "probeDomain" }, {});
  assert.match(probeFailure.message, /^线路不可用：/);

  router = function (request) {
    assert.match(request.url, /\/recs\?/);
    assert.match(request.url, /(?:\?|&)pos=3200102(?:&|$)/);
    assert.match(request.url, /(?:\?|&)offset=21(?:&|$)/);
    assert.match(request.url, /(?:\?|&)platform=3(?:&|$)/);
    return response({
      code: 200,
      results: {
        total: 50,
        list: [
          { comic: { path_word: "recommended", name: "推荐漫画" } },
          { comic: { path_word: "recommended", name: "重复漫画" } },
          { comic: { name: "缺少 ID" } }
        ]
      }
    });
  };
  const recommend = await registration.browse({
    feedId: "recommend",
    cursor: "21",
    limit: 40,
    filters: {}
  }, {});
  assert.equal(recommend.items.length, 1, "browse results must remove duplicate source IDs");
  assert.equal(recommend.items[0].title, "推荐漫画");
  assert.equal(recommend.nextCursor, "24", "cursor must advance by raw rows");

  router = function (request) {
    assert.match(request.url, /\/ranks\?/);
    assert.match(request.url, /(?:\?|&)type=5(?:&|$)/);
    assert.match(request.url, /(?:\?|&)date_type=month(?:&|$)/);
    assert.match(request.url, /(?:\?|&)audience_type=female(?:&|$)/);
    return response({
      code: 200,
      results: {
        total: 1,
        list: [{ book: { path_word: "rank-book", name: "轻小说榜首" } }]
      }
    });
  };
  const rank = await registration.browse({
    feedId: "rank",
    filters: { audience: "female", period: "month", kind: "5" }
  }, {});
  assert.equal(rank.items[0].sourceId, "rank-book");

  router = function (request) {
    assert.match(request.url, /\/comics\?/);
    assert.match(request.url, /(?:\?|&)free_type=1(?:&|$)/);
    assert.match(request.url, /(?:\?|&)ordering=-popular(?:&|$)/);
    assert.match(request.url, /(?:\?|&)theme=maoxian(?:&|$)/);
    assert.match(request.url, /(?:\?|&)top=japan(?:&|$)/);
    return response({
      code: 200,
      results: { total: 1, list: [{ path_word: "discover-comic", name: "分类漫画" }] }
    });
  };
  const discover = await registration.browse({
    feedId: "discover",
    filters: { theme: "maoxian", top: "japan", ordering: "-popular" }
  }, {});
  assert.equal(discover.items[0].sourceId, "discover-comic");

  let newestRequests = 0;
  router = function (request) {
    newestRequests += 1;
    if (/\/comics\?/.test(request.url)) {
      assert.match(request.url, /(?:\?|&)ordering=-datetime_updated(?:&|$)/);
    } else {
      assert.doesNotMatch(request.url, /(?:\?|&)ordering=/);
    }
    if (newestRequests === 1) {
      assert.match(
        request.url,
        isHotMangaProfile ? /\/comics\?/ : /\/update\/newest\?/
      );
      return response("not found", 404);
    }
    assert.match(
      request.url,
      isHotMangaProfile ? /\/update\/newest\?/ : /\/comics\?/
    );
    return response({
      code: 200,
      results: {
        total: 1,
        list: [{ comic: { path_word: "newest-comic", name: "最新漫画" } }]
      }
    });
  };
  const newest = await registration.browse({ feedId: "newest", filters: {} }, {});
  assert.equal(newestRequests, 2, "newest must use its fallback after a 404");
  assert.equal(newest.items[0].sourceId, "newest-comic");

  let failedNewestRequests = 0;
  router = function () {
    failedNewestRequests += 1;
    return response("server error", 500);
  };
  await assert.rejects(
    registration.browse({ feedId: "newest", filters: {} }, {}),
    function (error) { return error && error.statusCode === 500; }
  );
  assert.equal(failedNewestRequests, 1, "newest must not fall back after non-404 errors");

  await assert.rejects(
    registration.browse({ feedId: "recommend", cursor: "invalid", filters: {} }, {}),
    function (error) { return error && error.code === "INVALID_ARGUMENT"; }
  );

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
    assert.equal(request.headers.Authorization, undefined);
    assert.match(request.url, /(?:\?|&)platform=1(?:&|$)/);
    if (pageRequests.length === 1) {
      assert.match(
        request.url,
        isHotMangaProfile ? /\/chapter\/chapter-1\?/ : /\/chapter2\/chapter-1\?/
      );
      return response("not found", 404);
    }
    assert.match(
      request.url,
      isHotMangaProfile ? /\/chapter2\/chapter-1\?/ : /\/chapter\/chapter-1\?/
    );
    return response({
      code: 200,
      results: {
        chapter: {
          contents: [
            { url: "https://img.example/page-a.c800x.webp" },
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
  assert.equal(pageRequests.length, 2, "404 must try the alternate chapter endpoint");
  assert.equal(pages.pages[0].url, "https://img.example/page-b.webp");
  assert.equal(
    pages.pages[1].url,
    testOriginalImage
      ? "https://img.example/page-a.webp"
      : "https://img.example/page-a.c800x.webp"
  );
  assert.equal(pages.pages[1].index, 1);
  assert.equal(pages.revision, "r1");

  let failedPageRequests = 0;
  router = function () {
    failedPageRequests += 1;
    return response("rate limited", 429);
  };
  await assert.rejects(
    registration.pages({ sourceId: "fixture-comic", chapterId: "chapter-1" }, {}),
    function (error) { return error && error.statusCode === 429; }
  );
  assert.equal(failedPageRequests, 1, "chapter loading must not fall back after non-404 errors");

  process.stdout.write("copycomic fixture unit tests passed (" +
    (isHotMangaProfile ? "hot-manga" : "standard") + ")\n");
}

run().catch(function (error) {
  console.error(error);
  process.exitCode = 1;
}).finally(function () {
  delete global.inkleaf;
});
