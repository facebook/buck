:: Copyright 2018-present Facebook, Inc.
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
@setlocal
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
