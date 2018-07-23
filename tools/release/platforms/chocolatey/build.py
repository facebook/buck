#!/usr/bin/env python

import argparse
import glob
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ElemTree


def parse_args(args):
    parser = argparse.ArgumentParser(description="Build buck's choco package")
    parser.add_argument(
        "--license-file",
        required=True,
        help="The original license file that needs a prefix added",
    )
    parser.add_argument(
        "--verification-txt",
        required=True,
        help="The verification.txt template used when creating the nupkg",
    )
    parser.add_argument(
        "--version", required=True, help="The version that is being built"
    )
    parser.add_argument(
        "--timestamp", required=True, help="The timestamp when the release was made"
    )
    parser.add_argument(
        "--src-dir",
        required=True,
        help="The directory with all of the source files in it (nuspec and changelog)",
    )
    parser.add_argument("--output", required=True, help="where to output the nupkg")
    return parser.parse_args(args)


def copy_files(src_dir):
    # This gets set by genrule in buck
    dest_dir = os.environ["TMP"]
    if not dest_dir:
        raise Exception("TMP was not set in the environment. It must be configured")

    for src in glob.glob(os.path.join(src_dir, "*")):
        dest = os.path.join(dest_dir, os.path.basename(src))
        shutil.copy(src, dest)
    return dest_dir


def update_nuspec(nuspec, changelog, version):
    ns = "http://schemas.microsoft.com/packaging/2015/06/nuspec.xsd"
    nsurl = "{" + ns + "}"
    ElemTree.register_namespace("", ns)
    root = ElemTree.parse(nuspec)
    root.find("./{ns}metadata/{ns}version".format(ns=nsurl)).text = version
    with open(changelog, "r") as fin:
        root.find("./{ns}metadata/{ns}releaseNotes".format(ns=nsurl)).text = fin.read()
    root.write(nuspec)


def build(nuspec, output):
    subprocess.check_call(["choco", "pack", nuspec, "--output-directory", os.getcwd()])
    os.rename(glob.glob("buck.*.nupkg")[0], output)


def write_license_file(original_license):
    dest = "LICENSE.txt"
    with open(original_license, "r") as fin, open(dest, "w") as fout:
        fout.write("From: https://github.com/facebook/buck/blob/master/LICENSE\n")
        fout.write("\n")
        fout.write(fin.read())


def write_verification_txt(original_verification_txt, version, timestamp):
    dest = "VERIFICATION.txt"
    with open(original_verification_txt, "r") as fin, open(dest, "w") as fout:
        verification_text = fin.read().decode("utf-8")
        verification_text = verification_text.format(
            release_version=version, release_timestamp=timestamp
        )
        fout.write(verification_text)


if __name__ == "__main__":
    args = parse_args(sys.argv[1:])
    tmp_dir = copy_files(args.src_dir)
    os.chdir(tmp_dir)
    update_nuspec("buck.nuspec", "CHANGELOG.md", args.version)
    write_license_file(args.license_file)
    write_verification_txt(args.verification_txt, args.version, args.timestamp)
    build("buck.nuspec", args.output)
