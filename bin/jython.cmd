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

:: This script is migrated from bash script. See bin/jython for more information. 

@echo off
set BUCK_BIN_DIRECTORY=%~dp0
set BUCK_DIRECTORY=%BUCK_BIN_DIRECTORY%\..

set CLASS_PATH=%BUCK_DIRECTORY%\lib\jython-standalone-2.5.4-rc1.jar;%BUCK_DIRECTORY%\lib\jyson-1.0.2.jar
set MAIN_CLASS=org.python.util.jython
set JAVA_OPTS=

:: TODO (carbokuo) Pass -J parameters to Java

java -cp %CLASS_PATH% %JAVA_OPTS% %MAIN_CLASS% %*
