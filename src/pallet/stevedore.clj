(ns pallet.stevedore
  "Embed shell script in clojure"
  (:require
   [pallet.utils :as utils]
   [clojure.string :as string]
   [clojure.contrib.condition :as condition])
  (:use clojure.walk
        clojure.contrib.logging
        pallet.script))

(defonce hashlib (utils/slurp-resource "stevedore/hashlib.bash"))

(defn ^String substring
  "Drops first n characters from s.  Returns an empty string if n is
  greater than the length of s."
  [n ^String s]
  (if (< (count s) n)
    ""
    (.substring s n)))

(defn ^String char-at
  "Gets the i'th character in string."
  [^String s i]
  (.charAt s i))

(defn- add-quotes [s]
  (str "\"" s "\""))

(defn arg-string [option argument do-underscore do-assign dash]
  (let [opt (if do-underscore (utils/underscore (name option)) (name option))]
    (if argument
      (if (> (.length opt) 1)
        (str dash opt (if-not (= argument true)
                        (str (if do-assign "=" " ") argument)))
        (str "-" opt (if-not (= argument true) (str " " argument)))))))

(defn map-to-arg-string
  "Output a set of command line switches from a map"
  [m & options]
  (let [opts (apply hash-map options)]
    (apply
     str (interpose
          " "
          (map #(arg-string
                 (first %) (second %) (opts :underscore) (:opts :assign)
                 (get opts :dash "--"))
               m)))))

(defn option-args
  "Output a set of command line switches from a sequence of options"
  [options]
  (let [m (if (first options) (apply hash-map options) {})
        assign (m :assign)
        underscore (m :underscore)]
    (map-to-arg-string
     (dissoc m :assign :underscore) :assign assign :underscore underscore)))

(declare inner-walk outer-walk)

(defmulti emit
  (fn [ expr ] (do (type expr))))

(defmulti emit-special
  (fn [ & args] (identity (first args))))

(def statement-separator "\n")

(defn statement [expr]
  (if (not (= statement-separator (.substring expr (- (count expr) (count statement-separator)))))
    (str expr statement-separator)
    expr))

(defn comma-list [coll]
  (str "(" (string/join ", " coll) ")"))

(defn splice-list [coll]
  (string/join " " coll))

(defmethod emit nil [expr]
  "null")

(defmethod emit java.lang.Integer [expr]
  (str expr))

(defmethod emit clojure.lang.Ratio [expr]
  (str (float expr)))

(defmethod emit clojure.lang.Keyword [expr]
  (name expr))

(defmethod emit java.lang.String [expr]
  expr)

(defmethod emit clojure.lang.Symbol [expr]
  (str expr))

(defmethod emit :default [expr]
  (str expr))

