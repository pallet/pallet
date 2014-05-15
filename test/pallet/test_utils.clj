(ns pallet.test-utils
  (:require
   [clojure.java.io :as io]
   [clojure.stacktrace :refer [root-cause]]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [taoensso.timbre :refer [debugf]]
   [pallet.action :refer [declare-action implement-action]]
   [pallet.actions.direct.execute :as execute]
   [pallet.actions.impl :as actions-impl]
   [pallet.common.deprecate :as deprecate]
   [pallet.compute.node-list :as node-list]
   [pallet.core.executor :refer [node-state]]
   [pallet.core.nodes :refer [localhost]]
   [pallet.group :refer [group-spec]]
   [pallet.kb :refer [packager-for-os]]
   [pallet.node :as node]
   [pallet.script :as script]
   [pallet.session :as session]
   [pallet.spec :refer [server-spec]]
   [pallet.stevedore :as stevedore]
   [pallet.user :refer [*admin-user*]]
   [pallet.utils :refer [apply-map]]))

(defmacro with-private-vars [[ns fns] & tests]
  "Refers private fns from ns and runs tests in context.  From users mailing
list, Alan Dipert and MeikelBrandmeyer."
  `(let ~(reduce #(conj %1 %2 `@(ns-resolve '~ns '~%2)) [] fns)
     ~@tests))

(def dev-null
  (proxy [java.io.OutputStream] []
    (write ([i-or-bytes])
           ([bytes offset len]))))

(defmacro suppress-output
  "Prevent stdout to reduce test log noise"
  [& forms]
  `(binding [*out* (io/writer dev-null)]
    ~@forms))

(defmacro suppress-err
  "Prevent stdout to reduce test log noise"
  [& forms]
  `(binding [*err* (io/writer dev-null)]
    ~@forms))

(def null-print-stream
  (java.io.PrintStream. dev-null))

