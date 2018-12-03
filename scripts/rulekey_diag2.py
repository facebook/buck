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

import math
import traceback

from rulekey_diff2 import *


def format_duration(duration_s):
    if duration_s < 0:
        return "-"
    if duration_s < 1e-3:
        return "%0.3f us" % (duration_s * 1e6)
    if duration_s < 1:
        return "%0.3f ms" % (duration_s * 1e3)
    t = math.floor(duration_s * 1000)
    ms = t % 1000
    t /= 1000
    s = t % 60
    t /= 60
    m = t % 60
    t /= 60
    h = t
    return "%02d:%02d:%02d.%03d" % (h, m, s, ms)


def truncate(s, l):
    """
    Truncates the string `s` to length `l`
    """
    if l is None or len(s) <= l:
        return s
    return (s[: l - 3] + "...") if l >= 3 else ""


class Table:
    """
    A printable table.
    """

    def __init__(self, rows=None, max_lengths=None):
        self.rows = rows or []
        self.max_lengths = max_lengths or []

    def add_row(self, row):
        self.rows.append(row)

    def max_length(self, i):
        return self.max_lengths[i] if i < len(self.max_lengths) else None

    def format_element(self, el, i):
        return truncate(" %s " % el, self.max_length(i))

    def __str__(self):
        lengths = []
        for row in self.rows:
            while len(lengths) < len(row):
                lengths.append(0)
            for i in range(len(row)):
                lengths[i] = max(lengths[i], len(self.format_element(row[i], i)))
        lines = []
        for row in self.rows:
            line = ""
            if len(row) > 0:
                line += "|"
                for i in range(len(lengths)):
                    if lengths[i] > 0:
                        el = self.format_element(row[i], i) if i < len(row) else ""
                        line += el + " " * (lengths[i] - len(el)) + "|"
            else:
                line += "+"
                for i in range(len(lengths)):
                    if lengths[i] > 0:
                        line += "-" * lengths[i] + "+"
            lines.append(line)
        return "\n".join(lines)


class NodeInfo:
    """
    A class that encapsulates per node information.
    """

    def __init__(self, line):
        parts = line.strip().split(" ")
        self.id = int(parts[0])
        self.duration = int(parts[1])
        self.total_duration = -1
        self.rule_type = parts[2]
        self.target = parts[3]
        self.is_cacheable = bool(int(parts[4]))
        self.default_key = parts[5]
        self.input_key = parts[6]
        self.depfile_key = parts[7]
        self.manifest_key = parts[8]
        self.output_hash = parts[9]

    def get_column_values(self):
        return [
            str(self.id),
            str(self.rule_type),
            str(self.target),
            format_duration(self.duration / 1e9),
            format_duration(self.total_duration / 1e9),
            str(self.is_cacheable),
            str(self.default_key),
            str(self.input_key),
            str(self.depfile_key),
            str(self.manifest_key),
            str(self.output_hash),
        ]

    @staticmethod
    def num_columns():
        return 11

    @staticmethod
    def get_column_names():
        return [
            "id",
            "rule_type",
            "target",
            "duration",
            "total_duration",
            "is_cacheable",
            "default_key",
            "input_key",
            "depfile_key",
            "manifest_key",
            "output_hash",
        ]

    @staticmethod
    def get_max_lengths():
        return [
            1000,  # "id"
            1000,  # "rule_type"
            1000,  # "target"
            1000,  # "duration"
            1000,  # "total_duration"
            1000,  # "is_cacheable"
            1000,  # "default_key"
            0,  # "input_key"
            0,  # "depfile_key"
            0,  # "manifest_key"
            1000,  # "output_hash"
        ]

    @staticmethod
    def compare_columns(info1, info2, col_id):
        if col_id == 0:
            return cmp(info1.id, info2.id)
        elif col_id == 1:
            return cmp(info1.rule_type, info2.rule_type)
        elif col_id == 2:
            return cmp(info1.target, info2.target)
        elif col_id == 3:
            return -cmp(info1.duration, info2.duration)
        elif col_id == 4:
            return -cmp(info1.total_duration, info2.total_duration)
        elif col_id == 5:
            return cmp(info1.is_cacheable, info2.is_cacheable)
        elif col_id == 6:
            return cmp(info1.default_key, info2.default_key)
        elif col_id == 7:
            return cmp(info1.input_key, info2.input_key)
        elif col_id == 8:
            return cmp(info1.depfile_key, info2.depfile_key)
        elif col_id == 9:
            return cmp(info1.manifest_key, info2.manifest_key)
        elif col_id == 10:
            return cmp(info1.output_hash, info2.output_hash)
        else:
            return 0


