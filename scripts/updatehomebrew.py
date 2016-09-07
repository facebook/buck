import argparse
import os
import re
import requests  # Install with easy_install or pip install
import shutil
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from updatecommon import get_release
from updatecommon import upload_release


BOTTLE_TARGET = 'yosemite_or_later'


@contextmanager
def os_closing(o):
    yield o
    os.close(o)


def parse_args(args):
    parser = argparse.ArgumentParser(description='Update homebrew-fb')
    parser.add_argument(
        'version_tag',
        help='The name of the tag to create a release for')
    parser.add_argument(
        'github_token',
        type=file,
        help='The authentication token to use to talk to GitHub')
    parser.add_argument(
        '--tap-repo-location',
        default='/usr/local/Library/Taps/facebook/homebrew-fb',
        help='The location of the homebrew-fb tap')
    return parser.parse_args(args)


def build_bottle(version_tag, tap_repo_location):
    print('Building bottle...')
    subprocess.check_call(
        [
            'brew',
            'unlink',
            'buck',
        ],
        cwd=tap_repo_location)
    subprocess.check_call(
        [
            'brew',
            'install',
            '--build-bottle',
            'buck',
        ],
        cwd=tap_repo_location)
    subprocess.check_call(
        [
            'brew',
            'bottle',
            'buck',
        ],
        cwd=tap_repo_location)
    dest_name = 'buck-{version_name}.{bottle_target}.bottle.tar.gz'.format(
        version_name=version_tag[1:],
        bottle_target=BOTTLE_TARGET)
    subprocess.check_call(
        [
            'mv',
            'buck-{version_name}.el_capitan.bottle.tar.gz'.format(
                version_name=version_tag[1:]),
            dest_name,
        ],
        cwd=tap_repo_location)
    return os.path.join(tap_repo_location, dest_name)


def fetch_tarball(url):
    print('Fetching tarball from `{url}`...'.format(url=url))
    r = requests.get(url, stream=True)
    handle, path = tempfile.mkstemp(suffix='.tar.gz')
    with os_closing(handle) as handle:
        for chunk in r.iter_content(chunk_size=4096):
            if chunk:
                os.write(handle, chunk)
    return path


def validate():
    print('Validating bottle...')
    subprocess.check_call([
        'brew',
        'uninstall',
        '--force',
        'buck',
    ])
    subprocess.check_call([
        'brew',
        'install',
        'buck',
    ])


def update_bottle(version_tag, github_token, tap_repo_location):
    release_data = get_release(version_tag)
    tarball_sha256 = sha256(fetch_tarball(release_data['tarball_url']))

    # First, update the bottle to have the new version and tarball sha.
    temp_handle, temp_path = tempfile.mkstemp(text=True)
    with os_closing(temp_handle):
        with open(os.path.join(tap_repo_location, 'buck.rb'), 'r') as orig:
            for line in orig:
                line = re.sub(
                    r'@@buck_version = .*$',
                    '@@buck_version = "{version_name}"'.format(
                        version_name=version_tag[1:]),
                    line)
                line = re.sub(
                    r'sha256 "[a-z0-9]{64}"$',
                    'sha256 "{sha}"'.format(sha=tarball_sha256),
                    line)
                line = re.sub(
                    r'  url "https://.+"$',
                    '  url "{url}"'.format(url=release_data['tarball_url']),
                    line)
                os.write(temp_handle, line)
        shutil.copyfile(temp_path, os.path.join(tap_repo_location, 'buck.rb'))

    # Now, build the bottle's binary, and update the file with the new sha.
    bottle_file = build_bottle(version_tag, tap_repo_location)
    bottle_sha256 = sha256(bottle_file)
    upload_release(
            bottle_file,
            release_data['upload_url'],
            github_token,
            {
                'Content-Type': 'application/x-tar',
            })
    os.remove(bottle_file)

    temp_handle, temp_path = tempfile.mkstemp(text=True)
    with os_closing(temp_handle):
        with open(os.path.join(tap_repo_location, 'buck.rb'), 'r') as orig:
            for line in orig:
                line = re.sub(
                    r'sha256 "[a-z0-9]{64}" => :.+$',
                    'sha256 "{sha}" => :{bottle_target}'.format(
                        sha=bottle_sha256,
                        bottle_target=BOTTLE_TARGET),
                    line)
                os.write(temp_handle, line)
        shutil.copyfile(temp_path, os.path.join(tap_repo_location, 'buck.rb'))

    validate()

    subprocess.check_call(
        [
            'git',
            'commit',
            '-m',
            'Update `buck.rb` to {version_tag}'.format(
                version_tag=version_tag),
            'buck.rb',
        ],
        cwd=tap_repo_location)
    print('Your commit is ready for testing!  Check it out:')
    print(tap_repo_location)


def sha256(file_name):
    return subprocess.check_output([
        'shasum',
        '-a',
        '256',
        file_name,
    ]).split()[0]

if __name__ == '__main__':
    args = parse_args(sys.argv[1:])
    github_token_lines = args.github_token.readlines()
    assert len(github_token_lines) == 1, (
        'Invalid number of lines in github_token file')
    update_bottle(
        args.version_tag,
        github_token_lines[0].strip(),
        args.tap_repo_location)
