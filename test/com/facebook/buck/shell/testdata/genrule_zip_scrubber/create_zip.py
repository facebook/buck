#!/usr/bin/env python

import sys
import zipfile

date_options = [(1990, 4, 19, 11, 0, 0), (1984, 2, 5, 10, 0, 0)]

zip = zipfile.ZipFile(sys.argv[1], "w")
zip.writestr(zipfile.ZipInfo("file.txt", date_options[int(sys.argv[2])]), "data")
zip.close()
