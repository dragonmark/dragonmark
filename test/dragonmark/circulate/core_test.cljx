(ns dragonmark.circulate.core-test
  (:require
   [cognitect.transit :as ct]
   [dragonmark.circulate.core :as dc]
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
                          [dragonmark.circulate.core :as dc])
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
     [docs (_commands service)]
     :let [_ #+clj (println "Commands:\n" docs) #+cljs nil]
     (reset! atom-42 [x y])
     :error (reset! atom-42 &err))

    (letfn [(do-test []
              (is (= @atom-42 [42 43])))]
      #+clj (do
              (Thread/sleep 100)
              (do-test)
              )

      #+cljs (js/setTimeout
              (fn []
                (do-test)
                (t/done))
              25))))



(deftest ^:async distributed-test
  (let [a-chan (chan)
        b-chan (chan)
        a-info (atom 0)
        b-info (atom 0)
        b-delegate (dc/build-root-channel
                    {"woof"
                     (fn [msg env]
                       (get-in
                        (swap! env update-in
                               [:data :woof]
                               (fn [x]
                                 (if
                                     (number? x)
                                   (inc x)
                                   72)))
                        [:data :woof]))}
                    "b-delegate" nil)
        a-root (dc/build-root-channel {"get" (fn [msg env] @a-info)
                                       "inc" (fn [msg env] (swap! a-info inc))})
        b-root (dc/build-root-channel {"get" (fn [msg env]
                                               @b-info)
                                       "inc" (fn [msg env]
                                               (swap! b-info inc))}
                                      "b-root"
                                      b-delegate)
        service-for-b (dc/build-service)
        a-transport (dc/build-transport a-root a-chan b-chan)
        b-transport (dc/build-transport b-root b-chan a-chan)
        b-root-proxy (dc/remote-root a-transport)
        a-root-proxy (dc/remote-root b-transport)
        res (atom nil)
        done (atom false)

        #+clj wait-obj #+clj ""]

    (let [the-chan (chan)]
      #+cljs (aset the-chan "transitTag" "dragonmark-channel")

      (is (= {:foo the-chan}
             {:foo  (dc/deserialize b-transport
                                    (dc/serialize
                                     b-transport the-chan))}))

      (is (= 1  (-> (dc/proxy-info b-transport)
                    first
                    deref
                    count)))

      (async/close! the-chan)

      (is (= 0 (-> (dc/proxy-info b-transport)
                   first
                   deref
                   count)))
      )

    (dc/gofor
     [_ (add b-root {:service '42
                     :public true
                     :channel service-for-b})]
     [_ (inc b-root-proxy)]
     [answer (get b-root-proxy)
      service-list (list b-root-proxy)
      woof (woof b-root-proxy)
      service-42 (get-service b-root-proxy {:service '42})]
     [answer2 (get-42 service-42)]
     (do
       (reset! res [answer (into #{} service-list) answer2 woof])
       (reset! done true))
     :error
     (do
       (reset! res &err)
       (reset! done true)))

    #+clj
    (add-watch res :none (fn [_ _ _ _] (locking wait-obj (.notifyAll wait-obj))))

    (letfn
        [(test-it []
           (is (= [1 #{'42 'root} 42 72] @res))

           (is (= 1 (-> (dc/proxy-info b-transport)
                        first
                        deref
                        count)))
           (is (= 1 (-> (dc/proxy-info a-transport)
                        first
                        deref
                        count)))
           )]
      #+clj
      (do
        (locking wait-obj (.wait wait-obj))

        (Thread/sleep 50)

        (test-it)

        ;; (is (= (first @res) @b-info))
        )

      #+cljs
      (let [the-count (atom 0)]
        (letfn [(testit []
                  (if (not @done)
                    (if (> 50000 (swap! the-count inc))
                      (js/setTimeout testit 20)
                      (do
                        (is (= nil "Timeout"))
                        (t/done)
                        ))
                    (do
                      (test-it)
                      (t/done))
                    ))] (testit))))
    ))
