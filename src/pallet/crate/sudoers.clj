(ns pallet.crate.sudoers
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.actions :refer [package remote-file]]
   [pallet.core.plan-state]
   [pallet.crate-install :as crate-install]
   [pallet.group :as group]
   [pallet.plan :refer [defplan plan-fn]]
   [pallet.script.lib :refer [file config-root]]
   [pallet.settings :refer [assoc-settings get-settings update-settings]]
   [pallet.session :refer [target-session?]]
   [pallet.spec :as spec]
   [pallet.stevedore :refer [fragment]]
   [pallet.target :as target :refer [admin-group]]
   [pallet.utils :as utils :refer [conj-distinct]]))

;; TODO - add recogintion of +key or key+
;; TODO - add escaping according to man page
;; TODO - dsl for sudoers, eg. (alias "user1" "user2" :as :ADMINS)

(def facility ::sudoers)

(defn default-specs
  [session]
  {:pre [(target-session? session)]}
  (let [admin-group (admin-group session)]
    (array-map
     "root" {:ALL {:run-as-user :ALL}}
     (str "%" admin-group)
     {:ALL {:run-as-user :ALL}})))

(defn default-settings
  [session]
  {:pre [(target-session? session)]}
  {:sudoers-file (fragment (file (config-root) sudoers))
   :args [[(array-map) (array-map) (default-specs session)]]
   :install-strategy :packages
   :packages ["sudo"]})

(defplan settings
  [session settings & {:keys [instance-id] :as options}]
  {:pre [(target-session? session)]}
  (let [settings (merge (default-settings session) settings)]
    (logging/debugf "sudoers settings %s %s" instance-id settings)
    (assoc-settings session facility settings options)))

(defplan install
  [session {:keys [instance-id]}]
  (crate-install/install session facility instance-id))

(defplan default-specs
  [session]
  {:pre [(target-session? session)]}
  (let [admin-group (admin-group session)]
    (array-map
     "root" {:ALL {:run-as-user :ALL}}
     (str "%" admin-group)
     {:ALL {:run-as-user :ALL}})))

(defn- param-string [[key value]]
  (cond
   (instance? Boolean value) (str
                              (if-not value "!") (utils/underscore (name key)))
   (instance? String value) (str (utils/underscore (name key)) \= value)
   (keyword? value) (str
                     (utils/underscore (name key))
                     \=
                     (utils/underscore (name value)))
   :else (str (utils/underscore (name key)) \= value)))

(defn- write-defaults [type name defaults]
  (str "Defaults" type name " "
       (string/join  "," (map param-string defaults))
       "\n"))

(defn- defaults-for [defaults key type]
  (if defaults
    (apply
     str
     (map
      #(write-defaults type (utils/as-string (first %)) (second %))
      (defaults key)))))

(defn- defaults [defaults]
  (str
   (when-let [default (:default defaults)]
     (write-defaults "" "" default))
   (apply str
          (map (partial defaults-for defaults)
               [:run-as-user :user :host] [ \> \: \@]))))

(defn- write-aliases [type name aliased]
  (str type " " (string/upper-case name) " = "
       (apply str (interpose "," (map str aliased)))
       "\n"))

