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

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import unicode_literals

import argparse
import collections
import hashlib
import io
import itertools
import gzip
import codecs
import os
import re
import sys

PATH_VALUE_REGEX = re.compile(r'path\(([^:]+):\w+\)')
LOGGER_NAME = 'com.facebook.buck.rules.keys.RuleKeyBuilder'
NAME_FINDER = '"):key(.target_name)'


class LazyStructureMap(object):
    def __init__(self, data):
        self._data = data
        self._map = None

        self._name = None
        name_end = data.find(NAME_FINDER)
        if name_end != -1 and '.build_rule_type' in data:
            name_start = data.rfind('("', 0, name_end) + 2
            self._name = data[name_start:name_end]

    def get(self, key, default=None):
        return self._get().get(key, default)

    def name(self):
        return self._name

    def keys(self):
        return self._get().keys()

    def _get(self):
        if self._map is None:
            self._map = self._compute()
            self._data = None

        return self._map

    def __eq__(self, other):
        if self._map is not None or other._map is not None:
            return self._get() == other._get()
        return self._data == other._data

    def _compute(self):
        # Because BuildTargets have ':' in them we can't just split on that
        # character. We know that all values take the form name(..):name(..):
        # so we can cheat and split on ): instead
        structure = self._data
        structure_entries = structure.split('):')
        structure_map = collections.OrderedDict()
        last_key = None

        for e in reversed(structure_entries):
            # Entries do not have their trailing ')', which was chomped by the split.
            # These are added back as needed.
            if len(e) == 0:
                continue
            elif e.startswith('key('):
                last_key = e[4:]
            else:
                d = structure_map.get(last_key)
                if d is None:
                    structure_map[last_key] = [e + ')']
                else:
                    d.append(e + ')')

        return structure_map


def parseArgs():
    description = """Analyze RuleKey differences between two builds.
To use this tool you need to enable RuleKey logging in your build first by
adding the appropriate entry to the .bucklogging.properties file:

> echo '""" + LOGGER_NAME + """.level=FINER' > .bucklogging.properties

You would then perform two builds: a 'before' and 'after' build that you wish
to compare. Ideally these would differ by the minimal amount possible to
reproduce the issue you're investigating (so for example compile the same
target from the same source revision across different machines).

Finally you would invoke this tool on the two log files obtained this way along
with a build_target that you'd like to analyze (it's fine if this is simply
the top-level target you've built.

Make sure to do a 'buck kill', as the changes are JVM settings. Once you're done,
you may want to undo the .bucklogging.properties changes, otherwise builds will be
slower due to the extra logging.
"""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument(
        'left_log',
        help='buck.log file to look at first.')
    parser.add_argument(
        'right_log',
        help='buck.log file to look at second.')
    parser.add_argument(
        'build_target',
        help='Name of the RuleKey that you want to analyze',
        nargs='?')
    parser.add_argument(
        '--verbose',
        help='Verbose mode',
        action='store_true',
        default=False)

    # Print full help message if we're invoked with no arguments (otherwise
    # you get the shortened 1 line 'usage' message.
    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit(1)
    return parser.parse_args()


