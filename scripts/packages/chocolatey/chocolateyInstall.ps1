# We use a .bat file instead of .ps1 because the chocolatey shim lib does not
# properly execute .ps1 files, but it does .bat files
Install-BinFile -Name buck -Path "${env:ChocolateyPackageFolder}\tools\buck.bat"
