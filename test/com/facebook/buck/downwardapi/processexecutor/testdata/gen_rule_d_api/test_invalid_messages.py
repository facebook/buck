import os
import time

EVENT_TYPE_MSG = '{"eventType": "LOG_EVENT"}'
EVENT_MSG_1 = '{"log_level": "WARN", "message": "Hello from python script!!!", "logger_name": "PYTHON_SCRIPT_LOG"}'

print("Hello from script with invalid messages!")

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
        event_pipe.write("_0_")
        event_pipe.write("_1_")
        event_pipe.write("_2_")
        event_pipe.write("_3_")
        event_pipe.write("_4_")


time.sleep(1)
print("Exiting from script!")
