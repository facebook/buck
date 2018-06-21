"""
This script is used for diffing the contents of two .buck-out directories,
and will list any files that either have different paths or different
md5 hash sums.
"""

import argparse
import hashlib
import os

hash_cache = {}

parser = argparse.ArgumentParser(description="Recursively diff two directories")
parser.add_argument("dirOne", type=str, help="First top level directory")
parser.add_argument("dirTwo", type=str, help="Second top level directory")
parser.add_argument(
    "--compareRootDirs",
    action="store_true",
    help="Will not compare the ./gen and ./bin sub dirs",
)
args = parser.parse_args()
dirOne = args.dirOne
dirTwo = args.dirTwo
compareRootDirs = args.compareRootDirs


class FileHash:
    def __init__(self, root_dir, path, real_path, relative_path, hash_code):
        self.root_dir = root_dir
        self.path = path
        self.real_path = real_path
        self.relative_path = relative_path
        self.hash_code = hash_code


def hash_file(path, hasher, bytes_per_read=65536):
    file_buf = path.read(bytes_per_read)
    while len(file_buf) > 0:
        hasher.update(file_buf)
        file_buf = path.read(bytes_per_read)
    return hasher.hexdigest()


def clean_path(path, rootDir):
    return path.replace(rootDir, "")


def compute_file_hashes(dir):
    file_hashes = []
    for rootPath, _, filenames in os.walk(dir):
        for filename in filenames:
            path = os.path.abspath(os.path.join(rootPath, filename))
            real_path = os.path.realpath(path)
            if os.path.islink(path):
                continue
            if real_path in hash_cache:
                hash_code = hash_cache[real_path]
            else:
                hash_code = hash_file(open(path, "rb"), hashlib.md5())
                hash_cache[real_path] = hash_code
            file_hashes.append(
                FileHash(dir, path, real_path, clean_path(path, dir), hash_code)
            )
    return file_hashes


def process_hashes(file_hashes, hashes_by_path):
    for file_hash in file_hashes:
        if file_hash.relative_path not in hashes_by_path:
            hashes_by_path[file_hash.relative_path] = []
        hashes_by_path[file_hash.relative_path].append(file_hash)


def add_path_by_extension(path, paths_by_extension):
    _, file_ext = os.path.splitext(path)
    if file_ext == "":
        file_ext = "NONE"
    if file_ext not in paths_by_extension:
        paths_by_extension[file_ext] = []
    paths_by_extension[file_ext].append(path)


def print_line_sep(chr):
    # E.g. "----------...."
    print(chr * 100)


def print_files_for_each_extension(files_by_extension, description):
    for file_ext in files_by_extension:
        files_for_ext = files_by_extension[file_ext]
        files_for_ext.sort()

        print(
            str(len(files_for_ext))
            + " "
            + description
            + " files for extension "
            + file_ext
        )
        print_line_sep("-")
        for path in files_for_ext:
            print(path)
        print_line_sep("-")


def print_h1(text):
    print("")
    print_line_sep("=")
    print(text)
    print_line_sep("=")
    print("")


def compare_dirs(dirOne, dirTwo, description):
    hashes_by_path = {}

    file_hashes_dir_one = compute_file_hashes(dirOne)
    file_hashes_dir_two = compute_file_hashes(dirTwo)

    process_hashes(file_hashes_dir_one, hashes_by_path)
    process_hashes(file_hashes_dir_two, hashes_by_path)

    matching_paths = []
    mismatching_paths = []

    for path in hashes_by_path:
        count = len(hashes_by_path[path])
        if count == 1:
            mismatching_paths.append(path)
        elif count == 2:
            matching_paths.append(path)

    ################################################################
    # Display all the files that only appeared in one ./buck-out
    ################################################################

    extra_files_by_root = {}

    for path in mismatching_paths:
        file_hash = hashes_by_path[path][0]
        if file_hash.root_dir not in extra_files_by_root:
            extra_files_by_root[file_hash.root_dir] = {}
        extra_files_for_root = extra_files_by_root[file_hash.root_dir]
        add_path_by_extension(path, extra_files_for_root)

    for root_dir in extra_files_by_root:
        print_h1(str(len(mismatching_paths)) + " extra files in: " + root_dir)
        print_files_for_each_extension(extra_files_by_root[root_dir], "extra")

    ################################################################
    # Display all the files in both ./buck-outs that had different
    # hash sums.
    ################################################################

    paths_same_hash = 0
    paths_different_hash = 0
    paths_different_hash_by_extension = {}

    for path in matching_paths:
        hashes = map(lambda fh: fh.hash_code, hashes_by_path[path])
        hashes_match = (
            len(filter(lambda hash_code: hash_code != hashes[0], hashes)) == 0
        )
        if hashes_match:
            paths_same_hash += 1
        else:
            paths_different_hash += 1
            add_path_by_extension(path, paths_different_hash_by_extension)

    print_h1(
        str(paths_different_hash) + " files with different hashes in " + description
    )
    print_files_for_each_extension(paths_different_hash_by_extension, "mismatching")

    print_h1(str(paths_same_hash) + " files with matching hashes in " + description)


################################################################
# Main
################################################################

if compareRootDirs:
    compare_dirs(dirOne, dirTwo, "root dirs")
else:
    compare_dirs(os.path.join(dirOne, "bin"), os.path.join(dirTwo, "bin"), "bin")
    compare_dirs(os.path.join(dirOne, "gen"), os.path.join(dirTwo, "gen"), "gen")
