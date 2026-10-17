"use strict";

// Offline unit tests for the 紳士漫畫 (WNACG) source. No network.
//
//   node plugin-fixtures/wnacg/main.test.js
//
// The samples are hand-written from pages recorded off the live site (see smoke.js for the live
// counterpart). They keep the markup quirks the parser exists to survive: a `.info` div that is
// never closed before its `</li>`, a cover `src` that opens with four slashes, an intro paragraph
// that is only the site's own 簡介： label, and a reader payload whose image array carries a
// trailing comma, which is what makes that payload unparseable as strict JSON.

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const runtimeSource = fs.readFileSync(path.join(__dirname, "..", "shared", "runtime.js"), "utf8");
const pluginSource = fs.readFileSync(path.join(__dirname, "src", "main.js"), "utf8");

const SITE_HOST = "www.wn001.cfd";
const DEAD_HOST = "wn07.example";
const HOST_STATE_KEY = "hosts";
const NOW = 1791500000000;

// --------------------------------------------------------------------------- samples

const LIST_HTML = [
  '<div class="grid"><div class="bodywrap"><div class="gallary_wrap"><ul class="cc">',
  '<li class="li gallary_item">',
  '<div class="pic_box cate-1"><a href="/photos-index-aid-394338.html" title="沈鸢篇 [SMHTN]">',
  '<img alt="沈鸢篇 [SMHTN]" src="//t4.wnimg2.cfd/data/t/3943/38/17915329324234362.png"></a></div>',
  '<div class="info"><div class="title">',
  '<a href="/photos-index-aid-394338.html" title="沈鸢篇 [SMHTN]">沈鸢篇 [SMHTN]</a></div>',
  '<div class="info_col">23張圖片， 創建於2026-10-09',
  '<!--span class="gray"><span class="i_greenicn sprite"></span>已上架</span--></div>',
  "</li>",
  // Lazy-loaded cover: the real page keeps the address in data-src until the row scrolls in.
  '<li class="li gallary_item">',
  '<div class="pic_box cate-19"><a href="/photos-index-aid-394336.html">',
  '<img alt="足控" data-src="//t4.wnimg2.cfd/data/t/3943/36/17915312496142575.webp" src="loading.jpg"></a></div>',
  '<div class="info"><div class="title"><a href="/photos-index-aid-394336.html">',
  '小姐由我來守護&#124;第3季</a></div><div class="info_col">122張圖片， 創建於2026-10-09</div>',
  "</li>",
  // A 合集 row: same markup, but its 話 count replaces the page count in the info line.
  '<li class="li gallary_item">',
  '<div class="pic_box cate-38"><a href="/photos-index-aid-390062.html" title="砲友滿屋">',
  '<img alt="砲友滿屋" src="//t4.wnimg2.cfd/data/t/3687/85/17830649435422621.jpg"></a></div>',
  '<div class="info"><div class="title"><a href="/photos-index-aid-390062.html">砲友滿屋</a></div>',
  '<div class="info_col">8話， 更新於2026-10-09</div></li>',
  "</ul></div></div>",
  '<div class="f_left paginator"><span class="prev"><a href="/albums-index-page-1.html">&lt;前頁</a></span>',
  '<a href="/albums-index-page-1.html">1</a> <span class="thispage">2</span>',
  '<a href="/albums-index-page-3.html">3</a> <a href="/albums-index-page-15094.html">15094</a>',
  "</div>",
].join("\n");

const LIST_LAST_PAGE_HTML = LIST_HTML.replace('<span class="thispage">2</span>', '<span class="thispage">15094</span>')
  .replace('<a href="/albums-index-page-3.html">3</a> <a href="/albums-index-page-15094.html">15094</a>', "");

// The ranking page wraps each cover in two absolutely-positioned badge divs, and keeps an uploader
// avatar in the row. Both are traps for a cover extractor that walks <img> tags naively.
const RANKING_HTML = [
  '<div class="grid"><div class="gallary_wrap"><ul class="cc">',
  '<li class="li gallary_item"><div class="pic_box" style="position: relative;">',
  '<!-- 排名標識 --><div style="position: absolute; top: 8px;">👑</div>',
  '<!-- 收藏數標識 --><div style="position: absolute; top: 8px; right: 25px;">1.2k</div>',
  '<a href="/photos-index-aid-392520.html"><img alt="x" src="//t4.wnimg2.cfd/data/t/3925/20/17911274296047289.webp"></a>',
  "</div>",
  '<div class="info"><div class="title"><a href="/photos-index-aid-392520.html">今日から雌猫を飼うことになりました件</a></div>',
  '<div class="info_col">42張圖片， 創建於2026-10-04</div>',
  '<img src="userpic/20f425113f20788d7b5b7f25dcb422e5.jpeg" class="img_108x108"></div></li>',
  '<li class="li gallary_item"><div class="pic_box" style="position: relative;">',
  '<div style="position: absolute">2</div>',
  '<a href="/photos-index-aid-392516.html"><img alt="y" data-original="//t4.wnimg2.cfd/data/t/3925/16/17911274095129799.webp" src="loading.jpg"></a>',
  "</div>",
  '<div class="info"><div class="title"><a href="/photos-index-aid-392516.html">ドパギャル。</a></div>',
  '<div class="info_col">51張圖片， 創建於2026-10-04</div></li>',
  "</ul></div>",
  '<div class="f_left paginator"><span class="thispage">1</span>',
  '<a href="/albums-favorite_ranking-page-2-type-week.html">2</a></div>',
].join("\n");

