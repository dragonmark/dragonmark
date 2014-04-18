#/bin/sh

docker build -t dragon-dev devenv

if [ "$(docker inspect --format='{{.Image}}' dragondev)" != "$( docker inspect --format='{{.id}}' dragon-dev)" ]; then
   echo "Starting fresh"
   docker rm -f dragondev
   docker run --name dragondev -i -t -v ~/.m2:/home/dragon/.m2 -v ~/.emacs.d:/home/dragon/.emacs.d -v ~/.ssh:/home/dragon/.ssh:ro -v $(cd .. ; pwd):/home/dragon/dragonmark dragon-dev /usr/bin/byobu
else
  echo "Restarting and connecting"
  docker restart dragondev
  docker attach dragondev
fi
