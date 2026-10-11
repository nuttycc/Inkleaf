// 紳士漫畫 (WNACG) online source.
//
// The site publishes no API: every answer here is scraped from the same HTML its own browser
// templates use. Page images come from the reader payload embedded in /photos-item-aid-{aid}.html
// (`mReader.initData({...})`), with the older /photos-gallery-aid slider markup as fallback.
//
// Two properties of the site drive the design:
//   * Mirror churn. Domains are replaced in blocks, so the address list is read from the site's own
//     release page and every request rotates to the next candidate when one answers badly.
//   * Signed image URLs. Each image carries `?verify=<expiry>-<signature>` and the expiry is a
//     fixed point inside the same day, so a chapter opened after that point must have its page list
//     refetched. The host already re-invokes pages() when an image answers 403, which covers it.
//
// plugin.build.json asks .scripts/plugin.py to prepend the shared runtime while packaging, so
// text(), asObject(), asArray(), finiteNumber(), dedupeBy(), responseText() and pluginError() are
// already in scope.

const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/121.0";

// The site's own address-release pages. Each lists the current mirrors under 紳士漫畫最新地址.
const RELEASE_PAGES = ["https://wnacg01.link/", "https://wnacg02.link/", "https://wnlink.ru/"];

// Only used when every release page is unreachable too; the site moves these in blocks.
const FALLBACK_HOSTS = ["www.wn001.cfd", "www.wn002.cfd", "www.wnacg.com", "www.wn10.cfd", "www.wn07.cfd"];

// Never mirrors, even though they appear on the same release page.
const RELEASE_PAGE_HOSTS = ["wnacg01.link", "wnacg02.link", "wnlink.ru", "wnacg.link"];

const HOST_STATE_KEY = "hosts";
const HOST_TTL_MS = 60 * 60 * 1000;
const HOST_TRY_LIMIT = 4;

// The site's own page sizes. Browsing returns a whole page rather than trimming to the host's
// limit, because the page is the pagination unit and a partial page would break next-page probing.
const LIST_PAGE_SIZE = 20;
const SEARCH_PAGE_SIZE = 24;

// A 合集 lists at most this many sub-albums per page, and says how many exist in its 章節 label.
const CHAPTER_PAGE_SIZE = 12;
const MAX_CHAPTER_PAGES = 20;

// Labels below are ours; the strings the parser matches against the site stay Traditional.
// "all" stands for no category: the host rejects a blank filter option id.
const CATEGORY_OPTIONS = [
  ["all", "全部"],
  ["5", "同人志"],
  ["1", "同人志-汉化"],
  ["12", "同人志-日语"],
  ["16", "同人志-English"],
  ["2", "CG画集"],
  ["3", "写真&Cosplay"],
  ["22", "3D漫画"],
  ["37", "AI图集"],
  ["6", "单行本"],
  ["9", "单行本-汉化"],
  ["13", "单行本-日语"],
  ["17", "单行本-English"],
  ["7", "杂志&短篇"],
  ["10", "杂志&短篇-汉化"],
  ["14", "杂志&短篇-日语"],
  ["18", "杂志&短篇-English"],
  ["19", "韩漫"],
  ["20", "韩漫-汉化"],
  ["21", "韩漫-其他"],
  ["38", "合集"],
  ["0", "未分类"],
];

const RANKING_OPTIONS = [
  ["day", "本日排行"],
  ["week", "本周排行"],
  ["month", "本月排行"],
];

const SETTING_IDS = ["domain"];

// --------------------------------------------------------------------------- text

function decodeEntities(value) {
  return String(value === null || value === undefined ? "" : value)
    .replace(/&#(\d{1,7});/g, function (_match, code) {
      const point = Number(code);
      return point > 0 && point < 0x10000 ? String.fromCharCode(point) : "";
    })
    .replace(/&#x([0-9a-fA-F]{1,6});/g, function (_match, code) {
      const point = parseInt(code, 16);
      return point > 0 && point < 0x10000 ? String.fromCharCode(point) : "";
    })
    .replace(/&nbsp;/gi, " ")
    .replace(/&quot;/gi, '"')
    .replace(/&apos;/gi, "'")
    .replace(/&#\d+;/g, "")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&amp;/gi, "&");
}

