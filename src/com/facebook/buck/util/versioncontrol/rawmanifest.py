# rawmanifest.py
#
# Copyright 2013-2016 Facebook, Inc.
#
# This software may be used and distributed according to the terms of the
# GNU General Public License version 2 or any later version.
"""Output (a subset of) the manifest as a bser serialisation

Outputs raw manifest data

The manifest can be updated with the current working copy state.

"""
from __future__ import absolute_import

from mercurial import (
    cmdutil,
    match,
    node,
    util,
)

from itertools import chain

cmdtable = {}
command = cmdutil.command(cmdtable)

testedwith = 'internal'


@command(
    'rawmanifest', [
        ['o', 'output', '', 'direct output to a file rather than STDOUT',
         'FILE'],
        ['d', 'deletions', None,
         'add deleted entries (hash set to nullid and flag set to "r")']],
    '[PATTERN]')
def rawmanifest(ui, repo, *patterns, **opts):
    """Output the raw manifest (optionally updated with working copy status)

    If patterns are given and treemanifest is available, filter on paths. This is a best-effort
    filter, without a treemanifest the whole manifest is produced instead.

    """
    ctx = repo['.']

    matcher = None
    rawmanifest = ''
    if patterns:
        # can only honour a path filter if there are is a treemanifest available
        manifest = ctx.manifest()
        if util.safehasattr(manifest, '_treemanifest'):
            # Hybrid manifest
            tmanifest = manifest._treemanifest()
            if tmanifest is not None:
                # a treemanifest is available for this revision
                matcher = match.match(
                    repo.root, repo.getcwd(),
                    patterns=patterns)
                rawmanifest = tmanifest.matches(matcher).text()

    if matcher is None:
        # no paths or no tree manifest available, dump whole manifest
        manifestnode = ctx.manifestnode()
        rawmanifest = repo.manifestlog._revlog.revision(manifestnode)

    try:
        output = None
        if opts['output']:
            output = open(opts['output'], 'wb')
        else:
            output = ui

        output.write(rawmanifest)

        if opts['deletions']:
            # 'r' is a non-hex character, making it easy to distinguish from the hex string
            # preceding the flag when all you you have is a file position for the next newline
            # delimiter.
            deletedline = '\x00{0}r\n'.format(node.hex(node.nullid))
            matchfn = matcher and matcher.matchfn
            status = repo.status()
            for filename in chain(status.removed, status.deleted):
                if matchfn and not matchfn(filename):
                    continue
                output.write(filename)
                output.write(deletedline)
    finally:
        if opts['output'] and output is not None:
            output.close()
