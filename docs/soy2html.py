#!/usr/bin/env python
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
import sys
import urllib2


def main(output_dir):
    # Create opener.
    opener = urllib2.OpenerDirector()
    opener.add_handler(urllib2.ProxyHandler())
    opener.add_handler(urllib2.UnknownHandler())
    opener.add_handler(urllib2.HTTPHandler())
    opener.add_handler(urllib2.HTTPDefaultErrorHandler())
    opener.add_handler(urllib2.HTTPSHandler())
    opener.add_handler(urllib2.HTTPErrorProcessor())

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
                url = 'http://localhost:9814/' + html_file

                # Fetch url and copy its contents to output_dir.
                req = urllib2.Request(url)
                res = opener.open(req)
                html = res.read()
                copy_to_output_dir(html_file, output_dir, html)
            elif (file_name.endswith('.css') or
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


def copy_to_output_dir(path, output_dir, content):
    # Make sure that the directory for the destination path exists.
    last_slash = path.rfind('/')
    if last_slash != -1:
        output_subdir = os.path.join(output_dir, path[:last_slash])
        if not os.path.exists(output_subdir):
            os.makedirs(output_subdir)

    # Copy the file.
    output_file = os.path.join(output_dir, path)
    with open(output_file, 'w') as f:
        f.write(content)


if __name__ == '__main__':
    output_dir = sys.argv[1]
    main(output_dir)
