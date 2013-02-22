;;; A pallet configuration file

;;; By default, the pallet.actions, pallet.api and pallet.crate namespaces are
;;; already referred.
;;;
;;; pallet.crate.automated-admin-user/with-automated-admin-user and
;;; pallet.crate.automated-admin-user/automated-admin-user are also referred.

;;; (require '[your-ns :refer [your-group]])

(defproject my-project
  ;; Provider specific configuration
  :provider
  {
   ;; defaults for the aws-ec2 provider
   :aws-ec2 {;; specify a base node-spec
             :node-spec {:image {:image-id "us-east-1/ami-xxxxx"}}}

   ;; defaults for the vmfest provider
   :vmfest {:node-spec {:image {:os-64-bit true}}
            ;; we can specify provider specific phases
            :phases {}
            ;; And each provider can have multiple variants.  Variants will be
            ;; used based on the selectors.  A selector can match multiple
            ;; variants.
            :variants
              [{:node-spec
                {:image {:os-family :ubuntu :os-version-matches "12.04"
                         :os-64-bit true}}
                ;; Each variant can modify the name of the groups that
                ;; will be created when the variant is used.
                :group-prefix "vb"
                :group-suffix "u1204"
                ;; Selectors a re a set of keywords.  A match will occur if the
                ;; selector passed to a task is in the set
                :selectors #{:default}}
               {:node-spec
                {:image {:os-family :ubuntu :os-version-matches "12.04"
                         :os-64-bit true}}
                ;; Each variant can have per group configuration
                :groups {:xx {:node-spec
                              {:os-family :ubuntu :os-version-matches "12.10"
                               :os-64-bit true}}}
                :group-suffix "u1204a"
                :selectors #{:other}}]
              }}

  ;; # General phases

  ;; The default :bootstrap phase will add automated-admin-user.  You can
  ;; override that with an explicit :bootstrap phase here.
  :phases {:configure (plan-fn
                        (comment "call plan functions and actions here"))}


  ;; ## Groups

  ;; By default a group is created with the project name, and the default
  ;; :phases are applied.

  ;; You can specify groups directly, or by reference
  :groups [(group-spec "xx")])
