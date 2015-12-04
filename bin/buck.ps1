# Invoke the buck wrappers scripts with Python.
$env:PYTHONPATH=(Join-Path (Join-Path (Split-Path (Split-Path $PSCommandPath)) third-party) nailgun)
python (Join-Path (Join-Path (Split-Path (Split-Path $PSCommandPath)) programs) buck.py) $args
