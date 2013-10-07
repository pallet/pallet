(ns pallet.core.type-annotations
  "Type annotations on non-pallet namespaces required for pallet."
  (:require
   [clojure.core.typed
    :refer [ann ann-protocol non-nil-return
            Atom1 Coll Map Nilable NilableNonEmptySeq NonEmptySeq
            NonEmptySeqable Seq Seqable Set]]
   [clojure.java.io]
   [clojure.string]
   [clojure.tools.logging])
  (:import
   java.io.File
   java.net.URL
   (clojure.lang Associative IPersistentMap Keyword Named)))

;;; # java
(non-nil-return java.lang.Thread/currentThread :all)
(non-nil-return java.lang.Thread/getContextClassLoader :all)
(non-nil-return java.nio.charset.Charset/defaultCharset :all)
(non-nil-return java.nio.charset.Charset/name :all)
(non-nil-return java.util.Properties/propertyNames :all)
(non-nil-return java.net.URL/getContent :all)
(non-nil-return java.io.File/createTempFile :all)
(non-nil-return java.security.MessageDigest/digest :all)
(non-nil-return org.apache.commons.codec.binary.Base64/encodeBase64URLSafeString
                :all)

;;; # clojure.core
(ann ^:no-check clojure.core/*loaded-libs* (Set clojure.lang.Symbol))

(ann ^:no-check clojure.core/slurp [(U File String URL) -> String])
(ann ^:no-check clojure.core/print-str [Any -> String])
(ann ^:no-check clojure.pprint/pprint [Any -> nil])

(ann ^:no-check clojure.core/commute
     (All [x]
          [clojure.lang.Ref [Any Any * -> x] Any * -> x]))

(ann ^:no-check clojure.core/enumeration-seq
     [java.util.Enumeration -> (Seqable Any)])

(ann ^:no-check clojure.core/var-get
     [clojure.lang.Var -> Any])

(ann ^:no-check clojure.core/assoc-in
     (All [[x :< (Map Any Any)]]
          [x (NonEmptySeqable Any) Any -> x]))
(ann ^:no-check clojure.core/update-in
     (All [x y z ...]
          [x (NonEmptySeqable Any) [y z ... z -> y] z ... z -> x]))
(ann ^:no-check clojure.core/get-in
     (Fn
      [(Map Any Any) (NonEmptySeqable Any) -> Any]
      [(Map Any Any) (NonEmptySeqable Any) Any -> Any]))
(ann ^:no-check clojure.core/select-keys
     (U (All [k v]
          [(Map k v) (Seqable k) -> (Map k v)])
        [nil (Seqable Any) -> (HMap :mandatory {} :complete? true)]))
(ann ^:no-check clojure.core/val
     (All [x]
          [(clojure.lang.IMapEntry Any x) -> x]))

(ann ^:no-check clojure.core/satisfies?
     [Any Any -> boolean])
(ann ^:no-check clojure.core/sequential?
     (predicate clojure.lang.Sequential))

(ann ^:no-check clojure.core/distinct
     (All [x]
          (Fn
           [(NilableNonEmptySeq x) -> (NilableNonEmptySeq x)]
           [(clojure.lang.IPersistentVector x)
            -> (clojure.lang.IPersistentVector x)])))

(ann ^:no-check clojure.core/compare-and-set!
     (All [x] [(Atom1 x) x x -> x]))

(ann ^:no-check clojure.core/ex-info
     (Fn [String (HMap) -> Exception]
         [String (HMap) (Nilable Throwable) -> Exception]))

(ann ^:no-check clojure.core/find-ns
     [clojure.lang.Symbol -> clojure.lang.Namespace])
(ann ^:no-check clojure.core/remove-ns
     [clojure.lang.Symbol -> nil])


(ann ^:no-check clojure.core/sort
     (All [x]
          (Fn [(Seqable x) -> (Coll x)]
              [java.util.Comparator (Seqable x) -> (Coll x)])))
(ann ^:no-check clojure.core/comparator
     (All [x]
          [[x x -> Boolean] -> java.util.Comparator]))

(ann ^:no-check clojure.core/juxt
     (All [x y z]
          [[x -> y] [x -> z] -> [x -> (Vector* y z)]]))

(ann ^:no-check clojure.core/fn?
     (predicate clojure.lang.IFn))


(ann ^:no-check clojure.set/union [Set * -> Set])
(ann ^:no-check clojure.java.io/resource [String -> URL])
(ann ^:no-check clojure.stacktrace/print-cause-trace [Throwable -> nil])

(ann ^:no-check clojure.string/trim [CharSequence -> String])
(ann ^:no-check clojure.string/split-lines
     [CharSequence -> (NonEmptySeqable String)])
(ann ^:no-check clojure.string/replace
     [CharSequence (U String java.util.regex.Pattern) CharSequence -> String])
(ann ^:no-check clojure.string/blank? [CharSequence -> boolean])

;;; # Stevedore
(ann ^:no-check pallet.script/*script-context* Keyword)
(ann ^:no-check pallet.stevedore/*script-language* IPersistentMap)

;;; # pallet commons
(ann ^:no-check pallet.common.context/throw-map [String (Map Any Any)-> nil])

;;; # tools.logging
(ann-protocol
    ^:no-check clojure.tools.logging.impl/Logger
    enabled? [clojure.tools.logging.impl/Logger Keyword -> boolean]
    write! [clojure.tools.logging.impl/Logger Keyword Throwable String -> nil])

(ann-protocol
    ^:no-check clojure.tools.logging.impl/LoggerFactory
    name [clojure.tools.logging.impl/LoggerFactory -> String]
    get-logger [clojure.tools.logging.impl/LoggerFactory Any
                -> clojure.tools.logging.impl/Logger])

(ann ^:no-check clojure.tools.logging/*logger-factory*
     clojure.tools.logging.impl/LoggerFactory)


;; (ann ^:no-check clojure.tools.logging/tracef
;;      (Fn [String Any * -> nil] [Throwable String Any * -> nil]))
;; (ann ^:no-check clojure.tools.logging/debugf
;;      (Fn [String Any * -> nil] [Throwable String Any * -> nil]))
;; (ann ^:no-check clojure.tools.logging/infof
;;      (Fn [String Any * -> nil] [Throwable String Any * -> nil]))
;; (ann ^:no-check clojure.tools.logging/warnf
;;      (Fn [String Any * -> nil] [Throwable String Any * -> nil]))
;; (ann ^:no-check clojure.tools.logging/errorf
;;      (Fn [String Any * -> nil] [Throwable String Any * -> nil]))

;; (ann ^:no-check clojure.tools.logging/trace
;;      (Fn [String -> nil] [Throwable String -> nil]))
;; (ann ^:no-check clojure.tools.logging/debug
;;      (Fn [String -> nil] [Throwable String -> nil]))
;; (ann ^:no-check clojure.tools.logging/info
;;      (Fn [String -> nil] [Throwable String -> nil]))
;; (ann ^:no-check clojure.tools.logging/warn
;;      (Fn [String -> nil] [Throwable String -> nil]))
;; (ann ^:no-check clojure.tools.logging/error
;;      (Fn [String -> nil] [Throwable String -> nil]))

(ann ^:no-check clojure.tools.logging/log*
     [clojure.tools.logging.impl/Logger Keyword (Nilable Throwable)
      (Nilable String) -> nil])

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (ann 1)(All 1))
;; End:
