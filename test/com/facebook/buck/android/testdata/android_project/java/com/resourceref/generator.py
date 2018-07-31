#!/usr/bin/env python
import sys


def main(argv):
    RES_COUNT = 3000

    if argv[1] == "res":
        print """\
<?xml version='1.0' encoding='utf-8' ?>
<resources>
"""
        for c in range(RES_COUNT):
            print '  <color name="color_%d">#000</color>' % c
            print '  <string name="string_%d">hi</string>' % c
            print '  <item type="id" name="id_%d" />' % c
        print """\
</resources>
"""


if __name__ == "__main__":
    sys.exit(main(sys.argv))
