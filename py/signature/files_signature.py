#!/usr/bin/env python
# Copyright (c) Facebook, Inc. and its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

""" Produces a really simple md5 signature of a list of files.

Usage: python file_signatures.py <name1>=<file_or_dir1> <name2>=<file_or_dir2> ...

This will produce a file like:

```
<full signature>
{
  "name1": <signature1>,
  "name2": <signature2>,
  ...
}
```

The signature of a file is the md5 of its contents. For a directory we create a
dictionary with an entry for each file in the directory mapping to the md5 of
that file's contents. We then stringify that dictionary and compute an md5 of it.

The "full signature" is computed similar to a directory, with a dictionary
mapping in all the passed in name's to that file/dir's signature and then
computing a md5 of the stringified dictionary.
"""

from __future__ import print_function

import hashlib
import json
import os
import sys


def file_signature(path, verbose=False):
    with open(path, "rb") as f:
        content = f.read()
        digest = hashlib.md5(content).hexdigest()
        if verbose:
            print(path, digest)
        return digest


def dir_signature(path, verbose=False):
    digest = {}
    for (root, _, files) in os.walk(path):
        for f in files:
            f = os.path.join(root, f)
            relpath = os.path.relpath(f, path)
            digest[relpath] = file_signature(f, verbose)
    if verbose:
        print(path, digest)
    return digest


""" Computes signature for a path pointing to a file or directory. """


def path_signature(path, verbose=False):
    if os.path.isdir(path):
        return dir_signature(path)
    return file_signature(path)


def digests_to_str(digests):
    return json.dumps(digests, sort_keys=True, indent=2)


def signature(files, verbose=False):
    """
    Hashes the content of given files using md5 algorithm in the sorted order
    """
    digests = {}
    for (name, path) in files:
        digest = path_signature(path, verbose)
        if verbose:
            print("%s=%s" % (name, digest))
        digests[name] = digest
    digest_str = digests_to_str(digests)
    full_digest = hashlib.md5(digest_str.encode("utf-8")).hexdigest()
    return [full_digest, digest_str]


if __name__ == "__main__":
    print("\n".join(signature(arg.split("=") for arg in sys.argv[1:])))
