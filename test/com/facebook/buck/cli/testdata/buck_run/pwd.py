import os
import sys

cwdbase = os.path.basename(os.getcwd())
print (cwdbase)
sys.exit(0 if cwdbase == 'subdir' else 1)

