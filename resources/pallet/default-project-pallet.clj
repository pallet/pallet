;;; Pallet project configuration file

;;; By default, the pallet.api and pallet.crate namespaces are already referred.
;;; The pallet.crate.automated-admin-user/automated-admin-user us also referred.

;;; (require '[your-ns :refer [your-group])

(defproject {{project-name}}
  :provider {:vmfest
             {:node-specs
              [{:image {:os-family :ubuntu :os-version-matches "12.04"
                        :os-64-bit true}
                :image-url ""
                :selectors #{:default}}]}})