const SEARCH_HTML = [
  '<p class="result">為了紳士漫畫更好的運營生存下去，請將我們加入廣告屏蔽插件白名單</p>',
  '<div class="grid"><div class="gallary_wrap"><ul class="cc">',
  '<li class="li gallary_item"><div class="pic_box cate-1"><a href="/photos-index-aid-394360.html">',
  '<img src="//t4.wnimg2.cfd/data/t/3943/60/17915343177425321.webp"></a></div>',
  '<div class="info"><div class="title"><a href="/photos-index-aid-394360.html">[Sollyz] Anby Secret Love Tape</a></div>',
  '<div class="info_col">36張圖片， 創建於2026-10-09 16:25:17</div></li>',
  '<li class="li gallary_item"><div class="pic_box cate-1"><a href="/photos-index-aid-394359.html">',
  '<img src="//t4.wnimg2.cfd/data/t/3943/59/17915343130026970.webp"></a></div>',
  '<div class="info"><div class="title"><a href="/photos-index-aid-394359.html">[Feathermare] 诅咒の拘束显化</a></div>',
  '<div class="info_col">21張圖片， 創建於2026-10-09 16:25:12</div></li>',
  "</ul></div>",
  // Search spells its page numbers in the query string, not in the path.
  '<div class="f_left paginator"><a href="/search/index.php?q=%E6%BC%A2%E5%8C%96&p=1">1</a>',
  '<span class="thispage">2</span><a href="/search/index.php?q=%E6%BC%A2%E5%8C%96&p=3&c=a~0~1791530673394324~2">3</a>',
  '<a href="/search/index.php?q=%E6%BC%A2%E5%8C%96&p=10">10</a></div>',
].join("\n");

const SEARCH_EMPTY_HTML =
  '<p class="result">大約有 0 項符合查詢結果，庫內數據總量為 338,886 項。（搜索耗時：0.0073秒）</p>' +
  '<div class="grid"><div class="gallary_wrap"><ul class="cc"></ul></div></div>';

const ALBUM_HTML = [
  '<div id="bodywrap" class="userwrap"><h2>沈鸢篇 [SMHTN]</h2><div class="asTB">',
  '<div class="asTBcell uwthumb"><img alt="沈鸢篇 [SMHTN]"',
  ' src="////t4.wnimg2.cfd/data/t/3943/38/17915329324234362.png" width="240" height="302">',
  '<div style="display:none">同人誌／漢化2</div><a id="reader-btn" href="/photos-slide-aid-394338.html">下拉閱讀</a></div>',
  '<div class="asTBcell uwconn">',
  "<label>分類：同人誌／漢化</label><label>頁數：23P</label>",
  '<label title="複製後可在評論/簡介/搜索中引用此作品">編號：wnacg394338</label><label>狀態：連載中</label>',
  '<div class="addtags">標籤：<a class="tagshow" href="/albums-index-tag-SMHTN.html">SMHTN</a>',
  '<a class="tagshow" href="/albums-index-tag-%E7%86%9F%E5%A5%B3.html">熟女</a>',
  '<a onclick="alert(\'權限不足\')" class="btnNarrow">+TAG</a></div>',
  "<p>簡介：第一行<br>第二行</p></div>",
  '<div class="asTBcell uwuinfo"><a href="/albums-user-uid-325922.html"><img src="userpic/20f.jpeg">',
  "<p>AHU777</p></a><p>投搞作品 128 部</p></div>",
  "</div></div>",
].join("\n");

// The same page with nothing after the site's 簡介： label, which is the common case.
const ALBUM_WITHOUT_INTRO_HTML = ALBUM_HTML.replace("<p>簡介：第一行<br>第二行</p>", "<p>簡介：</p>");

