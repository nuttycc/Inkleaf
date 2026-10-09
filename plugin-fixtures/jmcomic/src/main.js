// 禁漫天堂 (18comic / JMComic) online source, driven by the mobile App API.
//
// Scope: request signing, the AES-256-ECB response envelope, domain bootstrap and page descriptors.
// The strip-split descrambling itself is the host's job (JmSegmentation.kt); this file only declares
// where it applies.
//
// plugin.build.json asks .scripts/plugin.py to prepend the shared runtime while packaging, so
// text(), asObject(), asArray(), finiteNumber(), queryString(), dedupeBy(), responseText(),
// decodeBase64(), decodeUtf8() and pluginError() are already in scope.

const TOKEN_SECRET = "185Hcomic3PAPP7R";
const HOST_CONFIG_SECRET = "diosfjckwpqpdfjkvnqQjsik";
const DEFAULT_APP_VERSION = "2.1.7";

// Every client that knows the site hard-codes this: the first chapter id that got scrambled. The
// /chapter response carries its own scramble_id, so this is only the fallback when it does not.
const DEFAULT_SCRAMBLE_ID = 220980;

const HOST_CONFIG_URLS = [
  "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
  "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
  "https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt",
];

const FALLBACK_API_HOSTS = ["www.cdnhjk.net", "www.cdngwc.cc", "www.cdngwc.net", "www.cdngwc.club"];
const FALLBACK_IMAGE_HOSTS = [
  "cdn-msp.jmapiproxy1.cc",
  "cdn-msp.jmapiproxy2.cc",
  "cdn-msp3.jmapiproxy2.cc",
];

// The site answers one fixed page per request, so the page is the pagination unit: a page is
// returned whole rather than trimmed to the host's requested limit.
const PAGE_SIZE = 80;
const ENDPOINT_TTL_MS = 30 * 60 * 1000;
const HOST_TRY_LIMIT = 4;

const USER_AGENT =
  "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) " +
  "Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36";

const ORDER_OPTIONS = [
  ["mr", "最新发布"],
  ["mv", "最多浏览"],
  ["mp", "最多图片"],
  ["tf", "最多喜欢"],
  ["mv_m", "月排行"],
  ["mv_w", "周排行"],
  ["mv_t", "日排行"],
];

const CATEGORY_OPTIONS = [
  ["0", "全部"],
  ["doujin", "同人"],
  ["single", "单本"],
  ["short", "短篇"],
  ["another", "其他"],
  ["hanman", "韩漫"],
  ["meiman", "美漫"],
  ["3D", "3D"],
  ["doujin_cosplay", "Cosplay"],
  ["english_site", "英文站"],
];

const ENDPOINT_STATE_KEY = "endpoints";
const JWT_KEY = "auth.jwt";
const USER_KEY = "auth.user";
const SETTING_IDS = ["account", "password", "apiHost", "imageHost"];

// --------------------------------------------------------------------------- md5

const MD5_K = [
  0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
  0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
  0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
  0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
  0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
  0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
  0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
  0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
];

const MD5_SHIFTS = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
];

function utf8Bytes(value) {
  const bytes = [];
  const source = String(value);
  for (let index = 0; index < source.length; index += 1) {
    const code = source.charCodeAt(index);
    const following = index + 1 < source.length ? source.charCodeAt(index + 1) : -1;
    if (code < 0x80) {
      bytes.push(code);
    } else if (code < 0x800) {
      bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
    } else if (code >= 0xd800 && code < 0xdc00 && following >= 0xdc00 && following < 0xe000) {
      index += 1;
      const point = 0x10000 + ((code - 0xd800) << 10) + (following - 0xdc00);
      bytes.push(
        0xf0 | (point >> 18),
        0x80 | ((point >> 12) & 0x3f),
        0x80 | ((point >> 6) & 0x3f),
        0x80 | (point & 0x3f)
      );
    } else {
      bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    }
  }
  return new Uint8Array(bytes);
}

function rotateLeft(value, shift) {
  return (value << shift) | (value >>> (32 - shift));
}