function stripComments(value) {
  return String(value === null || value === undefined ? "" : value).replace(
    /<!--[\s\S]*?-->/g,
    ""
  );
}

function visibleText(value) {
  return decodeEntities(stripComments(value).replace(/<br\s*\/?>/gi, "\n").replace(/<[^>]*>/g, ""))
    .replace(/[ \t\r]+/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function oneLine(value) {
  return visibleText(value).replace(/\s*\n\s*/g, " ").trim();
}

function firstMatch(source, pattern) {
  const found = String(source || "").match(pattern);
  return found ? found[1] : "";
}

/** Like firstMatch, for patterns that select a region rather than capture a value. */
function firstRegion(source, pattern) {
  const found = String(source || "").match(pattern);
  return found ? found[0] : "";
}

// --------------------------------------------------------------------------- urls

function toHost(value) {
  const raw = String(value || "").trim();
  if (!raw) return "";
  return raw.replace(/^https?:\/\//i, "").split("/")[0].toLowerCase();
}

function hostBase(host) {
  return "https://" + toHost(host);
}

function orderedHosts(values) {
  const seen = [];
  asArray(values)
    .filter(function (value) {
      return typeof value === "string";
    })
    .map(toHost)
    .filter(function (host) {
      return host && host.indexOf(".") > 0 && RELEASE_PAGE_HOSTS.indexOf(host) < 0;
    })
    .forEach(function (host) {
      if (seen.indexOf(host) < 0) seen.push(host);
    });
  return seen;
}

/**
 * The site emits protocol-relative URLs, sometimes with the slash count wrong (`////host/path` on
 * album pages), and image hosts answer over plain http. Both are normalised to an absolute https URL.
 */
function toAbsolute(raw, base) {
  let value = decodeEntities(String(raw || "").trim());
  if (!value || value.indexOf("javascript") === 0) return "";
  value = value.replace(/\\/g, "");
  if (/^\/{2,}/.test(value)) return "https://" + value.replace(/^\/+/, "");
  if (/^https?:\/\//i.test(value)) return value.replace(/^http:\/\//i, "https://");
  if (!base) return "";
  return base + (value.charAt(0) === "/" ? value : "/" + value);
}

function albumPath(aid) {
  return "/photos-index-aid-" + aid + ".html";
}

function imageRequest(url, referer) {
  return {
    url: url,
    headers: {
      "user-agent": USER_AGENT,
      accept: "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
      referer: referer,
    },
    referer: referer,
  };
}

// --------------------------------------------------------------------------- list parsing

const LIST_ITEM_PATTERN = /<li[^>]*class="[^"]*gallary_item[\s\S]*?<\/li>/g;

function albumIdFromHref(href) {
  const match = String(href || "").match(/[-/]aid-(\d+)/);
  return match ? match[1] : "";
}

// Every list row, thumbnail and uploader block on the site carries <img> tags, and only the cover
// points at album art: the rest are lazy-load placeholders, avatars and static chrome.
const NON_COVER_HINTS = ["loading.jpg", "data:", "userpic/", "statics/", "/themes/"];

function looksLikeCover(value) {
  if (!value) return false;
  for (let index = 0; index < NON_COVER_HINTS.length; index += 1) {
    if (value.indexOf(NON_COVER_HINTS[index]) >= 0) return false;
  }
  return true;
}

function imageSourceFromBlock(block) {
  const tags = String(block).match(/<img[^>]*>/g) || [];
  for (let index = 0; index < tags.length; index += 1) {
    const tag = tags[index];
    const candidates = [
      firstMatch(tag, /data-original="([^"]*)"/i),
      firstMatch(tag, /data-src="([^"]*)"/i),
      firstMatch(tag, /data-lazyload="([^"]*)"/i),
      firstMatch(tag, /src="([^"]*)"/i),
    ];
    for (let inner = 0; inner < candidates.length; inner += 1) {
      const value = String(candidates[inner] || "").trim();
      if (looksLikeCover(value)) return value;
    }
  }
  return "";
}

