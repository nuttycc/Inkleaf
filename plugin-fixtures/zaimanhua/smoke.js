"use strict";

// Manual smoke check for the 再漫画 source. NOT part of the test suite: it hits the real site, so it
// is slow, non-deterministic, and will fail whenever the network or the site is down.
//
//   node plugin-fixtures/zaimanhua/smoke.js
//
// Two jobs:
//   1. Prove the plugin still works end to end against the live API.
//   2. Answer the open questions recorded in docs/sources/zaimanhua.md, and print response samples
//      to paste into fixtures/ (truncate lists to 3 entries, keep the field structure intact).

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const overrides = {};
process.argv.slice(2).forEach(function (argument) {
  const match = /^--([A-Za-z]+)=(.*)$/.exec(argument);
  if (match) overrides[match[1]] = match[2];
});

const storedSettings = Object.assign(
  {
    apiBase: "",
    appVersion: "",
    userAgent: "",
    imageReferer: "",
    authToken: "",
    hdImages: "true",
    pageSize: "20",
  },
  overrides
);

const APP_VERSION = storedSettings.appVersion || "2.3.4";

const requestLog = [];

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
          requestLog.push(request);
          const response = await fetch(request.url, {
            method: request.method || "GET",
            headers: request.headers || {},
          });
          const bytes = Buffer.from(await response.arrayBuffer());
          return {
            statusCode: response.status,
            headers: {},
            bodyBase64: bytes.toString("base64"),
            bodySizeBytes: bytes.length,
          };
        },
        read: async function () {
          throw new Error("smoke harness returns every body inline");
        },
        close: async function () {
          return { closed: true };
        },
      },
      settings: {
        get: async function (id) {
          return storedSettings[id] ?? null;
        },
      },
      log: async function () {
        return { logged: true };
      },
    },
  },
};

const runtimeSource = fs.readFileSync(
  path.join(__dirname, "..", "shared", "runtime.js"),
  "utf8"
);
const pluginSource = fs.readFileSync(path.join(__dirname, "source.js"), "utf8");
vm.runInNewContext('(function () { "use strict";\n' + runtimeSource + "\n" + pluginSource + "\n})();", sandbox, {
  filename: "zaimanhua-bundle.js",
});

const registration = sandbox.registration;

function heading(title) {
  process.stdout.write("\n=== " + title + " ===\n");
}

/** Prints a response sample shaped the way a fixture should look. */
function sample(value, listKeys) {
  const clone = JSON.parse(JSON.stringify(value));
  (listKeys || []).forEach(function (key) {
    const parts = key.split(".");
    let node = clone;
    for (let index = 0; index < parts.length - 1 && node; index += 1) {
      node = node[parts[index]];
    }
    const last = parts[parts.length - 1];
    if (node && Array.isArray(node[last])) node[last] = node[last].slice(0, 3);
  });
  process.stdout.write(JSON.stringify(clone, null, 2) + "\n");
}

/** Direct API call that bypasses the plugin, for probing questions the plugin does not expose. */
async function rawGet(url) {
  const separator = url.indexOf("?") >= 0 ? "&" : "?";
  const response = await fetch(url + separator + "_v=" + APP_VERSION, {
    headers: {
      Accept: "application/json",
      "User-Agent":
        storedSettings.userAgent ||
        "Mozilla/5.0 (Linux; Android 14; SM-S9210) AppleWebKit/537.36 " +
          "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
    },
  });
  return { status: response.status, body: await response.json() };
}

