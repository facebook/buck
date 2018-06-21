import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from contextlib import contextmanager

import requests  # Install with easy_install or pip install
from updatecommon import get_release, upload_release

TARGET_MACOS_VER = "yosemite"
TARGET_MACOS_VER_SPEC = TARGET_MACOS_VER + "_or_later"


@contextmanager
def os_closing(o):
    yield o
    os.close(o)


def parse_args(args):
    parser = argparse.ArgumentParser(description="Update homebrew-fb")
    parser.add_argument(
        "version_tag", help="The name of the tag to create a release for"
    )
    parser.add_argument(
        "github_token",
        type=argparse.FileType(),
        help="The authentication token to use to talk to GitHub",
    )
    parser.add_argument(
        "--tap-repo-location",
        default="/usr/local/Homebrew/Library/Taps/facebook/homebrew-fb",
        help="The location of the homebrew-fb tap",
    )
    return parser.parse_args(args)


def build_bottle(version_tag, tap_repo_location):
    print("Building bottle...")
    subprocess.check_call(["brew", "unlink", "buck"], cwd=tap_repo_location)

    subprocess.check_call(
        ["brew", "install", "--build-bottle", "buck"], cwd=tap_repo_location
    )

    subprocess.check_call(
        ["brew", "bottle", "--no-rebuild", "--skip-relocation", "buck"],
        cwd=tap_repo_location,
    )

    bottle_filename = "buck-{ver}.{macos_ver}.bottle.tar.gz".format(
        ver=version_tag[1:], macos_ver=TARGET_MACOS_VER
    )
    subprocess.check_call(
        "mv buck-{ver}.*.bottle.tar.gz {bottle_filename}".format(
            ver=version_tag[1:], bottle_filename=bottle_filename
        ),
        shell=True,  # for wildcard expansion
        cwd=tap_repo_location,
    )

    return os.path.join(tap_repo_location, bottle_filename)


def fetch_tarball(url):
    print("Fetching tarball from `{}`...".format(url))
    r = requests.get(url, stream=True)
    handle, path = tempfile.mkstemp(suffix=".tar.gz")
    with os_closing(handle) as handle:
        for chunk in r.iter_content(chunk_size=4096):
            if chunk:
                os.write(handle, chunk)
    return path


def validate():
    print("Validating bottle...")
    subprocess.check_call(["brew", "uninstall", "--force", "buck"])
    subprocess.check_call(["brew", "install", "buck"])


def update_bottle(version_tag, github_token, tap_repo_location):
    release_data = get_release(version_tag)
    tarball_sha256 = sha256(fetch_tarball(release_data["tarball_url"]))

    # First, update the bottle to have the new version and tarball sha.
    temp_handle, temp_path = tempfile.mkstemp(text=True)
    with os_closing(temp_handle):
        with open(os.path.join(tap_repo_location, "buck.rb"), "r") as orig:
            for line in orig:
                line = re.sub(
                    r"@@buck_version = .*$",
                    '@@buck_version = "{}"'.format(version_tag[1:]),
                    line,
                )
                line = re.sub(
                    r'sha256 "[a-z0-9]{64}"$',
                    'sha256 "{}"'.format(tarball_sha256),
                    line,
                )
                line = re.sub(
                    r'  url "https://.+"$',
                    '  url "{}"'.format(release_data["tarball_url"]),
                    line,
                )
                os.write(temp_handle, line)
        shutil.copyfile(temp_path, os.path.join(tap_repo_location, "buck.rb"))

    # Now, build the bottle's binary, and update the file with the new sha.
    bottle_file = build_bottle(version_tag, tap_repo_location)
    bottle_sha256 = sha256(bottle_file)
    upload_release(
        bottle_file,
        release_data["upload_url"],
        github_token,
        {"Content-Type": "application/x-tar"},
    )
    os.remove(bottle_file)

    temp_handle, temp_path = tempfile.mkstemp(text=True)
    with os_closing(temp_handle):
        with open(os.path.join(tap_repo_location, "buck.rb"), "r") as orig:
            for line in orig:
                line = re.sub(
                    r'sha256 "[a-z0-9]{64}" => :.+$',
                    'sha256 "{sha}" => :{macos_version_spec}'.format(
                        sha=bottle_sha256, macos_version_spec=TARGET_MACOS_VER_SPEC
                    ),
                    line,
                )
                os.write(temp_handle, line)
        shutil.copyfile(temp_path, os.path.join(tap_repo_location, "buck.rb"))

    validate()

    subprocess.check_call(
        [
            "git",
            "commit",
            "-m",
            "Update `buck.rb` to {}".format(version_tag),
            "buck.rb",
        ],
        cwd=tap_repo_location,
    )
    print("Your commit is ready for testing!  Check it out:")
    print(tap_repo_location)


def sha256(file_name):
    with open(file_name) as fd:
        return hashlib.sha256(fd.read()).hexdigest()


if __name__ == "__main__":
    args = parse_args(sys.argv[1:])
    github_token_lines = args.github_token.readlines()
    assert len(github_token_lines) == 1, "Invalid number of lines in github_token file"
    update_bottle(
        args.version_tag, github_token_lines[0].strip(), args.tap_repo_location
    )
