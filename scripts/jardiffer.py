#!/usr/bin/env python
# Copyright 2018-present Facebook, Inc.
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


# Diffing two jar files can be a surprisingly subtle task.  It is possible
# for jar files to differ in packaging, but have the same content (examples:
# when they are zipped using different compresssion levels, or if the
# timestamps for entries differ, or if the ordering inside the jar is
# different.
#
# A common trick to diff two jar files is to unzip their contents to a
# directory and recursively diff the two directories, but this is subtly
# tricky: zip files may have duplicate entries with the same name, in
# which case one must be careful not to allow later entries to overwrite
# earlier entries, since the default Java classloader will only see the
# first entry in the zipfile.

from __future__ import print_function

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile


class JarDiffer(object):
    def __init__(self, args):
        self.parse_args(args)
        self.tmpdir = tempfile.mkdtemp()

    def __enter__(self):
        return self

    def __exit__(self, type, value, traceback):
        shutil.rmtree(self.tmpdir)

    def parse_args(self, args):
        parser = argparse.ArgumentParser(
            prog=args[0],
            description="Compares the contents of two jar files.",
            add_help=True,
        )
        parser.add_argument(
            "-d",
            "--diff-flags",
            help="Flags to pass to diff when comparing non-class files",
            default="-d",
        )
        parser.add_argument(
            "-p",
            "--javap-flags",
            help="Flags to pass to javap when comparing class files",
            default="-p -s -sysinfo",
        )
        parser.add_argument(
            "-r",
            "--raw-javap-output",
            help="Show raw (unsanitized) javap output.",
            action="store_true",
        )
        parser.add_argument(
            "-o",
            "--output",
            help="File to direct output (default: stdout)",
            type=argparse.FileType("w", 0),
            default=sys.stdout,
        )
        parser.add_argument("jarfile", help="The jar files to compare", nargs=2)
        options = parser.parse_args(args[1:])
        self.jar1 = options.jarfile[0]
        self.jar2 = options.jarfile[1]
        self.javap_flags = [f for f in options.javap_flags.split() if f]
        self.should_sanitize_javap = not options.raw_javap_output
        self.diff_flags = [f for f in options.diff_flags.split() if f]
        self.output = options.output

    def write_contents(self, index, entry_name, entry_contents):
        dest = os.path.join(self.tmpdir, str(index), entry_name)
        if not os.path.exists(os.path.dirname(dest)):
            os.makedirs(os.path.dirname(dest))
        with open(dest, "wb") as f:
            f.write(entry_contents)
            return os.path.join(str(index), entry_name)

    @staticmethod
    def sanitize_javap_output(content):
        content = re.sub(r"\d+:", "_:", content)
        content = re.sub(r"#\d+", "#_", content)
        return content

    def javap(self, index, entry_name, entry_contents):
        flags = self.javap_flags
        filename = self.write_contents(index, entry_name, entry_contents)
        cmd = ["javap"] + flags + [filename]
        output = subprocess.check_output(cmd, cwd=self.tmpdir)
        if self.should_sanitize_javap:
            output = JarDiffer.sanitize_javap_output(output)
        return output

    def diff_content(self, entry_name, entry_contents1, entry_contents2):
        filename1 = self.write_contents(1, entry_name, entry_contents1)
        filename2 = self.write_contents(2, entry_name, entry_contents2)
        cmd = ["diff"] + self.diff_flags + [filename1, filename2]
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, cwd=self.tmpdir)
        (stdoutdata, _) = p.communicate()
        return p.returncode, stdoutdata

    def diff_classes(self, entry_name, entry_contents1, entry_contents2):
        javap1 = self.javap(1, entry_name, entry_contents1)
        javap2 = self.javap(2, entry_name, entry_contents2)
        return self.diff_content(entry_name + ".javap", javap1, javap2)

    def diff_zipinfos(self, zipfile1, zipinfo1, zipfile2, zipinfo2):
        contents1 = zipfile1.read(zipinfo1)
        contents2 = zipfile2.read(zipinfo2)
        if contents1 == contents2:
            return True
        filename = zipinfo1.filename
        if filename.endswith(".class"):
            returncode, out = self.diff_classes(filename, contents1, contents2)
        else:
            returncode, out = self.diff_content(filename, contents1, contents2)
        if returncode != 0:
            print("Files differ: %s\n%s" % (filename, out), file=self.output)
        return returncode

    @staticmethod
    def zipinfo_by_name(zipfile):
        result = {}
        for zipinfo in zipfile.infolist():
            result.setdefault(zipinfo.filename, []).append(zipinfo)
        return result

    def run(self):
        return_code = 0
        absjar1 = os.path.abspath(self.jar1)
        absjar2 = os.path.abspath(self.jar2)
        print("Comparing:\n1: %s\n2: %s" % (absjar1, absjar2), file=sys.stderr)
        with ZipFile(absjar1) as zipfile1, ZipFile(absjar2) as zipfile2:
            zipinfos1_by_name = JarDiffer.zipinfo_by_name(zipfile1)
            zipinfos2_by_name = JarDiffer.zipinfo_by_name(zipfile2)
            all_files = sorted(set(zipinfos1_by_name.keys() + zipinfos2_by_name.keys()))
            for name in all_files:
                zipinfos1 = zipinfos1_by_name.get(name, [])
                zipinfos2 = zipinfos2_by_name.get(name, [])
                while zipinfos1 or zipinfos2:
                    if not zipinfos1:
                        print("Only in %s: %s" % (self.jar2, name), file=self.output)
                        zipinfos2.pop()
                        return_code = 1
                    elif not zipinfos2:
                        print("Only in %s: %s" % (self.jar1, name), file=self.output)
                        zipinfos1.pop()
                        return_code = 1
                        continue
                    elif not self.diff_zipinfos(
                        zipfile1, zipinfos1.pop(), zipfile2, zipinfos2.pop()
                    ):
                        return_code = 1
        return return_code


if __name__ == "__main__":
    with JarDiffer(sys.argv) as differ:
        differ.run()