function parseListItems(html, base) {
  const blocks = String(html || "").match(LIST_ITEM_PATTERN) || [];
  const rows = [];
  for (let index = 0; index < blocks.length; index += 1) {
    const block = blocks[index];
    const titleBlock = firstMatch(block, /class="title"[\s\S]*?<a[^>]*>([\s\S]*?)<\/a>/i);
    const href =
      firstMatch(block, /class="title"[\s\S]*?<a[^>]*href="([^"]*)"/i) ||
      firstMatch(block, /<a[^>]*href="(\/photos-[^"]*)"/i);
    const sourceId = albumIdFromHref(href);
    if (!sourceId) continue;
    const title = oneLine(titleBlock) || oneLine(firstMatch(block, /title="([^"]*)"/i));
    // The whole row is searched rather than just pic_box: on the ranking page a rank-badge div
    // closes before the cover <img> ever appears, which truncated the region to nothing useful.
    const coverSource = imageSourceFromBlock(block);
    const info = oneLine(firstMatch(block, /class="info_col">([\s\S]*?)<\/div>/i));
    rows.push({
      sourceId: sourceId,
      title: title || "绅士漫画 #" + sourceId,
      subtitle: info,
      coverUrl: coverSource ? toAbsolute(coverSource, base) : "",
    });
  }
  return rows;
}

function summaryOf(row, base) {
  const summary = { sourceId: row.sourceId, title: row.title, subtitle: row.subtitle || null };
  if (row.coverUrl) {
    summary.cover = imageRequest(row.coverUrl, hostBase(toHost(base)) + "/");
  }
  return summary;
}

/**
 * Pagination marks the current page with `class="thispage"` and prints its neighbours as anchors.
 * Album lists spell page numbers inside the path (`-page-3`) while search spells them in the query
 * (`&p=3`), so both forms count. A page is the last one when no anchor names a higher page.
 */
function paginatorOf(html) {
  const region =
    firstMatch(html, /class="f_left paginator">([\s\S]*?)<\/div>/i) ||
    firstMatch(html, /class="[^"]*paginator[^"]*">([\s\S]*?)<\/div>/i);
  const clean = stripComments(region || "");
  const current = Number(firstMatch(clean, /class="thispage">(\d+)/i) || 0);
  const pattern = /(?:-page-|[?&]p=)(\d+)/g;
  let lastPage = current;
  let found = pattern.exec(clean);
  while (found) {
    const candidate = Number(found[1]);
    if (candidate > lastPage) lastPage = candidate;
    found = pattern.exec(clean);
  }
  return { current: current, lastPage: lastPage, hasNext: lastPage > current };
}

/** `rows.length` only decides the answer when a page carries no paginator at all. */
function nextCursorFor(html, page, rows, pageSize) {
  if (!rows.length) return null;
  const paginator = paginatorOf(html);
  if (paginator.current) return paginator.hasNext ? String(page + 1) : null;
  return rows.length >= pageSize ? String(page + 1) : null;
}

// --------------------------------------------------------------------------- album page parsing

/**
 * The label row and the intro paragraph live in uwconn, and the uploader block follows it. Stopping
 * at that boundary keeps an album with no intro from reporting the uploader's name as its text.
 */
function albumRegion(html) {
  const source = String(html || "");
  const start = source.indexOf('class="asTBcell uwconn"');
  if (start < 0) return "";
  const uploader = source.indexOf('class="asTBcell uwuinfo"', start);
  return source.slice(start, uploader > start ? uploader : start + 12000);
}

function labelsOf(html) {
  const region = albumRegion(html);
  if (!region) return [];
  return (region.match(/<label[^>]*>[\s\S]*?<\/label>/g) || []).map(oneLine);
}

function labelValue(html, name) {
  const labels = labelsOf(html);
  for (let index = 0; index < labels.length; index += 1) {
    const match = labels[index].match(new RegExp("^" + name + "：(.*)$"));
    if (match) return match[1].trim();
  }
  return "";
}