class KeyValueDiff(object):
    ORDER_ONLY = "Only order of entries differs: [{left}] vs [{right}]."
    ORDER_ONLY_REMAINING = ("Only order of remaining entries differs: " +
                            "[{left}] vs [{right}].")
    ORDER_REPS_REMAINING = ("Order and repetition count of remaining " +
                            "entries differs: [{left}] vs [{right}].")
    ORDER_AND_CASE_ONLY = ("Only order and letter casing (Upper Case vs " +
                           "lower case) of entries differs:")

    def __init__(self, left_format=None, right_format=None):
        self._left = []
        self._right = []
        self._left_format = left_format or '-[%s]'
        self._right_format = right_format or '+[%s]'
        self._interesting_paths = set()

    def append(self, left, right):
        self._left.append(left)
        self._right.append(right)

    def getInterestingPaths(self):
        return self._interesting_paths

    def _filterPathValue(self, value):
        match = PATH_VALUE_REGEX.search(value)
        if match:
            return match.groups()[0]
        else:
            return None

    def diff(self):
        if self._left == self._right:
            return ['No changes']

        if sorted(self._left) == sorted(self._right):
            return [KeyValueDiff.ORDER_ONLY.format(
                    left=', '.join(self._left),
                    right=', '.join(self._right))]

        left_lower_index = dict([(v.lower(), v) for v in self._left])
        right_lower_index = dict([(v.lower(), v) for v in self._right])
        if set(left_lower_index.keys()) == set(right_lower_index.keys()):
            differences = []
            for k in sorted(left_lower_index.keys()):
                if left_lower_index[k] != right_lower_index[k]:
                    differences.append(
                            self._left_format % left_lower_index[k])
                    differences.append(
                            self._right_format % right_lower_index[k])
            return [KeyValueDiff.ORDER_AND_CASE_ONLY] + differences

        left_set = set(self._left)
        right_set = set(self._right)
        left_only = left_set.difference(right_set)
        right_only = right_set.difference(left_set)

        left_common = filter(lambda i: i not in left_only, self._left)
        right_common = filter(lambda i: i not in right_only, self._right)

        left_not_in_order = []
        right_not_in_order = []
        for l, r in map(None, left_common, right_common):
            if l == r:
                continue
            if l is not None:
                left_not_in_order.append(l)
            if r is not None:
                right_not_in_order.append(r)

        self._interesting_paths.update(
                filter(None,
                       map(self._filterPathValue,
                           left_only.union(right_only))))

        result = [self._left_format % v for v in sorted(left_only)]
        result.extend([self._right_format % v for v in sorted(right_only)])
        if len(left_not_in_order) > 0:
            format_string = KeyValueDiff.ORDER_REPS_REMAINING
            if len(left_not_in_order) == len(right_not_in_order):
                format_string = KeyValueDiff.ORDER_ONLY_REMAINING
            result.append(format_string.format(
                left=', '.join(left_not_in_order),
                right=', '.join(right_not_in_order)))

        return result


class RuleKeyStructureInfo(object):
    def __init__(self, buck_out, entries_for_test=None):
        if entries_for_test is not None:
            self._entries = entries_for_test
        elif isinstance(buck_out, basestring):
            parsed_log = RuleKeyStructureInfo._parseBuckOut(buck_out)
        else:
            parsed_log = RuleKeyStructureInfo._parseLogFile(buck_out)
        self._entries, self._invocation_info = parsed_log
        self._key_to_struct = RuleKeyStructureInfo._makeKeyToStructureMap(
            self._entries)
        self._name_to_key = RuleKeyStructureInfo._makeNameToKeyMap(
            self._entries)

    def getInvocationInfo(self, key):
        return self._invocation_info.get(key, '<unknown>')

    def getByKey(self, key):
        return self._key_to_struct.get(key)

    def getKeyForName(self, name):
        return self._name_to_key.get(name)

    def getNameToKeyMap(self):
        return self._name_to_key

    def getNameForKey(self, key):
        struct = self.getByKey(key)
        if struct is None:
            return None
        return struct.name()

    def getByName(self, name):
        key = self._name_to_key.get(name)
        if key is None:
            return None
        return self.getByKey(key)

    def getAllNames(self):
        names = []
        for e in self._entries:
            top_key, structure = e
            name = self.getNameForKey(top_key)
            if name is not None:
                names.append(name)
        return names

    def getRuleKeyRefs(self, values):
        return [
            (value, RuleKeyStructureInfo._extractRuleKeyRef(value))
            for value in values
        ]

    def size(self):
        return len(self._entries)

    @staticmethod
    def _makeKeyToStructureMap(entries):
        result = {}
        for e in entries:
            top_key, structure = e
            if top_key in result:
                assert structure == result[top_key]
            result[top_key] = structure
        return result

    @staticmethod
    def _makeNameToKeyMap(entries):
        result = {}
        for e in entries:
            top_key, structure = e
            name = structure.name()
            if name is None:
                continue
            result[name] = top_key
        return result


    @staticmethod
    def _parseRuleKeyLine(rule_key):
        if rule_key.endswith('='):
            return (rule_key[:-1], {})
        top_key, structure = rule_key.split('=', 1)
        return (top_key, LazyStructureMap(structure))

    RULE_LINE_REGEX = re.compile(r'(\[[^\]]+\])+\s+RuleKey\s+(.*)')
    INVOCATION_LINE_REGEX = re.compile(r'.*(\[[^\]+]\])*\s+InvocationInfo\s+(.*)')
    INVOCATION_VALUE_REGEX = re.compile(r'(\w+)=\[([^]]*)\]')

    @classmethod
    def _parseLogFile(cls, buck_out):
        rule_key_structures = []
        invocation_info_line = None
        for line in buck_out:
            if invocation_info_line is None:
                invocation_match = cls.INVOCATION_LINE_REGEX.match(line)
                if invocation_match is not None:
                    invocation_info_line = invocation_match.groups()[1]
            match = cls.RULE_LINE_REGEX.match(line)
            if match is None:
                continue
            parsed_line = RuleKeyStructureInfo._parseRuleKeyLine(match.group(2))
            rule_key_structures.append(parsed_line)

        invocation_info = {}
        if invocation_info_line is not None:
            invocation_info = dict(
                cls.INVOCATION_VALUE_REGEX.findall(invocation_info_line))

        return (rule_key_structures, invocation_info)

    @staticmethod
    def _parseBuckOut(file_path):
        if file_path.endswith('.gz'):
            with gzip.open(file_path, 'rb') as raw_log:
                info = codecs.lookup('utf-8')
                utf8_log = codecs.StreamReaderWriter(
                        raw_log,
                        info.streamreader,
                        info.streamwriter)
                return RuleKeyStructureInfo._parseLogFile(utf8_log)
        with io.open(file_path, mode='r', encoding='utf-8') as buck_out:
            return RuleKeyStructureInfo._parseLogFile(buck_out)

    @staticmethod
    def _extractRuleKeyRef(value):
        RULE_KEY_REF_START = 'ruleKey(sha1='
        RULE_KEY_REF_END = ')'

        def isRuleKeyRef(value):
            return (value.endswith(RULE_KEY_REF_END) and
                    value.startswith(RULE_KEY_REF_START))

        if not isRuleKeyRef(value):
            return None
        rk = value[len(RULE_KEY_REF_START):-len(RULE_KEY_REF_END)]
        return rk


