@echo off
REM Invoke buck.pex in the same directory as this script with python
REM We try to find a python2 installation first because python3 can
REM install itself as 'python.exe' on the path, and we don't want that
REM just yet
if exist "C:\Python27\python.exe" (
  C:\Python27\python.exe %~dp0\buck.pex %*
) else (
  python %~dp0\buck.pex %*
)
