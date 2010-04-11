(ns pallet.resource.file
  "File manipulation."
  (:use pallet.script
        pallet.stevedore
        [pallet.utils :only [cmd-join]]
        [pallet.resource :only [defcomponent]]
        clojure.contrib.logging))

(defscript rm [file & options])
(defimpl rm :default [file & options]
  ("rm" ~(map-to-arg-string (first options)) ~file))

(defscript chown [owner file & options])
(defimpl chown :default [owner file & options]
  ("chown" ~(map-to-arg-string (first options)) ~owner ~file))

(defscript chgrp [group file & options])
(defimpl chgrp :default [group file & options]
  ("chgrp" ~(map-to-arg-string (first options)) ~group ~file))

(defscript chmod [mode file & options])
(defimpl chmod :default [mode file & options]
  ("chmod" ~(map-to-arg-string (first options)) ~mode ~file))

(defscript touch [file & options])
(defimpl touch :default [file & options]
  ("touch" ~(map-to-arg-string (first options)) ~file))

(defscript sed-file [file expr replacement & options])

(defimpl sed-file :default [file expr replacement & options]
  (sed "-i" ~(str "/" expr "/" replacement "/") ~file))

(defscript tmp-dir [])
(defimpl tmp-dir :default []
  @TMPDIR-/tmp)

(defscript heredoc [path content])
(defimpl heredoc :default [path content]
  ("cat" ">" ~path ~(str "<<EOF\n" content "\nEOF")))

(defn heredoc
  [path content & options]
  (let [options (apply hash-map options)]
    (script ("cat" ">" ~path
             ~(str (if (options :literal) "<<'EOF'\n" "<<EOF\n")
                   content "\nEOF")))))

(defn adjust-file [path opts]
  (cmd-join
   (filter
    (complement nil?)
    [(when (opts :owner)
       (script (chown ~(opts :owner) ~path)))
     (when (opts :group)
       (script (chgrp ~(opts :group) ~path)))
     (when (opts :mode)
       (script (chmod ~(opts :mode) ~path)))])))

(defn touch-file [path opts]
  (cmd-join
   [(script
     (touch ~path ~(select-keys opts [:force])))
    (adjust-file path opts)]))

(defn file*
  [path & options]
  (let [opts (apply hash-map options)
        opts (merge {:action :create} opts)]
    (condp = (opts :action)
      :delete
      (script (rm ~path ~(select-keys opts [:force])))
      :create
      (touch-file path opts)
      :touch
      (touch-file path opts))))

(defcomponent file "File management."
  file* [filename & options])
