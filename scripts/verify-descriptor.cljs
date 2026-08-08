#!/usr/bin/env nbb
;; scripts/verify-descriptor.cljs — この repo が名乗っていることを、実際に確かめる。
;;
;;   nbb scripts/verify-descriptor.cljs              # offline のみ（CI 既定）
;;   nbb scripts/verify-descriptor.cljs --network    # did:web を実際に解決する
;;
;; この repo は実装ではなく **descriptor surface**（README 参照）なので、壊れ方は
;; 「テストが赤くなる」ではなく「書いてあることが静かに嘘になる」である。だから
;; gate は 2 種類を見る:
;;
;;   A. 構造不変条件 — 文書が parse でき、指している先が実在し、宣言した capability が
;;      実際の呼び出しと一致する。network 不要。
;;   B. 主張のドリフト — docs/identity-claims.edn に **実測値を固定**してあり、現在の
;;      測定がそれと違えば落ちる。直った場合も落ちる（「doc を更新せよ」と言って）。
;;
;; B が「直っても落ちる」のは意地悪ではない。この repo の identity claim は 4 つが
;; 食い違ったまま 1 ヶ月以上放置され、どれが正かを誰も書いていなかった。次に誰かが
;; 片方を直したとき、README がそれに追従しないと元の状態に戻る。

(ns verify-descriptor
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["fs" :as fs]
            ["child_process" :as cp]
            ["crypto" :as crypto]))

