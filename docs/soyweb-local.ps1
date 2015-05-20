# Unfortunately, plovr has a bug where we cannot override values in globals.json
# from the command line. Instead, we create a copy of globals.json with the
# modifications we need to run soyweb locally and use that.
try {
  $tempfile = "$env:TEMP\temp" + ("{0:d5}" -f (Get-Random)).Substring(0,5) +
    "globals.json"
  Get-Content docs/globals.json |
    ForEach-Object { $_ -replace '/buck', '' } |
    Out-File $tempfile -encoding ASCII
  java -jar docs\plovr-81ed862.jar soyweb --dir docs --globals $tempfile
}
finally {
  Remove-Item $tempfile
}
