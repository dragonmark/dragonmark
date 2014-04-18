(ns dragonmark.core-test
  (:require 
   [dragonmark.core :as dc]
   #+cljs [cemerick.cljs.test :as t]
   #+clj [clojure.test :as t
          :refer (is deftest with-test run-tests testing)]
   )
  #+cljs (:require-macros [cemerick.cljs.test
                           :refer (is deftest with-test run-tests testing test-var)])
  )

(deftest a-test
  (testing "FIXME, I fail."
    #+cljs (is (= 1 1))
    (is (= 1 1))))
