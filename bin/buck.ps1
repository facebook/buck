# Invoke the buck wrappers scripts with Python.
python (Join-Path (Join-Path (Split-Path (Split-Path $PSCommandPath)) programs) buck.py) $args
