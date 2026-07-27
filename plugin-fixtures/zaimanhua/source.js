// 再漫画 (zaimanhua) source.
//
// Protocol facts live in docs/sources/zaimanhua.md, not here. When the site changes, update that
// document first -- it records what two independent implementations agreed on and where they did
// not, which is the only reason we know which parts of this file are load-bearing.
//
// This file is concatenated with plugin-fixtures/shared/runtime.js by .scripts/build-plugin.ps1,
// so helpers such as text(), asObject(), requestText() and pluginError() are already in scope.

const DEFAULT_API_BASE = "https://v4api.zaimanhua.com/app/v1";

// The `_v` query parameter is a MINIMUM client-version gate, and it is not optional: the detail and
// chapter endpoints answer "漫画不存在或已被删除" without it, which reads like missing content rather
// than a rejected request. Verified 2026-07: 1.0.0 is rejected, 2.3.4 and 9.9.9 pass, garbage is
// rejected. If the site ever raises the floor, every comic will look deleted -- hence the setting,
// so a user can raise this without waiting for a release.
const DEFAULT_APP_VERSION = "2.3.4";

// A plain, current-looking Android UA. The site shows no sign of UA fingerprinting -- the venera
// source has long used an obviously fake one -- but the host injects no default, so without this
// every request would advertise itself as okhttp.
const DEFAULT_USER_AGENT =
  "Mozilla/5.0 (Linux; Android 14; SM-S9210) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

const IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8";
const MAX_CHAPTERS = 5000;
const DEFAULT_PAGE_SIZE = "20";

// Ordered so the common picks come first: the site's own list is 50-odd entries in no useful order,
// and a filter you have to scroll three screens to use is not a filter.
//
// Every id below was verified against the live filter endpoint (2026-07). Six ids carried by the
// venera source return totalNum=0 and are deliberately absent: 3249 泛爱, 3365 西方魔幻,
// 4459 高清单行, 6219 节操, 17192 AA, and 23388 -- which that source lists twice, as both 仙侠 and
// 日常, so neither name was right. An option that always yields an empty page is not a choice.
const THEME_OPTIONS = [
  { id: "0", title: "全部" },
  { id: "3248", title: "热血" },
  { id: "8", title: "爱情" },
  { id: "4", title: "冒险" },
  { id: "7568", title: "搞笑" },
  { id: "13", title: "校园" },
  { id: "7", title: "科幻" },
  { id: "5848", title: "奇幻" },
  { id: "6", title: "格斗" },
  { id: "3245", title: "悬疑" },
  { id: "3254", title: "治愈" },
  { id: "3243", title: "百合" },
  { id: "3246", title: "纯爱" },
  { id: "3324", title: "武侠" },
  { id: "3250", title: "历史" },
  { id: "5", title: "欢乐向" },
  { id: "9", title: "侦探" },
  { id: "10", title: "竞技" },
  { id: "11", title: "魔法" },
  { id: "12", title: "神鬼" },
  { id: "14", title: "惊悚" },
  { id: "17", title: "四格" },
  { id: "3242", title: "亲情" },
  { id: "3244", title: "秀吉" },
  { id: "3251", title: "战争" },
  { id: "3252", title: "萌系" },
  { id: "3253", title: "宅系" },
  { id: "3255", title: "励志" },
  { id: "3325", title: "机战" },
  { id: "3326", title: "音乐舞蹈" },
  { id: "3327", title: "美食" },
  { id: "3328", title: "职场" },
  { id: "4518", title: "TS" },
  { id: "5077", title: "东方" },
  { id: "5806", title: "魔幻" },
  { id: "6316", title: "轻小说" },
  { id: "6437", title: "颜艺" },
  { id: "7900", title: "舰娘" },
  { id: "13627", title: "动画" },
  { id: "18522", title: "福瑞" },
  { id: "23323", title: "生存" },
  { id: "30788", title: "画集" },
  { id: "31137", title: "C100" },
  { id: "16", title: "其他" },
];

const SORT_OPTIONS = [
  { id: "1", title: "最近更新" },
  { id: "2", title: "人气" },
];

const AUDIENCE_OPTIONS = [
  { id: "0", title: "全部受众" },
  { id: "3262", title: "少年漫画" },
  { id: "3263", title: "少女漫画" },
  { id: "3264", title: "青年漫画" },
  { id: "13626", title: "女青漫画" },
];

