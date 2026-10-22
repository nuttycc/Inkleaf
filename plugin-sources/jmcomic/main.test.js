"use strict";

// Offline unit tests for the 禁漫天堂 source. No network.
//
//   node plugin-sources/jmcomic/main.test.js
//
// Every API reply travels in the site's real envelope: the model is AES-256-ECB encrypted under
// md5(ts + secret) with the ts taken from the request's own tokenparam header, so signing and
// decryption are both exercised on every call. Node's crypto produces the ciphertext, which keeps
// this file's AES implementation from being checked against itself.
//
// Models are hand-written samples of the documented field names, not recorded traffic.

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const runtimeSource = fs.readFileSync(path.join(__dirname, "..", "shared", "runtime.js"), "utf8");
const pluginSource = fs.readFileSync(path.join(__dirname, "src", "main.js"), "utf8");

const TOKEN_SECRET = "185Hcomic3PAPP7R";
const HOST_CONFIG_SECRET = "diosfjckwpqpdfjkvnqQjsik";
const CONFIG_URL = "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt";

const md5Hex = (value) => crypto.createHash("md5").update(value, "utf8").digest("hex");

/**
 * The host hands handlers a `{ signal }` context, and its own bridge refuses anything else, so the
 * mocks below enforce the same shape: passing the context straight through as a signal fails here.
 */
function guardSignal(signal) {
  if (signal === undefined || signal === null) return;
  if (typeof signal.addEventListener !== "function" || typeof signal.removeEventListener !== "function") {
    throw new TypeError("signal.addEventListener is not a function");
  }
}

function ctx() {
  return {
    signal: {
      aborted: false,
      addEventListener: function () {},
      removeEventListener: function () {},
    },
  };
}

function aesEcbEncrypt(plain, keyString) {
  const cipher = crypto.createCipheriv("aes-256-ecb", Buffer.from(keyString, "utf8"), null);
  return Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]).toString("base64");
}

/** The host config file is prefixed with non-ASCII decoy bytes before the base64 payload. */
function configFile(servers) {
  const payload = aesEcbEncrypt(JSON.stringify({ Server: servers }), md5Hex(HOST_CONFIG_SECRET));
  return "£∯" + payload;
}

function createPlugin(options) {
  const settings = Object.assign(
    { account: "", password: "", apiHost: "", imageHost: "" },
    (options && options.settings) || {}
  );
  const respond = (options && options.respond) || defaultRespond;
  const requests = [];
  const store = new Map();

  const sandbox = {
    console: console,
    Date: Date,
    Promise: Promise,
    JSON: JSON,
    Math: Math,
    Number: Number,
    String: String,
    Array: Array,
    Object: Object,
    Boolean: Boolean,
    Uint8Array: Uint8Array,
    Uint32Array: Uint32Array,
    Int32Array: Int32Array,
    DataView: DataView,
    ArrayBuffer: ArrayBuffer,
    Set: Set,
    Map: Map,
    TypeError: TypeError,
    Error: Error,
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
            if (reply.statusCode && reply.statusCode >= 400) {
              return { statusCode: reply.statusCode, headers: {}, bodyBase64: "" };
            }
            let body;
            if (typeof reply.raw === "string") {
              body = reply.raw;
            } else {
              const ts = String(request.headers.tokenparam || "").split(",")[0] || "0";
              body = JSON.stringify({
                code: 200,
                data: aesEcbEncrypt(JSON.stringify(reply.model), md5Hex(ts + TOKEN_SECRET)),
              });
            }
            const bytes = Buffer.from(body, "utf8");
            return {
              statusCode: 200,
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
          get: async function (id, signal) {
            guardSignal(signal);
            return settings[id] === "" ? null : settings[id] ?? null;
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
        log: async function () {
          return true;
        },
      },
    },
  };

  // Internals are reachable only through this probe: the bundle is wrapped in an IIFE, exactly as
  // the packaging tool does, so nothing is exported to the host.
  const probe =
    "globalThis.__probe = { md5Hex: md5Hex, aesEcbDecrypt: aesEcbDecrypt, tables: AES, " +
    "decodeModel: decodeModel, stripTags: stripTags };";
  vm.runInNewContext(
    '(function () { "use strict";\n' +
      runtimeSource +
      "\n" +
      pluginSource +
      "\n" +
      probe +
      "\n})();",
    sandbox,
    { filename: "jmcomic-bundle.js" }
  );

  return {
    plugin: sandbox.registration,
    internals: sandbox.__probe,
    requests: requests,
    store: store,
  };
}

