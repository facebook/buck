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

import glob
import hashlib
import logging
import os
import re
import shutil
import tempfile

import requests
from platforms.common import ReleaseException, run, temp_move_file
from releases import get_version_and_timestamp_from_release


def brew(homebrew_dir, command, *run_args, **run_kwargs):
    """
    Run brew that is installed in the specified prefix.

    Args:
        homebrew_dir: The path containing bin/brew. e.g. /usr/local
        command: The list of args to pass to the brew command
        run_args: Extra args to send to platforms.common.run
        run_kwargs: Extra kwargs to send to platforms.common.run
    Returns:
        Result from subprocess.run
    """
    brew_path = os.path.join(homebrew_dir, "bin", "brew")
    return run([brew_path] + command, *run_args, **run_kwargs)


def install_homebrew(homebrew_dir):
    logging.info("Installing homebrew to {}".format(homebrew_dir))
    if not os.path.exists(homebrew_dir):
        os.makedirs(homebrew_dir)

    logging.info("Downloading homebrew...")
    response = requests.get(
        "https://github.com/Homebrew/brew/tarball/master", stream=True
    )
    response.raise_for_status()
    with tempfile.NamedTemporaryFile() as fout:
        for chunk in response.iter_content(1024 * 1024):
            fout.write(chunk)
        fout.flush()
        logging.info("Extracting homebrew...")
        run(["tar", "xzf", fout.name, "--strip", "1", "-C", homebrew_dir])
        logging.info("Extracted homebrew")


def fetch_tarball_sha256(url):
    """ Get the sha256 of a tarball """
    logging.info("Fetching tarball from {}...".format(url))
    response = requests.get(url, stream=True)
    sha256 = hashlib.sha256()
    for chunk in response.iter_content(chunk_size=1024 * 1024):
        sha256.update(chunk)
    hex_hash = sha256.hexdigest()
    logging.info("Downloaded {} with hash {}".format(url, hex_hash))
    return hex_hash


def get_formula_path(homebrew_dir, tap_repository):
    """ Get the path for the buck forumula in the given repository """
    result = brew(homebrew_dir, ["formula", tap_repository + "/buck"], None, True)
    return result.stdout.decode("utf-8").strip()


def setup_tap(homebrew_dir, tap_repository):
    """ Make sure that `tap_repository` is tapped """
    logging.info("Tapping {}".format(tap_repository))
    brew(homebrew_dir, ["tap", tap_repository])
    logging.info("Tapped {}".format(tap_repository))


def update_formula_before_bottle(
    release, release_version, release_timestamp, formula_path, tarball_sha256
):
    """
    Updates `formula_path` with correct urls, version and sha for building a bottle

    Args:
        release: The github release object
        release_version: The version of the release (no "v" prefix)
        release_timestamp: The timestamp to use while building
        formula_path: The local path to the buck formula
        tarball_sha256: The sha256 of the source tarball for the specified release
    """
    logging.info("Updating formula at {}".format(formula_path))
    with open(formula_path, "r") as fin:
        all_data = fin.read()
        all_data = re.sub(
            r"@@buck_version = .*$",
            '@@buck_version = "{}"'.format(release_version),
            all_data,
            flags=re.MULTILINE,
        )
        all_data = re.sub(
            r"@@buck_release_timestamp = .*$",
            '@@buck_release_timestamp = "{}"'.format(release_timestamp),
            all_data,
            flags=re.MULTILINE,
        )
        all_data = re.sub(
            r'sha256 "[a-z0-9]{64}"$',
            'sha256 "{}"'.format(tarball_sha256),
            all_data,
            flags=re.MULTILINE,
        )
        all_data = re.sub(
            r'  url "https://.+"$',
            r'  url "{}"'.format(release["tarball_url"]),
            all_data,
            flags=re.MULTILINE,
        )
    with open(formula_path, "w") as fout:
        fout.write(all_data)


