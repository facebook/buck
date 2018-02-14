#!/usr/bin/env python
"""Performance test to make sure rule keys are unaffected by absolute paths.

The general algorithm is:
  - Build all targets
  - Rename directory being tested
  - Build all targets, check to ensure everything pulls from dir cache
  - Buck build all targets to verify no-op build works.

"""
import argparse
import re
import subprocess
import os
import tempfile
import shutil
import sys

from collections import defaultdict
from datetime import datetime


def createArgParser():
    parser = argparse.ArgumentParser(
        description='Run the buck performance test')
    parser.add_argument(
        '--perftest_id',
        action='store',
        type=str,
        help='The identifier of this performance test')
    parser.add_argument(
        '--revisions_to_go_back',
        action='store',
        type=int,
        help='The maximum number of revisions to go back when testing')
    parser.add_argument(
        '--iterations_per_diff',
        action='store',
        type=int,
        help='The number of iterations to run on diff')
    parser.add_argument(
        '--targets_to_build',
        action='append',
        type=str,
        help='The targets to build')
    parser.add_argument(
        '--repo_under_test',
        action='store',
        type=str,
        help='Path to the repo under test')
    parser.add_argument(
        '--project_under_test',
        action='store',
        type=str,
        help='Path to the project folder being tested under repo')
    parser.add_argument(
        '--path_to_buck',
        action='store',
        type=str,
        help='The path to the buck binary')
    parser.add_argument(
        '--old_buck_revision',
        action='store',
        type=str,
        help='The original buck revision')
    parser.add_argument(
        '--new_buck_revision',
        action='store',
        type=str,
        help='The new buck revision')
    return parser


def log(message):
    print '%s\t%s' % (str(datetime.now()), message)
    sys.stdout.flush()


def timedelta_total_seconds(timedelta):
    return (
        timedelta.microseconds + 0.0 +
        (timedelta.seconds + timedelta.days * 24 * 3600) * 10 ** 6) / 10 ** 6


class BuildResult():
    def __init__(self, time_delta, cache_results, rule_key_map):
        self.time_delta = time_delta
        self.cache_results = cache_results
        self.rule_key_map = rule_key_map


def clean(cwd):
    log('Running hg purge.')
    subprocess.check_call(
        ['hg', 'purge', '--all'],
        cwd=cwd)


def reset(revision, cwd):
    subprocess.check_call(
        ['hg', 'revert', '-a', '-r', revision],
        cwd=cwd)


def ant_clean_build(buck_repo):
    log('Running ant clean default.')
    subprocess.check_call(
        ['ant', 'clean', 'default'],
        cwd=buck_repo)


def buck_clean(args, cwd):
    log('Running buck clean.')
    subprocess.check_call(
        [args.path_to_buck, 'clean'],
        cwd=cwd)

BUILD_RESULT_LOG_LINE = re.compile(
    r'BuildRuleFinished\((?P<rule_name>[\w_\-:#\/,]+)\): (?P<result>[A-Z_]+) '
    r'(?P<cache_result>[A-Z_]+) (?P<success_type>[A-Z_]+) '
    r'(?P<rule_key>[0-9a-f]*)')


RULEKEY_LINE = re.compile(
    r'^INFO: RuleKey (?P<rule_key>[0-9a-f]*)='
    r'(?P<rule_key_debug>.*)$')


BUCK_LOG_RULEKEY_LINE = re.compile(
    r'.*\[[\w ]+\](?:\[command:[0-9a-f-]+\])?\[tid:\d+\]'
    r'\[com.facebook.buck.rules.keys.RuleKey[\$\.]?Builder\] '
    r'RuleKey (?P<rule_key>[0-9a-f]+)='
    r'(?P<rule_key_debug>.*)$')


