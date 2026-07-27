# 再漫画 (zaimanhua) 协议笔记

这份文档记录的是**站点的事实**，不是插件的实现。插件坏了先看代码，接口变了先看这里。

事实来源是两个互相独立的开源实现，交叉验证：

- `deretame/Breeze-plugin-zaiManHuan`（TypeScript，Breeze 插件）
- `venera-app/venera-configs` 的 `zaimanhua.js`（JavaScript，venera 源）

两者一致的部分可信度高；分歧的部分单独记在下面，因为分歧通常意味着「站点改过」或「其中一方有 bug」。

---

## 通用约定

**API 根**：`https://v4api.zaimanhua.com/app/v1`

**版本门槛 `_v`（必需）**：每个请求都要带 `_v=<客户端版本号>`，例如 `_v=2.3.4`。

这不是可选的装饰参数。缺了它，`/comic/detail` 和 `/comic/chapter` 会返回 `errno: 2, errmsg: "漫画不存在或已被删除"`——也就是说**站点会谎称内容不存在，而不是报参数错误**。搜索、筛选、排行、更新这几个接口目前不带也能工作，但插件对所有请求统一携带，避免将来站点扩大门槛时变成难以定位的局部故障。

实测（2026-07）它是一个**最低版本**判断，不是白名单：

| `_v` | 结果 |
|---|---|
| `1.0.0` | 拒绝 |
| `2.3.4` | 通过 |
| `9.9.9` | 通过 |
| `x`（非法） | 拒绝 |

`platform` 和 `_c` 参数**不影响结果**，`channel=android`（venera 用的那个）**无效**。

**响应信封**：所有接口都是

```json
{ "errno": 0, "errmsg": "ok", "data": ... }
```

`errno !== 0` 即失败，`errmsg` 是给人看的原因。注意 `data` 的形状每个接口都不一样——有的是对象，有的是**裸数组**，详情接口还多套了一层 `data.data`。

**没有任何签名或加密。** 没有 md5(params + secret)，没有时间戳签名，没有加密响应体。这是纯明文 JSON API，插件不需要宿主提供任何 crypto 能力。

**鉴权**：`Authorization: Bearer <token>`。未登录时不带此头，公开内容照常返回。

**登录**（本插件不实现，仅记录）：

```
POST https://account-api.zaimanhua.com/v1/login/passwd
Content-Type: application/x-www-form-urlencoded;charset=utf-8

username=<账号>&passwd=<密码的 md5 十六进制小写>
```

成功后 token 在 `data.user.token`。

---

## 端点

### 搜索

```
GET /search/index?keyword=<关键词>&page=1&sort=0&size=20
```

`data.list[]` 是结果数组，`data.total` 是总数，`data.size` 是每页条数。

单项字段：

| 字段 | 说明 |
|---|---|
| `comic_id` / `id` | 漫画 id。**优先取 `comic_id`**，为 0 或缺失时回退 `id` |
| `title` | 标题 |
| `authors` | 作者，字符串，多个用 `/` 分隔 |
| `cover` | 封面 URL |
| `status` | 连载状态，字符串（如「连载中」「已完结」） |
| `types` | 题材，字符串，多个用 `/` 分隔 |
| `last_updatetime` | 秒级 Unix 时间戳 |
| `last_update_chapter_name` | 最新章节名 |

### 分类筛选

```
GET /comic/filter/list?theme=0&cate=0&status=0&zone=0&sortType=1&page=1&size=20
```

`data.comicList[]` 是结果，`data.totalNum` 是总数。单项字段同搜索。

### 排行榜

```
GET /comic/rank/list?page=1&rank_type=0&by_time=0
```

`data` **直接就是数组**，没有总数字段。`rank_type` 是榜单类型，`by_time` 是时间周期（见下方筛选维度）。

### 最近更新

```
GET /comic/update/list/0/1
```

路径最后两段是 `<分类>/<页码>`。`data` 同样是**裸数组**。

### 漫画详情

```
GET /comic/detail/<comicId>
```

真正的数据在 `data.data`（**双层嵌套**，容易踩）。

| 字段 | 说明 |
|---|---|
| `id` | 漫画 id |
| `title` | 标题 |
| `cover` | 封面 URL |
| `description` | 简介 |
| `last_updatetime` | 秒级时间戳 |
| `last_update_chapter_name` | 最新章节名 |
| `authors[]` | 对象数组，取 `tag_name` |
| `status[]` | 对象数组，取 `tag_name` |
| `types[]` | 对象数组，取 `tag_name` |
| `hit_num` / `hot_num` / `subscribe_num` | 点击 / 热度 / 订阅数 |
| `chapters[]` | **分组数组**，见下 |