const PROGRESS_OPTIONS = [
  { id: "0", title: "全部进度" },
  { id: "2309", title: "连载中" },
  { id: "2310", title: "已完结" },
  { id: "29205", title: "短篇" },
];

const ZONE_OPTIONS = [
  { id: "0", title: "全部地区" },
  { id: "2304", title: "日本" },
  { id: "2305", title: "韩国" },
  { id: "2306", title: "欧美" },
  { id: "2307", title: "港台" },
  { id: "2308", title: "内地" },
  { id: "8435", title: "其他" },
];

const RANK_PERIOD_OPTIONS = [
  { id: "0", title: "日榜" },
  { id: "1", title: "周榜" },
  { id: "2", title: "月榜" },
  { id: "3", title: "总榜" },
];

const RANK_TYPE_OPTIONS = [
  { id: "0", title: "人气" },
  { id: "1", title: "吐槽" },
  { id: "2", title: "订阅" },
];

const PAGE_SIZE_OPTIONS = [
  { id: "10", title: "10 条" },
  { id: "20", title: "20 条" },
  { id: "30", title: "30 条" },
  { id: "50", title: "50 条" },
];

const SETTING_DESCRIPTORS = [
  {
    id: "apiBase",
    title: "接口地址",
    type: "text",
    section: "网络",
    defaultValue: DEFAULT_API_BASE,
  },
  {
    id: "appVersion",
    title: "客户端版本号（接口要求的最低值）",
    type: "text",
    section: "网络",
    defaultValue: DEFAULT_APP_VERSION,
  },
  {
    id: "userAgent",
    title: "User-Agent",
    type: "text",
    section: "网络",
    defaultValue: DEFAULT_USER_AGENT,
  },
  {
    id: "imageReferer",
    title: "图片 Referer（留空则不发送）",
    type: "text",
    section: "网络",
    defaultValue: "",
  },
  {
    id: "authToken",
    title: "登录 Token",
    type: "secret",
    secret: true,
    section: "账号",
    defaultValue: "",
  },
  {
    id: "hdImages",
    title: "优先加载高清图",
    type: "boolean",
    section: "内容",
    defaultValue: "true",
  },
  {
    id: "pageSize",
    title: "每页条数",
    type: "select",
    section: "内容",
    defaultValue: DEFAULT_PAGE_SIZE,
    options: PAGE_SIZE_OPTIONS,
  },
];

// Settings are immutable during one isolate lifetime. The host rebuilds this isolate when the user
// leaves the source settings screen, which makes a batch of edits take effect atomically.
let cachedSettings = null;

async function settings() {
  if (cachedSettings) return cachedSettings;
  const [apiBase, appVersion, userAgent, imageReferer, authToken, hdImages, pageSize] =
    await Promise.all([
      inkleaf.host.settings.get("apiBase"),
      inkleaf.host.settings.get("appVersion"),
      inkleaf.host.settings.get("userAgent"),
      inkleaf.host.settings.get("imageReferer"),
      inkleaf.host.settings.get("authToken"),
      inkleaf.host.settings.get("hdImages"),
      inkleaf.host.settings.get("pageSize"),
    ]);
  const requestedSize = text(pageSize);
  cachedSettings = {
    apiBase: (text(apiBase) || DEFAULT_API_BASE).replace(/\/+$/, ""),
    appVersion: text(appVersion) || DEFAULT_APP_VERSION,
    userAgent: text(userAgent) || DEFAULT_USER_AGENT,
    imageReferer: text(imageReferer),
    authToken: text(authToken),
    // Absent means "not configured yet", which should behave like the declared default.
    hdImages: text(hdImages) === "" ? true : text(hdImages) === "true",
    pageSize: PAGE_SIZE_OPTIONS.some(function (option) {
      return option.id === requestedSize;
    })
      ? requestedSize
      : DEFAULT_PAGE_SIZE,
  };
  return cachedSettings;
}

function apiHeaders(active) {
  const headers = {
    Accept: "application/json",
    "User-Agent": active.userAgent,
  };
  if (active.authToken) {
    headers.Authorization = "Bearer " + active.authToken;
  }
  return headers;
}

