(ns pallet.utils
  "Utilities used across pallet."
  (:require
   [clojure.core.typed
    :refer [ann fn> letfn> loop>
            AnyInteger Atom1 Coll Map Nilable NilableNonEmptySeq
            NonEmptySeq NonEmptySeqable Option Seq Seqable Vec]]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.common.deprecate :refer [deprecated]]
   [pallet.core.type-annotations]
   [pallet.core.types
    :refer [assert-not-nil assert-instance Keyword MapDestructure Symbol]])
  (:import
   (java.security MessageDigest NoSuchAlgorithmException)
   (org.apache.commons.codec.binary Base64)
   (clojure.lang APersistentMap ASeq IMapEntry IPersistentMap IPersistentSet
                 IPersistentVector)))

(ann pprint-lines [String -> nil])
(defn pprint-lines
  "Pretty print a multiline string"
  [s]
  (pprint/pprint (seq (.split #"\r?\n" s))))

(ann quoted [Any -> String])
(defn quoted
  "Return the string value of the argument in quotes."
  [s]
  (str "\"" s "\""))

(ann underscore [String -> String])
(defn underscore [^String s]
  "Change - to _"
  (apply str (interpose "_"  (.split s "-"))))

(ann as-string [Any -> String])
(defn as-string
  "Return the string value of the argument."
  [arg]
  (cond
   (symbol? arg) (name arg)
   (keyword? arg) (name arg)
   :else (str arg)))

(ann first-line [String -> String])
(defn first-line
  "Return the first line of a string."
  [s]
  (first (string/split-lines (str s))))

(defmacro apply-map
  [& args]
  `(apply ~@(drop-last args) (apply concat ~(last args))))

;; TODO - Remove :no-check
(ann ^:no-check find-var-with-require
     (Fn
      [Symbol -> Any]
      [Symbol Symbol -> Any]))
(defn find-var-with-require
  "Find the var for the given namespace and symbol. If the namespace does
   not exist, then it will be required.
       (find-var-with-require 'my.ns 'a-symbol)
       (find-var-with-require 'my.ns/a-symbol)

   If the namespace exists, but can not be loaded, and exception is thrown.  If
   the namespace is loaded, but the symbol is not found, then nil is returned."
  ([sym]
     (find-var-with-require
      (symbol (or (namespace sym) (ns-name *ns*)))
      (symbol (name sym))))
  ([ns sym]
     (try
       (when-not (find-ns ns)
         (require ns))
       (catch java.io.FileNotFoundException _)
       (catch Exception e
         ;; require on a bad namespace still instantiates the namespace
         (remove-ns ns)
         (dosync (commute @#'clojure.core/*loaded-libs* disj ns))
         (throw e)))
     (try
       (when-let [v (ns-resolve ns sym)]
         (assert-instance clojure.lang.Var v)
         (var-get v))
       (catch Exception _))))

(defmacro with-temp-file
  "Create a block where `varname` is a temporary `File` containing `content`."
  [[varname content] & body]
  `(let [~varname (java.io.File/createTempFile "stevedore", ".tmp")]
     (io/copy ~content ~varname)
     (let [rv# (do ~@body)]
       (.delete ~varname)
       rv#)))

(ann tmpfile (Fn [-> java.io.File][java.io.File -> java.io.File]))
(defn tmpfile
  "Create a temporary file"
  ([] (java.io.File/createTempFile "pallet_" "tmp"))
  ([^java.io.File dir] (java.io.File/createTempFile "pallet_" "tmp" dir)))

(ann tmpdir [-> java.io.File])
(defn tmpdir
  "Create a temporary directory."
  []
  (doto (java.io.File/createTempFile "pallet_" "tmp")
    (.delete) ; this is a potential cause of non-unique names
    (.mkdir)))

(defmacro with-temporary
  "A block scope allowing multiple bindings to expressions.  Each binding will
   have the member function `delete` called on it."
  [bindings & body] {:pre
   [(vector?  bindings)
         (even? (count bindings))]}
  (cond
   (= (count bindings) 0) `(do ~@body)
   (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                             (try
                              (with-temporary ~(subvec bindings 2) ~@body)
                              (finally
                               (. ~(bindings 0) delete))))
   :else (throw (IllegalArgumentException.
                 "with-temporary only allows Symbols in bindings"))))


(ann map-with-keys-as-symbols
     (All [x]
          [(Map Any x) -> (Map Symbol x)]))
(defn map-with-keys-as-symbols
  "Produce a map that is the same as m, but with all keys are converted to
  symbols."
  [m]
  (letfn> [to-symbol :- [Any -> Symbol]
           (to-symbol [x]
                     (cond
                      (symbol? x) x
                      (string? x) (symbol x)
                      (keyword? x) (symbol (name x))
                      :else (throw
                             (ex-info
                              (str "Couldn't convert " (pr-str x) " to symbol")
                              {:type :pallet/conversion-error
                               :target-type 'Symbol
                               :value x}))))]
    (zipmap (map to-symbol (keys m)) (vals m))))

(ann dissoc-keys (All [k v]
                      (Fn [(Map k v) (NilableNonEmptySeq Any) -> (Map k v)])))
(defn dissoc-keys
  "Like clojure.core/dissoc, except it takes a vector of keys to remove"
  [m keys]
  (apply dissoc m keys))

(ann dissoc-if-empty (All [k v]
                          (Fn [(Map k v) Any -> (Map k v)])))
(defn dissoc-if-empty
  "Like clojure.core/dissoc, except it only dissoc's if the value at the
   keyword is nil."
  [m key]
  (if (empty? (assert-instance clojure.lang.Seqable (m key)))
    (dissoc m key)
    m))

;; TODO remove no-check when core.typed can do update-in
(ann ^:no-check maybe-update-in
     (All [x y z ...]
          [x (NonEmptySeqable Any) [y z ... z -> y] z ... z -> x]))
(defn maybe-update-in
  "'Updates' a value in a nested associative structure, where ks is a
  sequence of keys and f is a function that will take the old value
  and any supplied args and return the new value, and returns a new
  nested structure.  If any levels do not exist, hash-maps will be
  created only if the update function returns a non-nil value. If
  the update function returns nil, the map is returned unmodified."
  [m ks f & args]
  (let [v (apply f (get-in m ks) args)]
    (if v
      (assoc-in m ks v)
      m)))

(ann ^:no-check maybe-assoc
     (All [b c d ...]
       (Fn [(Map b c) b c -> (Map b c)]
           [(Map b c) b c Any * -> (Map b c)])))
(defn maybe-assoc
  "'Assoc a value in an associative structure, where k is a key and v is the
value to assoc. The assoc only occurs if the value is non-nil."
  ([m k v]
     (if (nil? v)
       m
       (assoc m k v)))
  ([m k v & key-vals]
     (let [ret (maybe-assoc m k v)]
       (if key-vals
         (if (next key-vals)
           (recur ret (first key-vals) (second key-vals) (nnext key-vals))
           (throw
            (IllegalArgumentException.
             "maybe-assoc expects even number of arguments after map, found odd number")))
         ret))))

(ann map-seq (All [x] [x -> (Nilable x)]))
(defn map-seq
  "Given an argument, returns the argument, or nil if passed an empty map."
  [m]
  (if (not= {} m) m))

(defmacro pipe
  "Build a session processing pipeline from the specified forms."
  [& forms]
  (let [[middlewares etc] (split-with #(or (seq? %) (symbol? %)) forms)
        middlewares (reverse middlewares)
        [middlewares [x :as etc]]
          (if (seq etc)
            [middlewares etc]
            [(rest middlewares) (list (first middlewares))])
          handler x]
    (if (seq middlewares)
      `(-> ~handler ~@middlewares)
      handler)))

(ann base64-md5 [String -> String])
(defn base64-md5
  "Computes the base64 encoding of the md5 of a string"
  [#^String unsafe-id]
  (let [alg (doto (assert-not-nil (MessageDigest/getInstance "MD5"))
              (.reset)
              (.update (.getBytes unsafe-id)))]
    (try
      (Base64/encodeBase64URLSafeString (.digest alg))
      (catch NoSuchAlgorithmException e
        (throw (new RuntimeException e))))))

(defmacro middleware
  "Build a middleware processing pipeline from the specified forms.
   The result is a middleware."
  [& forms]
  (let [[middlewares] (split-with #(or (seq? %) (symbol? %)) forms)
        middlewares (reverse middlewares)]
    (if (seq middlewares)
      `(fn [handler#] (-> handler# ~@middlewares))
      `(fn [handler#] handler#))))

(defmacro forward-to-script-lib
  "Forward a script to the new script lib"
  [& symbols]
  `(do
     ~@(for [sym symbols]
         (list `def sym (symbol "pallet.script.lib" (name sym))))))

(defmacro fwd-to-configure [name & [as-name & _]]
  `(defn ~name [& args#]
     (require '~'pallet.configure)
     (let [f# (ns-resolve '~'pallet.configure '~(or as-name name))]
       (apply f# args#))))

;; TODO - work out how to allow type variables in loop argument type
(ann ^:no-check compare-and-swap!
     (All [v]
          [(Atom1 v) [v Any * -> v] Any * -> '[v v]]))
(defn compare-and-swap!
  "Compare and swap, returning old and new values"
  [a f & args]
  (loop> [old-val :- v @a]
    (let [new-val (apply f old-val args)]
      (if (compare-and-set! a old-val new-val)
        [old-val new-val]
        (recur @a)))))

(defmacro with-redef
  [[& bindings] & body]
  (if (find-var 'clojure.core/with-redefs)
    `(clojure.core/with-redefs [~@bindings] ~@body)
    `(binding [~@bindings] ~@body)))

(defmacro local-env
  "Return clojure's local environment as a map of keyword value pairs."
  []
  (letfn [(not-gensym? [sym] #(not (.contains (name sym) "__")))]
    (into {}
          (map
           #(vector (keyword (name %)) %)
           (filter not-gensym? (keys &env))))))

(defmacro log-multiline
  "Log a multiline string in multiple log lines"
  [level-kw fmt string]
  `(let [fmt# ~fmt]
     (when (logging/enabled? ~level-kw)
       (doseq [l# (string/split-lines ~string)]
         (logging/log ~level-kw (format fmt# l#))))))

(ann deep-merge [(Map Any Any) * -> (Map Any Any)])
(defn deep-merge
  "Recursively merge maps."
  [& ms]
  (letfn> [f :- [Any Any -> Any]
           (f [a b]
              (if (and (map? a) (map? b))
                (deep-merge a b)
                b))]
    (apply merge-with f ms)))

(ann obfuscate [(Nilable String) -> (Nilable String)])
(defn obfuscate
  "Obfuscate a password, by replacing every character by an asterisk."
  [pw]
  (when pw (string/replace pw #"." "*")))

;; TODO fix the no-check when the brain hurts less
(ann ^:no-check total-order-merge [(NilableNonEmptySeq Keyword) *
                        -> (NilableNonEmptySeq Keyword)])
(defn total-order-merge
  "Merge the `seqs` sequences so that the ordering of the elements in result is
  the same as the ordering of elements present in each of the specified
  sequences.  Throws an exception if no ordering can be found that satisfies the
  ordering in all the `seqs`."
  [& seqs]
  {:pre [(every?
          (fn> [x :- Any] (or (nil? x) (sequential? x)))
          seqs)]}
  (loop> [m-seqs :- (NilableNonEmptySeq (NilableNonEmptySeq Keyword)) seqs
          r :- (Vec Keyword) []]
    (if (seq m-seqs)
      (let [first-elements (map first m-seqs)
            other-elements (set (mapcat rest m-seqs))
            candidates (remove other-elements first-elements)]
        (if (seq candidates)
          (recur
           (->>
            m-seqs
            (map (fn> [x :- (Seq Any)]
                      (if (= (first candidates) (first x))
                        (rest x)
                        x)))
            (filter seq))
           (conj r (first candidates)))
          (throw
           (ex-info (str "No total ordering available: "
                         (vec first-elements) ", "
                         (vec other-elements))
                    {:seqs seqs}))))
      r)))

(ann conj-distinct
     ;; copied from conj
     (All [x y]
          (Fn [(IPersistentVector x) x -> (IPersistentVector x)])))
(defn conj-distinct
  "Conj, returning a vector, removing duplicates in the resulting vector."
  [coll arg]
  (vec (distinct (conj (or coll []) arg))))

(ann ^:no-check map-arg-and-ref
     [(U Symbol MapDestructure) -> '[MapDestructure Symbol]])
(defn map-arg-and-ref
  "Ensure a symbolic argument, arg, can be referred to.
  Returns a tuple with a modifed argument and an argument reference."
  [arg]
  (let [arg (if (and (map? arg) (not (:as arg)))
              (assoc arg :as (gensym "arg"))
              arg)
        arg-ref (if (map? arg) (:as arg) arg)]
    [arg arg-ref]))

(ann safe-id [String -> String])
(defn safe-id
  "Computes a configuration and filesystem safe identifier corresponding to a
  potentially unsafe ID"
  [^String unsafe-id]
  (base64-md5 unsafe-id))

(ann count-by (All [x y] [[x -> y] (Seqable x) -> (Map y AnyInteger)]))
(defn count-by
  "Take a sequence and a key function, and returns a map with the
  count of each key."
  [key-fn s]
  (reduce
   (fn> [cnts :- (Map y AnyInteger)
         e :- x]
     (update-in cnts [(key-fn e)] (fnil inc 0)))
   {} s))

(ann count-values (All [x] [(Seqable x) -> (Map x AnyInteger)]))
(defn count-values
  "Take a sequence, and returns a map with the count of each value."
  [s]
  (reduce
   (fn> [cnts :- (Map x AnyInteger)
         e :- x]
     (update-in cnts [e] (fnil inc 0)))
   {} s))

(ann first-existing-file
  [(U String java.io.File) (Seqable (U String java.io.File))
   -> (Option java.io.File)])
(defn ^java.io.File first-existing-file
  "Return the first file that exists.  Each name in filenames is
  tested under root for existence.  Returns a java.io.File."
  [root filenames]
  (->>
   filenames
   (map  #(io/file root %))
   (filter (fn> [f :- java.io.File] (.exists ^java.io.File f)))
   first))

(ann multi-fn? (predicate clojure.lang.MultiFn))
(defn multi-fn?
  "Predicate for a multi-method."
  [x]
  (instance? clojure.lang.MultiFn x))
