# operator quickstart

この repo は **descriptor surface** なので、operator の仕事は「起動する」ことではなく
**名乗っていることが今も本当かを確かめる**ことである。所要 1〜2 分。

必要なもの: `nbb`（ClojureScript on Node）と `curl`。この repo に依存パッケージは無い
（`deps.edn` も `package.json` も持たない）。

## 1. 取得する

```bash
west update --fetch smart handotai-actor
cd orgs/cloud-itonami/handotai-actor
```

west を使わないなら `git clone git@github.com:cloud-itonami/handotai-actor` でよい。

## 2. 構造を検査する（network 不要）

```bash
nbb scripts/verify-descriptor.cljs
```

期待される最後の行は `30 検査 / 0 失敗`。これが見ているもの:

- `actor-manifest.jsonld` / `.well-known/did.json` が JSON として読め、必須キーを持つ
- pipeline の各 step が使う `fn` が、宣言された `capabilities` の部分集合であること
- DID document の `service[].id` が DID を prefix に持つこと（DID Core）
- `kotoba.app.edn` の component の `:src` が実在し、`run` を定義していること。
  `:kse` trigger を持つなら `on-kse` も定義していること
- **capability 宣言と実際の呼び出しの一致** — `methods/mesh.clj` が `kqe-*` を呼ぶなら
  `:requires` に `:cap/kqe` があること（逆も）
- `docs/identity-claims.edn` に固定した「参照先の実在」が実測と一致すること
- 各 `did:web` の解決 URL が、DID から機械的に導いたものと一致すること
- `CLAUDE.md` が兄弟 repo（`cloud-itonami/handotai`）と `DEPRECATED` に触れていること
  — 冒頭の断り書きを黙って外せないようにするため

## 3. identity を実際に解決する

```bash
nbb scripts/verify-descriptor.cljs --network
```

期待される最後の行は `36 検査 / 0 失敗`。**`0 失敗` は「全部健全」という意味ではない** —
この repo の identity は 4 つに割れており、そのうち 3 つが解決しないことが**既知の
状態として固定してある**。verifier が緑なのは「実測が固定値と一致した」という意味である。

だから **赤くなったら、壊れたとは限らない。直ったのかもしれない。** どちらの場合も
やることは同じ:

1. 何がどう変わったかを読む（失敗行が固定値と実測値の両方を出す）
2. `docs/identity-claims.edn` の `:measured` を実測に合わせ、`:measured-at` を更新する
3. **`README.md` の該当記述も直す** — これが本体。EDN だけ直すと README が嘘のまま残る
4. 変更理由を commit message に書く

## 4. mesh component を deploy する（任意）

```bash
kotoba app deploy kotoba.app.edn
```

`methods/mesh.clj` を CID にコンパイルして control datom に載せる。`:requires #{:cap/kqe}`
なので **KOTOBA Mesh 側で `cap/kqe` が付与されていないと admission で落ちる**（deny by
default）。この repo 単体では検証できない部分なので、上の verifier は「宣言と呼び出しが
一致しているか」までしか見ない。

## やらないこと

- **`CLAUDE.md` の Build & Deploy 節を実行しない。** あれは兄弟 repo
  `cloud-itonami/handotai` の、しかも deprecated な T3 fallback の手順で、`cd` する先は
  この repo に存在しない（`CLAUDE.md` 冒頭の断り書きを参照）。
- **`.well-known/did.json` を編集して「直った」としない。** このファイルは live DID
  document の source ではない（配信文書と内容が食い違っていることを実測済み）。ここを
  変えても配信は変わらない。
