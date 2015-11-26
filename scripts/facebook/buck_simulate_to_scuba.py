#!/usr/bin/env python
#
# This script runs 'buck simulate' for the argument target and
# uploads all the results to Scuba for analysis.
#
# This data in Scuba UI: https://fburl.com/178307484

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import unicode_literals
from __future__ import with_statement

import argparse
import datetime
import gzip
import json
import logging
import os
import os.path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib2

SCUBA_TABLE = 'buck_simulate_per_diff'
REPORTS_DIR = os.path.join(tempfile.gettempdir(), 'buck_simulate_py')
LOCAL_TIMES_DIR = os.path.join(REPORTS_DIR, 'times')
ALIAS_PATTERN = re.compile('.+<(.+)@fb.com>.*')
GFS_TIMES_DIR = '/mnt/vol/gfsdataswarm-global/namespaces/infrastructure/buck-global/buck-simulate'


##################################################
# Script command line arguments
##################################################

def parse_args():
    parser = argparse.ArgumentParser(
        description='Computes [buck simulate] stats and uploads them to Scuba.')
    parser.add_argument(
        '--repo',
        dest='repo',
        help='The repository path.',
        required=True,
    )
    parser.add_argument(
        dest='targets',
        nargs='+',
        help='The target to simulate.',
    )
    parser.add_argument(
        '--rev',
        dest='rev',
        default='HEAD',
        help='The most recent revision to run this command for. '
                '(defaults to HEAD)'
    )
    parser.add_argument(
        '--rev-count',
        dest='rev_count',
        default=1,
        type=int,
        help='The number of revs to run the command for. '
             ' (defaults to zero)',
    )
    parser.add_argument(
        '--rev-step-secs',
        dest='rev_step_secs',
        default=0,
        type=int,
        help='Minimum time between to consecutive revisions in seconds.',
    )
    parser.add_argument(
        '--num-threads',
        dest='num_threads',
        default=8,
        type=int,
        help='The number of threads to simulate.',
    )
    parser.add_argument(
        '--times-file',
        dest='times_file',
        default=None,
        help='File containing buck simulate times.',
    )
    parser.add_argument(
        '--time-aggregate',
        dest='time_aggregate',
        default=['avg', 'p90', 'p99'],
        nargs="+",
        help='The time aggregates used from the simulate times file.',
    )
    return parser.parse_args()


##################################################
# Repository related code
##################################################

def get_repo(path):
    path = os.path.realpath(path)
    git_path = os.path.join(path, '.git')
    if os.path.isdir(git_path):
        return GitRepository(path)
    else:
        return HgRepository(path)


def run_cmd(cmd, cwd=None, env=None, stderr=False, utf8decode=True,
            input=None):
    environ = os.environ.copy()
    if env:
        environ.update(env)

    stdin = None
    if input:
        stdin = subprocess.PIPE

    # Uncomment the line below for debugging purposes - very useful!
    # printf('$ {0}'.format(' '.join(cmd)))

    p = subprocess.Popen(
        cmd,
        stdin=stdin,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=cwd,
        env=environ,
        close_fds=True,
    )

    if input:
        out, err = p.communicate(input=input)
    else:
        out, err = p.communicate()

    if out is not None and utf8decode:
        out = out.decode('utf-8')
    if err is not None and utf8decode:
        err = err.decode('utf-8')

    if p.returncode != 0:
        cmdstr = ' '.join(cmd)
        out = 'CWD: {cwd}\nCMD: {cmd}\n'.format(cwd=cwd, cmd=cmdstr)
        out += 'STDOUT: %s\nSTDERR: %s\n' % (out, err)
        ex = subprocess.CalledProcessError(p.returncode, cmdstr)
        ex.output = out
        printf('Failed to run with output: [{0}]'.format(out))
        raise ex

    if stderr:
        return out, err
    return out


class Repository(object):
    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)

    def get_ancestors(self, rev, limit=None):
        raise NotImplementedError()

    def checkout(self, rev):
        raise NotImplementedError()

    def run(self, cmd, cwd=None, env=None, stderr=False, utf8decode=True,
            input=None):
        if not cwd:
            cwd = self.path
        return run_cmd(cmd, cwd, env, stderr, utf8decode, input)


