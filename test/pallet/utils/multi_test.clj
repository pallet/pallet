(ns pallet.utils.multi-test
  (:refer-clojure :exclude [defmulti defmethod])
  (:require
   [clojure.test :refer :all]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   pallet.test-utils ; for thrown-cause-with-msg?
   [pallet.utils.multi :refer :all]))

(defn gen-exclude [exclusions gen]
  (gen/such-that (complement exclusions) gen))

(defn map->dispatch-map
  "Takes a map, and returns a map with identical keys where each value
  is a function that returns the value in the original map."
  [m]
  (assoc (zipmap (keys m)
                 (map constantly (vals m)))
    ::default (constantly ::default-val)))

(def dispatch-key-fn-lookup
  (let [exclusions #{::default ::default-val ::not-found}]
    (prop/for-all [vs (gen/map (gen-exclude exclusions gen/keyword)
                               (gen-exclude exclusions gen/any))
                   kws (gen/vector (gen-exclude exclusions gen/keyword))]
      (let [dispatch-map (map->dispatch-map vs)
            f (dispatch-key-fn identity {:default ::default})]
        (every? #(= (f dispatch-map [%]) (% vs ::default-val))
                (concat kws (keys vs)))))))

(clojure.core/defmulti kk (fn [kw m] kw) :default ::default)
(clojure.core/defmethod kk ::default [kw m] (get m kw ::default-val))

(def dispatch-key-fn-matches-defmulti
  (let [exclusions #{::default ::default-val}]
    (prop/for-all [vs (gen/map (gen-exclude exclusions gen/keyword)
                               (gen-exclude exclusions gen/char))
                   kws (gen/vector (gen-exclude exclusions gen/keyword))]
      (let [dispatch-map (map->dispatch-map vs)
            f (dispatch-key-fn identity {:default ::default})]
        (every? #(= (f dispatch-map [%])
                    (kk % vs))
                (concat kws (keys vs)))))))

(deftest dispatch-key-fn-test
  (testing "dispatch-key-fn"
    (testing "throws on missing dispatch value"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Dispatch failed.*"
           ((dispatch-key-fn identity {}) {} [:x])))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Dispatch failed in fred.*"
           ((dispatch-key-fn identity {:name "fred"}) {} [:x]))))
    (testing "on values in dispatch map, and not in dispatch map"
      (let [result (tc/quick-check 30 dispatch-key-fn-lookup
                                   :max-size 30)]
        (is (nil? (:fail result)))
        (when (instance? Throwable (:result result))
          (clojure.stacktrace/print-cause-trace (:result result))
          (is (not (:result result)) "Unexpected exception"))
        (is (:result result))))
    (testing "dispatches identically to defmulti"
      (let [result (tc/quick-check 30 dispatch-key-fn-matches-defmulti
                                   :max-size 30)]
        (is (nil? (:fail result)))
        (is (:result result))))))

