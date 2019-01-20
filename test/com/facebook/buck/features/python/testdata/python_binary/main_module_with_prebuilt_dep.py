from lib.foo.bar import bar
from lib.foobar.baz import baz
from wheel_package import my_wheel


def main():
    my_wheel.f()
    bar()
    baz()


if __name__ == "__main__":
    main()