function chapterAnchor(aid, order) {
  return [
    '<li class="li gallary_item" data-chid="' + aid + '"><div class="pic_box cate-38">',
    '<a href="/photos-slide-aid-' + aid + '-sid-389918.html"><img src="//t4.wnimg2.cfd/data/t/x/' + aid + '.jpg"></a></div>',
    '<div class="info"><div class="title"><a class="tagshow" href="/photos-slide-aid-' + aid + '-sid-389918.html"',
    ' data-chid="' + aid + '" title="第' + order + '話《似曾相識的她》 82P 2024-07-12">第' + order + "話</a></div>",
    '<div class="info_col">' + aid + "</div></li>",
  ].join("");
}

function seriesHtml(fromAid, count, declared) {
  const anchors = [];
  for (let index = 0; index < count; index += 1) {
    anchors.push(chapterAnchor(String(fromAid + index), fromAid + index));
  }
  return (
    '<div id="bodywrap" class="userwrap"><h2>似曾相識的她</h2>' +
    '<div class="asTBcell uwconn"><label>分類：韓漫／合集</label>' +
    "<label>章節：" + declared + " 話</label><label>狀態：連載中</label>" +
    "<label>編號：wnacg389918</label><p>簡介：</p></div>" +
    '<div class="sr_compact">' + anchors.join("") + "</div>" +
    '<div class="f_left paginator"><span class="thispage">1</span>' +
    '<a href="/photos-index-aid-389918-page-2.html">2</a></div>'
  );
}

const SERIES_PAGE_1_HTML = seriesHtml(257972, 12, 24);
const SERIES_PAGE_2_HTML = seriesHtml(300001, 12, 24);
const SERIES_SHORT_HTML = seriesHtml(368785, 8, 8);

const ITEM_HTML =
  '<script>mReader.initData({"page_url":[' +
  '"http://img5.wnimg2.cfd/data/3943/38/001.png?verify=1791547200-sigOne",' +
  '"http://img5.wnimg2.cfd/data/3943/38/002.png?verify=1791547200-sigTwo",' +
  '"http://img5.wnimg2.cfd/data/3943/38/003.png?verify=1791547200-sigThree",],' +
  '"sns_sys_id":"","next_chap_id":0,"comment_count":0});</script>';

// The legacy slider page writes its list through document.writeln, so every quote arrives escaped,
// and its final entry is the site's own bookmark banner rather than a page.
const GALLERY_HTML = [
  'document.writeln("\t\tvar fast_img_host=\\"\\";");',
  'document.writeln("\t\tvar imglist = [{ url: fast_host_delim+\\"//img5.wnimg2.cfd/data/3943/38/001.png?verify=a\\",',
  ' caption: \\"[001]\\"},{ url: fast_img_host+\\"//img5.wnimg2.cfd/data/3943/38/002.png?verify=b\\",',
  ' caption: \\"[002]\\"}];");',
  'document.writeln("\t\timglist.push({ url: fast_img_host+\\"/themes/weitu/images/bg/shoucang.jpg\\",',
  ' caption: \\"喜歡紳士漫畫的同學請加入收藏哦！\\"});");',
].join("\n").replace("fast_host_delim", "fast_img_host");

const RELEASE_HTML = [
  "<ul>",
  '<li>發佈頁地址: <a href="https://wnacg01.link/" target="_blank">wnacg01.link</a>｜',
  '<a href="https://wnacg02.link/" target="_blank">wnacg02.link</a></li>',
  '<li>紳士漫畫最新地址 <a href="https://www.wn001.cfd/" target="_blank"><i>www.wn001.cfd</i></a></li>',
  '<li>紳士漫畫最新地址 <a href="https://www.wn002.cfd/" target="_blank"><i>www.wn002.cfd</i></a></li>',
  '<li>請使用 <a href="https://www.google.cn/chrome/">Chrome</a> 瀏覽</li>',
  "</ul>",
].join("\n");

const LOGIN_REQUIRED_HTML =
  '<div id="login_form"><input name="login_name"></div><p>您沒有權限，需要登錄</p>';

const LOGIN_OK_JSON = JSON.stringify({ ret: true, html: "登入成功" });
const LOGIN_FAIL_JSON = JSON.stringify({ ret: false, html: "密碼錯誤" });

// --------------------------------------------------------------------------- harness

/**
 * The host hands handlers a `{ signal }` context, and its bridge refuses anything else, so the mocks
 * below enforce the same shape: passing the context straight through as a signal fails here.
 */
function guardSignal(signal) {
  if (signal === undefined || signal === null) return;
  if (typeof signal.addEventListener !== "function" || typeof signal.removeEventListener !== "function") {
    throw new TypeError("signal.addEventListener is not a function");
  }
}

function ctx() {
  return {
    signal: { aborted: false, addEventListener: function () {}, removeEventListener: function () {} },
  };
}

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