def reportOnInterestingPaths(paths):
    result = []
    for path in paths:
        if not os.path.exists(path):
            result.append(' %s does not exist' % path)
        else:
            try:
                if not os.path.isfile(path):
                    result.append(' %s is not a file' % (path))
                else:
                    h = hashlib.sha1()
                    with open(path, 'rb') as f:
                        while True:
                            buf = f.read(128 * 1024)
                            if len(buf) == 0:
                                break
                            h.update(buf)
                    result.append(
                            ' %s exists and hashes to %s' %
                            (path, h.hexdigest()))
            except Exception as e:
                result.append(' %s error hashing: %s' % (path, e))

    return result


def diffValues(left_values, right_values):
    left_diff, right_diff = zip(*filter(lambda (left, right): left != right,
                                        itertools.izip_longest(left_values, right_values)))
    return filter(lambda l: l is not None, left_diff), filter(lambda l: l is not None, right_diff)


def diffInternal(
        label,
        left_s,
        left_info,
        right_s,
        right_info,
        verbose,
        format_tuple,
        check_paths):
    keys = set(left_s.keys()).union(set(right_s.keys()))
    changed_key_pairs_with_labels = set()
    changed_key_pairs_with_values = collections.defaultdict(
            lambda: KeyValueDiff(format_tuple[0], format_tuple[1]))
    changed_key_pairs_with_labels_for_key = set()
    interesting_paths = set()
    report = []
    for key in keys:
        if key is None:
            continue
        left_values = left_s.get(key, set([]))
        right_values = right_s.get(key, set([]))

        if left_values == right_values:
            continue

        left_diff_values, right_diff_values = diffValues(left_values, right_values)

        left_with_keys = left_info.getRuleKeyRefs(left_diff_values)
        right_with_keys = right_info.getRuleKeyRefs(right_diff_values)

        did_align_for_deps = False
        if key.endswith(('Deps', 'deps')):
            right_names = [right_info.getNameForKey(right_key) for (_, right_key) in
                           right_with_keys]
            right_index = dict([(right_name, i) for (i, right_name) in enumerate(right_names)])
            for left_idx, (left_v, left_key) in enumerate(left_with_keys):
                left_name = left_info.getNameForKey(left_key)
                if left_name is None or left_idx >= len(right_with_keys):
                    continue
                right_idx = right_index.get(left_name)
                if right_idx is None:
                    continue
                if right_idx != left_idx:
                    right_name = right_names[left_idx]
                    swap_entries_in_list(right_with_keys, right_idx, left_idx)
                    swap_entries_in_list(right_names, right_idx, left_idx)
                    right_index[right_name] = right_idx
                    right_index[left_name] = left_idx
                    did_align_for_deps = True

        if did_align_for_deps:
            report.append('  (' + key + '): order of deps was name-aligned.')

        both_with_keys = map(None, left_with_keys, right_with_keys)
        for l, r in both_with_keys:
            (left_v, left_key) = l or ('<missing>', None)
            (right_v, right_key) = r or ('<missing>', None)
            if left_v == right_v:
                continue

            left_name = None
            right_name = None
            if left_key is not None:
                left_name = left_info.getNameForKey(left_key)
            if right_key is not None:
                right_name = right_info.getNameForKey(right_key)

            if left_key is not None and right_key is not None:
                if left_name == right_name and left_name is not None:
                    changed_key_pairs_with_labels_for_key.add(
                        (left_name, (left_key, right_key)))
                    continue
                if (left_name is None and right_name is None and
                    (left_info.getByKey(left_key).keys() ==
                        right_info.getByKey(right_key).keys())):
                    # Assume that if the set of keys in the structure of the
                    # referenced RuleKey matches then it's the same "thing".
                    # The names need to empty, otherwise we'll end up
                    # comparing BuildRules for different targets.
                    q_label = label + '->' + key
                    changed_key_pairs_with_labels_for_key.add(
                        (q_label, (left_key, right_key)))
                    continue
            if left_name:
                left_v = '"%s"@%s' % (left_name, left_v)
            if right_name:
                right_v = '"%s"@%s' % (right_name, right_v)
            changed_key_pairs_with_values[key].append(left_v, right_v)

    for key in sorted(changed_key_pairs_with_values.keys()):
        value_pairs = changed_key_pairs_with_values[key]
        report.append('  (' + key + '):')
        report.extend(['    ' + l for l in value_pairs.diff()])
        interesting_paths.update(value_pairs.getInterestingPaths())

    changed_key_pairs_with_labels.update(
        changed_key_pairs_with_labels_for_key)
    if len(changed_key_pairs_with_labels_for_key) > 0:
        changed_labels = [l for (l, _) in
                          changed_key_pairs_with_labels_for_key]
        if verbose:
            report.append('  (' + key + ') : changed because of ' +
                          ','.join(sorted(changed_labels)))

    if check_paths and len(interesting_paths) > 0:
        report.append('Information on paths the script has seen:')
        report.extend(reportOnInterestingPaths(interesting_paths))

    if len(report) > 0:
        report = ['Change details for [' + label + ']'] + report

    return report, changed_key_pairs_with_labels


