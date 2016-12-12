#! /usr/bin/python

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import unicode_literals

import argparse
import collections
import json
import sys

BUILD_RULE_FINISHED_TOKEN = 'BuildRuleEvent.Finished'

BuildRule = collections.namedtuple(
    'BuildRule', 'name rulekey input_rulekey output_hash cache_result')


def parseArgs():
    description = """Analyze build output differences between two builds.
To use this tool you need to obtain the buck-machine-log file for both the
builds. Then you would invoke this tool on the two log files.

Note that this tool is not to be trusted blindly.
It will point out differences in default rule key for rules which were built or
fetched in both the builds. It will also try to spot the differences in output
hash but only for the build rules which have the `outputHash` field set in both
builds. (Buck doesn't set output hash for rules which it fetches from the cache.
So do a --no-cache build to get the output hash for every rule.)
"""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument('machineLogOne',
                        type=str,
                        help='Path to first buck-machine-log')
    parser.add_argument('machineLogTwo',
                        type=str,
                        help='Path to second buck-machine-log')
    parser.add_argument('-v',
                        '--verbose',
                        action='store_true',
                        help='Print extra information (like cache misses).')
    return parser.parse_args()


def read_file(path_to_log_file):
    build_rules = {}
    with open(path_to_log_file, 'r') as logfile:
        for line in logfile:
            if line.startswith(BUILD_RULE_FINISHED_TOKEN):
                row = json.loads(line[len(BUILD_RULE_FINISHED_TOKEN) + 1:])
                rule_keys = row['ruleKeys']
                if not rule_keys['inputRuleKey']:
                    rule_keys['inputRuleKey'] = {'hashCode': None}

                rule = BuildRule(
                    name=row['buildRule']['name'],
                    rulekey=rule_keys['ruleKey']['hashCode'],
                    input_rulekey=rule_keys['inputRuleKey']['hashCode'],
                    output_hash=row['outputHash'],
                    cache_result=row['cacheResult']['type'])
                build_rules[rule.name] = rule
    return build_rules


def get_cache_misses(build_rules):
    misses = []
    for rule in build_rules:
        if build_rules[rule].cache_result == 'MISS':
            misses.append(rule)
    return sorted(misses)


def count_by_cache_result(build_rules):
    stats = {}
    for rule in build_rules:
        if build_rules[rule].cache_result not in stats:
            stats[build_rules[rule].cache_result] = 0
        stats[build_rules[rule].cache_result] += 1
    return stats


def print_line_sep(c):
    print(c * 80)


def list_rules_with_msg(rules, msg):
    if len(rules) > 0:
        print_line_sep('=')
        print(msg)
        print_line_sep('-')
        for rule in rules:
            print(rule)
        print_line_sep('=')


def print_cache_stats_with_msg(stats, msg):
    print_line_sep('-')
    print(msg)
    print_line_sep('-')
    for cache_result in stats:
        print(cache_result + ": " + str(stats[cache_result]))
    print_line_sep('-')


if __name__ == '__main__':
    args = parseArgs()
    build1 = read_file(args.machineLogOne)
    build2 = read_file(args.machineLogTwo)
    common_rules = set(build1.keys()).intersection(build2.keys())
    common_rules_in_build1 = dict((rule, build1[rule])
                                  for rule in common_rules)
    common_rules_in_build2 = dict((rule, build2[rule])
                                  for rule in common_rules)
    exclusive_rules_in_build1 = set(build1.keys()).difference(common_rules)
    exclusive_rules_in_build2 = set(build2.keys()).difference(common_rules)

    rk_mismatch = []
    num_rulekeys_compared = 0
    output_mismatch = []
    num_output_compared = 0
    for rule in sorted(common_rules):
        if build1[rule].rulekey is not None and \
           build2[rule].rulekey is not None:
            num_rulekeys_compared += 1
            if build1[rule].rulekey != build2[rule].rulekey:
                rk_mismatch.append(rule)
        if build1[rule].output_hash is not None and \
           build2[rule].output_hash is not None:
            num_output_compared += 1
            if build1[rule].output_hash != build2[rule].output_hash:
                output_mismatch.append(rule)

    misses_in_build1 = get_cache_misses(common_rules_in_build1)
    misses_in_build2 = get_cache_misses(common_rules_in_build2)

    cache_stats1 = count_by_cache_result(build1)
    cache_stats2 = count_by_cache_result(build2)

    print_cache_stats_with_msg(cache_stats1, 'Cache statistics for Build 1:')
    print_cache_stats_with_msg(cache_stats2, 'Cache statistics for Build 2:')

    if len(rk_mismatch) == 0 and len(output_mismatch) == 0:
        exit_code = 0
        print('The builds are a perfect match. Rejoice!')
    else:
        exit_code = -1
        list_rules_with_msg(rk_mismatch,
                            'Following rules have a rule-key mismatch:')
        list_rules_with_msg(output_mismatch,
                            'Following rules have an output hash mismatch:')
    if args.verbose:
        list_rules_with_msg(
            misses_in_build1,
            'Following rules were a cache miss in the first build:')
        list_rules_with_msg(
            misses_in_build2,
            'Following rules were a cache miss in the second build:')
    print(('Note that we could only compare the ruleKeys for %d rules \n' +
           'and output hashes for %d locally built rules.') %
          (num_rulekeys_compared, num_output_compared))
    sys.exit(exit_code)