function md5Hex(value) {
  const message = utf8Bytes(value);
  const bitLength = message.length * 8;
  const padded = new Uint8Array(((((message.length + 8) / 64) | 0) + 1) * 64);
  padded.set(message);
  padded[message.length] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, bitLength >>> 0, true);
  view.setUint32(padded.length - 4, Math.floor(bitLength / 4294967296), true);

  let a0 = 0x67452301;
  let b0 = 0xefcdab89;
  let c0 = 0x98badcfe;
  let d0 = 0x10325476;
  const words = new Array(16);

  for (let block = 0; block < padded.length; block += 64) {
    for (let index = 0; index < 16; index += 1) {
      words[index] = view.getUint32(block + index * 4, true);
    }
    let a = a0;
    let b = b0;
    let c = c0;
    let d = d0;
    for (let round = 0; round < 64; round += 1) {
      let f;
      let g;
      if (round < 16) {
        f = (b & c) | (~b & d);
        g = round;
      } else if (round < 32) {
        f = (d & b) | (~d & c);
        g = (5 * round + 1) & 15;
      } else if (round < 48) {
        f = b ^ c ^ d;
        g = (3 * round + 5) & 15;
      } else {
        f = c ^ (b | ~d);
        g = (7 * round) & 15;
      }
      const previous = d;
      d = c;
      c = b;
      const sum = (a + (f >>> 0) + MD5_K[round] + words[g]) >>> 0;
      b = (b + rotateLeft(sum, MD5_SHIFTS[round])) >>> 0;
      a = previous;
    }
    a0 = (a0 + a) >>> 0;
    b0 = (b0 + b) >>> 0;
    c0 = (c0 + c) >>> 0;
    d0 = (d0 + d) >>> 0;
  }

  let output = "";
  [a0, b0, c0, d0].forEach(function (word) {
    for (let index = 0; index < 4; index += 1) {
      const byte = (word >>> (index * 8)) & 0xff;
      output += (byte < 16 ? "0" : "") + byte.toString(16);
    }
  });
  return output;
}

function encodeBase64(bytes) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  let output = "";
  for (let index = 0; index < bytes.length; index += 3) {
    const remaining = bytes.length - index;
    const first = bytes[index];
    const second = remaining > 1 ? bytes[index + 1] : 0;
    const third = remaining > 2 ? bytes[index + 2] : 0;
    output += alphabet.charAt(first >> 2);
    output += alphabet.charAt(((first & 0x03) << 4) | (second >> 4));
    output += remaining > 1 ? alphabet.charAt(((second & 0x0f) << 2) | (third >> 6)) : "=";
    output += remaining > 2 ? alphabet.charAt(third & 0x3f) : "=";
  }
  return output;
}

// --------------------------------------------------------------------------- aes-256-ecb

// Tables are derived, not pasted: GF(2^8) inverses plus the AES affine transform produce the S-box
// exactly, which keeps a transcription typo out of the failure modes.
function buildAesTables() {
  const exp = new Uint8Array(256);
  const log = new Uint8Array(256);
  let cursor = 1;
  for (let index = 0; index < 255; index += 1) {
    exp[index] = cursor;
    log[cursor] = index;
    cursor = (cursor ^ ((cursor << 1) & 0xff) ^ ((cursor & 0x80) ? 0x1b : 0)) & 0xff;
  }
  exp[255] = exp[0];

  function multiply(left, right) {
    if (left === 0 || right === 0) return 0;
    return exp[(log[left] + log[right]) % 255];
  }

  function rotateByte(value, shift) {
    return ((value << shift) | (value >> (8 - shift))) & 0xff;
  }

  const sbox = new Uint8Array(256);
  for (let index = 0; index < 256; index += 1) {
    const inverse = index === 0 ? 0 : exp[255 - log[index]];
    sbox[index] =
      (inverse ^
        rotateByte(inverse, 1) ^
        rotateByte(inverse, 2) ^
        rotateByte(inverse, 3) ^
        rotateByte(inverse, 4) ^
        0x63) &
      0xff;
  }
  const inverseSbox = new Uint8Array(256);
  for (let index = 0; index < 256; index += 1) {
    inverseSbox[sbox[index]] = index;
  }
  return { exp: exp, log: log, sbox: sbox, inverseSbox: inverseSbox, multiply: multiply };
}

const AES = buildAesTables();