function albumRow(id, name) {
  return {
    id: id,
    name: name,
    author: "作者A",
    tags: ["全彩", "汉化"],
    image: "media/albums/" + id + "_3x4.jpg",
    total_views: 1200,
    likes: 34,
  };
}

function rows(count, offset) {
  const list = [];
  for (let index = 0; index < count; index += 1) {
    list.push(albumRow(500000 + index + (offset || 0), "本 " + (index + 1)));
  }
  return list;
}

const SETTING = { img_host: "https://cdn-msp.setting.example", jm3_version: "2.1.7" };
const ALBUM = {
  id: 501234,
  name: "測試本",
  description: "敘述：<br>第一段<br><br>第二段",
  author: ["作者A", "作者B"],
  tags: ["短篇", "漢化"],
  image: "/media/albums/501234_3x4.jpg",
  addtime: 1600000000,
  update_at: 1700000000,
  total_views: 999,
  likes: 8,
  series: [
    { id: 501235, name: "第1话", sort: 1, order: 1 },
    { id: 501236, name: "第2话", sort: 2, order: 2 },
    { id: 501237, name: "无关小说", sort: 0 },
  ],
};
const CHAPTER = {
  id: 501235,
  name: "第1话",
  series_id: "501234",
  scramble_id: 220980,
  data_original_domain: "cdn-msp.own.example",
  page_arr: ["00001.webp", "00002.webp", "00003.gif"],
};

function defaultRespond(request) {
  const url = request.url;
  if (url.indexOf("newsvr-2025.txt") >= 0) {
    return { raw: configFile(["www.configured.net", "www.second.net"]) };
  }
  if (url.indexOf("/setting") >= 0) return { model: SETTING };
  if (url.indexOf("/search") >= 0) return { model: { content: rows(3), total: 240 } };
  if (url.indexOf("/album") >= 0) return { model: ALBUM };
  if (url.indexOf("/chapter") >= 0) return { model: CHAPTER };
  if (url.indexOf("/latest") >= 0) return { model: rows(80) };
  if (url.indexOf("/categories/filter") >= 0) return { model: { content: rows(80, 100), total: 300 } };
  if (url.indexOf("/promote") >= 0) return { model: [{ id: "s1", title: "推荐", content: rows(5) }] };
  if (url.indexOf("/login") >= 0) {
    return { model: { jwttoken: "jwt-abc", uid: "777", user_name: "tester", message: "登录成功" } };
  }
  throw new Error("unrouted request: " + url);
}

// Values built inside the vm come from that realm's prototypes, so compare through JSON.
const plain = (value) => JSON.parse(JSON.stringify(value === undefined ? null : value));

function pathOf(request) {
  return new URL(request.url).pathname + (new URL(request.url).search || "");
}

