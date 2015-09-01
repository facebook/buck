nailgun-server-0.9.2-SNAPSHOT.jar and nailgun-server-0.9.2-SNAPSHOT-sources.jar were
built from https://github.com/bhamiltoncx/nailgun.git

To regenerate these jars:

 0) install maven (brew install maven)
 1) git clone https://github.com/bhamiltoncx/nailgun.git
 2) cd nailgun
 3) git checkout 040f35ec60eca27ef641a526eaae7e27e7f8bd9c
 4) mvn clean package
 5) copy nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT.jar and
    nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT-sources.jar to your buck/third-party/java/nailgun directory
 6) copy nailgun-client/ng.c to your buck/third-party/nailgun/nailgun-client directory