class Graph:
    """
    A class that encapsulates nodes and dependencies among them.
    """

    def __init__(self, lines):
        it = iter(lines)
        # int id_all, id_roots;
        t0 = time.clock()
        # read nodes
        n = int(next(it))
        self.nodes = [None] * n
        for i in range(n):
            info = NodeInfo(next(it))
            self.nodes[info.id] = info
        # read deps
        m = int(next(it))
        self.deps = [[] for _ in range((n + 2))]
        self.rdeps = [[] for _ in range((n + 2))]
        deg = [0] * (n + 1)
        for i in range(m):
            parts = next(it).split(" ")
            u = int(parts[0])
            v = int(parts[1])
            self.deps[u].append(v)
            self.rdeps[v].append(u)
            deg[v] += 1
        # all nodes: convenience sink (has all the nodes as its direct dep)
        self.id_all = n + 0
        self.deps[self.id_all] = range(n)
        # root nodes: convenience sink (has all the nodes that no node depends on)
        self.id_roots = n + 1
        self.deps[self.id_roots] = [i for i in range(n) if deg[i] == 0]
        # compute additional data
        self.calc_total_durations()
        self.calc_target_index()
        dt = time.clock() - t0
        eprint("nodes: ", n, ", edges: ", m, ", time: ", dt, sep="")

    def __len__(self):
        return len(self.nodes)

    def calc_total_durations(self):
        for u in range(len(self.nodes)):
            if self.nodes[u].total_duration == -1:
                total = 0
                stk = [u]
                visited = {u}
                while len(stk) > 0:
                    w = stk.pop()
                    total += self.nodes[w].duration
                    for v in self.deps[w]:
                        if v not in visited:
                            visited.add(v)
                            stk.append(v)
                self.nodes[u].total_duration = total

    def calc_target_index(self):
        self.target_index = {}
        for node in self.nodes:
            self.target_index[node.target] = node


def print_deps(g, u, sort_col_id, cacheable_only, reverse_deps=False):
    is_proper_node = 0 <= u < len(g)
    if not is_proper_node and u != g.id_all and u != g.id_roots:
        eprint("id out of range:", u)
        return

    def col_cmp(v1, v2):
        return NodeInfo.compare_columns(g.nodes[v1], g.nodes[v2], sort_col_id)

    def id_col(i):
        return str(i) + (" * " if i == sort_col_id else "")

    # build & print table
    tbl = Table([], NodeInfo.get_max_lengths())
    tbl.add_row([])
    tbl.add_row([id_col(i) for i in range(NodeInfo.num_columns())])
    tbl.add_row(NodeInfo.get_column_names())
    if is_proper_node:
        tbl.add_row(g.nodes[u].get_column_values())
    tbl.add_row([])
    deps = g.rdeps[u] if reverse_deps else g.deps[u]
    for v in sorted(deps, col_cmp):
        if g.nodes[v].is_cacheable or not cacheable_only:
            tbl.add_row(g.nodes[v].get_column_values())
    tbl.add_row([])
    if u == g.id_all:
        print("all:")
    elif u == g.id_roots:
        print("roots:")
    elif reverse_deps:
        print("reverse deps for:", u)
    else:
        print("deps for:", u)
    print(str(tbl))


def print_node(g, u):
    if u < 0 or u >= len(g):
        eprint("id out of range:", u)
        return
    tbl = Table()
    tbl.add_row([])
    for name, val in zip(NodeInfo.get_column_names(), g.nodes[u].get_column_values()):
        tbl.add_row([name, val])
    tbl.add_row([])
    print(str(tbl))