/** The login body is the only request the source sends as a form, so it is decoded back for asserts. */
function formBody(request) {
  if (!request.bodyBase64) return {};
  const fields = {};
  Buffer.from(request.bodyBase64, "base64")
    .toString("utf8")
    .split("&")
    .forEach(function (part) {
      if (!part) return;
      const index = part.indexOf("=");
      const key = decodeURIComponent(index < 0 ? part : part.slice(0, index));
      fields[key] = index < 0 ? "" : decodeURIComponent(part.slice(index + 1));
    });
  return fields;
}

function createPlugin(options) {
  const settings = Object.assign({ domain: "" }, (options && options.settings) || {});
  const respond = (options && options.respond) || defaultRespond;
  const requests = [];
  const store = new Map();
  let cookieClears = 0;
  if (options && options.seedHosts) {
    store.set(HOST_STATE_KEY, { hosts: options.seedHosts, updatedAt: NOW });
  }

  const sandbox = {
    console: console,
    Date: { now: function () { return NOW; } },
    Promise: Promise,
    JSON: JSON,
    Math: Math,
    Number: Number,
    String: String,
    Array: Array,
    Object: Object,
    Boolean: Boolean,
    Set: Set,
    Map: Map,
    Error: Error,
    TypeError: TypeError,
    RegExp: RegExp,
    encodeURIComponent: encodeURIComponent,
    decodeURIComponent: decodeURIComponent,
    inkleaf: {
      register: function (value) {
        sandbox.registration = value;
      },
      host: {
        http: {
          request: async function (request, signal) {
            guardSignal(signal);
            requests.push(request);
            const reply = respond(request);
            const body = reply.body === undefined ? "" : reply.body;
            return {
              statusCode: reply.statusCode === undefined ? 200 : reply.statusCode,
              headers: reply.headers || {},
              bodyBase64: Buffer.from(body, "utf8").toString("base64"),
              bodySizeBytes: Buffer.byteLength(body, "utf8"),
            };
          },
          read: async function () {
            throw new Error("fixtures are always returned inline");
          },
          close: async function () {
            return {};
          },
        },
        settings: {
          get: async function (id, signal) {
            guardSignal(signal);
            return settings[id] ? settings[id] : null;
          },
        },
        kv: {
          get: async function (request, signal) {
            guardSignal(signal);
            return store.has(request.key) ? store.get(request.key) : null;
          },
          set: async function (request, signal) {
            guardSignal(signal);
            store.set(request.key, request.value);
            return true;
          },
          delete: async function (request) {
            store.delete(request.key);
            return true;
          },
          keys: async function () {
            return Array.from(store.keys());
          },
        },
        cookie: {
          set: async function () { return { stored: true }; },
          list: async function () { return []; },
          clear: async function (signal) {
            guardSignal(signal);
            cookieClears += 1;
            return { cleared: true };
          },
        },
        log: { write: function () {} },
        clock: { now: function () { return NOW; } },
      },
    },
  };

  vm.runInNewContext(runtimeSource + "\n" + pluginSource, sandbox, { filename: "main.js" });

  return {
    api: sandbox.registration,
    requests: requests,
    store: store,
    cookieClears: function () {
      return cookieClears;
    },
    urls: function () {
      return requests.map(function (request) {
        return request.url;
      });
    },
  };
}

function siteResponse(request) {
  const url = request.url;
  if (url.indexOf("/albums-index-page-15094") >= 0) return { body: LIST_LAST_PAGE_HTML };
  if (url.indexOf("/albums-favorite_ranking") >= 0) return { body: RANKING_HTML };
  if (url.indexOf("/albums-index") >= 0) return { body: LIST_HTML };
  if (url.indexOf("/search/") >= 0) {
    return { body: url.indexOf("q=%E9%9F%93%E6%BC%A2") >= 0 ? SEARCH_EMPTY_HTML : SEARCH_HTML };
  }
  if (url.indexOf("/photos-item-aid-") >= 0) return { body: ITEM_HTML };
  if (url.indexOf("/photos-gallery-aid-") >= 0) return { body: GALLERY_HTML };
  if (url.indexOf("/photos-index-aid-389918-page-2") >= 0) return { body: SERIES_PAGE_2_HTML };
  if (url.indexOf("/photos-index-aid-389918") >= 0) return { body: SERIES_PAGE_1_HTML };
  if (url.indexOf("/photos-index-aid-390062") >= 0) return { body: SERIES_SHORT_HTML };
  if (url.indexOf("/photos-index-aid-394338") >= 0) {
    return { body: request.withFullIntro ? ALBUM_HTML : ALBUM_WITHOUT_INTRO_HTML };
  }
  if (url.indexOf("/photos-index-aid-") >= 0) return { body: ALBUM_WITHOUT_INTRO_HTML };
  return { statusCode: 404, body: "" };
}

