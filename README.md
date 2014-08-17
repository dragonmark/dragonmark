# Dragonmark - Clustered CSP

[Communicating Sequential Processes (CSP)](http://en.wikipedia.org/wiki/Communicating_sequential_processes) provides excellent patterns
for building concurrent systems. [Clojure's](http://clojure.org/)
[core.async](https://github.com/clojure/core.async) provides
a Clojure implementation of CSP in a single address space.
[Sente](https://github.com/ptaoussanis/sente) provides a
browser/server
websockets transport for messages, but Sente exposes the transport
layer as well as the event loop rather than abstracting the
fact that the messages are traversing address spaces. Put another
way, a developer using CSP should not have to code differently
(other than making sure all data structures are serializable)
to write a local or distributed app.

## Basic info

[![Clojars Project](http://clojars.org/dragonmark/circulate/latest-version.svg)](http://clojars.org/dragonmark/circulate)

The current build status:
<a href="https://travis-ci.org/dragonmark/dragonmark">
![Build Status](https://travis-ci.org/dragonmark/dragonmark.svg?branch=develop)</a>

## More

Dragonmark provides distributed CSP without bothering the developer
with the specifics of the location of the channel. How?

Dragonmark adds a single hook to Clojure's [EDN serialization](https://github.com/edn-format/edn) to deal with channels. If a channel is serialized, a GUID
is inserted and when the message is deserialized, the GUID is associated
with a proxy channel in the remote system. Thus:

```
(>! my-service {:command "compute" :info [1,2,3] :reply-to my-channel})

(go (let [answer (<! my-channel)] (println "answer: " answer)))
```

Nothing in the above example belies a distributed system.
Yes, it's a simplification and in reality, we'd want a timeout
waiting for the answer, etc. But the timeout is no different
in the local address space and in a distributed environment.

Both Clojure and ClojureScript are first-class citizens in Dragonmark.
The build system will use [cljx](https://github.com/lynaghk/cljx) and
the code will as much as possible be unified. This means that
remote systems in the browser, on a JVM-based server, or a NodeJS-based
server will all share the same semantics and substantially the same
APIs.

And Dragonmark will also be [JRuby](http://jruby.org/) friendly.

## Pieces

The pieces of Dragonmark include:

* The EDN serializer
* The Channel Proxy manager with leases and such
* A transportation Protocol so that different transportation mechanisms can be added
* Transport layers on top of Sente and RabbitMQ
* Integration with JRuby
* Docker containers for development and for testing across "machines"

## Playing with the code

I'm taking a different approach to setup with Dragonmark...
using [Docker](http://docker.io).

Why?

Because it's easier to have a pre-configured container to
do development rather than installing RabbitMQ, NodeJS, and
whatever else.

To get started, install Docker 0.9 or 0.10 on your system and
then `cd dragonmarker/containers` and type `./run-dev.sh`.

The first time you run the command, most of the Internet will
be downloaded and an Ubuntu 13.10 container is built with Emacs,
Vim, OpenJDK 7, git, and a few other things installed.

After the container is built, you get a [byobu](http://byobu.co/)
prompt. You can do `lein cleantest` to see the code compiled and
tested. You can do `headless-repl` and get a headless repl bound
to the IP address of the container and port 7888. This allows
you to jack in via Cider or some other client to nRepl.

If you're in the headless repl, you can press `F2` and get a new
bash prompt and use `F3` to toggle between prompts.

The container alises 3 directories into the container. It
aliases your `~/.ssh` directory (read-only) so you've got
access to your SSH keys. It aliases `~/.m2` to the container
so you don't have to repopulate the Maven cache.
Finally, the Draognmark directory is aliased to `~/dragonmark`
in the container.

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

