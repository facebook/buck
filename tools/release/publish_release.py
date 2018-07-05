#!/usr/bin/env python3

import argparse
import datetime
import logging
import os
import shutil
import sys
import tempfile

from platforms.chocolatey import build_chocolatey, publish_chocolatey
from platforms.common import ReleaseException
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
TARGET_MACOS_VERSION_SPEC = TARGET_MACOS_VERSION + "_or_later"


def parse_args(args):
    parser = argparse.ArgumentParser("Publish releases of buck to github")
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
    parsed_kwargs = dict(parser.parse_args(args)._get_kwargs())
    if parsed_kwargs["deb_file"]:
        parsed_kwargs["build_deb"] = False
    if parsed_kwargs["homebrew_file"]:
        parsed_kwargs["build_homebrew"] = False
    if parsed_kwargs["chocolatey_file"]:
        parsed_kwargs["build_chocolatey"] = False
    return argparse.Namespace(**parsed_kwargs)


def configure_logging():
    TTY_LOGGING = " publish_release => \033[1m%(message)s\033[0m"
    NOTTY_LOGGING = " publish_release => %(message)s"
    msg_format = TTY_LOGGING if sys.stderr.isatty() else NOTTY_LOGGING
    logging.basicConfig(level=logging.INFO, format=msg_format)


def build(args, output_dir, release, github_token):
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
            release,
            args.tap_repository,
            args.homebrew_target_macos_version,
            args.homebrew_target_macos_version_spec,
            output_dir,
        )
    if args.build_chocolatey:
        chocolatey_file = build_chocolatey(
            args.repository, release, args.docker_windows_host, output_dir
        )

    return deb_file, homebrew_file, chocolatey_file


def publish(
    args,
    release,
    github_token,
    chocolatey_token,
    deb_file,
    homebrew_file,
    chocolatey_file,
):
    if args.upload_assets:
        if deb_file:
            add_assets(release, github_token, deb_file)
        if chocolatey_file:
            add_assets(release, github_token, chocolatey_file)
            if args.chocolatey_publish:
                publish_chocolatey(chocolatey_file, chocolatey_token)
        if homebrew_file:
            add_assets(release, github_token, homebrew_file)
            validate_tap(args.tap_repository, args.version)
            if args.homebrew_push_tap:
                publish_tap_changes(args.version, args.tap_repository)
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

    if args.use_existing_release:
        release = get_release_for_tag(args.repository, github_token, version_tag)
    else:
        release = create_new_release(
            args.repository, github_token, version_tag, args.release_message
        )
    try:
        if args.output_dir:
            output_dir = args.output_dir
        else:
            temp_dir = tempfile.mkdtemp()
            output_dir = temp_dir

        deb_file, homebrew_file, chocolatey_file = build(
            args, output_dir, release, github_token
        )
        publish(
            args,
            release,
            github_token,
            chocolatey_token,
            deb_file,
            homebrew_file,
            chocolatey_file,
        )

    except ReleaseException:
        logging.error(str(ReleaseException))
    finally:
        if not args.keep_temp_files and temp_dir:
            try:
                shutil.rmtree(temp_dir)
            except Exception:
                logging.error("Could not remove temp dir at {}".format(temp_dir))


if __name__ == "__main__":
    main()
