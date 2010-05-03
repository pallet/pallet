(ns pallet.heynote-test
  (:use [pallet.heynote] :reload-all)
  (:use
   clojure.test
   pallet.test-utils))

(defn reset-atoms
  [f]
  (f)
  (reset! pallet.heynote/user-prefs nil)
  (reset! pallet.heynote/heynote-project nil))

(use-fixtures :each reset-atoms)

(deftest project-test
  (reset! pallet.heynote/heynote-project nil)
  (is (= "p1" (project "p1")))
  (is (= "p1" @pallet.heynote/heynote-project)))

(with-private-vars
  [pallet.heynote
   [user-preferences process-response]]

  (deftest user-prefs-test
    (with-temporary [tmp (tmpfile)]
      (.delete tmp)
      (reset! pallet.heynote/user-prefs nil)
      (binding [pallet.heynote/user-prefs-file (.getPath tmp)]
        (testing "no preferenes"
          (is (= {} (user-preferences)))
          (is (= {} @pallet.heynote/user-prefs))
          (is (not (.canRead tmp))))
        (testing "setting preferences"
          (is (= {:id "id1"} (user-preferences :id "id1")))
          (is (= {:id "id1"} (user-preferences)))
          (is (.canRead tmp))
          (is (= "{:id \"id1\"}" (slurp (.getPath tmp)))))
        (testing "reloading..."
          (reset! pallet.heynote/user-prefs nil)
          (is (= {:id "id1"} (user-preferences :id "id1")))))))

  (deftest message-map-test
    (project "p1")
    (reset! user-prefs {})
    (is (= {:project "p1" :user-id nil} (message-map)))
    (reset! user-prefs {:id "user"})
    (is (= {:project "p1" :user-id "user"} (message-map))))

  (deftest process-response-test
    (reset! user-prefs {})
    (process-response {"user-id" "user"})
    (is (= "user" ((user-preferences) :id)))))