function defaultRespond(request) {
  const url = request.url;
  if (url.indexOf("wnacg01.link") >= 0 || url.indexOf("wnacg02.link") >= 0 || url.indexOf("wnlink.ru") >= 0) {
    return { body: RELEASE_HTML };
  }
  if (url.indexOf("/users-check_login.html") >= 0) {
    return { body: LOGIN_OK_JSON };
  }
  if (url.indexOf(DEAD_HOST) >= 0) {
    return { statusCode: 200, headers: { Server: "Parking/1.0" }, body: "<html>domain parked</html>" };
  }
  return siteResponse(request);
}

// --------------------------------------------------------------------------- tests

const harness = createPlugin({ seedHosts: [SITE_HOST] });
const api = harness.api;

async function main() {
  await describeContract();
  await browseLists();
  await rankingAndCategoryRequests();
  await searches();
  await readsDetail();
  await readsChapters();
  await readsPages();
  await rotatesDeadMirrors();
  await honoursManualDomain();
  await discoversMirrorsFromTheReleasePage();
  await reportsLoginWalls();
  await logsInAndOut();

  process.stdout.write("wnacg fixture unit tests passed\n");
}

async function describeContract() {
  const described = plain(await api.describe());
  assert.equal(described.schemaVersion, 1);
  assert.deepEqual(
    described.feeds.map(function (feed) {
      return feed.id;
    }),
    ["latest", "category", "ranking"]
  );
  const category = described.feeds[1].filters[0];
  assert.equal(category.type, "select");
  assert.ok(
    category.options.some(function (option) {
      return option.id === "38" && option.title === "合集";
    }),
    "the 合集 category has to be selectable, it is the only way into a series"
  );
  // The host rejects a blank filter option id, so 全部 needs a sentinel rather than "".
  described.feeds.forEach(function (feed) {
    (feed.filters || []).forEach(function (filter) {
      filter.options.forEach(function (option) {
        assert.ok(option.id && option.id.trim() === option.id, feed.id + " has a blank option id");
      });
    });
  });
  assert.deepEqual(
    described.settings.map(function (setting) {
      return setting.id;
    }),
    ["domain", "account", "password"]
  );
  const password = described.settings[2];
  assert.equal(password.type, "secret");
  assert.equal(password.secret, true, "the password must never render in cleartext");
  assert.deepEqual(
    described.actions.map(function (action) {
      return action.kind;
    }),
    ["action", "login", "verifyCredentials", "clearSession"]
  );
}

async function browseLists() {
  const fresh = createPlugin({ seedHosts: [SITE_HOST] });
  const page = plain(
    await fresh.api.browse({ feedId: "latest", cursor: null, limit: 40, filters: {} }, ctx())
  );
  assert.equal(page.items.length, 3);
  assert.equal(page.nextCursor, "2");
  assert.equal(page.items[0].sourceId, "394338");
  assert.equal(page.items[0].title, "沈鸢篇 [SMHTN]");
  assert.equal(page.items[0].subtitle, "23張圖片， 創建於2026-10-09");
  assert.equal(
    page.items[0].cover.url,
    "https://t4.wnimg2.cfd/data/t/3943/38/17915329324234362.png"
  );
  // The CDN answers 403 to anything without a browser identity, covers included.
  assert.match(page.items[0].cover.headers["user-agent"], /^Mozilla\/5\.0/);

  // data-src wins over the loading.jpg the img still carries.
  assert.equal(
    page.items[1].cover.url,
    "https://t4.wnimg2.cfd/data/t/3943/36/17915312496142575.webp"
  );
  assert.equal(page.items[1].title, "小姐由我來守護|第3季");
  assert.equal(page.items[2].subtitle, "8話， 更新於2026-10-09");

  assert.equal(
    fresh.urls()[0],
    "https://" + SITE_HOST + "/albums-index.html",
    "page one must not ask for -page-1, which the site does not serve"
  );

  const second = plain(
    await fresh.api.browse({ feedId: "latest", cursor: "2", limit: 40, filters: {} }, ctx())
  );
  assert.equal(fresh.urls()[1], "https://" + SITE_HOST + "/albums-index-page-2.html");
  assert.equal(second.items.length, 3);

  const last = plain(
    await fresh.api.browse({ feedId: "latest", cursor: "15094", limit: 40, filters: {} }, ctx())
  );
  assert.equal(last.nextCursor, null, "a paginator with nothing above the current page is the end");
}

