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

import argparse
import codecs
import os
import re
import sys
import time


def eprint(*args, **kwargs):
    print(*args, file=sys.stderr, **kwargs)


def gather(elements, key_func):
    res = {}
    for el in elements:
        key = key_func(el)
        if key is None:
            continue
        if key not in res:
            res[key] = []
        res[key].append(el)
    return res


def ordinals(elements):
    res = {}
    o = 0
    for el in elements:
        res[el] = o
        o += 1
    return res


def tokenize_rulekey_line(line):
    """
    Tokenizes a line of the form: '<type>(<value>):<type>(<value>):...<type>(<value>):'
    Tokens are returned in reverse order, because the serialization was also reversed.
    """
    # Since ':' can appear within a token value, we cheat and delimit tokens by '):'.
    delimiter = "):"
    return [token + ")" for token in reversed(line.split(delimiter)) if len(token) > 1]


def read_rulekeys(keys_file, prefix, delimiter):
    """
    Parses lines of the form: '<anything> <prefix> <key> <delimiter> <tokens>',
    where <tokens> structure is described in `tokenize_rulekey_line`.
    """
    t0 = time.clock()
    keys = {}
    line_cnt = 0
    for line in keys_file:
        line = line.strip()
        line_cnt += 1
        pos_p = line.find(prefix)
        if pos_p < 0:
            continue
        pos_p += len(prefix)
        pos_t = line.find(delimiter, pos_p)
        if pos_t < 0:
            continue
        key = line[pos_p:pos_t]
        pos_t += len(delimiter)
        keys[key] = tokenize_rulekey_line(line[pos_t:])
    dt = time.clock() - t0
    eprint("parsed lines: ", line_cnt, ", keys: ", len(keys), ", time: ", dt, sep="")
    return keys


def read_rulekeys_autodetect(path):
    """
    Automatically detects the file format based one the first line and reads the rulekey data.
    """
    if not os.path.exists(path):
        raise Exception(path + " does not exist")
    if os.path.isdir(path):
        path = os.path.join(path, "rule_key_diag_keys.txt")
    eprint("Loading:", path)
    with codecs.open(path, "r", "utf-8") as keys_file:
        line = keys_file.readline()
    # reopen again as we consumed the first line above
    with codecs.open(path, "r", "utf-8") as keys_file:
        if line.startswith("["):
            eprint("Looks like a buck-0.log file...")
            prefix = "[com.facebook.buck.rules.keys.RuleKeyBuilder] RuleKey "
            return read_rulekeys(keys_file, prefix, "=")
        else:
            eprint("Looks like a rule_key_diag_keys.txt file...")
            return read_rulekeys(keys_file, "", " ")


TOKEN_TYPE_REGEX = re.compile(r"([a-zA-Z]+)\(")
TOKEN_VALUE_REGEX = re.compile(r"[a-zA-Z]+\((.*)\)")
CONTAINER_LENGTH_REGEX = re.compile(r"container\(.*,len=(\d+)\)")

TARGET_NAME_TOKEN_PATTERN = r"key\(\.target_name\)"
TARGET_NAME_FIELD_PATTERN = r"\.target_name"
RULEKEY_TYPE_FIELD_PATTERN = r"\.rule_key_type"


def token_type(token):
    match = TOKEN_TYPE_REGEX.match(token)
    if match is None:
        eprint("Invalid token: " + str(token))
    return match.group(1) if match is not None else None


def token_value(token):
    match = TOKEN_VALUE_REGEX.match(token)
    if match is None:
        eprint("Invalid token: " + str(token))
    return match.group(1) if match is not None else None


def token_length(token):
    if token.startswith("key"):
        return 1
    if token.startswith("wrapper"):
        return 1
    if token.startswith("container"):
        return int(CONTAINER_LENGTH_REGEX.match(token).group(1))
    return 0


def print_rulekey(tokens):
    """
    Prints the serialized (non-structured) rulekey in a structured way.
    """
    stk = []
    for token in tokens:
        prefix = "  " * len(stk)
        print(prefix, token, sep="")
        if len(stk) > 0:
            stk[-1] -= 1
        stk.append(token_length(token))
        while len(stk) > 0 and stk[-1] == 0:
            stk.pop()