NULLID = '0' * 40


class HgRepository(Repository):
    def __init__(self, path):
        super(HgRepository, self).__init__(path)
        self.type = 'hg'

    def get_ancestors(self, rev, limit=None):
        printf("Getting {0} HG revisions...".format(limit))
        query = '::%s' % rev
        query = 'sort(%s,-rev)' % query

        # Use \1 to split, since python's subprocess module doesn't allow
        # command arguments with \0 in them.
        command = ['hg', 'log', '-r', query, '-T',
                   '{node}\1{date}\1{author|user}\2']
        command.extend(['-l', str(limit)])
        history = self.run(command).split('\2')
        revs = []
        for line in history:
            if not line:
                continue

            hash, date, author = line.split('\1')
            date = int(date.split('.')[0])
            commit = HgCommit(self, hash, date, author)
            revs.append(commit)

        printf("Sorting {0} HG revisions...".format(limit))
        # HG can only return sorted by REV which does not guarantee time is
        # sorted.
        revs = sorted(revs, key=lambda r: r.date, reverse=True)
        printf("Finished sorting {0} HG revisions.".format(limit))
        return revs

    def checkout(self, rev):
        if rev is None:
            rev = 'null'
        cmd = ['hg', 'update', '--clean', rev]
        try:
            self.run(cmd)
        except subprocess.CalledProcessError as ex:
            if ex.output and "unknown revision '-1'" in ex.output:
                # workaround a bug in remotenames that doesn't allow creating
                # bookmarks on -1
                cmd.extend(['--config', 'extensions.remotenames=!'])
                self.run(cmd)
            else:
                raise


class GitRepository(Repository):
    def __init__(self, path):
        super(GitRepository, self).__init__(path)
        self.type = 'git'

    def get_ancestors(self, rev, limit=None):
        command = ['git', 'log', '--format=%H %P', rev]
        command.extend(['-n', str(limit)])
        history = self.run(command).split('\n')
        for line in history:
            if not line:
                continue
            parts = line.split()
            commit = GitCommit(self, parts[0])
            commit._parents = []
            for parent in parts[1:]:
                commit._parents.append(GitCommit(self, parent))
            yield commit

    def checkout(self, rev):
        if rev is None:
            nullbranch = 'sourcesyncnull'
            try:
                self.run(['git', 'branch', '-D', nullbranch])
            except subprocess.CalledProcessError as ex:
                if 'you are currently on' in ex.cmd:
                    # We are already on the nullbranch. Move off it, so we can
                    # delete it.
                    self.run(['git', 'checkout', self.run(['git', 'rev-parse',
                              'HEAD']).strip()])
                    self.run(['git', 'branch', '-D', nullbranch])
                elif 'not found' not in ex.cmd:
                    raise
            self.run(['git', 'checkout', '--orphan', nullbranch])
            try:
                self.run(['git', 'rm', '-rf', '.'])
            except subprocess.CalledProcessError as ex:
                if 'did not match any files' not in ex.cmd:
                    raise
        else:
            self.run(['git', 'checkout', '--force', rev])


class Commit(object):
    def __init__(self, repo, hash):
        self.repo = repo
        self.hash = hash

    def get_alias(self, author):
        matches = ALIAS_PATTERN.match(author)
        if matches and len(matches.groups()) == 1:
            return matches.group(1)
        else:
            return author

    def datetime(self):
        dt = datetime.datetime.utcfromtimestamp(self.date)
        return dt.strftime('%Y-%m-%d %H:%M:%S')


class HgCommit(Commit):
    def __init__(self, repo, hash, date, author):
        super(HgCommit, self).__init__(repo, hash)
        self.hash = hash
        self.date = date
        self.author = author


class GitCommit(Commit):
    def __init__(self, repo, hash):
        super(GitCommit, self).__init__(repo, hash)
        raw = self.repo.run([
            'git',
            'log',
            '-1',
            '--date',
            'raw',
            self.hash,
            '--format=%an <%ae>\n%ad\n%cn <%ce>\n%cd\n%B'
        ])

        parts = raw.strip().split('\n', 4)
        self.author = self.get_alias(parts[0])
        date_parts = parts[1].split()
        self.date = int(date_parts[0])


