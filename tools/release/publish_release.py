#!/usr/bin/env python3
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


import argparse
import datetime
import logging
import logging.config
import os
import shutil
import subprocess
import sys
import tempfile

from platforms.chocolatey import build_chocolatey, publish_chocolatey
from platforms.common import ReleaseException, docker, run
from platforms.debian import build_deb
from platforms.homebrew import (
    build_bottle,
    log_about_manual_tap_push,
    publish_tap_changes,
    validate_tap,
)
from releases import (
    add_assets,
    create_new_release,
    get_all_releases,
    get_current_user,
    get_release_for_tag,
    get_token,
)

TARGET_MACOS_VERSION = "yosemite"
TARGET_MACOS_VERSION_SPEC = TARGET_MACOS_VERSION


def parse_args(args):
    parser = argparse.ArgumentParser("Publish releases of buck to github")
    parser.add_argument(
        "--valid-git-upstreams",
        default=(
            "git@github.com:facebook/buck.git",
            "https://github.com/facebook/buck.git",
        ),
        nargs="+",
        help="List of valid upstreams for the git repository in order to publish",
    )
    parser.add_argument(
        "--github-token-file",
        default=os.path.expanduser("~/.buck-github-token"),
        help="A file containing the github token to use",
    )
    parser.add_argument(
        "--github-token",
        help="If provided, use this github token instead of the one in `--github-token-file`",
    )
    parser.add_argument(
        "--repository",
        default="facebook/buck",
        help="The github repository to operate on",
    )
    parser.add_argument(
        "--tap-repository", default="facebook/fb", help="The tap to use for homebrew"
    )
    parser.add_argument(
        "--version",
        default=datetime.datetime.now().strftime("%Y.%m.%d.01"),
        help=(
            "Version to use in git tags and github releases. This is generated "
            "by default"
        ),
    )
    parser.add_argument(
        "--use-existing-release",
        action="store_true",
        help=(
            "If specified, use an existing release (specified by --version), rather "
            "than pushing tags and creating a new release"
        ),
    )
    parser.add_argument(
        "--release-message",
        help=(
            "If specified, use this for the release message. If not specified, "
            "and a new release is created, user will be prompted for a message"
        ),
    )
    parser.add_argument(
        "--no-prompt-for-message",
        help="If set, use a default message rather than prompting for a message",
        action="store_false",
        dest="prompt_for_message",
    )
    parser.add_argument(
        "--no-build-deb",
        dest="build_deb",
        action="store_false",
        help="Do not build deb packages for this release",
    )
    parser.add_argument(
        "--no-build-homebrew",
        dest="build_homebrew",
        action="store_false",
        help="Do not build homebrew packages for this release",
    )
    parser.add_argument(
        "--no-build-chocolatey",
        dest="build_chocolatey",
        action="store_false",
        help="Do not build chocolatey packages for this release",
    )
    parser.add_argument(
        "--deb-file",
        help="Upload this file as the deb for this release. Implies --no-build-deb",
    )
    parser.add_argument(
        "--homebrew-file",
        help="Upload this file as the bottle for this release. Implies --no-build-homebrew",
    )
    parser.add_argument(
        "--chocolatey-file",
        help="Upload this file as the nupkg for this release. Implies --no-build-chocolatey",
    )
    parser.add_argument(
        "--docker-linux-host",
        help="If provided, the docker:port to connect to to build linux images",
    )
    parser.add_argument(
        "--docker-windows-host",
        help="If provided, the docker:port to connect to to build windows images",
    )
    parser.add_argument(
        "--docker-windows-memory",
        default="4g",
        help="The memory argument to pass to docker for windows containers",
    )
    parser.add_argument(
        "--docker-windows-isolation",
        default="process",
        help="The --isolation= argument for windows docker commands",
    )
    parser.add_argument(
        "--keep-temp-files",
        action="store_true",
        help="Keep temporary files regardless of success/failure",
    )
    parser.add_argument(
        "--no-upload-assets",
        dest="upload_assets",
        action="store_false",
        help="Do not upload assets",
    )
    parser.add_argument(
        "--homebrew-target-macos-version",
        default=TARGET_MACOS_VERSION,
        help="The target macos version to use in homebrew specs",
    )
    parser.add_argument(
        "--homebrew-target-macos-version-spec",
        default=TARGET_MACOS_VERSION_SPEC,
        help="The target macos version spec to use in homebrew specs",
    )
    parser.add_argument(
        "--no-homebrew-push-tap",
        dest="homebrew_push_tap",
        action="store_false",
        help="Do not push the homebrew tap. A manual commit will have to be made",
    )
    parser.add_argument(
        "--no-chocolatey-publish",
        dest="chocolatey_publish",
        action="store_false",
        help="Do not publish to chocolatey's community stream",
    )
    parser.add_argument(
        "--chocolatey-token",
        help="If provided, use this chocolatey token instead of the one in `--chocolatey-token-file`",
    )
    parser.add_argument(
        "--chocolatey-token-file",
        default=os.path.expanduser("~/.buck-chocolatey-token"),
        help="A file containing the chocolatey token to use",
    )
    parser.add_argument(
        "--output-dir",
        help=(
            "If specified, artifacts will be written to this directory, instead of "
            "a temporary one"
        ),
    )
    parser.add_argument(
        "--homebrew-dir",
        help=(
            "Where homebrew is (e.g. /usr/local). If not specified, homebrew will be "
            "installed in a separate, temporary directory that gets cleaned up after "
            "building (unless --keep-temp-files is specified). If --output-dir is "
            "specified, homebrew will be installed in a subdirectory there. This can "
            "be useful to ensure that tap directories are preserved and can be "
            "validated and pushed to github if a first run fails, or if a "
            "--no-upload-asset run is done"
        ),
    )
    parser.add_argument(
        "--insecure-chocolatey-upload",
        action="store_true",
        help=(
            "Do less certificate verification when uploading to chocolatey. "
            "This is a workaround for "
            "https://github.com/chocolatey/chocolatey.org/issues/584"
        ),
    )
    parsed_kwargs = dict(parser.parse_args(args)._get_kwargs())
    if parsed_kwargs["deb_file"]:
        parsed_kwargs["build_deb"] = False
    if parsed_kwargs["homebrew_file"]:
        parsed_kwargs["build_homebrew"] = False
    if parsed_kwargs["chocolatey_file"]:
        parsed_kwargs["build_chocolatey"] = False
    return argparse.Namespace(**parsed_kwargs)


