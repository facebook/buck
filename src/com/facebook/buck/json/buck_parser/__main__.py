"""Main module for running this tool standalone.

When buck invokes this tool it generates its own main module.
"""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import with_statement

from . import buck

if __name__ == '__main__':
    buck.main()