def print_help():
    description = """
interactive commands:
    q                   quits
    h                   prints this help message
    lg <path>           loads graph from a file
    c                   toggles cacheable_only mode (only cacheable rules get displayed)
    r                   shows root nodes
    a                   shows all nodes
    d 12                shows deps of node 12
    rd 12               shows reverse deps (parents) of node 12
    b                   shows deps of the previously queried node
    s 3                 selects 3-rd column as the sort column
    p 12                prints the node 12
    ft <pattern>        finds and lists all the targets matching the given pattern
    lk <path>           loads keys from a file
    lkr <path>          loads reference keys from a file
    fk <criteria>       finds and lists all the keys matching the given criteria
    fkr <criteria>      finds and lists all the reference keys matching the given criteria
                        criteria: pattern1 field1 [pattern2 field2 pattern3 field3 ...]
                        e.g.: `fk java/com/example/my_target .target_name`
    pk <hahs>           shows rulekey composition for the given key hash
    pkr <hahs>          shows rulekey composition for the given reference key hash
    pt                  shows all the targets whose rulekey differ from the reference key hash
    pta                 shows all the targets that are present in the both keys sets
    dt <target>         diffs the keys and reference keys for the given target
    gv path             saves the graph as a graphviz file

    Note that some commands require the corresponding graph or keys file to be loaded.
    These files can be found at `buck-out/log/{build}/rule_key_diag_{graph|keys}.txt`.
    It is enough to specify just the containing folder though.
    """
    print(description)


class Diag:
    """
    A class that encapsulates diagnostics state such as graph and rulekey data.
    """

    def __init__(self):
        self.graph = None
        self.keys = None
        self.targets_to_keys = None
        self.keys_ref = None
        self.targets_to_keys_ref = None

    def load_graph(self, path):
        if not os.path.exists(path):
            raise Exception(path + " does not exist")
        if os.path.isdir(path):
            path = os.path.join(path, "rule_key_diag_graph.txt")
        eprint("Loading:", path)
        with codecs.open(path, "r", "utf-8") as graph_file:
            self.graph = Graph(graph_file)

    def load_keys(self, path):
        self.keys = read_rulekeys_autodetect(path)
        self.targets_to_keys = build_targets_to_rulekeys_index(self.keys)

    def load_keys_ref(self, path):
        self.keys_ref = read_rulekeys_autodetect(path)
        self.targets_to_keys_ref = build_targets_to_rulekeys_index(self.keys_ref)

    def print_targets_intersection(self, only_different, only_cacheable):
        same = 0
        nonc = 0
        for target, keys in self.targets_to_keys.iteritems():
            if target in self.targets_to_keys_ref:
                keys_ref = self.targets_to_keys_ref[target]
                cacheable = False
                if self.graph is not None and target in self.graph.target_index:
                    cacheable = self.graph.target_index[target].is_cacheable
                if keys == keys_ref:
                    same += 1
                    if not cacheable:
                        nonc += 1
                if (keys != keys_ref or not only_different) and (
                    cacheable or not only_cacheable
                ):
                    print(target, keys, keys_ref)
        print("%d with mathcing keys, %d out of which non-cacheable" % (same, nonc))

    def save_graphviz(self, path):
        # $ dot -Tpng graph.gv -o graph.png
        with codecs.open(path, "w", "utf-8") as gv_file:
            print("digraph G {", file=gv_file)
            for u in range(len(self.graph)):
                label = self.graph.nodes[u].target
                print('  %d [shape=box, label="%s"];' % (u, label), file=gv_file)
            for u in range(len(self.graph)):
                for v in self.graph.deps[u]:
                    print("  %d -> %d;" % (u, v), file=gv_file)
            print("}", file=gv_file)

    def find_targets(self, pattern):
        regex = re.compile(pattern)
        res = []
        for u in range(len(self.graph)):
            if regex.search(self.graph.nodes[u].target) is not None:
                res.append(u)
        return res

    def diff_keys_for_target(self, target):
        # use the prebuilt index when finding keys for target
        keys_left = {k: self.keys[k] for k in self.targets_to_keys.get(target, [])}
        keys_right = {
            k: self.keys_ref[k] for k in self.targets_to_keys_ref.get(target, [])
        }
        keys_tuple_to_diff = find_keys_for_target(keys_left, keys_right, target)
        if keys_tuple_to_diff is not None:
            diff_keys_recursively(self.keys, self.keys_ref, keys_tuple_to_diff)


def parse_args():
    description = """
    """
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument(
        "-g", "--graph", metavar="<path>", help="path to a graph file to load"
    )
    parser.add_argument(
        "-k", "--keys", metavar="<path>", help="path to a keys file to load"
    )
    parser.add_argument(
        "-l",
        "--log_folder",
        metavar="<path>",
        help="folder containing the keys and graph file (use instead of -g and -k)",
    )
    parser.add_argument(
        "-r",
        "--ref_keys",
        metavar="<path>",
        help="path to a reference keys file to load",
    )

    if len(sys.argv) == 1:
        parser.print_help()
        print_help()
    return parser.parse_args()