function parseAlbumDetail(html, base, sourceId) {
  const title = oneLine(firstMatch(html, /class="userwrap">[\s\S]*?<h2[^>]*>([\s\S]*?)<\/h2>/i));
  const coverSource = imageSourceFromBlock(
    firstRegion(html, /class="asTBcell uwthumb"[\s\S]*?<\/div>/i) ||
      firstRegion(html, /class="uwthumb"[\s\S]*?<\/div>/i)
  );
  const tagsBlock = firstMatch(html, /class="addtags">([\s\S]*?)<\/div>/i);
  const tagAnchors = String(tagsBlock || "").match(/<a[^>]*class="tagshow"[^>]*>[\s\S]*?<\/a>/g) || [];
  const tags = tagAnchors
    .map(function (anchor) {
      return oneLine(anchor.replace(/<[^>]*>/g, " "));
    })
    .filter(Boolean);

  // The intro paragraph is always prefixed with the site's own 簡介： label, and most albums have
  // nothing after it, which must not become a description of one character.
  const description = visibleText(firstMatch(albumRegion(html), /<p[^>]*>([\s\S]*?)<\/p>/i)).replace(
    /^簡介[：:]\s*/,
    ""
  );
  // The uploader block is absent on some albums, and its first paragraph is the name.
  const uploader = oneLine(
    firstMatch(firstRegion(html, /class="asTBcell uwuinfo"[\s\S]*?<\/div>/i), /<p[^>]*>([\s\S]*?)<\/p>/i)
  );
  const number = labelValue(html, "編號").replace(/[^0-9]/g, "");
  const status = labelValue(html, "狀態");
  const detail = {
    sourceId: number || sourceId,
    title: title || "绅士漫画 #" + sourceId,
    subtitle: uploader || labelValue(html, "分類") || null,
    description: description || null,
    tags: tags.slice(0, 40),
    status: status || null,
  };
  if (coverSource) detail.cover = imageRequest(toAbsolute(coverSource, base), base + "/");
  return detail;
}

function pageCountOf(html) {
  const value = labelValue(html, "頁數").match(/(\d+)/);
  return value ? Number(value[1]) : 0;
}

function declaredChapterCount(html) {
  const value = labelValue(html, "章節").match(/(\d+)/);
  return value ? Number(value[1]) : 0;
}

/**
 * 合集 pages list their sub-albums, and only those: a plain album carries no data-chid at all.
 * The anchor sits in different containers depending on the viewer's stored list/thumbnail
 * preference, so the scan is over the whole page rather than one subtree.
 */
function parseChapterRows(html) {
  const anchors = String(html || "").match(/<a[^>]*data-chid="\d+"[\s\S]*?<\/a>/g) || [];
  const rows = [];
  const seen = {};
  for (let index = 0; index < anchors.length; index += 1) {
    const anchor = anchors[index];
    const chapterId = firstMatch(anchor, /data-chid="(\d+)"/);
    if (!chapterId || seen[chapterId]) continue;
    seen[chapterId] = true;
    const title =
      oneLine(firstMatch(anchor, /title="([^"]*)"/i)) ||
      oneLine(anchor.replace(/<[^>]*>/g, " "));
    rows.push({
      chapterId: chapterId,
      title: title || "第" + (rows.length + 1) + "话",
    });
  }
  return rows;
}

// --------------------------------------------------------------------------- image list parsing

/**
 * The reader page embeds `mReader.initData({"page_url":[...], ...})`. The object is valid JSON apart
 * from a trailing comma the site leaves behind, so that is removed before parsing.
 */
function parseReaderPayload(html) {
  const source = String(html || "");
  const marker = source.indexOf("mReader.initData(");
  if (marker < 0) return [];
  let start = source.indexOf("{", marker);
  if (start < 0) return [];
  let depth = 0;
  let end = -1;
  let quoted = false;
  let escaped = false;
  for (let index = start; index < source.length; index += 1) {
    const character = source.charAt(index);
    if (escaped) {
      escaped = false;
      continue;
    }
    if (character === "\\") {
      escaped = true;
      continue;
    }
    if (character === '"') {
      quoted = !quoted;
      continue;
    }
    if (quoted) continue;
    if (character === "{") depth += 1;
    else if (character === "}") {
      depth -= 1;
      if (depth === 0) {
        end = index + 1;
        break;
      }
    }
  }
  if (end < 0) return [];
  let payload;
  try {
    payload = JSON.parse(source.slice(start, end).replace(/,\s*([}\]])/g, "$1"));
  } catch (error) {
    return [];
  }
  return asArray(payload.page_url)
    .map(function (url) {
      return String(url || "").trim();
    })
    .filter(Boolean);
}