##################################################
# Scuba/Scribe related code
##################################################

SCRIBE_ENDPOINT = 'FILL_IN'
APP_ID = 'FILL_IN'
TOKEN = 'FILL_IN'
CONTENT_TEMPLATE = 'FILL_IN'


class ScubaData(object):
    def __init__(self, table):
        self.table = table
        self.category = 'perfpipe_' + table

    def add_sample(self, sample):
        content = CONTENT_TEMPLATE.format(
            app_id=APP_ID,
            token=TOKEN,
            category=self.category,
            sample=json.dumps(sample)
        )
        response = urllib2.urlopen(url=SCRIBE_ENDPOINT, data=content)
        status = response.getcode()
        assert status == 200, \
            'Failed to upload to scuba with HTTP Status Code: ' + str(status)


##################################################
# Actual script code
##################################################

def buck_simulate(targets, num_threads, repository, times_file, time_aggregate,
                  report_file):
    cmd = [
        'buck', 'simulate',
        '--report-file', report_file,
        '--num-threads', str(num_threads),
        '--time-aggregate', time_aggregate,
    ]
    if times_file:
        cmd.extend(('--times-file', times_file,))
    cmd.extend(targets)
    sys.stdout.flush()
    return run_cmd(cmd, cwd=repository)


def add_to_scuba_sample(to_add, sample):
    for key, value in to_add.items():
        if isinstance(value, int):
            sample['int'][key] = int(value)
        elif isinstance(value, list):
            sample['normal'][key] = ','.join(sorted(value))
        else:
            sample['normal'][key] = str(value)


def upload_to_scuba(
        client,
        rev_info,
        report_path):

    sample = {
        'int': {
        },
        'normal': {
        },
    }
    with open(report_path) as data_file:
        report = json.load(data_file)
    add_to_scuba_sample(report, sample)
    add_to_scuba_sample(rev_info, sample)
    printf('Sending sample to Scuba: {0}'.format(json.dumps(sample)))
    assert 'time' in sample['int'], 'sample["int"]["time"] must be defined.'
    try:
        client.add_sample(sample)
    except:
        logging.exception('Failed to write to scuba')


def find_times_file(epoch_seconds):
    date = datetime.datetime.fromtimestamp(float(epoch_seconds))
    date = datetime.datetime.now()
    for i in range(8):
        new_date = date + datetime.timedelta(days=i)
        path = get_times_file_for_date(new_date)
        if path:
            return path

    return None


gfs_cache = set()


def get_times_file_for_date(date):
    if not os.path.isdir(LOCAL_TIMES_DIR):
        os.makedirs(LOCAL_TIMES_DIR)

    # 1. Check if the file already exists locally.
    date_str = date.strftime('%Y-%m-%d')
    json_file = 'buck_simulate_times_{0}.json'.format(date_str)
    local_path = os.path.join(LOCAL_TIMES_DIR, json_file)
    if os.path.isfile(local_path):
        return local_path

    # 2. Try to fetch it from gfsdataswarm-global.
    gz_file = json_file + '.gz'
    gfs_path = os.path.join(GFS_TIMES_DIR, gz_file)
    if gfs_path in gfs_cache:
        # Already checked before so skip it.
        return None
    gfs_cache.add(gfs_path)
    printf('Trying to fetch [{0}].'.format(gfs_path))
    if os.path.isfile(gfs_path):
        local_gz_path = os.path.join(LOCAL_TIMES_DIR, gz_file)
        shutil.copyfile(gfs_path, local_gz_path)
        with gzip.open(local_gz_path, 'rb') as f_in:
            with open(local_path, 'wb') as f_out:
                shutil.copyfileobj(f_in, f_out)
        return local_path

    # 3. Finally give up.
    return None


def get_rev_iterator(repo, start_rev_hash):
    # Go through the entire revision log if the script is so inclined.
    while True:
        for rev in repo.get_ancestors(start_rev_hash, limit=50000):
            start_rev_hash = rev.hash
            yield rev


