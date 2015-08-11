#!/usr/bin/env python
import sys
import os
import zipfile
import shutil


def main(argv):
    indir = argv[1]
    outdir = argv[2]
    for root, dirs, files in os.walk(indir):
        outroot = os.path.join(outdir, os.path.relpath(root, indir))
        for d in dirs:
            os.mkdir(os.path.join(outroot, d))
        for f in files:
            infile = os.path.join(root, f)
            outfile = os.path.join(outroot, f)
            if not f.endswith(".jar"):
                shutil.copyfile(infile, outfile)
            else:
                inzip = zipfile.ZipFile(infile, 'r')
                outzip = zipfile.ZipFile(outfile, 'w')
                for zinfo in inzip.infolist():
                    raw_content = inzip.read(zinfo.filename)
                    transformed = raw_content.replace(
                        b"__FIND_ME_HERE__content=1__GOODBYE__",
                        b"__FIND_ME_HERE__content=2__GOODBYE__")
                    outzip.writestr(zinfo, transformed)
                outzip.close()
                inzip.close()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
