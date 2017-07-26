nailgun-server-0.9.2-SNAPSHOT.jar and nailgun-server-0.9.2-SNAPSHOT-sources.jar were
built from https://github.com/martylamb/nailgun

To regenerate these jars:

 0) install maven (brew install maven)
 1) git clone https://github.com/martylamb/nailgun
 2) cd nailgun
 3) git checkout e7fa6c7e4fc3e4acf049b2ec2f02373e96e848d2
 4) mvn clean package
 5) copy nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT.jar and
    nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT-sources.jar to your buck/third-party/java/nailgun directory