async function run() {
  heading("describe");
  const descriptor = await registration.describe();
  process.stdout.write(
    "feeds: " +
      descriptor.feeds
        .map(function (feed) {
          return feed.id;
        })
        .join(", ") +
      "\nsettings: " +
      descriptor.settings
        .map(function (setting) {
          return setting.section + "/" + setting.id;
        })
        .join(", ") +
      "\n"
  );

  heading("search");
  const search = await registration.search({ query: "海贼", limit: 40 }, {});
  process.stdout.write(
    "items=" + search.items.length + " nextCursor=" + search.nextCursor + "\n"
  );
  process.stdout.write(JSON.stringify(search.items[0], null, 2) + "\n");

  heading("browse:filter");
  const filtered = await registration.browse({ feedId: "filter", filters: { theme: "3248" } }, {});
  process.stdout.write(
    "items=" + filtered.items.length + " nextCursor=" + filtered.nextCursor + "\n"
  );

  heading("browse:rank");
  const rank = await registration.browse({ feedId: "rank", filters: { byTime: "0" } }, {});
  process.stdout.write("items=" + rank.items.length + " nextCursor=" + rank.nextCursor + "\n");

  heading("browse:update");
  const update = await registration.browse({ feedId: "update" }, {});
  process.stdout.write("items=" + update.items.length + " nextCursor=" + update.nextCursor + "\n");

  // Detail/chapters/pages run against a recently updated comic rather than a search hit: search
  // surfaces licensed or withdrawn titles whose chapters carry no images at all, which would make
  // this check report a failure that has nothing to do with the plugin.
  const sourceId = update.items[0].sourceId;
  process.stdout.write("using comic " + sourceId + " (" + update.items[0].title + ")\n");

  heading("detail");
  const detail = await registration.detail({ sourceId: sourceId }, {});
  process.stdout.write(JSON.stringify(detail, null, 2) + "\n");

  heading("chapters");
  const chapters = await registration.chapters({ sourceId: sourceId }, {});
  process.stdout.write(
    "count=" +
      chapters.chapters.length +
      " unavailable=" +
      chapters.chapters.filter(function (chapter) {
        return !chapter.available;
      }).length +
      "\nfirst=" +
      JSON.stringify(chapters.chapters[0]) +
      "\nlast=" +
      JSON.stringify(chapters.chapters[chapters.chapters.length - 1]) +
      "\n"
  );

  heading("pages");
  const pages = await registration.pages(
    { sourceId: sourceId, chapterId: chapters.chapters[0].chapterId },
    {}
  );
  process.stdout.write(
    "count=" + pages.pages.length + "\nfirst=" + JSON.stringify(pages.pages[0]) + "\n"
  );

  // ---- Open questions from docs/sources/zaimanhua.md ----

  heading("Q: 图片是否需要 Referer");
  const imageUrl = pages.pages[0].url;
  const withoutReferer = await fetch(imageUrl, {
    headers: { Accept: "image/*,*/*;q=0.8", "User-Agent": storedSettings.userAgent || "Mozilla/5.0" },
  });
  const withReferer = await fetch(imageUrl, {
    headers: {
      Accept: "image/*,*/*;q=0.8",
      "User-Agent": storedSettings.userAgent || "Mozilla/5.0",
      Referer: "https://www.zaimanhua.com/",
    },
  });
  process.stdout.write(
    "no-referer=" +
      withoutReferer.status +
      " with-referer=" +
      withReferer.status +
      "  => Referer " +
      (withoutReferer.status === 200 ? "NOT required" : "REQUIRED") +
      "\n"
  );

  heading("Q: size 是否接受大于 20");
  const bigPage = await rawGet(
    "https://v4api.zaimanhua.com/app/v1/search/index?keyword=%E6%B5%B7%E8%B4%BC&page=1&sort=0&size=50"
  );
  const bigRows = (bigPage.body && bigPage.body.data && bigPage.body.data.list) || [];
  process.stdout.write(
    "requested size=50 -> returned " +
      bigRows.length +
      " rows, reported size=" +
      (bigPage.body && bigPage.body.data && bigPage.body.data.size) +
      "\n"
  );

  heading("Q: 题材 23388 实际是什么");
  const theme = await rawGet(
    "https://v4api.zaimanhua.com/app/v1/comic/filter/list?theme=23388&cate=0&status=0&zone=0&sortType=1&page=1&size=10"
  );
  const themeRows = (theme.body && theme.body.data && theme.body.data.comicList) || [];
  process.stdout.write(
    themeRows
      .map(function (row) {
        return "- " + row.title + "  [" + row.types + "]";
      })
      .join("\n") + "\n"
  );

  heading("Q: canRead / is_fee 取值分布（未登录）");
  const rawDetail = await rawGet(
    "https://v4api.zaimanhua.com/app/v1/comic/detail/" + encodeURIComponent(sourceId)
  );
  const groups = (rawDetail.body && rawDetail.body.data && rawDetail.body.data.data.chapters) || [];
  const counters = { total: 0, feeTrue: 0, canReadFalse: 0, canReadMissing: 0 };
  groups.forEach(function (group) {
    (group.data || []).forEach(function (chapter) {
      counters.total += 1;
      if (chapter.is_fee) counters.feeTrue += 1;
      if (chapter.canRead === false) counters.canReadFalse += 1;
      if (chapter.canRead === undefined) counters.canReadMissing += 1;
    });
  });
  process.stdout.write(JSON.stringify(counters) + "\n");

  // ---- Fixture samples ----

  heading("FIXTURE search.json");
  const rawSearch = await rawGet(
    "https://v4api.zaimanhua.com/app/v1/search/index?keyword=%E6%B5%B7%E8%B4%BC&page=1&sort=0&size=20"
  );
  sample(rawSearch.body, ["data.list"]);

  heading("FIXTURE filter.json");
  const rawFilter = await rawGet(
    "https://v4api.zaimanhua.com/app/v1/comic/filter/list?theme=3248&cate=0&status=0&zone=0&sortType=1&page=1&size=20"
  );
  sample(rawFilter.body, ["data.comicList"]);

  heading("FIXTURE detail.json");
  const detailClone = JSON.parse(JSON.stringify(rawDetail.body));
  const detailGroups = detailClone.data.data.chapters || [];
  detailClone.data.data.chapters = detailGroups.slice(0, 2).map(function (group) {
    return Object.assign({}, group, { data: (group.data || []).slice(0, 3) });
  });
  process.stdout.write(JSON.stringify(detailClone, null, 2) + "\n");

  heading("FIXTURE chapter.json");
  const rawChapter = await rawGet(
    "https://v4api.zaimanhua.com/app/v1/comic/chapter/" +
      encodeURIComponent(sourceId) +
      "/" +
      encodeURIComponent(chapters.chapters[0].chapterId)
  );
  sample(rawChapter.body, ["data.data.page_url", "data.data.page_url_hd"]);

  heading("done");
  process.stdout.write("requests issued through the plugin: " + requestLog.length + "\n");
}

run().catch(function (error) {
  console.error(error);
  process.exitCode = 1;
});
