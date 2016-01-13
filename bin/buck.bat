@echo off
set PYTHONPATH=%~dp0..\third-party\nailgun
python %~dp0..\programs\buck.py %*
