import signal
import sys


def signal_handler(signal, frame):
    with open("signal_cancelled_runner.txt", "w+") as f:
        f.write("interrupted")
    sys.exit(0)


def run_until_int():
    signal.signal(signal.SIGINT, signal_handler)
    with open("started_neverending_test_runner.txt", "w+") as f:
        f.write("started")
    while True:
        pass


if __name__ == "__main__":
    run_until_int()