class RulekeyStruct:
    """
    Represents diagnostic rulekey in a structured way, where the structure resembles that of the
    source code.
    """

    def __init__(self, token):
        self.token = token
        self._children = []
        self._hashcode = None

    def append(self, child):
        self._children.append(child)

    def type(self):
        return token_type(self.token)

    def is_primitive(self):
        return len(self._children) == 0 and token_length(self.token) == 0

    def __str__(self):
        return str(self.token) + (
            "" if self.is_primitive() else ":" + str(self._children)
        )

    def __repr__(self):
        return repr(self.token) + (
            "" if self.is_primitive() else ":" + repr(self._children)
        )

    def __iter__(self):
        return iter(self._children)

    def __getitem__(self, index):
        return self._children[index] if index is not None else None

    def __len__(self):
        return len(self._children)

    def __hash__(self):
        if self._hashcode is None:
            self._hashcode = hash(self.token)
            for ch in self._children:
                self._hashcode = self._hashcode * 31 + hash(ch)
        return self._hashcode

    def __eq__(self, other):
        if isinstance(other, self.__class__):
            return self.token == other.token and self._children == other._children
        return NotImplemented

    def __ne__(self, other):
        if isinstance(other, self.__class__):
            return not self.__eq__(other)
        return NotImplemented


def reconstruct_rulekey(tokens):
    """
    Reconstructs the structured rulekey from its serialized representation.
    """
    stk = []
    res = [RulekeyStruct("root()")]
    for token in tokens:
        if len(stk) > 0:
            stk[-1] -= 1
        el = RulekeyStruct(token)
        res[-1].append(el)
        stk.append(token_length(token))
        res.append(el)
        while len(stk) > 0 and stk[-1] == 0:
            stk.pop()
            res.pop()
    return res[-1]


def match_elements(struct1, struct2):
    """
    Heuristically matches elements from struct1 with those in struct2.
    """
    # TODO(plamenko): match nested wrapper(BUILD_RULE)/ruleKey(sha1=...) ??
    matches1 = {}
    matches2 = {}
    # if both structures have only one non-key-token element, match them together
    if (
        len(struct1) == 1
        and struct1[0].type() != "key"
        and len(struct2) == 1
        and struct2[0].type() != "key"
    ):
        return {0: 0}, {0: 0}
    # first match elements that are equal (assume there are no hash collisions)
    orders1 = {hash(el): i for i, el in enumerate(struct1)}
    orders2 = {hash(el): i for i, el in enumerate(struct2)}
    for h in set(orders1) & set(orders2):
        matches1[orders1[h]] = orders2[h]
        matches2[orders2[h]] = orders1[h]
    # next, match unmatched elements with the same key-type token
    orders1 = {
        el.token: i
        for i, el in enumerate(struct1)
        if el.type() == "key" and i not in matches1
    }
    orders2 = {
        el.token: i
        for i, el in enumerate(struct2)
        if el.type() == "key" and i not in matches2
    }
    for token in set(orders1) & set(orders2):
        matches1[orders1[token]] = orders2[token]
        matches2[orders2[token]] = orders1[token]
    # finally, match unmatched elements with the same token non-key-type (do so in the same order)
    orders1 = gather(
        range(len(struct1)), lambda i: struct1[i].type() if i not in matches1 else None
    )
    orders2 = gather(
        range(len(struct2)), lambda i: struct2[i].type() if i not in matches2 else None
    )
    for ttype in set(orders1) & set(orders2):
        if token_type == "key":
            continue
        for i in range(min(len(orders1[ttype]), len(orders2[ttype]))):
            matches1[orders1[ttype][i]] = orders2[ttype][i]
            matches2[orders2[ttype][i]] = orders1[ttype][i]
    return matches1, matches2


def get_token(struct):
    return struct.token if struct is not None else None


def get_type(struct):
    return struct.type() if struct is not None else None


