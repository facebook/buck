manifest-merger 25.2.0

== Steps

1) Downloaded following sources from Maven central:
com.android.tools.build:manifest-merger:25.2.0
com.android.tools:common:25.2.0
com.android.tools:annotations:25.2.0
com.android.tools:sdk-common:25.2.0
com.android.tools:sdklib:25.2.0
com.android.tools.layoutlib:layoutlib-api:25.2.0
2) Copied com.android.manifmerger.ManifestMerger2 and all required dependencies to the aosp/ module.
3) In the Android SDK's source code manifmerger, sdklib and utils are separate modules. These are
copied into a single module which alters certain package paths. For example,
com.android.SDKConstants becomes com.android.common.SDKConstants. This is done to avoid conflicts
with different version of those libraries in //third-party/java/android


== How to build apksig.jar
1) Download source code from Google Open Source https://android.googlesource.com/platform/tools/apksig/+/master/src/main/java/com/android/apksig
2) Navigate into src/ directory
3) Build class tree by running
  > javac -d ./build com/android/apksig/*.java
4) Navigate into build/ directory
5) Check that class files are built successfully
6) Compile jar file by running
  > jar cvf apksig.jar *
7) Check that apksig.jar is successfully compiled in current directory
