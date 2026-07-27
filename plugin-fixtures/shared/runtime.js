// Shared helpers for Inkleaf source plugins.
//
// This file is NOT a module and NOT a standalone script. The build script concatenates it with a
// source's own file and wraps the result in one IIFE, because a plugin package may contain exactly
// one main.js -- the archive validator rejects any other entry at the root. So everything here is
// a bare function declaration that the source file can call directly.
//
// Keep this file free of site-specific knowledge. If a helper needs to know about a particular
// API's envelope, URL shape, or field names, it belongs in that source's file instead.

function pluginError(code, message, retryable) {
  const error = new Error(message);
  error.code = code;
  error.retryable = Boolean(retryable);
  return error;
}

function asObject(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
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
    .filter(function (key) {
      return values[key] !== null && values[key] !== undefined && values[key] !== "";
    })
    .map(function (key) {
      return encodeURIComponent(key) + "=" + encodeURIComponent(String(values[key]));
    })
    .join("&");
}

// The host rejects a page containing two items with the same sourceId, so every list a plugin
// returns has to be filtered. Sites do serve duplicates -- a comic can appear twice in one
// ranking page when its rank changes mid-request.
function dedupeBy(items, keyOf) {
  const seen = new Set();
  return items.filter(function (item) {
    if (!item) return false;
    const key = keyOf(item);
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
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
  for (let index = 0; index < bytes.length; ) {
    const first = bytes[index++];
    let codePoint;
    if (first < 0x80) {
      codePoint = first;
    } else if ((first & 0xe0) === 0xc0 && index < bytes.length) {
      codePoint = ((first & 0x1f) << 6) | (bytes[index++] & 0x3f);
    } else if ((first & 0xf0) === 0xe0 && index + 1 < bytes.length) {
      codePoint = ((first & 0x0f) << 12) | ((bytes[index++] & 0x3f) << 6) | (bytes[index++] & 0x3f);
    } else if ((first & 0xf8) === 0xf0 && index + 2 < bytes.length) {
      codePoint =
        ((first & 0x07) << 18) |
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

// Bodies over the host's inline threshold arrive as a handle that must be paged through and then
// released. Getting this wrong truncates large responses *silently* -- the JSON still parses, it
// just has fewer entries -- so the loop refuses to continue unless the offset actually advances.
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
      const chunk = await inkleaf.host.http.read(
        { handle: handle, offset: offset, maxBytes: 384 * 1024 },
        signal
      );
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

/** Issues a request and returns { statusCode, body } with the body already decoded to text. */
async function requestText(request, signal) {
  const response = await inkleaf.host.http.request(request, signal);
  const body = await responseText(response, signal);
  return { statusCode: response.statusCode, body: body };
}
