(ns pallet.script-builder
  "Build scripts with prologues, epilogues, etc, and command lines for
   running them in different environments"
  (:require
   [clojure.string :as string]
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.bash :as bash])
  (:use
   [pallet.script.lib :only [mkdir make-temp-file heredoc]]))

(def prolog (str "#!/usr/bin/env bash\n"))
(def epilog "\nexit $?")

(defmulti interpreter
  "The interprester used to run a script."
  (fn [{:keys [language]}] language))
(defmethod interpreter :default [_] nil)
(defmethod interpreter :bash [_] "/bin/bash")

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
  (if (or (and (= (:username user) "root") (not (:sudo-user user)))
          (:no-sudo user))
    nil
    (str
     (if-let [pw (:sudo-password user)]
       (str "echo '" (or (:password user) pw) "' | /usr/bin/sudo -S")
       (stevedore/script (~sudo-no-password)))
     (if-let [su (:sudo-user user)]
       (str " -u " su)
       ""))))

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
   {:keys [script-dir] :as action}]
  (str
   prolog
   (if script-dir
     (stevedore/script
      (~mkdir ~script-dir :path true)
      (cd ~script-dir))
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
  [session {:keys [script-prefix script-dir sudo-user] :as action}
   & args]
  {:execv
   (->>
    (concat
     (when-let [prefix (prefix
                        (:script-prefix session (or script-prefix :sudo))
                        session
                        action)]
       (string/split prefix #" "))
     ["/usr/bin/env"]
     (map (fn [[k v]] (format "%s=\"%s\"" k v)) (:script-env session))
     [(interpreter {:language :bash})]
     args)
    (filter identity))})
