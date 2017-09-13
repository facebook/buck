nailgun-server-0.9.2-SNAPSHOT.jar and nailgun-server-0.9.2-SNAPSHOT-sources.jar were
built from https://github.com/ilya-klyuchnikov/nailgun

To regenerate these jars:

 0) install maven (brew install maven)
 1) git clone https://github.com/ilya-klyuchnikov/nailgun
 2) cd nailgun
 3) git checkout 79e1e71b996c9e5ed893b4f272dab24aa5ae8c28
 4) mvn clean package
 5) copy nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT.jar and
    nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT-sources.jar to your buck/third-party/java/nailgun directory
