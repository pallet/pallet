(ns pallet.project.loader
  "Functions and macros required to load a pallet project configuration file."
  (:require
   [pallet.actions :refer [package-manager]]
   [pallet.api :refer [default-phase-meta plan-fn group-spec]]
   [pallet.crate.automated-admin-user :refer [automated-admin-user]]))

;;; Defaults are handled here so we can prevent pallet.project from dependening
;;; statically on pallet.api and the crates.

(def static-project-defaults
  {:source-paths ["pallet/src"]})

(defn add-default-phases
  [{:keys [project-name phases] :as project}]
  (-> project
      (update-in [:phases :bootstrap]
                 #(or % (with-meta
                          (plan-fn
                            (package-manager :update)
                            (automated-admin-user))
                          (:bootstrap default-phase-meta))))))

(defn add-default-group-spec
  [{:keys [project-name phases] :as project}]
  (update-in project [:groups] #(or %
                                    [(group-spec project-name
                                                 :phases phases
                                                 :count 1)])))

(defn add-defaults
  "Add project defaults"
  [project]
  (->>
   project
   (merge static-project-defaults)
   add-default-phases
   add-default-group-spec))

(defn make-project
  [project project-name root]
  (add-defaults (assoc project
                  :project-name (name project-name)
                  :root root)))

(defmacro defproject
  "The pallet.clj file must either def a project map or call this macro."
  [project-name & {:as args}]
  `(let [args# ~args
         root# ~(.getParent (clojure.java.io/file *file*))]
     (def ~'pallet-project-map
       (make-project args# '~project-name root#))))
