(ns dragonmark.core-test
  (:require 
   [dragonmark.core :as dc]
   [schema.core :as sc]
   #+clj [clojure.core.async :as async :refer [go chan timeout <! close!]]
   #+cljs [cljs.core.async :as async :refer [chan timeout <! close!]]
   #+cljs [cemerick.cljs.test :as t]
   #+clj [clojure.test :as t
          :refer (is deftest with-test run-tests testing)]
   )
  #+cljs (:require-macros [cemerick.cljs.test :as t
                           :refer (is deftest with-test run-tests testing test-var)]
                          [schema.macros :as sc]
                          [cljs.core.async.macros :as async :refer [go]]
                          [dragonmark.core :as dc])
  )

(def my-root (dc/build-root-channel {}))

(def my-atom (atom nil))

(def my-error-atom (atom nil))

(dc/gofor 
 :let [b 44]
 [root (get-service my-root {:service 'root})]
 [added (add root {:service 'wombat2 :channel (chan) :public true})]
 [b (list root)]
 :let [a (+ 1 1)]
 (reset! my-atom b)
 :error (reset! my-atom (str "Got an error " &err " for frogs " &var)))

(dc/gofor 
 :let [b 44]
 [root (get-service-will-fail my-root {:service 'root})]
 [added (add root {:service 'wombat2 :channel (chan) :public true})]
 [b (list root)]
 :let [a (+ 1 1)]
 (reset! my-atom [b])
 :error (reset! my-error-atom (str "Got an error " &err " for frogs " &var)))


(deftest  ^:async go-for-test
  
  #+clj (is (= (into #{} @my-atom) #{'wombat2 'root}))
  
  #+cljs (js/setTimeout
          (fn []
            (is (= (into #{} @my-atom) #{'wombat2 'root}))
            (t/done))
          25)
  )

(deftest  ^:async go-for-test
  
  #+clj (is (string? @my-error-atom))
  
  #+cljs (js/setTimeout
          (fn []
            (is (string? @my-error-atom))
            (t/done))
          25)
  )

(sc/defn ^:service get-42 :- sc/Num
  "Hello"
  ([] 42)
  ([x :- sc/Num] (+ x 42)))

(sc/defn ^:service plus-one :- sc/Num
  [x :- sc/Num]
  (+ 1 x))


(deftest ^:async build-service-test
  (let [atom-42 (atom nil)]

    (dc/gofor
     :let [service (dc/build-service)]
     [x (get-42 service)]
     [y (plus-one service {:x x})]
     (reset! atom-42 [x y])
     :error (reset! atom-42 &err))

  #+clj (do
          (Thread/sleep 100)
          (is (= @atom-42 [42 43])))

  #+cljs (js/setTimeout
          (fn []
            (is (= @atom-42 [42 43]))
            (t/done))
          25)))