def printf(msg):
    # Log prettifier with some forced flushing action.
    date = datetime.datetime.now().strftime("%H:%M:%S")
    print('[{date}] {msg}'.format(msg=msg, date=date))
    sys.stdout.flush()


def main():
    args = parse_args()
    repo = get_repo(args.repo)
    buck_config = os.path.join(args.repo, '.buckconfig')
    scuba_client = ScubaData(SCUBA_TABLE)
    if not os.path.isdir(REPORTS_DIR):
        os.makedirs(REPORTS_DIR)
    i = 1
    epoch_seconds = int(time.time())
    next_rev_secs = None
    skipped_revs = 0
    failed_revs = []
    for rev in get_rev_iterator(repo, args.rev):
        if i > args.rev_count:
            # Check if we have run for enough revisions.
            break

        if next_rev_secs is None:
            # If this is the first rev then use it as the baseline timestamp.
            next_rev_secs = rev.date - args.rev_step_secs
        elif rev.date > next_rev_secs:
            # Otherwise enforce args.rev_step_secs.
            skipped_revs += 1
            continue

        rev_info = {}
        rev_info['time'] = rev.date
        rev_info['author'] = rev.author
        rev_info['revision'] = rev.hash
        rev_info['repository'] = repo.name
        rev_info['repository_type'] = repo.type

        printf("Checking out revision [{0}] from [{1}] ({2}/{3} - {4:.1f}%)."
               .format(rev.hash,
                       rev.datetime(),
                       i,
                       args.rev_count,
                       100.0 * i / float(args.rev_count)))
        repo.checkout(rev.hash)

        # Unfortunately hg sparse checkouts will return revisions that don't
        # apply to the current sparse checked out repo and will remove all
        # files. We skip these by checking the presence of .buckconfig.
        if not os.path.isfile(buck_config):
            printf("Skipping revision [{0}]. Does not belong to repo."
                   .format(rev.hash))
            skipped_revs += 1
            continue

        # Skip over to the next interesting time window. This can most
        # definitely be done with one single math formulae but that will be
        # harder to read.
        while rev.date <= next_rev_secs:
            next_rev_secs -= args.rev_step_secs

        if skipped_revs > 0:
            printf('Skipped a total of [{0}] revs.'.format(skipped_revs))
        skipped_revs = 0

        if args.times_file:
            times_file = args.times_file
        else:
            # Expensive operation so let's report it.
            printf("Retrieving buck simulate times file.")
            times_file = find_times_file(epoch_seconds)

        # TODO(ruibm): Change buck simulate so it does this internally.
        for time_aggregate in args.time_aggregate:
            report_file = "buck_simulate_report_{0}_{1}.json".format(
                time_aggregate,
                rev.hash)
            report_path = os.path.join(REPORTS_DIR, report_file)
            printf("Running buck simulate. report_file=[{0}]".format(
                report_file))

            try:
                buck_simulate(
                    targets=args.targets,
                    num_threads=args.num_threads,
                    repository=args.repo,
                    report_file=report_path,
                    times_file=times_file,
                    time_aggregate=time_aggregate)
            except Exception as exception:
                printf('Failed to run. rev=[{rev}] exception=[{exc}].'.format(
                    rev=rev.hash,
                    exc=exception
                ))
                failed_revs.append('{0}-{1}'.format(rev.hash, time_aggregate))
                continue

            printf("Uploading results to Scuba table [{0}].".format(
                SCUBA_TABLE))
            upload_to_scuba(
                client=scuba_client,
                rev_info=rev_info,
                report_path=report_path,
            )
        i += 1

    total = (i - 1) * len(args.time_aggregate)
    if len(failed_revs) > 0:
        success = total - len(failed_revs)
        printf('Finished {success} successfully and failed {fail}.'.format(
            fail=len(failed_revs),
            success=success))
        printf('Failed revisions=[{0}]'.format(', '.join(failed_revs)))
    else:
        printf('Successfully simulated all {0} runs.'.format(total))


if __name__ == '__main__':
    main()