`chapters` 的结构是分组的，不是扁平的：

```json
"chapters": [
  { "title": "连载", "data": [ { "chapter_id": 123, "chapter_title": "第01话", ... } ] },
  { "title": "单行本", "data": [ ... ] }
]
```

组内章节是**倒序**的（最新在前），需要 reverse 才是阅读顺序。章节项字段：

| 字段 | 说明 |
|---|---|
| `chapter_id` | 章节 id |
| `chapter_title` | 章节名 |
| `chapter_order` | 排序号 |
| `updatetime` | 秒级时间戳 |
| `is_fee` | 是否付费 |
| `canRead` | 是否可读 |

### 章节图片

```
GET /comic/chapter/<comicId>/<chapterId>
```

数据在 `data.data`（同样双层）。

| 字段 | 说明 |
|---|---|
| `chapter_id` | 章节 id |
| `title` | 章节名 |
| `chapter_order` | 排序号 |
| `page_url_hd[]` | 高清图 URL 数组 |
| `page_url[]` | 普通图 URL 数组 |
| `canRead` | 是否可读 |

取图规则：优先 `page_url_hd`，为空则回退 `page_url`。两份参考实现都是无条件优先高清。

权限不足时 `canRead` 为 `false` 且图片数组为空。

---

## 筛选维度

分类筛选接口接受 5 个正交参数。以下取值来自 venera 的实现，是硬编码的站点内部 id。

**排序 `sortType`**：`1` 更新 · `2` 人气

**受众 `cate`**：`0` 全部 · `3262` 少年漫画 · `3263` 少女漫画 · `3264` 青年漫画 · `13626` 女青漫画

**进度 `status`**：`0` 全部 · `2309` 连载中 · `2310` 已完结 · `29205` 短篇

**地区 `zone`**：`0` 全部 · `2304` 日本 · `2305` 韩国 · `2306` 欧美 · `2307` 港台 · `2308` 内地 · `8435` 其他

**题材 `theme`**：下表是 venera 记录的完整映射，**其中 6 个是死 id**（`3249` `3365` `4459` `6219` `17192` `23388`），插件已剔除，详见下方「已验证的事实」。

| id | 名称 | id | 名称 | id | 名称 |
|---|---|---|---|---|---|
| 0 | 全部 | 3245 | 悬疑 | 5848 | 奇幻 |
| 4 | 冒险 | 3246 | 纯爱 | 6219 | 节操 |
| 5 | 欢乐向 | 3248 | 热血 | 6316 | 轻小说 |
| 6 | 格斗 | 3249 | 泛爱 | 6437 | 颜艺 |
| 7 | 科幻 | 3250 | 历史 | 7568 | 搞笑 |
| 8 | 爱情 | 3251 | 战争 | 7900 | 舰娘 |
| 9 | 侦探 | 3252 | 萌系 | 13627 | 动画 |
| 10 | 竞技 | 3253 | 宅系 | 17192 | AA |
| 11 | 魔法 | 3254 | 治愈 | 18522 | 福瑞 |
| 12 | 神鬼 | 3255 | 励志 | 23323 | 生存 |
| 13 | 校园 | 3324 | 武侠 | 23388 | 仙侠 / 日常（见下） |
| 14 | 惊悚 | 3325 | 机战 | 30788 | 画集 |
| 16 | 其他 | 3326 | 音乐舞蹈 | 31137 | C100 |
| 17 | 四格 | 3327 | 美食 | 4459 | 高清单行 |
| 3242 | 亲情 | 3328 | 职场 | 4518 | TS |
| 3243 | 百合 | 3365 | 西方魔幻 | 5077 | 东方 |
| 3244 | 秀吉 | 5806 | 魔幻 | | |

**排行榜的两个维度**（`comic/rank/list`）：

- `by_time`：`0` 日榜 · `1` 周榜 · `2` 月榜 · `3` 总榜
- `rank_type`：`0` 人气 · `1` 吐槽 · `2` 订阅

---

## 两实现的分歧

分歧点比一致点更有信息量，逐条记录。

**1. 图片 Referer** — Breeze 给图片请求加 `Referer: https://www.zaimanhua.com/`，venera 什么都不加。venera 长期可用说明 Referer **大概率不是必需的**，Breeze 属于保险起见。本插件默认不发送，用设置项留了口子。