def buck_build_target(args, cwd, targets, log_as_perftest=True):
    """Builds a target with buck and returns performance information.
    """
    log('Running buck build %s.' % ' '.join(targets))
    bucklogging_properties_path = os.path.join(
        cwd, '.bucklogging.local.properties')
    with open(bucklogging_properties_path, 'w') as bucklogging_properties:
        # The default configuration has the root logger and FileHandler
        # discard anything below FINE level.
        #
        # We need RuleKey logging, which uses FINER (verbose), so the
        # root logger and file handler both need to be reconfigured
        # to enable verbose logging.
        bucklogging_properties.write(
            """.level=FINER
            java.util.logging.FileHandler.level=FINER""")
    env = os.environ.copy()
    # Force buck to pretend it's repo is clean.
    env.update({
        'BUCK_REPOSITORY_DIRTY': '0'
    })
    if log_as_perftest:
        with open('.buckjavaargs.local', 'a') as f:
            f.write('-Dbuck.perftest_id=%s\n' % (args.perftest_id,))
            f.write('-Dbuck.perftest_side=new\n')
    start = datetime.now()
    tmpFile = tempfile.TemporaryFile()
    try:
        subprocess.check_call(
            [
                args.path_to_buck,
                'build',
                '--deep',
                # t16296463
                '--config',
                'project.glob_handler=',
                '--config',
                'cache._exp_propagation=false',
            ] + targets + ['-v', '5'],
            stdout=tmpFile,
            stderr=tmpFile,
            cwd=cwd,
            env=env)
    except:
        tmpFile.seek(0)
        log('Buck build failed: %s' % tmpFile.read())
        raise
    tmpFile.seek(0)
    finish = datetime.now()

    (cache_results, rule_key_map) = build_maps(cwd, tmpFile)

    result = BuildResult(finish - start, cache_results, rule_key_map)
    cache_counts = {}
    for key, value in result.cache_results.iteritems():
        cache_counts[key] = len(value)
    log('Test Build Finished! Elapsed Seconds: %d, Cache Counts: %s' % (
        timedelta_total_seconds(result.time_delta), repr(cache_counts)))
    return result


def build_maps(cwd, tmpFile):
    java_utils_log_path = os.path.join(
        cwd,
        'buck-out', 'log', 'buck-0.log')
    if os.path.exists(java_utils_log_path):
        pattern = BUCK_LOG_RULEKEY_LINE
        build_output_file = open(java_utils_log_path)
    else:
        pattern = RULEKEY_LINE
        build_output_file = tmpFile

    rule_debug_map = {}
    for line in build_output_file:
        match = pattern.match(line)
        if match:
            rule_debug_map[match.group('rule_key')] = match.group(
                'rule_key_debug')

    logfile_path = os.path.join(
        cwd,
        'buck-out', 'bin', 'build.log')
    cache_results = defaultdict(list)
    rule_key_map = {}
    with open(logfile_path, 'r') as logfile:
        for line in logfile.readlines():
            line = line.strip()
            match = BUILD_RESULT_LOG_LINE.search(line)
            if match:
                rule_name = match.group('rule_name')
                rule_key = match.group('rule_key')
                if not rule_key in rule_debug_map:
                    raise Exception("""ERROR: build.log contains an entry
                        which was not found in buck build -v 5 output.
                        Rule: {0}, rule key: {1}""".format(rule_name, rule_key))
                cache_results[match.group('cache_result')].append({
                    'rule_name': rule_name,
                    'rule_key': rule_key,
                    'rule_key_debug': rule_debug_map[rule_key]
                })
                rule_key_map[match.group('rule_name')] = (rule_key, rule_debug_map[rule_key])
    return (cache_results, rule_key_map)

def set_cache_settings(
        args,
        cwd,
        cache_mode,
        dir_cache_only=True):
    log('Reconfiguring cache settings:')
    buckconfig_contents = """[cache]
    %s
    dir = buck-cache
    dir_mode = %s
[build]
    # Some repositories set this to a lower value, which breaks an assumption
    # in this test: that all rules with correct rule keys will get hits.
    artifact_cache_size_limit = 2000000000
  """ % ('mode = dir' if dir_cache_only else '', cache_mode)
    log(buckconfig_contents)
    buckconfig_path = os.path.join(cwd, '.buckconfig.local')
    with open(buckconfig_path, 'w') as buckconfig:
        buckconfig.write(buckconfig_contents)
        buckconfig.truncate()
    buckversion_path = os.path.join(cwd, '.buckversion')
    with open(buckversion_path, 'w') as buckversion:
        buckversion.write(args.new_buck_revision + os.linesep)
        buckversion.truncate()


def build_all_targets(
        args,
        cwd,
        cache_mode,
        run_clean=True,
        dir_cache_only=True,
        log_as_perftest=True):
    set_cache_settings(
        args,
        cwd,
        cache_mode,
        dir_cache_only=dir_cache_only)
    targets = []
    for target_str in args.targets_to_build:
        targets.extend(target_str.split(','))
    if run_clean:
        buck_clean(args, cwd)
    return buck_build_target(
        args,
        cwd,
        targets,
        log_as_perftest=log_as_perftest)


