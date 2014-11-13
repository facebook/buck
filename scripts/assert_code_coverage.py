#!/usr/bin/env python

import xml.etree.ElementTree as ElementTree
import sys

# This parses buck-out/gen/jacoco/code-coverage/coverage.xml after
# `buck test --all --code-coverage --code-coverage-format xml --no-results-cache`
# has been run.
PATH_TO_CODE_COVERAGE_XML = 'buck-out/gen/jacoco/code-coverage/coverage.xml'

# If the code coverage for the project drops below this threshold,
# fail the build. This is designed to far enough below our current
# standards (80% coverage) that this should be possible to sustain
# given the inevitable ebb and flow of the code coverage level.
CODE_COVERAGE_GOAL = 78


def is_covered_package_name(package_name):
    """We exclude third-party code."""
    if not package_name.startswith('com/facebook/buck/'):
        return False
    return True


def calculate_code_coverage():
    root = ElementTree.parse(PATH_TO_CODE_COVERAGE_XML)

    # The length of the longest Java package included in the report.
    # Used for display purposes.
    max_package_name = 0

    # Coverage types measured by Jacoco.
    TYPES = set([
        'BRANCH',
        'CLASS',
        'COMPLEXITY',
        'INSTRUCTION',
        'LINE',
        'METHOD',
    ])

    # List determines column display order in final report.
    COLUMN_NAMES = [
        'INSTRUCTION',
        'LINE',
        'BRANCH',
        'METHOD',
        'CLASS',
        'LOC2FIX',
    ]

    # Column by which rows will be sorted in the final report.
    SORT_TYPE = 'INSTRUCTION'

    # Keys are values from TYPES; values are integers.
    total_covered_by_type = {}
    total_missed_plus_covered_type = {}
    for coverage_type in TYPES:
        total_covered_by_type[coverage_type] = 0
        total_missed_plus_covered_type[coverage_type] = 0

    # Values are dicts. Will have key 'package_name' as well as all values
    # from TYPES as keys. For entries from TYPES, values are the corresponding
    # coverage percentage for that type.
    coverage_by_package = []

    # Track count of untested lines to see which packages
    # have the largest amount of untested code.
    missed_lines_by_package = {}
    total_missed_lines = 0

    for element in root.findall('.//package'):
        package_name = element.attrib['name']
        if not is_covered_package_name(package_name):
            continue

        max_package_name = max(max_package_name, len(package_name))
        coverage = {}
        coverage['package_name'] = package_name
        coverage_by_package.append(coverage)
        for counter in element.findall('./counter'):
            counter_type = counter.attrib.get('type')
            missed = int(counter.attrib.get('missed'))
            covered = int(counter.attrib.get('covered'))

            percentage = round(100 * covered / float(missed + covered), 2)
            total_covered_by_type[counter_type] += covered
            total_missed_plus_covered_type[counter_type] += missed + covered
            coverage[counter_type] = percentage

            if counter_type == 'LINE':
                missed_lines_by_package[package_name] = missed
                total_missed_lines += missed

    def pair_compare(p1, p2):
        # High percentage should be listed first.
        diff1 = cmp(p2[SORT_TYPE], p1[SORT_TYPE])
        if diff1:
            return diff1
        # Ties are broken by lexicographic comparison.
        return cmp(p1['package_name'], p2['package_name'])

    def label_with_padding(label):
        return label + ' ' * (max_package_name - len(label)) + ' '

    def column_format_str(column_name):
        if column_name == 'LOC2FIX':
            return '%(' + column_name + ')8d'
        else:
            return '%(' + column_name + ')7.2f%%'

    def print_separator(sep_len):
        print '-' * sep_len

    def get_color_for_percentage(percentage):
        # \033[92m is OKGREEN.
        # \033[93m is WARNING.
        return '\033[92m' if percentage >= CODE_COVERAGE_GOAL else '\033[93m'

    # Print header.
    # Type column headers are right-justified and truncated to 7 characters.
    column_names = map(lambda x: x[0:7].rjust(7), COLUMN_NAMES)
    print label_with_padding('PACKAGE') + ' ' + ' '.join(column_names)
    separator_len = max_package_name + 1 + len(column_names) * 8
    print_separator(separator_len)

    # Create the format string to use for each row.
    format_string = '%(color)s%(label)s'
    for column in COLUMN_NAMES:
        format_string += column_format_str(column)
    format_string += '\033[0m'

    # Print rows sorted by line coverage then package name.
    coverage_by_package.sort(cmp=pair_compare)
    for item in coverage_by_package:
        info = item.copy()
        pkg = item['package_name']
        if not 'BRANCH' in info:
            # It is possible to have a module of Java code with no branches.
            info['BRANCH'] = 100
        info['color'] = get_color_for_percentage(item[SORT_TYPE])
        info['label'] = label_with_padding(pkg)
        info['LOC2FIX'] = missed_lines_by_package[pkg]
        print format_string % info

    # Print aggregate numbers.
    overall_percentages = {}
    for coverage_type in TYPES:
        numerator = total_covered_by_type[coverage_type]
        denominator = total_missed_plus_covered_type[coverage_type]
        percentage = 100.0 * numerator / denominator
        overall_percentages[coverage_type] = percentage

    observed_percentage = overall_percentages[SORT_TYPE]
    overall_percentages['color'] = get_color_for_percentage(observed_percentage)
    overall_percentages['label'] = label_with_padding('TOTAL')
    overall_percentages['LOC2FIX'] = total_missed_lines
    print_separator(separator_len)
    print format_string % overall_percentages

    return observed_percentage


def main():
    """Exits with 0 or 1 depending on whether the code coverage goal is met."""
    coverage = calculate_code_coverage()
    if coverage < CODE_COVERAGE_GOAL:
        data = {
            'expected': CODE_COVERAGE_GOAL,
            'observed': coverage,
        }
        print '\033[91mFAIL: %(observed).2f%% does not meet goal of %(expected).2f%%\033[0m' % data
        sys.exit(1)


if __name__ == '__main__':
    main()