(def special-forms
  (set
   ['if 'if-not 'when 'case 'aget 'aset 'get 'defn 'return 'set! 'var 'defvar
    'let 'local 'literally 'deref 'do 'str 'quoted 'apply 'file-exists?
    'symlink? 'readable? 'writeable? 'not 'println 'print 'group 'pipe 'chain-or
    'chain-and 'while 'doseq 'merge! 'assoc!]))

(def infix-operators
  (set
   ['+ '- '/ '* '% '== '= '< '> '<= '>= '!= '<< '>> '<<< '>>> '& '| '&& '||
    'and 'or]))

(def logical-operators
  (set
   ['== '= '< '> '<= '>= '!= '<< '>> '<<< '>>> '& '| '&& '|| 'file-exists?
    'symlink? 'readable? 'writeable? 'not 'and 'or]))

(def quoted-operators (disj logical-operators 'file-exists? 'symlink 'can-read))

(def infix-conversions
     {'&& "-a"
      'and "-a"
      '|| "-o"
      'or "-o"
      '< "\\<"
      '> "\\>"
      '= "=="})

(defn special-form? [expr]
  (contains? special-forms expr))

(defn compound-form? [expr]
  (= 'do  (first expr)))

(defn infix-operator? [expr]
  (contains? infix-operators expr))

(defn logical-operator? [expr]
  (contains? logical-operators expr))

(defn quoted-operator? [expr]
  (contains? quoted-operators expr))

(defn emit-quoted-if-not-subexpr [f expr]
  (let [s (emit expr)]
    (if (or (.startsWith s "\\(")
            (.startsWith s "!")
            (.startsWith s "-")
            (.startsWith s "@"))
      s
      (f s))))

(defn emit-infix [type [operator & args]]
  (when (< (count args) 2)
    (throw (Exception. "not supported yet")))
  (let [open (if (logical-operator? operator) "\\( " "(")
        close (if (logical-operator? operator) " \\)" ")")
        quoting (if (quoted-operator? operator) add-quotes identity)]
    (str open (emit-quoted-if-not-subexpr quoting (first args)) " "
         (get infix-conversions operator operator)
         " " (emit-quoted-if-not-subexpr quoting (second args)) close)))

(defmethod emit-special 'file-exists? [type [file-exists? path]]
  (str "-e " (emit path)))

(defmethod emit-special 'symlink?
  [type [symlink? path]]
  (str "-h " (emit path)))

(defmethod emit-special 'readable?
  [type [readable? path]]
  (str "-r " (emit path)))

(defmethod emit-special 'writeable?
  [type [readable? path]]
  (str "-w " (emit path)))

(defmethod emit-special 'not [type [not expr]]
  (str "! " (emit expr)))

(defmethod emit-special 'local [type [local name expr]]
  (str "local " (emit name) "=" (emit expr)))

(defn- check-symbol [var-name]
  (when (re-matches #".*-.*" var-name)
    (condition/raise
     :type :invalid-bash-symbol
     :message (format "Invalid bash symbol %s" var-name)))
  var-name)

(defn- munge-symbol [var-name]
  (let [var-name (string/replace var-name "-" "__")
        var-name (string/replace var-name "." "_DOT_")
        var-name (string/replace var-name "/" "_SLASH_")]
    var-name))

(defn- set-map-values
  [var-name m]
  (str "{ "
         (string/join ""
          (map
           #(format "hash_set %s %s %s; "
                    (munge-symbol (emit var-name))
                    (munge-symbol (emit (first %)))
                    (emit (second %)))
           m))
         " }"))

    ;; This requires bash 4
    ;; (str
    ;;  "{ "
    ;;  "declare -a " (emit var-name) "; "
    ;;  (check-symbol (emit var-name)) "=" (emit expr)
    ;;  "; }")

(defmethod emit-special 'var [type [var var-name expr]]
  (if (instance? clojure.lang.IPersistentMap expr)
    (set-map-values var-name expr)
    (str
     (check-symbol (emit var-name)) "=" (emit expr))))

(defmethod emit-special 'defvar [type [defvar name expr]]
  (str (emit name) "=" (emit expr)))

(defmethod emit-special 'let [type [let name expr]]
  (str "let " (emit name) "=" (emit expr)))

(defmethod emit-special 'str [type [str & args]]
  (apply clojure.core/str (map emit args)))

(defmethod emit-special 'quoted [type [quoted arg]]
  (add-quotes (emit arg)))

(defmethod emit-special 'println [type [println & args]]
  (str "echo " (emit args)))

(defmethod emit-special 'print [type [println & args]]
  (str "echo -n " (emit args)))

(defmethod emit-special 'invoke
  [type [name & args]]
  (trace (str "INVOKE " name args))
  (or (try
        (invoke-target name args)
        (catch java.lang.IllegalArgumentException e
          (throw (java.lang.IllegalArgumentException.
                  (str "Invalid arguments for " name) e))))
      (apply str (emit name) (if (empty? args) "" " ")
             (interpose " " (map emit args)))))

(defn emit-method [obj method args]
  (str (emit obj) "." (emit method) (comma-list (map emit args))))

(defn- logical-test? [test]
  (and (sequential? test)
       (or (infix-operator? (first test))
           (logical-operator? (first test)))))

(defn- emit-body-for-if [form]
  (if (or (compound-form? form)
          (= 'if (first form)))
    (str \newline (string/trim (emit form)) \newline)
    (str " " (emit form) ";")))

(defmethod emit-special 'if [type [if test true-form & false-form]]
  (str "if "
       (if (logical-test? test) (str "[ " (emit test) " ]") (emit test))
       "; then"
       (emit-body-for-if true-form)
       (when (first false-form)
         (str "else" (emit-body-for-if (first false-form))))
       "fi"))

(defmethod emit-special 'if-not [type [if test true-form & false-form]]
  (str "if "
       (if (logical-test? test)
         (str "[ ! " (emit test) " ]")
         (str "! " (emit test)))
       "; then"
       (emit-body-for-if true-form)
       (when (first false-form)
         (str "else" (emit-body-for-if (first false-form))))
       "fi"))

(defmethod emit-special 'case
  [type [case test & exprs]]
  (str "case " (emit test) " in\n"
       (string/join ";;\n"
        (map #(str (emit (first %)) ")\n" (emit (second %)))
             (partition 2 exprs)))
       ";;\nesac"))

(defmethod emit-special 'dot-method [type [method obj & args]]
  (let [method (symbol (substring (str method) 1))]
    (emit-method obj method args)))

(defmethod emit-special 'return [type [return expr]]
  (str "return " (emit expr)))

(defmethod emit-special 'set! [type [set! var val]]
  (str (check-symbol (emit var)) "=" (emit val)))

(defmethod emit-special 'new [type [new class & args]]
  (str "new " (emit class) (comma-list (map emit args))))

(defmethod emit-special 'aget [type [aget var idx]]
  (str "${" (emit var) "[" (emit idx) "]}"))

(defmethod emit-special 'get [type [get var-name idx]]
  (str "$(hash_echo "
       (munge-symbol (emit var-name)) " "
       (munge-symbol (emit idx))
       " -n )"))

(defmethod emit-special 'aset [type [aget var idx val]]
  (str (emit var) "[" (emit idx) "]=" (emit val)))

(defmethod emit-special 'merge! [type [merge! var-name expr]]
  (set-map-values var-name expr))

(defmethod emit-special 'assoc! [type [merge! var-name idx val]]
  (format "hash_set %s %s %s"
       (munge-symbol (emit var-name))
       (munge-symbol (emit idx))
       (emit val)))

(defmethod emit-special 'deref
  [type [deref expr]]
  (if (instance? clojure.lang.IPersistentList expr)
    (str "$(" (emit expr) ")")
    (str "${" (emit expr) "}")))

(defn emit-do [exprs]
  (string/join "" (map (comp statement emit) exprs)))

(defmethod emit-special 'do [type [ do & exprs]]
  (emit-do exprs))

(defmethod emit-special 'when [type [when test & form]]
  (str "if "
       (if (logical-test? test) (str "[ " (emit test) " ]") (emit test))
       "; then"
       (str \newline (string/trim (emit-do form)) \newline)
       "fi"))

(defmethod emit-special 'while
  [type [ while test & exprs]]
  (str "while "
       (if (logical-test? test) (str "[ " (emit test) " ]") (emit test))
       "; do\n"
       (emit-do exprs)
       "done\n"))

(defmethod emit-special 'doseq
  [type [ doseq [arg values] & exprs]]
  (str "for " (emit arg) " in " (string/join " " (map emit values))
       "; do\n"
       (emit-do exprs)
       "done"))

(defmethod emit-special 'group
  [type [ group & exprs]]
  (str "{ " (string/join "; " (map emit exprs)) "; }"))

(defmethod emit-special 'pipe
  [type [ pipe & exprs]]
  (string/join " | " (map emit exprs)))

(defmethod emit-special 'chain-or
  [type [chain-or & exprs]]
  (string/join " || " (map emit exprs)))

(defmethod emit-special 'chain-and
  [type [chain-and & exprs]]
  (string/join " && " (map emit exprs)))

(defn emit-function [name sig body]
  (assert (or (symbol? name) (nil? name)))
  (assert (vector? sig))
  (str "function " name "() {\n"
       (when (not (empty? sig))
         (str
          (string/join "\n" (map #(str (emit %1) "=" "$" %2) sig (iterate inc 1)))
          \newline))
       (emit-do body)
       " }\n"))

(defmethod emit-special 'defn [type [fn & expr]]
  (if (symbol? (first expr))
    (let [name (first expr)
          signature (second expr)
          body (rest (rest expr))]
      (emit-function name signature body))
    (let [signature (first expr)
          body (rest expr)]
      (emit-function nil signature body))))

(defn emit-s-expr [expr]
  (if (symbol? (first expr))
    (let [head (symbol (name (first expr)))  ; remove any ns resolution
          expr (conj (rest expr) head)]
      (cond
        (and (= (char-at (str head) 0) \.)
             (> (count (str head)) 1)) (emit-special 'dot-method expr)
        (special-form? head) (emit-special head expr)
        (infix-operator? head) (emit-infix head expr)
        :else (emit-special 'invoke expr)))
    (string/join " " (map emit expr))))

(defmethod emit clojure.lang.IPersistentList [expr]
  (emit-s-expr expr))

(defmethod emit clojure.lang.Cons
  [expr]
  (if (= 'list (first expr))
    (emit-s-expr (rest expr))
    (emit-s-expr expr)))

(defn- spread
  [arglist]
  (cond
   (nil? arglist) nil
   (nil? (next arglist)) (seq (first arglist))
   :else (apply list (first arglist) (spread (next arglist)))))

(defmethod emit-special 'apply [type [apply & exprs]]
  (emit-s-expr (spread exprs)))

(defmethod emit clojure.lang.IPersistentVector [expr]
  (str "(" (string/join " " (map emit expr)) ")"))

(defmethod emit clojure.lang.IPersistentMap [expr]
  (letfn [(subscript-assign
           [pair]
           (str "["(emit (key pair)) "]=" (emit (val pair))))]
    (str "(" (string/join " " (map subscript-assign (seq expr))) ")")))

(defn _script [forms]
  (let [code (if (> (count forms) 1)
               (emit-do forms)
               (emit (first forms)))]
    code))

(defn- unquote?
  "Tests whether the form is (clj ...)."
  [form]
  (or (and (seq? form)
           (symbol? (first form))
           (= (symbol (name (first form))) 'clj))
      (and (seq? form) (= (first form) `unquote))))

(defn- unquote-splicing?
  "Tests whether the form is ~@( ...)."
  [form]
  (and (seq? form) (= (first form) `unquote-splicing)))

(defn handle-unquote [form]
  (second form))

(defn splice [form]
  (string/join " " (map emit form)))

(defn handle-unquote-splicing [form]
  (list splice (second form)))

(defn- inner-walk [form]
  (cond
   (unquote? form) (handle-unquote form)
   (unquote-splicing? form) (handle-unquote-splicing form)
   :else (walk inner-walk outer-walk form)))

(defn- outer-walk [form]
  (cond
   (symbol? form) (list 'quote form)
   (seq? form) (list* 'list form)
   :else form))

(defmacro quasiquote [form]
  (let [post-form (walk inner-walk outer-walk form)]
    post-form))

(defmacro script
  "Takes one or more forms. Returns a string of the forms translated into
  javascript."
  [& forms]
  `(with-line-number
     (_script (quasiquote ~forms))))

(defn do-script*
  "Concatenate multiple scripts."
  [scripts]
  (str
   (string/join \newline
     (filter (complement utils/blank?) (map #(when % (string/trim %)) scripts)))
   \newline))

(defn do-script
  "Concatenate multiple scripts."
  [& scripts]
  (do-script* scripts))

(defn chain-commands*
  "Chain commands together with &&."
  [scripts]
  (string/join " && "
    (filter (complement utils/blank?) (map #(when % (string/trim %)) scripts))))

(defn chain-commands
  "Chain commands together with &&."
  [& scripts]
  (chain-commands* scripts))

(defn checked-commands*
  "Wrap a command in a code that checks the return value. Code to output the
  messages is added before the command."
  [message cmds]
  (let [chained-cmds (chain-commands* cmds)]
    (if (utils/blank? chained-cmds)
      ""
      (str
        "echo \"" message "...\"" \newline
        "{ " chained-cmds "; } || { echo " message " failed ; exit 1 ; } >&2 "
        \newline
        "echo \"...done\"\n"))))

(defn checked-commands
  "Wrap a command in a code that checks the return value. Code to output the
  messages is added before the command."
  [message & cmds]
  (checked-commands* message cmds))

(defmacro chained-script
  "Takes one or more forms. Returns a string of the forms translated into a
   chained shell script command."
  [& forms]
  `(chain-commands
    ~@(map (fn [f] (list `script f)) forms)))

(defmacro checked-script
  "Takes one or more forms. Returns a string of the forms translated into
   shell scrip.  Wraps the expression in a test for the result status."
  [message & forms]
  `(checked-commands ~message
    ~@(map (fn [f] (list `script f)) forms)))

(defmacro defimpl
  "Define a script fragment implementation for a given set of specialisers"
  [script-name specialisers [& args]  & body]
  {:pre [(or (= :default specialisers)
             (vector? specialisers))]}
  `(pallet.script/implement
    ~(name script-name) ~specialisers
    (fn ~(symbol (str "script-fn-for-" (name script-name))) [~@args]
      (script ~@body))))
