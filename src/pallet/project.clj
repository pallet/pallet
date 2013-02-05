(ns pallet.project
  "Pallet projects are specified in a pallet.clj file.

The files are loaded by calling clojure.core/load in the pallet.project.load
namespace.  The defproject form in that file def's a `pallet-project-map` var,
which is reset after loading.

defproject refers to pallet.project.loader/defproject."
  (:require
   [clojure.java.io :refer [file resource]]
   [clojure.string :as string]
   [pallet.api :as api :refer [cluster-spec]]))

;;; ## Read a project file
(def default-pallet-file "pallet.clj")

(defn add-default-phases
  "Adds default phases.  Note that these are merged as ordinary clojure maps,
  not as server-specs, so that the project can remove default behaviour."
  [{:keys [phases]} project]
  (assoc project :phases (merge {:bootstrap ()}) ))

(defn read-project
  "Read the project file"
  ([pallet-file]
     (locking read-project
       (require 'pallet.project.load)
       (binding [*ns* (find-ns 'pallet.project.load)]
         (try (load-file pallet-file)
              (catch Exception e
                (throw (ex-info "Error loading project.clj" {} e)))))
       (let [project (resolve 'pallet.project.load/pallet-project-map)]
         (when-not project
           (throw (ex-info "pallet.clj must define project map."
                           {:actual (pr-str project)})))
         ;; Remove the pallet project loading var
         (ns-unmap 'pallet.project.load 'pallet-project-map)
         @project)))
  ([] (read-project default-pallet-file)))

(defn pallet-file-exists?
  "Predicate to check if a pallet.clj file exsists."
  ([pallet-file] (.exists (file pallet-file)))
  ([] (pallet-file-exists? default-pallet-file)))

(defn create-project-file
  [project-name pallet-file]
  (spit pallet-file
        (-> (resource "pallet/default-project-pallet.clj")
            slurp
            (string/replace "{{project-name}}" project-name))))

(defn read-or-create-project
  ([project-name pallet-file]
     (when-not (pallet-file-exists? pallet-file)
       (create-project-file project-name pallet-file))
     (read-project pallet-file))
  ([project-name] (read-or-create-project project-name default-pallet-file)))

;;; ## Provide a default project
(def ^:dynamic ^:internal *project* nil)

(defn use-project!
  "Set the specified project map as the default project."
  [project]
  (alter-var-root #'*project* project))

(defn default-project
  "Return the default project, or nil if there is none."
  []
  *project*)

;;; ## group specs for a project

(defn ensure-group-count [group]
  (merge {:count 1} group))

(defn ensure-node-spec [node-spec group]
  (merge node-spec group))

(defn decorate-name [{:keys [group-prefix group-suffix]
                      :or {group-prefix "" group-suffix ""}
                      :as node-spec} group]
  (update-in group [:group-name] #(str group-prefix % group-suffix)))

(defn spec-from-project
  "Compute the groups for a pallet project using the given compute service
  provider keyword.  The selector defaults to :default."
  ([{:keys [groups provider service] :as pallet-project} provider-kw selector]
     (let [selector (or selector :default)
           node-specs (get-in provider [provider-kw :node-specs])
           node-specs (filter (comp selector :selectors) node-specs)]
       (:groups
        (cluster-spec "" :groups
                      (apply concat
                             (for [node-spec node-specs]
                               (map
                                (comp
                                 #(decorate-name node-spec %)
                                 #(ensure-node-spec node-spec %)
                                 ensure-group-count)
                                groups)))))))
  ([pallet-project provider-kw]
     (spec-from-project pallet-project provider-kw :default)))
