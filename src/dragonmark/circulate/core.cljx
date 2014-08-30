(ns dragonmark.circulate.core
  #+cljs (:require-macros [cljs.core.async.macros :refer [go]]
                          [schema.core :as sc])
  (:require
   [dragonmark.util.core :as du]
   [schema.core :as sc]
   [cognitect.transit :as t]
   #+clj [cljs.analyzer :as cljs-analyzer]
   #+clj [clojure.core.async :as async :refer [go chan timeout]]
   #+cljs [cljs.core.async :as async :refer [chan timeout]]

   #+clj [clojure.core.async.impl.protocols :as impl]
   #+clj [clojure.core.async.impl.channels :as chans]

   #+cljs [cljs.core.async.impl.protocols :as impl]
   #+cljs [cljs.core.async.impl.channels :as chans]
   )

  #+clj (:import
         [clojure.core.async.impl.channels ManyToManyChannel]
         [clojure.core.async.impl.protocols Channel]
         [java.io Writer])
  )

(defonce env-root
  (atom nil))

(def i-got-it
  "Return this from the message handler if the
  answer is going to be sent back to the return channel
  ansynchronously"
  :dragonmark.circulate:i_got_it)

(defn- error-or-answer
  "If the answer is not {:error something} wrap in {:answer value}"
  [value]
  (or
   (:error value)
   {:answer value}))

(defprotocol EnvInfo
  "Carries the environment around"
  (env-info [_] "Gets the environment atom")
  (locate-service [_ service] "locates the service in the delegation chain"))

(defn- process
  "Processes a message sent to the root channel"
  [channel message env]
  (let [return (:_return message)
        command (:_cmd message)
        local? (-> message meta :local boolean)
        answer
        (case command
          "list" {:answer (->> @env :services
                               (filter #(or local? (-> % second :public)))
                               (map first))}

          "add" (if local?
                  (let [old (get-in @env [:services (:service message)])]
                    (when old (go (async/>! old {:_cmd "removed_from_root"})))
                    (swap! env update-in [:services]
                           assoc (:service message)
                           {:channel (:channel message)
                            :public (-> message :public boolean)})
                    {:answer true})
                  {:error "Cannot add a service without a :local in the meta of the message"})

          "remove" (if local?
                     (let [old (get-in @env [:services (:service message)])]
                       (when old
                         (go (async/>! old {:_cmd "removed_from_root"})))
                       (swap! env update-in [:services] dissoc
                              (:service message)))

                     {:error "Cannot add a service without a :local in the meta of the message"})

          "get-service"
          (let [result (-> @env :services (get (:service message)))
                result (if (and result
                                (or (not local?)
                                    (:public result)))
                         (:channel result)
                         nil)]
            (if result
              {:answer result}
              {:error
               (str "Service " (:service message)
                    " not found")}))

          "locate-service"
          (let [result (locate-service channel (:service message))
                result (if (and result
                                (or (not local?)
                                    (:public result)))
                         (:channel result)
                         nil)]
            (if result
              {:answer result}
              {:error
               (str "Service " (:service message)
                    " not found")}))

          "name"
          {:answer (:name @env)}

          "add-handler"
          (if local?
            ((swap!
              env
              assoc-in [:commands (:command message)] (:function message))
             {:answer true})
            {:error "Cannot add handler without a :local in the meta of the message"})

          "remove-handler"
          (if local?
            ((swap!
              env
              update-in [:commands] dissoc (:command message))
             {:answer true})
            {:error "Cannot remove handler without a :local in the meta of the message"})

          "delegate"
          {:answer (:delegate @env)}

          (or
           ;; try local custom functions
           (some-> (get (:commands @env) command)
                   (apply [message env])
                   error-or-answer)

           ;; or send it to the delegate
           (if-let [delegate  (:delegate @env)]
            (do
              (async/put! delegate message)
              i-got-it)
            nil
            )

           {:error
            (str "Unabled to process command: "
                 command)}))]
    (when (and answer
               return
               (not (= answer i-got-it))) (go (async/>! return answer)))))




(defn build-root-channel
  "Builds a root channel (usually put in env-root,
  but can be stand-alone so there can be multiple
  root channels in a single address space... nice for testing.
  The commands parameter is a map of String/function where the
  String is the name of the command and the function is a two
  parameter function (message and env) that returns a value to
  send to the answer channel or :dragonmark.circulate:i_got_it"
  ([] (build-root-channel {} (du/next-guid) nil))
  ([commands] (build-root-channel commands (du/next-guid) nil))
  ([commands name delegate]
     (let [c (chan 5)
           env (atom {:services
                      {'root {:channel c
                              :public true}}

                      :name name

                      :commands commands

                      :delegate delegate})

           chan+env
           (reify
             EnvInfo
             (env-info [_] env)
             (locate-service [_ service]
               (let [ret
                     (or
                      (-> @env :services (get service))
                      (if-let [delegate (:delegate @env)]
                        (do
                          (and
                           (instance? dragonmark.circulate.core.EnvInfo delegate)
                           (locate-service delegate service)))))]
                 ret
                 ))

             chans/MMC
             (cleanup [_] (chans/cleanup c))
             (abort [_] (chans/abort c))

             impl/WritePort
             (put! [this val handler]
               (impl/put! c val handler))

             impl/ReadPort
             (take! [port fn1-handler] (impl/take! c fn1-handler))

             impl/Channel
             (close! [chan] (impl/close! c))
             (closed? [chan] (impl/closed? c))
             )]

       ;; make sure Transit can serialize this in ClojureScript
       #+cljs (aset chan+env "transitTag" "dragonmark-channel")

       (go
         (loop []
           (let [message (async/<! c)]
             (if (nil? message) nil ;; nil... closed channel... bye
                 (do
                   (process chan+env message env)
                   (recur))))))

       chan+env
       )))

