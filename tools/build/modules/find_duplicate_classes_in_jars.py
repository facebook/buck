#!/usr/bin/env python
# Copyright 2017-present Facebook, Inc.
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


# Copyright 2017-present Facebook, Inc.
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


"""
Performs checks for consistency between the main Buck binary and jars with modules.

Detects duplicate classes that are present in multiple jars and report them.
"""

import logging
import re
import sys

from io import BytesIO
from zipfile import ZipFile


def main():
    argv = get_argv()

    if len(argv) < 2:
        print_help()
        return 0

    zip_file_path = argv[1]
    filename_patterns = argv[2:]

    logging.info("Analyzing %s" % zip_file_path)
    zip_file_entries = find_entries_in_zip_file(zip_file_path, filename_patterns)

    logging.info("Found entries: %s" % zip_file_entries)

    all_entries = read_list_of_entries_from_zip_file_entries(zip_file_path, zip_file_entries)
    class_entries = remove_non_class_entries(all_entries)

    report_class_entries(class_entries)

    reversed_entries = reverse_dict(class_entries)

    return check_entries_and_report_duplicates(reversed_entries)


def get_argv():
    if len(sys.argv) > 2 and (sys.argv[1] == '-v' or sys.argv[1] == '--verbose'):
        logging.getLogger().setLevel(logging.INFO)
        argv = sys.argv[0:0] + sys.argv[1:]
    else:
        argv = sys.argv
    return argv


def find_entries_in_zip_file(zip_file_path, filename_patterns):
    """
    Reads the given zip file and returns the list of entry names from this file that match at least
    one of the given file name patterns
    """

    found_entries = []
    filename_compiled_patterns = map(re.compile, filename_patterns)
    with ZipFile(zip_file_path) as zip_file:
        for zip_file_entry in zip_file.namelist():
            for filename_pattern in filename_compiled_patterns:
                if filename_pattern.match(zip_file_entry):
                    found_entries.append(zip_file_entry)
    return found_entries


def read_list_of_entries_from_zip_file_entries(zip_file_path, zip_file_entry_names):
    """
    Reads the list of entries in zip files stored inside another zip file.

    For example, `first.zip` contains the following:

    first.zip:
      a.zip
      b.zip
      c.jar

    a.zip:
      a.txt
      a.png

    b.zip:
      b.txt
      b.java

    c.jar:
      c.class
      c.png

    Calling `read_list_of_entries_from_zip_file_entries('first.zip', ('a.zip', 'c.jar'))` will
    return:

    {
      'a.zip': ['a.txt', 'a.png'],
      'c.jar': ['c.class', 'c.png'],
    }


    :param zip_file_path: path to the file file that contains (as entries) multiple zip files
    :param zip_file_entry_names: the entry names of the `zip_file_path` file that are zip files
           themselves. The entries of these zip files will be placed in the result.
    :return: dict of zip file entry name to the list of entry names inside that nested zip file
    """
    zip_file_entry_entries = {}
    with ZipFile(zip_file_path) as zip_file:
        for zip_file_entry_name in zip_file_entry_names:
            entry_file_data = BytesIO(zip_file.read(zip_file_entry_name))
            with ZipFile(entry_file_data) as entry_zip_file:
                zip_file_entry_entries[zip_file_entry_name] = []
                for entry_name in entry_zip_file.namelist():
                    zip_file_entry_entries[zip_file_entry_name].append(entry_name)
    return zip_file_entry_entries


def remove_non_class_entries(entries):
    """
    Removes entries that do not end with `.class`.

    :param entries: dict of zip file entry name to the list of entry names inside that
           nested zip file
    :return: entries without files that do not end with `.class`
    """
    new_entries = {}
    for entry_name in entries.keys():
        new_nested_entries = []
        for nested_entry in entries[entry_name]:
            if nested_entry[-6:] == '.class':
                new_nested_entries.append(nested_entry)
        new_entries[entry_name] = new_nested_entries
    return new_entries


def report_class_entries(entries):
    logging.info("Java class entries by jar:")
    for k in entries.keys():
        logging.info("%s: %s" % (k, len(entries[k])))


def reverse_dict(dict_to_reverse):
    """
    Reverses a dict.

    Given a dict with values that contain multiple elements creates a new dict with keys of the
    input dict used as values and values of the input dict used as keys.

    For example, using the following dict as input:

    {
        '1': ['a', 'b', 'c'],
        '2': ['b'],
        '3': ['d'],
    }

    would generated the following dict:

    {
        'a': ['1'],
        'b': ['1', '2'],
        'c': ['1'],
        'd`': ['3'],
    }
    """

    result = {}
    for k in dict_to_reverse.keys():
        for v in dict_to_reverse[k]:
            result.setdefault(v, []).append(k)
    return result


def check_entries_and_report_duplicates(class_entries):
    """
    Scans given dict with class entries and prints information about duplicate entries

    :return 0 if there no duplicates, 1 otherwise
    """

    rc = 0
    for class_entry in class_entries.keys():
        class_entry_jars = class_entries[class_entry]
        if len(class_entry_jars) > 1:
            logging.error("%s is present in (%s)" % (class_entry, ", ".join(class_entry_jars)))
            rc = 1
    return rc


def print_help():
    print """Analyzes jars stored in a zip file and detects classes that are present in multiple jars.

Usage:
    %s <zip_file> <jar_file_name_pattern> [<jar_file_name_pattern> ... ]
    """ % sys.argv[0]


if __name__ == '__main__':
    rc = main()

    if rc > 0:
        print("""
Found multiple classes in different jars. Please, refactor dependencies to include classes in the
main Buck binary only (buck_verser) or move classes to relevant modules.
        """)

    sys.exit(rc)