def build_bottle_file(
    homebrew_dir,
    tap_repository,
    tap_path,
    release_version,
    target_macos_version,
    output_dir,
):
    """
    Builds the actual bottle file via brew

    Args:
        tap_repository: The name of the tap repository
        tap_path: The local path to the given tap repository
        release_version: The version that should be built (no "v" prefix)
        target_macos_version: The target macos short nameto use in the resulting path
        output_dir: The directory to move the build artifact to after building

    Returns:
        The path to the bottle.tar.gz
    """
    brew_target = tap_repository + "/buck"

    # So, if buck wasn't linked to begin with, we can't unlink it. Ideally the install
    # fails down the road. There is, so far as I could tell, no way to verify if
    # a formula is linked :/
    logging.info("Unlinking buck")
    brew(homebrew_dir, ["unlink", brew_target], tap_path, check=False)

    logging.info("Building bottle")
    # If there is still a buck file that exists, move it out of the way for now
    # This should generally not be an issue outside of FB
    with temp_move_file("/usr/local/bin/buck") as moved:
        # Cool, so install --force will still not rebuild. Uninstall, and just don't
        # care if the uninstall fails
        brew(
            homebrew_dir,
            ["uninstall", "--force", "--build-bottle", brew_target],
            tap_path,
            check=False,
        )
        brew(
            homebrew_dir,
            ["install", "--force", "--build-bottle", brew_target],
            tap_path,
        )
        logging.info("Creating bottle file")
        brew(
            homebrew_dir,
            ["bottle", "--no-rebuild", "--skip-relocation", brew_target],
            tap_path,
        )
        logging.info("Created bottle file")
        if moved:
            # Make sure to unlink again so that we can move the original file back
            logging.info("Unlinking buck again")
            brew(homebrew_dir, ["unlink", brew_target], tap_path)

    bottle_filename = "buck-{ver}.{macos_ver}.bottle.tar.gz".format(
        ver=release_version, macos_ver=target_macos_version
    )
    bottle_path = os.path.join(output_dir, bottle_filename)
    bottles = glob.glob(
        os.path.join(tap_path, "buck--{}*.bottle.tar.gz".format(release_version))
    )
    if len(bottles) != 1:
        raise ReleaseException(
            "Got an invalid number of bottle files ({} files: {})".format(
                len(bottles), " ".join(bottles)
            )
        )
    shutil.move(bottles[0], bottle_path)
    return bottle_path


def get_sha256(path, chunk_size=1024 * 1024):
    """ Get the sha256 of a file """
    sha = hashlib.sha256()
    with open(path, "rb") as fin:
        data = fin.read(chunk_size)
        while data:
            sha.update(data)
            data = fin.read(chunk_size)
    return sha.hexdigest()


def update_formula_after_bottle(formula_path, sha, target_macos_version_spec):
    """
    Update the buck formula with the sha for the newly created bottle

    Args:
        formula_path: The path to the buck formula
        sha: The new sha to use
        target_macos_version_spec: The version spec to use for this sha
    """
    logging.info("Updating formula with new bottle sha")
    with open(formula_path, "r") as fin:
        all_data = fin.read()
        all_data = re.sub(
            r'sha256 "[a-z0-9]+" => :.*$',
            'sha256 "{}" => :{}'.format(sha, target_macos_version_spec),
            all_data,
            flags=re.MULTILINE,
        )
    with open(formula_path, "w") as fout:
        fout.write(all_data)
    logging.info("Updated formula with new bottle sha")


