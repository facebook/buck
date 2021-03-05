#!/usr/bin/env python3

import os
import sys

from tools import impl

EVENT_TYPE_MSG = '{"eventType": "LOG_EVENT"}'
EVENT_MSG_1 = '{"log_level": "WARN", "message": "Hello from fbcc", "logger_name": "FBCC_MAKE_LOG"}'
EVENT_MSG_2 = '{"log_level": "WARN", "message": "Hello again from fbcc", "logger_name": "FBCC_MAKE_LOG"}'

if "BUCK_EVENT_PIPE" in os.environ:
    event_path = os.path.abspath(os.environ["BUCK_EVENT_PIPE"])
    end_of_line = os.linesep
    with open(event_path, "w") as event_pipe:
        # establish communication protocol
        event_pipe.write("j")
        event_pipe.write(end_of_line)
        event_pipe.flush()

        # send the first event
        event_pipe.write(str(len(EVENT_TYPE_MSG)))
        event_pipe.write(end_of_line)
        event_pipe.write(EVENT_TYPE_MSG)
        event_pipe.write(str(len(EVENT_MSG_1)))
        event_pipe.write(end_of_line)
        event_pipe.write(EVENT_MSG_1)
        event_pipe.flush()

        # send the second event
        event_pipe.write(str(len(EVENT_TYPE_MSG)))
        event_pipe.write(end_of_line)
        event_pipe.write(EVENT_TYPE_MSG)
        event_pipe.write(str(len(EVENT_MSG_2)))
        event_pipe.write(end_of_line)
        event_pipe.write(EVENT_MSG_2)
        event_pipe.flush()

parser = impl.argparser()
# We just need to know the input file, the output location and the depfile location
parser.add_argument("-o", dest="output", action=impl.StripQuotesAction)
parser.add_argument("-MF", dest="depfile", action=impl.StripQuotesAction)
parser.add_argument("-test-arg", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args()

impl.log(options)
impl.log(args)

# input file appears last in the command
input = args[-1]

# We configured the toolchain to use .object as object file extension
assert options.output.endswith(".object")

with open(input) as inputfile:
    data = inputfile.read()

with open(options.output, "w") as out:
    out.write("compile output: " + data)
    if options.test_arg:
        with open(options.test_arg) as inputfile:
            out.write("\ntest arg: " + inputfile.read())

with open(options.depfile, "w") as depfile:
    depfile.write(options.output + " :\\\n    " + input + "\n")

sys.exit(0)