// The site keys AES with the *hex string*, so a 32-character MD5 becomes a 32-byte AES-256 key.
function expandKey(keyBytes) {
  const words = new Uint32Array(60);
  for (let index = 0; index < 8; index += 1) {
    words[index] =
      ((keyBytes[index * 4] << 24) |
        (keyBytes[index * 4 + 1] << 16) |
        (keyBytes[index * 4 + 2] << 8) |
        keyBytes[index * 4 + 3]) >>>
      0;
  }

  function subWord(word) {
    return (
      (AES.sbox[(word >>> 24) & 0xff] << 24) |
      (AES.sbox[(word >>> 16) & 0xff] << 16) |
      (AES.sbox[(word >>> 8) & 0xff] << 8) |
      AES.sbox[word & 0xff]
    ) >>> 0;
  }

  let rcon = 1;
  for (let index = 8; index < 60; index += 1) {
    let temp = words[index - 1];
    if (index % 8 === 0) {
      // FIPS-197 order: RotWord, then SubWord, then the round constant.
      const rotated = ((temp << 8) | (temp >>> 24)) >>> 0;
      temp = (subWord(rotated) ^ ((rcon << 24) >>> 0)) >>> 0;
      rcon = AES.multiply(rcon, 2);
    } else if (index % 8 === 4) {
      temp = subWord(temp);
    }
    words[index] = (words[index - 8] ^ temp) >>> 0;
  }
  return words;
}

function decryptBlock(source, words, state) {
  const inverseSbox = AES.inverseSbox;
  const multiply = AES.multiply;

  function addRoundKey(round) {
    for (let column = 0; column < 4; column += 1) {
      const word = words[round * 4 + column];
      state[column * 4] ^= (word >>> 24) & 0xff;
      state[column * 4 + 1] ^= (word >>> 16) & 0xff;
      state[column * 4 + 2] ^= (word >>> 8) & 0xff;
      state[column * 4 + 3] ^= word & 0xff;
    }
  }

  function inverseShiftRows() {
    const copy = state.slice();
    for (let row = 1; row < 4; row += 1) {
      for (let column = 0; column < 4; column += 1) {
        state[column * 4 + row] = copy[((column - row + 4) & 3) * 4 + row];
      }
    }
  }

  function inverseSubBytes() {
    for (let index = 0; index < 16; index += 1) {
      state[index] = inverseSbox[state[index]];
    }
  }

  function inverseMixColumns() {
    for (let column = 0; column < 4; column += 1) {
      const base = column * 4;
      const a0 = state[base];
      const a1 = state[base + 1];
      const a2 = state[base + 2];
      const a3 = state[base + 3];
      state[base] = multiply(a0, 14) ^ multiply(a1, 11) ^ multiply(a2, 13) ^ multiply(a3, 9);
      state[base + 1] = multiply(a0, 9) ^ multiply(a1, 14) ^ multiply(a2, 11) ^ multiply(a3, 13);
      state[base + 2] = multiply(a0, 13) ^ multiply(a1, 9) ^ multiply(a2, 14) ^ multiply(a3, 11);
      state[base + 3] = multiply(a0, 11) ^ multiply(a1, 13) ^ multiply(a2, 9) ^ multiply(a3, 14);
    }
  }

  for (let index = 0; index < 16; index += 1) {
    state[index] = source[index];
  }
  addRoundKey(14);
  for (let round = 13; round >= 1; round -= 1) {
    inverseShiftRows();
    inverseSubBytes();
    addRoundKey(round);
    inverseMixColumns();
  }
  inverseShiftRows();
  inverseSubBytes();
  addRoundKey(0);
}

function aesEcbDecrypt(bytes, keyString) {
  if (bytes.length === 0 || bytes.length % 16 !== 0) {
    throw pluginError("PLUGIN_PROTOCOL", "加密响应长度异常", false);
  }
  const keyBytes = utf8Bytes(keyString);
  if (keyBytes.length !== 32) {
    throw pluginError("PLUGIN_PROTOCOL", "响应密钥长度异常", false);
  }
  const words = expandKey(keyBytes);
  const state = new Uint8Array(16);
  const output = new Uint8Array(bytes.length);
  for (let offset = 0; offset < bytes.length; offset += 16) {
    decryptBlock(bytes.subarray(offset, offset + 16), words, state);
    output.set(state, offset);
  }
  const padding = output[output.length - 1];
  if (padding < 1 || padding > 16 || padding > output.length) {
    throw pluginError("PLUGIN_PROTOCOL", "响应填充无效", false);
  }
  for (let index = output.length - padding; index < output.length; index += 1) {
    if (output[index] !== padding) {
      throw pluginError("PLUGIN_PROTOCOL", "响应填充无效", false);
    }
  }
  return output.subarray(0, output.length - padding);
}

