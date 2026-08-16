(function () {
  "use strict";

  const DEFAULT_API_BASE = "https://api.manga2025.com/api/v3";
  const PLATFORM = "1";
  const BROWSE_PLATFORM = "3";
  const MAX_SEARCH_LIMIT = 21;
  const CHAPTER_PAGE_LIMIT = 500;
  // 拷贝系线路会按 UA 与 version 头做门禁：非浏览器/非客户端 UA 直接返回纯文本 "error"，
  // 过旧的 version 会触发 210。以下对齐官方 App 值（社区客户端 copymanga-downloader 已验证可用）。
  const COPY_APP_USER_AGENT = "COPY/3.0.0";
  const COPY_APP_VERSION = "2025.08.15";

  // Keep verified route knowledge in the plugin package so endpoint changes do not leak into
  // Android UI or become part of the host contract.
  const API_DOMAIN_OPTIONS = [
    { id: "https://api.manga2025.com/api/v3", title: "默认 · 热辣漫画线路 2" },
    { id: "https://api.2026copy.com/api/v3", title: "大陆专线新站" },
    { id: "https://mapi.copy20.com/api/v3", title: "大陆专线 1" },
    { id: "https://mapi.copy2000.site/api/v3", title: "大陆专线 2" },
    { id: "https://api.2025copy.com/api/v3", title: "大陆专线 3" },
    { id: "https://api.mangacopy.com/api/v3", title: "国际服" },
    { id: "https://api.copy2000.online/api/v3", title: "国际服 1" },
    { id: "https://api.copy202601.com/api/v3", title: "国际服 2 · 2026 新站" },
    { id: "https://mapi.hotmangasd.com/api/v3", title: "热辣漫画线路 1" },
    { id: "https://mapi.hotmangasf.com/api/v3", title: "热辣漫画线路 3" },
    { id: "https://mapi.hotmangasg.com/api/v3", title: "热辣漫画线路 4" },
    { id: "https://mapi.elfgjfghkk.club/api/v3", title: "热辣漫画线路 5" },
    { id: "https://mapi.fgjfghkk.club/api/v3", title: "热辣漫画线路 6" },
    { id: "https://mapi.fgjfghkkcenter.club/api/v3", title: "热辣漫画线路 7" }
  ];
  const SETTING_DESCRIPTORS = [
    {
      id: "apiDomain",
      title: "接口线路（不可用时切换）",
      type: "select",
      defaultValue: DEFAULT_API_BASE,
      options: API_DOMAIN_OPTIONS
    },
    {
      id: "originalImage",
      title: "阅读时加载原图",
      type: "boolean",
      defaultValue: "false"
    }
  ];

  // Settings are immutable during one isolate lifetime. The host rebuilds this isolate when the
  // user leaves the source settings screen, which makes a batch of edits take effect atomically.
  let cachedSettings = null;

  async function settings() {
    if (cachedSettings) return cachedSettings;
    const [domain, original] = await Promise.all([
      inkleaf.host.settings.get("apiDomain"),
      inkleaf.host.settings.get("originalImage")
    ]);
    const requestedDomain = text(domain);
    cachedSettings = {
      apiBase: API_DOMAIN_OPTIONS.some(function (option) {
        return option.id === requestedDomain;
      }) ? requestedDomain : DEFAULT_API_BASE,
      originalImage: text(original) === "true"
    };
    return cachedSettings;
  }

  function pluginError(code, message, retryable) {
    const error = new Error(message);
    error.code = code;
    error.retryable = Boolean(retryable);
    return error;
  }

  function asObject(value) {
    return value && typeof value === "object" && !Array.isArray(value) ? value : {};
  }

  function text(value) {
    return value === null || value === undefined ? "" : String(value).trim();
  }

  function finiteNumber(value, fallback) {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  }

  function queryString(values) {
    return Object.keys(values)
      .map(function (key) {
        return encodeURIComponent(key) + "=" + encodeURIComponent(String(values[key]));
      })
      .join("&");
  }

  function apiHost(apiBase) {
    return text(apiBase).replace(/^https?:\/\//i, "").split("/")[0].toLowerCase();
  }

  function isHotMangaApiBase(apiBase) {
    const host = apiHost(apiBase);
    return host.indexOf("hotmanga") >= 0 ||
      host === "api.manga2025.com" ||
      host.indexOf("fgjfghkk") >= 0;
  }

  function apiHeaders(active) {
    const headers = {
      Accept: "application/json",
      "Accept-Language": "en-US,en;q=0.9,zh-TW;q=0.8,zh;q=0.7",
      platform: PLATFORM,
      webp: "1"
    };
    if (isHotMangaApiBase(active.apiBase)) {
      headers.version = "2025.02.12";
      headers.Origin = "https://m.relamanhua.org";
      return headers;
    }
    // 拷贝系线路：模拟官方客户端，去掉 Origin，改用官方 UA/版本/region。
    headers["User-Agent"] = COPY_APP_USER_AGENT;
    headers.version = COPY_APP_VERSION;
    headers.region = "1";
    return headers;
  }

  // JavaScriptEngine isolates are not guaranteed to expose browser decoding globals.
  function decodeBase64(value) {
    const source = String(value || "").replace(/\s+/g, "");
    if (typeof atob === "function") {
      const binary = atob(source);
      const bytes = new Uint8Array(binary.length);
      for (let index = 0; index < binary.length; index += 1) {
        bytes[index] = binary.charCodeAt(index);
      }
      return bytes;
    }

    const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    const output = [];
    let buffer = 0;
    let bits = 0;
    for (let index = 0; index < source.length; index += 1) {
      const character = source.charAt(index);
      if (character === "=") break;
      const valueIndex = alphabet.indexOf(character);
      if (valueIndex < 0) continue;
      buffer = (buffer << 6) | valueIndex;
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        output.push((buffer >> bits) & 0xff);
      }
    }
    return new Uint8Array(output);
  }

  function decodeUtf8(bytes) {
    if (typeof TextDecoder === "function") {
      return new TextDecoder("utf-8").decode(bytes);
    }

    let output = "";
    for (let index = 0; index < bytes.length;) {
      const first = bytes[index++];
      let codePoint;
      if (first < 0x80) {
        codePoint = first;
      } else if ((first & 0xe0) === 0xc0 && index < bytes.length) {
        codePoint = ((first & 0x1f) << 6) | (bytes[index++] & 0x3f);
      } else if ((first & 0xf0) === 0xe0 && index + 1 < bytes.length) {
        codePoint = ((first & 0x0f) << 12) |
          ((bytes[index++] & 0x3f) << 6) |
          (bytes[index++] & 0x3f);
      } else if ((first & 0xf8) === 0xf0 && index + 2 < bytes.length) {
        codePoint = ((first & 0x07) << 18) |
          ((bytes[index++] & 0x3f) << 12) |
          ((bytes[index++] & 0x3f) << 6) |
          (bytes[index++] & 0x3f);
      } else {
        codePoint = 0xfffd;
      }
      if (codePoint <= 0xffff) {
        output += String.fromCharCode(codePoint);
      } else {
        codePoint -= 0x10000;
        output += String.fromCharCode(0xd800 + (codePoint >> 10), 0xdc00 + (codePoint & 0x3ff));
      }
    }
    return output;
  }

  function concatBytes(parts, totalSize) {
    const bytes = new Uint8Array(totalSize);
    let offset = 0;
    parts.forEach(function (part) {
      bytes.set(part, offset);
      offset += part.length;
    });
    return bytes;
  }

  async function responseText(response, signal) {
    if (response.bodyBase64 !== null && response.bodyBase64 !== undefined) {
      return decodeUtf8(decodeBase64(response.bodyBase64));
    }
    if (!response.bodyHandle) return "";

    const handle = response.bodyHandle;
    const parts = [];
    let totalSize = 0;
    let offset = 0;
    try {
      while (true) {
        const chunk = await inkleaf.host.http.read({
          handle: handle,
          offset: offset,
          maxBytes: 384 * 1024
        }, signal);
        const bytes = decodeBase64(chunk.bodyBase64);
        parts.push(bytes);
        totalSize += bytes.length;
        const nextOffset = Number(chunk.offset);
        if (chunk.eof) break;
        if (!Number.isFinite(nextOffset) || nextOffset <= offset) {
          throw pluginError("PLUGIN_PROTOCOL", "HTTP body reader did not advance", false);
        }
        offset = nextOffset;
      }
    } finally {
      // Close without an already-aborted invocation signal so the host can release the handle.
      await inkleaf.host.http.close({ handle: handle }).catch(function () {});
    }
    return decodeUtf8(concatBytes(parts, totalSize));
  }

  async function apiGet(path, parameters, signal) {
    const active = await settings();
    const query = parameters ? queryString(parameters) : "";
    const headers = apiHeaders(active);
    const response = await inkleaf.host.http.request({
      method: "GET",
      url: active.apiBase + path + (query ? "?" + query : ""),
      headers: headers
    }, signal);
    const body = await responseText(response, signal);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      const error = pluginError(
        "HTTP",
        "CopyComic HTTP " + response.statusCode + (body ? ": " + body.slice(0, 256) : ""),
        response.statusCode === 429 || response.statusCode >= 500
      );
      error.statusCode = response.statusCode;
      throw error;
    }

    let payload;
    try {
      payload = JSON.parse(body);
    } catch (_) {
      throw pluginError("PLUGIN_PROTOCOL", "CopyComic returned invalid JSON", false);
    }
    if (Number(payload && payload.code) !== 200) {
      throw pluginError("HTTP", text(payload && payload.message) || "CopyComic request failed", false);
    }
    return asObject(payload.results);
  }

  function relationNames(value) {
    return Array.isArray(value)
      ? value.map(function (item) { return text(item && item.name); }).filter(Boolean)
      : [];
  }

  function statusText(value) {
    const status = asObject(value);
    return text(status.display) || text(status.value);
  }

  function mapComicSummary(value) {
    const comic = asObject(value);
    const sourceId = text(comic.path_word);
    if (!sourceId) return null;
    const authors = relationNames(comic.author);
    const status = statusText(comic.status);
    const latest = text(comic.last_chapter_name);
    return {
      sourceId: sourceId,
      title: text(comic.name) || sourceId,
      subtitle: [authors.join(" / "), status, latest].filter(Boolean).join(" · ") || null,
      cover: text(comic.cover) ? { url: text(comic.cover) } : null,
      tags: relationNames(comic.theme),
      opaqueContext: { comicId: sourceId }
    };
  }

  function uniqueSummaries(rows, unwrap) {
    const sourceIds = new Set();
    return rows.map(function (row) {
      return mapComicSummary(unwrap(row));
    }).filter(function (item) {
      if (!item || sourceIds.has(item.sourceId)) return false;
      sourceIds.add(item.sourceId);
      return true;
    });
  }

  function browsePage(results, offset, unwrap) {
    const rows = Array.isArray(results.list) ? results.list : [];
    const items = uniqueSummaries(rows, unwrap);
    const total = Math.max(rows.length, finiteNumber(results.total, rows.length));
    const nextOffset = offset + rows.length;
    return {
      items: items,
      nextCursor: rows.length > 0 && nextOffset < total ? String(nextOffset) : null
    };
  }

  function browseOffset(value) {
    if (value === null || value === undefined || value === "") return 0;
    const offset = Number(value);
    if (!Number.isInteger(offset) || offset < 0) {
      throw pluginError("INVALID_ARGUMENT", "Browse cursor must be a non-negative integer", false);
    }
    return offset;
  }

  function feedDescriptors() {
    return [
      { id: "recommend", title: "推荐" },
      { id: "newest", title: "最新" },
      {
        id: "rank",
        title: "排行榜",
        filters: [
          {
            id: "audience",
            title: "受众",
            type: "select",
            options: [
              { id: "male", title: "男频" },
              { id: "female", title: "女频" }
            ]
          },
          {
            id: "period",
            title: "周期",
            type: "select",
            options: [
              { id: "day", title: "日榜" },
              { id: "week", title: "周榜" },
              { id: "month", title: "月榜" },
              { id: "total", title: "总榜" }
            ]
          },
          {
            id: "kind",
            title: "类型",
            type: "select",
            options: [
              { id: "1", title: "全部" },
              { id: "5", title: "轻小说" }
            ]
          }
        ]
      },
      {
        id: "discover",
        title: "分类",
        filters: [
          {
            id: "theme",
            title: "题材",
            type: "select",
            options: [
              { id: "all", title: "全部" },
              { id: "aiqing", title: "爱情" },
              { id: "huanlexiang", title: "欢乐向" },
              { id: "maoxian", title: "冒险" },
              { id: "qihuan", title: "奇幻" },
              { id: "baihe", title: "百合" },
              { id: "xiaoyuan", title: "校园" },
              { id: "kehuan", title: "科幻" },
              { id: "rexue", title: "热血" },
              { id: "yishijie", title: "异世界" }
            ]
          },
          {
            id: "top",
            title: "地区/状态",
            type: "select",
            options: [
              { id: "all", title: "全部" },
              { id: "japan", title: "日本" },
              { id: "korea", title: "韩漫" },
              { id: "west", title: "美漫" },
              { id: "finish", title: "完结" }
            ]
          },
          {
            id: "ordering",
            title: "排序",
            type: "select",
            options: [
              { id: "-datetime_updated", title: "最新" },
              { id: "datetime_updated", title: "最旧" },
              { id: "-popular", title: "热度最高" },
              { id: "popular", title: "热度最低" }
            ]
          }
        ]
      }
    ];
  }

  function normalizeGroups(value) {
    const rows = Array.isArray(value) ? value : Object.keys(asObject(value)).map(function (key) {
      return value[key];
    });
    return rows.map(function (item) {
      const group = asObject(item);
      return { id: text(group.path_word) || text(group.id), name: text(group.name) };
    }).filter(function (group) { return Boolean(group.id); });
  }

  function chapterImageUrls(chapter) {
    const contents = Array.isArray(chapter.contents) ? chapter.contents : [];
    const urls = contents.map(function (item) { return text(item && item.url); });
    const words = Array.isArray(chapter.words) ? chapter.words.map(Number) : [];
    if (words.length !== urls.length) return urls.filter(Boolean);

    const ordered = urls.map(function (url, index) {
      return { url: url, originalIndex: index, order: words[index] };
    }).filter(function (item) {
      return item.url && Number.isFinite(item.order);
    });
    if (!ordered.length) return urls.filter(Boolean);
    ordered.sort(function (left, right) {
      return left.order === right.order
        ? left.originalIndex - right.originalIndex
        : left.order - right.order;
    });
    return ordered.map(function (item) { return item.url; });
  }

  async function loadDetail(sourceId, signal) {
    const results = await apiGet(
      "/comic2/" + encodeURIComponent(sourceId),
      { platform: PLATFORM },
      signal
    );
    const resultGroups = normalizeGroups(results.groups);
    return {
      comic: asObject(results.comic),
      groups: resultGroups.length ? resultGroups : normalizeGroups(asObject(results.comic).groups)
    };
  }

  async function loadChapterPage(sourceId, groupId, offset, signal) {
    return apiGet(
      "/comic/" + encodeURIComponent(sourceId) +
        "/group/" + encodeURIComponent(groupId) + "/chapters",
      { limit: CHAPTER_PAGE_LIMIT, offset: offset },
      signal
    );
  }

  async function loadChapterContent(sourceId, chapterId, signal) {
    const basePath = "/comic/" + encodeURIComponent(sourceId) + "/";
    const active = await settings();
    const primaryPath = isHotMangaApiBase(active.apiBase) ? "chapter" : "chapter2";
    const secondaryPath = primaryPath === "chapter" ? "chapter2" : "chapter";
    try {
      return await apiGet(
        basePath + primaryPath + "/" + encodeURIComponent(chapterId),
        { platform: PLATFORM },
        signal
      );
    } catch (error) {
      if (!error || error.statusCode !== 404) throw error;
      return apiGet(
        basePath + secondaryPath + "/" + encodeURIComponent(chapterId),
        { platform: PLATFORM },
        signal
      );
    }
  }

  inkleaf.register({
    describe: async function () {
      return {
        schemaVersion: 1,
        feeds: feedDescriptors(),
        actions: [
          { id: "probeDomain", title: "检测当前线路是否可用", kind: "action" }
        ],
        filters: [],
        settings: SETTING_DESCRIPTORS
      };
    },

    invokeAction: async function (request, context) {
      const actionId = text(request && request.actionId);
      const signal = context && context.signal;
      if (actionId === "probeDomain") {
        const active = await settings();
        try {
          await apiGet("/comics", {
            limit: 1,
            offset: 0,
            ordering: "-datetime_updated",
            platform: BROWSE_PLATFORM
          }, signal);
          return { message: "线路可用：" + active.apiBase };
        } catch (error) {
          return { message: "线路不可用：" + (error && error.message ? error.message : "未知错误") };
        }
      }
      throw pluginError("NOT_FOUND", "Unknown action: " + actionId, false);
    },

    search: async function (request, context) {
      const query = text(request.query);
      if (!query) throw pluginError("INVALID_ARGUMENT", "Search query is required", false);
      const limit = Math.floor(Math.max(1, Math.min(MAX_SEARCH_LIMIT, finiteNumber(request.limit, 21))));
      const offset = Math.max(0, Math.floor(finiteNumber(request.cursor, 0)));
      const results = await apiGet("/search/comic", {
        limit: limit,
        offset: offset,
        q: query,
        q_type: "",
        platform: PLATFORM
      }, context && context.signal);
      const rows = Array.isArray(results.list) ? results.list : [];
      const items = uniqueSummaries(rows, function (item) { return item; });
      const total = Math.max(items.length, finiteNumber(results.total, items.length));
      const nextOffset = offset + rows.length;
      return {
        items: items,
        nextCursor: rows.length > 0 && nextOffset < total ? String(nextOffset) : null
      };
    },

    browse: async function (request, context) {
      const feedId = text(request.feedId);
      const limit = Math.floor(Math.max(1, Math.min(MAX_SEARCH_LIMIT, finiteNumber(request.limit, 21))));
      const offset = browseOffset(request.cursor);
      const filters = asObject(request.filters);
      const signal = context && context.signal;

      if (feedId === "recommend") {
        const results = await apiGet("/recs", {
          pos: "3200102",
          limit: limit,
          offset: offset,
          platform: BROWSE_PLATFORM
        }, signal);
        return browsePage(results, offset, function (row) { return asObject(row).comic; });
      }

      if (feedId === "newest") {
        const active = await settings();
        const primaryPath = isHotMangaApiBase(active.apiBase) ? "/comics" : "/update/newest";
        const secondaryPath = primaryPath === "/comics" ? "/update/newest" : "/comics";
        function newestParameters(path) {
          const parameters = {
            limit: limit,
            offset: offset,
            platform: BROWSE_PLATFORM
          };
          if (path === "/comics") parameters.ordering = "-datetime_updated";
          return parameters;
        }
        let results;
        try {
          results = await apiGet(primaryPath, newestParameters(primaryPath), signal);
          return browsePage(results, offset, function (row) {
            const record = asObject(row);
            return text(asObject(record.comic).path_word) ? record.comic : record;
          });
        } catch (error) {
          if (!error || error.statusCode !== 404) throw error;
          results = await apiGet(secondaryPath, newestParameters(secondaryPath), signal);
          return browsePage(results, offset, function (row) {
            const record = asObject(row);
            return text(asObject(record.comic).path_word) ? record.comic : record;
          });
        }
      }

      if (feedId === "rank") {
        const audience = filters.audience === "female" ? "female" : "male";
        const period = ["day", "week", "month", "total"].indexOf(filters.period) >= 0
          ? filters.period
          : "day";
        const kind = filters.kind === "5" ? "5" : "1";
        const results = await apiGet("/ranks", {
          type: kind,
          date_type: period,
          limit: limit,
          offset: offset,
          audience_type: audience,
          platform: BROWSE_PLATFORM
        }, signal);
        return browsePage(results, offset, function (row) {
          const record = asObject(row);
          return text(asObject(record.comic).path_word) ? record.comic : record.book;
        });
      }

      if (feedId === "discover") {
        const ordering = ["-datetime_updated", "datetime_updated", "-popular", "popular"]
          .indexOf(filters.ordering) >= 0 ? filters.ordering : "-datetime_updated";
        const results = await apiGet("/comics", {
          limit: limit,
          offset: offset,
          free_type: "1",
          ordering: ordering,
          theme: filters.theme === "all" ? "" : text(filters.theme),
          top: filters.top === "all" ? "" : text(filters.top),
          platform: BROWSE_PLATFORM
        }, signal);
        return browsePage(results, offset, function (row) { return row; });
      }

      throw pluginError("INVALID_ARGUMENT", "Unknown feed: " + feedId, false);
    },

    detail: async function (request, context) {
      const sourceId = text(request.sourceId);
      if (!sourceId) throw pluginError("INVALID_ARGUMENT", "sourceId is required", false);
      const loaded = await loadDetail(sourceId, context && context.signal);
      const comic = loaded.comic;
      const authors = relationNames(comic.author);
      const themes = relationNames(comic.theme);
      const status = statusText(comic.status);
      const region = text(asObject(comic.region).display);
      const kind = text(asObject(comic.reclass).display);
      return {
        sourceId: sourceId,
        title: text(comic.name) || sourceId,
        subtitle: [authors.join(" / "), status, kind, region].filter(Boolean).join(" · ") || null,
        description: text(comic.brief) || null,
        cover: text(comic.cover) ? { url: text(comic.cover) } : null,
        tags: themes,
        status: status || null,
        opaqueContext: { comicId: sourceId, groups: loaded.groups }
      };
    },

    chapters: async function (request, context) {
      const sourceId = text(request.sourceId);
      if (!sourceId) throw pluginError("INVALID_ARGUMENT", "sourceId is required", false);
      const requestContext = asObject(request.opaqueContext);
      let groups = normalizeGroups(requestContext.groups);
      if (!groups.length) {
        groups = (await loadDetail(sourceId, context && context.signal)).groups;
      }

      const chapters = [];
      const chapterIds = new Set();
      for (let groupIndex = 0; groupIndex < groups.length; groupIndex += 1) {
        const group = groups[groupIndex];
        let offset = 0;
        while (chapters.length < 5000) {
          const data = await loadChapterPage(sourceId, group.id, offset, context && context.signal);
          const rows = Array.isArray(data.list) ? data.list : [];
          for (let rowIndex = 0; rowIndex < rows.length && chapters.length < 5000; rowIndex += 1) {
            const row = asObject(rows[rowIndex]);
            const chapterId = text(row.uuid);
            if (!chapterId || chapterIds.has(chapterId)) continue;
            chapterIds.add(chapterId);
            const number = chapters.length + 1;
            const name = text(row.name) || "第" + number + "话";
            chapters.push({
              chapterId: chapterId,
              title: group.name ? group.name + " - " + name : name,
              number: number,
              publishedAt: text(row.datetime_created) || null,
              revision: text(row.datetime_updated) || text(row.datetime_created) || null,
              available: true,
              opaqueContext: { comicId: sourceId, groupId: group.id }
            });
          }
          const total = Math.max(rows.length, finiteNumber(data.total, rows.length));
          offset += rows.length;
          if (!rows.length || offset >= total) break;
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
      const results = await loadChapterContent(sourceId, chapterId, context && context.signal);
      const chapter = asObject(results.chapter);
      const urls = chapterImageUrls(chapter);
      if (!urls.length) throw pluginError("NOT_FOUND", "No readable images found for this chapter", false);
      const active = await settings();
      const imageHeaders = {
        Accept: "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
      };
      // 拷贝系图片 CDN 同样校验 UA，与接口请求保持一致。
      if (!isHotMangaApiBase(active.apiBase)) {
        imageHeaders["User-Agent"] = COPY_APP_USER_AGENT;
      }
      return {
        sourceId: sourceId,
        chapterId: chapterId,
        revision: request.revision || null,
        pages: urls.map(function (url, index) {
          return {
            pageId: chapterId + "-" + (index + 1),
            index: index,
            // CopyComic uses a .c<width>x suffix for resized images; removing it requests source.
            url: active.originalImage ? url.replace(/\.c[0-9]+x\./, ".") : url,
            headers: imageHeaders
          };
        })
      };
    }
  });
})();
