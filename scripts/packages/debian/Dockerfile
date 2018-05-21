FROM azul/zulu-openjdk:8
ARG tag
RUN apt-get update
RUN apt-get install -y --no-install-recommends curl ca-certificates \
      git pkg-config zip unzip \
      g++ gcc \
      zlib1g-dev libarchive-dev \
      ca-certificates-java \
      ant \
      python \
      groovy \
      ghc \
      equivs && \
      apt-get clean

RUN git clone --depth 1 https://github.com/facebook/buck.git src

WORKDIR /src

RUN git checkout tags/${tag}

RUN ant

RUN ./bin/buck build scripts/packages/debian --show-output