**2. User-Agent** — venera 用固定的 `Mozilla/5.0 (Linux; Android) Mobile`（一个明显伪造但一直能用的值）；Breeze 用 60 行设备型号表随机生成并持久化。没有任何证据表明站点对 UA 做指纹风控。本插件用固定值，暴露为可改设置项。

**3. 额外请求参数** — Breeze 每个请求带 `platform=android&timestamp=<秒>&_v=2.3.4&_c=101_01_01_000`；venera 只在详情接口带 `channel=android`。

**这一条我最初判断错了。** 静态阅读时我认为「两者都工作正常，说明这些参数都不是必需的」，实测推翻了它：`_v` 是**必需**的，Breeze 是对的；venera 的详情接口现在是坏的——它带的 `channel=android` 完全无效，所以 venera 上打开任何一本漫画都会得到「漫画不存在」。`platform` 和 `_c` 确实无关紧要。

教训记在这里：两个实现「都能跑」不等于「它们做的事都不必要」，也可能是其中一个**根本没跑通那条路径**。

**4. 章节排序** — venera 在**组内** reverse；Breeze 把所有分组拍平后**整体** reverse，连带把分组的先后顺序也倒过来了。后者大概率是 bug。本插件按组内 reverse。

---

## 已知 bug（不要照抄）

- **venera 的题材表里 `仙侠` 和 `日常` 都映射到 `23388`**。同一个 id 两个名字，必有一个是错的。待冒烟验证后修正，见下方未验证项。
- **Breeze 的权限错误文案写的是「请前往快漫画官方 app」**。它是从另一个插件 fork 来的，这行没改干净。
- **Breeze 的 `common.ts` 残留 copyManga 的数据形状**（`category_sub`、`actors`、`works`、`subtitle: '这是一个占位漫画条目'`）。再漫画没有这些概念，是模板没清理。

---

## 已验证的事实（2026-07 冒烟）

原先列为「待验证」的四条已经全部有答案。

**图片不需要 Referer。** 同一张图 URL，带与不带 `Referer: https://www.zaimanhua.com/` 都返回 200。插件默认不发送，设置项保留为逃生舱。图片 URL 自带 `?sign=<md5>&t=<时间戳>` 签名，鉴权在 URL 里而不在请求头里，这解释了为什么 Referer 无关紧要。

**`size` 接受大于 20 的值。** 实测 `size=100` 正常，返回 `min(total, size)` 条。`data.size` 会回显请求值。因此「每页条数」设置项的 10/20/30/50 全部有效。

**题材 `23388` 是死 id。** `totalNum=0`，不返回任何内容——所以 venera 那两个名字（仙侠 / 日常）**都不对**，不是二选一。

顺带把整张题材表逐个打了一遍，另外发现 5 个同样返回 `totalNum=0` 的死 id：

| id | venera 上的名字 |
|---|---|
| 3249 | 泛爱 |
| 3365 | 西方魔幻 |
| 4459 | 高清单行 |
| 6219 | 节操 |
| 17192 | AA |
| 23388 | 仙侠 / 日常 |

这 6 个已从插件的题材选项里移除。永远返回空页的选项不是「入口」，是坏的。

**`canRead` 字段确实存在且可信。** 在一本免费连载漫画上，全部 16 章 `canRead: true`、`is_fee: false`，字段都存在（没有缺失）。未能取到 `canRead: false` 的样本——需要一本付费漫画才能观察到，因此「`is_fee` 单独是否意味着锁定」仍未验证。插件采取保守策略：**只有 `canRead === false` 才标记为不可读**，宁可让用户点进去看到明确报错，也不在列表里谎称某章不存在。

---

## fixture 怎么录

fixture 是给单元测试用的**真实响应样本**，位于 `plugin-fixtures/zaimanhua/fixtures/`。录制是手动动作，一年可能只做两次，不值得写脚本。

步骤：

1. 跑 `node plugin-fixtures/zaimanhua/smoke.js`。它会打真网络并把每个接口的原始响应打印出来。
2. 把需要的响应体粘进 `fixtures/<接口名>.json`。
3. **把列表截断到 3 条**（`data.list`、`data.comicList`、`chapters[].data` 等），但**保留完整的字段结构**——截断条数是为了可读，删字段会让测试在站点改字段时失去感知能力。
4. 不需要脱敏：这些接口不带登录，返回的全是公开漫画元数据。

如果站点改了接口，重录一遍 fixture，测试会立刻告诉你哪些解析假设不再成立。