function decodeJson(value) {
  const body = String(value || "").trim();
  // The site's JSON occasionally arrives with a UTF-8 BOM, which JSON.parse rejects.
  const cleaned = body.charCodeAt(0) === 0xfeff ? body.slice(1) : body;
  try {
    return JSON.parse(cleaned || "null");
  } catch (error) {
    throw pluginError("PLUGIN_PROTOCOL", "禁漫响应无法解析", true);
  }
}

function decryptToJson(encoded, ts, secret) {
  const plain = decodeUtf8(
    aesEcbDecrypt(decodeBase64(String(encoded).replace(/\s+/g, "")), md5Hex(ts + secret))
  );
  return decodeJson(plain);
}

// Responses are envelopes: {"code":200,"data":"<base64 aes>"}. The model lives in `data`, and some
// endpoints answer with a bare array once decrypted.
function decodeModel(body, ts) {
  const envelope = decodeJson(body);
  if (Array.isArray(envelope)) return envelope;
  const wrapper = asObject(envelope);
  if (wrapper.code !== undefined && Number(wrapper.code) !== 200) {
    throw serverError(wrapper);
  }
  const model =
    typeof wrapper.data === "string" && wrapper.data.trim()
      ? decryptToJson(wrapper.data, ts, TOKEN_SECRET)
      : wrapper;
  if (typeof (model || {}).error === "string") throw serverError(model);
  return model;
}

function serverError(payload) {
  const message = String((payload || {}).error || (payload || {}).msg || "禁漫接口返回异常");
  return pluginError("HTTP", message, true);
}

function isTransportError(error) {
  if (!error) return false;
  const code = String(error.code || "");
  if (code === "NETWORK" || code === "TIMEOUT") return true;
  return /timeout|network|connect|host|address/i.test(String(error.message || ""));
}

function isEmptyResult(model) {
  return String((model || {}).error || "").indexOf("没有资料") >= 0;
}

// --------------------------------------------------------------------------- settings & storage

let settingCache = null;
let endpointState = null;
let hostConfigHosts = null;

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

// --------------------------------------------------------------------------- endpoints