def configure_logging():
    # Bold message
    TTY_LOGGING = " publish_release => \033[1m%(message)s\033[0m"
    NOTTY_LOGGING = " publish_release => %(message)s"
    msg_format = TTY_LOGGING if sys.stderr.isatty() else NOTTY_LOGGING

    # Red message for errors
    TTY_ERROR_LOGGING = " publish_release => \033[1;31mERROR: %(message)s\033[0m"
    NOTTY_ERROR_LOGGING = " publish_release => ERROR: %(message)s"
    error_msg_format = TTY_ERROR_LOGGING if sys.stderr.isatty() else NOTTY_ERROR_LOGGING

    class LevelFilter(logging.Filter):
        def filter(self, record):
            return record.levelno < logging.ERROR

    logging.config.dictConfig(
        {
            "version": 1,
            "filters": {"lower_than_error": {"()": LevelFilter}},
            "formatters": {
                "info": {"format": msg_format},
                "error": {"format": error_msg_format},
            },
            "handlers": {
                "info": {
                    "level": "INFO",
                    "class": "logging.StreamHandler",
                    "formatter": "info",
                    "filters": ["lower_than_error"],
                },
                "error": {
                    "level": "ERROR",
                    "class": "logging.StreamHandler",
                    "formatter": "error",
                },
            },
            "loggers": {"": {"handlers": ["info", "error"], "level": "INFO"}},
        }
    )


def validate_repo_upstream(args):
    """ Make sure we're in the right repository, not a fork """
    output = subprocess.check_output(
        ["git", "remote", "get-url", "origin"], encoding="utf-8"
    ).strip()
    if output not in args.valid_git_upstreams:
        raise ReleaseException(
            "Releases may only be published from the upstream OSS buck repository"
        )


def validate_environment(args):
    """ Make sure we can build """

    validate_repo_upstream(args)
    if args.build_deb:
        ret = docker(
            args.docker_linux_host,
            ["info", "-f", "{{.OSType}}"],
            check=False,
            capture_output=True,
        )
        host = args.docker_linux_host or "localhost"
        if ret.returncode != 0:
            raise ReleaseException(
                "docker info on linux host {} failed. debs cannot be built", host
            )
        host_os = ret.stdout.decode("utf-8").strip()
        if host_os != "linux":
            raise ReleaseException(
                "docker info on host {} returned type '{}' not 'linux'. debs cannot be built",
                host,
                host_os,
            )
    if args.build_chocolatey:
        ret = docker(
            args.docker_windows_host,
            ["info", "-f", "{{.OSType}}"],
            check=False,
            capture_output=True,
        )
        host = args.docker_windows_host or "localhost"
        if ret.returncode != 0:
            raise ReleaseException(
                "docker info on windows host {} failed. chocolatey nupkgs cannot be built",
                host,
            )
        host_os = ret.stdout.decode("utf-8").strip()
        if host_os != "windows":
            raise ReleaseException(
                "docker info on host {} returned type '{}' not 'windows'. chocolatey nupkgs cannot be built",
                host,
                host_os,
            )
    if args.build_homebrew:
        if args.homebrew_dir:
            if not os.path.exists(args.homebrew_dir):
                raise ReleaseException(
                    "Specified homebrew path, {}, does not exist", args.homebrew_dir
                )
            brew_path = os.path.join(args.homebrew_dir, "bin", "brew")
            try:
                ret = run([brew_path, "--version"])
            except Exception:
                raise ReleaseException(
                    "{} --version failed. bottles cannot be created", brew_path
                )


