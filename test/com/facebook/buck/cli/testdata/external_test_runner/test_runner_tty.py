#!/usr/bin/env python3
import sys


print(f"stdout is a tty: {sys.stdout.isatty()}")
print(f"stdin is a tty: {sys.stdin.isatty()}")