/** Headers every image request needs. Kept beside apiHeaders so the two cannot drift apart. */
function imageHeaders(active) {
  return { Accept: IMAGE_ACCEPT, "User-Agent": active.userAgent };
}

function imageDescriptor(active, url) {
  const image = { url: url, headers: imageHeaders(active) };
  if (active.imageReferer) image.referer = active.imageReferer;
  return image;
}

async function apiGet(path, parameters, signal) {
  const active = await settings();
  // Every endpoint carries the version gate, not just the two that visibly need it -- a site-side
  // change extending the gate should not turn into a partial outage nobody can explain.
  const query = queryString(Object.assign({}, parameters || {}, { _v: active.appVersion }));
  const result = await requestText(
    {
      method: "GET",
      url: active.apiBase + path + (query ? "?" + query : ""),
      headers: apiHeaders(active),
    },
    signal
  );

  if (result.statusCode === 401 || result.statusCode === 403) {
    throw pluginError("AUTH_REQUIRED", "再漫画拒绝了这次请求，登录 Token 可能已失效", false);
  }
  if (result.statusCode < 200 || result.statusCode >= 300) {
    const error = pluginError(
      "HTTP",
      "再漫画 HTTP " + result.statusCode + (result.body ? ": " + result.body.slice(0, 256) : ""),
      result.statusCode === 429 || result.statusCode >= 500
    );
    error.statusCode = result.statusCode;
    throw error;
  }

  let payload;
  try {
    payload = JSON.parse(result.body);
  } catch (_) {
    throw pluginError("PLUGIN_PROTOCOL", "再漫画返回了非 JSON 响应", false);
  }
  // Structural failures must be loud: a changed envelope should not read as "no results".
  if (payload && payload.errno !== undefined && Number(payload.errno) !== 0) {
    throw pluginError("HTTP", text(payload.errmsg) || "再漫画请求失败", false);
  }
  if (!payload || payload.data === undefined || payload.data === null) {
    throw pluginError("PLUGIN_PROTOCOL", "再漫画响应缺少 data 字段", false);
  }
  return payload.data;
}

function pageFromCursor(cursor) {
  if (cursor === null || cursor === undefined || cursor === "") return 1;
  const page = Number(cursor);
  if (!Number.isInteger(page) || page < 1) {
    throw pluginError("INVALID_ARGUMENT", "Cursor must be a positive page number", false);
  }
  return page;
}

/** The site packs multi-valued fields into one string with mixed separators. */
function splitValues(value) {
  return text(value)
    .split(/[/,，]/g)
    .map(function (item) {
      return item.trim();
    })
    .filter(Boolean);
}

function tagNames(values) {
  return asArray(values)
    .map(function (item) {
      return text(asObject(item).tag_name);
    })
    .filter(Boolean);
}

function formatDate(seconds) {
  const value = finiteNumber(seconds, 0);
  if (value <= 0) return null;
  return new Date(value * 1000).toISOString().slice(0, 10);
}

// List endpoints return the comic id as comic_id, but a few return only id, and some rows carry a
// comic_id of 0. Both reference implementations guard the same way.
function pickComicId(comic) {
  const comicId = finiteNumber(comic.comic_id, 0);
  if (comicId > 0) return String(comicId);
  const fallback = finiteNumber(comic.id, 0);
  return fallback > 0 ? String(fallback) : "";
}

function mapComicSummary(active, value) {
  const comic = asObject(value);
  const sourceId = pickComicId(comic);
  if (!sourceId) return null;
  const authors = splitValues(comic.authors);
  const status = text(comic.status);
  const latest = text(comic.last_update_chapter_name);
  const cover = text(comic.cover);
  return {
    sourceId: sourceId,
    title: text(comic.title) || text(comic.name) || sourceId,
    subtitle: [authors.join(" / "), status, latest].filter(Boolean).join(" · ") || null,
    cover: cover ? imageDescriptor(active, cover) : null,
    tags: splitValues(comic.types),
    opaqueContext: { comicId: sourceId },
  };
}

function mapComicList(active, rows) {
  return dedupeBy(
    asArray(rows).map(function (row) {
      return mapComicSummary(active, row);
    }),
    function (item) {
      return item.sourceId;
    }
  );
}

/** Ranking and update feeds return a bare array with no total, so emptiness is the only end signal. */
function bareArrayPage(active, data, page) {
  const rows = asArray(data);
  return {
    items: mapComicList(active, rows),
    nextCursor: rows.length > 0 ? String(page + 1) : null,
  };
}