def build(args, output_dir, release, github_token, homebrew_dir):
    deb_file = args.deb_file
    chocolatey_file = args.chocolatey_file
    homebrew_file = args.homebrew_file

    if args.build_deb:
        user = get_current_user(github_token)
        releases = get_all_releases(args.repository, github_token)
        deb_file = build_deb(
            args.repository, release, user, releases, args.docker_linux_host, output_dir
        )
    if args.build_homebrew:
        homebrew_file = build_bottle(
            homebrew_dir,
            release,
            args.repository,
            args.tap_repository,
            args.homebrew_target_macos_version,
            args.homebrew_target_macos_version_spec,
            output_dir,
        )
    if args.build_chocolatey:
        chocolatey_file = build_chocolatey(
            args.repository,
            release,
            args.docker_windows_host,
            args.docker_windows_memory,
            args.docker_windows_isolation,
            output_dir,
        )

    return deb_file, homebrew_file, chocolatey_file


def publish(
    args,
    release,
    github_token,
    chocolatey_token,
    deb_file,
    homebrew_file,
    homebrew_dir,
    chocolatey_file,
):
    if args.upload_assets:
        if deb_file:
            add_assets(release, github_token, deb_file)
        if chocolatey_file:
            add_assets(release, github_token, chocolatey_file)
            if args.chocolatey_publish:
                publish_chocolatey(
                    chocolatey_file, chocolatey_token, args.insecure_chocolatey_upload
                )
        if homebrew_file:
            add_assets(release, github_token, homebrew_file)
            validate_tap(homebrew_dir, args.tap_repository, args.version)
            if args.homebrew_push_tap:
                publish_tap_changes(homebrew_dir, args.tap_repository, args.version)
            else:
                log_about_manual_tap_push(args.tap_repository)


def main():
    args = parse_args(sys.argv[1:])
    configure_logging()
    version_tag = "v" + args.version
    github_token = (
        args.github_token if args.github_token else get_token(args.github_token_file)
    )
    if args.chocolatey_publish:
        chocolatey_token = (
            args.chocolatey_token
            if args.chocolatey_token
            else get_token(args.chocolatey_token_file)
        )
    else:
        chocolatey_token = None

    temp_dir = None
    temp_homebrew_dir = None
    homebrew_file = None

    try:
        validate_environment(args)

        if args.use_existing_release:
            release = get_release_for_tag(args.repository, github_token, version_tag)
        else:
            release = create_new_release(
                args.repository,
                github_token,
                version_tag,
                args.release_message,
                args.prompt_for_message,
            )
        if args.output_dir:
            output_dir = args.output_dir
            if not os.path.exists(output_dir):
                logging.info("{} does not exist. Creating it".format(output_dir))
                os.makedirs(output_dir, exist_ok=True)
        else:
            temp_dir = tempfile.mkdtemp()
            output_dir = temp_dir
        if args.homebrew_dir:
            homebrew_dir = args.homebrew_dir
        elif args.output_dir:
            homebrew_dir = os.path.abspath(
                os.path.join(output_dir, "homebrew_" + version_tag)
            )
        else:
            temp_homebrew_dir = tempfile.mkdtemp()
            homebrew_dir = temp_homebrew_dir

        deb_file, homebrew_file, chocolatey_file = build(
            args, output_dir, release, github_token, homebrew_dir
        )
        publish(
            args,
            release,
            github_token,
            chocolatey_token,
            deb_file,
            homebrew_file,
            homebrew_dir,
            chocolatey_file,
        )

    except ReleaseException as e:
        logging.error(str(e))
    finally:
        if not args.keep_temp_files:

            def remove(path):
                try:
                    shutil.rmtree(path)
                except Exception:
                    logging.error("Could not remove temp dir at {}".format(path))

            if temp_dir:
                remove(temp_dir)
            if temp_homebrew_dir:
                # If the person didn't want to publish, we need to keep this around
                if not homebrew_file or args.homebrew_push_tap:
                    remove(temp_homebrew_dir)


if __name__ == "__main__":
    main()
