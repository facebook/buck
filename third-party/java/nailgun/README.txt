nailgun-server-0.9.2-SNAPSHOT.jar and nailgun-server-0.9.2-SNAPSHOT-sources.jar were
built from https://github.com/martylamb/nailgun

To regenerate these jars:

 0) install maven (brew install maven)
 1) git clone https://github.com/martylamb/nailgun
 2) cd nailgun
 3) git checkout 7adb931c4f3d6d72f6c775afe3cea52d6b380eeb
 4) mvn clean package
 5) copy nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT.jar and
    nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT-sources.jar to your buck/third-party/java/nailgun directory
 6) copy nailgun-client/ng.c to your buck/third-party/nailgun/nailgun-client directory
