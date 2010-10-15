(ns pallet.resource.file
  "File manipulation."
  (:require
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.resource.file :as file]
   [pallet.script :as script]
   [clojure.string :as string])
  (:use
   [pallet.resource :only [defresource]]
   clojure.contrib.logging))

(script/defscript rm [file & options])
(stevedore/defimpl rm :default [file & options]
  ("rm" ~(stevedore/map-to-arg-string (first options)) ~file))
(stevedore/defimpl rm [#{:darwin}] [file & options]
  ("rm" ~(stevedore/map-to-arg-string
          {:r (:recursive (first options))
           :f (:force (first options))}) ~file))

(script/defscript chown [owner file & options])
(stevedore/defimpl chown :default [owner file & options]
  ("chown" ~(stevedore/map-to-arg-string (first options)) ~owner ~file))

(script/defscript chgrp [group file & options])
(stevedore/defimpl chgrp :default [group file & options]
  ("chgrp" ~(stevedore/map-to-arg-string (first options)) ~group ~file))

(script/defscript chmod [mode file & options])
(stevedore/defimpl chmod :default [mode file & options]
  ("chmod" ~(stevedore/map-to-arg-string (first options)) ~mode ~file))

(script/defscript touch [file & options])
(stevedore/defimpl touch :default [file & options]
  ("touch" ~(stevedore/map-to-arg-string (first options)) ~file))

(script/defscript md5sum [file & {:as options}])
(stevedore/defimpl md5sum :default [file & {:as options}]
  ("md5sum" ~(stevedore/map-to-arg-string options) ~file))
(stevedore/defimpl md5sum [#{:darwin}] [file & {:as options}]
  ("/sbin/md5" -r ~file))

(script/defscript md5sum-verify [file & {:as options}])
(stevedore/defimpl md5sum-verify :default [file & {:as options}]
  ("md5sum" ~(stevedore/map-to-arg-string options) ~file))
(stevedore/defimpl md5sum-verify [#{:darwin}] [file & {:as options}]
  (var testfile @(cut -d "' '" -f 2 ~file))
  (var md5 @(cut -d "' '" -f 1 ~file))
  (test (quoted @("/sbin/md5" -q @testfile)) == (quoted @md5)))

(script/defscript backup-option [])
(stevedore/defimpl backup-option :default []
  "--backup=numbered")
(stevedore/defimpl backup-option [#{:darwin}] []
  "")

(script/defscript sed-file [file expr-map options])

(stevedore/defimpl sed-file :default [file expr-map options]
  ("sed" "-i"
   ~(let [sep (:seperator options "/")]
      (string/join
       " "
       (map
        #(format "-e \"s%s%s%s%s%s\"" sep (first %) sep (second %) sep)
        expr-map)))
   ~file))

(script/defscript download-file [url path])

(stevedore/defimpl download-file :default [url path]
  ("curl" "-o" (quoted ~path) --retry 3 --silent --show-error --fail --location
   (quoted ~url)))

(script/defscript download-request [path request])
(stevedore/defimpl download-request :default [path request]
  ("curl" "-o" (quoted ~path) --retry 3 --silent --show-error --fail --location
   ~(string/join
     " "
     (map (fn dlr-fmt [e] (format "-H \"%s: %s\"" (key e) (val e)))
          (:headers request)))
   (quoted ~(:endpoint request))))

(script/defscript tmp-dir [])
(stevedore/defimpl tmp-dir :default []
  @TMPDIR-/tmp)

(script/defscript make-temp-file [pattern])
(stevedore/defimpl make-temp-file :default [pattern]
  @(mktemp (quoted ~(str pattern "XXXXX"))))

(script/defscript heredoc [path content])
(stevedore/defimpl heredoc :default [path content]
  ("{ cat" ">" ~path ~(str "<<EOFpallet\n" content "\nEOFpallet\n }")))

;; the cat is wrapped in braces so that the final newline is protected
(defn heredoc
  "Generates a heredoc. Options:
      :literal boolean  - if true, prevents shell expansion of contents"
  [path content & options]
  (let [options (apply hash-map options)]
    (stevedore/script ("{ cat" ">" ~path
             ~(str (if (options :literal) "<<'EOFpallet'\n" "<<EOFpallet\n")
                   content "\nEOFpallet\n }")))))

(defn adjust-file [path options]
  (stevedore/chain-commands*
   (filter
    identity
    [(when (:owner options)
       (stevedore/script (chown ~(options :owner) ~path)))
     (when (:group options)
       (stevedore/script (chgrp ~(options :group) ~path)))
     (when (:mode options)
       (stevedore/script (chmod ~(options :mode) ~path)))])))

(defn write-md5-for-file
  "Create a .md5 file for the specified input file"
  [path md5-path]
  (stevedore/script
   ((md5sum ~path) > ~md5-path)))

(defn touch-file [path opts]
  (stevedore/chain-commands
   (stevedore/script
    (touch ~path ~(select-keys opts [:force])))
   (adjust-file path opts)))

(defresource file
  "File management."
  (file*
   [request path & {:keys [action owner group mode force]
                    :or {action :create}
                    :as options}]
   (case action
     :delete (stevedore/checked-script
              (str "delete file " path)
              (rm ~path ~{:force (:force options true)}))
     :create (stevedore/checked-commands
              (str "file " path)
              (touch-file path options))
     :touch (stevedore/checked-commands
             (str "file " path)
             (touch-file path options)))))

(defresource symbolic-link
  "Symbolic link management."
  (symbolic-link*
   [request from name & {:keys [action owner group mode force]
                                        :or {action :create force true}}]
   (case action
     :delete (stevedore/checked-script
              (str "Link %s " name)
              (rm ~name ~{:force force}))
     :create (stevedore/checked-script
              (format "Link %s as %s" from name)
              (ln -s
                  ~(stevedore/map-to-arg-string {:force force})
                  ~from ~name)))))

(defresource fifo
  "FIFO pipe management."
  (fifo*
   [request path & {:keys [action] :or {action :create} :as options}]
   (case action
     :delete (stevedore/checked-script
              (str "fifo " path)
              (rm ~path ~{:force force}))
     :create (stevedore/checked-commands
              (str "fifo " path)
              (stevedore/script
               (if-not (file-exists? ~path)
                 (mkfifo ~path)))
              (adjust-file path options)))))

(defresource sed
  "Execute sed on a file.  Takes a path and a map for expr to replacement."
  (sed*
   [request path exprs-map & {:keys [seperator] :as options}]
   (stevedore/checked-script
    (format "sed file %s" path)
    (sed-file ~path ~exprs-map ~options))))
