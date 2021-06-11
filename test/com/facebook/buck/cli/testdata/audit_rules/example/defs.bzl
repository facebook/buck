def my_genrule(
        name,
        out,
        visibility = []):
    native.genrule(
        name = name,
        srcs = [
            "foo.txt",
            "bar.txt",
            "//fake:rule",
        ],
        bash = "cat $SRCS > $OUT",
        out = out,
        visibility = visibility,
    )
