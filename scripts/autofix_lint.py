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

    def get_translate_function(self, translate_metadata):
        return None


class DeleteLineFixer(Fixer):
    """Fixes lint problems that are on a single line.
    """
    def fix_it(self, problem):
        result = []
        line_count = 1
        with open(problem.file_name, 'r') as file:
            for line in file.readlines():
                new_line = line
                if line_count != problem.line:
                    result.append(new_line)
                line_count += 1

        with open(problem.file_name, 'w') as file:
            file.write(''.join(result))
            file.truncate()

        return {
            'line': problem.line
        }

    def get_translate_function(self, translate_metadata):
        def translateFunc(problem):
            if problem.line < translate_metadata['line']:
                return problem
            else:
                return problem.cloneWithNewOffset(
                    problem.line - 1,
                    problem.column)

        return translateFunc


class SingleLineFixer(Fixer):
    """Fixes lint problems that are on a single line.
    """
    def fix_problem_line(self, problem, line):
        return line

    def modify_result(self, result, problem):
        return {}

    def fix_it(self, problem):
        result = []
        line_count = 1
        line_len_delta = 0
        with open(problem.file_name, 'r') as file:
            for line in file.readlines():
                new_line = line
                if line_count == problem.line:
                    new_line = self.fix_problem_line(problem, line)
                    line_len_delta = len(new_line) - len(line)
                result.append(new_line)
                line_count += 1

        additional_metadata = self.modify_result(result, problem)

        with open(problem.file_name, 'w') as file:
            file.write(''.join(result))
            file.truncate()

        additional_metadata.update({
            'line': problem.line,
            'column': problem.column,
            'line_len_delta': line_len_delta
        })

        return additional_metadata

    def get_translate_function(self, translate_metadata):
        def translateFunc(problem):
            if (problem.line != translate_metadata['line'] or
                    problem.column < translate_metadata['column']):
                return problem
            else:
                return problem.cloneWithNewOffset(
                    problem.line,
                    problem.column + translate_metadata['line_len_delta'])

        return translateFunc


# Map of file names to an array of functions to apply to translate original
# FileIndex's to the new FileIndex after
OFFSET_FUNCTIONS = defaultdict(list)


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
class FixTrailingOperator(Fixer):
    OPERATOR_REGEX = re.compile("'(?P<operator>\S+)' "
                                "should be on the previous line.")

    """Inserts missing whitespace after a token.
    """
    def matches(self, problem):
        return (problem.source == "com.puppycrawl.tools."
                                  "checkstyle.checks.whitespace."
                                  "OperatorWrapCheck")

    def fix_it(self, problem):
        """Inserts missing whitespace after a token.
        """
        result = []
        line_count = 1
        operator = FixTrailingOperator.OPERATOR_REGEX.match(
            problem.message).group('operator')

        line_len_delta = 0
        with open(problem.file_name, 'r') as file:
            for line in file.readlines():
                new_line = line
                if line_count == problem.line - 1:
                    new_line = line.rstrip() + ' ' + operator + '\n'
                elif line_count == problem.line:
                    oldlen = len(line)
                    new_line = re.sub(
                        re.escape(operator) + '\s*',
                        '',
                        line,
                        count=1)
                    line_len_delta = oldlen - len(new_line)
                result.append(new_line)
                line_count += 1

        with open(problem.file_name, 'w') as file:
            file.write(''.join(result))
            file.truncate()

        return {
            'line': problem.line,
            'column': problem.column,
            'line_len_delta': line_len_delta
        }

    def get_translate_function(self, translate_metadata):
        def translateFunc(problem):
            if (translate_metadata['line'] != problem.line or
                    translate_metadata['column'] < problem.column):
                return problem
            else:
                return problem.cloneWithNewOffset(
                    problem.line,
                    problem.column - translate_metadata['line_len_delta'])

        return translateFunc


@fixer
class FixFollowedByWhitespace(SingleLineFixer):
    """Inserts missing whitespace after a token.
    """
    def matches(self, problem):
        return (problem.source == "com.puppycrawl.tools."
                                  "checkstyle.checks.whitespace."
                                  "WhitespaceAfterCheck")

    def fix_problem_line(self, problem, line):
        return (line[0:problem.column - 1] +
                ' ' +
                line[problem.column - 1:])


@fixer
class FixJunit3Use(SingleLineFixer):
    """Convert JUnit3 -> JUnit4 APIs
    """
    JUNIT_ASSERT_RE = re.compile(
        'junit\.framework\.TestCase\.(fail|assert\w*)')

    def matches(self, problem):
        return (problem.message == 'The package junit.framework belongs to '
                                   'JUnit v3. Use org.junit instead.')

    def fix_problem_line(self, problem, line):
        match = FixJunit3Use.JUNIT_ASSERT_RE.search(line)
        if match:
            # Update the column of the problem to be where we first matched
            # the junit regex.
            problem.column = match.start()
        return FixJunit3Use.JUNIT_ASSERT_RE.sub(
            r"org.junit.Assert.\1",
            line,
            count=1)

    def modify_result(self, result, problem):
        additional_metadata = {}
        if problem.column and result[problem.line].startswith('import'):
            start_index = problem.line
            while result[start_index].startswith('import'):
                start_index -= 1

            end_index = problem.line
            while result[end_index].startswith('import'):
                end_index += 1

            # Hack to create a oldindex -> new index lookup.
            offset_array = sorted(
                xrange(start_index, end_index), key=result.__getitem__)

            additional_metadata = {
                'start_index': start_index,
                'end_index': end_index,
                'offset_array': offset_array
            }

            result[start_index:end_index] = sorted(
                result[start_index:end_index])
        return additional_metadata

    def get_translate_function(self, translate_metadata):
        """If applicable, correct for sorting imports.
        """
        if 'start_index' in translate_metadata:
            def translateFunc(problem):
                start_index = translate_metadata['start_index']
                end_index = translate_metadata['end_index']
                offset_array = translate_metadata['offset_array']
                if start_index <= problem.line < end_index:
                    new_line = offset_array.index(problem.line) + start_index
                    return problem.cloneWithNewOffset(
                        new_line,
                        problem.column)
                return problem
            return translateFunc

        return super(FixJunit3Use, self).get_translate_function(
            translate_metadata)


@fixer
class FixUnusedImport(DeleteLineFixer):
    """Fixes unused imports.
    """
    def matches(self, problem):
        return (problem.source == "com.puppycrawl.tools."
                                  "checkstyle.checks.imports."
                                  "UnusedImportsCheck")


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
            column = error.get('column')
            problem = LintProblem(
                file_name,
                int(error.get('line')),
                int(column or 0),
                error.get('severity'),
                error.get('message'),
                error.get('source'))
            for fixer in FIXERS:
                if fixer.matches(problem):
                    translate_metadata = fixer.fix_it(apply_offsets(problem))
                    offset_function = fixer.get_translate_function(
                        translate_metadata)
                    if offset_function:
                        OFFSET_FUNCTIONS[problem.file_name].append(
                            offset_function)


if __name__ == "__main__":
    main()
