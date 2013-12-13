# This was created by running:

wget https://jarjar.googlecode.com/files/jarjar-1.4.jar
# Verify this is d87f53c99e4cd88f5416edd5abb77f2a1ccfb050.
shasum jarjar-1.4.jar

wget https://closure-templates.googlecode.com/files/closure-templates-for-java-2012-12-21.zip
# Verify this is 6f670af115b89d1fb26b291efa502717f5c98e96.
shasum closure-templates-for-java-2012-12-21.zip

unzip closure-templates-for-java-2012-12-21.zip
java -jar jarjar-1.4.jar process jarjar-rules.txt soy-2012-12-21.jar soy-2012-12-21-no-guava.jar
