#!/usr/bin/env python
"""Performance test to compare the performance of buck between two revisions.

The general algorithm is:

Checkout <revisions_to_go_back - 1>
Warm up the cache:
  Set .buckversion to old revision, build all targets
  Set .buckversion to new revision, build all targets

For each revision to test:
  - Rename directory being tested
  - Build all targets, check to ensure everything pulls from dir cache
  - Check out revision to test
  - Clean Build all targets <iterations_per_diff> times, only reading from
      cache, not writing (except for the last one, write that time)
  - Buck build all targets to verify no-op build works.

"""
import argparse
import re
import subprocess
import os
import tempfile
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


class BuildResult():
    def __init__(self, time_delta, cache_results, rule_key_map):
        self.time_delta = time_delta
        self.cache_results = cache_results
        self.rule_key_map = rule_key_map


def git_clean(cwd):
    log('Running git clean.')
    subprocess.check_call(
        ['git', 'clean', '--quiet', '-xfd'],
        cwd=cwd)


def git_reset(cwd, revision):
    subprocess.check_call(
        ['git', 'reset', '--quiet', '--hard', revision],
        cwd=cwd)


def buck_clean(args, cwd):
    log('Running buck clean.')
    subprocess.check_call(
        [args.path_to_buck, 'clean'],
        cwd=cwd)


def git_get_revisions(args):
    return list(reversed(subprocess.check_output(
        ['git', 'log', '--pretty=format:%H', 'HEAD', '-n',
         str(args.revisions_to_go_back + 1)],
        cwd=args.repo_under_test).splitlines()))


def git_checkout(revision, cwd):
    log('Checking out %s.' % revision)
    git_reset(cwd, 'HEAD')
    subprocess.check_call(
        ['git', 'checkout', '--quiet', revision],
        cwd=cwd)


BUILD_RESULT_LOG_LINE = re.compile(
    r'BuildRuleFinished\((?P<rule_name>[\w_\-:#\/]+)\): (?P<result>[A-Z_]+) '
    r'(?P<cache_result>[A-Z_]+) (?P<success_type>[A-Z_]+) '
    r'(?P<rule_key>[0-9a-f]*)')


RULEKEY_LINE = re.compile(
    r'^INFO: RuleKey (?P<rule_key>[0-9a-f]*)='
    r'(?P<rule_key_debug>.*)$')


BUCK_LOG_RULEKEY_LINE = re.compile(
    r'.*\[[\w ]+\](?:\[command:[0-9a-f-]+\])?\[tid:\d+\]'
    r'\[com.facebook.buck.rules.RuleKey[\$\.]Builder\] '
    r'RuleKey (?P<rule_key>[0-9a-f]+)='
    r'(?P<rule_key_debug>.*)$')


def buck_build_target(args, cwd, target, perftest_side, log_as_perftest=True):
    """Builds a target with buck and returns performance information.
    """
    log('Running buck build %s.' % target)
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
            '''.level=FINER
            java.util.logging.FileHandler.level=FINER''')
    env = os.environ.copy()
    # Force buck to pretend it's repo is clean.
    env.update({
        'BUCK_REPOSITORY_DIRTY': '0'
    })
    if log_as_perftest:
        env.update({
            'BUCK_EXTRA_JAVA_ARGS':
            '-Dbuck.perftest_id=%s, -Dbuck.perftest_side=%s' % (
            args.perftest_id, perftest_side)
        })
    start = datetime.now()
    with tempfile.TemporaryFile() as tmpFile:
        try:
            subprocess.check_call(
                [args.path_to_buck, 'build', target, '-v', '5'],
                stdout=tmpFile,
                stderr=tmpFile,
                cwd=cwd,
                env=env)
        except:
            tmpFile.seek(0)
            log('Buck build failed: %s' % tmpFile.read())
            raise
        tmpFile.seek(0)
        build_output = tmpFile.read()
    finish = datetime.now()

    java_utils_log_path = os.path.join(
        cwd,
        'buck-out', 'log', 'buck-0.log')
    if os.path.exists(java_utils_log_path):
        pattern = BUCK_LOG_RULEKEY_LINE
        with open(java_utils_log_path) as f:
            build_output = f.read()
    else:
        pattern = RULEKEY_LINE

    rule_debug_map = {}
    for line in build_output.splitlines():
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
                    raise Exception('''ERROR: build.log contains an entry
                        which was not found in buck build -v 5 output.
                        Rule: {}, rule key: {}'''.format(rule_name, rule_key))
                cache_results[match.group('cache_result')].append({
                    'rule_name': rule_name,
                    'rule_key': rule_key,
                    'rule_key_debug': rule_debug_map[rule_key]
                })
                rule_key_map[match.group('rule_name')] = rule_debug_map[
                    match.group('rule_key')]

    result = BuildResult(finish - start, cache_results, rule_key_map)
    cache_counts = {}
    for key, value in result.cache_results.iteritems():
        cache_counts[key] = len(value)
    log('Test Build Finished! Elapsed Seconds: %d, Cache Counts: %s' % (
        result.time_delta.total_seconds(), repr(cache_counts)))
    return result