/**
 * Fallback for the legacy slider page, which writes its list through `document.writeln`, so every
 * quote arrives escaped. The final entry is the site's own bookmark banner and carries no host.
 */
function parseGalleryPayload(html) {
  const source = String(html || "").replace(/\\"/g, '"');
  const found = source.match(/url:\s*fast_img_host\+"([^"]*)"/g) || [];
  const urls = [];
  for (let index = 0; index < found.length; index += 1) {
    const value = found[index].replace(/^[\s\S]*fast_img_host\+"/, "").replace(/"$/, "");
    if (value.indexOf("//") !== 0) continue;
    urls.push(value);
  }
  return urls;
}

// --------------------------------------------------------------------------- settings & storage

let settingCache = null;
let hostState = null;

async function readSettings(signal) {
  if (settingCache) return settingCache;
  const values = await Promise.all(
    SETTING_IDS.map(function (id) {
      // An unreadable setting means "use the default", which is the ordinary case.
      return inkleaf.host.settings.get(id, signal).catch(function () {
        return null;
      });
    })
  );
  settingCache = {};
  SETTING_IDS.forEach(function (id, index) {
    settingCache[id] = text(values[index]);
  });
  return settingCache;
}

async function storedValue(key, signal) {
  const value = await inkleaf.host.kv.get({ key: key }, signal);
  return value === null || value === undefined ? null : value;
}

async function remember(key, value, signal) {
  await inkleaf.host.kv.set({ key: key, value: value }, signal);
}

// --------------------------------------------------------------------------- host discovery

function normaliseState(value) {
  const state = asObject(value);
  const hosts = orderedHosts(asArray(state.hosts));
  return { hosts: hosts.length ? hosts : FALLBACK_HOSTS.slice(), updatedAt: finiteNumber(state.updatedAt, 0) };
}

async function hostQueue(signal) {
  const settings = await readSettings(signal);
  const stored = normaliseState(await storedValue(HOST_STATE_KEY, signal));
  if (Date.now() - stored.updatedAt >= HOST_TTL_MS) {
    const discovered = await discoverHosts(stored, settings, signal);
    hostState = discovered;
    await remember(HOST_STATE_KEY, discovered, signal);
  } else {
    hostState = stored;
  }
  // A manual domain always leads; the discovered list stays behind it as the rotation pool.
  return orderedHosts([settings.domain].concat(hostState.hosts));
}

/**
 * Cheap by design: one release page is enough, and probing the mirrors for liveness is done by the
 * request loop itself so a cold isolate never pays for a sweep inside the NORMAL deadline.
 */
async function discoverHosts(fallback, settings, signal) {
  const released = await loadReleaseHosts(signal);
  const hosts = orderedHosts(
    [settings.domain].concat(released, fallback.hosts, FALLBACK_HOSTS)
  );
  return { hosts: hosts.length ? hosts : FALLBACK_HOSTS.slice(), updatedAt: Date.now() };
}

async function loadReleaseHosts(signal) {
  const attempts = await Promise.all(
    RELEASE_PAGES.map(function (url) {
      return inkleaf.host.http
        .request({ method: "GET", url: url, headers: { "user-agent": USER_AGENT } }, signal)
        .then(function (response) {
          return responseText(response, signal).then(function (body) {
            if (response.statusCode < 200 || response.statusCode >= 300) return [];
            return hostsFromReleasePage(body);
          });
        })
        .catch(function () {
          return [];
        });
    })
  );
  return orderedHosts(
    attempts.reduce(function (all, part) {
      return all.concat(part);
    }, [])
  );
}