(defn- index-of
  "find the integer into of the value in the collection"
  [coll value]
  #+clj (.indexOf coll value)
  #+cljs (loop [pos 0 coll coll]
           (if (empty? coll) -1
               (let [i (first coll)
                     r (rest coll)]
                 (cond
                  (= i value) pos
                  :else (recur (+ 1 pos) r)))))
  )

(defn go-parallel
  "Run a bunch of go routines in parallel. params is a sequence of {:chan :msg}.
  For each param, a return channel is created and sent with the messages. When
  all the messages have been answered, to result is sent back to
  the result chan. If there's a timeout waiting, then send a timeout error
  to the result-chan"
  [params timeout result-chan]
  (let [chans (map (fn [p]
                     (let [ret-chan (async/chan)]
                       [(:chan p) (assoc (:msg p) :_return ret-chan) ret-chan]))
                   params)
        len (count chans)
        _ (doall (map (fn [[ch msg _]] (go (async/>! ch msg))) chans))
        toc (async/timeout timeout)
        alt-on (cons toc (map last chans))
        ret (mapv (fn [x] nil) chans)
        close-and-send (fn [result]
                         (go (async/>! result-chan result))
                         (doall (map async/close! (rest alt-on)))
                         )]
    (go
      (loop [ret ret cnt 0]
        (let [[value chan] (async/alts! alt-on)]
          (if (= chan toc)
            (close-and-send {:error "timeout"})
            (let [pos (index-of alt-on chan)
                  ret (assoc ret (- pos 1) value)
                  cnt (+ 1 cnt)]
              (if (= len cnt)
                (close-and-send ret)
                (recur ret cnt)
                )
              )))
        ))
    ))