def main():
    print("RuleKey Diagnostics script v0.1")

    d = Diag()

    args = parse_args()
    if (args.graph or args.log_folder) is not None:
        d.load_graph(args.graph or args.log_folder)
    if (args.keys or args.log_folder) is not None:
        d.load_keys(args.keys or args.log_folder)
    if args.ref_keys is not None:
        d.load_keys_ref(args.ref_keys)

    bstk = []
    sort_col_id = 4
    cacheable_only = True
    while True:
        try:
            print()
            parts = raw_input("> ").strip().split(" ")
        except EOFError:
            break
        try:
            cmd = parts[0]
            if cmd == "q" or cmd == "quit":
                break
            if cmd == "h" or cmd == "help":
                print_help()
            elif cmd == "lg" or cmd == "load_graph":
                path = parts[1]
                d.load_graph(path)
            elif cmd == "r" or cmd == "roots":
                bstk.append(d.graph.id_roots)
                print_deps(d.graph, bstk[-1], sort_col_id, cacheable_only)
            elif cmd == "a" or cmd == "all":
                bstk.append(d.graph.id_all)
                print_deps(d.graph, bstk[-1], sort_col_id, cacheable_only)
            elif cmd == "c" or cmd == "cacheable_only":
                cacheable_only = not cacheable_only
                print("cacheable_only: %s" % cacheable_only)
            elif cmd == "d" or cmd == "deps":
                bstk.append(int(parts[1]))
                print_deps(d.graph, bstk[-1], sort_col_id, cacheable_only)
            elif cmd == "rd" or cmd == "parents":
                bstk.append(int(parts[1]))
                print_deps(d.graph, bstk[-1], sort_col_id, cacheable_only, True)
            elif cmd == "b" or cmd == "back":
                if len(bstk) > 1:
                    bstk.pop()
                print_deps(d.graph, bstk[-1], sort_col_id, cacheable_only)
            elif cmd == "s" or cmd == "sort":
                sort_col_id = int(parts[1])
                print_deps(d.graph, bstk[-1], sort_col_id, cacheable_only)
            elif cmd == "p" or cmd == "print":
                u = int(parts[1])
                print_node(d.graph, u)
            elif cmd == "ft" or cmd == "find_target":
                pattern = parts[1]
                for u in d.find_targets(pattern):
                    print(u, d.graph.nodes[u].target)
            elif cmd == "lk" or cmd == "load_keys":
                path = parts[1]
                d.load_keys(path)
            elif cmd == "lkr" or cmd == "load_keys_ref":
                path = parts[1]
                d.load_keys_ref(path)
            elif cmd == "fk" or cmd == "find_keys":
                criteria = zip(*[iter(parts[1:])] * 2)
                for key in find_keys(d.keys, criteria):
                    print(key)
            elif cmd == "fkr" or cmd == "find_keys_ref":
                criteria = zip(*[iter(parts[1:])] * 2)
                for key in find_keys(d.keys_ref, criteria):
                    print(key)
            elif cmd == "pk" or cmd == "print_key":
                rulekey_hash = parts[1]
                print_rulekey(d.keys.get(rulekey_hash, []))
            elif cmd == "pkr" or cmd == "print_key_ref":
                rulekey_hash = parts[1]
                print_rulekey(d.keys_ref.get(rulekey_hash, []))
            elif cmd == "pt" or cmd == "print_targets":
                d.print_targets_intersection(True, cacheable_only)
            elif cmd == "pta" or cmd == "print_targets_all":
                d.print_targets_intersection(False, cacheable_only)
            elif cmd == "dt" or cmd == "diff_target":
                try:
                    d.diff_keys_for_target(d.graph.nodes[int(parts[1])].target)
                except ValueError:
                    d.diff_keys_for_target(parts[1])
            elif cmd == "gv" or cmd == "save_graphviz":
                path = parts[1]
                d.save_graphviz(path)
            else:
                eprint("unknown command: ", parts)
        except Exception:
            eprint(
                "Something went wrong. Make sure you loaded all the required data and\n"
                "specified all the required arguments necessary for performing the command."
            )
            eprint(traceback.format_exc())


if __name__ == "__main__":
    main()