async function loadDetailNode(sourceId, signal) {
  const data = await apiGet("/comic/detail/" + encodeURIComponent(sourceId), null, signal);
  // The detail endpoint nests its payload one level deeper than every other endpoint.
  const node = asObject(asObject(data).data);
  if (!Object.keys(node).length) {
    throw pluginError("NOT_FOUND", "未找到该漫画", false);
  }
  return node;
}

function feedDescriptors() {
  return [
    {
      id: "filter",
      title: "分类",
      filters: [
        { id: "theme", title: "题材", type: "select", options: THEME_OPTIONS },
        { id: "sortType", title: "排序", type: "select", options: SORT_OPTIONS },
        { id: "cate", title: "受众", type: "select", options: AUDIENCE_OPTIONS },
        { id: "status", title: "进度", type: "select", options: PROGRESS_OPTIONS },
        { id: "zone", title: "地区", type: "select", options: ZONE_OPTIONS },
      ],
    },
    {
      id: "rank",
      title: "排行榜",
      filters: [
        { id: "byTime", title: "周期", type: "select", options: RANK_PERIOD_OPTIONS },
        { id: "rankType", title: "类型", type: "select", options: RANK_TYPE_OPTIONS },
      ],
    },
    { id: "update", title: "最近更新" },
  ];
}

function pickOption(options, value, fallback) {
  const requested = text(value);
  return options.some(function (option) {
    return option.id === requested;
  })
    ? requested
    : fallback;
}

