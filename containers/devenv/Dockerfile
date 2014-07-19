# A Dragonmark development instance container.
# This has everything to do develoment and testing for Dragonmark
# including RabbitMQ and Node for testing

FROM ubuntu:14.04

MAINTAINER David Pollak, @dpp

RUN locale-gen en_US.UTF-8
ENV LANG en_US.UTF-8

# Use squid deb proxy if the USE_SQUID shell variable is set. 
# See https://gist.github.com/dergachev/8441335
RUN /sbin/ip route | awk '/default/ { print "Acquire::http::Proxy \"http://"$3":8000\";" }' > /etc/apt/apt.conf.d/30proxy 

# Make sure the repo is up to date
# RUN echo "deb http://archive.ubuntu.com/ubuntu trusty main universe" > /etc/apt/sources.list

# Update package defs.
RUN apt-get update

# Install system utils.
RUN apt-get install -y curl vim emacs24 emacs24-el openjdk-7-jdk git-cola rabbitmq-server nodejs npm 

# Install byobu for multiple bash problems in a single terminal
RUN apt-get install -y byobu screen

# Upgrade to the latest
RUN apt-get upgrade -y

# Alias nodejs to node

RUN ln -s /usr/bin/nodejs /usr/bin/node

# Add the dragon user
RUN useradd -s /bin/bash -m dragon
RUN adduser dragon sudo

RUN mkdir /home/dragon/bin

# Make sure ~/bin gets added to the .bashrc file
RUN echo "PATH=$PATH:/home/dragon/bin" >> /home/dragon/.bashrc


# Copy lein-related files
ADD lein /home/dragon/bin/lein
ADD headless-repl /home/dragon/bin/headless-repl

RUN chmod +x /home/dragon/bin/lein
RUN chmod +x /home/dragon/bin/headless-repl

# Clean up permissions
RUN chown -R dragon:dragon /home/dragon

# And we can do sudo without a password
RUN echo "dragon ALL=NOPASSWD:ALL" >>  /etc/sudoers

# Start rabbit on startup and land the user in the dragonmark directory
ENTRYPOINT sudo /etc/init.d/rabbitmq-server start && cd ~/dragonmark && /usr/bin/byobu

USER dragon
ENV HOME /home/dragon







