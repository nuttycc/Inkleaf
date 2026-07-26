(function () {
  "use strict";

  const API_BASE = "https://api.manga2025.com/api/v3";
  const PLATFORM = "1";
  const MAX_SEARCH_LIMIT = 21;
  const CHAPTER_PAGE_LIMIT = 500;

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

  function apiHeaders() {
    return {
      Accept: "application/json",
      "Accept-Language": "en-US,en;q=0.9,zh-TW;q=0.8,zh;q=0.7",
      version: "2025.02.12",
      Origin: "https://m.relamanhua.org",
      platform: PLATFORM,
      webp: "1"
    };
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
    const query = parameters ? queryString(parameters) : "";
    const response = await inkleaf.host.http.request({
      method: "GET",
      url: API_BASE + path + (query ? "?" + query : ""),
      headers: apiHeaders()
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
    try {
      return await apiGet(
        basePath + "chapter/" + encodeURIComponent(chapterId),
        { platform: PLATFORM },
        signal
      );
    } catch (error) {
      if (!error || error.statusCode !== 404) throw error;
      return apiGet(
        basePath + "chapter2/" + encodeURIComponent(chapterId),
        { platform: PLATFORM },
        signal
      );
    }
  }

  inkleaf.register({
    describe: async function () {
      return { schemaVersion: 1, actions: [], filters: [], settings: [] };
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
      const items = rows.map(function (item) {
        const comic = asObject(item);
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
      }).filter(Boolean);
      const total = Math.max(items.length, finiteNumber(results.total, items.length));
      const nextOffset = offset + rows.length;
      return {
        items: items,
        nextCursor: rows.length > 0 && nextOffset < total ? String(nextOffset) : null
      };
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
      return {
        sourceId: sourceId,
        chapterId: chapterId,
        revision: request.revision || null,
        pages: urls.map(function (url, index) {
          return {
            pageId: chapterId + "-" + (index + 1),
            index: index,
            url: url,
            headers: { Accept: "image/avif,image/webp,image/apng,image/*,*/*;q=0.8" }
          };
        })
      };
    }
  });
})();
