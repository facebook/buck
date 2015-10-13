from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import unicode_literals
import argparse
import collections
import os
import re
import sys

RULE_LINE_REGEX = re.compile(r'.*(\[[^\]+]\])*\s+RuleKey\s+(.*)')
LOGGER_NAME = 'com.facebook.buck.rules.RuleKeyBuilder'
TAG_NAME = 'RuleKey'


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
the top-level target you've built."""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument(
        'left_log',
        help='buck.log file to look at first.')
    parser.add_argument(
        'right_log',
        help='buck.log file to look at second.')
    parser.add_argument(
        'build_target',
        help='Name of the RuleKey that you want to analyze')
    parser.add_argument(
        '--verbose',
        help='Verbose mode',
        action='store_true',
        default=False)
    return parser.parse_args()


class RuleKeyStructureInfo(object):
    def __init__(self, buck_out_path):
        self._entries = RuleKeyStructureInfo._parseBuckOut(buck_out_path)
        self._key_to_struct = RuleKeyStructureInfo._makeKeyToStructureMap(
            self._entries)
        self._name_to_key = RuleKeyStructureInfo._makeNameToKeyMap(
            self._entries)

    def getByKey(self, key):
        return self._key_to_struct.get(key)

    def getKeyForName(self, name):
        return self._name_to_key.get(name)

    def getNameForKey(self, key):
        struct = self.getByKey(key)
        if struct is None:
            return None
        return RuleKeyStructureInfo._nameFromStruct(struct)

    def getByName(self, name):
        key = self._name_to_key.get(name)
        if key is None:
            return None
        return self.getByKey(key)

    @staticmethod
    def _nameFromStruct(structure):
        name = None
        if 'name' in structure and 'buck.type' in structure:
            name = list(structure['name'])[0]
            if name.startswith('string("'):
                name = name[8:-2]
        return name

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
            name = RuleKeyStructureInfo._nameFromStruct(structure)
            if name is None:
                continue
            if name in result and result[name] != top_key:
                print('Conflict for name ' + name + ' ' + top_key +
                      ' ' + result[name])
            result[name] = top_key
        return result

    @staticmethod
    def _parseRuleKeyLine(match):
        rule_key = match.groups()[1]
        if rule_key.endswith('='):
            return (rule_key[:-1], {})
        top_key, structure = rule_key.split('=', 1)
        # Because BuildTargets have ':' in them we can't just split on that
        # character. We know that all values take the form name(..):name(..):
        # so we can cheat and split on ): instead
        structure_entries = structure.split('):')
        structure_entries = [e + ')' for e in structure_entries if len(e) > 0]
        structure_map = collections.defaultdict(list)
        last_key = None

        for e in reversed(structure_entries):
            if len(e) == 0:
                continue
            elif e.startswith('key('):
                last_key = e[4:-1]
            else:
                structure_map[last_key].append(e)
                last_key = None

        return (top_key, structure_map)

    @staticmethod
    def _parseBuckOut(file_path):
        rule_key_structures = []
        with open(file_path, 'r') as buck_out:
            for line in buck_out.readlines():
                match = RULE_LINE_REGEX.match(line)
                if match is None:
                    continue
                parsed_line = RuleKeyStructureInfo._parseRuleKeyLine(match)
                rule_key_structures.append(parsed_line)
        return rule_key_structures

RULE_KEY_REF_START = 'ruleKey(sha1='
RULE_KEY_REF_END = ')'


def isRuleKeyRef(value):
    return (value.endswith(RULE_KEY_REF_END) and
            value.startswith(RULE_KEY_REF_START))


def extractRuleKeyRefs(values, struct_info):
    def extract(v):
        if not isRuleKeyRef(v):
            return None
        rk = v[len(RULE_KEY_REF_START):-len(RULE_KEY_REF_END)]
        return rk

    return [(v, extract(v)) for v in values]


def diffInternal(
        label,
        left_s,
        left_info,
        right_s,
        right_info,
        verbose):
    keys = set(left_s.keys()).union(set(right_s.keys()))
    changed_key_pairs_with_labels = set()
    report = []
    for key in keys:
        if key is None:
            continue
        left_values = left_s.get(key, set([]))
        right_values = right_s.get(key, set([]))

        left_with_keys = extractRuleKeyRefs(left_values, left_info)
        right_with_keys = extractRuleKeyRefs(right_values, right_info)

        changed_key_pairs_with_labels_for_key = set()
        both_with_keys = zip(left_with_keys, right_with_keys)
        for (left_v, left_key), (right_v, right_key) in both_with_keys:
            if left_v == right_v:
                continue
            if left_key is not None and right_key is not None:
                left_name = left_info.getNameForKey(left_key)
                right_name = right_info.getNameForKey(right_key)
                if left_name == right_name and left_name is not None:
                    changed_key_pairs_with_labels_for_key.add(
                        (left_name, (left_key, right_key)))
                    continue
                if (left_info.getByKey(left_key).keys() ==
                        right_info.getByKey(right_key).keys()):
                    # Assume that if the set of keys in the structure of the
                    # referenced RuleKey matches then it's the same "thing".
                    q_label = label + '->' + key
                    changed_key_pairs_with_labels_for_key.add(
                        (q_label, (left_key, right_key)))
                    continue

            report.append('  (' + key + '):\n' +
                          '    -[' + left_v + ']\n' +
                          '    +[' + right_v + ']')

        changed_key_pairs_with_labels.update(
            changed_key_pairs_with_labels_for_key)
        if len(changed_key_pairs_with_labels_for_key) > 0:
            changed_labels = [label for (label, _) in
                              changed_key_pairs_with_labels_for_key]
            if verbose:
                report.append('  (' + key + ') : changed because of ' +
                              ','.join(changed_labels))

    if len(report) > 0:
        report = ['Change details for [' + label + ']'] + report

    return (report, changed_key_pairs_with_labels)


def diff(name, left_info, right_info, verbose):
    left_key = left_info.getKeyForName(name)
    right_key = right_info.getKeyForName(name)
    if left_key is None:
        raise Exception('Left log does not contain ' + name + '. Did you ' +
                        'forget to enable logging? (see help).')
    if right_key is None:
        raise Exception('Right log does not contain ' + name + '. Did you ' +
                        'forget to enable logging? (see help).')
    queue = set([(name, (left_key, right_key))])
    result = []
    seen_keys = set()
    while len(queue) > 0:
        p = queue.pop()
        label, ref_pair = p
        (left_key, right_key) = ref_pair
        report, changed_key_pairs_with_labels = diffInternal(
            label,
            left_info.getByKey(left_key),
            left_info,
            right_info.getByKey(right_key),
            right_info,
            verbose)
        for e in changed_key_pairs_with_labels:
            label, ref_pair = e
            if ref_pair in seen_keys:
                continue
            seen_keys.add(ref_pair)
            queue.add(e)

        result += report
    return result


def main():
    args = parseArgs()
    if not os.path.exists(args.left_log):
        raise Exception(args.left_log + ' does not exist')
    left = RuleKeyStructureInfo(args.left_log)

    if not os.path.exists(args.right_log):
        raise Exception(args.right_log + ' does not exist')
    right = RuleKeyStructureInfo(args.right_log)

    print('\n'.join(diff(args.build_target, left, right, args.verbose)))


if __name__ == '__main__':
    main()
