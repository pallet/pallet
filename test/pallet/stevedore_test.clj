(ns pallet.stevedore-test
  (:use [pallet.stevedore] :reload-all)
  (:use [pallet.utils :only [bash]]
        clojure.test)
 (:require pallet.compat))

(pallet.compat/require-contrib)

(defn strip-ws
  "strip extraneous whitespace so tests don't fail because of differences in whitespace"
  [s]
  (-> s
    (.replaceAll "[ ]+" " ")
    .trim))

(defn strip-line-ws
  "strip extraneous whitespace so tests don't fail because of differences in whitespace"
  [#^String s]
  (-> s
    (.replace "\n" " ")
    (.replaceAll "[ ]+" " ")
    .trim))

(defmacro bash-out
  "Check output of bash. Macro so that errors appear on the correct line."
  [str]
  `(let [r# (bash ~str)]
    (is (= 0 (:exit r#)))
    (is (= "" (:err r#)))
    (:out r#)))

(deftest number-literal
  (is (= "42" (script 42)))
  (is (= "0.5" (script 1/2))))

(deftest simple-call-test
  (is (= "a b" (script (a b)))))

(deftest call-multi-arg-test
  (is (= "a b c" (script (a b c)))))

(deftest test-arithmetic
  (is (= "(x * y)" (script (* x y)))))

(deftest test-return
  (is (= "return 42" (strip-ws (script (return 42))))))

(deftest test-script-call
  (let [name "name1"]
    (is (= "grep \"^name1\" /etc/passwd"
           (script (grep ~(str "\"^" name "\"") "/etc/passwd"))))))

(deftest test-clj
  (let [foo 42
        bar [1 2 3]]
    (is (= "42" (script (clj foo))))
    (is (= "42" (script ~foo)))
    (is (= "foo 1 2 3" (script (apply foo ~bar))))))

(deftest test-str
  (is (= "foobar"
         (script (str foo bar)))))

(deftest test-fn
  (is (= "function foo(x) {\nfoo a\nbar b\n }"
         (strip-ws (script (fn foo [x] (foo a) (bar b)))))))

(deftest test-aget
  (is (= "${foo[2]}" (script (aget foo 2)))))

(deftest test-array
  (is (= "(1 2 \"3\" foo)" (script [1 "2" "\"3\"" :foo]))))

(deftest test-if
  (is (= "if [ \\( \\( \"foo\" == \"bar\" \\) -a \\( \"foo\" != \"baz\" \\) \\) ]; then echo fred;fi\n"
         (script (if (&& (== foo bar) (!= foo baz)) (echo fred)))))
  (is (= "fred\n"
         (bash-out (script (if (&& (== foo foo) (!= foo baz)) (echo "fred"))))))
  (is (= "if foo; then\nx=3\nfoo x\nelse\ny=4\nbar y\nfi\n"
         (script (if foo (do (var x 3) (foo x)) (do (var y 4) (bar y))))))
  (is (= "not foo\n"
         (bash-out (script (if (== foo bar)
                             (do (echo "foo"))
                             (do (echo "not foo")))))))
  (is (= "if [ -e file1 ]; then echo foo;fi\n"
         (script (if (file-exists? "file1") (echo "foo")))))
  (is (= "if [ ! -e file1 ]; then echo foo;fi\n"
         (script (if (not (file-exists? "file1")) (echo "foo")))))
  (is (= "if [ \\( ! -e file1 -o \\( \"a\" == \"b\" \\) \\) ]; then echo foo;fi\n"
           (script (if (|| (not (file-exists? "file1")) (== "a" "b")) (echo "foo"))))))

(deftest if-nested-test
  (is (= "if [ \\( \"foo\" == \"bar\" \\) ]; then
if [ \\( \"foo\" != \"baz\" \\) ]; then echo fred;fi
fi\n"
         (script (if (== foo bar)
                   (if (!= foo baz)
                     (echo fred)))))))

(deftest test-if-not
  (is (= "if [ ! -e bar ]; then echo fred;fi\n"
         (script (if-not (file-exists? bar) (echo fred)))))
  (is (= "if [ ! \\( -e bar -a \\( \"foo\" == \"bar\" \\) \\) ]; then echo fred;fi\n"
         (script (if-not (&& (file-exists? bar) (== foo bar)) (echo fred)))))
  (is (= "if [ ! \\( \\( \"foo\" == \"bar\" \\) -a \\( \"foo\" == \"baz\" \\) \\) ]; then echo fred;fi\n"
         (script (if-not (&& (== foo bar) (== foo baz)) (echo fred)))))
  (is (= "fred\n"
         (bash-out (script (if-not (&& (== foo foo) (== foo baz)) (echo "fred")))))))

(deftest test-map
  (is (= "([packages]=(columnchart))" (strip-ws (script {:packages ["columnchart"]}))))
  (is (= "x=([packages]=columnchart)\necho ${x[packages]}"
         (strip-ws (script (do (var x {:packages "columnchart"})
                               (echo (aget x :packages)))))))
  (is (= "columnchart\n"
         (bash-out (script (do (var x {:packages "columnchart"})
                             (echo (aget x :packages))))))))

(deftest test-do
  (is (= "let x=3\nlet y=4\nlet z=(x + y)"
         (strip-ws
	  (script
	   (let x 3)
	   (let y 4)
	   (let z (+ x y))))))
  (is (= "7\n"
         (bash-out
	  (script
	   (let x 3)
	   (let y 4)
	   (let z (+ x y))
           (echo @z))))))

(deftest deref-test
  (is (= "${TMPDIR-/tmp}" (script @TMPDIR-/tmp)))
  (is (= "$(ls)" (script @(ls)))))

(deftest test-combine-forms
  (let [stuff (quote (do
		       (local x 3)
		       (local y 4)))]
    (is (= "function foo(x) {\nlocal x=3\nlocal y=4\n }"
	   (strip-ws (script (fn foo [x] ~stuff)))))))


