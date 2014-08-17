# Dragonmark - Clustered CSP


[Communicating Sequential Processes (CSP)](http://en.wikipedia.org/wiki/Communicating_sequential_processes) provides excellent patterns
for building concurrent systems. [Clojure's](http://clojure.org/)
[core.async](https://github.com/clojure/core.async) provides
a Clojure implementation of CSP in a single address space.

However, very few programs run in a single address space.
Web applications run in a combination of the browser and
one or more servers. Very often, applications will span
a cluster of servers.

[Dragonmark Circulate](https://github.com/dragonmark/dragonmark)
provides a mechanism for distributing `core.async` channels
across address spaces while providing the same semantics
to all the address spaces.

## Basic info

[![Clojars Project](http://clojars.org/dragonmark/circulate/latest-version.svg)](http://clojars.org/dragonmark/circulate)

The current build status:
<a href="https://travis-ci.org/dragonmark/dragonmark">
![Build Status](https://travis-ci.org/dragonmark/dragonmark.svg?branch=develop)</a>

## Some macros

I've written some macros to make writing `core.async` code
easier and more linear.

### `gofor`

The `gofor` macro allows you to build a comprehension
around `core.async` messsage sends and receives.
Here's an example of `gofor`:

    (dc/gofor
      :let [b 44]
      [root (get-service my-root {:service 'root})]
      [added (add root {:service 'wombat2 :channel (chan) :public true})]
      [b (list root)]
      :let [a (+ 1 1)]
      (reset! my-atom b)
      :error (reset! my-atom (str "Got an error " &err " for frogs " &var)))

I've [blogged about `gofor`](http://blog.goodstuff.im/gofor). Please see
that post for more information about the macro.

The key take-away from the `gofor` macro is timeouts and error handling.
Because the timeout mechanism is automatic (defaulting to 30 seconds),
if a distributed system is unavailable, the macro will timeout.

### `build-service` services from functions

It's pretty easy to convert a set of functions in a package
to a `core.async` channel that will respond/route messages
to the marked functions.

    (sc/defn ^:service get-42 :- sc/Num
        "Hello"
        ([] 42)
        ([x :- sc/Num] (+ x 42)))

    (sc/defn ^:service plus-one :- sc/Num
        [x :- sc/Num]
        (+ 1 x))

The above functions are marked with `^:service` metadata.

Calling the `build-service` macro in the current namespace,
one gets a channel that responds to messages formatted by the
`gofor` macro. For example:

    (def service-channel (dc/build-service))

Thus, in a `gofor` comprehension:

     (dc/gofor
         :let [service (dc/build-service)]
         [x (get-42 service)]
         [y (plus-one service {:x x})]
         (reset! atom-42 [x y])
         :error (reset! atom-42 &err))

Note, too, that using [Prismatic Schema](https://github.com/Prismatic/schema)
in the function definitions, one gets nice, typed documentation by sending
the `_commands` command:

     [docs (_commands service)]
     :let [_ (println "Commands:\n" docs)]

And the resulting documentation:

    Commands:
    {get-42 Inputs: ([] [x :- sc/Num])
    Returns: sc/Num

    Hello, plus-one Inputs: [x :- sc/Num]
    Returns: sc/Num}

## The root context for a distributed service

The `build-root-channel` function returns a channel
that by default responds to the commands `add`,
`remove`, `list`, and `get-service`. The
`add` and `remove` commands only respond to messages
with `{:local true}` in its [metadata](http://clojure.org/metadata)
so that only messages sent locally can add metadata,
it's a security feature.

One can add a service to the root channel:

    (dc/gofor
     [_ (add b-root {:service '42
                     :public true
                     :channel service-for-b})]

The `42` service is another channel.

We can also list services and get services:

      service-list (list b-root-proxy)
      service-42 (get-service b-root-proxy {:service '42})

## Doing it distributed

So, we have a root service that will respond to local messages
that add services (or remove services). We tie it all together
by creating a transport:

        b-transport (dc/build-transport b-root message-source message-sink)

Where `b-root` is a root channel created via `build-root-channel`
and `message-source` is a channel that will have String content from remote
transports and `message-sink` is a channel that serialized message
strings will be sent to.

The source and sink represent abstractions over transporting Strings across
address spaces. So, a source/sink pair may represent a web-socket connection,
a pair of message queues, etc.

They are abstractions of the message across address spaces. This means that Dragonmark
can abstract messages across address spaces by properly serializing the messages
and sending them to the sink as well as receiving messages from the source,
deserializing, and processing them.

### A bit of serial magic

Dragonmark uses [Cognitect Transit](http://blog.cognitect.com/blog/2014/7/22/transit)
for serialization. It includes a custom serializer for `core.async` channels
such that when a channel is serialized, a GUID for the channel is created (or
looked up if the channel has already been encountered) and on the other
side, when the GUID is deserialized, a proxy channel is created. When
a message is sent to the proxy, it is serialized and sent to the target in
the target channel's address space. This makes sending a message to a remote
channel as simple as sending a message to a local channel. Just send the
message that includes a "reply" channel. The recipient channel does
work and sends a response back to the reply channel. This works the
same in a local system and across address spaces.

### Works well with `gofor`

This works particularly well with `gofor`. When a message is sent to a channel,
an `_answer` channel is created and sent with the message. The response is
sent to the answer channel (which is a proxy in the local address space) and
the answer is populated into the variable. Also, the temporary answer
channel is closed and this triggers a close message to be sent to the
remote system which closes the proxy channel and removes it.

### All together now

So, pulling it all together, we get:

    (dc/gofor
     [_ (add b-root {:service '42
                     :public true
                     :channel service-for-b})]
     [_ (inc b-root-proxy)]
     [answer (get b-root-proxy)
      service-list (list b-root-proxy)
      service-42 (get-service b-root-proxy {:service '42})]
     [answer2 (get-42 service-42)]
     (do
       (reset! res [answer (into #{} service-list) answer2])
       (reset! done true))
     :error
     (do
       (reset! res &err)
       (reset! done true)))

All of the messages for the `b-root-proxy` are serialized and sent
across the faux address space and processed by the remote `b-root`
handler. This includes asking for the service list, getting a service,
and invoking the service.

# Where to from here?

Over the next few months, I plan to build transports. My first two
are transports for web sockets so that [Om](https://github.com/swannodette/om)
apps don't need to explicitly talk HTTP, but do all the service communication
via channels. I also plan to do a [RabbitMQ](http://www.rabbitmq.com/) transport
so that it's easy to build a distributed back end.

What needs to happen in Dragonmark itself?

* Improved error detection/reporting... not just timeouts.
* Improved mechanisms for discovery of local services and network services.
* Maybe enhance services to support REST endpoints as well, so one only writes the service once and it works via channels and HTTP

## Who

Right now, [David Pollak](https://twitter.com/dpp) is the
only one working on the project and there's no runnable code...
but that will change.

I'm not sure about the pull request policy yet. Having
clean IP in [Lift](http://liftweb.net) has been a real plus.
The core Clojure code is only changeable by Clojure committers.
On the other hand, I want to encourage contribution to Dragonmark...
once there's something worth contributing to.

## License

Dragonmark is dual licensed under the Eclipse Public License,
just like Clojure, and the LGPL 2, your choice.

A side note about licenses... my goal with the license is to
make sure the code is usable in a very wide variety of projects.
Both the EPL and the LGPL have contribute-back clauses. This means
if you make a change to Dragonmark, you have to make your changes
public. But you can use Dragonmark in any project, open or closed.
Also, the dual license is meant to allow Dragonmark to be used in
GPL/AGPL projects... and there are some "issues" between the FSF
and the rest of the world about how open the EPL, the Apache 2, etc.
licenses are. I'm not getting caught in that deal.

(c) 2014 WorldWide Conferencing, LLC
