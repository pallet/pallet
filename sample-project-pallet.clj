;;; A pallet configuration file

;;; By default, the pallet.api and pallet.crate namespaces are already referred.
;;; The pallet.crate.automated-admin-user/automated-admin-user us also referred.

;;; (require '[your-ns :refer [your-group])

(defproject bricklayer
  ;; Provide provider specific configuration
  :provider {:vmfest
             {:node-specs
              [{:image {:os-family :ubuntu :os-version-matches "12.04"
                        :os-64-bit true}
                :group-prefix ""
                :group-suffix "u1204"
                :selectors #{:default}}]
              :phases {}}}
  ;; Provide service specific configurat
  :services {:vb4 {:phases {}}}

  ;; # General phases

  ;; The default :bootstrap phase will add automated-admin-user.  You can
  ;; override that with an explicit :bootstrap phase here.
  :phases {:configure (plan-fn
                        (comment "call plan functions and actions here"))}


  ;; ## Groups

  ;; By default a group is created with the project name, and the default
  ;; :phases are applied.

  ;; You can specify groups directly, or by reference
  :groups [(group-spec "xx")]

  ;; add directories to the classpath
  ;; by default the pallet directory is added.
  :source-paths []
  )