def check_cache_results(result, expected_keys, message, exception_message, last_result):
    suspect_keys = [
        x
        for x in result.cache_results.keys()
        if x not in expected_keys
    ]
    if suspect_keys:
        log(message)
        for result_type in suspect_keys:
            for rule in result.cache_results[result_type]:
                rule_name = rule['rule_name']
                key, key_debug = result.rule_key_map[rule_name]
                old_key, old_key_debug = last_result.rule_key_map[rule_name]
                log('Rule %s, result %s.' % (rule_name, result_type))
                log('\tOld Rule Key (%s): %s.' % (old_key, old_key_debug))
                log('\tNew Rule Key (%s): %s.' % (key, key_debug))
        raise Exception(exception_message)


def get_buck_repo_root(path):
    while (path is not None and
           not os.path.exists(os.path.join(path, '.buckconfig'))):
        path = os.path.dirname(path)
    return path


def move_mount(from_mount, to_mount):
    subprocess.check_call("sync")
    subprocess.check_call(["mount", "--move", from_mount, to_mount])
    for subdir, dirs, files in os.walk(to_mount):
        for file in files:
            path = os.path.join(subdir, file)
            if (os.path.islink(path) and
               os.path.realpath(path).startswith(from_mount + '/')):
                new_path = os.path.realpath(path).replace(
                    from_mount + '/',
                    to_mount + '/'
                )
                os.unlink(path)
                os.symlink(new_path, path)


def main():
    args = createArgParser().parse_args()
    log('Running Performance Test!')
    ant_clean_build(get_buck_repo_root(args.path_to_buck))
    clean(args.repo_under_test)
    log('=== Warming up cache ===')
    cwd = os.path.join(args.repo_under_test, args.project_under_test)
    last_result = build_all_targets(
        args,
        cwd,
        'readwrite',
        dir_cache_only=False,
        log_as_perftest=False)
    log('=== Cache Warm!  Running tests ===')
    new_directory_name = (os.path.basename(args.repo_under_test) +
                          '_test_iteration_')

    # Rename the directory to flesh out any cache problems.
    cwd_root = os.path.join(os.path.dirname(args.repo_under_test),
                       new_directory_name)
    cwd = os.path.join(cwd_root, args.project_under_test)

    log('Renaming %s to %s' % (args.repo_under_test, cwd_root))
    if not os.path.isfile('/proc/mounts'):
        is_mounted = False
    else:
        with open('/proc/mounts', 'r') as mounts:
            # grab the second element (mount point) from /proc/mounts
            lines = [l.strip().split() for l in mounts.read().splitlines()]
            lines = [l[1] for l in lines if len(l) >= 2]
            is_mounted = args.repo_under_test in lines
    if is_mounted:
        if not os.path.exists(cwd_root):
            os.makedirs(cwd_root)
        move_mount(args.repo_under_test, cwd_root)
    else:
        # If cwd_root exists, it means that a previous attempt to run
        # this script wasn't able to clean up that folder properly.
        # In this case, we clean up that folder.
        shutil.rmtree(cwd_root, ignore_errors=True)
        os.rename(args.repo_under_test, cwd_root)

    try:
        log('== Checking for problems with absolute paths ==')
        result = build_all_targets(args, cwd, 'readonly')
        check_cache_results(result,
                            ['DIR_HIT', 'IGNORED', 'LOCAL_KEY_UNCHANGED_HIT'],
                            'Building was unable to reuse the cache from a '
                            'previous run. This suggests one of the rule keys '
                            'contains an absolute path.',
                            'Failed to reuse cache across directories!!!',
                            last_result)

        log('== Ensure noop build does nothing. ==')
        result = build_all_targets(
            args,
            cwd,
            'readonly',
            run_clean=False)
        check_cache_results(result, ['LOCAL_KEY_UNCHANGED_HIT'],
                            'Doing a noop build not hit all of its keys.',
                            'Doing a noop build not hit all of its keys.',
                            last_result)

    finally:
        log('Renaming %s to %s' % (cwd_root, args.repo_under_test))
        if is_mounted:
            move_mount(cwd_root, args.repo_under_test)
            shutil.rmtree(cwd_root)
        else:
            os.rename(cwd_root, args.repo_under_test)


if __name__ == '__main__':
    main()
