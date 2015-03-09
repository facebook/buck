#!/usr/bin/env python
#
# Copyright 2014-present Facebook, Inc.
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

"""This script will compile an Xcode 5 asset catalog (*.xcassets) in a fashion
that is compatible with legacy bundle placement.  For instance, asset catalogs
flatten all assets into a single namespace and all assets will be placed in
the main application bundle's resource path's root.  However, when using a
bundle, the bundle's directory would be placed in the same place, thus
allowing for a hierarchy.

For example, when building an application called Sample.app that references a
Resource.bundle in its Copy Resources build phase, the files in the bundle
would be placed at Sample.app/Resource.bundle/<files>.  If the bundle's image
assets are moved to an asset catalog, the files are placed in at
Sample.app/<files>.  This creates namespacing issues for code that references
these resources.

This script manipulates the asset catalog compiler (actool) to place resources
in the expected place."""

import optparse
import errno
import logging
import os
import os.path
import re
import subprocess
import sys

import StringIO

logger = logging.getLogger('compile_asset_catalog')


def get_xcode_path():
    cmd = ['xcode-select', '--print-path']
    return subprocess.check_output(cmd).strip()


def get_actool_env_path(platform):
    platform = 'iPhoneSimulator'
    if platform != 'iphonesimulator':
        platform = 'iPhoneOS'
    xcode_path = get_xcode_path()
    return ':'.join([
        os.path.join(
            xcode_path,
            'Platforms',
            platform + '.platform',
            'Developer/usr/bin/actool'),
        os.path.join(xcode_path, 'usr/bin'),
        '/usr/bin',
        '/bin',
        '/usr/sbin',
        '/sbin'])


def version_components_from_string(v):
    return v.split('.')


def major_version_from_version_string(v):
    return int(version_components_from_string(v)[0])


def minor_version_from_version_string(v):
    components = version_components_from_string(v)
    if len(components) < 2:
        raise ValueError(v + ': does not have a minor version')
    return int(components[1])


def catalog_name_from_path(path):
    return os.path.splitext(os.path.basename(path))[0]


def actool_cmds(target, platform, devices, output, catalog_paths,
                split_into_bundles):
    """Returns an array of tuples.  Each tuple is (command, output_directory).
       command is an array of command line parameters, while directory is a
       directory that is expected to exist."""

    # When splitting into bundles, Assets.car cannot be used, so force the
    # deployment target to a version that does not support it.
    if split_into_bundles:
        if (platform == 'macosx' and
                minor_version_from_version_string(target) >= 9):
            target = '10.8'
        elif major_version_from_version_string(target) >= 7:
            target = '6.0'

    base_cmd = [
        'actool',
        '--output-format',
        'human-readable-text',
        '--notices',
        '--warnings',
        '--platform',
        platform,
        '--minimum-deployment-target',
        target]

    for d in devices:
        base_cmd.extend(['--target-device', d])

    base_cmd.extend(['--compress-pngs', '--compile'])

    if split_into_bundles:
        bundle_directories = [
            catalog_name_from_path(path) + '.bundle' for path in catalog_paths]
        output_directories = [
            os.path.join(output, bundle) for bundle in bundle_directories]

        pairs = zip(output_directories, catalog_paths)
        cmds = []
        for (output_directory, catalog_path) in pairs:
            cmd = list(base_cmd)
            cmd.extend([output_directory, catalog_path])
            cmds.append(cmd)

        return zip(cmds, output_directories)
    else:
        base_cmd.append(output)
        base_cmd.extend(catalog_paths)
        return [(base_cmd, output)]


def mkdir_p(path):
    try:
        os.makedirs(path)
    except OSError as e:
        if e.errno == errno.EEXIST and os.path.isdir(path):
            pass
        else:
            raise


# We don't know all the different types of potential warnings actool outputs.
# We assume that they have some potential preamble, then "warning:" and then
# the message. In the case that the preamble is a path, we truncate the path
# to a suffix that indicates the asset name to make it easier to debug in
# Xcode.
ACTOOL_WARNING_REGEX = re.compile(
    '(?P<asset_path>.*:)?\s*warning:\s*(?P<message>.*)')


def transform_actool_output_line(line):
    line = line.rstrip()

    # Xcode seems to ignore actool's warnings when they are piped through
    # stdout as normal.  We want to treat these as errors that must be
    # resolved.  This filter formats the warnings nicely to make it easy to
    # resolve them.
    #
    # Sample:
    #   /path/to/images.xcassets:./some.imageset: warning: ...
    # should be:
    #  error: images.xcassets:./some.imageset: warning: ...
    matches = ACTOOL_WARNING_REGEX.match(line)
    if matches:
        groups = matches.groupdict()
        if (groups['asset_path']):
            groups['asset_path'] = os.path.basename(groups['asset_path'])
        line = 'error: ' + groups['asset_path'] + ': ' + groups['message']
    return line


