import sys


def hello():
    pass


# If this module is installed as `__main__`, we should be able to look it up
# via `sys.module['__main__']`.
assert "hello" in sys.modules["__main__"].__dict__
