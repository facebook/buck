import signal
import sys
import time


def signal_handler(signal, frame):
    with open("signal_cancelled_runner.txt", "w+") as f:
        f.write("interrupted")
    sys.exit(0)


def run_until_int():
    start_time = time.time()
    signal.signal(signal.SIGINT, signal_handler)
    with open("started_neverending_test_runner.txt", "w+") as f:
        f.write("started")
    while time.time() - start_time < 120:
        time.sleep(1)


if __name__ == "__main__":
    run_until_int()
