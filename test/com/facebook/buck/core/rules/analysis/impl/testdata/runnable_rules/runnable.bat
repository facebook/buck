@echo off

echo pwd: %cd%
echo ENV: %CUSTOM_ENV%
echo EXIT_CODE: %EXIT_CODE%
for %%a in (%*) do (
    echo arg[%%a]
  )

if "%EXIT_CODE%"=="" (exit /B 0)
exit /B %EXIT_CODE%
