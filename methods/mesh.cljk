;; mesh.clj — handotai 半導体 KOTOBA Mesh entry component (Clojure / kotoba-clj).
;;
;; The mesh-hosting face of actor:handotai (semiconductor supply KG). Observatory
;; on-kse pattern (ADR-2606230001 §4): observes node→stage production edges as
;; Datom assertions and derives supply-stage concentration via Datalog, routed to
;; RESILIENCE. The full silicon supply analysis stays in the actor's methods.
;;
;; Posture: a resilience / diversification map, never a target-list; disclosed
;; public facts only; aggregate.
;; host-imports: kqe-assert! / kqe-query → kotoba:kais/kqe (needs cap/kqe)
(ns handotai)

(defn observe []
  ;; observe — semiconductor supply stages (node → stage), aggregate.
  (kqe-assert! "handotai" "foundry" "produces" "leading-node")
  (kqe-assert! "handotai" "litho" "produces" "euv-tool")
  (kqe-assert! "handotai" "osat" "produces" "advanced-package")
  ;; derive — supply-stage concentration → resilience priority (Datalog).
  (kqe-query "resilience(?s) :- produces(?s)."))

(defn run [ctx] (observe))
(defn on-kse [topic payload] (observe))