def transform_actool_output(stdout, verbose):
    has_errors = False
    is_error = False

    # Using `for line in proc.stdout` causes OS X to barf with errno=35.  Not
    # sure why, but it appears to be a rate limiting issue.  Creating the loop
    # manually appears to resolve the issue.
    line = stdout.readline()
    while line:
        line = transform_actool_output_line(line)
        if line.startswith('error:'):
            is_error = True
            has_errors = True
        if is_error or verbose:
            print line
        line = stdout.readline()
    return not has_errors


def compile_asset_catalogs(target, platform, devices, output, catalogs,
                           split_into_bundles, verbose):
    cmd_pairs = actool_cmds(
        target,
        platform,
        devices,
        output,
        catalogs,
        split_into_bundles)
    actool_env_path = get_actool_env_path(platform)

    env = os.environ.copy()
    env['PATH'] = actool_env_path

    errors_encountered = False

    for (cmd, output_directory) in cmd_pairs:
        mkdir_p(output_directory)

        logger.info('PATH=' + actool_env_path + ' ' + ' '.join(cmd))

        # The explicit PATH is provided because Xcode runs actool with an
        # explicit PATH when it runs as part of the automatic "Copy Bundle
        # Resources" phase.  This ensures that the tool is run in the same
        # environment as expected.
        #
        # Note that check_output raises an exception if the exit code
        # indicates an error, which is the desired behavior here.
        actool_output = subprocess.check_output(cmd, env=env)
        actool_stdout = StringIO.StringIO(actool_output)
        success = transform_actool_output(actool_stdout, verbose)

        if not success:
            errors_encountered = True

    return not errors_encountered


if __name__ == "__main__":
    parser = optparse.OptionParser(fromfile_prefix_chars='@')
    parser.add_option('-t', '--target',
                      help='Target operating system version for deployment')
    parser.add_option('-p', '--platform',
                      help='Target platform.  Choices are iphonesimulator, '
                      'iphoneos, and macosx.')
    parser.add_option('-d', '--device', action='append', type=str,
                      help='Choices are iphone and ipad. May be specified '
                      'multiple times. When platform is macosx, this '
                      'option cannot be specified. Otherwise, this option '
                      'must be specified.')
    parser.add_option('-b', '--bundles', action='store_true',
                      help='Use the legacy output format, which copies '
                      'asset catalogs to their sibling bundles. Without '
                      'this option, all assets are copied to the root (or '
                      'compiled into Assets.car)')
    parser.add_option('-o', '--output',
                      help='Output directory for the specified asset '
                      'catalog(s).')
    parser.add_option('-v', '--verbose', action='store_true',
                      help='Print verbose output')
    opts, args = parser.parse_args()

    logging.basicConfig(
        level=(logging.DEBUG if opts.verbose else logging.INFO))
    logger.info('Compiling asset catalogs...')

    catalogs = map(os.path.abspath, args)
    opts.output = os.path.abspath(opts.output)

    # Validation:
    #
    # - deployment target is a version string
    # - platform is one of the appropriate choices
    # - when platform is macosx, devices are not specified.  Otherwise,
    #   devices must be specified
    # - devices are valid values
    # - asset catalogs paths end in .xcassets
    for component in opts.target.split('.'):
        try:
            int(component)
        except:
            raise ValueError(opts.target + ': target must be a version string')

    if (opts.platform != 'iphonesimulator' and opts.platform != 'iphoneos'
            and opts.platform != 'macosx'):
        raise ValueError(opts.platform + ': platform must be either '
                         'iphoneos, iphonesimulator, or macosx')

    if opts.platform == 'macosx' and opts.device is not None:
        raise ValueError(
            'devices must not be specified when platform is macosx')
    elif opts.platform != 'macosx' and (opts.device or len(opts.device) == 0):
        raise ValueError('devices must be specified when platform is iphoneos '
                         'or iphonesimulator')

    if opts.device is not None:
        for device in opts.device:
            if device != 'iphone' and device != 'ipad':
                raise ValueError(
                    device + ': device(s) must be either iphone or ipad')

    for path in catalogs:
        if os.path.splitext(os.path.basename(path))[1] != '.xcassets':
            raise ValueError(
                path + ': catalog paths must have an xcassets extension')

    # When the target platform is macosx, the device supplied to actool is
    # 'mac'
    if opts.platform == 'macosx':
        opts.device = ['mac']

    exit_code = 0
    if not compile_asset_catalogs(opts.target, opts.platform, opts.device,
                                  opts.output, catalogs,
                                  opts.bundles, opts.verbose):
        exit_code = 1

    logger.info('Done')
    sys.exit(exit_code)
