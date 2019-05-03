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

import logging
import os
import tempfile

import dateutil.parser
import magic
import requests

from platforms.common import ReleaseException, run

MESSAGE_PROMPT_TEMPLATE = """\
# Add release notes below. Any lines starting with '#' will be ignored.
{default_message}

# Commits since last release:
#
{commit_messages}
"""

MESSAGE_TEMPLATE = """\
Includes various improvements and bug fixes, viewable here: {html_url}
"""


def get_version_and_timestamp_from_release(release):
    """ Returns the version (without a 'v' prefix) and the timestamp of the release """
    release_version = release["tag_name"].lstrip("v")
    release_timestamp = dateutil.parser.parse(release["created_at"]).strftime("%s")
    return release_version, release_timestamp


def get_token(token_file):
    """
    Reads the first line from token_file to get a token
    """
    with open(token_file, "r") as fin:
        ret = fin.read().strip()
    if not ret:
        raise ReleaseException("No valid token found in {}".format(token_file))
    return ret


def get_current_user(github_token, prefer_fb_email=True):
    """ Gets info about the current github user """
    url = "https://api.github.com/user"
    emails_url = "https://api.github.com/user/emails"
    headers = get_headers(github_token)
    response = requests.get(url, headers=headers)
    response.raise_for_status()
    ret = response.json()
    if not ret["email"].endswith("@fb.com") and prefer_fb_email:
        while emails_url:
            response = requests.get(emails_url, headers=headers)
            response.raise_for_status()
            fb_email = next(
                (
                    email["email"]
                    for email in response.json()
                    if email["verified"] and email["email"].endswith("@fb.com")
                ),
                None,
            )
            if fb_email:
                ret["email"] = fb_email
                break
            else:
                emails_url = response.links.get("next", {}).get("url")
    return ret


def get_commit(repository, github_token, commit):
    """ Gets a specific commit's info from github """
    url = "https://api.github.com/repos/{}/commits/{}".format(repository, commit)
    headers = get_headers(github_token)
    logging.info("Fetching commit {} for {}".format(commit, repository))
    response = requests.get(url, headers=headers)
    response.raise_for_status()
    return response.json()["sha"]


def get_latest_release(repository, github_token):
    """ Gets the latest release object from github or None if there are no releases """
    url = "https://api.github.com/repos/{}/releases/latest".format(repository)
    headers = get_headers(github_token)
    logging.info("Fetching latest release for {}".format(repository))
    response = requests.get(url, headers=headers)
    if response.status_code == 404:
        return None
    response.raise_for_status()
    ret = response.json()
    return ret


def get_summary_from_commit(commit):
    """ Takes a full commit message, and gives something abbreviated for changelogs """
    message = commit["commit"]["message"].split("\n")[0]
    return "{}: {}".format(commit["sha"], message)


def get_summaries_between_releases(
    repository, github_token, earliest_revision, latest_revision
):
    """
    Gets all of the commit messages (as a list) for commits between earliest_revision
    and latest_revision. These messages are summaries, not the full message
    """
    url = "https://api.github.com/repos/{}/compare/{}...{}".format(
        repository, earliest_revision, latest_revision
    )
    headers = get_headers(github_token)
    response = requests.get(url, headers=headers)
    response.raise_for_status()
    js = response.json()
    return (
        js["html_url"],
        [get_summary_from_commit(commit) for commit in js["commits"]],
    )


def get_headers(github_token):
    return {"Authorization": "token " + github_token}


def create_release(repository, github_token, message, version_tag, commit):
    """
    Creates a release on github and returns that data

    Args:
        repository: The name of the repository to work on
        github_token: The token to use for github operations
        message: The message to put in the body of the release
        version_tag: The tag to have github create. This is also used in the release
                     title
        commit: The commit to pin the release to
    """
    url = "https://api.github.com/repos/{}/releases".format(repository)
    data = {
        "tag_name": version_tag,
        "target_commitish": commit,
        "name": "Release {}".format(version_tag),
        "body": message.format(tag=version_tag),
        # Draft would be nice, but then the releases don't show up in the API,
        # and we can't get tarball/zipball urls until publishing :(
        "draft": False,
    }
    headers = get_headers(github_token)
    logging.info("Creating a new release")
    response = requests.post(url, json=data, headers=headers)
    response.raise_for_status()
    ret = response.json()
    logging.info("Created new release at {}".format(ret["html_url"]))
    return ret