/** The release page lists both its own mirrors and the site's addresses; only the marked line counts. */
function hostsFromReleasePage(html) {
  const blocks = String(html || "").split(/<\/li>/i);
  const hosts = [];
  for (let index = 0; index < blocks.length; index += 1) {
    if (blocks[index].indexOf("最新地址") < 0) continue;
    const links = blocks[index].match(/href="https?:\/\/[^"/]+/g) || [];
    for (let inner = 0; inner < links.length; inner += 1) {
      hosts.push(toHost(links[inner].replace(/^href="https?:\/\//, "")));
    }
  }
  return orderedHosts(hosts);
}

function headerOf(response, name) {
  const headers = asObject(response.headers);
  const wanted = String(name).toLowerCase();
  const keys = Object.keys(headers);
  for (let index = 0; index < keys.length; index += 1) {
    if (keys[index].toLowerCase() === wanted) return String(headers[keys[index]] || "");
  }
  return "";
}

function isTransportError(error) {
  if (!error) return false;
  const code = String(error.code || "");
  if (code === "NETWORK" || code === "TIMEOUT") return true;
  return /timeout|network|connect|host|address|ssl|tls/i.test(String(error.message || ""));
}

/**
 * A retired mirror either answers from the registrar's parking server or fails outright; both mean
 * "try the next candidate" rather than "this album is missing".
 */
function isDeadMirror(response) {
  if (headerOf(response, "server").toLowerCase().indexOf("parking") >= 0) return true;
  return response.statusCode >= 500 || response.statusCode === 404 || response.statusCode === 410;
}

/** Fetches one site page, rotating the host pool until a live mirror answers. */
async function siteRequest(path, signal) {
  const queue = await hostQueue(signal);
  let lastError = null;
  for (let index = 0; index < Math.min(queue.length, HOST_TRY_LIMIT); index += 1) {
    const base = hostBase(queue[index]);
    let response;
    let body = "";
    try {
      response = await inkleaf.host.http.request(
        {
          method: "GET",
          url: base + path,
          headers: {
            "user-agent": USER_AGENT,
            accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            referer: base + "/",
          },
        },
        signal
      );
      body = await responseText(response, signal);
    } catch (error) {
      lastError = error;
      if (!isTransportError(error)) throw error;
      continue;
    }
    if (isDeadMirror(response)) {
      lastError = pluginError("NETWORK", "绅士漫画域名 " + queue[index] + " 已失效", true);
      continue;
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      lastError = pluginError("HTTP", "绅士漫画接口状态码 " + response.statusCode, true);
      continue;
    }
    if (visibleText(body).indexOf("需要登錄") >= 0 || /id="login_form"/i.test(body)) {
      throw pluginError("AUTH_REQUIRED", "这本漫画需要登录绅士漫画账号后才能阅读", true);
    }
    if (index > 0) await promoteHost(queue[index], signal);
    return { html: body, base: base };
  }
  throw lastError || pluginError("NETWORK", "没有可用的绅士漫画域名", true);
}

/** Keeps the last mirror that answered at the front, so the next call skips the dead ones. */
async function promoteHost(host, signal) {
  const state = normaliseState(await storedValue(HOST_STATE_KEY, signal));
  hostState = {
    hosts: orderedHosts([host].concat(state.hosts)),
    updatedAt: Date.now(),
  };
  await remember(HOST_STATE_KEY, hostState, signal);
}

// --------------------------------------------------------------------------- mapping

function rowsToItems(rows, base) {
  return dedupeBy(
    rows.map(function (row) {
      return summaryOf(row, base);
    }),
    function (item) {
      return item.sourceId;
    }
  );
}

function pageFromCursor(cursor) {
  const parsed = finiteNumber(cursor, 1);
  return parsed < 1 ? 1 : Math.floor(parsed);
}

/** The 全部 option is a sentinel, not a category id the site understands. */
function categoryOf(value) {
  const cate = text(value);
  return !cate || cate === "all" ? "" : cate;
}

function listPath(page, cate) {
  if (cate) return "/albums-index-page-" + page + "-cate-" + cate + ".html";
  return page > 1 ? "/albums-index-page-" + page + ".html" : "/albums-index.html";
}

// --------------------------------------------------------------------------- describe

async function describe() {
  function options(pairs) {
    return pairs.map(function (pair) {
      return { id: pair[0], title: pair[1] };
    });
  }
  return {
    schemaVersion: 1,
    feeds: [
      { id: "latest", title: "最新上传" },
      {
        id: "category",
        title: "分类书库",
        filters: [
          { id: "cate", title: "分类", type: "select", options: options(CATEGORY_OPTIONS) },
        ],
      },
      {
        id: "ranking",
        title: "排行榜",
        filters: [
          { id: "type", title: "时间", type: "select", options: options(RANKING_OPTIONS) },
          { id: "cate", title: "分类", type: "select", options: options(CATEGORY_OPTIONS) },
        ],
      },
    ],
    actions: [{ id: "refreshDomains", title: "重新获取域名", kind: "action" }],
    settings: [
      {
        id: "domain",
        title: "指定域名（留空自动从发布页获取）",
        type: "text",
        section: "线路",
      },
    ],
  };
}

// --------------------------------------------------------------------------- handlers

async function search(request, context) {
  const signal = context && context.signal;
  const query = text(request.query);
  if (!query) {
    return browse({ feedId: "latest", cursor: request.cursor, filters: request.filters }, { signal: signal });
  }

  const page = pageFromCursor(request.cursor);
  // The site resolves a bare album number directly, and 編號 is what readers copy around.
  const directId = query.replace(/[^0-9]/g, "");
  if (directId === query && /^\d{3,}$/.test(directId) && page === 1) {
    try {
      const found = await siteRequest(albumPath(directId), signal);
      const rows = parseListItems(found.html, found.base);
      const detail = parseAlbumDetail(found.html, found.base, directId);
      const items = rows.length ? rowsToItems(rows, found.base) : [];
      if (detail.title && items.length === 0) {
        items.push({
          sourceId: detail.sourceId || directId,
          title: detail.title,
          subtitle: detail.subtitle,
          cover: detail.cover || null,
        });
      }
      return { items: dedupeBy(items, function (item) { return item.sourceId; }), nextCursor: null };
    } catch (error) {
      if (isTransportError(error)) return { items: [], nextCursor: null };
    }
  }

  let path = "/search/?q=" + encodeURIComponent(query) + "&f=_all&s=create_time_DESC&syn=yes";
  if (page > 1) path += "&p=" + page;
  const found = await siteRequest(path, signal);
  const rows = parseListItems(found.html, found.base);
  return {
    items: rowsToItems(rows, found.base),
    nextCursor: nextCursorFor(found.html, page, rows, SEARCH_PAGE_SIZE),
  };
}

async function browse(request, context) {
  const signal = context && context.signal;
  const page = pageFromCursor(request.cursor);
  const filters = request.filters || {};
  const cate = categoryOf(filters.cate);

  let path;
  if (request.feedId === "ranking") {
    const type = text(filters.type) || "week";
    path =
      "/albums-favorite_ranking-page-" +
      page +
      "-type-" +
      encodeURIComponent(type) +
      (cate ? "-cate-" + cate : "") +
      ".html";
  } else {
    path = listPath(page, cate);
  }

  const found = await siteRequest(path, signal);
  const rows = parseListItems(found.html, found.base);
  return {
    items: rowsToItems(rows, found.base),
    nextCursor: nextCursorFor(found.html, page, rows, LIST_PAGE_SIZE),
  };
}

async function detail(request, context) {
  const signal = context && context.signal;
  const sourceId = text(request.sourceId);
  const found = await siteRequest(albumPath(sourceId) + "?order=asc", signal);
  return parseAlbumDetail(found.html, found.base, sourceId);
}

/**
 * A 合集 is one album whose sub-albums carry their own aid, and the index lists twelve per page.
 * Without the declared count we would silently offer the first twelve chapters of a fifty-chapter
 * album, so the 章節 label drives the loop and the paginator only ends it early.
 */
async function chapters(request, context) {
  const signal = context && context.signal;
  const sourceId = text(request.sourceId);
  const first = await siteRequest(albumPath(sourceId) + "?order=asc", signal);
  const declared = declaredChapterCount(first.html);
  let rows = parseChapterRows(first.html);

  // The declared count is the bound: an album lists twelve sub-albums per page and says how many it
  // has in its 章節 label, so the loop knows to stop without trusting the paginator.
  const limit = Math.min(MAX_CHAPTER_PAGES, Math.max(1, Math.ceil(declared / CHAPTER_PAGE_SIZE)));
  for (let pageNumber = 2; rows.length < declared && pageNumber <= limit; pageNumber += 1) {
    let extra = [];
    try {
      const next = await siteRequest(
        "/photos-index-aid-" + sourceId + "-page-" + pageNumber + ".html?order=asc",
        signal
      );
      extra = parseChapterRows(next.html);
    } catch (error) {
      if (!isTransportError(error)) throw error;
      break;
    }
    if (!extra.length) break;
    rows = rows.concat(extra);
  }

  if (!rows.length) {
    return {
      sourceId: sourceId,
      chapters: [
        {
          chapterId: sourceId,
          title: "第1話",
          number: 1,
          available: true,
          opaqueContext: { albumId: sourceId },
        },
      ],
      revision: String(pageCountOf(first.html) || 0),
    };
  }

  // The site orders by its own recency preference; aids only ever increase with time.
  rows.sort(function (left, right) {
    return Number(left.chapterId) - Number(right.chapterId);
  });

  return {
    sourceId: sourceId,
    chapters: dedupeBy(rows, function (row) {
      return row.chapterId;
    }).map(function (row, index) {
      return {
        chapterId: row.chapterId,
        title: row.title || "第" + (index + 1) + "话",
        number: index + 1,
        available: true,
        opaqueContext: { albumId: sourceId },
      };
    }),
    revision: String(declared || rows.length),
  };
}

async function pages(request, context) {
  const signal = context && context.signal;
  const sourceId = text(request.sourceId);
  const chapterId = text(request.chapterId) || sourceId;

  // A 合集's parent answers with its first sub-album rather than an error, so chapter ids coming
  // from chapters() are always the sub-album's own aid and only the bare album id is trusted here.
  const found = await siteRequest("/photos-item-aid-" + chapterId + ".html", signal);
  let urls = parseReaderPayload(found.html);
  if (!urls.length) {
    const legacy = await siteRequest("/photos-gallery-aid-" + chapterId + ".html", signal);
    urls = parseGalleryPayload(legacy.html);
    if (urls.length) found.base = legacy.base;
  }
  if (!urls.length) {
    throw pluginError("NOT_FOUND", "这一话没有可读图片（合集请从章节列表进入）", false);
  }

  const referer = found.base + albumPath(chapterId);
  const normalised = [];
  const seen = {};
  for (let index = 0; index < urls.length; index += 1) {
    const url = toAbsolute(urls[index], "");
    if (!url || seen[url]) continue;
    seen[url] = true;
    normalised.push(url);
  }

  return {
    sourceId: sourceId,
    chapterId: chapterId,
    pages: normalised.map(function (url, index) {
      return Object.assign(
        {
          pageId: chapterId + "/" + (index + 1),
          index: index,
        },
        imageRequest(url, referer)
      );
    }),
  };
}

async function invokeAction(request, context) {
  const signal = context && context.signal;
  const actionId = text(request.actionId);

  if (actionId === "refreshDomains") {
    await remember(HOST_STATE_KEY, { hosts: [], updatedAt: 0 }, signal);
    const settings = await readSettings(signal);
    const released = await loadReleaseHosts(signal);
    const hosts = orderedHosts(
      [settings.domain].concat(released, normaliseState(await storedValue(HOST_STATE_KEY, signal)).hosts, FALLBACK_HOSTS)
    );
    hostState = { hosts: hosts, updatedAt: Date.now() };
    await remember(HOST_STATE_KEY, hostState, signal);

    // Confirm the front of the queue actually serves the site before reporting it.
    const probe = await siteRequest("/albums-index-page-1.html", signal);
    return {
      ok: true,
      message: "已重新获取域名",
      hosts: hosts,
      active: toHost(probe.base),
      usable: parseListItems(probe.html, probe.base).length > 0,
    };
  }

  throw pluginError("NOT_FOUND", "未知操作: " + actionId, false);
}

inkleaf.register({
  describe: describe,
  search: search,
  browse: browse,
  detail: detail,
  chapters: chapters,
  pages: pages,
  invokeAction: invokeAction,
});