function toHost(value) {
  const raw = String(value || "").trim();
  if (!raw) return "";
  return raw.replace(/^https?:\/\//, "").split("/")[0].toLowerCase();
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
    .filter(Boolean)
    .forEach(function (host) {
      if (seen.indexOf(host) < 0) seen.push(host);
    });
  return seen;
}

function normaliseState(value) {
  const state = asObject(value);
  const apiHosts = orderedHosts(asArray(state.apiHosts));
  const imageHosts = orderedHosts(asArray(state.imageHosts));
  return {
    apiHosts: apiHosts.length ? apiHosts : FALLBACK_API_HOSTS.slice(),
    imageHosts: imageHosts.length ? imageHosts : FALLBACK_IMAGE_HOSTS.slice(),
    appVersion: text(state.appVersion) || DEFAULT_APP_VERSION,
    updatedAt: finiteNumber(state.updatedAt, 0),
  };
}

async function endpoints(signal, force) {
  if (force) {
    endpointState = await discoverEndpoints(
      normaliseState(await storedValue(ENDPOINT_STATE_KEY, signal)),
      signal
    );
    await remember(ENDPOINT_STATE_KEY, endpointState, signal);
    return endpointState;
  }
  if (endpointState && Date.now() - endpointState.updatedAt < ENDPOINT_TTL_MS) return endpointState;
  const stored = normaliseState(await storedValue(ENDPOINT_STATE_KEY, signal));
  if (Date.now() - stored.updatedAt < ENDPOINT_TTL_MS) {
    endpointState = stored;
    return stored;
  }
  endpointState = await discoverEndpoints(stored, signal);
  await remember(ENDPOINT_STATE_KEY, endpointState, signal);
  return endpointState;
}

// Bootstrap cost is one config file plus one /setting call, so a cold isolate stays inside the
// NORMAL deadline. The latency-measuring sweep lives behind the refreshEndpoints action.
async function discoverEndpoints(fallback, signal) {
  const settings = await readSettings(signal);
  const configHosts = await loadHostConfig(signal);
  const state = {
    apiHosts: orderedHosts(
      [settings.apiHost].concat(configHosts, fallback.apiHosts, FALLBACK_API_HOSTS)
    ),
    imageHosts: orderedHosts([settings.imageHost].concat(fallback.imageHosts)),
    appVersion: fallback.appVersion,
    updatedAt: Date.now(),
  };
  if (!state.apiHosts.length) state.apiHosts = FALLBACK_API_HOSTS.slice();
  if (!state.imageHosts.length) state.imageHosts = FALLBACK_IMAGE_HOSTS.slice();

  for (let index = 0; index < Math.min(state.apiHosts.length, 3); index += 1) {
    let setting;
    try {
      setting = await callApi(hostBase(state.apiHosts[index]), "/setting", null, null, signal, false);
    } catch (error) {
      if (!isTransportError(error)) break;
      continue;
    }
    const imgHost = toHost(setting.img_host);
    if (imgHost) state.imageHosts = orderedHosts([imgHost].concat(state.imageHosts));
    // The site tells clients when their signing version is behind. Carrying it forward keeps every
    // request working across an app release we cannot ship ourselves.
    if (isVersionNewer(text(setting.jm3_version), state.appVersion)) {
      state.appVersion = text(setting.jm3_version);
    }
    state.apiHosts = orderedHosts([state.apiHosts[index]].concat(state.apiHosts));
    break;
  }
  return state;
}

async function loadHostConfig(signal) {
  if (hostConfigHosts !== null) return hostConfigHosts;
  const attempts = await Promise.all(
    HOST_CONFIG_URLS.map(function (url) {
      return inkleaf.host.http
        .request({ method: "GET", url: url }, signal)
        .then(function (response) {
          return responseText(response, signal).then(function (body) {
            if (response.statusCode !== 200) return [];
            // The file is prefixed with non-ASCII decoy bytes ahead of the base64 payload.
            let payload = String(body || "");
            while (payload.length && payload.charCodeAt(0) > 0x7e) payload = payload.slice(1);
            const servers = asObject(decryptToJson(payload.trim(), "", HOST_CONFIG_SECRET));
            return orderedHosts(asArray(servers.Server));
          });
        })
        .catch(function () {
          return [];
        });
    })
  );
  hostConfigHosts = orderedHosts(
    attempts.reduce(function (all, part) {
      return all.concat(part);
    }, [])
  );
  return hostConfigHosts;
}

function isVersionNewer(candidate, current) {
  if (!candidate) return false;
  const left = candidate.split(".");
  const right = current.split(".");
  for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
    const a = finiteNumber(left[index], 0);
    const b = finiteNumber(right[index], 0);
    if (a !== b) return a > b;
  }
  return false;
}

function timestampSeconds() {
  return String(Math.floor(Date.now() / 1000));
}

/** One signed call against one known base. Deliberately does not consult the endpoint state. */
async function callApi(base, path, extraParams, form, signal, useJwt) {
  const ts = timestampSeconds();
  const headers = {
    "user-agent": USER_AGENT,
    accept: "application/json",
    referer: base + "/",
    token: md5Hex(ts + TOKEN_SECRET),
    tokenparam: ts + "," + (endpointState ? endpointState.appVersion : DEFAULT_APP_VERSION),
  };
  if (useJwt !== false) {
    const jwt = await storedValue(JWT_KEY, signal);
    if (text(jwt)) headers.authorization = "Bearer " + text(jwt);
  }

  const params = Object.assign({}, extraParams);
  if (path === "/setting") {
    params.app_img_shunt = 1;
    params.t = ts;
  }
  const request = { method: form ? "POST" : "GET", url: base + path, headers: headers };
  if (Object.keys(params).length) request.url += "?" + queryString(params);
  if (form) {
    request.headers["content-type"] = "application/x-www-form-urlencoded";
    request.bodyBase64 = encodeBase64(utf8Bytes(queryString(form)));
  }

  const response = await inkleaf.host.http.request(request, signal);
  const body = await responseText(response, signal);
  if (response.statusCode === 401 || response.statusCode === 403) {
    throw pluginError(
      "AUTH_REQUIRED",
      "禁漫拒绝了请求（" + response.statusCode + "），可能需要登录或换线路",
      true
    );
  }
  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw pluginError("HTTP", "禁漫接口状态码 " + response.statusCode, true);
  }
  return decodeModel(body, ts);
}

