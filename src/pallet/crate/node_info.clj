(ns pallet.crate.node-info
  "Detection of node information, e.g to determine os and version"
  (:require
   [clojure.string :as string :refer [blank? lower-case]]
   [taoensso.timbre :refer [debugf]]
   [pallet.actions :refer [exec-script]]
   [pallet.kb :refer [packager-for-os]]
   [pallet.middleware :refer [execute-on-filtered]]
   [pallet.node :as node :refer [node-values-schema]]
   [pallet.plan :refer [defplan plan-fn]]
   [pallet.spec :as spec :refer [bootstrapped-meta unbootstrapped-meta]]
   [pallet.session :refer [plan-state target target-session?]]
   [pallet.settings
    :refer [assoc-settings get-settings get-target-settings update-settings]]
   [pallet.stevedore :refer [script]]
   [pallet.utils :refer [maybe-assoc]]
   [schema.core :as schema :refer [check optional-key validate]]))

(def facility :pallet/os)

(defn node-details-map? [x]
  (validate node-values-schema x))

(defn node-info
  "Return the node information in settings for the specified target."
  [session target]
  {:pre [(node/node? target)]
   :post [(or (nil? %) (node-details-map? %))]}
  (let [node-info-map (get-target-settings session target facility)]
    (debugf "node-info node-info-map %s" node-info-map)
    node-info-map))

(defn node-info!
  "Set the node os information map"
  [session node-details]
  {:pre [(or (nil? node-details) (node-details-map? node-details))]}
  (assoc-settings session facility node-details))

(defn node-info-merge!
  "Merge the os-details into the node os information map"
  [session node-details]
  {:pre [(or (nil? node-details) (node-details-map? node-details))]}
  (update-settings session facility merge node-details))

(defn target-with-os
  "Adds any inferred os details to a target"
  [target plan-state]
  (merge (node-info target plan-state) target))

;;; NB no script functions here
(defn os-detection
  "Returns a script, that when executed, should identify the os of a unix
  target."
  []
  (script
   (println "{")
   (println "  :os" (str "'\"'" @("uname" -s) "'\"'"))
   (println "  :rev" (str "'\"'" @("uname" -r) "'\"'"))
   (println "  :mach" (str "'\"'" @("uname" -m) "'\"'"))
   (println "}")))

(defn distro-detection
  "Returns a script, that when executed, should identify distro of a linux
  target."
  []
  (script
   (when (file-exists? "/etc/debconf_version")
     (set! ID @(pipe ("cat" "/etc/redhat-release")
                     ("egrep" -o -e "'^[A-Za-z ]+release'")
                     ("sed -e 's/ release//'")))
     (set! RELEASE @("lsb_release" -s -r)))
   (when (file-exists? "/etc/lsb-release")
     ("source" "/etc/lsb-release")
     (set! ID @DISTRIB_ID)
     (set! RELEASE @DISTRIB_RELEASE))
   (when (file-exists? "/etc/redhat-release")
     (set! ID @(pipe ("cat" "/etc/redhat-release")
                     ("egrep" -o -e "'^[A-Za-z ]+release'")
                     ("sed -e 's/ release//'")))
     (set! RELEASE @(pipe ("cat" "/etc/redhat-release")
                          ("sed" -e "'s/.*release//'")
                          ("sed" -e "'s/[^0-9.]//g'"))))
   (when (file-exists? "/etc/SUSE-release")
     (set! ID @(pipe ("cat" "/etc/SUSE-release")
                     ("tr" "\n" "' '")
                     ("sed" -e "'s/VERSION.*//'")))
     (set! RELEASE @(pipe ("cat" "/etc/SUSE-release")
                          ("tr" "\n" "' '")
                          ("sed" -e "'s/.*= //'"))))
   (when (file-exists? "/etc/mandrake-release")
     (set! ID "Mandrake")
     (set! RELEASE @(pipe ("cat" "/etc/mandrake-release")
                          ("sed" -e "'s/.*release //'")
                          ("sed" -e "'s/ .*//'"))))

   (println "{")
   (println "  :id" (str "'\"'" @ID:-unknown "'\"'"))
   (println "  :release" (str "'\"'" @RELEASE:-unknown "'\"'"))
   (println "}")))

(def pre-map-output #"(?m)[^{}]*\{")

(defplan infer-os
  "Infer the OS family and version from a node"
  [session]
  {:pre [(target-session? session)]}
  (let [os (exec-script session (os-detection))]
    (when (and (number? (:exit os)) (zero? (:exit os)))
      (let [out (string/replace-first (:out os) pre-map-output "{")
            os (read-string out)]
        (->> (-> {}
                 (maybe-assoc :os-family
                              (when-not (blank? (:os os))
                                (keyword (lower-case (:os os)))))
                 (maybe-assoc :os-version
                              (when-not (blank? (:rev os))
                                (lower-case (:rev os))))
                 (maybe-assoc :arch
                              (when-not (blank? (:mach os))
                                (lower-case (:mach os)))))
             (remove #(#{:unknown "unknown"} (val %)))
             (into {}))))))

(defplan infer-distro
  "Infer the linux distribution from a node"
  [session]
  {:pre [(target-session? session)]}
  (let [distro (exec-script session (distro-detection))]
    (when (and (number? (:exit distro)) (zero? (:exit distro)))
      (let [out (string/replace-first (:out distro) pre-map-output "{")
            distro (read-string out)]
        (->> (-> {}
                 (maybe-assoc :os-family
                              (when-not (blank? (:id distro))
                                (keyword (lower-case (:id distro)))))
                 (maybe-assoc :os-version
                              (when-not (blank? (:release distro))
                                (lower-case (:release distro)))))
             (remove #(#{:unknown "unknown"} (val %)))
             (into {}))))))

(defplan os
  "Infer OS and distribution.  Puts a map into the settings' :pallet/os
  facility."
  [session]
  {:pre [(target-session? session)]}
  (let [os (infer-os session)
        distro (infer-distro session)
        m (dissoc (merge os distro) :action-symbol :context)
        m (update-in m [:packager]
                     #(or % (packager-for-os (:os-family m) (:os-version m))))]
    (debugf "os %s %s %s" os distro m)
    (when (plan-state session)
      (node-info! session m))
    m))

(defn server-spec
  "Return a spec with pallet os-detection phases.  When all-targets is
  false, the default, it will only run on targets without a specified
  os-family."
  [{:keys [all-targets] :as settings}]
  (spec/server-spec
   {:phases
    {:pallet/os (vary-meta
                 (plan-fn [session] (os session))
                 merge (if all-targets
                         unbootstrapped-meta
                         (update-in unbootstrapped-meta [:middleware]
                                    #(execute-on-filtered
                                      % (complement node/os-family)))))
     :pallet/os-bs (vary-meta
                    (plan-fn [session] (os session))
                    merge (if all-targets
                            bootstrapped-meta
                            (update-in bootstrapped-meta [:middleware]
                                       #(execute-on-filtered
                                         % (complement node/os-family)))))}}))
