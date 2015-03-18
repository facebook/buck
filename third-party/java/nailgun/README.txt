nailgun-server-0.9.2-SNAPSHOT.jar and nailgun-server-0.9.2-SNAPSHOT-sources.jar were
built from https://github.com/martylamb/nailgun.git

To regenerate these jars:

 0) install maven (brew install maven)
 1) git clone https://github.com/martylamb/nailgun
 2) cd nailgun
 3) git checkout df35649c56b793f9a37e6beb02b0d0dee57b4fda
 4) mvn clean install
 5) copy nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT.jar and
    nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT-sources.jar to your buck/lib directory
