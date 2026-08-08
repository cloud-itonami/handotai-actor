# handotai-actor 半導体

**半導体サプライチェーン actor の *descriptor*。実装ではない。**
`did:web:etzhayyim.com:actor:handotai` · 🔴 R0 · docs/adr/0001

`handotai`（半導体）は主題を言うが、**この repo が何であるか**を言わない。しかも west
には `handotai` という名前の repo が**もう 1 つ**登録されている。だから最初に名乗る:

| west path | これは何か | 中身 |
|---|---|---|
| `orgs/cloud-itonami/handotai-actor` ← **ここ** | actor の **descriptor / identity 面** | `actor-manifest.jsonld`・`.well-known/did.json`・`kotoba.app.edn` + 20 行の mesh component |
| `orgs/cloud-itonami/handotai` | **実装**（`:kind :app`） | `appview/etzhayyim-wasm-handotai-dtyy44cr/` — `component.wasm` + SvelteKit |

出自が違う。この repo は etzhayyim monorepo の `20-actors/` 由来、兄弟は `60-apps/` 由来。
**ここには動くサービスは無い。** ここにあるのは「その actor が何を名乗り、何を要求し、
どのパイプラインを持つと宣言しているか」だけである。

## 確かめる

散文ではなく実行で確かめられる。`scripts/verify-descriptor.cljs` が、この README が
主張することを全部検査する。

```bash
nbb scripts/verify-descriptor.cljs             # 構造不変条件のみ（network 不要）
nbb scripts/verify-descriptor.cljs --network   # did:web を実際に解決して照合する
```

`--network` 無しで 30 検査、有りで 36 検査。手順は [docs/operator-quickstart.md](docs/operator-quickstart.md)。

## ここにあるもの

| ファイル | 役割 |
|---|---|
| `actor-manifest.jsonld` | actor 宣言。5 pipeline（cron×2 / subscribeRepos / xrpc×2）、6 writer DID、RSS 6 本 |
| `.well-known/did.json` | DID document（**配信されているものとは別物** — 下記） |
| `kotoba.app.edn` | KOTOBA Mesh app manifest。component 1 本、`:requires #{:cap/kqe}` |
| `methods/mesh.clj` | その component 実体。20 行。`observe` / `run` / `on-kse` |
| `docs/identity-claims.edn` | 下の表の**実測値を固定したもの**。verifier の期待値 |
| `CLAUDE.md` | **兄弟 repo の写し**。冒頭の断り書きを読むこと |
| `storage-profile.edn` / `NOTICE` / `.nojekyll` | 保管方針 / 出所・ライセンス表示 / Pages 残骸 |

`methods/mesh.clj` は「半導体供給の観測点」を KOTOBA Mesh に置くもので、node→stage の
生産辺を Datom として assert し、Datalog で供給段の集中度を導いて RESILIENCE に流す。
**姿勢は resilience / diversification map であって target list ではない**（公開事実のみ、
集計のみ）。これは manifest が宣言する RSS クロール実装とは**別の runtime**である。

## identity が 4 つある（これは既知の未解決事項）

この actor は 4 つの異なる名前で名乗られており、**解決するのは 1 つだけ**。
2026-08-08 実測、`docs/identity-claims.edn` に固定済み:

| 名乗り | 出どころ | 解決 |
|---|---|---|
| `did:web:etzhayyim.com:actor:handotai` | `.well-known/did.json` | **200** |
| `did:web:handotai.etzhayyim.com` | `actor-manifest.jsonld` の `@id` | 接続不可 |
| `did:web:handotai-dtyy44cr.etzhayyim.com` | `CLAUDE.md` | 接続不可 |
| `https://etzhayyim.com/ns/actor/v1` | manifest の `@context` | **404** |

**唯一解決する 1 つも、配信されている文書はこの repo の commit と別物である。** id は
同じだが suite（`ed25519-2020` / `jws-2020`）・`alsoKnownAs`（4 件 / 空）・PDS endpoint
（`pds.etzhayyim.com` / `pds.aozora.app`）が食い違う。つまり **`.well-known/did.json` は
live DID document の source ではない** — 誰か別のものが配信している。

`@context` が 404 なので、`actor-manifest.jsonld` は JSON-LD として展開できない
（実質ただの JSON）。`.nojekyll` があるが GitHub Pages は有効化されていない（404）。

これらは**直していない** — どれを正とするかはこの repo だけでは決められない。代わりに
実測値を固定して、**どちらに動いても verifier が赤くなる**ようにした（直った場合も
「doc を更新せよ」と言って落ちる）。

## 参照先が切れているもの

| どこから | どこへ | |
|---|---|---|
| `NOTICE` | `CHARTER-RIDER.md` | 無い（`LICENSE` も無い。NOTICE は Apache-2.0 + Rider を主張） |
| `actor-manifest.jsonld` | `90-docs/rules/compliance/...` ほか 1 件 | 無い（etzhayyim monorepo 内のパス） |
| `CLAUDE.md` | `60-apps/etzhayyim-project-handotai/wasm/...` | 無い（兄弟 repo 側にある） |

これも固定してあるので、埋まったら verifier が教える。

## 出所

etzhayyim `20-actors` からの移行（2026-05-21、NOTICE 参照）。`788178a` で snapshot、
`4be1a4c` で did:web を `etzhayyim.com` scheme へ移行（**このとき `did.json` だけが移行され
`actor-manifest.jsonld` の `@id` が取り残された** — 上表 2 行目の由来）。
旧 GitHub 名 `etzhayyim/com-etzhayyim-handotai` はこの repo へリダイレクトする。

Apache License 2.0 + etzhayyim Charter Compliance Rider v3.1（`NOTICE`。ただし Rider 本文は
上記のとおり未収録）。
