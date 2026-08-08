> ## ⚠ この文書は **この repo のものではない**
>
> 以下は兄弟 repo **`cloud-itonami/handotai`**（west: `orgs/cloud-itonami/handotai`）の
> CLAUDE.md の写しで、そちらの実装（`appview/etzhayyim-wasm-handotai-dtyy44cr/` —
> `component.wasm` + SvelteKit）を説明している。**この repo にその実装は無い** —
> 下の Build & Deploy 節が `cd` する先も、この repo には存在しない。
>
> さらに、兄弟側の原本にある **DEPRECATED** 銘がこの写しからは落ちている。原本はこう
> 書いている:
>
> > **DEPRECATED**: Actor migrated to `20-actors/handotai/actor-manifest.jsonld`
> > (T1 MCP-Compose). This project wasm/\*/src/app.ts is retained as T3 fallback only.
>
> つまり以下の Build & Deploy 手順は **deprecated な T3 fallback のもの**であって現行の
> actor 経路ではない。その移行先である `actor-manifest.jsonld` は**この repo にある**。
>
> **この repo が何であるかは [README.md](README.md) を読むこと。** この写しは、上記の
> 断りを付けた上で、兄弟側の記述への参照として残している。

# etzhayyim-project-handotai — Semiconductor Intelligence Platform

## App Identity

→ nanoid / domain: `deps.toml [[mitama_actors]]`

| Key | Value |
|---|---|
| **AT bot DID** | `did:web:handotai-dtyy44cr.etzhayyim.com` |
| **World** | `etzhayyim-actor-agent` (LLM agent for semiconductor intelligence) |
| **Runtime** | **Single Worker** (TS Native + SvelteKit SSR) |
| **Data store** | **W Protocol Event Stream** — Write: `WRecord()` (PDS → yata SQL direct (SHA-256 content CID))、Read: `G()` (yata SQL) |
| **UI mode** | `appview` |
| **Reference** | ohsi (vibes.etzhayyim.com) が canonical 実装パターン。handotai は W Protocol Event Stream stateful 拡張例 |

## Writer Entities (News Sources with DID)

Each news source is a **writer entity** with its own AT Protocol bot DID. Articles produced by a writer carry `writer_did` and `actor_id` = writer's DID for provenance tracking.

| Source | DID | Language | Category |
|---|---|---|---|
| PC Watch | `did:web:handotai.etzhayyim.com:writer:pcw` | ja | fabrication |
| ITmedia NEWS | `did:web:handotai.etzhayyim.com:writer:itm` | ja | market |
| Publickey | `did:web:handotai.etzhayyim.com:writer:pubk` | ja | design |
| SemiAnalysis | `did:web:handotai.etzhayyim.com:writer:semia` | en | market |
| Semiconductor Engineering | `did:web:handotai.etzhayyim.com:writer:semie` | en | design |
| EE Times | `did:web:handotai.etzhayyim.com:writer:eet` | en | market |

- Writer entities are registered via `IdentityRegister()` at init with discoverable `crawl` tool
- Custom sources added via `source_add` command get dynamic DIDs: `did:web:handotai.etzhayyim.com:writer:{source_id}`
- `kotodama.jsonld` `entities[]` section declares all built-in writers

## W Protocol Event Stream Data Flow

```
Writer Entity (DID) → crawlSource(RSS) → enrichArticle(LLM)
  → WRecord("articles", row)  ← W Protocol write (PDS → yata SQL direct (SHA-256 content CID))
  → WSend(handotai-feed)       ← Channel notification (summary card)
  → evaluateAlerts()           ← Alert matching → WSend/WCreateDM

Read path:
  G("Articles").Match(Eq{...}).Return("*").Query()  ← SQL
  G("Article").Match(Eq{...}).Query()   ← yata SQL analytics
```

## Architecture

```
Request → CF Single Worker
  ├─ /_worker/health, /_worker/metrics  → instant edge response
  ├─ /uploads/*                          → CDN_R2 (immutable)
  ├─ /api/*, /health                     → KotodamaContainer DO (WASM)
  │   ├─ seed_articles      → WRecord("articles", payload)
  │   ├─ list_articles      → G("Articles").Match(Eq{...}).Return("*").Query()
  │   ├─ search_articles    → G("Articles").Match(Like{...}).Return("*").Query()
  │   ├─ update_translation → WUpdate(rkey, payload)
  │   └─ crawl_trigger      → RSS fetch + LLM enrichment
  ├─ /_app/*                            → Workers Assets (immutable)
  ├─ HTML pages (no .)                  → SvelteKit SSR → +page.server.ts
  └─ OTEL log                           → R2 NDJSON (全リクエスト、async)
```

## OTEL Observability

**全リクエストを OTLP JSON 準拠で B2 に永続化。**

| Field | OTEL Semantic Convention | Source |
|---|---|---|
| `timeUnixNano` | OTLP timestamp | `Date.now() + "000000"` |
| `traceId` / `spanId` | W3C Trace Context | `crypto.getRandomValues()` |
| `severityNumber` | OTLP severity (9=INFO, 13=WARN, 17=ERROR) | HTTP status code |
| `http.request.method` | HTTP SC | `request.method` |
| `url.path` | HTTP SC | `url.pathname` |
| `http.route` | HTTP SC | categorized route |
| `http.response.status_code` | HTTP SC | response status |
| `http.response.body.size` | HTTP SC | response bytes |
| `client.address` | HTTP SC | `CF-Connecting-IP` |
| `client.geo.country_iso_code` | HTTP SC | `request.cf.country` |
| `user_agent.original` | HTTP SC | `User-Agent` header |
| `http.server.request.duration` | HTTP SC | ms |
| `kotodama.route.status` | Custom | `api` / `static` / `ssr` |
| `kotodama.app_id` | Custom | app name |

