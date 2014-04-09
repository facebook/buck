#!/usr/bin/env python

import argparse
import re
import sys
import xml.etree.ElementTree as ET

from collections import defaultdict

class LintProblem(object):
    """Stores a reference to a checkstyle problem.
    """
    def __init__(self, file_name, line, column, severity, message, source):
        self.file_name = file_name
        self.line = line
        self.column = column
        self.severity = severity
        self.message = message
        self.source = source

    def cloneWithNewOffset(self, line, column):
        return LintProblem(
            self.file_name,
            line,
            column,
            self.severity,
            self.message,
            self.source)


class Fixer(object):
    """Class to fix lint problems.
    """
    def matches(self, problem):
        """Returns "True" if this Fixer can fix the problem passed in.
        """
        return False

    def fix_it(self, problem):
        pass

    def get_translate_function(self, problem):
        return None

# Map of file names to an array of functions to apply to translate original FileIndex's to the new
# FileIndex after
OFFSET_FUNCTIONS=defaultdict(list)


def apply_offsets(problem):
    """Returns an updated FileIndex to account for any previous fixes.
    """
    for func in OFFSET_FUNCTIONS[problem.file_name]:
        problem = func(problem)
    return problem


FIXERS = []


def fixer(cls):
    FIXERS.append(cls())
    return cls


@fixer
class FixFollowedByWhitespace(Fixer):
    """Inserts missing whitespace after a token.
    """
    def matches(self, problem):
        return (problem.source == "com.puppycrawl.tools."
                                  "checkstyle.checks.whitespace."
                                  "WhitespaceAfterCheck")

    def fix_it(self, problem):
        result = []
        line_count = 1
        with open(problem.file_name, 'r') as file:
            for line in file.readlines():
                new_line = line
                if line_count == problem.line:
                    new_line = (line[0:problem.column-1] +
                                ' ' +
                                line[problem.column-1:])
                result.append(new_line)
                line_count += 1

        with open(problem.file_name, 'w') as file:
            file.write(''.join(result))
            file.truncate()

        return {
            'line': problem.line,
            'column': problem.column
        }

    def get_translate_function(self, translate_metadata):
        def translateFunc(problem):
            if (problem.line != translate_metadata['line'] or
                        problem.column < translate_metadata['column']):
                return problem
            else:
                return problem.cloneWithNewOffset(
                    problem.line,
                    problem.column + 1)

        return translateFunc


def main():
    parser = argparse.ArgumentParser(
        description='Automatically fix some lint problems')
    parser.add_argument(
        '--checkstyle_output_xml',
        action='store',
        help='The path to the XML output from a checkstyle run.')
    args = parser.parse_args()

    tree = ET.parse(args.checkstyle_output_xml)
    root = tree.getroot()
    for file in root.findall('file'):
        file_name = file.get('name')
        for error in file.findall('error'):
            problem = LintProblem(
                file_name,
                int(error.get('line')),
                int(error.get('column')),
                error.get('severity'),
                error.get('message'),
                error.get('source'))
            for fixer in FIXERS:
                if fixer.matches(problem):
                    translate_metadata = fixer.fix_it(apply_offsets(problem))
                    offset_function = fixer.get_translate_function(translate_metadata)
                    if offset_function:
                        OFFSET_FUNCTIONS[problem.file_name].append(offset_function)


if __name__ == "__main__":
    main()