async function rankingAndCategoryRequests() {
  const fresh = createPlugin({ seedHosts: [SITE_HOST] });
  await fresh.api.browse({ feedId: "ranking", cursor: "3", limit: 40, filters: { type: "day", cate: "19" } }, ctx());
  assert.equal(
    fresh.urls()[0],
    "https://" + SITE_HOST + "/albums-favorite_ranking-page-3-type-day-cate-19.html"
  );

  await fresh.api.browse({ feedId: "category", cursor: null, limit: 40, filters: { cate: "38" } }, ctx());
  assert.equal(fresh.urls()[1], "https://" + SITE_HOST + "/albums-index-page-1-cate-38.html");

  await fresh.api.browse({ feedId: "category", cursor: null, limit: 40, filters: { cate: "all" } }, ctx());
  assert.equal(
    fresh.urls()[2],
    "https://" + SITE_HOST + "/albums-index.html",
    "the 全部 sentinel must fall back to the unfiltered list"
  );

  const ranked = plain(
    await fresh.api.browse({ feedId: "ranking", cursor: null, limit: 40, filters: { cate: "all" } }, ctx())
  );
  assert.equal(fresh.urls()[3], "https://" + SITE_HOST + "/albums-favorite_ranking-page-1-type-week.html");
  // Ranking rows carry two badge divs ahead of the cover, which used to truncate the search region
  // and leave every thumbnail blank on this feed only.
  assert.equal(ranked.items.length, 2);
  assert.equal(
    ranked.items[0].cover.url,
    "https://t4.wnimg2.cfd/data/t/3925/20/17911274296047289.webp"
  );
  assert.ok(ranked.items[1].cover.url.indexOf("/data/t/") > 0);
  assert.equal(ranked.nextCursor, "2");
}

async function searches() {
  const fresh = createPlugin({ seedHosts: [SITE_HOST] });
  const found = plain(
    await fresh.api.search({ query: "漢化", cursor: "2", limit: 40, filters: {} }, ctx())
  );
  assert.match(
    fresh.urls()[0],
    /\/search\/\?q=%E6%BC%A2%E5%8C%96&f=_all&s=create_time_DESC&syn=yes&p=2$/,
    "search carries its sort and page in the query"
  );
  assert.equal(found.items.length, 2);
  assert.equal(found.items[0].sourceId, "394360");
  // Search paginates with &p= rather than a path segment, and 24 rows per page.
  assert.equal(found.nextCursor, "3");

  const nothing = plain(await fresh.api.search({ query: "韓漢", cursor: null, limit: 40, filters: {} }, ctx()));
  assert.deepEqual(nothing.items, []);
  assert.equal(nothing.nextCursor, null);

  // A bare album number is how readers quote the site, and it resolves as an album.
  const numbered = plain(await fresh.api.search({ query: "394338", cursor: null, limit: 40, filters: {} }, ctx()));
  assert.equal(numbered.items.length, 1);
  assert.equal(numbered.items[0].sourceId, "394338");
  assert.match(fresh.urls()[fresh.urls().length - 1], /\/photos-index-aid-394338\.html/);

  const empty = plain(await fresh.api.search({ query: "  ", cursor: "4", limit: 40, filters: {} }, ctx()));
  assert.equal(empty.items.length, 3, "an empty query falls back to browsing");
  assert.match(fresh.urls()[fresh.urls().length - 1], /\/albums-index-page-4\.html$/);
}

async function readsDetail() {
  const fresh = createPlugin({ seedHosts: [SITE_HOST] });
  const detail = plain(await fresh.api.detail({ sourceId: "394338" }, ctx()));
  assert.match(fresh.urls()[0], /\/photos-index-aid-394338\.html\?order=asc$/);
  assert.equal(detail.sourceId, "394338");
  assert.equal(detail.title, "沈鸢篇 [SMHTN]");
  assert.equal(detail.subtitle, "AHU777");
  assert.equal(detail.status, "連載中");
  assert.deepEqual(detail.tags, ["SMHTN", "熟女"]);
  // Only the four leading slashes were wrong; the address itself is fine.
  assert.equal(detail.cover.url, "https://t4.wnimg2.cfd/data/t/3943/38/17915329324234362.png");
  assert.equal(detail.cover.referer, "https://" + SITE_HOST + "/");

  const bare = plain(await fresh.api.detail({ sourceId: "394338" }, ctx()));
  assert.equal(bare.description, null, "the site's own 簡介： label is not a description");

  const full = createPlugin({
    seedHosts: [SITE_HOST],
    respond: function (request) {
      if (request.url.indexOf("/photos-index-aid-394338") >= 0) return { body: ALBUM_HTML };
      return defaultRespond(request);
    },
  });
  assert.equal(
    (await full.api.detail({ sourceId: "394338" }, ctx())).description,
    "第一行\n第二行"
  );
}

