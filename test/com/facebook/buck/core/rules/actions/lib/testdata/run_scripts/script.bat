@echo off

echo Message on stdout
echo Message on stderr 1>&2

for %%a in (%*) do (
   echo arg[%%a] 1>&2
)
echo PWD: %PWD% 1>&2
echo CUSTOM_ENV: %CUSTOM_ENV% 1>&2

if "%EXIT_CODE%"=="" (exit /B 0)
exit /B %EXIT_CODE%
