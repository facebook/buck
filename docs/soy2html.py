#!/usr/bin/env python
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
#
# This script is designed to be invoked by soy2html.sh.
# Usage:
#
#    buck/docs$ python soy2html.py <output_dir>
#
# This will write all of the static content to the specified output directory.
# You may want to verify that things worked correctly by running:
#
#    python -m SimpleHTTPServer <output_dir>
#
# and then navigating to http://localhost:8000/.
#
# When this script is run, soyweb should already be running locally on port
# 9814 via ./docs/soyweb-prod.sh.


import os
import subprocess
import sys
import time


URL_ROOT = 'http://localhost:9814/'


def main(output_dir):
    # Iterate over the files in the docs directory and copy them, as
    # appropriate.
    for root, dirs, files in os.walk('.'):
        for file_name in files:
            if file_name.endswith('.soy') and not file_name.startswith('__'):
                # Strip the './' prefix, if appropriate.
                if root.startswith('./'):
                    root = root[2:]

                # Construct the URL where the .soy file is being served.
                soy_file = file_name
                html_file = root + '/' + soy_file[:-len('.soy')] + '.html'
                url = URL_ROOT + html_file

                copy_dest = ensure_dir(html_file, output_dir)
                subprocess.check_call([
                    "curl", "--fail", "--output", copy_dest, url
                ])
            elif (file_name == ".nojekyll" or
                  file_name == "CNAME" or
                  file_name.endswith('.css') or
                  file_name.endswith('.jpg') or
                  file_name.endswith('.js') or
                  file_name.endswith('.png') or
                  file_name.endswith('.gif') or
                  file_name.endswith('.html') or
                  file_name.endswith('.md') or
                  file_name.endswith('.svg') or
                  file_name.endswith('.ttf') or
                  file_name.endswith('.txt')):
                #  Copy the static resource to output_dir.
                relative_path = os.path.join(root, file_name)
                with open(relative_path) as resource_file:
                    resource = resource_file.read()
                    copy_to_output_dir(relative_path, output_dir, resource)


def ensure_dir(path, output_dir):
    last_slash = path.rfind('/')
    if last_slash != -1:
        output_subdir = os.path.join(output_dir, path[:last_slash])
        if not os.path.exists(output_subdir):
            os.makedirs(output_subdir)

    return os.path.join(output_dir, path)


def copy_to_output_dir(path, output_dir, content):
    output_file = ensure_dir(path, output_dir)
    with open(output_file, 'w') as f:
        f.write(content)


def pollForServerReady():
    SERVER_START_POLL = 5
    print 'Waiting for server to start.'
    for _ in range(0, SERVER_START_POLL):
        result = subprocess.call(['curl', '--fail', '-I', URL_ROOT])
        if result == 0:
            return
        time.sleep(1)
    print 'Server failed to start after %s seconds.' % SERVER_START_POLL

if __name__ == '__main__':
    output_dir = sys.argv[1]
    pollForServerReady()
    main(output_dir)
