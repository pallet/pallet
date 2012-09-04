(ns pallet.crate.sudoers
  (:require
   [clojure.tools.logging :as logging]
   [clojure.string :as string]
   [pallet.template :as template]
   [pallet.utils :as utils])
  (:use
   [pallet.actions :only [package package-manager]]
   [pallet.crate :only [admin-group def-plan-fn defplan def-collect-plan-fn]]
   [pallet.monad :only [phase-pipeline]]))

;; TODO - add recogintion of +key or key+
;; TODO - add escaping according to man page
;; TODO - dsl for sudoers, eg. (alias "user1" "user2" :as :ADMINS)

(def-plan-fn install
  [& {:keys [package-name action]
      :or {package-name "sudo" action :install}}]
  (package package-name :action action))

(defplan default-specs
  [admin-group admin-group]
  (m-result (array-map
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
  (apply
   str
   (map
    #(write-defaults type (utils/as-string (first %)) (second %))
    (defaults key))))

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
  (apply
   str
   (map #(write-aliases type (name (first %)) (second %)) (aliases key))))

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

(template/deftemplate sudoer-templates
  [aliases-map default-map spec-map]
  {{:path "/etc/sudoers" :owner "root" :mode "0440"}
   (str
    (aliases aliases-map)
    (defaults default-map)
    (specs spec-map))})

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

(defn- sudoer-merge [initial args]
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
    (reduce (fn [v1 v2]
              (map #(merge-fn
                     (merge-with merge-user-spec %1 %2) (keys %1) (keys %2))
                   v1 v2))
            initial args)))

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
    (logging/trace "apply-sudoers")
    (phase-pipeline sudoers {}
      [specs default-specs]
      (template/apply-templates
       sudoer-templates
       (sudoer-merge
        [(array-map) (array-map) specs]
        args)))))
