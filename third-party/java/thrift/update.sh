#!/usr/bin/env bash -ex
old=$(cat third-party/java/thrift/version)
version=$(thrift -version | sed 's/Thrift version //')

rm third-party/java/thrift/*.jar || true

curl https://repo1.maven.org/maven2/org/apache/thrift/libthrift/$version/libthrift-$version.jar > third-party/java/thrift/libthrift-$version.jar
curl https://repo1.maven.org/maven2/org/apache/thrift/libthrift/$version/libthrift-$version-sources.jar > third-party/java/thrift/libthrift-$version-sources.jar

files_to_update='build.xml programs/classpaths .idea/libraries/buck_lib.xml third-party/java/thrift/BUCK'
for f in $files_to_update; do
  sed -i '' "s/libthrift-$old/libthrift-$version/g" $f
done

./third-party/java/thrift/gen.sh

echo $version > third-party/java/thrift/version
git add third-party/java/thrift

