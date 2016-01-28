(ns pallet.crate.sudoers
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.actions :refer [package remote-file]]
   [pallet.api :as api :refer [plan-fn]]
   [pallet.crate
    :refer [admin-group assoc-settings get-settings def-collect-plan-fn defplan
            phase-context update-settings]]
   [pallet.script.lib :refer [config-root file]]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.template :as template]
   [pallet.utils :as utils]
   [schema.core :as schema
    :refer [either maybe named one optional-key validate]]))

;; TODO - add recogintion of +key or key+
;; TODO - add escaping according to man page
;; TODO - dsl for sudoers, eg. (alias "user1" "user2" :as :ADMINS)

(def facility ::sudoers)

(def Aliases
  {schema/Keyword {schema/Keyword [String]}})

(def DefaultsValue
  (named (either schema/Keyword schema/Bool String) "defaults-value"))

(def Defaults
  {schema/Keyword
   (either {(either String schema/Keyword)
            (either DefaultsValue
                    {schema/Keyword DefaultsValue})})})

(def SpecKey
  (named (either String schema/Keyword) "spec-key"))

(def SpecKeyOrKeys
  (named (either SpecKey [(one SpecKey "spec-key") SpecKey])
         "spec-key-or-keys"))

(def SpecValue
  (named {(named SpecKeyOrKeys "spec-value-key")
          (named (either schema/Keyword String schema/Bool
                         [(either schema/Keyword String schema/Bool)]
                         {schema/Any schema/Any}) "spec-value-value")}
         "spec-value"))

(def Specs
  (named {(named SpecKeyOrKeys "specs-key")
          (named (either SpecValue [SpecValue])
                 "specs-value")}
         "specs"))

(defplan default-specs
  []
  {:post [(validate Specs %)]}
  (let [admin-group (admin-group)]
    (array-map
     "root" {:ALL {:run-as-user :ALL}}
     (str "%" admin-group)
     {:ALL {:run-as-user :ALL}})))

(defn default-settings
  []
  {:sudoers-file (fragment (file (config-root) sudoers))
   :args [[(array-map) (array-map) (default-specs)]]
   :install-strategy :packages
   :packages ["sudo"]})

(def Settings
  {:sudoers-file String
   :install-strategy schema/Keyword
   :args [[(one Aliases "aliases")
           (one Defaults "defaults")
           (one Specs "specs")]]
   (optional-key :packages) [String]
   schema/Keyword schema/Any})

(defplan settings
  [settings & {:keys [instance-id] :as options}]
  (let [settings (merge (default-settings) settings)]
    (logging/debugf "sudoers settings %s %s" instance-id settings)
    (validate Settings settings)
    (assoc-settings facility settings options)))

(defplan install
  [& {:keys [package-name action]
      :or {package-name "sudo" action :install}}]
  (package package-name :action action))

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
  (string/join
   (map
    #(write-defaults type (utils/as-string (first %)) (second %))
    (defaults key))))

(defn- defaults [defaults]
  (str
   (when-let [default (:default defaults)]
     (write-defaults "" "" default))
   (string/join
    (map (partial defaults-for defaults)
         [:run-as-user :user :host] [ \> \: \@]))))

(defn- write-aliases [type name aliased]
  (str type " " (string/upper-case name) " = "
       (string/join (interpose "," (map str aliased)))
       "\n"))

(defn- aliases-for [aliases key type]
  (string/join
   (map #(write-aliases type (name (first %)) (second %)) (aliases key))))

(defn- aliases [aliases]
  (string/join
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

(template/deftemplate sudoer-templates
  [aliases-map default-map spec-map]
  {{:path "/etc/sudoers" :owner "root" :mode "0440"}
   (str
    (aliases aliases-map)
    (defaults default-map)
    (specs spec-map))})

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
                     (mapcat
                      #(vector % (m %))
                      (concat initial-keys additional-keys)))))]
    (reduce (fn [v1 v2]
              (map #(merge-fn
                     (merge-with merge-user-spec %1 %2) (keys %1) (keys %2))
                   v1 v2))
            args)))

(def-collect-plan-fn sudoers
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
  [aliases defaults specs]
  (fn [& args]
    ;; {:pre [(validate
    ;;         [[(schema/one Aliases "aliases")
    ;;           (schema/one Defaults "defaults")
    ;;           (schema/one Specs "specs")]]
    ;;         args)]}
    (logging/debugf "sudoers %s" (pr-str args))

    (validate
     [[(schema/one Aliases "aliases")
       (schema/one Defaults "defaults")
       (schema/one Specs "specs")]]
     args)
    ;; (validate Aliases aliases)
    ;; (validate Defaults defaults)
    ;; (validate Specs specs)
    (logging/trace "apply-sudoers")
    (phase-context sudoers {}
      (let [specs (default-specs)]
        (template/apply-templates
         sudoer-templates
         (sudoer-merge
          (concat [[(array-map) (array-map) specs]]
                  args)))))))

(defn sudoer
  "Add sudo configuration.
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
  [aliases defaults specs {:keys [instance-id] :as options}]
  ;; {:pre [(validate Aliases aliases)
  ;;        (validate Defaults defaults)
  ;;        (validate Specs specs)]}
  (logging/debugf "sudoer %s %s %s"
                  (pr-str aliases) (pr-str defaults) (pr-str specs))
  (logging/debugf "sudoer %s" (get-settings facility options))
  (validate Aliases aliases)
  (validate Defaults defaults)
  (validate Specs specs)
  (update-settings
   facility options update-in [:args]
   (fn [c] (vec (conj c [aliases defaults specs])))))

(defplan configure
  "Install the sudoers configuration based on settings"
  [{:keys [instance-id] :as options}]
  (let [{:keys [sudoers-file args] :as settings}
        (get-settings facility options)]
    (logging/debugf "Sudoers configure %s" (pr-str settings))
    (assert settings "No sudoers settings")
    (assert sudoers-file
            (str "No sudoers-file in settings for sudoers: "
                 settings))
    (remote-file
     sudoers-file
     :mode "0440"
     :owner "root"
     :group "root"
     :content (sudoers-config (sudoer-merge args)))))

(defn server-spec
  "Returns a server-spec that installs sudoers in the configure phase."
  [{:keys [] :as settings} & {:keys [instance-id] :as options}]
  (api/server-spec
   {:phases {:settings (plan-fn
                        (pallet.crate.sudoers/settings settings))
             :install (plan-fn
                       (utils/apply-map install options))
             :configure (plan-fn
                         (configure options))}}))