def push_tap(git_repository, tap_path, version):
    """
    Grab any working directory changes for the tap, clone a new tap repository,
    and push those changes upstream. The original tap path is in a clean state
    after this push. The clone is done with ssh, so ssh keys must be available

    Args:
        git_repository: The repo on github that needs to be cloned/pushed to
        tap_path: The directory that the tap (with changes) exists in
        version: The version to use in commit messages
    """
    logging.info("Gathering git diff from {}".format(tap_path))
    git_diff = run(["git", "diff"], tap_path, True).stdout
    git_url = "git@github.com:{}.git".format(git_repository)

    with tempfile.TemporaryDirectory() as temp_dir:
        logging.info("Cloning {} into {}".format(git_url, temp_dir))
        run(["git", "clone", git_url, temp_dir])

        logging.info("Cloned into {}. Applying patch".format(temp_dir))
        run(["git", "apply", "-"], temp_dir, input=git_diff)

        logging.info("Committing...")
        with tempfile.NamedTemporaryFile() as fout:
            commit_message = (
                "Bump buck to version {}\n\nThis commit was generated by "
                "release automation\n"
            ).format(version)
            fout.write(commit_message.encode("utf-8"))
            fout.flush()
            run(["git", "commit", "-F", fout.name, "buck.rb"], temp_dir)

        logging.info("Pushing commit upstream")
        run(["git", "push", "origin"], temp_dir)
        logging.info("Pushed commit upstream!")

    logging.info("Resetting state of {}, and updating it after push".format(tap_path))
    run(["git", "checkout", "buck.rb"], tap_path)
    run(["git", "checkout", "master"], tap_path)
    run(["git", "pull"], tap_path)
    logging.info("Reset state of {}, and updating it after push".format(tap_path))


def validate_tap(homebrew_dir, tap_repository, version):
    logging.info("Validating that brew installs with new tap information")
    brew_target = tap_repository + "/buck"
    brew(homebrew_dir, ["uninstall", "--force", brew_target])
    with temp_move_file("/usr/local/bin/buck") as moved:
        brew(homebrew_dir, ["install", brew_target])
        output = (
            brew(homebrew_dir, ["info", brew_target], capture_output=True)
            .stdout.decode("utf-8")
            .splitlines()[0]
        )
        if moved:
            brew(homebrew_dir, ["uninstall", brew_target])
        if "{}/buck: stable {}".format(tap_repository, version) not in output:
            raise ReleaseException(
                "Expected version {} to be installed, but got this from `brew info {}`: {}".format(
                    version, tap_repository, output
                )
            )


def publish_tap_changes(homebrew_dir, tap_repository, version):
    git_user, git_repo = tap_repository.split("/")
    full_git_repo = "{}/homebrew-{}".format(git_user, git_repo)
    formula_path = get_formula_path(homebrew_dir, tap_repository)
    tap_path = os.path.dirname(formula_path)

    push_tap(full_git_repo, tap_path, version)


def log_about_manual_tap_push(homebrew_dir, tap_repository):
    formula_path = get_formula_path(homebrew_dir, tap_repository)
    tap_path = os.path.dirname(formula_path)
    logging.info(
        "The homebrew tap is ready for a pull request. It can be found at {}".format(
            tap_path
        )
    )


def build_bottle(
    homebrew_dir,
    release,
    tap_repository,
    target_macos_version,
    target_macos_version_spec,
    output_dir,
):
    release_version, release_timestamp = get_version_and_timestamp_from_release(release)

    if not os.path.exists(os.path.join(homebrew_dir, "bin", "brew")):
        install_homebrew(homebrew_dir)
    setup_tap(homebrew_dir, tap_repository)
    formula_path = get_formula_path(homebrew_dir, tap_repository)
    tap_path = os.path.dirname(formula_path)

    tarball_sha256 = fetch_tarball_sha256(release["tarball_url"])

    # First, update the bottle to have the new version and tarball sha.
    update_formula_before_bottle(
        release, release_version, release_timestamp, formula_path, tarball_sha256
    )

    # Build the actual bottle file
    bottle_path = build_bottle_file(
        homebrew_dir,
        tap_repository,
        tap_path,
        release_version,
        target_macos_version,
        output_dir,
    )

    # Get the bottle file sha, and update the bottle formula
    bottle_sha = get_sha256(bottle_path)
    update_formula_after_bottle(formula_path, bottle_sha, target_macos_version_spec)

    return bottle_path