(def ^:private network? (boolean (some #{"--network"} *command-line-args*)))

(def ^:private failures (atom []))
(def ^:private checks (atom 0))

(defn- fail! [id msg]
  (swap! failures conj (str id " — " msg)))

(defn- check! [id ok? msg]
  (swap! checks inc)
  (if ok?
    (println "  ok  " id)
    (do (println "  FAIL" id "—" msg) (fail! id msg))))

(defn- exists? [p] (.existsSync fs p))
(defn- slurp* [p] (when (exists? p) (.readFileSync fs p "utf8")))

;; ---------------------------------------------------------------- did:web

(defn did-web->url
  "did:web を解決 URL にする（W3C did:web）。method-specific id の ':' が '/' になり、
   path 成分が無いときだけ /.well-known/ を挟む。host のポートは %3A で表される。"
  [did]
  (when (str/starts-with? did "did:web:")
    (let [segs (str/split (subs did (count "did:web:")) #":")
          host (str/replace (first segs) "%3A" ":")
          path (rest segs)]
      (if (seq path)
        (str "https://" host "/" (str/join "/" path) "/did.json")
        (str "https://" host "/.well-known/did.json")))))

;; ---------------------------------------------------------------- network

(defn- curl-code [url]
  (try
    (-> (.execFileSync cp "curl"
                       (clj->js ["-sL" "--max-time" "20" "-o" "/dev/null" "-w" "%{http_code}" url])
                       #js {:encoding "utf8"})
        str/trim js/parseInt)
    (catch :default e
      ;; curl は DNS 不成立でも exit≠0 かつ stdout に "000" を出す。
      (let [out (some-> (.-stdout e) str str/trim)]
        (if (and out (re-matches #"\d+" out)) (js/parseInt out) 0)))))

(defn- curl-sha256 [url]
  (try
    (let [buf (.execFileSync cp "curl" (clj->js ["-sL" "--max-time" "20" url]))]
      (-> (.createHash crypto "sha256") (.update buf) (.digest "hex")))
    (catch :default _ nil)))

(defn- file-sha256 [p]
  (when (exists? p)
    (-> (.createHash crypto "sha256") (.update (.readFileSync fs p)) (.digest "hex"))))

;; ---------------------------------------------------------------- A. 構造

(defn verify-structure! []
  (println "\nA. 構造不変条件")
  (let [manifest-s (slurp* "actor-manifest.jsonld")
        did-s      (slurp* ".well-known/did.json")
        app-s      (slurp* "kotoba.app.edn")]

    (check! :manifest/exists (some? manifest-s) "actor-manifest.jsonld が無い")
    (check! :did/exists (some? did-s) ".well-known/did.json が無い")
    (check! :app/exists (some? app-s) "kotoba.app.edn が無い")

    (let [manifest (try (js->clj (js/JSON.parse manifest-s)) (catch :default _ nil))
          did-doc  (try (js->clj (js/JSON.parse did-s)) (catch :default _ nil))
          app      (try (edn/read-string app-s) (catch :default _ nil))]

      (check! :manifest/parses (map? manifest) "JSON として読めない")
      (check! :did/parses (map? did-doc) "JSON として読めない")
      (check! :app/parses (map? app) "EDN として読めない")

      (when (map? manifest)
        (doseq [k ["@id" "@context" "name" "nanoid" "capabilities" "pipelines"]]
          (check! (keyword "manifest" (str "has" k)) (contains? manifest k)
                  (str "必須キー " k " が無い")))
        ;; pipeline の step が使う fn は、宣言された capabilities の部分集合であること。
        (let [declared (set (get manifest "capabilities"))
              used     (set (for [p (get manifest "pipelines")
                                  s (get p "steps")]
                              (get s "fn")))
              extra    (remove declared used)]
          (check! :manifest/capabilities-cover-steps (empty? extra)
                  (str "pipeline が使う fn が capabilities に無い: " (pr-str (vec extra))))))

      (when (map? did-doc)
        (check! :did/is-did-web (str/starts-with? (str (get did-doc "id")) "did:web:")
                "id が did:web: で始まらない")
        ;; service の id は DID を prefix に持つこと（DID Core）。
        (doseq [svc (get did-doc "service")]
          (check! (keyword "did" (str "service-prefix/" (get svc "type")))
                  (str/starts-with? (str (get svc "id")) (str (get did-doc "id") "#"))
                  (str "service id が DID を prefix に持たない: " (get svc "id")))))

      ;; kotoba.app.edn の component が指す src が実在し、KSE trigger の契約を満たすこと。
      (when (map? app)
        (doseq [c (:kotoba.app/components app)]
          (let [src (:src c)
                body (slurp* src)]
            (check! (keyword "app" (str "src-exists/" (:name c))) (some? body)
                    (str ":src が指す " src " が無い"))
            (when body
              ;; :kse trigger を持つなら on-kse を、常に run を定義していること。
              ;;
              ;; 境界に \b を使わないこと。Clojure の symbol は '-' を含むので
              ;; `(defn on-kse\b` は `(defn on-kse-RENAMED` にも当たる（'e' と '-' の
              ;; 間が word boundary になる）。実際 mutation M2 がこれで緑のまま
              ;; 通り抜けた。symbol を構成しうる文字が続かないことを直接見る。
              (check! (keyword "app" (str "defines-run/" (:name c)))
                      (re-find #"\(defn\s+run(?![-\w*+!?<>=/.'])" body)
                      (str src " が run を定義していない"))
              (when (some #(= :kse (:type %)) (:triggers c))
                (check! (keyword "app" (str "defines-on-kse/" (:name c)))
                        (re-find #"\(defn\s+on-kse(?![-\w*+!?<>=/.'])" body)
                        (str src " は :kse trigger を持つのに on-kse が無い")))
              ;; capability 宣言と実際の host-import 呼び出しの一致。
              (let [calls-kqe? (boolean (re-find #"\(kqe-(assert!|query)\b" body))
                    declares?  (contains? (set (:requires c)) :cap/kqe)]
                (check! (keyword "app" (str "cap-kqe-declared/" (:name c)))
                        (= calls-kqe? declares?)
                        (if calls-kqe?
                          (str src " が kqe-* を呼ぶのに :requires に :cap/kqe が無い")
                          (str src " は kqe-* を呼ばないのに :cap/kqe を要求している")))))))))))

;; ---------------------------------------------------------------- B. ドリフト

(defn verify-claims! []
  (println "\nB. 主張のドリフト（docs/identity-claims.edn に固定した実測との差）")
  (let [claims-s (slurp* "docs/identity-claims.edn")
        doc (try (edn/read-string claims-s) (catch :default _ nil))]
    (check! :claims/parses (map? doc) "docs/identity-claims.edn が読めない")
    (when (map? doc)

      ;; 参照先の実在は network を要らない。
      (doseq [r (:dangling-references doc)]
        (check! (keyword "ref" (str (:from r) "→" (:refers-to r)))
                (= (exists? (:refers-to r)) (boolean (:exists? r)))
                (str "実在が固定値と違う（固定=" (:exists? r)
                     " 実測=" (exists? (:refers-to r)) "）")))

      ;; CLAUDE.md は兄弟 repo の写しなので、そのことを本文が名指ししていること。
      ;; 銘を黙って外すと、deprecated な deploy 手順が現行手順として読まれる。
      (let [cm (slurp* "CLAUDE.md")]
        (doseq [needle (:claude-md-must-mention doc)]
          (check! (keyword "claude-md" (str "mentions/" needle))
                  (and cm (str/includes? cm needle))
                  (str "CLAUDE.md が \"" needle "\" に触れていない"))))

      ;; 固定した resolves-to が、did から機械的に導けるものと一致すること。
      (doseq [c (:claims doc) :when (:did c)]
        (check! (keyword "claim" (str "url-derivation/" (name (:id c))))
                (= (:resolves-to c) (did-web->url (:did c)))
                (str "did:web の解決 URL が固定値と違う: 導出=" (did-web->url (:did c)))))

      (if-not network?
        (println "  skip  network 検査（--network で実行すると did:web を実際に解決する）")
        (doseq [c (:claims doc)]
          (let [url (:resolves-to c)
                want (get-in c [:measured :http])
                got (curl-code url)]
            (check! (keyword "claim" (str "http/" (name (:id c))))
                    (= got want)
                    (str url " が " want " でなく " got
                         (if (and (= want 0) (pos? got))
                           "（解決するようになった — README と identity-claims.edn を更新せよ）"
                           "")))
            ;; 配信されている文書が commit と一致するか、も固定してある claim は照合する。
            (when (contains? (:measured c) :matches-committed-bytes?)
              (let [want-match (boolean (get-in c [:measured :matches-committed-bytes?]))
                    got-match (= (curl-sha256 url) (file-sha256 ".well-known/did.json"))]
                (check! (keyword "claim" (str "bytes/" (name (:id c))))
                        (= got-match want-match)
                        (str "配信文書と commit 済み文書の一致が固定値と違う（固定="
                             want-match " 実測=" got-match "）"))))))))))

;; ---------------------------------------------------------------- main

(println "handotai-actor descriptor verification"
         (if network? "(offline + network)" "(offline のみ)"))
(verify-structure!)
(verify-claims!)

(println (str "\n" @checks " 検査 / " (count @failures) " 失敗"))
(when (seq @failures)
  (println "\n落ちた検査:")
  (doseq [f @failures] (println "  ·" f))
  (js/process.exit 1))