async function run() {
  // 1. MD5 agrees with Node's implementation, including multi-byte input.
  {
    const { internals } = createPlugin();
    ["", "abc", "1700566805185Hcomic3PAPP7R", "禁漫天堂", "a".repeat(55), "b".repeat(64)].forEach(
      function (value) {
        assert.equal(internals.md5Hex(value), md5Hex(value), "md5 of " + JSON.stringify(value));
      }
    );
  }

  // 2. AES tables and the ECB decrypt match Node, with PKCS7 padding removed.
  {
    const { internals } = createPlugin();
    assert.equal(internals.tables.sbox[0x00], 0x63);
    assert.equal(internals.tables.sbox[0x01], 0x7c);
    assert.equal(internals.tables.sbox[0x53], 0xed);
    assert.equal(internals.tables.inverseSbox[internals.tables.sbox[200]], 200);

    const key = md5Hex("1700566805" + TOKEN_SECRET);
    const message = JSON.stringify({ ok: true, 名称: "测试" });
    const bytes = Buffer.from(aesEcbEncrypt(message, key), "base64");
    const decrypted = internals.aesEcbDecrypt(new Uint8Array(bytes), key);
    assert.equal(Buffer.from(decrypted).toString("utf8"), message);
    assert.throws(function () {
      internals.aesEcbDecrypt(new Uint8Array(17), key);
    }, /长度/);
  }

  // 3. search maps rows, honours the signing headers and derives the cursor from the page size.
  {
    const { plugin, requests } = createPlugin();
    const page = await plugin.search({ query: "火影", limit: 40 }, ctx());
    assert.equal(page.items.length, 3);
    assert.equal(page.items[0].sourceId, "500000");
    assert.ok(page.items[0].cover.url.startsWith("https://cdn-msp.setting.example/media/albums/"));
    assert.equal(page.items[0].subtitle, "作者A");
    assert.deepEqual(plain(page.items[0].tags), ["全彩", "汉化"]);
    // 3 of a 80-item page means the site is done, even though total says 240.
    assert.equal(page.nextCursor, null);

    const search = requests.find(function (request) {
      return pathOf(request).indexOf("/search") === 0;
    });
    const tokenparam = search.headers.tokenparam;
    assert.match(tokenparam, /^\d+,2\.1\.7$/);
    assert.equal(search.headers.token, md5Hex(tokenparam.split(",")[0] + TOKEN_SECRET));
    assert.match(search.headers["user-agent"], /Android/);
    assert.equal(search.headers.referer, "https://www.configured.net/");
    assert.equal(new URL(search.url).searchParams.get("search_query"), "火影");
    assert.equal(new URL(search.url).searchParams.get("o"), "mr");
    assert.equal(search.headers.authorization, undefined, "anonymous requests carry no bearer token");
  }

  // 4. A full page yields the next cursor; the search order filter reaches the wire.
  {
    const { plugin } = createPlugin({
      respond: function (request) {
        if (request.url.indexOf("/search") >= 0) {
          return { model: { content: rows(80), total: 240 } };
        }
        return defaultRespond(request);
      },
    });
    const page = await plugin.search(
      { query: "火影", limit: 40, filters: { o: "mv_w" } },
      ctx()
    );
    assert.equal(page.nextCursor, "2");
    assert.equal(page.items.length, 80);
  }

  // 5. A plate number resolves as an album instead of a keyword search.
  {
    const { plugin, requests } = createPlugin();
    const page = await plugin.search({ query: "JM501234" }, ctx());
    assert.equal(page.items.length, 1);
    assert.equal(page.items[0].sourceId, "501234");
    assert.ok(requests.some(function (request) {
      return pathOf(request).indexOf("/album") === 0 && request.url.indexOf("id=501234") >= 0;
    }));
  }

  // 6. browse /latest counts pages from zero, matching the site's own offset.
  {
    const { plugin, requests } = createPlugin();
    const first = await plugin.browse({ feedId: "latest", limit: 40 }, ctx());
    assert.equal(first.items.length, 80);
    assert.equal(first.nextCursor, "2");
    assert.equal(pathOf(requests[requests.length - 1]), "/latest?page=0");

    await plugin.browse({ feedId: "latest", cursor: "3" }, ctx());
    assert.equal(pathOf(requests[requests.length - 1]), "/latest?page=2");
  }

  // 7. browse /ranking defaults then overrides its select filters.
  {
    const { plugin, requests } = createPlugin();
    await plugin.browse({ feedId: "ranking", cursor: "2" }, ctx());
    assert.equal(pathOf(requests[requests.length - 1]), "/categories/filter?page=2&c=0&o=mv_m");

    await plugin.browse({ feedId: "ranking", filters: { c: "hanman", o: "mv_t" } }, ctx());
    assert.equal(pathOf(requests[requests.length - 1]), "/categories/filter?page=1&c=hanman&o=mv_t");
  }

  // 8. /recommend flattens the section list without a cursor.
  {
    const { plugin } = createPlugin();
    const page = await plugin.browse({ feedId: "recommend" }, ctx());
    assert.equal(page.items.length, 5);
    assert.equal(page.nextCursor, null);
  }

  // 9. detail cleans the description and echoes the requested sourceId.
  {
    const { plugin, internals } = createPlugin();
    assert.equal(internals.stripTags(ALBUM.description), "敘述：\n第一段\n\n第二段");
    const detail = await plugin.detail({ sourceId: "501234" }, ctx());
    assert.equal(detail.sourceId, "501234");
    assert.equal(detail.title, "測試本");
    assert.equal(detail.description, "敘述：\n第一段\n\n第二段");
    assert.deepEqual(plain(detail.tags), ["短篇", "漢化"]);
    assert.equal(detail.cover.url, "https://cdn-msp.setting.example/media/albums/501234_3x4.jpg");
  }

  // 10. chapters drops the sort=0 cross-links; a lone album becomes one chapter.
  {
    const { plugin } = createPlugin();
    const listing = await plugin.chapters({ sourceId: "501234" }, ctx());
    assert.deepEqual(
      plain(listing.chapters.map(function (chapter) {
        return [chapter.chapterId, chapter.title, chapter.number];
      })),
      [
        ["501235", "第1话", 1],
        ["501236", "第2话", 2],
      ]
    );
    assert.equal(listing.sourceId, "501234");

    const lone = createPlugin({
      respond: function (request) {
        if (request.url.indexOf("/album") >= 0) {
          return { model: Object.assign({}, ALBUM, { series: [] }) };
        }
        return defaultRespond(request);
      },
    });
    const fallback = await lone.plugin.chapters({ sourceId: "501234" }, ctx());
    assert.deepEqual(
      plain(fallback.chapters.map(function (chapter) {
        return chapter.chapterId;
      })),
      ["501234"]
    );
  }

  // 11. pages build host-qualified urls, carry the site's scramble id and index contiguously.
  {
    const { plugin, requests } = createPlugin();
    const result = await plugin.pages({ sourceId: "501234", chapterId: "501235" }, ctx());
    assert.equal(result.sourceId, "501234");
    assert.equal(result.chapterId, "501235");
    assert.deepEqual(
      plain(result.pages.map(function (page) {
        return page.index;
      })),
      [0, 1, 2]
    );
    assert.equal(result.pages[0].url, "https://cdn-msp.own.example/media/photos/501235/00001.webp");
    assert.deepEqual(
      plain(result.pages.map(function (page) {
        return page.imageTransform;
      })),
      [
        { type: "jmSegmentation", scrambleId: 220980 },
        { type: "jmSegmentation", scrambleId: 220980 },
        { type: "jmSegmentation", scrambleId: 220980 },
      ]
    );
    assert.ok(result.pages[0].referer.startsWith("https://www.configured.net/"));
    assert.equal(result.pages[0].headers["x-requested-with"], "com.JMComic3.app");
    assert.equal(new Set(result.pages.map((p) => p.pageId)).size, 3);
    // /chapter names its own image host, so the pool only backs it up.
    assert.ok(!requests.some((request) => request.url.indexOf("cdn-msp.setting.example") >= 0));
  }

  // 12. A user-pinned image host wins over the site's suggestion.
  {
    const { plugin } = createPlugin({ settings: { imageHost: "cdn-msp.pinned.example" } });
    const result = await plugin.pages({ sourceId: "501234", chapterId: "501235" }, ctx());
    assert.ok(result.pages[0].url.startsWith("https://cdn-msp.pinned.example/"));
  }

  // 13. A chapter response without a scramble id falls back to the published threshold.
  {
    const { plugin } = createPlugin({
      respond: function (request) {
        if (request.url.indexOf("/chapter") >= 0) {
          const copy = Object.assign({}, CHAPTER);
          delete copy.scramble_id;
          return { model: copy };
        }
        return defaultRespond(request);
      },
    });
    const result = await plugin.pages({ sourceId: "501234", chapterId: "501235" }, ctx());
    assert.equal(result.pages[0].imageTransform.scrambleId, 220980);
  }

  // 14. The bootstrap config file replaces the built-in hosts.
  {
    const { plugin, requests } = createPlugin();
    await plugin.search({ query: "x" }, ctx());
    assert.ok(requests[0].url.indexOf("bytepluses") >= 0, "line config is fetched first");
    assert.ok(
      requests.some(function (request) {
        return request.url.indexOf("https://www.configured.net/setting") === 0;
      })
    );
  }

  // 15. A newer jm3_version from /setting is adopted for later signatures.
  {
    const { plugin, requests } = createPlugin({
      respond: function (request) {
        if (request.url.indexOf("/setting") >= 0) {
          return { model: { img_host: SETTING.img_host, jm3_version: "9.9.9" } };
        }
        return defaultRespond(request);
      },
    });
    await plugin.search({ query: "x" }, ctx());
    const last = requests[requests.length - 1];
    assert.equal(last.headers.tokenparam.split(",")[1], "9.9.9");
  }

  // 16. login stores the bearer token and later requests use it; logout clears it.
  {
    const { plugin, requests, store } = createPlugin({
      settings: { account: "tester", password: "pw" },
    });
    const result = await plugin.invokeAction({ actionId: "login" }, ctx());
    assert.equal(result.ok, true);
    assert.equal(store.get("auth.jwt"), "jwt-abc");
    const login = requests.find(function (request) {
      return pathOf(request).indexOf("/login") === 0;
    });
    assert.equal(login.method, "POST");
    assert.equal(login.headers["content-type"], "application/x-www-form-urlencoded");
    assert.equal(
      Buffer.from(login.bodyBase64, "base64").toString("utf8"),
      "username=tester&password=pw"
    );

    await plugin.search({ query: "x" }, ctx());
    const search = requests[requests.length - 1];
    assert.equal(search.headers.authorization, "Bearer jwt-abc");

    await plugin.invokeAction({ actionId: "logout" }, ctx());
    assert.equal(store.get("auth.jwt"), "");
    await plugin.search({ query: "x" }, ctx());
    assert.equal(requests[requests.length - 1].headers.authorization, undefined);
  }

  // 17. Credentials come from the action input when the user typed them there.
  {
    const { plugin, requests, store } = createPlugin();
    await plugin.invokeAction({ actionId: "login", input: { account: "a", password: "b" } }, ctx());
    assert.equal(store.get("auth.jwt"), "jwt-abc");
    const login = requests.find(function (request) {
      return pathOf(request).indexOf("/login") === 0;
    });
    assert.equal(Buffer.from(login.bodyBase64, "base64").toString("utf8"), "username=a&password=b");
  }

  // 18. Login without any credentials is an argument error, not a network failure.
  {
    const { plugin } = createPlugin();
    await assert.rejects(
      function () {
        return plugin.invokeAction({ actionId: "login" }, ctx());
      },
      function (error) {
        return error.code === "INVALID_ARGUMENT";
      }
    );
  }

  // 19. refreshEndpoints forgets the cached line state and re-reads the config.
  {
    const { plugin, requests } = createPlugin();
    await plugin.search({ query: "x" }, ctx());
    const before = requests.length;
    const result = await plugin.invokeAction({ actionId: "refreshEndpoints" }, ctx());
    assert.equal(result.appVersion, "2.1.7");
    assert.ok(result.apiHosts[0] === "www.configured.net");
    assert.ok(requests.length > before);
  }

  // 20. An error-shaped payload surfaces the site's own message.
  {
    const { plugin } = createPlugin({
      respond: function (request) {
        if (request.url.indexOf("/chapter") >= 0) {
          return { model: { error: "Restricted Access!" } };
        }
        return defaultRespond(request);
      },
    });
    await assert.rejects(
      function () {
        return plugin.pages({ sourceId: "501234", chapterId: "501235" }, ctx());
      },
      function (error) {
        return error.code === "HTTP" && /Restricted/.test(error.message);
      }
    );
  }

  // 21. A paid or removed chapter with no images is NOT_FOUND, not a crash.
  {
    const { plugin } = createPlugin({
      respond: function (request) {
        if (request.url.indexOf("/chapter") >= 0) {
          return { model: { id: 501235, name: "第1话", page_arr: [] } };
        }
        return defaultRespond(request);
      },
    });
    await assert.rejects(
      function () {
        return plugin.pages({ sourceId: "501234", chapterId: "501235" }, ctx());
      },
      function (error) {
        return error.code === "NOT_FOUND";
      }
    );
  }

  // 22. A dead first host is skipped and the survivor is promoted in storage.
  {
    const { plugin, requests, store } = createPlugin({
      respond: function (request) {
        if (request.url.indexOf("www.configured.net") >= 0) {
          throw Object.assign(new Error("connect ECONNREFUSED"), { code: "NETWORK" });
        }
        return defaultRespond(request);
      },
    });
    const page = await plugin.search({ query: "x" }, ctx());
    assert.equal(page.items.length, 3);
    assert.ok(
      requests.some(function (request) {
        return request.url.indexOf("https://www.second.net/search") === 0;
      })
    );
    assert.equal(store.get("endpoints").apiHosts[0], "www.second.net");
  }

  console.log("jmcomic plugin tests passed");
}

run().catch(function (error) {
  console.error(error);
  process.exitCode = 1;
});
