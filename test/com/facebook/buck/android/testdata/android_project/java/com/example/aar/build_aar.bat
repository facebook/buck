@echo off

set OUT=%1
set BUILD_AAR=%2\build_aar
set MANIFEST=%3
set PRIMARY=%4
set DEP=%5

mkdir %BUILD_AAR%
copy %MANIFEST% %BUILD_AAR% >nul
copy %PRIMARY% %BUILD_AAR%\classes.jar >nul
mkdir %BUILD_AAR%\libs
copy %DEP% %BUILD_AAR%\libs >nul
mkdir %BUILD_AAR%\res
mkdir %BUILD_AAR%\res\values

set STRINGS=%BUILD_AAR%\res\values\strings.xml
echo ^<?xml version="1.0" encoding="utf-8" ^?^> > %STRINGS%
echo ^<resources^> >> %STRINGS%
echo   ^<string name="app_name"^>Sample App^</string^> >> %STRINGS%
echo   ^<string name="base_button"^>Hello world!^</string^> >> %STRINGS%
echo ^</resources^> >> %STRINGS%
jar cf %OUT% -C %BUILD_AAR% .
