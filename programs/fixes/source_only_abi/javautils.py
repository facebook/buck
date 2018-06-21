from __future__ import absolute_import, division, print_function, unicode_literals

from collections import namedtuple

Replacement = namedtuple("Replacement", ["line", "col", "old", "new"])

loaded_paths = {}


def load(path):
    if not path in loaded_paths:
        loaded_paths[path] = JavaFile(path)

    return loaded_paths[path]


def write_all():
    for java_file in loaded_paths.itervalues():
        java_file.write()


class JavaFile:
    def __init__(self, path):
        self.path = path
        with open(self.path) as java_file:
            self.lines = java_file.readlines()
        self.imports = set()
        self.replacements = set()

    def add_import(self, type):
        self.imports.add(type)

    def replace_name(self, line, col, old, new):
        if self.lines[line - 1][col - 1] == ".":
            col += 1
        self.replacements.add(Replacement(line, col, old, new))

    def write(self):
        for replacement in sorted(self.replacements, compare_replacements_reversed):
            line = self.lines[replacement.line - 1]
            self.lines[replacement.line - 1] = (
                line[0 : replacement.col - 1]
                + replacement.new
                + line[replacement.col + len(replacement.old) - 1 :]
            )

        first_import = None
        for i, line in enumerate(self.lines):
            stripped = line.strip()
            if stripped.startswith("import"):
                first_import = i
                break

        for type in self.imports:
            self.lines.insert(first_import, "import %s;\n" % type)

        with open(self.path, "w+") as java_file:
            java_file.writelines(self.lines)


def compare_replacements_reversed(a, b):
    if a.line != b.line:
        return b.line - a.line

    if a.col != b.col:
        return b.col - a.col

    return 0
