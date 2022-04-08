# Copyright (c) Meta Platforms, Inc. and affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Use the strictest mode
Set-StrictMode -Version latest
# Make sure cmdlet stop executing when they run into an error
$PSDefaultParameterValues['*:ErrorAction']='Stop'
$ErrorActionPreference = "Stop"

# Strips "Microsoft.PowerShell.Core\FileSystem" prefix if it exists
$pwd_dir = Convert-Path $pwd
if ($pwd_dir.StartsWith("\\?\")) {
  # Removes "\\?\" prefix - with this prefix java fails with LinkageError. I don't
  # understand the root cause of that yet, but for now let's strip the prefix.
  cd $pwd_dir.substring(4)
}

function resolveList() {
  param (
    $value
  )

  return [string]::Join(":", ($value.Split(":") | ForEach-Object { echo "$pwd\$_" }))
}

function resolve() {
  param (
    $value
  )

  return "$pwd\$value"
}

$env:BUCK_CLASSPATH = resolveList -value "$env:BUCK_CLASSPATH"
$env:BUCK_CLASSPATH_EXTRA = resolveList -value "$env:BUCK_CLASSPATH_EXTRA"
$CLASSPATH = resolveList -value $env:CLASSPATH
$BUCK_ISOLATED_ROOT = $PWD
$BUCK_PLUGIN_RESOURCES = resolve -value $env:BUCK_PLUGIN_RESOURCES
$BUCK_PLUGIN_ROOT = resolve -value $env:BUCK_PLUGIN_ROOT

# this env variable is used in some rules to check if build is running using BUCK
$env:BUCK_BUILD_ID = "RE_buck_build_id"

$BUCK_DEBUG_ARGS = ""
if ("$env:BUCK_DEBUG_MODE" -eq "1") {
  $BUCK_DEBUG_ARGS = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:8888"
}

cd $args[0]

if ($env:BUCK_JAVA_VERSION -eq "11") {
  $javaPath = "C:\Program Files\Java\jdk-11.0.2\bin\java.exe"
} elseif ($env:BUCK_JAVA_VERSION -eq "8") {
  $javaPath = "C:\Program Files\Java\jre1.8.0_311\bin\java.exe"
} else {
  echo "Unsupported Java version - $env:BUCK_JAVA_VERSION"
  exit 1
}

# Check that java file exists
if (! (Test-Path -Path $javaPath)) {
  echo "Java $env:BUCK_JAVA_VERSION is not installed."
  exit 127
}

& "$javaPath" -cp $CLASSPATH `
  $BUCK_DEBUG_ARGS `
  $env:EXTRA_JAVA_ARGS.Split(" ") `
  -Xverify:none -XX:+TieredCompilation -XX:TieredStopAtLevel=1 `
  "-Dpf4j.pluginsDir=$BUCK_PLUGIN_ROOT" `
  "-Dbuck.module.resources=$BUCK_PLUGIN_RESOURCES" `
  "-Dbuck.base_buck_out_dir=$env:BASE_BUCK_OUT_DIR" `
  com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper `
  com.facebook.buck.rules.modern.builders.OutOfProcessIsolatedBuilder `
  $BUCK_ISOLATED_ROOT $args

if (! $?) {
  Write-Error "Failed!"
}