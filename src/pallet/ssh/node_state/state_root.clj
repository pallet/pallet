(ns pallet.ssh.node-state.state-root
  "A node-state implementation that maintains node state in a parallel directory
  tree."
  (:require
   [pallet.actions.direct.file :as file]
   [pallet.core.session :refer [admin-user]]
   [pallet.script.lib :as lib
    :refer [canonical-path cat chgrp chmod chown cp diff dirname exit file ls
            md5sum md5sum-verify mkdir path-group path-mode path-owner rm
            tail user-home xargs]]
   [pallet.ssh.node-state :refer [file-backup file-checksum]]
   [pallet.ssh.node-state.protocols :refer [FileBackup FileChecksum]]
   [pallet.stevedore :refer [chain-commands chained-script fragment script]]))

(defn default-state-root
  "Provide a computed default for state-root if it isn't set."
  []
  (fragment (file (lib/state-root) "pallet")))

(defn- adjust-root
  [session ^String script-dir ^String path]
  (if (or (.startsWith path "/")
          (.startsWith path "$")
          (.startsWith path "`"))
    path
    (fragment
     (file ~(or script-dir
                (user-home (:username (admin-user session))))
           ~path))))

(defn state-path*
  "Return a path under state-root for a given path."
  [session state-root script-dir path]
  (str state-root (adjust-root session script-dir path)))

(defn state-path
  "Return a path under state-root for a given path."
  [session state-root path]
  (state-path* session
               (or state-root (default-state-root))
               (fragment @("pwd"))
               path))

(defn md5-path
  "Return a path for the md5 file for state-path under state-root."
  [state-path]
  (str state-path ".md5"))


(defn create-path-with-template
  "Create the /var/lib/pallet directory if required, ensuring correct
permissions. Note this is not the final directory."
  [template-path new-path]
  (script
   (do
     (set! dirpath @(dirname ~new-path))
     (set! templatepath @(dirname @(if (file-exists? ~template-path)
                                     (canonical-path ~template-path)
                                     (println ~template-path))))
     (when (not (directory? @templatepath))
       (println @templatepath ": Directory does not exist.")
       (exit 1))
     (set! templatepath @(canonical-path @templatepath))
     (chain-or (mkdir @dirpath :path true) (exit 1))
     ("while" (!= "/" @templatepath) ";do"
      ~(chained-script
        (set! d @dirpath)               ; copy these and update
        (set! t @templatepath)          ; so we can continue on any failure
        (when (not (directory? @templatepath))
          (println @templatepath ": Directory does not exist.")
          (exit 1))
        (set! dirpath @(dirname @dirpath))
        (set! templatepath @(dirname @templatepath))
        (chain-or (chgrp @(path-group @t) @d) ":")
        (chain-or (chmod @(path-mode @t) @d) ":")
        (chain-or (chown @(path-owner @t) @d) ":"))
      ("; done")))))

(defn verify
  "verify if the files at path and state-path are identical, and
  whether they match the md5-path."
  [path state-path md5-path]
  (chained-script
   ;; check if the file and the current copy are the same
   (var filediff "")
   (if (&& (file-exists? ~path) (file-exists? ~state-path))
     (do
       (diff ~path ~state-path :unified true)
       (set! filediff "$?")))
   ;; check if the current copy and the md5 match
   (var md5diff "")
   (if (&& (file-exists? ~state-path) (file-exists? ~md5-path))
     (do
       (md5sum-verify ~md5-path)
       (set! md5diff "$?")))

   ;; report any errors
   (var errexit 0)
   (if (== @filediff 1)
     (do
       (println
        "Existing file did not match the pallet master copy: FAIL")
       (set! errexit 1)))

   (if (== @md5diff 1)
     (do
       (println "Existing content did not match md5: FAIL")
       (set! errexit 1)))

   ;; exit if error
   (== @errexit 0)))

(defn record
  "Script to record a new (version of a) file in state-root"
  [path state-path {:keys [max-versions no-versioning versioning]
                    :or {max-versions 5
                         versioning :numbered}}]
  (chain-commands
   ;; output the diff between current and new
   (script
    (var contentdiff "")
    (if (&& (file-exists? ~path) (file-exists? ~state-path))
      (do
        (diff ~path ~state-path :unified true)
        (set! contentdiff "$?")))

    ;; install the file if the content is different
    (if (&& (not (== @contentdiff 0)) (file-exists? ~path))
      (cp ~path ~state-path :force ~true :backup ~versioning)))

   ;; cleanup backup copies
   (if (and (not no-versioning) (pos? max-versions))
     (script
      (pipe
       ((ls (str ~state-path ".~[0-9]*~") :sort-by-time ~true)
        "2>" "/dev/null")
       (tail "" :max-lines ~(str "+" (inc max-versions)))
       (xargs (rm "" :force ~true)))))))

(defn record-md5
  "Script to record a file's md5"
  [path md5-path]
   ;; write the md5 file
  (chain-commands
   (file/write-md5-for-file path md5-path)
   (script
    (println "MD5 sum is" @(cat ~md5-path)))))

(defrecord StateRootBackup [state-root]
  FileBackup
  (new-file-content
    [_ session path options]
    ;; create the state-root dir
    (let [state-path (state-path session state-root path)]
      (chain-commands
       (create-path-with-template path state-path)
       (record path state-path options)))))

(defrecord StateRootChecksum [state-root]
  FileChecksum
  (verify-checksum [_ session path]
    (let [state-path (state-path session state-root path)]
      (verify path state-path (md5-path state-path))))

  (record-checksum [_ session path]
    (let [state-path (state-path session state-root path)]
      (create-path-with-template path state-path)
      (record-md5 path (md5-path state-path)))))

(defn state-root-backup
  "Return a state-root backup instance that can keep backups."
  [{:keys [state-root] :as options}]
  (map->StateRootBackup options))

(defn state-root-checksum
  "Return a state-root checksum instance that can verify md5 checksums."
  [{:keys [state-root] :as options}]
  (map->StateRootChecksum options))

(defmethod file-backup :state-root
  [_ options]
  (state-root-backup options))

(defmethod file-checksum :state-root
  [_ options]
  (state-root-checksum options))