def diff_rulekeys(struct1, struct2, visitor, path1="", path2=""):
    """
    Compares two structured rulekeys and returns a list of differences.
    """
    path1 += "/" + str(get_token(struct1)) if struct1 is not None else ""
    path2 += "/" + str(get_token(struct2)) if struct2 is not None else ""

    # nothing to compare
    if struct1 is None and struct2 is None:
        return
    # record token difference
    if get_token(struct1) != get_token(struct2):
        visitor(path1, struct1, path2, struct2)
    # if the type is different there is no point comparing the content
    if get_type(struct1) != get_type(struct2):
        return

    # match inner elements before comparing
    (matches1, matches2) = match_elements(struct1, struct2)
    matches_set1 = {(i1, matches1.get(i1, None)) for i1 in range(len(struct1))}
    matches_set2 = {(matches2.get(i2, None), i2) for i2 in range(len(struct2))}

    # record differences in inner elements order
    ord1 = ordinals(sorted(matches1.keys()))
    ord2 = ordinals(sorted(matches2.keys()))
    orders = [
        (i1, i2)
        for i1, i2 in sorted(matches_set1 & matches_set2)
        if ord1[i1] != ord2[i2]
    ]
    if len(orders) > 0:
        visitor(
            path1 + ":order" + str([i1 for i1, i2 in orders]),
            struct1,
            path2 + ":order" + str([i2 for i1, i2 in orders]),
            struct2,
        )

    # recursively compare inner elements
    for (i1, i2) in sorted(matches_set1 | matches_set2):
        diff_rulekeys(
            struct1[i1],
            struct2[i2],
            visitor,
            path1 + ":" + str(i1),
            path2 + ":" + str(i2),
        )


def diff_rulekeys_to_list(struct1, struct2):
    res = []

    def visitor(v1, s1, v2, s2):
        res.append((v1, s1, v2, s2))

    diff_rulekeys(struct1, struct2, visitor)
    return res


def find_children(struct, pattern, max_depth=1000, res=None):
    """
    Finds all the children structs whose token match the given pattern
    """
    if res is None:
        res = []
    if re.search(pattern, get_token(struct)):
        res.append(struct)
    if max_depth > 0:
        for ch in struct:
            find_children(ch, pattern, max_depth - 1, res)
    return res


def find_keys(keys, criteria):
    """
    Finds all the keys that satisfy *all* of the given criterions.
    A criterion is a tuple consisting of a pattern and optionally a field name.
    If field name is specified, only tokens under the given field name are considered.
    """
    if len(criteria) == 0:
        return []
    res = []
    for key, tokens in keys.iteritems():
        for pattern, field_name in criteria:
            field_found = field_name is None
            for token in tokens:
                if field_name is not None and token_type(token) == "key":
                    field_found = re.search(field_name, token_value(token)) is not None
                if field_found and re.search(pattern, token) is not None:
                    break
            else:
                # loop finished normally, meaning the current criterion was not met
                break
        else:
            # loop finished normally, meaning all the criterions were met
            res.append(key)
    return res


def extract_target(struct):
    """
    Extracts the target name from a structured rulekey
    """
    for s in find_children(struct, TARGET_NAME_TOKEN_PATTERN, 1):
        if len(s) == 1 and token_type(s[0].token) == "string":
            return token_value(s[0].token)[1:-1]  # strip quotes
    return None


def build_targets_to_rulekeys_index(keys):
    """
    Builds the index of target -> rulekeys
    """
    res = {}
    t0 = time.clock()
    for key, tokens in keys.iteritems():
        target = extract_target(reconstruct_rulekey(tokens))
        if target is not None:
            if target not in res:
                res[target] = []
            res[target].append(key)
    dt = time.clock() - t0
    eprint("index built; targets: ", len(res), ", time: ", dt, sep="")
    return res


def find_keys_for_target(keys_left, keys_right, target):
    """
    Finds a (left, right) pair of default rule keys in the respective keys dictionaries
    for the given target
    """
    print("Searching for the specified build target...")
    print(target)
    target_pattern = '"' + target + '"'
    criteria = [
        (target_pattern, TARGET_NAME_FIELD_PATTERN),
        (r"DEFAULT", RULEKEY_TYPE_FIELD_PATTERN),
    ]
    rulekeys_left = find_keys(keys_left, criteria)
    rulekeys_right = find_keys(keys_right, criteria)
    if not check_rulekeys_count(rulekeys_left, "left", target):
        return None
    if not check_rulekeys_count(rulekeys_right, "right", target):
        return None
    return (rulekeys_left[0], rulekeys_right[0])


