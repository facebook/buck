@echo off
REM Copyright (C) 2013 The Android Open Source Project
REM
REM Licensed under the Apache License, Version 2.0 (the "License");
REM you may not use this file except in compliance with the License.
REM You may obtain a copy of the License at
REM
REM     http://www.apache.org/licenses/LICENSE-2.0
REM
REM Unless required by applicable law or agreed to in writing, software
REM distributed under the License is distributed on an "AS IS" BASIS,
REM WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
REM See the License for the specific language governing permissions and
REM limitations under the License.

REM don't modify the caller's environment
setlocal

rem Check we have a valid Java.exe in the path.
set java_exe=
if exist    "%~dp0..\tools\lib\find_java.bat"    call    "%~dp0..\tools\lib\find_java.bat"
if exist    "%~dp0..\..\tools\lib\find_java.bat" call    "%~dp0..\..\tools\lib\find_java.bat"
if not defined java_exe goto :EOF

set baserules="%~dp0\mainDexClasses.rules"

REM Locate dx.jar in the directory where dx.bat was found.
set jarfile=dx.jar
set "frameworkdir=%~dp0"
rem frameworkdir must not end with a dir sep.
set "frameworkdir=%frameworkdir:~0,-1%"
if exist "%frameworkdir%\%jarfile%" goto JarFileOk
    set "frameworkdir=%~dp0lib"

if exist "%frameworkdir%\%jarfile%" goto JarFileOk
    set "frameworkdir=%~dp0..\framework"
:JarFileOk

set "jarpath=%frameworkdir%\%jarfile%"

set "shrinkedAndroidJar=%SHRINKED_ANDROID_JAR%
if exist "%shrinkedAndroidJar%" goto shrinkedAndroidOk
    set "shrinkedAndroidJar=shrinkedAndroid.jar"

if exist "%shrinkedAndroidJar%" goto shrinkedAndroidOk
    set "shrinkedAndroidJar=%frameworkdir%\%shrinkedAndroidJar%"

:shrinkedAndroidOk
set "proguardExec=proguard.bat"
set "proguard=%PROGUARD_HOME%\bin\%proguardExec%"

if exist "%proguard%" goto proguardOk
REM set proguard location for the SDK case
    set "PROGUARD_HOME=%~dp0\..\..\tools\proguard"
    set "proguard=%PROGUARD_HOME%\bin\%proguardExec%"

if exist "%proguard%" goto proguardOk
REM set proguard location for the Android tree case
    set "PROGUARD_HOME=%~dp0\..\..\..\..\external\proguard"
    set "proguard=%PROGUARD_HOME%\bin\%proguardExec%"

:proguardOk
REM Capture all arguments.
REM Note that when reading the input arguments with %1, the cmd.exe
REM automagically converts --name=value arguments into 2 arguments "--name"
REM followed by "value". Dx has been changed to know how to deal with that.
set params=

set output=

:firstArg
if [%1]==[] goto endArgs

    if %1 NEQ --output goto notOut
        set "output=%2"
        shift
        shift
        goto firstArg

:notOut
    if defined params goto usage
    set params=%1
    shift
    goto firstArg

:endArgs
if defined params ( goto makeTmpJar ) else ( goto usage )

:makeTmpJar
set "tmpJar=%TMP%\mainDexClasses-%RANDOM%.tmp.jar"
if exist "%tmpJar%" goto makeTmpJar
echo "" > "%tmpJar%"
set "exitStatus=0"


call "%proguard%" -injars %params% -dontwarn -forceprocessing  -outjars "%tmpJar%" -libraryjars "%shrinkedAndroidJar%" -dontoptimize -dontobfuscate -dontpreverify -include "%baserules%" 1>nul

if DEFINED output goto redirect
call "%java_exe%" -Djava.ext.dirs="%frameworkdir%" com.android.multidex.ClassReferenceListBuilder "%tmpJar%" "%params%"
goto afterClassReferenceListBuilder
:redirect
call "%java_exe%" -Djava.ext.dirs="%frameworkdir%" com.android.multidex.ClassReferenceListBuilder "%tmpJar%" "%params%" 1>"%output%"
:afterClassReferenceListBuilder

del %tmpJar%
exit /b

:usage
echo "Usage : %0 [--output <output file>] <application path>"
exit /b 1
