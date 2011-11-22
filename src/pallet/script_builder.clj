(ns pallet.script-builder
  "Build scripts with prologues, epilogues, etc, and command lines for
   running them in different environments"
  (:require
   [clojure.string :as string]
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.bash :as bash]))


(defmulti prolog
  "A prologue adds script environment, etc, at the start of a script."
  (fn [action] (:action-type action)))
(defmethod prolog :default [action])
(defmethod prolog :script/bash
  [action]
  (str "#!/usr/bin/env bash\n" bash/hashlib))

(defmulti epilog
  "An epilogue adds content at the end of a script."
  (fn [action] (:action-type action)))
(defmethod epilog :default [action])
(defmethod epilog :script/bash
  [action]
  "\nexit $?")

(defmulti interpreter
  "The interprester used to run a script."
  (fn [{:keys [action-type]}] action-type))
(defmethod interpreter :default [_] nil)
(defmethod interpreter :script/bash [_] "/bin/bash")

(script/defscript sudo-no-password [])
(script/defimpl sudo-no-password :default []
  ("/usr/bin/sudo" -n))
(script/defimpl sudo-no-password
  [#{:centos-5.3 :os-x :darwin :debian :fedora}]
  []
  ("/usr/bin/sudo"))

(defn sudo-cmd-for
  "Construct a sudo command prefix for the specified user."
  [user]
  (if (or (= (:username user) "root") (:no-sudo user))
    "/bin/bash"
    (if-let [pw (:sudo-password user)]
      (str "echo \"" (or (:password user) pw) "\" | /usr/bin/sudo -S")
      (stevedore/script (~sudo-no-password)))))

(defmulti prefix
  "The executable used to prefix the interpreter (eg. sudo, chroot, etc)."
  (fn [kw env] kw))
(defmethod prefix :default [_ _] nil)
(defmethod prefix :sudo [_ env]
  (sudo-cmd-for (:user env)))

(defn build-script
  "Builds a script with a prologue"
  [script {:keys [action-type] :as action}]
  (str (prolog action) script (epilog action)))

(defn build-code
  "Builds a code map, describing the command to execute a script."
  [session action & args]
  ;; {:in
  ;;  (->>
  ;;   (concat
  ;;    [(prefix (:script-prefix session :sudo) (:script-env session))
  ;;     "/usr/bin/env"]
  ;;    (map (fn [[k v]] (format "%s=\"%s\"" k v)) (:script-env session))
  ;;    [(interpreter action)]
  ;;    args)
  ;;   (filter identity)
  ;;   (interpose " ")
  ;;   (apply str))}
  {:execv
   (->>
    (concat
     (string/split
      (prefix (:script-prefix session :sudo) (:script-env session)) #" ")
     ["/usr/bin/env"]
     (map (fn [[k v]] (format "%s=\"%s\"" k v)) (:script-env session))
     [(interpreter action)]
     args)
    (filter identity))})
