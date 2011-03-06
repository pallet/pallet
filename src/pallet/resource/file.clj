(ns pallet.resource.file
  "File manipulation."
  (:require
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.script :as script-impl]
   [pallet.resource.shell :as shell]
   [pallet.script :as script]
   [clojure.string :as string]
   pallet.resource.script)
  (:use
   [pallet.resource :only [defresource]]
   clojure.contrib.logging))

(script/defscript rm [file & {:keys [recursive force]}])
(script-impl/defimpl rm :default [file & {:keys [recursive force] :as options}]
  ("rm" ~(stevedore/map-to-arg-string options) ~file))
(script-impl/defimpl rm [#{:darwin :os-x}] [file & {:keys [recursive force]}]
  ("rm" ~(stevedore/map-to-arg-string {:r recursive :f force}) ~file))

(script/defscript mv [source destination & {:keys [force backup]}])
(script-impl/defimpl mv :default
  [source destination & {:keys [force backup]}]
  ("mv"
   ~(stevedore/map-to-arg-string
     {:f force :backup (when backup (name backup))}
     :assign true)
   ~source ~destination))
(script-impl/defimpl mv [#{:darwin :os-x}]
  [source destination & {:keys [force backup]}]
  ("mv"
   ~(stevedore/map-to-arg-string
     {:f force}
     :assign true)
   ~source ~destination))

(script/defscript cp [source destination & {:keys [force backup preserve]}])
(script-impl/defimpl cp :default
  [source destination & {:keys [force backup preserve]}]
  ("cp"
   ~(stevedore/map-to-arg-string {:f force
                                  :backup (when backup (name backup))
                                  :p preserve})
   ~source ~destination))
(script-impl/defimpl cp [#{:darwin :os-x}]
  [source destination & {:keys [force backup preserve]}]
  ("cp"
   ~(stevedore/map-to-arg-string {:f force :p preserve})
   ~source ~destination))

(script/defscript ln [source destination & {:keys [force symbolic]}])
(script-impl/defimpl ln :default
  [source destination & {:keys [force symbolic]}]
  ("ln"
   ~(stevedore/map-to-arg-string {:f force :s symbolic})
   ~source ~destination))

(script/defscript backup-option [])
(script-impl/defimpl backup-option :default []
  "--backup=numbered")
(script-impl/defimpl backup-option [#{:darwin :os-x}] []
  "")

(script/defscript basename [path])
(script-impl/defimpl basename :default
  [path]
  ("basename" ~path))

(script/defscript ls [pattern & {:keys [sort-by-time sort-by-size reverse]}])
(script-impl/defimpl ls :default
  [pattern & {:keys [sort-by-time sort-by-size reverse]}]
  ("ls" ~(stevedore/map-to-arg-string
          {:t sort-by-time
           :S sort-by-size
           :r reverse})
   ~pattern))

(script/defscript cat [pattern])
(script-impl/defimpl cat :default
  [pattern]
  ("cat" ~pattern))

(script/defscript tail [pattern & {:keys [max-lines]}])
(script-impl/defimpl tail :default
  [pattern & {:keys [max-lines]}]
  ("tail" ~(stevedore/map-to-arg-string {:n max-lines}) ~pattern))

(script/defscript diff [file1 file2 & {:keys [unified]}])
(script-impl/defimpl diff :default
  [file1 file2 & {:keys [unified]}]
  ("diff" ~(stevedore/map-to-arg-string {:u unified}) ~file1 ~file2))

(script/defscript cut [file & {:keys [fields delimiter]}])
(script-impl/defimpl cut :default
  [file & {:keys [fields delimiter]}]
  ("cut"
   ~(stevedore/map-to-arg-string {:f fields :d delimiter})
   ~file))

(script/defscript chown [owner file & {:as options}])
(script-impl/defimpl chown :default [owner file & {:as options}]
  ("chown" ~(stevedore/map-to-arg-string options) ~owner ~file))

(script/defscript chgrp [group file & {:as options}])
(script-impl/defimpl chgrp :default [group file & {:as options}]
  ("chgrp" ~(stevedore/map-to-arg-string options) ~group ~file))

(script/defscript chmod [mode file & {:as options}])
(script-impl/defimpl chmod :default [mode file & {:as options}]
  ("chmod" ~(stevedore/map-to-arg-string options) ~mode ~file))

(script/defscript touch [file & {:as options}])
(script-impl/defimpl touch :default [file & {:as options}]
  ("touch" ~(stevedore/map-to-arg-string options) ~file))

(script/defscript md5sum [file & {:as options}])
(script-impl/defimpl md5sum :default [file & {:as options}]
  ("md5sum" ~(stevedore/map-to-arg-string options) ~file))
(script-impl/defimpl md5sum [#{:darwin :os-x}] [file & {:as options}]
  ("/sbin/md5" -r ~file))

(script/defscript md5sum-verify [file & {:as options}])
(script-impl/defimpl md5sum-verify :default
  [file & {:keys [quiet check] :or {quiet true check true} :as options}]
  (chain-and
   (cd @(dirname ~file))
   ("md5sum"
    ~(stevedore/map-to-arg-string {:quiet quiet :check check})
    @(basename ~file))
   (cd -)))
(script-impl/defimpl md5sum-verify [#{:centos :debian :amzn-linux :rhel}]
  [file & {:keys [quiet check] :or {quiet true check true} :as options}]
  (chain-and
   (cd @(dirname ~file))
   ("md5sum"
    ~(stevedore/map-to-arg-string {:status quiet :check check})
    @(basename ~file))
   (cd -)))
(script-impl/defimpl md5sum-verify [#{:darwin :os-x}] [file & {:as options}]
  (chain-and
   (var testfile @(cut ~file :delimiter " " :fields 2))
   (var md5 @(cut ~file :delimiter " " :fields 1))
   ("test" (quoted @("/sbin/md5" -q @testfile)) == (quoted @md5))))

(script/defscript backup-option [])
(script-impl/defimpl backup-option :default []
  "--backup=numbered")
(script-impl/defimpl backup-option [#{:darwin :os-x}] []
  "")

(script/defscript sed-file [file expr-map options])

(def ^{:doc "Possible sed separators" :private true}
  sed-separators
  (concat [\/ \_ \| \: \% \! \@] (map char (range 42 127))))

(script-impl/defimpl sed-file :default
  [file expr-map {:keys [seperator restriction] :as options}]
  ("sed" "-i"
   ~(if (map? expr-map)
      (string/join
       " "
       (map
        (fn [[key value]]
          (let [used (fn [c]
                       (or (>= (.indexOf key (int c)) 0)
                           (>= (.indexOf value (int c)) 0)))
                seperator (or seperator (first (remove used sed-separators)))]
            (format
             "-e \"%ss%s%s%s%s%s\""
             (if restriction (str restriction " ") "")
             seperator key seperator value seperator)))
        expr-map))
      (format "-e \"%s%s\"" (when restriction (str restriction " ")) expr-map))
   ~file))

(script/defscript download-file [url path & {:keys [proxy]}])

(script-impl/defimpl download-file :default [url path & {:keys [proxy]}]
  (if ("test" @(shell/which curl))
    ("curl" "-o" (quoted ~path)
     --retry 5 --silent --show-error --fail --location
     ~(if proxy
        (let [url (java.net.URL. proxy)]
          (format "--proxy %s:%s" (.getHost url) (.getPort url)))
        "")
     (quoted ~url))
    (if ("test" @(shell/which wget))
      ("wget" "-O" (quoted ~path) --tries 5 --no-verbose
       ~(if proxy
          (format "-e \"http_proxy = %s\" -e \"ftp_proxy = %s\"" proxy proxy)
          "")
       (quoted ~url))
      (do
        (println "No download utility available")
        (shell/exit 1)))))

(script/defscript download-request [path request])
(script-impl/defimpl download-request :default [path request]
  ("curl" "-o" (quoted ~path) --retry 3 --silent --show-error --fail --location
   ~(string/join
     " "
     (map (fn dlr-fmt [e] (format "-H \"%s: %s\"" (key e) (val e)))
          (:headers request)))
   (quoted ~(:endpoint request))))

(script/defscript tmp-dir [])
(script-impl/defimpl tmp-dir :default []
  @TMPDIR-/tmp)

(script/defscript make-temp-file [pattern])
(script-impl/defimpl make-temp-file :default [pattern]
  @("mktemp" (quoted ~(str pattern "XXXXX"))))

(script/defscript heredoc-in [cmd content {:keys [literal]}])
(script-impl/defimpl heredoc-in :default [cmd content {:keys [literal]}]
  ("{" ~cmd
   ~(str (if literal "<<'EOFpallet'\n" "<<EOFpallet\n")
         content "\nEOFpallet\n }")))

(script/defscript heredoc [path content {:keys [literal]}])
(script-impl/defimpl heredoc :default
  [path content {:keys [literal] :as options}]
  (heredoc-in ("cat" ">" ~path) ~content ~options))

;; ;; the cat is wrapped in braces so that the final newline is protected
;; (defn heredoc
;;   "Generates a heredoc. Options:
;;       :literal boolean  - if true, prevents shell expansion of contents"
;;   [path content & {:keys [literal] :as options}]
;;   (stevedore/script (heredoc-file ~path content options))
;;   ;; (stevedore/script ("{ cat" ">" ~path
;;   ;;          ~(str (if (options :literal) "<<'EOFpallet'\n" "<<EOFpallet\n")
;;   ;;                content "\nEOFpallet\n }")))
;;   )

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

(defn touch-file [path {:keys [force] :as options}]
  (stevedore/chain-commands
   (stevedore/script
    (touch ~path :force ~force))
   (adjust-file path options)))

(defresource file
  "File management."
  (file*
   [request path & {:keys [action owner group mode force]
                    :or {action :create force true}
                    :as options}]
   (case action
     :delete (stevedore/checked-script
              (str "delete file " path)
              (rm ~path :force ~force))
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
              (rm ~name :force ~force))
     :create (stevedore/checked-script
              (format "Link %s as %s" from name)
              (ln ~from ~name :force ~force :symbolic ~true)))))

(defresource fifo
  "FIFO pipe management."
  (fifo*
   [request path & {:keys [action] :or {action :create} :as options}]
   (case action
     :delete (stevedore/checked-script
              (str "fifo " path)
              (rm ~path :force ~force))
     :create (stevedore/checked-commands
              (str "fifo " path)
              (stevedore/script
               (if-not (file-exists? ~path)
                 (mkfifo ~path)))
              (adjust-file path options)))))

(defresource sed
  "Execute sed on a file.  Takes a path and a map for expr to replacement."
  (sed*
   [request path exprs-map & {:keys [seperator no-md5 restriction] :as options}]
   (stevedore/checked-script
    (format "sed file %s" path)
    (sed-file ~path ~exprs-map ~options)
    ~(when-not no-md5
       (write-md5-for-file path (str path ".md5"))))))