inkleaf.register({
  describe: async function () {
    return {
      schemaVersion: 1,
      feeds: feedDescriptors(),
      actions: [],
      filters: [],
      settings: SETTING_DESCRIPTORS,
    };
  },

  search: async function (request, context) {
    const query = text(request.query);
    if (!query) throw pluginError("INVALID_ARGUMENT", "Search query is required", false);
    const active = await settings();
    const page = pageFromCursor(request.cursor);
    const size = Number(active.pageSize);
    const data = await apiGet(
      "/search/index",
      { keyword: query, page: page, sort: 0, size: size },
      context && context.signal
    );
    const node = asObject(data);
    const rows = asArray(node.list);
    const total = finiteNumber(node.total, 0);
    const items = mapComicList(active, rows);
    const consumed = page * size;
    return {
      items: items,
      nextCursor: rows.length > 0 && consumed < total ? String(page + 1) : null,
    };
  },

  browse: async function (request, context) {
    const feedId = text(request.feedId);
    const active = await settings();
    const page = pageFromCursor(request.cursor);
    const filters = asObject(request.filters);
    const signal = context && context.signal;

    if (feedId === "filter") {
      const size = Number(active.pageSize);
      const data = await apiGet(
        "/comic/filter/list",
        {
          theme: pickOption(THEME_OPTIONS, filters.theme, "0"),
          cate: pickOption(AUDIENCE_OPTIONS, filters.cate, "0"),
          status: pickOption(PROGRESS_OPTIONS, filters.status, "0"),
          zone: pickOption(ZONE_OPTIONS, filters.zone, "0"),
          sortType: pickOption(SORT_OPTIONS, filters.sortType, "1"),
          page: page,
          size: size,
        },
        signal
      );
      const node = asObject(data);
      const rows = asArray(node.comicList);
      const total = finiteNumber(node.totalNum, 0);
      const consumed = page * size;
      return {
        items: mapComicList(active, rows),
        nextCursor: rows.length > 0 && consumed < total ? String(page + 1) : null,
      };
    }

    if (feedId === "rank") {
      const data = await apiGet(
        "/comic/rank/list",
        {
          page: page,
          rank_type: pickOption(RANK_TYPE_OPTIONS, filters.rankType, "0"),
          by_time: pickOption(RANK_PERIOD_OPTIONS, filters.byTime, "0"),
        },
        signal
      );
      return bareArrayPage(active, data, page);
    }

    if (feedId === "update") {
      const data = await apiGet("/comic/update/list/0/" + page, null, signal);
      return bareArrayPage(active, data, page);
    }

    throw pluginError("INVALID_ARGUMENT", "Unknown feed: " + feedId, false);
  },

  detail: async function (request, context) {
    const sourceId = text(request.sourceId);
    if (!sourceId) throw pluginError("INVALID_ARGUMENT", "sourceId is required", false);
    const active = await settings();
    const node = await loadDetailNode(sourceId, context && context.signal);
    const authors = tagNames(node.authors);
    const status = tagNames(node.status);
    const types = tagNames(node.types);
    const cover = text(node.cover);
    const latest = text(node.last_update_chapter_name);
    return {
      sourceId: sourceId,
      title: text(node.title) || sourceId,
      subtitle: [authors.join(" / "), status.join(" / "), latest].filter(Boolean).join(" · ") || null,
      description: text(node.description) || null,
      cover: cover ? imageDescriptor(active, cover) : null,
      tags: types,
      status: status.join(" / ") || null,
      // Chapters are deliberately NOT cached here. They arrive in this same response, but a long
      // series easily exceeds the host's 64 KiB opaqueContext budget, and losing the whole detail
      // call to a size violation is a worse trade than one extra request when chapters load.
      opaqueContext: { comicId: sourceId },
    };
  },

  chapters: async function (request, context) {
    const sourceId = text(request.sourceId);
    if (!sourceId) throw pluginError("INVALID_ARGUMENT", "sourceId is required", false);
    const node = await loadDetailNode(sourceId, context && context.signal);
    const groups = asArray(node.chapters);
    // Prefix chapter titles with their group only when there is something to disambiguate --
    // a single "连载" group would otherwise stamp its name onto every row for nothing.
    const prefixGroups = groups.length > 1;

    const chapters = [];
    const seen = new Set();
    for (let groupIndex = 0; groupIndex < groups.length; groupIndex += 1) {
      const group = asObject(groups[groupIndex]);
      const groupTitle = text(group.title);
      // The site lists newest-first within each group; readers expect the opposite.
      const rows = asArray(group.data).slice().reverse();
      for (let rowIndex = 0; rowIndex < rows.length && chapters.length < MAX_CHAPTERS; rowIndex += 1) {
        const row = asObject(rows[rowIndex]);
        const chapterId = text(row.chapter_id);
        if (!chapterId || seen.has(chapterId)) continue;
        seen.add(chapterId);
        const number = chapters.length + 1;
        const title = text(row.chapter_title) || "第" + number + "话";
        chapters.push({
          chapterId: chapterId,
          title: prefixGroups && groupTitle ? groupTitle + " - " + title : title,
          number: number,
          publishedAt: formatDate(row.updatetime),
          revision: null,
          // Only an explicit denial locks a chapter. is_fee alone does not: a paid chapter is
          // readable once the account owns it, and hiding it would be a lie about the content.
          available: row.canRead !== false,
          opaqueContext: { comicId: sourceId },
        });
      }
    }
    return { sourceId: sourceId, chapters: chapters, revision: null };
  },

  pages: async function (request, context) {
    const sourceId = text(request.sourceId);
    const chapterId = text(request.chapterId);
    if (!sourceId || !chapterId) {
      throw pluginError("INVALID_ARGUMENT", "sourceId and chapterId are required", false);
    }
    const active = await settings();
    const data = await apiGet(
      "/comic/chapter/" + encodeURIComponent(sourceId) + "/" + encodeURIComponent(chapterId),
      null,
      context && context.signal
    );
    const node = asObject(asObject(data).data);
    const hd = asArray(node.page_url_hd).map(text).filter(Boolean);
    const standard = asArray(node.page_url).map(text).filter(Boolean);
    const preferred = active.hdImages ? hd : standard;
    const fallback = active.hdImages ? standard : hd;
    const urls = preferred.length ? preferred : fallback;

    if (!urls.length) {
      if (node.canRead === false) {
        throw pluginError("AUTH_REQUIRED", "该章节需要更高权限，请在设置中填写登录 Token", false);
      }
      throw pluginError("NOT_FOUND", "该章节没有可读图片", false);
    }

    return {
      sourceId: sourceId,
      chapterId: chapterId,
      revision: request.revision || null,
      pages: urls.map(function (url, index) {
        const page = {
          pageId: chapterId + "-" + (index + 1),
          index: index,
          url: url,
          headers: imageHeaders(active),
        };
        if (active.imageReferer) page.referer = active.imageReferer;
        return page;
      }),
    };
  },
});