async function readsChapters() {
  const single = createPlugin({ seedHosts: [SITE_HOST] });
  const alone = plain(await single.api.chapters({ sourceId: "394338" }, ctx()));
  assert.equal(alone.chapters.length, 1);
  assert.equal(alone.chapters[0].chapterId, "394338");
  assert.equal(alone.revision, "23", "the page count is what tells an update that nothing was added");

  // A 合集 lists twelve sub-albums per page, so a declared 24 has to be gathered across two.
  const series = createPlugin({ seedHosts: [SITE_HOST] });
  const listed = plain(await series.api.chapters({ sourceId: "389918" }, ctx()));
  assert.equal(listed.chapters.length, 24);
  assert.equal(listed.revision, "24");
  assert.deepEqual(
    series.urls(),
    [
      "https://" + SITE_HOST + "/photos-index-aid-389918.html?order=asc",
      "https://" + SITE_HOST + "/photos-index-aid-389918-page-2.html?order=asc",
    ],
    "the loop must stop at the declared count"
  );
  const ids = listed.chapters.map(function (chapter) {
    return Number(chapter.chapterId);
  });
  assert.deepEqual(ids, ids.slice().sort(function (a, b) { return a - b; }), "oldest sub-album first");
  assert.equal(listed.chapters[0].chapterId, "257972");
  assert.match(listed.chapters[0].title, /^第257972話/);
  assert.deepEqual(plain(listed.chapters[0].opaqueContext), { albumId: "389918" });

  // Fewer chapters than one page: no second request at all.
  const short = createPlugin({ seedHosts: [SITE_HOST] });
  const eight = plain(await short.api.chapters({ sourceId: "390062" }, ctx()));
  assert.equal(eight.chapters.length, 8);
  assert.equal(short.urls().length, 1);
}

async function readsPages() {
  const fresh = createPlugin({ seedHosts: [SITE_HOST] });
  const read = plain(await fresh.api.pages({ sourceId: "389918", chapterId: "257972" }, ctx()));
  assert.equal(
    fresh.urls()[0],
    "https://" + SITE_HOST + "/photos-item-aid-257972.html",
    "the reader page is the primary image list, and it is fetched by sub-album id"
  );
  assert.equal(read.pages.length, 3);
  assert.equal(read.pages[0].pageId, "257972/1");
  assert.equal(read.pages[0].index, 0);
  assert.equal(
    read.pages[0].url,
    "https://img5.wnimg2.cfd/data/3943/38/001.png?verify=1791547200-sigOne",
    "http is upgraded, and the verify signature has to survive untouched"
  );
  assert.equal(read.pages[0].headers["user-agent"].indexOf("Mozilla/5.0"), 0);
  assert.equal(read.pages[0].referer, "https://" + SITE_HOST + "/photos-index-aid-257972.html");
  assert.equal(read.pages[0].imageTransform, undefined, "WNACG serves complete images");

  // The slider page is the fallback, and its trailing banner entry is not a page of the book.
  const fallback = createPlugin({
    seedHosts: [SITE_HOST],
    respond: function (request) {
      if (request.url.indexOf("/photos-item-aid-") >= 0) return { body: "<html>no reader</html>" };
      return defaultRespond(request);
    },
  });
  const legacy = plain(await fallback.api.pages({ sourceId: "394338", chapterId: "394338" }, ctx()));
  assert.equal(legacy.pages.length, 2);
  assert.equal(legacy.pages[1].url, "https://img5.wnimg2.cfd/data/3943/38/002.png?verify=b");
  assert.ok(
    fallback.urls()[1].indexOf("/photos-gallery-aid-394338.html") > 0,
    "the slider page is only requested once the reader page has failed"
  );

  const empty = createPlugin({
    seedHosts: [SITE_HOST],
    respond: function (request) {
      if (request.url.indexOf("/photos-item-aid-") >= 0) return { body: "<html>no reader</html>" };
      if (request.url.indexOf("/photos-gallery-aid-") >= 0) return { body: "<html>empty</html>" };
      return defaultRespond(request);
    },
  });
  await assert.rejects(
    () => empty.api.pages({ sourceId: "390062", chapterId: "390062" }, ctx()),
    (error) => error.code === "NOT_FOUND",
    "a 合集 parent has no images of its own and must say so"
  );
}

async function rotatesDeadMirrors() {
  const fresh = createPlugin({ seedHosts: [DEAD_HOST, SITE_HOST] });
  const page = await fresh.api.browse({ feedId: "latest", cursor: null, limit: 40, filters: {} }, ctx());
  assert.equal(page.items.length, 3);
  assert.deepEqual(
    fresh.urls().map(function (url) {
      return url.split("/")[2];
    }),
    [DEAD_HOST, SITE_HOST],
    "a parked domain must not end the request"
  );
  assert.deepEqual(
    plain(fresh.store.get(HOST_STATE_KEY).hosts),
    [SITE_HOST, DEAD_HOST],
    "the mirror that answered is remembered at the front of the queue"
  );
}

async function honoursManualDomain() {
  const fresh = createPlugin({ seedHosts: [SITE_HOST], settings: { domain: "https://wn002.cfd/" } });
  await fresh.api.browse({ feedId: "latest", cursor: null, limit: 40, filters: {} }, ctx());
  assert.equal(fresh.urls()[0], "https://wn002.cfd/albums-index.html");
}

