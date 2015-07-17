@echo off
REM Copyright (C) 2007 The Android Open Source Project
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

REM Locate dx.jar in the directory where dx.bat was found and start it.

REM Set up prog to be the path of this script, including following symlinks,
REM and set up progdir to be the fully-qualified pathname of its directory.
set prog=%~f0

rem Check we have a valid Java.exe in the path.
set java_exe=
if exist    "%~dp0..\tools\lib\find_java.bat" call    "%~dp0..\tools\lib\find_java.bat"
if exist "%~dp0..\..\tools\lib\find_java.bat" call "%~dp0..\..\tools\lib\find_java.bat"
if not defined java_exe goto :EOF

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

set javaOpts=
set args=

REM By default, give dx a max heap size of 1 gig and a stack size of 1meg.
rem This can be overridden by using "-JXmx..." and "-JXss..." options below.
set defaultXmx=-Xmx1024M
set defaultXss=-Xss1m

REM Capture all arguments that are not -J options.
REM Note that when reading the input arguments with %1, the cmd.exe
REM automagically converts --name=value arguments into 2 arguments "--name"
REM followed by "value". Dx has been changed to know how to deal with that.
set params=

:firstArg
if [%1]==[] goto endArgs
set a=%~1

    if [%defaultXmx%]==[] goto notXmx
    if %a:~0,5% NEQ -JXmx goto notXmx
        set defaultXmx=
    :notXmx

    if [%defaultXss%]==[] goto notXss
    if %a:~0,5% NEQ -JXss goto notXss
        set defaultXss=
    :notXss

    if %a:~0,2% NEQ -J goto notJ
        set javaOpts=%javaOpts% -%a:~2%
        shift /1
        goto firstArg

    :notJ
    set params=%params% %1
    shift /1
    goto firstArg

:endArgs

set javaOpts=%javaOpts% %defaultXmx% %defaultXss%
call "%java_exe%" %javaOpts% -Djava.ext.dirs="%frameworkdir%" -jar "%jarpath%" %params%

