(ns pallet.script-builder
  "Build scripts with prologues, epilogues, etc, and command lines for
   running them in different environments"
  (:require
   [clojure.tools.logging :refer [debugf]]
   [clojure.string :as string]
   [clojure.string :refer [split]]
   [pallet.script :refer [with-script-context *script-context*]]
   [pallet.script.lib
    :refer [bash env env-var-pairs exit heredoc make-temp-file mkdir rm sudo]]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore :refer [fragment with-source-line-comments]]
   [pallet.stevedore.bash :refer [infix-operators]]))

;; keep slamhound from removing the pallet.stevedore.bash require
infix-operators

(defn prolog []  (str "#!" (fragment (env)) " bash\n"))
(def epilog "\nexit $?")

(defmulti interpreter
  "The interprester used to run a script."
  (fn [{:keys [language]}] language))
(defmethod interpreter :default [_] nil)
(defmethod interpreter :bash [_] (split (fragment (bash)) #" +"))

(defn sudo-cmd-for
  "Construct a sudo command prefix for the specified user."
  [{:keys [no-sudo password sudo-user sudo-password username] :as user}]
  (debugf
   "sudo-cmd-for %s"
   (select-keys user [:no-sudo :password :sudo-user :sudo-password :username]))
  (if (or (and (= username "root") (not sudo-user))
          no-sudo)
    nil
    (str
     (if sudo-password
       (fragment
        (pipe
         (println (str "'" ~sudo-password "'"))
         (sudo :stdin true :user ~sudo-user)))
       (fragment
        (sudo :user ~sudo-user :no-prompt true))))))

(defmulti prefix
  "The executable used to prefix the interpreter (eg. sudo, chroot, etc)."
  (fn [kw session action] kw))
(defmethod prefix :default [_ _ _] nil)
(defmethod prefix :sudo [_ session action]
  (debugf "prefix sudo %s" (into {} (merge (:user session) action)))
  (sudo-cmd-for (merge (:user session) action)))

(defn build-script
  "Builds a script. The script is wrapped in a shell script to set
up the working directory (and possibly environment variables in the
future)."
  [{:keys [language version interpreter] :or {language :bash} :as options}
   script
   {:keys [script-dir script-trace script-hash]
    :or {script-hash true}
    :as action}]
  (str
   (prolog)
   (if script-dir
     (stevedore/script
      (chain-or (~mkdir ~script-dir :path true) (exit 1))
      ("cd" ~script-dir))
     "")
   (if (and (= language :bash) script-trace)
     "set -x\n"
     "")
   (if (and (= language :bash) script-hash)
     "set -h\n"
     "set +h\n")
   (if (= language :bash)
     script
     (let [interpreter (or interpreter
                           (pallet.script-builder/interpreter options))]
       (stevedore/script
        (var t (~make-temp-file "pallet"))
        (~heredoc @t ~script {:literal true})
        ((str ~interpreter) @t)
        (var r @?)
        (rm @t)
        (exit @r))))
   epilog))

(defn build-code
  "Builds a code map, describing the command to execute a script."
  [session {:keys [default-script-prefix script-context script-dir script-env
                   script-prefix sudo-user]
            :as action}
   & args]
  (debugf
   "%s"
   (select-keys action
                [:default-script-prefix :script-dir :script-env :script-prefix
                 :sudo-user :script-context]))
  (debugf
   "prefix kw %s"
   (:script-prefix session (or script-prefix default-script-prefix :sudo)))
  (with-script-context (concat *script-context* script-context)
    (with-source-line-comments false
      {:execv
       (->>
        (concat
         (when-let [prefix (prefix
                            (:script-prefix
                             session
                             (or script-prefix default-script-prefix :sudo))
                            session
                            action)]
           (debugf "prefix %s" prefix)
           (string/split prefix #" "))
         [(fragment (env))]
         (env-var-pairs (merge {:SSH_AUTH_SOCK (fragment @SSH_AUTH_SOCK)}
                               (or script-env (:script-env session))))
         (interpreter {:language :bash})
         args)
        (filter identity))})))