/** Reads through the host pool, promoting whichever base worked last. */
async function apiRequest(path, params, form, signal) {
  const state = await endpoints(signal);
  const settings = await readSettings(signal);
  const queue = orderedHosts([settings.apiHost].concat(state.apiHosts));
  let lastError = null;
  for (let index = 0; index < Math.min(queue.length, HOST_TRY_LIMIT); index += 1) {
    try {
      const model = await callApi(hostBase(queue[index]), path, params, form, signal, true);
      if (index > 0) {
        endpointState = {
          apiHosts: orderedHosts([queue[index]].concat(queue)),
          imageHosts: state.imageHosts,
          appVersion: state.appVersion,
          updatedAt: Date.now(),
        };
        await remember(ENDPOINT_STATE_KEY, endpointState, signal);
      }
      return model;
    } catch (error) {
      lastError = error;
      if (!isTransportError(error)) throw error;
    }
  }
  throw lastError || pluginError("NETWORK", "没有可用的禁漫线路", true);
}

// --------------------------------------------------------------------------- mapping

function stripTags(value) {
  return String(value === null || value === undefined ? "" : value)
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<[^>]*>/g, "")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function asStringList(value) {
  if (Array.isArray(value)) {
    return value
      .map(function (item) {
        return text(item && item.title ? item.title : item);
      })
      .filter(Boolean);
  }
  const single = text(value);
  if (!single) return [];
  return single
    .split(/[,，]/)
    .map(function (item) {
      return item.trim();
    })
    .filter(Boolean);
}

function imageHeaders() {
  return {
    "user-agent": USER_AGENT,
    accept: "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
    "x-requested-with": "com.JMComic3.app",
  };
}

function coverImage(album, hosts) {
  const raw = text(album && album.image);
  let url = "";
  if (/^https?:\/\//.test(raw)) {
    url = raw;
  } else if (raw.charAt(0) === "/") {
    url = hosts.imageBase + raw;
  } else if (raw.indexOf("media/") === 0) {
    url = hosts.imageBase + "/" + raw;
  } else if (text(album && album.id)) {
    url = hosts.imageBase + "/media/albums/" + text(album.id) + "_3x4.jpg";
  }
  if (!url) return null;
  return { url: url, headers: imageHeaders(), referer: hosts.apiReferer };
}

/** The API answers with bare hosts and referers are checked, so both travel together. */
function lineHosts(state) {
  return { imageBase: hostBase(state.imageHosts[0]), apiReferer: hostBase(state.apiHosts[0]) + "/" };
}

function comicSummary(album, hosts) {
  const summary = {
    sourceId: text(album && album.id),
    title: text(album && album.name) || "无名",
    subtitle: asStringList(album && album.author).join(" / "),
    tags: asStringList(album && album.tags).slice(0, 12),
  };
  const cover = coverImage(album, hosts);
  if (cover) summary.cover = cover;
  return summary;
}

function albumRows(model) {
  if (Array.isArray(model)) return model;
  const rows = asArray((model || {}).content);
  return rows.length ? rows : asArray((model || {}).list);
}

function pageFromCursor(cursor) {
  const parsed = finiteNumber(cursor, 1);
  return parsed < 1 ? 1 : Math.floor(parsed);
}

function nextCursor(page, rows, total) {
  if (rows.length < PAGE_SIZE) return null;
  if (total > 0 && page * PAGE_SIZE >= total) return null;
  return String(page + 1);
}

function chapterRows(album) {
  const series = asArray((album || {}).series)
    .filter(function (item) {
      // sort === "0" marks the site's non-chapter cross-links (novels, other albums).
      return text(item && item.id) && String(item.sort) !== "0";
    })
    .map(function (item, index) {
      return {
        chapterId: text(item.id),
        title: text(item.name) || "第" + (index + 1) + "话",
        number: finiteNumber(item.order, index + 1),
      };
    });
  if (series.length) return series;
  // Single-chapter albums reuse the album id as the chapter id; that is what /chapter expects.
  const albumId = text((album || {}).id);
  return albumId ? [{ chapterId: albumId, title: "第1话", number: 1 }] : [];
}