(defn- aliases-for [aliases key type]
  (if aliases
    (apply
     str
     (map #(write-aliases type (name (first %)) (second %)) (aliases key)))))

(defn- aliases [aliases]
  (apply str
          (map (partial aliases-for aliases)
               [:user :run-as-user :host :cmnd]
               ["User_Alias" "Runas_Alias" "Host_Alias" "Cmnd_Alias"])))

(defn- as-tag [item]
  (str (utils/as-string item) ":"))

(defn- tag-or-vector [item]
  (if (vector? item)
    (string/join " " (map as-tag item))
    (as-tag item)))

(defn- item-or-vector [item]
  (if (vector? item)
    (string/join "," (map utils/as-string item))
    (utils/as-string item)))

(defn- write-cmd-spec [[cmds options]]
  (str (when (:run-as-user options)
         (str "(" (item-or-vector (:run-as-user options))  ") "))
       (when (:tags options)
         (str (tag-or-vector (:tags options)) " "))
       (item-or-vector cmds)))

(defn- write-host-spec [host-spec]
  (logging/trace (str "write-host-spec" host-spec))
  (str
   (item-or-vector (or (:host host-spec) :ALL)) " = "
   (string/join "," (map write-cmd-spec (dissoc host-spec :host)))))

(defn- write-spec [[user-spec host-spec]]
  (str (item-or-vector user-spec) " "
       (if (vector? host-spec)
         (string/join " : " (map write-host-spec host-spec))
         (write-host-spec host-spec))))

(defn- specs [spec-map]
  (string/join "\n" (map write-spec spec-map)))

(defn sudoers-config
  [[aliases-map default-map spec-map]]
  (str
   (aliases aliases-map)
   (defaults default-map)
   (specs spec-map)))

(defn- merge-user-spec [m1 m2]
  (cond
   (and (map? m1) (map? m2) (and (= (:host m1) (:host m2))))
   (merge m2 m1)
   (and (vector? m1) (vector? m2))
   (apply vector (concat m1 m2))
   :else
   (throw
    (IllegalArgumentException.
     "do not know how to merge mixed style user specs"))))

(defn- sudoer-merge [args]
  (letfn [(merge-fn [m initial-keys args-keys]
                    (let [additional-keys
                          (filter
                           #(not-any? (fn [x] (= x %)) initial-keys) args-keys)]
                      (apply array-map
                       (apply
                        concat
                        (map
                         #(vector % (m %))
                         (concat initial-keys additional-keys))))))]
    (if (seq args)
      (reduce (fn [v1 v2]
                (map (fn sudoer-merge-reduce
                       [a b]
                       (merge-fn
                        (merge-with merge-user-spec a b) (keys a) (keys b)))
                     v1 v2))
              args))))

(defn sudoers
  "Sudo configuration. Generates a sudoers file.
By default, root and an admin group are already present.

Examples of the arguments are:

aliases { :user { :ADMINS [ \"user1\" \"user2\" ] }
          :host { :TRUSTED [ \"host1\" ] }
          :run-as-user { :OP [ \"root\" \"sysop\" ] }
          :cmnd { :KILL [ \"kill\" ]
                  :SHELLS [ \"/usr/bin/sh\" \"/usr/bin/csh\" \"/usr/bin/ksh\"]}}
default-map { :default { :fqdn true }
              :host { \"host\" { :lecture false } }
              :user { \"user\" { :lecture false } }
              :run-as-user { \"sysop\" { :lecture false } } }
specs [ { [\"user1\" \"user2\"]
          { :host :TRUSTED
            :KILL { :run-as-user \"operator\" :tags :NOPASSWORD }
            [\"/usr/bin/*\" \"/usr/local/bin/*\"]
            { :run-as-user \"root\" :tags [:NOEXEC :NOPASSWORD} }"
  [session aliases defaults specs & {:keys [instance-id] :as options}]
  (logging/debugf "sudoers %s" (get-settings session facility options))
  (update-settings
   session
   facility options update-in [:args] conj-distinct [aliases defaults specs]))

(defplan configure
  "Install the sudoers configuration based on settings"
  [session {:keys [instance-id] :as options}]
  (let [{:keys [sudoers-file args] :as settings}
        (get-settings session facility options)]
    (logging/debugf "Sudoers configure %s" (pr-str settings))
    (assert settings "No sudoers settings")
    (assert sudoers-file
            (str "No sudoers-file in settings for sudoers: "
                 settings))
    (remote-file
     session
     sudoers-file
     :mode "0440"
     :owner "root"
     :group "root"
     :content (sudoers-config (sudoer-merge args)))))

(defn server-spec
  "Returns a server-spec that installs sudoers in the configure phase."
  [{:keys [] :as settings} & {:keys [instance-id] :as options}]
  (spec/server-spec
   :phases {:settings (plan-fn [session]
                        (pallet.crate.sudoers/settings session settings))
            :install (plan-fn [session]
                       (install session options))
            :configure (plan-fn [session]
                         (configure session options))}))

;; (defn bootstrap-spec
;;   "Returns a server-spec that installs sudoers in the bootstrap phase."
;;   [{:keys [] :as settings} & {:keys [instance-id] :as options}]
;;     (api/server-spec
;;    :phases {:settings (plan-fn (pallet.crate.sudoers/settings settings))
;;             :bootstrap (plan-fn (configure options))}))
