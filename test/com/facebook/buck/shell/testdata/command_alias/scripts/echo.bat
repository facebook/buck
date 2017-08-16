@echo off
dir %~df1 /B /ON
shift
:loop
if "%~1"=="" goto loop_end
echo %~1
shift
goto loop
:loop_end
echo %ENV_A% %ENV_B%