function toItemList(rows, hosts) {
  return dedupeBy(
    rows.map(function (album) {
      return comicSummary(album, hosts);
    }),
    function (item) {
      return item.sourceId;
    }
  );
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
      { id: "latest", title: "最新更新" },
      {
        id: "ranking",
        title: "分类排行",
        filters: [
          { id: "c", title: "分类", type: "select", options: options(CATEGORY_OPTIONS) },
          { id: "o", title: "排行方式", type: "select", options: options(ORDER_OPTIONS) },
        ],
      },
      { id: "recommend", title: "禁漫推荐" },
    ],
    filters: [{ id: "o", title: "搜索排序", type: "select", options: options(ORDER_OPTIONS) }],
    actions: [
      { id: "login", title: "登录禁漫账号", kind: "login" },
      { id: "verifyCredentials", title: "校验账号", kind: "verifyCredentials" },
      { id: "logout", title: "退出登录", kind: "clearSession" },
      { id: "checkin", title: "每日签到", kind: "action" },
      { id: "refreshEndpoints", title: "重新选线", kind: "action" },
    ],
    settings: [
      { id: "account", title: "账号", type: "text", section: "登录" },
      { id: "password", title: "密码", type: "secret", secret: true, section: "登录" },
      { id: "apiHost", title: "指定 API 域名（留空自动）", type: "text", section: "线路" },
      { id: "imageHost", title: "指定图床域名（留空跟随接口）", type: "text", section: "线路" },
    ],
  };
}

// --------------------------------------------------------------------------- handlers

async function search(request, signal) {
  const query = text(request.query);
  if (!query) return browse({ feedId: "latest", cursor: request.cursor }, signal);

  const page = pageFromCursor(request.cursor);
  const hosts = lineHosts(await endpoints(signal));

  // A bare plate number (or a jm-prefixed one) is a direct hit on the site, so resolve it as an
  // album. Numeric keywords are also ordinary searches, so any failure falls through.
  const directId = query.toLowerCase().replace(/^jm/, "");
  if (/^\d{3,}$/.test(directId) && page === 1) {
    try {
      const album = await apiRequest("/album", { id: directId }, null, signal);
      if (album && text(album.id)) {
        return { items: [comicSummary(album, hosts)], nextCursor: null };
      }
    } catch (error) {
      if (isTransportError(error)) {
        return { items: [], nextCursor: null };
      }
    }
  }

  const model = await apiRequest(
    "/search",
    {
      search_query: query,
      page: page,
      o: text((request.filters || {}).o) || "mr",
    },
    null,
    signal
  );
  if (isEmptyResult(model)) return { items: [], nextCursor: null };
  const rows = albumRows(model);
  return {
    items: toItemList(rows, hosts),
    nextCursor: nextCursor(page, rows, finiteNumber(model.total, 0)),
  };
}

async function browse(request, signal) {
  const hosts = lineHosts(await endpoints(signal));
  const page = pageFromCursor(request.cursor);
  const filters = request.filters || {};

  if (request.feedId === "recommend") {
    const sections = await apiRequest("/promote", { page: 0 }, null, signal);
    const rows = [];
    asArray(sections).forEach(function (section) {
      rows.push.apply(rows, asArray(section.content));
    });
    return { items: toItemList(rows, hosts), nextCursor: null };
  }

  const ranking = request.feedId === "ranking";
  const params = ranking
    ? { page: page, c: text(filters.c) || "0", o: text(filters.o) || "mv_m" }
    : { page: page - 1 };
  const model = await apiRequest(ranking ? "/categories/filter" : "/latest", params, null, signal);
  if (isEmptyResult(model)) return { items: [], nextCursor: null };
  const rows = albumRows(model);
  return {
    items: toItemList(rows, hosts),
    nextCursor: nextCursor(page, rows, finiteNumber(model.total, 0)),
  };
}

async function detail(request, signal) {
  const sourceId = text(request.sourceId);
  const hosts = lineHosts(await endpoints(signal));
  const album = await apiRequest("/album", { id: sourceId }, null, signal);
  const result = {
    sourceId: text(album.id) || sourceId,
    title: text(album.name) || "无名",
    subtitle: asStringList(album.author).join(" / "),
    description: stripTags(album.description),
    tags: asStringList(album.tags).slice(0, 40),
  };
  const cover = coverImage(album, hosts);
  if (cover) result.cover = cover;
  return result;
}

