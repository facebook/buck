# Copyright 2015-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import contextlib
import os
import shutil
import subprocess
import zipfile

from optparse import OptionParser


if __name__ == "__main__":
    parser = OptionParser()
    parser.add_option("--lib", action="store", dest="lib", default="")
    (options, args) = parser.parse_args()
    tmp = args[0]
    output = args[1]

    # Write out AndroidManifest.xml
    with open(os.path.join(tmp, "AndroidManifest.xml"), "w") as f:
        f.write("""<manifest
  xmlns:android='http://schemas.android.com/apk/res/android'
  package='com.example'
/>""")

    # Write out a resource
    os.makedirs(os.path.join(tmp, "res", "values"))
    with open(os.path.join(tmp, "res", "values", "strings.xml"), "w") as f:
        f.write("""<?xml version='1.0' encoding='utf-8' ?>
<resources>
  <string name='app_name'>Hello World</string>
</resources>""")

    # Include some .class files in classes.jar because the .aar spec requires it
    with open(os.path.join(tmp, "Utils.java"), "w") as f:
        f.write("""package com.example;
public class Utils {
  public static String capitalize(String str) {
    return str.substring(0, 1).toUpperCase() + str.substring(1);
  }
}""")
    os.makedirs(os.path.join(tmp, "classes"))
    subprocess.check_call(
        ["javac", "-source", "1.7", "-target", "1.7", "-d", "classes", "Utils.java"],
        cwd=tmp)
    subprocess.check_call(
        ["jar", "-cf", "classes.jar", "-C", "classes", "."],
        cwd=tmp)
    os.remove(os.path.join(tmp, "Utils.java"))
    shutil.rmtree(os.path.join(tmp, "classes"))

    # Include some .so in jni/ folder
    os.makedirs(os.path.join(tmp, "jni", "x86"))
    with open(os.path.join(tmp, "jni", "x86", "liba.so"), "w") as f:
        f.write("Empty")

    if options.lib:
        dir = os.path.join(tmp, "libs")
        os.makedirs(dir)
        shutil.copyfile(options.lib, os.path.join(dir, "lib.jar"))

    # Note that we do not include an R.txt file, even though it is required by the .aar spec.
    # Currently, Buck does not check for its existence.

    files = [f for f in os.listdir(tmp) if os.path.isfile(os.path.join(tmp, f))]
    with contextlib.closing(zipfile.ZipFile(output, "w")) as z:
        for (dir, _, files) in os.walk(tmp):
            for f in files:
                z.write(os.path.join(tmp, dir, f), os.path.relpath(os.path.join(dir, f), tmp))
