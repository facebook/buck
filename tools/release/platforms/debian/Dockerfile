FROM azul/zulu-openjdk:8
ARG version=
ARG timestamp=
ARG repository=facebook/buck
RUN apt-get update
RUN apt-get install -y --no-install-recommends curl ca-certificates \
      git pkg-config zip unzip \
      g++ gcc \
      zlib1g-dev libarchive-dev \
      ca-certificates-java \
      ant \
      python3 \
      python3-distutils \
      groovy \
      ghc \
      equivs && \
      apt-get clean

# docker needs the old name python, create a symlink to python3
RUN python_path=`which python3`;ln -f -s ${python_path} ${python_path%3}

RUN git clone --branch v${version} --depth 1 https://github.com/${repository}.git src

WORKDIR /src

RUN ant

RUN ./bin/buck build -c buck.release_version=${version} -c buck.release_timestamp=${timestamp} buck

# This gets updated by tooling to reflect the current state of GH releases
ADD Changelog /src/tools/release/platforms/debian/Changelog

RUN ./bin/buck build -c buck.release_version=${version} -c buck.release_timestamp=${timestamp} tools/release/platforms/debian --out /src/buck.deb