(def dispatch-every-fn-equal-lookup
  (let [exclusions #{::default ::default-val ::not-found}]
    (prop/for-all [vs (gen/map (gen-exclude exclusions gen/keyword)
                               (gen-exclude exclusions gen/any))
                   kws (gen/vector (gen-exclude exclusions gen/keyword))]
      (let [dispatch-map (map->dispatch-map vs)
            f (dispatch-every-fn [(fn [k args] (= [k] args))]
                                 {:default ::default})]
        (every?
         #(= (f dispatch-map [%]) (% vs ::default-val))
         (concat kws (keys vs)))))))

(def dispatch-every-fn-int-string-lookup
  (let [exclusions #{::default ::default-val ::not-found ::int}]
    (prop/for-all [vs (gen/vector
                       (gen/one-of [gen/int gen/string]))]
      (let [dispatch-map {{:type-fn string? :pred #(.contains % "s") :p 1}
                          (constantly :contains-s)
                          {:type-fn string? :pred #(.contains % "t") :p 2}
                          (constantly :contains-t)
                          {:type-fn integer? :pred #(< % 5) :p 1}
                          (constantly :less-than-5)
                          {:type-fn integer? :pred #(< % 20) :p 2}
                          (constantly :less-than-20)
                          ::default (constantly ::default-val)}
            f (dispatch-every-fn
               [(fn [k [arg]]
                  (and (map? k) ((:type-fn k) arg)))
                (fn [k [arg]]
                  (and (map? k) ((:type-fn k) arg) ((:pred k) arg)))]
               {:selector #(first (sort-by (comp :p first) %))})]
        (every?
         #(= (f dispatch-map [%])
             (if (integer? %)
               (cond (< % 5) :less-than-5
                     (< % 20) :less-than-20
                     :else ::default-val)
               (if (string? %)
                 (cond (.contains % "s") :contains-s
                       (.contains % "t") :contains-t
                       :else ::default-val))))
         vs)))))

(deftest dispatch-every-fn-test
  (testing "dispatch-every-fn"
    (testing "throws on missing dispatch value"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Dispatch failed.*"
           ((dispatch-every-fn [(constantly nil)] {}) {} [:x])))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Dispatch failed in fred.*"
           ((dispatch-every-fn [(constantly nil)] {:name "fred"}) {} [:x]))))
    (testing "simulates key-fn based dispatch"
      (let [f (dispatch-every-fn
               [(fn [k args] (= [k] args))]
               {:default ::default})
            m {:k (constantly 1) ::default (constantly 2)}]
        (is (= 1 (f m [:k])))
        (is (= 2 (f m [:l]))))
      (let [result (tc/quick-check 30 dispatch-every-fn-equal-lookup
                                   :max-size 30)]
        (is (nil? (:fail result)))
        (is (:result result))))
    (testing "even integer test"
      (let [result (tc/quick-check 30 dispatch-every-fn-int-string-lookup
                                   :max-size 30)]
        (is (or (nil? (:fail result)) (:result result)))
        (is (:result result))))))

(deftest name-with-attributes-test
  (is (= {:name 'mp
          :dispatch '(fn [session] (:dispatch session))
          :options nil}
         (name-with-attributes 'mp ['(fn [session] (:dispatch session))])))
  (is (= {:name 'mp
          :dispatch '(fn [session] (:dispatch session))
          :options nil}
         (name-with-attributes
          'mp ["mydoc" '(fn [session] (:dispatch session))])))
  (is (= {:name 'mp
          :dispatch '(fn [session] (:dispatch session))
          :options nil}
         (name-with-attributes
          'mp ["mydoc" {:x ::x} '(fn [session] (:dispatch session))])))
  (is (= "mydoc"
         (-> (name-with-attributes
              'mp ["mydoc" '(fn [session] (:dispatch session))])
             :name meta :doc)))
  (is (= ::x
         (-> (name-with-attributes
              'mp ["mydoc" {:x ::x} '(fn [session] (:dispatch session))])
             :name meta :x))))

(deftest multi-fn-test
  (let [f (multi-fn identity)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dispatch failed.*"
                          (f :x)))
    (assoc-method! f :x identity)
    (is (= :x (f :x)))))

(deftest multi-every-fn-test
  (let [f (multi-every-fn
           [(fn [k [arg]] (and (map? k) ((:type-fn k) arg)))
            (fn [k [arg]] (and (map? k) ((:type-fn k) arg) ((:pred k) arg)))]
           {:selector #(first (sort-by (comp :p first) %))})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dispatch failed.*"
                          (f :x)))
    (assoc-method!
     f
     {:type-fn string? :pred #(.contains % "s") :p 1} (constantly :contains-s)
     {:type-fn string? :pred #(.contains % "t") :p 2} (constantly :contains-t)
     {:type-fn integer? :pred #(< % 5) :p 1} (constantly :less-than-5)
     {:type-fn integer? :pred #(< % 20) :p 2} (constantly :less-than-20)
     :default (constantly ::default-val))
    (is (= ::default-val (f :x)))
    (is (= :less-than-5 (f 1)))
    (is (= :contains-s (f "asdf")))))


(defmulti xx (fn [x] #(isa? % x)))

(defmethod xx :x
  [_]
  :a)

(defmethod xx :y
  [_]
  :b)


(deftest defmulti-test
  (let [xxx (gensym "xxx")]
    (is (thrown-cause-with-msg?
         Exception
         (re-pattern (str "Could not find defmulti " (name xxx)))
         (eval `(defmethod ~xxx {} [])))
        "error for defmethod on nonexistent defmulti."))
  (is (= 2 (count @(-> #'xx meta :pallet.utils.multi/dispatch))))
  (is (= :a (xx :x)))
  (is (= :b (xx :y)))
  (is (thrown-cause-with-msg?
       Exception #"Dispatch failed in xx" (xx :z))
      "Error for no matching dispatch."))

(clojure.core/defmulti yy identity)

(clojure.core/defmethod yy :x
  [_]
  :a)

(clojure.core/defmethod yy :y
  [_]
  :b)

(def matches-defmulti
  (let [exclusions #{::default ::default-val}]
    (prop/for-all [arg gen/keyword]
                  (or
                   (= ::thrown
                      (try (xx arg) (catch Exception _ ::thrown))
                      (try (yy arg) (catch Exception _ ::thrown)))
                   (= (xx arg) (yy arg))))))


(deftest defmulti-core-test
  (testing "dispatches identically to defmulti"
    (let [result (tc/quick-check 100 matches-defmulti)]
      (is (nil? (:fail result)))
      (is (:result result)))))