async function chapters(request, signal) {
  const sourceId = text(request.sourceId);
  const album = await apiRequest("/album", { id: sourceId }, null, signal);
  return {
    sourceId: sourceId,
    chapters: chapterRows(album).map(function (chapter) {
      return {
        chapterId: chapter.chapterId,
        title: chapter.title,
        number: chapter.number,
        available: true,
        opaqueContext: { albumId: sourceId },
      };
    }),
    revision: text(album.update_at) || text(album.addtime) || null,
  };
}

async function pages(request, signal) {
  const sourceId = text(request.sourceId);
  const chapterId = text(request.chapterId);
  const hosts = lineHosts(await endpoints(signal));
  const settings = await readSettings(signal);
  const chapter = await apiRequest("/chapter", { id: chapterId }, null, signal);

  const fileNames = asArray(chapter.page_arr).length
    ? asArray(chapter.page_arr)
    : asArray(chapter.images);
  if (!fileNames.length) {
    throw pluginError("NOT_FOUND", "这一话没有可读图片（可能需要积分或已下架）", false);
  }

  // /chapter names the host its own images are served from; only fall back to our pool without it.
  const override = text(settings.imageHost) || toHost(chapter.data_original_domain);
  const imageBase = override ? hostBase(override) : hosts.imageBase;
  const scrambleId = Math.max(
    0,
    Math.floor(finiteNumber(chapter.scramble_id, DEFAULT_SCRAMBLE_ID))
  );

  return {
    sourceId: sourceId,
    chapterId: chapterId,
    pages: fileNames.map(function (fileName, index) {
      const name = String(fileName);
      return {
        pageId: chapterId + "/" + name,
        index: index,
        url: imageBase + "/media/photos/" + chapterId + "/" + name,
        headers: imageHeaders(),
        referer: hosts.apiReferer,
        imageTransform: { type: "jmSegmentation", scrambleId: scrambleId },
      };
    }),
  };
}

async function invokeAction(request, signal) {
  const actionId = text(request.actionId);
  const input = asObject(request.input);

  if (actionId === "logout") {
    await remember(JWT_KEY, "", signal);
    await remember(USER_KEY, "", signal);
    return { ok: true, message: "已退出登录" };
  }

  if (actionId === "login" || actionId === "verifyCredentials") {
    const settings = await readSettings(signal);
    const account = text(input.account) || settings.account;
    const password = text(input.password) || settings.password;
    if (!account || !password) {
      throw pluginError("INVALID_ARGUMENT", "请先在源设置里填写账号和密码", false);
    }
    const model = await apiRequest("/login", null, { username: account, password: password }, signal);
    const token = text(model.jwttoken);
    if (!token) {
      throw pluginError("AUTH_REQUIRED", text(model.message || model.msg) || "账号或密码错误", false);
    }
    await remember(JWT_KEY, token, signal);
    await remember(
      USER_KEY,
      { uid: text(model.uid), account: account, displayName: text(model.user_name) },
      signal
    );
    return { ok: true, message: "登录成功", account: account, uid: text(model.uid) };
  }

  if (actionId === "checkin") {
    const user = asObject(await storedValue(USER_KEY, signal));
    if (!text(user.uid)) throw pluginError("AUTH_REQUIRED", "请先登录禁漫账号", false);
    const listed = await apiRequest(
      "/daily_list/filter",
      null,
      { data: String(new Date().getFullYear()) },
      signal
    );
    const items = asArray(listed.list).concat(asArray(asObject(listed.data).list));
    if (!items.length) return { ok: true, message: "今天没有可签到项目" };
    const dailyId = text(items[items.length - 1].id);
    if (!dailyId) throw pluginError("PLUGIN_PROTOCOL", "签到列表缺少 id", true);
    const result = await apiRequest(
      "/daily_chk",
      null,
      { user_id: text(user.uid), daily_id: dailyId },
      signal
    );
    return { ok: true, message: text(result.msg) || "签到完成" };
  }

  if (actionId === "refreshEndpoints") {
    hostConfigHosts = null;
    const state = await endpoints(signal, true);
    return {
      ok: true,
      message: "已重新选线",
      apiHosts: state.apiHosts,
      imageHosts: state.imageHosts,
      appVersion: state.appVersion,
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