(defn no-output-fixture
  "A fixture for no output from tests"
  [f]
  (let [out# System/out]
    (System/setOut null-print-stream)
    (try
      (f)
      (finally (System/setOut out#)))))

(defmacro with-location-info
  "A scope for enabling or disabling location info"
  [b & body]
  `(binding [actions-impl/*script-location-info* ~b]
     ~@body))

(def no-location-info
  (fn [f] (with-location-info false (f))))

(def no-source-line-comments
  (fn [f] (stevedore/with-source-line-comments false (f))))

(defn test-username
  "Function to get test username. This is a function to avoid issues with AOT."
  [] (or (. System getProperty "ssh.username")
         (. System getProperty "user.name")))

(defn test-unprivileged-username
  "Function to get test username. This is a function to avoid issues with AOT."
  [] (or (. System getProperty "unprivileged.username")
         "unpriv-user"))

(def ubuntu-session {:server {:image {:os-family :ubuntu}}})
(def centos-session {:server {:image {:os-family :centos}}})

(defn with-ubuntu-script-template
  [f]
  "A test fixture for selection ubuntu as the script context"
  (script/with-script-context [:ubuntu]
    (f)))

(defn with-centos-script-template
  [f]
  "A test fixture for selection ubuntu as the script context"
  (script/with-script-context [:centos]
    (f)))

(defn with-bash-script-language
  [f]
  "A test fixture for selection bash as the output language"
  (stevedore/with-script-language :pallet.stevedore.bash/bash
    (f)))

(defn with-no-source-line-comments
  [f]
  "A test fixture to remove source line comments"
  (stevedore/with-source-line-comments nil
    (f)))

(defn make-node
  "Simple node for testing"
  [node-name {:as options}]
  {:pre [node-name]
   :post [(node/validate-node %)]}
  (as->
   (merge
    {:hostname (str (name (:group-name options node-name)) "-0")
     :id (name node-name)
     :primary-ip (:ip options "1.2.3.4")
     :os-family (:os-family options :ubuntu)}
    (dissoc options :group-name :ip :os-family))
   node
   (-> (update-in node [:packager]
                  #(or % (packager-for-os
                          (:os-family node)
                          (:os-version node)))))))

(defn make-localhost-node
  "Simple localhost node for testing"
  [{:as options}]
  (localhost options))

(defn make-localhost-compute
  [& {:as options}]
  (node-list/node-list
   {:node-list [(localhost options)]}))

(defmacro build-resources
  "Forwarding definition"
  [& args]
  `(do
     (require 'pallet.build-actions)
     (deprecate/deprecated-macro
      ~&form
      (deprecate/rename
       'pallet.test-utils/build-resources
       'pallet.build-actions/build-actions))
     ((resolve 'pallet.build-actions/build-actions) ~@args)))

(defn target-server
  "Build the target server for the session map"
  [& {:as options}]
  {:server (server-spec options)})

(defn group
  "Build a group for the session map"
  [name & {:as options}]
  (group-spec name options))

(defn target-group
  "Build the target group for the session map"
  [name & {:as options}]
  {:group (group-spec name options)})

(defmacro redef
  [ [& bindings] & body ]
  (if (find-var 'clojure.core/with-redefs)
    `(with-redefs [~@bindings] ~@body)
    `(binding [~@bindings] ~@body)))

(defmacro script-action
  "Creates a script action with a :direct implementation."
  {:indent 1}
  [args & impl]
  (let [action-sym (gensym "script-action")]
    `(let [action# (declare-action '~action-sym {})]
       (implement-action action# :direct
         {:action-type :script :location :target}
         ~args
         ~@impl)
       action#)))

(defn target-node-state
  [session key]
  (get (node-state (session/executor session)
                   (session/target session))
       key))

;; (defn verify-flag-set
;;   "Verify that the specified flag is set for the current target."
;;   [flag]
;;   (when-not (target-flag? (session) flag)
;;     (throw-map
;;      (format "Verification that flag %s was set failed" flag)
;;      {:flag flag})))

;; (defn verify-flag-not-set
;;   "Verify that the specified flag is not set for the current target."
;;   [flag]
;;   (when (target-flag? (session) flag)
;;     (throw-map
;;      (format "Verification that flag %s was not set failed" flag)
;;      {:flag flag})))

(defn bash
  "Create a bash literal string as returned by an action function"
  [& bash-strings]
  [{:language :bash} (apply str bash-strings)])

(defn remove-source-line-comments [script]
  {:pre [(string? script)]}
  (-> script
      (string/replace #"(?sm)^( *# [\w\d_]+.clj:\d+)+\n" "")
      (string/replace #"\s+# [\w\d_]+.clj:\d+" "")
      (string/replace #" \([\w\d_]+.clj:\d+\)\.\.\." "...")
      (string/replace #" \([\w\d_]+.clj:\d+\) : " " : ")
      (string/replace #"\\\n" "")
      (string/trim)))

;;; A test method that strips location and source comments
(defmethod assert-expr 'script-no-comment= [msg form]
  (let [[_ expected expr] form]
    `(let [expected# ~expected
           actual# ~expr
           expected-norm# (remove-source-line-comments expected#)
           actual-norm# (remove-source-line-comments actual#)]
       (if (= expected-norm# actual-norm#)
         (do-report
          {:type :pass :message ~msg :expected expected# :actual actual#})
         (do-report
          {:type :fail :message ~msg
           :expected (list '= [expected# expected-norm#] [actual# actual-norm#])
           :actual (list 'not (list '= [expected# expected-norm#]
                                       [actual# actual-norm#]))})))))


(defmethod assert-expr 'thrown-cause? [msg form]
  ;; (is (thrown? c expr))
  ;; Asserts that evaluating expr throws an exception with a cause of class c.
  ;; Returns the exception thrown.
  (let [klass (second form)
        body (nthnext form 2)]
    `(try ~@body
          (do-report {:type :fail, :message ~msg,
                   :expected '~form, :actual nil})
          (catch Throwable e#
            (if (instance? ~klass (root-cause e#))
              (do
                (do-report {:type :pass, :message ~msg,
                            :expected '~form, :actual e#})
                (root-cause e#))
              (throw (root-cause e#)))))))

(defmethod assert-expr 'thrown-cause-with-msg? [msg form]
  ;; (is (thrown-with-msg? c re expr))
  ;; Asserts that evaluating expr throws an exception with a cause of class c.
  ;; Also asserts that the message string of the exception matches
  ;; (with re-find) the regular expression re.
  (let [klass (nth form 1)
        re (nth form 2)
        body (nthnext form 3)]
    `(try ~@body
          (do-report
           {:type :fail, :message ~msg, :expected '~form, :actual nil})
          (catch Throwable e#
            (debugf "thrown-cause-with-msg? %s %s" e# (root-cause e#))
            (let [m# (.getMessage (root-cause e#))]
              (if (instance? ~klass e#)
                (do (if (and m# (re-find ~re m#))
                      (do-report {:type :pass, :message ~msg,
                                  :expected '~form, :actual (root-cause e#)})
                      (do-report {:type :fail, :message ~msg,
                                  :expected '~form, :actual (root-cause e#)}))
                    (root-cause e#))
                (throw (root-cause e#))))))))