def diff_keys_recursively(
    keys_left,
    keys_right,
    keys_tuple_to_diff,
    report_only_unique_causes=True,
    max_differences_to_report=100,
):
    """
    Recursively lists the differences starting from the given (left, right) rule keys pair
    """
    print("Comparing keys...")
    keys_path_stack = []
    processed_keys = set()
    reported_keys = set()
    reported_differences = set()

    def print_path():
        for (rk1, rk2) in keys_path_stack:
            if rk1 in reported_keys and rk2 in reported_keys:
                continue
            reported_keys.add(rk1)
            reported_keys.add(rk2)
            target = "unknown"
            if rk1 in keys_left:
                target = extract_target(reconstruct_rulekey(keys_left[rk1]))
            elif rk2 in keys_right:
                target = extract_target(reconstruct_rulekey(keys_right[rk2]))
            print("%s vs %s (%s) ..." % (rk1, rk2, target))

    def rec(rulekey1, rulekey2):
        if rulekey1 in processed_keys and rulekey2 in processed_keys:
            return
        processed_keys.add(rulekey1)
        processed_keys.add(rulekey2)
        keys_path_stack.append((rulekey1, rulekey2))
        if rulekey1 in keys_left and rulekey2 in keys_right:
            struct1 = reconstruct_rulekey(keys_left[rulekey1])
            struct2 = reconstruct_rulekey(keys_right[rulekey2])
            for (v1, s1, v2, s2) in diff_rulekeys_to_list(struct1, struct2):
                t1 = get_token(s1)
                t2 = get_token(s2)
                if len(reported_differences) >= max_differences_to_report:
                    print(
                        "stopped after reporting %d differences"
                        % len(reported_differences)
                    )
                    break
                if get_type(s1) == "ruleKey" and get_type(s2) == "ruleKey":
                    # value looks like 'sha1=8960bfa79841fa1482f7...', strip 'sha1='
                    rec(token_value(t1)[5:], token_value(t2)[5:])
                elif (
                    not report_only_unique_causes
                    or (t1, t2) not in reported_differences
                ):
                    reported_differences.add((t1, t2))
                    print_path()
                    print("  L:", v1)
                    print("  R:", v2)
        else:
            # no more rulekey diagnostics data, probably due to a cache hit so not built locally
            pass
        keys_path_stack.pop()

    rec(keys_tuple_to_diff[0], keys_tuple_to_diff[1])


def check_for_absolute_paths(keys):
    eprint("Checking for absolute paths (should not happen)...")
    for key, tokens in keys.iteritems():
        for token in tokens:
            if '"/Users' in token:
                print_rulekey(tokens)
                eprint("^^ %s contains an abslute path: %s" % (key, token))
                return True
    return False


def parse_args():
    description = """
    """
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("left_log", help="buck.log file to look at first.")
    parser.add_argument("right_log", help="buck.log file to look at second.")
    parser.add_argument(
        "build_target",
        help="Name of the build target for which to analyze the rulekeys.",
    )
    parser.add_argument(
        "-d",
        "--report_duplicate",
        action="store_true",
        help="whether to report already reported causes",
    )
    parser.add_argument(
        "--max-differences-to-report",
        type=int,
        default=100,
        help="Maximum number of differences to report before giving up.",
    )

    # Print full help message if we're invoked with no arguments (otherwise
    # you get the shortened 1 line 'usage' message.
    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit(1)
    return parser.parse_args()


def check_rulekeys_count(rulekeys, side, target):
    if len(rulekeys) == 1:
        return True
    elif len(rulekeys) == 0:
        eprint('No rulekey found on %s for target "%s"' % (side, target))
        return False
    else:
        eprint(
            'Expected 1 rulekey on %s for target "%s", found %d'
            % (side, target, len(rulekeys))
        )
        for rulekey in rulekeys:
            eprint("  ", rulekey)
        return True


def main():
    args = parse_args()
    keys_left = read_rulekeys_autodetect(args.left_log)
    if check_for_absolute_paths(keys_left):
        return
    keys_right = read_rulekeys_autodetect(args.right_log)
    if check_for_absolute_paths(keys_right):
        return
    keys_tuple_to_diff = find_keys_for_target(keys_left, keys_right, args.build_target)
    diff_keys_recursively(
        keys_left,
        keys_right,
        keys_tuple_to_diff,
        not args.report_duplicate,
        args.max_differences_to_report,
    )


if __name__ == "__main__":
    main()
