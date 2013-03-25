(ns pallet.script-builder
  "Build scripts with prologues, epilogues, etc, and command lines for
   running them in different environments"
  (:require
   [clojure.string :as string :refer [split]]
   [pallet.script :as script]
   [pallet.stevedore :as stevedore :refer [fragment with-source-line-comments]]
   [pallet.stevedore.bash :as bash])
  (:use
   [pallet.script.lib
    :only [bash env env-var-pairs exit heredoc make-temp-file mkdir rm sudo]]))

(defn prolog []  (str "#!" (fragment (env)) "bash\n"))
(def epilog "\nexit $?")

(defmulti interpreter
  "The interprester used to run a script."
  (fn [{:keys [language]}] language))
(defmethod interpreter :default [_] nil)
(defmethod interpreter :bash [_] (split (fragment (bash)) #" +"))

(defn sudo-cmd-for
  "Construct a sudo command prefix for the specified user."
  [{:keys [no-sudo password sudo-user sudo-password username] :as user}]
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
  (sudo-cmd-for (merge (:user session) action)))

(defn build-script
  "Builds a script. The script is wrapped in a shell script to set
up the working directory (and possibly environment variables in the
future)."
  [{:keys [language version interpreter] :or {language :bash} :as options}
   script
   {:keys [script-dir script-trace] :as action}]
  (str
   (prolog)
   (if script-dir
     (stevedore/script
      (~mkdir ~script-dir :path true)
      ("cd" ~script-dir))
     "")
   (if (and (= language :bash) script-trace)
     "set -x\n"
     "")
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
  [session {:keys [default-script-prefix script-dir script-env script-prefix
                   sudo-user]
            :as action}
   & args]
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
         (string/split prefix #" "))
       [(fragment (env))]
       (env-var-pairs (or script-env (:script-env session)))
       (interpreter {:language :bash})
       args)
      (filter identity))}))