def set_perftest_side(
        args,
        cwd,
        perftest_side,
        cache_mode,
        dir_cache_only=True):
    log('Reconfiguring to test %s version of buck.' % perftest_side)
    buckconfig_path = os.path.join(cwd, '.buckconfig.local')
    with open(buckconfig_path, 'w') as buckconfig:
        buckconfig.write('''[cache]
    %s
    dir = buck-cache-%s
    dir_mode = %s
  ''' % ('mode = dir' if dir_cache_only else '', perftest_side, cache_mode))
        buckconfig.truncate()
    buckversion_path = os.path.join(cwd, '.buckversion')
    with open(buckversion_path, 'w') as buckversion:
        if perftest_side == 'old':
            buckversion.write(args.old_buck_revision + os.linesep)
        else:
            buckversion.write(args.new_buck_revision + os.linesep)
        buckversion.truncate()

def build_all_targets(
        args,
        cwd,
        perftest_side,
        cache_mode,
        run_clean=True,
        dir_cache_only=True,
        log_as_perftest=True):
    set_perftest_side(
        args,
        cwd,
        perftest_side,
        cache_mode,
        dir_cache_only=dir_cache_only)
    result = {}
    for target in args.targets_to_build:
        if run_clean:
            buck_clean(args, cwd)
        #TODO(royw): Do smart things with the results here.
        result[target] = buck_build_target(
            args,
            cwd,
            target,
            perftest_side,
            log_as_perftest=log_as_perftest)
    return result


def run_tests_for_diff(args, revisions_to_test, test_index, last_results):
    log('=== Running tests at revision %s ===' % revisions_to_test[test_index])
    new_directory_name = (os.path.basename(args.repo_under_test) +
                          '_test_iteration_%d' % test_index)

    # Rename the directory to flesh out any cache problems.
    cwd = os.path.join(os.path.dirname(args.repo_under_test),
                       new_directory_name)
    log('Renaming %s to %s' % (args.repo_under_test, cwd))
    os.rename(args.repo_under_test, cwd)

    try:
        log('== Checking new revision for problems with absolute paths ==')
        results = build_all_targets(args, cwd, 'new', 'readonly')
        for target, result in results.iteritems():
            if (len(result.cache_results.keys()) != 1 or
                    'DIR_HIT' not in result.cache_results):
                # Remove DIR_HITs to make error message cleaner
                result.cache_results.pop('DIR_HIT', None)
                log('Building %s at revision %s with the new buck version '
                    'was unable to reuse the cache from a previous run.  '
                    'This suggests one of the rule keys contains an '
                    'abosolute path.' % (
                        target,
                        revisions_to_test[test_index - 1]))
                for rule in result.cache_results['MISS']:
                    rule_name = rule['rule_name']
                    old_key = last_results[target].rule_key_map[rule_name]
                    log('Rule %s missed.' % rule_name)
                    log('\tOld Rule Key: %s.' % old_key)
                    log('\tNew Rule Key: %s.' % result.rule_key_map[rule_name])
                raise Exception('Failed to reuse cache across directories!!!')

        git_checkout(revisions_to_test[test_index], cwd)

        for attempt in xrange(args.iterations_per_diff):
            cache_mode = 'readonly'
            if attempt == args.iterations_per_diff - 1:
                cache_mode = 'readwrite'

            build_all_targets(args, cwd, 'old', cache_mode)
            build_all_targets(args, cwd, 'new', cache_mode)

        log('== Checking new revision to ensure noop build does nothing. ==')
        results = build_all_targets(
            args,
            cwd,
            'new',
            cache_mode,
            run_clean=False)
        for target, result in results.iteritems():
            if (len(result.cache_results.keys()) != 1 or
                    'LOCAL_KEY_UNCHANGED_HIT' not in result.cache_results):
                result.cache_results.pop('DIR_HIT', None)
                raise Exception(
                    'Doing a noop build for %s at revision %s with the new '
                    'buck version did not hit all of it\'s keys.\nMissed '
                    'Rules: %s' % (
                        target,
                        revisions_to_test[test_index - 1],
                        repr(result.cache_results)))

    finally:
        log('Renaming %s to %s' % (cwd, args.repo_under_test))
        os.rename(cwd, args.repo_under_test)

    return results


def main():
    args = createArgParser().parse_args()
    log('Running Performance Test!')
    git_clean(args.repo_under_test)
    revisions_to_test = git_get_revisions(args)
    # Checkout the revision previous to the test and warm up the local dir
    # cache.
    # git_clean(args.repo_under_test)
    log('=== Warming up cache ===')
    git_checkout(revisions_to_test[0], args.repo_under_test)
    build_all_targets(
        args,
        args.repo_under_test,
        'old',
        'readwrite',
        dir_cache_only=False,
        log_as_perftest=False)
    results_for_new = build_all_targets(
        args,
        args.repo_under_test,
        'new',
        'readwrite',
        dir_cache_only=False,
        log_as_perftest=False)
    log('=== Cache Warm!  Running tests ===')
    for i in xrange(1, args.revisions_to_go_back):
        results_for_new = run_tests_for_diff(
            args,
            revisions_to_test,
            i,
            results_for_new)


if __name__ == '__main__':
    main()