async function discoversMirrorsFromTheReleasePage() {
  const fresh = createPlugin({});
  await fresh.api.browse({ feedId: "latest", cursor: null, limit: 40, filters: {} }, ctx());
  const state = plain(fresh.store.get(HOST_STATE_KEY));
  // The release page's two mirrors lead, the built-in stale ones stay behind them as a last resort,
  // and the release pages themselves never enter the queue.
  assert.deepEqual(state.hosts.slice(0, 2), [SITE_HOST, "www.wn002.cfd"]);
  assert.deepEqual(
    state.hosts.filter(function (host) {
      return host.indexOf("link") >= 0 || host.indexOf("wnlink") >= 0;
    }),
    []
  );
  assert.ok(
    fresh.urls().some(function (url) {
      return url.indexOf("wnlink.ru") >= 0;
    }),
    "all three release pages are asked, since any one of them can be down"
  );
}

async function reportsLoginWalls() {
  const fresh = createPlugin({
    seedHosts: [SITE_HOST],
    respond: function (request) {
      if (request.url.indexOf("/photos-item-aid-") >= 0) return { body: LOGIN_REQUIRED_HTML };
      return defaultRespond(request);
    },
  });
  await assert.rejects(
    () => fresh.api.pages({ sourceId: "394338", chapterId: "394338" }, ctx()),
    (error) => error.code === "AUTH_REQUIRED" && error.retryable === true
  );
}

async function logsInAndOut() {
  const fresh = createPlugin({
    seedHosts: [SITE_HOST],
    settings: { account: "reader", password: "secret" },
  });
  const result = plain(await fresh.api.invokeAction({ actionId: "login", input: {} }, ctx()));
  assert.equal(result.ok, true);
  assert.equal(result.message, "登录成功");
  assert.equal(result.account, "reader");
  assert.match(result.host, /^www\./);

  const loginRequest = fresh.requests[0];
  assert.equal(loginRequest.method, "POST");
  assert.equal(loginRequest.url, "https://" + SITE_HOST + "/users-check_login.html");
  assert.equal(loginRequest.headers["content-type"], "application/x-www-form-urlencoded; charset=UTF-8");
  assert.equal(loginRequest.headers["x-requested-with"], "XMLHttpRequest");
  assert.deepEqual(formBody(loginRequest), {
    login_name: "reader",
    login_pass: "secret",
    remember_pass: "1",
  });
  assert.equal(fresh.urls().length, 1, "the login post is the only request the action makes");

  // 校验账号 re-runs the same endpoint without a separate probe.
  const verify = createPlugin({
    seedHosts: [SITE_HOST],
    settings: { account: "reader", password: "secret" },
  });
  const checked = plain(await verify.api.invokeAction({ actionId: "verifyCredentials", input: {} }, ctx()));
  assert.equal(checked.message, "账号校验通过");
  assert.equal(verify.urls()[0], "https://" + SITE_HOST + "/users-check_login.html");

  // Nothing to send yet: this is a settings problem, not a credentials one.
  const anonymous = createPlugin({ seedHosts: [SITE_HOST] });
  await assert.rejects(
    () => anonymous.api.invokeAction({ actionId: "login", input: {} }, ctx()),
    (error) => error.code === "INVALID_ARGUMENT",
    "login without settings must ask the reader to fill them in"
  );

  // A rejected login surfaces the site's own message and is not retryable.
  const wrong = createPlugin({
    seedHosts: [SITE_HOST],
    settings: { account: "reader", password: "nope" },
    respond: function (request) {
      if (request.url.indexOf("/users-check_login.html") >= 0) return { body: LOGIN_FAIL_JSON };
      return defaultRespond(request);
    },
  });
  await assert.rejects(
    () => wrong.api.invokeAction({ actionId: "login", input: {} }, ctx()),
    (error) => error.code === "AUTH_REQUIRED" && error.message === "密碼錯誤" && error.retryable === false
  );

  // Logout drops the persisted session cookies; the host owns the jar.
  const session = createPlugin({ seedHosts: [SITE_HOST] });
  const bye = plain(await session.api.invokeAction({ actionId: "logout", input: {} }, ctx()));
  assert.equal(bye.ok, true);
  assert.equal(session.cookieClears(), 1);

  const unknown = createPlugin({ seedHosts: [SITE_HOST] });
  await assert.rejects(
    () => unknown.api.invokeAction({ actionId: "nope", input: {} }, ctx()),
    (error) => error.code === "NOT_FOUND"
  );
}

main().catch(function (error) {
  process.stderr.write((error && error.stack ? error.stack : String(error)) + "\n");
  process.exitCode = 1;
});
