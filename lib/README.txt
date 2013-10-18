emma-2.0.5312.jar was taken from http://emma.sourceforge.net/. 

The version is not the latest but the latest version is in a folder called emma_testing 
which might be an unstable version. Also the new version's main feature is to be able 
to recover code coverage data from a JVM without the JVM exiting. This is not a 
critical feature that we need for our purposes. For download go to:

http://downloads.sourceforge.net/project/emma/emma-release/2.0.5312/emma-2.0.5312-lib.zip


nailgun-server-0.9.2-SNAPSHOT.jar and nailgun-server-0.9.2-SNAPSHOT-sources.jar were
built from a fork of Nailgun which adds support for detecting client disconnection at
https://github.com/jimpurbrick/nailgun.git

To regenerate these jars:

 0) install maven (brew install maven)
 1) git clone https://github.com/jimpurbrick/nailgun.git
 2) cd nailgun
 3) git checkout d005c16f13d42489ac1ab428b15c3d9cdb1ada31
 4) mvn clean install
 5) copy nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT.jar and
    nailgun-server/target/nailgun-server-0.9.2-SNAPSHOT-sources.jar to your buck/lib directory
