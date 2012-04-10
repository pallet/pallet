(ns pallet.operate-test
  (:use
   clojure.test
   pallet.operate
   [pallet.operations :only [operation]]))

(defmacro time-body
  "Evaluates body and returns a vector of the expression's result, and the time
  it took in ms."
  {:added "1.0"} [& body]
  `(let [start# (. System (nanoTime))
         ret# (do ~@body)]
     [ret#
      (/ (double (- (. System (nanoTime)) start#)) 1000000.0)]))

(deftest delay-test
  (testing "time-body"
    (let [[r t] (time-body (Thread/sleep 1000))]
      (is (< (- t 1000) 500))))
  (testing "delay-for"
    (let [delay-op (operation delay-op [delay-length]
                     [_ (delay-for delay-length :ms)]
                     _)
          ;; start operation
          [op t] (time-body (let [op (operate delay-op 1000)]
                              (is (instance? pallet.operate.Operation op))
                              (is (not (complete? op)))
                              (is (nil? @op))
                              op))]
      (is (complete? op))
      ;; if this fails, check the volume of debugging info being logged
      (is (< (- t 1000) 500)))))

(deftest timeout-test
  (testing "timeout"
    (let [operation (operation delay-op [delay-length]
                      [_ (timeout
                          (delay-for delay-length :ms)
                          (/ delay-length 2) :ms)]
                      _)
          ;; start operation
          [op t] (time-body (let [op (operate operation 1000)]
                              (is (instance? pallet.operate.Operation op))
                              (is (not (complete? op)))
                              (is (nil? @op))
                              op))]
      (is (not (complete? op)))
      (is (failed? op))
      ;; if this fails, check the volume of debugging info being logged
      (is (< (- t 500) 400)))))
