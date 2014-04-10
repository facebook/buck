:: Copyright 2013-present Facebook, Inc.
::
:: Licensed under the Apache License, Version 2.0 (the "License"); you may
:: not use this file except in compliance with the License. You may obtain
:: a copy of the License at
::
::     http://www.apache.org/licenses/LICENSE-2.0
::
:: Unless required by applicable law or agreed to in writing, software
:: distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
:: WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
:: License for the specific language governing permissions and limitations
:: under the License.

:: FIXME (carbokuo) This script is migrated from bin/buck and bin/buck_common and still very incomplete.

@echo off

:: Check %JAVA_HOME%
if "%JAVA_HOME%"=="" (
  echo Please have JDK 1.7 or higher installed and set environment variable %%JAVA_HOME%%.
  exit /b
)
:: Check java
set JAVA=%JAVA_HOME%\bin\java.exe
if not exist "%JAVA%" (
  echo java.exe not found under "%JAVA_HOME%\bin". Please check your environment variable %%JAVA_HOME%%.
  exit /b
)

set ORIGINAL_WORKING_DIRECTORY=%cd%
set PROJECT_ROOT=%cd%
set BUCK_BIN_DIRECTORY=%~dp0
cd %BUCK_BIN_DIRECTORY%\..
set BUCK_DIRECTORY=%cd%
cd %ORIGINAL_WORKING_DIRECTORY%

set BUCKD_DIR=%PROJECT_ROOT%\.buckd
set BUCKD_LOG_FILE=%PROJECT_ROOT%\buckd.log
set BUCKD_PID_FILE=%PROJECT_ROOT%\buckd.pid
set BUCKD_PORT_FILE=%PROJECT_ROOT%\buckd.port
set BUCKD_RUNNING=0

if exist "%PROJECT_ROOT%\.buckversion" (
  if not exist "%PROJECT_ROOT%\.nobuckcheck" (
    set /p BUCK_REQUIRED_VERSION=<%PROJECT_ROOT%\.buckversion
    rem TODO checkout the required version
  )
)

set BUCK_REPOSITORY_DIRTY=0
set BUCK_CURRENT_VERSION=N/A
set BUCK_VERSION_TIMESTAMP=-1
set BUCK_GIT_DIRECTORY=%BUCK_DIRECTORY%\.git

:: The following commands are to retrieve output from external commands.
:: Because the second following command contains % (percent sign), it could not be executed in a for /f statement.
:: The workaround is to write the output of the command into a temp file and read it.
:: The name of the temp file contains a random number in order to avoid race condition.
set BUCK_VERSION_TIMESTAMP_TEMPFILE=%TEMP%\BUCK_VERSION_TIMESTAMP_%RANDOM%.tmp
if exist "%BUCK_GIT_DIRECTORY%" (
  for /f %%i in ('git --git-dir %BUCK_GIT_DIRECTORY% rev-parse HEAD') do set BUCK_CURRENT_VERSION=%%i
  git --git-dir %BUCK_GIT_DIRECTORY% log --pretty=format:%%ct -1 HEAD >%BUCK_VERSION_TIMESTAMP_TEMPFILE%
  set /p BUCK_VERSION_TIMESTAMP=<%BUCK_VERSION_TIMESTAMP_TEMPFILE%
)

set BUCK_VERSION_UID=N/A
:: TODO (carbokuo) Retrieve BUCK_VERSION_UID

:: Path to Python interpreter will be tried to find. If not found, Jython will be used.
set PYTHON_INTERP_FALLBACK=%BUCK_BIN_DIRECTORY%\jython.cmd

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
-Dbuck.path_to_python_interp=%PYTHON_INTERP_FALLBACK% ^
-Dbuck.path_to_buck_py=%PATH_TO_BUCK_PY% ^
-Dbuck.path_to_intellij_py=%BUCK_DIRECTORY%\src\com\facebook\buck\command\intellij.py ^
-Dbuck.path_to_compile_asset_catalogs_py=%BUCK_DIRECTORY%\src\com\facebook\buck\apple\compile_asset_catalogs.py ^
-Dbuck.path_to_compile_asset_catalogs_build_phase_sh=%BUCK_DIRECTORY%\src\com\facebook\buck\apple\compile_asset_catalogs_build_phase_sh ^
-Dbuck.git_commit=%BUCK_CURRENT_VERSION% ^
-Dbuck.git_commit_timestamp=%BUCK_VERSION_TIMESTAMP% ^
-Dbuck.git_dirty=%BUCK_REPOSITORY_DIRTY% ^
-Dbuck.quickstart_origin_dir=%BUCK_DIRECTORY%\src\com\facebook\buck\cli\quickstart\android ^
-Dbuck.version_uid=%BUCK_VERSION_UID% ^
-Dbuck.buckd_dir=%BUCKD_DIR% ^
-Dlog4j.configuration=file:%BUCK_DIRECTORY%\config\log4j.properties

set BUCK_JAVA_CLASSPATH=^
%BUCK_DIRECTORY%\src;^
%BUCK_DIRECTORY%\build\classes;^
%BUCK_DIRECTORY%\lib\args4j-2.0.26.jar;^
%BUCK_DIRECTORY%\lib\ddmlib-22.5.3.jar;^
%BUCK_DIRECTORY%\lib\guava-15.0.jar;^
%BUCK_DIRECTORY%\lib\ini4j-0.5.2.jar;^
%BUCK_DIRECTORY%\lib\jackson-annotations-2.0.5.jar;^
%BUCK_DIRECTORY%\lib\jackson-core-2.0.5.jar;^
%BUCK_DIRECTORY%\lib\jackson-databind-2.0.5.jar;^
%BUCK_DIRECTORY%\lib\jsr305.jar;^
%BUCK_DIRECTORY%\lib\nailgun-server-0.9.2-SNAPSHOT.jar;^
%BUCK_DIRECTORY%\lib\sdklib.jar;^
%BUCK_DIRECTORY%\third-party\java\asm\asm-debug-all-4.1.jar;^
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
%BUCK_DIRECTORY%\third-party\java\gson\gson-2.2.4.jar;^
%BUCK_DIRECTORY%\third-party\java\jetty\jetty-all-9.0.4.v20130625.jar;^
%BUCK_DIRECTORY%\third-party\java\jetty\servlet-api.jar;^
%BUCK_DIRECTORY%\third-party\java\xz-java-1.3\xz-1.3.jar

"%JAVA%" ^
  -classpath %BUCK_JAVA_CLASSPATH% ^
  %BUCK_JAVA_ARGS% ^
  -Dbuck.daemon=false ^
  com.facebook.buck.cli.Main ^
  %*
