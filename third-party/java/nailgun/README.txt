nailgun-server-0.9.2-SNAPSHOT.jar and nailgun-server-0.9.2-SNAPSHOT-sources.jar were
built from https://github.com/bhamiltoncx/nailgun

To regenerate these jars:

 0) install maven (brew install maven)
 1) git clone https://github.com/bhamiltoncx/nailgun
 2) cd nailgun
 3) git checkout c2788d68d67247a1b30ef7a857027f9b877827ea
 4) mvn clean package
 5) copy nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT.jar and
    nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT-sources.jar to your buck/third-party/java/nailgun directory
 6) copy nailgun-client/ng.c to your buck/third-party/nailgun/nailgun-client directory