def diffAndReturnSeen(starting_refs, left_info, right_info, verbose,
                      format_tuple, check_paths, seen_keys, excludes):
    queue = collections.deque(starting_refs)
    result = []
    visited_keys = []
    while len(queue) > 0:
        p = queue.popleft()
        label, ref_pair = p
        if label in excludes:
            continue
        (left_key, right_key) = ref_pair
        visited_keys.append(ref_pair)
        report, changed_key_pairs_with_labels = diffInternal(
            label,
            left_info.getByKey(left_key),
            left_info,
            right_info.getByKey(right_key),
            right_info,
            verbose,
            format_tuple or (None, None),
            check_paths)
        for e in sorted(changed_key_pairs_with_labels):
            label, ref_pair = e
            if ref_pair in seen_keys:
                continue
            seen_keys.add(ref_pair)
            queue.append(e)

        result += report
    return result, visited_keys


def diff(name, left_info, right_info, verbose, format_tuple=None,
         check_paths=False):
    left_key = left_info.getKeyForName(name)
    right_key = right_info.getKeyForName(name)
    if left_key is None:
        raise KeyError('Left log does not contain ' + name)
    if right_key is None:
        raise KeyError('Right log does not contain ' + name)
    result, _ = diffAndReturnSeen([(name, (left_key, right_key))], left_info,
                                  right_info, verbose, format_tuple,
                                  check_paths, set(), set())
    if not result and left_key != right_key:
        result.append("I don't know why RuleKeys for {} do not match.".format(
            name))
    return result


