#!/usr/bin/python
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


import os
import sys
import subprocess
import re
import mmap
import string

EVENT_NAME_PREFIX = "com.facebook.buck.artifact_cache."
RULE_KEY_KEY = 'rulekey'
TIMESTAMP_KEY = 'timestamp'
EVENT_NAME_KEY = 'event_name'
RESULT_KEY = 'result'
INFO_KEY = 'info'


class Entry(object):
    def __init__(self, rulekey, timestamp, event_name, info, result):
        self.rulekey = rulekey
        self.timestamp = timestamp
        self.event_name = event_name
        self.info = info
        self.result = result

    def expand(self, show_timestamp, show_event_name, show_result, show_info):
        r = ''
        if show_timestamp:
            r += "\t\ttimestamp  = " + self.timestamp + "\n"
        if show_event_name:
            r += "\t\tevent_name = " + self.event_name + "\n"
        if show_result:
            r += "\t\tresult     = " + self.result + "\n"
        if show_info:
            r += "\t\tinfo       = " + self.info + "\n"
        return r


class ArtifactHistory(object):
    def __init__(self, rulekey):
        self.rulekey = rulekey
        self.entries = []

    def add_entry(self, rulekey, timestamp, event_name, info, result):
        new_entry = Entry(rulekey, timestamp, event_name, info, result)
        if self.rulekey != new_entry.rulekey:
            raise Exception("Attempt to insert event with rulekey=" + rulekey +
                            " into artifact history with rulekey=" + self.rulekey)
        self.entries.append(new_entry)

    def expand(self, show_timestamp, show_event_name, show_result, show_info):
        repr = "rulekey=" + self.rulekey
        print_something = show_timestamp or show_event_name or show_result or show_info
        if print_something:
            repr += "\n"
        for i, item in enumerate(self.entries):
            if print_something:
                repr += "\tItem: \n"
                repr += item.expand(show_timestamp, show_event_name, show_result, show_info)
        return repr


def parseArgs():
    import argparse
    description = "Prints out rulekeys with all attempts to get the cache item for each rulekey."
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument(
        'log_file',
        help='buck.log file')
    parser.add_argument(
        '--only-misses',
        help='Show only cache misses',
        action='store_true',
        default=False)
    parser.add_argument(
        '--fields',
        help='Comma separated list of fields to print out. Default value is: ' +
             'timestamp,event_name,result,info',
        default='timestamp,event_name,result,info')
    parser.add_argument(
        '--verbose',
        help='Verbose mode',
        action='store_true',
        default=False)
    return parser.parse_args()


def parse_line(line):
    rulekey_match = re.search(r'\b[0-9a-f]{40}\b', line)
    if not rulekey_match:
        return None
    timestamp_match = re.search(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}.\d{3}', line)
    if not timestamp_match:
        return None
    rulekey = line[rulekey_match.start():rulekey_match.end()]
    timestamp = line[timestamp_match.start():timestamp_match.end()]

    event_name_start = line.find(EVENT_NAME_PREFIX)

    buck_event_tmp_line = line[event_name_start:]
    event_name = buck_event_tmp_line[:buck_event_tmp_line.find("]")][len(EVENT_NAME_PREFIX):]

    result_keyword = "cache "
    result_start = line.rfind(result_keyword)
    result = line[result_start + len(result_keyword): -1].strip()

    info = line[line.find(event_name) + len(event_name) + 1:]
    info = info[: -len(result) - len(result_keyword) - 1].strip()
    return {RULE_KEY_KEY: rulekey,
            TIMESTAMP_KEY: timestamp,
            EVENT_NAME_KEY: event_name,
            RESULT_KEY: result,
            INFO_KEY: info}


def analyze(log_file, show_only_misses, fields_to_show):
    rulekey_to_history = {}
    file = open(log_file, "r+")
    mm = mmap.mmap(file.fileno(), 0)
    while mm.tell() < mm.size():
        line = mm.readline()
        if EVENT_NAME_PREFIX in line:
            parsed_dict = parse_line(line)
            if not parsed_dict:
                continue
            rulekey = parsed_dict.get(RULE_KEY_KEY)
            history = rulekey_to_history.get(rulekey)
            if not history:
                history = ArtifactHistory(rulekey)
                rulekey_to_history[rulekey] = history
            history.add_entry(rulekey,
                              parsed_dict.get(TIMESTAMP_KEY),
                              parsed_dict.get(EVENT_NAME_KEY),
                              parsed_dict.get(INFO_KEY),
                              parsed_dict.get(RESULT_KEY))
    mm.close()
    file.close()

    for i, artifact_history_item in enumerate(rulekey_to_history.values()):
        got_hit = False
        for j, history_entry in enumerate(artifact_history_item.entries):
            if (history_entry.result == "hit"):
                got_hit = True
                break
        if (show_only_misses and not got_hit or not show_only_misses):
            print artifact_history_item.expand(TIMESTAMP_KEY in fields_to_show,
                                               EVENT_NAME_KEY in fields_to_show,
                                               RESULT_KEY in fields_to_show,
                                               INFO_KEY in fields_to_show)


def main():
    args = parseArgs()
    if not os.path.exists(args.log_file):
        raise Exception(args.log_file + ' does not exist')
    analyze(args.log_file, args.only_misses, args.fields)


if __name__ == '__main__':
    main()