**Storage**: `{app}/otel-logs/{hour}/{ts}.ndjson` in B2 (`etzhayyim-graph` bucket)
**Worker metrics**: `/_worker/metrics` (OTEL-compatible JSON with histograms)
**Backend telemetry**: `kotodama:observability/telemetry@1.0.0` WIT (counter/gauge/histogram)
**Backend access-log**: `kotodama:observability/access-log@1.0.0` WIT (auto-captured)
**Trace propagation**: UI Worker → backend Worker via `traceparent` header

## Data Model (W Protocol Event Stream)

### `articles` WRecord kind

| Column | Type | Description |
|---|---|---|
| `article_id` | string | Primary key (nanoid) |
| `source_url` | string | Original URL |
| `source_name` | string | Source name |
| `source_lang` | string | `ja` / `en` |
| `title_original` | string | Original title |
| `title_ja` | string | Japanese title |
| `title_en` | string | English title |
| `summary_original` | string | Original summary |
| `summary_ja` | string | Japanese summary |
| `summary_en` | string | English summary |
| `category` | string | `fabrication` / `design` / `equipment` / `materials` / `policy` / `market` / `supply_chain` / `research` |
| `subcategory` | string | `advanced_node` / `memory` / `cpu` / `gpu` / `automotive` / `power` / `lithography` / etc. |
| `entities` | string | JSON array (companies/products) |
| `tags` | string | JSON array (technology tags) |
| `sentiment` | string | `positive` / `neutral` / `negative` |
| `importance` | int | 1-5 |
| `published_at` | string | RFC 3339 |
| `crawled_at` | string | RFC 3339 |
| `visibility` | string | `free` / `pro` / `enterprise` |
| `writer_did` | string | Writer entity DID (provenance) |
| `org_id` | string | RLS |
| `user_id` | string | RLS |
| `actor_id` | string | RLS |

### `sources` WRecord kind

| Column | Type | Description |
|---|---|---|
| `source_id` | string | Primary key |
| `name` | string | Source display name |
| `url` | string | RSS URL |
| `source_type` | string | `rss` / `web` |
| `language` | string | `ja` / `en` |
| `category` | string | Default category |
| `crawl_interval_min` | int | Minutes |
| `enabled` | bool | Active |
| `writer_did` | string | Writer entity DID |
| RLS | | `org_id`, `user_id`, `actor_id` |

## Content Sources (80 seed articles)

### Japan Primary Sources (優先)

| Source | Language | Topics |
|---|---|---|
| **Nikkei xTECH** | ja | Rapidus, TSMC Kumamoto, TEL, Kioxia, 経産省, Murata, Rohm, SCREEN, Advantest, Lasertec, Shin-Etsu, SUMCO, JSR, Resonac |
| **EE Times Japan** | ja | Renesas, Disco, Ibiden, TOK, NEC, 東芝, NXP, Wolfspeed, KOKUSAI, Hitachi High-Tech, Panasonic |
| **PC Watch** | ja | Intel, MediaTek, Qualcomm, Fujitsu MONAKA, Socionext, Nikon, Kioxia |
| **ITmedia NEWS** | ja | Sony CIS, DRAM market |
| **Publickey** | ja | Arm CSS, Cadence, Lightmatter, Quantum Computing |

### Global Sources

| Source | Language | Topics |
|---|---|---|
| **SemiAnalysis** | en | NVIDIA, AMD, Broadcom, Intel 18A, Tenstorrent |
| **Semiconductor Engineering** | en | TSMC 2nm, ASML High-NA, Applied Materials, UCIe, GlobalFoundries, EU CHIPS, Amkor |
| **EE Times** | en | Samsung, SK Hynix, Micron, SMIC, TI, Infineon, RISC-V, Equipment market, Workforce |

## Multi-Language Support

- **Language toggle**: Header EN/JA button (`langStore` Svelte 5 runes)
- **Translation source**: `svelte/src/lib/translations.ts` (75+ static translation pairs)
- **Display logic**: `displayTitle(article, lang)` → `lookupJaTranslation(title_original)` → JA title
- **Known limitation**: TS Native 移行により UTF-8 問題は解消
- **Future**: WIT UTF-8 修正後、i18n.etzhayyim.com LLM auto-translation で SQL 直接保存に移行

## Build & Deploy

```bash
cd 60-apps/etzhayyim-project-handotai/wasm/etzhayyim-wasm-handotai-dtyy44cr

# Build
GOROOT=$(/opt/homebrew/opt/go@1.25/bin/go env GOROOT) \
PATH="/opt/homebrew/opt/go@1.25/bin:$PATH" etzhayyim build

# Deploy (Single Worker via Cloudflare REST API)
etzhayyim deploy

# Seed articles
for i in $(seq 0 79); do
  curl -s -X POST https://atproto.etzhayyim.com/xrpc/com.etzhayyim.apps.handotai.seedArticles \
    -H "Content-Type: application/json" -d "{\"i\":$i}"
  sleep 2
done

# Health check
curl https://handotai.etzhayyim.com/health
curl https://handotai.etzhayyim.com/_worker/health
curl https://handotai.etzhayyim.com/_worker/metrics
```

## Key Technical Notes

- **SSR prerender**: `svelte.config.js` の `prerender.entries: []` 必須。SSR-only (prerender は `platform` が undefined で空データに��る)
- **server/connect.ts**: SSR path は SvelteKit `fetch` で same-origin `/xrpc/...` を叩く
- **seed_articles**: バッチ対応済み

## Daily Evolution

- **ISCO 5-Agent Team**: BM/PO/MK/ENG/QA
- **Focus**: crawler success rate, article quality, translation accuracy, SEO, subscription conversion