def diffAll(left_info, right_info, verbose, format_tuple=None,
            check_paths=False, excludes=None):
    excludes = excludes or set()
    # Ghetto ordered set implementation.
    seen_left_names = collections.OrderedDict(
        [(k, True) for k in left_info.getAllNames()])
    all_seen = set()
    all_results = []
    while True:
        if not seen_left_names:
            break
        name, _ = seen_left_names.popitem()
        if name is None:
            continue
        left_key = left_info.getKeyForName(name)
        if left_key is None:
            all_results.append('Skipping {} because it is missing' +
                               'from the left log.'.format(name))
            continue
        right_key = right_info.getKeyForName(name)
        if right_key is None:
            all_results.append('Skipping {} because it is missing' +
                               'from the right log.'.format(name))
            continue
        if left_key == right_key:
            continue
        print('Analyzing', name, 'for changes...')

        single_result, visited_keys = diffAndReturnSeen(
                [(name, (left_key, right_key))], left_info, right_info,
                verbose, format_tuple, check_paths, all_seen, excludes)
        if name not in excludes and not single_result and left_key != right_key:
            single_result.append(
                "I don't know why RuleKeys for {} do not match.".format(name))
        all_results.extend(single_result)
        for l, r in visited_keys:
            left_name = left_info.getNameForKey(l)
            seen_left_names.pop(left_name, False)
    return all_results


def compute_rulekey_mismatches(left_info, right_info, left_name='left',
                               right_name='right'):
    left_name_to_key = left_info.getNameToKeyMap()
    right_name_to_key = right_info.getNameToKeyMap()
    left_names = set(left_name_to_key.keys())
    right_names = set(right_name_to_key.keys())

    mismatch = []

    for name in left_names.union(right_names):
        left_key = left_name_to_key.get(name)
        right_key = right_name_to_key.get(name)
        if left_key is None:
            mismatch.append('{} missing from {}'.format(name, left_name))
        elif right_key is None:
            mismatch.append('{} missing from {}'.format(name, right_name))
        elif left_key != right_key:
            mismatch.append(
                '{} {}:{} != {}:{}'.format(name, left_name, left_key,
                                           right_name, right_key))
    return mismatch


def swap_entries_in_list(l, i, j):
    (l[i], l[j]) = (l[j], l[i])


def main():
    args = parseArgs()
    if not os.path.exists(args.left_log):
        raise Exception(args.left_log + ' does not exist')
    print('Loading', args.left_log)
    left = RuleKeyStructureInfo(args.left_log)
    print('Loaded', left.size(), 'rules')

    if not os.path.exists(args.right_log):
        raise Exception(args.right_log + ' does not exist')
    print('Loading', args.right_log)
    right = RuleKeyStructureInfo(args.right_log)
    print('Loaded', right.size(), 'rules')

    print('Comparing rules...')
    name = args.build_target
    if name:
        left_key = left.getKeyForName(name)
        right_key = right.getKeyForName(name)
        if left_key is None:
            raise KeyError(('Left log does not contain {}. Did you forget ' +
                           'to enable logging? (see help).').format(name))
        if right_key is None:
            raise KeyError(('Right log does not contain {}. Did you forget ' +
                           'to enable logging? (see help).').format(name))

        print('\n'.join(diff(name, left, right, args.verbose)))
    else:
        left_args = left.getInvocationInfo('Args')
        right_args = right.getInvocationInfo('Args')
        if left_args != right_args:
            print('Commands used to generate the logs are not identical: [',
                  left_args, '] vs [', right_args, ']. This might cause ' +
                  'spurious differences to be listed.')
        print('\n'.join(diffAll(left, right, args.verbose)))


if __name__ == '__main__':
    main()
