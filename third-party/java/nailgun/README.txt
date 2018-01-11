nailgun-server-0.9.2-SNAPSHOT.jar and nailgun-server-0.9.2-SNAPSHOT-sources.jar were
built from https://github.com/facebook/nailgun

To regenerate these jars:

 0) install maven (brew install maven)
 1) git clone https://github.com/facebook/nailgun
 2) cd nailgun
 3) git checkout 9acfd3926bed067eab84eb0bfe8c850a4e3eb31a
 4) mvn clean package
 5) copy nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT.jar and
    nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT-sources.jar to your buck/third-party/java/nailgun directory
