nailgun-server-1.0.0.jar and nailgun-server-1.0.0-sources.jar were
built from https://github.com/facebook/nailgun

To regenerate these jars:

 0) install maven (brew install maven)
 1) git clone https://github.com/facebook/nailgun
 2) cd nailgun
 3) git checkout origin/master or any other revision you want to build nailgun from
 4) mvn clean package
 5) copy nailgun-server/target/nailgun-server-1.0.0.jar and
    nailgun-server/target/nailgun-server-1.0.0-sources.jar to your buck/third-party/java/nailgun directory
 6) update VERSION file with the current hash of facebook/nailgun (from which nailgun was built)