def get_all_releases(repository, github_token):
    """ Get all of the releases from github for a repository. Useful for changelogs """
    url = "https://api.github.com/repos/{}/releases".format(repository)
    headers = get_headers(github_token)
    logging.info("Getting all releases")
    releases = []
    while url:
        response = requests.get(url, headers=headers)
        response.raise_for_status()
        releases.extend(response.json())
        if "next" in response.links:
            url = response.links["next"]["url"]
        else:
            url = None
    logging.info("Got {} releases".format(len(releases)))
    return releases


def get_release_for_tag(repository, github_token, version_tag):
    """ Gets the release from github for a specific git tag """
    url = "https://api.github.com/repos/{}/releases/tags/{}".format(
        repository, version_tag
    )
    headers = get_headers(github_token)
    logging.info(
        "Getting release information for {} tagged {}".format(repository, version_tag)
    )
    response = requests.get(url, headers=headers)
    response.raise_for_status()
    ret = response.json()
    logging.info(
        "Got release information for {} tagged {} ({})".format(
            repository, version_tag, ret["html_url"]
        )
    )
    return ret


def prompt_for_message(html_url, summaries):
    """
    Prompts the user for a release message in an editor, showing them the commits since
    last release, and returns what the user specified

    Args:
        html_url:  The url to see the difference between the current commit and the
                   commit for the previous release.
        summaries: The commit summaries to display to the user
    """
    default_message = create_default_message(html_url)
    summaries_text = "\n".join(("# " + line for line in summaries))
    full_message = MESSAGE_PROMPT_TEMPLATE.format(
        default_message=default_message, commit_messages=summaries_text
    )
    temp_fd, temp_path = tempfile.mkstemp()
    try:
        with open(temp_path, "w") as fout:
            fout.write(full_message)
        editor = os.environ.get("EDITOR", "vim")
        run([editor, temp_path])
        with open(temp_path, "r") as fin:
            message = "\n".join(line for line in fin if not line.startswith("#"))
            message = message.strip()
        if not message:
            raise ReleaseException("No valid message was provided")
        return message
    finally:
        if os.path.exists(temp_path):
            os.remove(temp_path)


def create_default_message(html_url):
    return MESSAGE_TEMPLATE.format(html_url=html_url)


def add_assets(release, github_token, path):
    """
    Add an asset at `path` to a specific release

    Args:
        release: The release object from github
        github_token: The token to modify the release
        path: The path of the file to upload

    Returns:
        The asset object from github
    """
    filename = os.path.basename(path)
    url = release["upload_url"].replace("{?name,label}", "?name=" + filename)
    headers = get_headers(github_token)
    headers["Content-Type"] = magic.from_file(path, mime=True)
    logging.info("Adding {} at {} to release".format(filename, path))
    with open(path, "rb") as fin:
        response = requests.post(url, headers=headers, data=fin)
        if response.status_code == 422:
            raise ReleaseException(
                "A file by the name of {} is already attached to {}".format(
                    filename, release["html_url"]
                )
            )
        response.raise_for_status()
        ret = response.json()
    logging.info("Added {} to release at {}".format(filename, release["html_url"]))
    return ret


def create_new_release(
    repository, github_token, version_tag, message, should_prompt_for_message
):
    """
    Creates a new release, optionally prompting the user for a release message

    Args:
        repository: The github repository
        github_token: The github token that can create releases
        verison_tag: The version tag that should be created at the current 'master'
        message: If None, prompt the user for a message (or create a default one),
                 otherwise use this message for the release summary.
        should_prompt_for_message: If false, just create a release that points to the
                            list of changes between the last release and this one.
                            Otherwise, prompt the user for a message.

    Returns:
        The github repository object
    """
    master_commit = get_commit(repository, github_token, "master")
    if not message:
        latest_release = get_latest_release(repository, github_token)
        commit_summaries = []
        html_url = ""
        if latest_release:
            latest_release_hash = get_commit(
                repository, github_token, latest_release["tag_name"]
            )
            html_url, commit_summaries = get_summaries_between_releases(
                repository, github_token, latest_release_hash, master_commit
            )
        if should_prompt_for_message:
            message = prompt_for_message(html_url, commit_summaries)
        else:
            message = create_default_message(html_url)
    release = create_release(
        repository, github_token, message, version_tag, master_commit
    )
    logging.info("Created release at {}".format(release["html_url"]))
    return release
