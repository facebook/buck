import os
import shutil
import sys

extra_file_name = "Extra.class"

java_file = None
output = None


def process_arg(s):
    global java_file
    global output
    if s.startswith("@"):
        with open(s[1:]) as argfile:
            for a in argfile.readlines():
                process_arg(a.strip())
    else:
        if s.endswith(".java"):
            java_file = s
            print("found java_file")

        if s.endswith("classes"):
            print("found classes")
            output = s


for s in sys.argv:
    process_arg(s)

assert java_file is not None
assert output is not None

class_file = os.path.join(
    output, os.path.splitext(os.path.split(java_file)[1])[0] + ".class"
)
shutil.copyfile(java_file, class_file)
shutil.copyfile(java_file, os.path.join(output, extra_file_name))
