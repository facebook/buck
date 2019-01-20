import json
import os
import sys

json.dump(dict(os.environ), sys.stdout)
