#!/usr/bin/env python

import json
import os
import platform
import subprocess
import sys

CORE_QUERY = (
    "deps(//src/com/facebook/buck/core/...) - //third-party/... - //src-gen/..."
)


def find_repo_root(cwd):
    while os.path.exists(cwd):
        if os.path.exists(os.path.join(cwd, ".buckconfig")):
            return cwd
        cwd = os.path.dirname(cwd)
    raise Exception("Could not locate buck repo root.")


def run_process(*args, **kwargs):
    process = subprocess.Popen(stdout=subprocess.PIPE, *args, **kwargs)
    stdout, _ = process.communicate()
    retcode = process.poll()
    if retcode != 0:
        raise Exception("Error %s running %s" % (retcode, args))
    return stdout


def get_actual_core_targets(repo_root):
    env = dict(os.environ.items())
    env["NO_BUCKD"] = "1"
    if platform.system() == "Windows":
        buck_path = os.path.join(repo_root, "bin", "buck.bat")
        cmd_prefix = ["cmd.exe", "/C", buck_path]
    else:
        buck_path = os.path.join(repo_root, "bin", "buck")
        cmd_prefix = [buck_path]
    cmd_output = run_process(
        cmd_prefix + ["query", CORE_QUERY, "--json"], env=env, cwd=repo_root
    )
    cmd_output = cmd_output[cmd_output.index("[") :]
    return set(json.loads(cmd_output))


def get_expected_core_targets():
    dn = os.path.dirname(os.path.realpath(__file__))
    core_modules_json = os.path.join(dn, "core_modules.json")
    with open(core_modules_json) as data_file:
        return set(json.load(data_file))


def print_actual_core_targets(repo_root):
    actual = sorted(get_actual_core_targets(repo_root))
    print(json.dumps(actual, indent=4))


def verify(repo_root):
    actual = get_actual_core_targets(repo_root)
    expected = get_expected_core_targets()

    if actual == expected:
        return 0

    diff1 = sorted(actual - expected)
    diff2 = sorted(expected - actual)

    print("Verification of core boundaries failed!")
    print(
        "The transitive closure of Buck core dependencies is inconsistent with allowed "
        "set of targets."
    )
    print("Try to refactor the dependencies to keep the set of core targets minimal.")
    print(
        "In case an existing dependency was removed from the core remove that target from "
        "tools/build/core-boundaries/core_modules.json"
    )

    if diff1:
        print("Extra targets:")
        for t in diff1:
            print("    {0}".format(t))

    if diff2:
        print("missing targets:")
        for t in diff2:
            print("    {0}".format(t))

    return 1


if __name__ == "__main__":
    repo_root = find_repo_root(os.getcwd())
    if "--print-actual-core-targets" in sys.argv:
        print_actual_core_targets(repo_root)
    else:
        sys.exit(verify(repo_root))
