VCS: git
URL: https://github.com/google/closure-templates.git
Revision: aac4fd30a73114cdf11cace696985c7d24d08ac7

To update:

ant jar-excluding-deps
cp build/soy-excluding-deps.jar ~/devtools/buck/third-party/java/closure-templates/

You can build the source jar by zipping the java/src directory.
