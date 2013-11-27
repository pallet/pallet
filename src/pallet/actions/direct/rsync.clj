(ns pallet.actions.direct.rsync
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :refer [implement-action]]
   [pallet.actions :refer [rsync* rsync-to-local*]]
   [pallet.script.lib :refer [sudo]]
   [pallet.stevedore :as stevedore :refer [fragment]]))

(def ^{:private true}
  cmd "/usr/bin/rsync -e '%s' -F -F %s %s %s@%s:%s")

(def ^{:private true}
  cmd-to-local "/usr/bin/rsync -e '%s' -F -F %s '%s@%s:%s' %s")

(defn default-options
  [action-options]
  {:r true :delete true :copy-links true
   :rsync-path (let [sudo-user (and (not (:no-sudo action-options))
                                    (:sudo-user action-options "root"))]
                 (if sudo-user
                   (fragment ((sudo :no-promt true :user ~sudo-user) "rsync"))
                   "rsync"))
   :owner true
   :perms true})

(defn rsync-command
  [from to username address port options]
  (let [ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if port (format "-p %s" port))) ]
    (format
     cmd ssh
     (stevedore/map-to-arg-string options)
     from username
     address to)))

(implement-action rsync* :direct {:action-type :script :location :origin}
  [action-options from to {:keys [ip username port] :as options}]
  (logging/debugf "rsync %s to %s" from to)
  (let [extra-options (dissoc options :port)
        cmd (rsync-command
             from to
             username
             ip
             port
             (merge (default-options action-options) extra-options))]
    [{:language :bash}
     (stevedore/checked-commands (format "rsync %s to %s" from to) cmd)]))


(implement-action rsync-to-local* :direct
                  {:action-type :script :location :origin}
  [action-options from to {:keys [ip username port] :as options}]
  (logging/debugf "rsync %s to %s" from to)
  (let [extra-options (dissoc options :port)
        ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if port
                   (format "-p %s" port)))
        cmd (format
             cmd-to-local ssh
             (stevedore/map-to-arg-string
              (merge (default-options action-options)
                     {:delete false}
                     extra-options))
             username ip from to)]
    (logging/debugf "rsync-to-local %s" cmd)
    [[{:language :bash}
      (stevedore/checked-commands (format "rsync %s to %s" from to) cmd)]]))
