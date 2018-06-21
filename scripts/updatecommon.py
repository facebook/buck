import json
import os

import requests  # Install with easy_install or pip install


def get_release(version_tag):
    print(
        "Getting release metadata for {version_tag}...".format(version_tag=version_tag)
    )
    releases = requests.get(
        "https://api.github.com/repos/facebook/buck/releases"
    ).json()
    for data in releases:
        if "tag_name" in data and data["tag_name"] == version_tag:
            return data
    raise RuntimeError(
        "Unable to find release for version {version_tag}!".format(
            version_tag=version_tag
        )
    )


def upload_release(bottle_file, upload_url, github_token, content_type):
    fname = os.path.basename(bottle_file)
    upload_url = upload_url.replace("{?name,label}", "?name=") + fname
    print("Uploading release to {url}...".format(url=upload_url))
    with open(bottle_file, "rb") as bottle_bin:
        r = requests.post(
            upload_url,
            auth=("token", github_token),
            headers=content_type,
            data=bottle_bin,
        )
        print(json.dumps(r.json(), indent=2))
