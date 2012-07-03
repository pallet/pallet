(ns pallet.script-exec
  "Script execution for pallet.
   Abstracts over transport, script environment, and the script itself")



;;; Transfer

;; A transport needs to be able to transfer files


;;; Script Environment

;; Run under given user (maybe with authentication details.
;; Run with a prefix command (e.g. use a chroot)
;; Run in a given directory
;; Is interpreter specific


    :transport {:protocol [:ssh]
                :user {:username, :password, :private-key, :public-key}}

(defn exec
  "Options
      :environment {:exec-prefix [:sudo]
                    :user
                    :directory
                    :env }
    :script    {:interpreter [:bash], :text}"
  [{:as options}]

  )
