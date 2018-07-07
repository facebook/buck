import logging
import os

import dateutil.parser

from platforms.common import (
    copy_from_docker_linux,
    docker,
    temp_file_with_contents,
    temp_move_file,
)
from releases import get_version_and_timestamp_from_release


def create_changelog(user, releases, release_datetime):
    release_datetime_string = release_datetime.strftime("%a, %d %b %Y %H:%M:%S %z")
    CHANGELOG_TEMPLATE = """\
buck ({version}) stable; urgency=low
  * {body}

"""
    ret = "\n".join(
        (
            CHANGELOG_TEMPLATE.format(
                version=release["tag_name"][1:],
                body=release["body"].strip() or "Periodic release",
            )
            for release in releases
        )
    )
    ret += " -- {name} <{email}>  {date}".format(
        name=user["name"], email=user["email"], date=release_datetime_string
    )
    return ret


def validate(linux_host, image_tag, deb_path):
    """ Spin up a fresh docker image, and make sure that the deb installs and runs """
    DOCKERFILE = r"""\
FROM azul/zulu-openjdk:8
RUN sed -i 's,archive\.canonical\.com,mirror\.facebook\.net,g' /etc/apt/sources.list
RUN apt-get update
ADD {deb_filename} /{deb_filename}
RUN apt install -y /{deb_filename}
RUN touch .buckconfig
RUN /usr/local/bin/buck --help
"""
    build_dir = os.path.dirname(deb_path)
    deb_filename = os.path.basename(deb_path)
    dockerfile_temp_path = os.path.join(build_dir, "Dockerfile")
    dockerfile = DOCKERFILE.format(deb_filename=deb_filename)

    with temp_file_with_contents(dockerfile_temp_path, dockerfile):
        docker(linux_host, ["build", "-t", image_tag + "-validate", build_dir])
        docker(linux_host, ["rmi", image_tag + "-validate"])


def build_deb(repository, release, user, all_releases, linux_host, output_dir):
    """
    Builds a .deb package in docker, and copies it to the host.

    Args:
        repository: The github repository to use. username/repo
        release: The release object from github
        user: The github user information. Required for changelog information
        all_releases: All releases from github. Used for generating a changelog
        linux_host: If set, the docker host ot use that can run linux containers
                    If not None, this should be a format that would work with
                    docker -H
        output_dir: The directory to place artifacts in after the build

    Returns:
        The path to the artifact
    """

    release_version, release_timestamp = get_version_and_timestamp_from_release(release)
    release_datetime = dateutil.parser.parse(release["created_at"])
    image_tag = "buck:" + release_version
    deb_name = "buck.{}_all.deb".format(release_version)
    deb_path = os.path.join(output_dir, deb_name)
    logging.info("Building debian docker image...")

    # Move the in-repo changelog out of the way for the moment. We keep the file in
    # repo so that the build rules evaluate properly and don't complain about missing
    # srcs.
    changelog_path = os.path.join(
        "tools", "release", "platforms", "debian", "Changelog"
    )
    changelog = create_changelog(user, all_releases, release_datetime)
    with temp_move_file(changelog_path), temp_file_with_contents(
        changelog_path, changelog
    ):
        docker(
            linux_host,
            [
                "build",
                "-t",
                image_tag,
                "--build-arg",
                "version=" + release_version,
                "--build-arg",
                "timestamp=" + str(release_timestamp),
                "--build-arg",
                "repository=" + repository,
                "tools/release/platforms/debian",
            ],
        )

    logging.info("Copying deb out of docker container")
    copy_from_docker_linux(linux_host, image_tag, "/src/buck.deb", deb_path)

    logging.info("Validating that .deb installs...")
    validate(linux_host, image_tag, deb_path)

    logging.info("Built .deb file at {}".format(deb_path))
    return deb_path
