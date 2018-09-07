#!/usr/bin/env python

from __future__ import absolute_import, division, print_function, unicode_literals

import os
import subprocess

# The location of the generate grammar kit script
DIR = os.path.dirname(__file__)

# The location of the plugin directory
PLUGIN_PATH = os.path.join(DIR, "..")
# The location of the grammar-kit directory
GRAMMAR_KIT = os.path.join(DIR, "../../../third-party/java/grammar-kit/")

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

print(FLEX_OUT_DIR)

subprocess.call(["java", "-jar", GRAMMAR_KIT_JAR, OUT_DIR, BNF_FILE])
subprocess.call(
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
