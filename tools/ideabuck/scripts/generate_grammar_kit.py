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


from __future__ import absolute_import, division, print_function, unicode_literals

import os
import shutil
import subprocess

# The location of the generate grammar kit script
DIR = os.path.dirname(__file__)

# The location of the plugin directory
PLUGIN_PATH = os.path.abspath(os.path.join(DIR, ".."))
# The location of the grammar-kit directory
GRAMMAR_KIT = os.path.abspath(
    os.path.join(DIR, "../../../third-party/java/grammar-kit/")
)

OUT_DIR = os.path.join(PLUGIN_PATH, "gen")
FLEX_OUT_DIR = os.path.join(OUT_DIR, "com/facebook/buck/intellij/ideabuck/lang")

GRAMMAR_KIT_JAR = os.path.join(GRAMMAR_KIT, "grammar-kit.jar")
GRAMMAR_KIT_JFLEX_JAR = os.path.join(GRAMMAR_KIT, "JFlex.jar")

JFLEX_SKELETON = os.path.join(PLUGIN_PATH, "resources/idea-flex.skeleton")
FLEX_FILE = os.path.join(
    PLUGIN_PATH, "src/com/facebook/buck/intellij/ideabuck/lang/Buck.flex"
)
BNF_FILE = os.path.join(
    PLUGIN_PATH, "src/com/facebook/buck/intellij/ideabuck/lang/Buck.bnf"
)


def subprocess_call(cmd):
    print("Running: %s" % (" ".join(cmd)))
    subprocess.call(cmd)


shutil.rmtree(OUT_DIR, ignore_errors=True)
subprocess_call(["java", "-jar", GRAMMAR_KIT_JAR, OUT_DIR, BNF_FILE])
subprocess_call(
    [
        "java",
        "-jar",
        GRAMMAR_KIT_JFLEX_JAR,
        "-sliceandcharat",
        "-skel",
        JFLEX_SKELETON,
        "-d",
        FLEX_OUT_DIR,
        FLEX_FILE,
    ]
)
