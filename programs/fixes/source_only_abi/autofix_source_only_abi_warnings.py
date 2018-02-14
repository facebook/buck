#!/usr/bin/env python2.7
from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import unicode_literals

import argparse
import os
import re

from collections import namedtuple

import buckutils as buck
import javautils as java

# Example:
#         timestamp         level      command       tid                 class                                                           message
#  |---------------------|  |   |  |----------||     ||  |----------------------------------------------|  |---------------------------------------------------------------------------------------------------------------------------------------------------------|
# [2017-10-26 01:08:10.126][debug][command:null][tid:83][com.facebook.buck.jvm.java.Jsr199JavacInvocation] javac: /Users/jkeljo/buck/third-party/java/dx/src/com/android/dx/cf/code/LocalsArraySet.java:-1: note: Some input files use unchecked or unsafe operations.
BUCK_LOG_LINE_PATTERN = re.compile(
    r"^\[(?P<timestamp>[^]]+)\]\[(?P<level>[^]]+)\]\[command:(?P<command>[^]]+)\]\[tid:(?P<tid>\d+)\]\[(?P<class>[^]]+)\] (?P<message>.+)$")

# Example:
#                                               path                                          line                          message
#        |-----------------------------------------------------------------------------------| ||  |--------------------------------------------------------|
# javac: /Users/jkeljo/buck/third-party/java/dx/src/com/android/dx/cf/code/LocalsArraySet.java:-1: note: Some input files use unchecked or unsafe operations.
JAVAC_MESSAGE_PATTERN = re.compile(r"^javac: (?P<path>[^:]+):(?P<line>\d+): (?P<message>.+)$")

# Example
#   spaces
# |--------|
#           ^
JAVAC_LOCATION_PATTERN = re.compile(r"^(?P<spaces> +)\^.*$")


def main():
    args = parse_args()

    with open(args.log_file) as log_file:
        migrate(log_file)


def parse_args():
    description = """Parses a Buck log file for source-only ABI migration warnings, and applies
automatic fixes for those warnings. To generate a log file with these warnings present, build
the targets to be migrated with buck build --config
java.abi_generation_mode=migrating_to_source_only."""

    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--log-file",
                        help="buck.log file to search for migration instructions "
                             "(default ./buck-out/log/last_buildcommand/buck.log)",
                        default=os.path.join(os.getcwd(), "buck-out", "log", "last_buildcommand", "buck.log"))
    return parser.parse_args()


def migrate(log_file):
    for message in javac_messages(log_file):
        for pattern, plugin in migration_plugins:
            if len(message.details) == 0:
                continue
            match = pattern.search(message.details[0])
            if match:
                plugin(message)

    java.write_all()
    buck.write_all()


JavacMessage = namedtuple("JavacMessage", ["path", "line", "col", "summary", "details"])


def javac_messages(log_file):
    for message in messages(log_file):
        match = JAVAC_MESSAGE_PATTERN.match(message[0])
        if not match:
            continue
        path = match.group("path")
        file_line = int(match.group("line"))
        summary = match.group("message")

        # Skip line 1; it's just the code
        match = JAVAC_LOCATION_PATTERN.match(message[2])
        if not match:
            continue
        col = len(match.group("spaces")) + 1

        details = [line.strip() for line in message[3:]]

        yield JavacMessage(path, file_line, col, summary, details)


def messages(log_file):
    message = None
    for line in log_file:
        log_line = BUCK_LOG_LINE_PATTERN.match(line)
        if log_line:
            if message:
                yield message
            message = [log_line.group("message")]
        else:
            message.append(line)


required_for_source_only_abi = set()


def add_required_for_source_only_abi(message):
    match = ADD_REQUIRED_FOR_SOURCE_ABI_PATTERN.search(message.details[0])
    if not match:
        return
    rule = match.group("rule")
    if rule in required_for_source_only_abi:
        return

    buck_target = buck.get_build_target(rule)
    buck_target["required_for_source_only_abi"] = "True"

    required_for_source_only_abi.add(rule)


def add_source_only_abi_deps(message):
    match = ADD_SOURCE_ONLY_ABI_DEPS_PATTERN.search(message.details[0])
    if not match:
        return
    rule = match.group("rule")
    rules = match.group("rules").split(", ")

    buck_target = buck.get_build_target(rule)

    if not "source_only_abi_deps" in buck_target:
        buck_target["source_only_abi_deps"] = buck.EditableList()
    source_only_abi_deps = buck_target["source_only_abi_deps"]

    for dep in rules:
        if not dep in source_only_abi_deps:
            source_only_abi_deps.prepend(dep)


def do_remediations(message):
    for remediation in message.details[1:]:
        apply_remediation(message.path, message.line, message.col, remediation)


def apply_remediation(path, line, col, remediation):
    for (pattern, fn) in remediations:
        match = pattern.match(remediation)
        if match:
            fn(path, line, col, **match.groupdict())
            return

    print("Manual: %s:%s,%s: %s" % (path, line, col, remediation))


def add_import(path, line, col, type):
    file = java.load(path)
    file.add_import(type)


def replace_name(path, line, col, old, new):
    file = java.load(path)
    file.replace_name(line, col, old, new)


ADD_REQUIRED_FOR_SOURCE_ABI_PATTERN = re.compile(
    r"add required_for_source_only_abi = True to (?P<rule>[^#.]*).")
ADD_SOURCE_ONLY_ABI_DEPS_PATTERN = re.compile(
    r"add the following rules to source_only_abi_deps in (?P<rule>[^:]+:[^#:]+)[^:]*: (?P<rules>.+)")
ADD_AN_IMPORT_PATTERN = re.compile(r'^Add an import for "(?P<type>[^"]+)"')
REPLACE_A_NAME_PATTERN = re.compile(r'^Use "(?P<new>[^"]+)" here instead of "(?P<old>[^"]+)"')
migration_plugins = [
    (re.compile(r"^To fix:$"), do_remediations),
    (ADD_REQUIRED_FOR_SOURCE_ABI_PATTERN, add_required_for_source_only_abi),
    (ADD_SOURCE_ONLY_ABI_DEPS_PATTERN, add_source_only_abi_deps),
]

remediations = [
    (ADD_AN_IMPORT_PATTERN, add_import),
    (REPLACE_A_NAME_PATTERN, replace_name),
]

if __name__ == "__main__":
    main()