#+clj
(defmacro gofor
  "A for comprehension built out of go blocks

  ```
  (gofor
  [a (foo channel {:thing value :other_thing other_value})]
  :let [b (+ a 1)]
  [c (baz other_channel)
  d (moose third_channel {:p b}
  :timeout 45000]
  (println a b c)
  :error (println &err &at))
  ```
  "
  [& info]
  (let [cljs-macro (boolean (:ns &env))

        [chan go <! >! close!] (if cljs-macro

                                 '[cljs.core.async/chan
                                   cljs.core.async.macros/go
                                   cljs.core.async/<!
                                   cljs.core.async/>!
                                   cljs.core.async/close!]

                                 '[clojure.core.async/chan
                                   clojure.core.async/go
                                   clojure.core.async/<!
                                   clojure.core.async/>!
                                   clojure.core.async/close!])

        [err info]
        (or
         (and
          (= :error (-> info butlast last))
          (seq? (last info))
          [`(fn [~'&err ~'&var] ~(last info)) (drop-last 2 info)])

         [`(fn [& foo#] ) info])
        err-var `err#
        [body info] [(last info) (butlast info)]]
    (letfn [(do-go [mine other]
              (let [pairs (partition 2 mine)
                    [timeout pairs]
                    (if (= :timeout (-> pairs last first))
                      [(-> pairs last second) (butlast pairs)]
                      [30000 pairs])]
                `(let [chan# (~chan)]
                   (go-parallel
                    ~(mapv (fn [[_ [cmd chan params]]]
                             `{:chan ~chan
                               :msg (with-meta (assoc ~params :_cmd ~(name cmd))
                                      {:local true})
                               }) pairs)
                    ~timeout chan#)
                   (~go
                     (let [res# (~<! chan#)]
                       (~close! chan#)
                       (if (vector? res#)
                         (let [answers# (partition 2
                                                   (interleave
                                                    '[~@(map first pairs)] res#))
                               errors# (filter #(-> % second :error) answers#)]
                           (if (not (empty? errors#))
                             (~err-var
                              (-> errors# first first)
                              (-> errors# first second))

                             (let [[~@(map first pairs)] (map :answer res#)]
                               ~other)))
                         (~err-var res# '[~@(mapv first pairs)])))))
                ))

            (process-info [info]
              (cond
               (empty? info) `~body

               (vector? (first info))
               (do-go (first info) (process-info (rest info)))

               (and (= :let (first info))
                    (vector? (second info)))
               `(let ~(second info) ~(process-info (drop 2 info)))

               :else (throw (Exception. (str "Don't know how to process " info)))
               ))]
      `(let [~err-var ~err]
         ~(process-info info)))))


#+clj
(defn- build-func
  "Takes a function defintion and builds a name/function pair
  where the function applies the function with the named parameters"
  [info]
  (let [xn `x#
        params (:arglists info)
        params (if (-> params first symbol?) (second params) params)
        params (sort #(> (count %1) (count %2)) params)
        params (map #(map keyword %) params)

        the-cond `(cond
                   ~@(mapcat (fn [arity]
                               [`(and
                                  ~@(map (fn [p] `(contains? ~xn ~p)) arity)
                                  )
                                `(apply ~(:name info) [~@(map (fn [p] `(~p ~xn))
                                                              arity)])]) params)
                   :else (with-meta
                           {:error (str "parameters not matched. expecting "
                                        ~(str (into [] params)) " but got "
                                        (keys ~xn))} {:error true}))
        ]

    [(-> info :name name)
     `(fn [~xn]
        ~the-cond
        )]))

#+clj
(defn- wrap-in-thread
  "Wraps the call in a thread if it's on the JVM"
  [env s-exp]
  (if (:ns env) s-exp
      `(future ~s-exp)))

#+clj
(defmacro build-service
  "builds a service -- a channel that looks at all the public functions in the
  current package marked with {:service true} and wraps up message responders.
  When a message comes in with a :_cmd that has the name of the function, the
  named parameters are unwrapped and the function is dispatched and the response
  is sent back to the channel in :_return.

  Plays very well with `gofor`."
  []
  (let [cljs-macro (boolean (:ns &env))

        [chan go <! >! close!] (if cljs-macro '[cljs.core.async/chan
                                                cljs.core.async.macros/go
                                                cljs.core.async/<!
                                                cljs.core.async/>!
                                                cljs.core.async/close!]

                                   '[clojure.core.async/chan
                                     clojure.core.async/go
                                     clojure.core.async/<!
                                     clojure.core.async/>!
                                     clojure.core.async/close!])

        my-ns (if cljs-macro cljs.analyzer/*cljs-ns* *ns*)

        info
        (if cljs-macro
          (some->> (cljs.analyzer/get-namespace my-ns) :defs vals
                   (filter :service)
                   (into []))
          (->> (ns-publics my-ns)
               vals
               (filter #(-> % meta :service))
               (map meta)
               (into [])))
        the-funcs (map build-func info)

        cmds (into {} (map (fn [x] [(-> x :name name) (:doc x)]) info))

        built-funcs (merge {"_commands"
                            `(fn [x#] ~cmds)
                            }
                           (into {} the-funcs))

        answer `answer#

        the-func `the-func#

        it `it#

        cmd `cmd#

        wrapper `wrapper#
        ]
    (let [ret
          `(let [c# (~chan)
                 funcs# ~built-funcs
                 ]
             (~go
               (loop [~it (~<! c#)]
                 (if (nil? ~it) nil
                     (let [~cmd (:_cmd ~it)
                           ~answer (:_return ~it)
                           ~the-func (funcs# ~cmd)
                           ~wrapper (or (-> ~it meta :bound-fn)
                                        (fn [f# p#] (f# p#)))
                           ]
                       ~(wrap-in-thread
                         &env
                         `(let [res#
                                (if ~the-func
                                  (try
                                    (let [result# (~wrapper ~the-func ~it)]
                                      (if (-> result# meta :error) result#
                                          {:answer result#}))
                                    (catch ~(if cljs-macro
                                              `js/Object
                                              `Exception)
                                        excp# {:error excp#}))
                                  {:error (str "Command " ~cmd
                                               " not found")})]
                            (when ~answer
                              (~go (~>! ~answer res#)))))
                       (recur (~<! c#))))))
             c#)]
      ret)))

(defprotocol Transport
  "A transport to another address space"
  (remote-root
    ;;"Returns a channel that is a way to send messages to the remote root"
    [this])
  (close!
    ;;"Close the transport"
    [this])

  (proxy-info [this])

  (serialize [this info])

  (deserialize [this info]))

#+clj
(defn- chan-closed? "Is the channel closed?" [chan] (some-> (.-closed chan) deref))

#+cljs
(defn- chan-closed? "Is the channel closed?" [chan]  (.-closed chan))

#+clj
(defn- on-close
  "registers a function to call on close"
  [chan func]
  (if (chan-closed? chan)
    (func)
    (add-watch (.-closed chan) :none (fn [k r os ns]
                                       (func))))
  )

#+cljs
(defn- on-close
  "registers a function to call on close"
  [chan func]
  (if (chan-closed? chan)
    (func)
    (let [cur-value (atom false)
          it #js
          {"configurable" true
           "enumerable" true

           "get" (fn [] @cur-value)
           "set" (fn [nv]
                   (reset! cur-value nv)
                   (when nv
                     (func))
                   nv
                   )
           }]
      (.defineProperty
       js/Object
       chan
       "closed"
       it
       ))))

;; #+cljs
;; (deftype TransportImpl [remote-proxy close-func]
;;   Transport
;;   (remote-root [this] remote-proxy)
;;   (close! [this] (close-func)
;;     ;; FIXME what else do we close down?
;;     )
;;   )

#+cljs
(deftype ChannelHandler [write-func]
  ;; com.cognitect.transit.WriteHandler
  Object
  (tag [_ v] "chan-guid")
  (rep [_ v] (write-func v))
  (stringRep [_ v] (write-func v)))

(defn build-transport
  "Builds a transport that consumes strings from `source-chan' and sends
  string messages to `dest-chan'. Incoming messages will either be
  routed to the local `root' or to the appropriate GUID"
  [root
   source-chan
   dest-chan]

  (let [remote-proxy (chan)
        my-mode (atom :starting)
        chan-to-guid (atom {})
        guid-to-chan (atom {})
        running? (atom true)
        seen-hello? (atom false)
        last-10 (atom nil)
        ack-channels (atom {})
        guid-closed (atom (fn [x]))
        send-message (atom (fn [guid message ack-chan]))

        write-func
        (fn [item]
          (cond
           (or (nil? item)
               (chan-closed? item))
           "closed"

           (= item root)
           "root"

           :else
           (if-let [guid (get @chan-to-guid item)]
             guid
             (let [guid (du/next-guid)]
               (swap! chan-to-guid assoc item guid)
               (on-close item (fn [] (@guid-closed guid)))
               (swap! guid-to-chan assoc guid {:chan item
                                               :local true :proxy false})
               guid
               )
             )))

        write-handler
        #+clj (reify com.cognitect.transit.WriteHandler
                (tag [_ _] "chan-guid")
                (stringRep [this item] (write-func item))
                (rep [_ item] (write-func item))
                (getVerboseHandler [_] nil))

        #+cljs (ChannelHandler. write-func)

        ;; convert a GUID into a Channel
        read-func
        (fn [guid]
          (cond
           (= "closed" guid)
           nil

           (= "root" guid)
           root

           :else
           (if-let [the-chan (get @guid-to-chan guid)]
             (:chan the-chan)
             (let [proxy-chan (chan)]
               (swap! guid-to-chan assoc guid {:chan proxy-chan
                                               :local false :proxy true})
               (swap! chan-to-guid assoc proxy-chan guid)
               (on-close proxy-chan
                         (fn []
                           (do
                             (swap! chan-to-guid dissoc proxy-chan)
                             (swap! guid-to-chan dissoc guid)
                             )))
               (go
                 (loop []
                   (let [message (async/<! proxy-chan)]
                     (if (nil? message) nil
                         (let [ack-chan (chan)]
                           (@send-message guid message ack-chan)
                           (async/<! ack-chan)
                           (async/close! ack-chan)
                           (if @running? (recur) nil)
                           ))
                     )
                   ))
               proxy-chan))))

        read-handler
        #+clj
        (reify
          com.cognitect.transit.ReadHandler
          (fromRep [_ guid] (read-func guid)
            ))
        #+cljs read-func

        ]
    (letfn [#+clj
            (do-serialize [a-form]
                          (let [bos (java.io.ByteArrayOutputStream.)]
                            (t/write (t/writer bos :json
                                               {:handlers
                                                {;;ManyToManyChannel
                                                 ;;write-handler
                                                 Channel
                                                 write-handler}
                                                ;; {}
                                                } )
                                     a-form)
                            (.toString bos "UTF-8")))

            #+clj
            (do-deserialize [a-string]
                            (t/read
                             (t/reader (java.io.ByteArrayInputStream.
                                        (.getBytes a-string "UTF-8"))
                                       :json
                                       {:handlers {"chan-guid" read-handler}}
                                       )))

            #+cljs
            (do-serialize
             [a-form]
             (let [ret
                   (t/write (t/writer
                             :json
                             {:handlers
                              {cljs.core.async.impl.channels/ManyToManyChannel
                               write-handler
                               "dragonmark-channel"
                               write-handler
                               }}) a-form)]
               ret
               ))

            #+cljs
            (do-deserialize
             [a-string]
             (let [ret
                   (t/read
                    (t/reader :json
                              {:handlers {"chan-guid" read-handler}}
                              )
                    a-string
                    )]
               ret
               ))
            ]
      (let [sender-ack-chan (chan) ;; send acks to this channel
            sender-chan ;; send a message to this channel and it's given a GUID
            ;; serialized
            ;; and forwarded across the wire
            (let [my-chan (chan)]
              (go
                (loop []
                  (let [message (async/<! my-chan)]
                    (when message
                      (let [guid (or (:guid message) (du/next-guid))
                            message (assoc message :guid guid)
                            message-str (do-serialize message)]
                        (async/>! dest-chan message-str)
                        (loop []
                          (let [resp-guid (async/<! sender-ack-chan)]
                            (cond
                             (nil? resp-guid) nil
                             (= resp-guid guid) nil

                             ;; keep sending the message 'til we get an ack
                             :else (do (async/>! dest-chan message-str) (recur))))))
                      (if @running? (recur) nil)))
                  ))
              my-chan)
            ]
        (letfn [(do-guid-closed
                  [guid]
                  (async/put! sender-chan {:type :close :target guid})
                  (if-let [info (get @guid-to-chan guid)]
                    (let [{:keys [chan local proxy]} info]
                      (swap! guid-to-chan dissoc guid)
                      (swap! chan-to-guid dissoc chan))))

                (do-send-message [target message ack-chan]
                  (let [guid (du/next-guid)]
                    (when ack-chan
                      (swap! ack-channels assoc guid ack-chan))
                    (go (async/>! sender-chan {:type :forward
                                               :target target
                                               :guid guid
                                               :body (do-serialize message)}))
                    ))
                ]
          (reset! guid-closed do-guid-closed)
          (reset! send-message do-send-message)
          (go
            (loop []
              (when-some [message (async/<! remote-proxy)]
                (let [ack-chan (chan)]
                  (do-send-message "root" message ack-chan)
                  (async/<! ack-chan)
                  (async/close! ack-chan)
                  (if @running? (recur) nil)))))
          (go
            (loop []
              (let [message-str (async/<! source-chan)]
                (when message-str
                  (let [message (do-deserialize message-str)
                        guid (:guid message)
                        type (:type message)]

                    ;; ack the message
                    (when guid
                      (go (async/>! dest-chan (do-serialize {:type :ack
                                                             :target guid}))))
                    (if (and (not guid) (contains? (into #{} @last-10) guid))
                      nil ;; if we've processed the message (seen the GUID) do nothing

                      (do
                        (when guid
                          (swap! last-10 (fn [v] (take 10 (cons guid v)))))

                        (condp = type
                          :hello
                          (when (not @seen-hello?)
                            (reset! seen-hello? true)
                            (go (async/>! sender-chan {:type :hello}))
                            )

                          :bye
                          (do
                            (reset! running? false)
                            ;; FIXME what else do we do?
                            )

                          :ack
                          (go
                            (async/>! sender-ack-chan (:target message)))

                          :close
                          (let [guid (:target message)
                                info (get @guid-to-chan guid)]
                            (when (and info (:proxy info))
                              (let [the-chan (:chan info)]
                                (swap! guid-to-chan dissoc guid)
                                (swap! chan-to-guid dissoc the-chan)
                                (async/close! the-chan))
                              ))

                          :forward
                          (let [target-guid (:target message)
                                target (read-func target-guid)
                                inner-message (do-deserialize (:body message))]
                            (go
                              (async/>! target inner-message)
                              (async/>! sender-chan {:type :ack-forward
                                                     :target guid})))

                          :ack-forward
                          (when-some [target (:target message)]
                            (when-some [ack-chan (get @ack-channels target)]
                              (swap! ack-channels dissoc target)
                              (go (async/>! ack-chan target))
                              )))))
                    )
                  (if @running? (recur) nil)))
              )))

        (reify Transport
          (remote-root [this] remote-proxy)
          (close! [this]

            (async/close! remote-proxy)
            (do
              (async/put! sender-chan {:type :bye})
              (reset! running? false))
              (dorun
               (as-> @guid-to-chan
                     info
                     (vals info)
                     (filter :proxy info)
                     (map :chan info)
                     (map async/close! info)
                     ))
            ;; FIXME what else do we close down?
            )
          (proxy-info [this] [chan-to-guid guid-to-chan])
          (serialize [this form] (do-serialize form))
          (deserialize [this string] (do-deserialize string))
          )))))
