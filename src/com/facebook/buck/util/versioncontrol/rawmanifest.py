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
    node,
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
         'add deleted entries (hash set to nullid and flag set to "d")']],
    '[PREFIX]')
def rawmanifest(ui, repo, *args, **opts):
    """Output the raw manifest (optionally updated with working copy status)"""
    manifestnode = repo['.'].manifestnode()
    rawmanifest = repo.manifestlog._revlog.revision(manifestnode)

    try:
        output = None
        if opts['output']:
            output = open(opts['output'], 'wb')
        else:
            output = ui

        output.write(rawmanifest)

        if opts['deletions']:
            deletedline = '\x00{0}d\n'.format(node.hex(node.nullid))
            status = repo.status()
            for filename in chain(status.removed, status.deleted):
                output.write(filename)
                output.write(deletedline)
    finally:
        if opts['output'] and output is not None:
            output.close()
