def gen_src(count):
    for i in range(1, count):
        data = "".join(["%d" % x for x in range(1, 10000)])
        native.genrule(
            name = "generated_class_%d" % i,
            out = "Class%d.java" % i,
            bash = "echo -e 'package gen;\npublic class Class%d { static String data = \"%s\"; }' > $OUT" % (i, data),
        )
        native.android_library(
            name = "generated_lib_%d" % i,
            srcs = [":generated_class_%d" % i],
        )

    return [":generated_lib_%d" % x for x in range(1, count)]
