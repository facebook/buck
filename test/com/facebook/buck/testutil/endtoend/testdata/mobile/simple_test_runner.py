#!/usr/bin/env python

import json
import subprocess
import sys
import tempfile

if __name__ == "__main__":
    test_specs = []
    # Flags are --buck-test-info /path/to/specs
    with open(sys.argv[2], "rb") as f:
        test_specs = json.load(f)
    # Adb devices returns one line of descriptive text followed by one device per line
    device_serial = (
        subprocess.check_output(["adb", "devices"]).splitlines()[1].split()[0]
    )
    device_prop = "-Dbuck.device.id={}".format(device_serial)
    for t in test_specs:
        # This is hacky, but the -D flag needs to come before the other CL args
        command_with_prop = ["java", device_prop] + t["command"][1:]
        print("Running test", " ".join(command_with_prop))
        output = tempfile.mkdtemp()
        try:
            subprocess.check_output(command_with_prop + ["--output", output])
        except:
            sys.stderr.write("Failed to run test")
