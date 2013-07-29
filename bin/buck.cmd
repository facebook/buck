@echo off
set ORIGINAL_WORKING_DIRECTORY=%cd%
set PROJECT_ROOT=%cd%
set BUCK_BIN_DIRECTORY=%~dp0
cd %BUCK_BIN_DIRECTORY%\..
set BUCK_DIRECTORY=%cd%

set BUCKD_DIR=%PROJECT_ROOT%\.buckd
set BUCKD_LOG_FILE=%PROJECT_ROOT%\buckd.log
set BUCKD_PID_FILE=%PROJECT_ROOT%\buckd.pid
set BUCKD_PORT_FILE=%PROJECT_ROOT%\buckd.port
set BUCKD_RUNNING=0
rem kill -0 `cat "$BUCKD_PID_FILE" 2> /dev/null` &> /dev/null || BUCKD_RUNNING=1

if not exist %PROJECT_ROOT%\.buckversion goto SET_BUCK_REQUIRED_VERSION_END
  if exist %PROJECT_ROOT%\.nobuckcheck goto SET_BUCK_REQUIRED_VERSION_END
  set /p BUCK_REQUIRED_VERSION=<%PROJECT_ROOT%\.buckversion
  rem TODO checkout the required version
:SET_BUCK_REQUIRED_VERSION_END

set BUCK_REPOSITORY_DIRTY=0
set BUCK_CURRENT_VERSION=N/A
set BUCK_VERSION_TIMESTAMP=-1
if not exist .git goto GET_BUCK_VERSION_END
  git rev-parse HEAD >%TEMP%\BUCK_CURRENT_VERSION
  set /p BUCK_CURRENT_VERSION=<%TEMP%\BUCK_CURRENT_VERSION
  git log --pretty=format:%%ct -1 HEAD >%TEMP%\BUCK_VERSION_TIMESTAMP
  set /p BUCK_VERSION_TIMESTAMP=<%TEMP%\BUCK_VERSION_TIMESTAMP
:GET_BUCK_VERSION_END

set BUCK_VERSION_UID=N/A

set DEFAULT_PYTHON_INTERP=python
set PYTHON_INTERP=%DEFAULT_PYTHON_INTERP%

set RELATIVE_PATH_TO_BUCK_PY=src/com/facebook/buck/parser/buck.py
set PATH_TO_BUCK_PY=%BUCK_DIRECTORY%\%RELATIVE_PATH_TO_BUCK_PY%

set BUCK_JAVA_ARGS=^
-XX:MaxPermSize=256m ^
-Xmx1000m ^
-Djava.awt.headless=true ^
-Dbuck.testrunner_classes=%BUCK_DIRECTORY%\build\testrunner\classes ^
-Dbuck.abi_processor_classes=%BUCK_DIRECTORY%\build\abi_processor\classes ^
-Dbuck.path_to_emma_jar=%BUCK_DIRECTORY%\third-party\java\emma-2.0.5312\out\emma-2.0.5312.jar ^
-Dbuck.test_util_no_tests_dir=true ^
-Dbuck.path_to_python_interp=%PYTHON_INTERP% ^
-Dbuck.path_to_buck_py=%PATH_TO_BUCK_PY% ^
-Dbuck.path_to_intellij_py=%BUCK_DIRECTORY%\src\com\facebook\buck\command\intellij.py ^
-Dbuck.git_commit=%BUCK_CURRENT_VERSION% ^
-Dbuck.git_commit_timestamp=%BUCK_VERSION_TIMESTAMP% ^
-Dbuck.git_dirty=%BUCK_REPOSITORY_DIRTY% ^
-Dbuck.version_uid=%BUCK_VERSION_UID% ^
-Dbuck.buckd_dir=%BUCKD_DIR% ^
-Dlog4j.configuration=file:%BUCK_DIRECTORY%\config\log4j.properties

set BUCK_JAVA_CLASSPATH=^
%BUCK_DIRECTORY%\src;^
%BUCK_DIRECTORY%\build\classes;^
%BUCK_DIRECTORY%\lib\args4j.jar;^
%BUCK_DIRECTORY%\lib\guava-14.0.1.jar;^
%BUCK_DIRECTORY%\lib\ini4j-0.5.2.jar;^
%BUCK_DIRECTORY%\lib\jackson-annotations-2.0.5.jar;^
%BUCK_DIRECTORY%\lib\jackson-core-2.0.5.jar;^
%BUCK_DIRECTORY%\lib\jackson-databind-2.0.5.jar;^
%BUCK_DIRECTORY%\lib\jsr305.jar;^
%BUCK_DIRECTORY%\lib\sdklib.jar;^
%BUCK_DIRECTORY%\lib\ddmlib-r21.jar;^
%BUCK_DIRECTORY%\lib\sdklib.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\astyanax-cassandra-1.56.38.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\astyanax-core-1.56.38.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\astyanax-thrift-1.56.38.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\cassandra-1.2.3.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\cassandra-thrift-1.2.3.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\commons-cli-1.1.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\commons-codec-1.2.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\commons-lang-2.6.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\high-scale-lib-1.1.2.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\joda-time-2.2.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\libthrift-0.7.0.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\log4j-1.2.16.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\slf4j-api-1.7.2.jar;^
%BUCK_DIRECTORY%\third-party\java\astyanax\slf4j-log4j12-1.7.2.jar;^
%BUCK_DIRECTORY%\third-party\java\xz-java-1.3\xz-1.3.jar

java ^
  -classpath %BUCK_JAVA_CLASSPATH% ^
  %BUCK_JAVA_ARGS% ^
  -Dbuck.daemon=false ^
  com.facebook.buck.cli.Main ^
  %*

cd %ORIGINAL_WORKING_DIRECTORY%
