# ADR-0001 — この repo は descriptor surface であって実装ではない

- **status**: accepted
- **date**: 2026-08-08
- **upstream**: ADR-2606231200（sovereign actor repo への切り出し）、
  ADR-2606230001 §4（observatory on-kse パターン）
- **supersedes**: なし

## Context

`cloud-itonami/handotai-actor` は 2026-05-21 に etzhayyim monorepo の `20-actors/` から
切り出された（`NOTICE`、commit `788178a`）。しかし切り出し以降、**この repo が何であるか
を述べた文書が 1 つも無かった** — README も ADR も無く、唯一の散文である `CLAUDE.md` は
兄弟 repo の写しだった。

その結果、実測できる形で次の混乱が残っていた（2026-08-08 実測、
`docs/identity-claims.edn` に固定）:

1. **west に `handotai` という名前の repo が 2 つある。** `handotai-actor`（この repo、
   `20-actors` 由来の descriptor）と `handotai`（`60-apps` 由来の実装、`:kind :app`、
   `appview/etzhayyim-wasm-handotai-dtyy44cr/` に `component.wasm` + SvelteKit を持つ）。
   名前からはどちらがどちらか分からない。
2. **この repo の `CLAUDE.md` は兄弟の写しで、しかも原本にある `DEPRECATED` 銘が
   落ちている。** 原本は当該実装を「T3 fallback only、actor は
   `20-actors/handotai/actor-manifest.jsonld` へ移行済み」と明記しているが、写しには
   それが無い。よってこの repo だけを読むと、**deprecated な fallback の deploy 手順を
   現行手順として読む**。移行先の `actor-manifest.jsonld` はこの repo にある。
3. **identity が 4 つに割れ、解決するのは 1 つだけ。** `did.json` の
   `did:web:etzhayyim.com:actor:handotai` のみ 200。manifest の `@id`
   （`did:web:handotai.etzhayyim.com`）と `CLAUDE.md` の
   （`did:web:handotai-dtyy44cr.etzhayyim.com`）は接続不可、`@context` は 404。
   原因は `4be1a4c` の did:web scheme 移行で `did.json` だけが移行され manifest が
   取り残されたこと。
4. **唯一解決する DID ですら、配信文書がこの repo の commit と別物。** id は同じだが
   suite・`alsoKnownAs`・PDS endpoint が食い違う。`.well-known/did.json` は live DID
   document の source ではない。

## Decision

**この repo の役割を「actor descriptor / identity 面」と明示し、実装を持たないことを
契約とする。** 実装は兄弟 `cloud-itonami/handotai` にある。

**未解決の identity 分裂は、この repo では解決しない。** どの名乗りを正とするかは
配信側（`etzhayyim.com` を serve しているもの）と PDS の運用を含む決定で、descriptor
repo 単独では決められない。代わりに **実測値を `docs/identity-claims.edn` に固定し、
`scripts/verify-descriptor.cljs` が現在の測定と突き合わせる**。

固定した値は「あるべき姿」ではなく「2026-08-08 に測った姿」である。したがって
verifier は **分裂が解消した場合にも赤くなる** — その時は doc を更新せよ、という意味で
落ちる。これは意図した挙動である。1 ヶ月以上放置された理由は、誰かが悪意を持って
放置したからではなく、**ずれても何も鳴らなかった**からである。

## Consequences

- README が名乗りを持ち、2 repo の区別が最初の表で読める。
- `CLAUDE.md` は冒頭に断り書きを持つ。verifier がその文言（`cloud-itonami/handotai` と
  `DEPRECATED`）の存在を検査するので、黙って外せない。
- 検査は 2 層。network 不要な構造不変条件（30）と、did:web を実際に解決する照合（+6）。
  CI は前者だけでも回せる。
- **verifier は 13 個の変異で赤くなることを確認済み**（2026-08-08）。うち 1 件は検査側の
  実バグを暴いた: `(defn on-kse\b` は Clojure symbol の `-` が word boundary になるため
  `(defn on-kse-RENAMED` にも当たり、改名を検出できなかった。symbol 構成文字の
  negative lookahead に修正した。
- **この repo は依存を持たない**（`deps.edn` / `package.json` 無し）。verifier は nbb と
  curl だけで動く。descriptor repo に build を持ち込まないための制約として維持する。

## 未解決（この ADR は答えを出さない）

- 4 つの名乗りのどれを正とするか。`actor-manifest.jsonld` の `@id` を `did.json` に
  合わせるのが最小の修正だが、`handotai.etzhayyim.com` を実際に立てる選択もある。
- `.well-known/did.json` と配信文書のどちらが正か。この repo を source にするなら配信を
  この repo から行う経路が要る（Pages は無効、`.nojekyll` は残骸）。
- `NOTICE` が指す `CHARTER-RIDER.md` と `LICENSE` の不在。NOTICE は Apache-2.0 + Rider
  v3.1 を主張しているが、本文がこの repo に無い。
- `@context`（`https://etzhayyim.com/ns/actor/v1`）が 404 である限り、
  `actor-manifest.jsonld` は JSON-LD ではなくただの JSON である。拡張子が主張を先取り
  している。
