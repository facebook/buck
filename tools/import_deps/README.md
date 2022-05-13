## Import Gradle Dependencies to BUCK

The tool is designed to help with migrating from gradle to buck.
It imports all dependencies defined in a build.gradle and generates BUCK files.

Steps:

1. Create a dependencies file:

```
./gradlew -q :app:dependencies --configuration debugRuntimeClasspath >> report_file.txt
./gradlew -q :app:dependencies --configuration debugRuntimeClasspath >> report_file.txt
./gradlew -q :app:dependencies --configuration debugAnnotationProcessorClasspath >> report_file.txt
```

The gradle build resolves versions. The tool will import the packages of the versions
preferred by gradle.

2. Run import dependencies script:

Activate python virtual environment:
source venv/bin/activate

```
./importdeps.py --gdeps report_file.txt --libs third-party
```

The tool will import dependencies and generate BUCK files.

3. Test generated BUCK file:

```
buck targets //third-party:
```

4. Use imported targets in your project. 
