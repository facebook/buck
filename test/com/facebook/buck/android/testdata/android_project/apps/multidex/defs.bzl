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

def gen_src_with_refs(index, ref_count, type):
    if type == "method":
        refs = " ".join(["void fun%d() {};\n" % i for i in range(1, ref_count + 1)])
    elif type == "field":
        refs = " ".join(["int field%d = 1;\n" % i for i in range(1, ref_count + 1)])
    else:
        fail("unknown type")

    name = "generated_class_%d_%d_%s_refs" % (index, ref_count, type)
    native.genrule(
        name = name,
        out = "Class%d.java" % index,
        bash = "echo -e 'package gen;\npublic class Class%d {\n%s}' > $OUT" % (index, refs),
    )
    return ":%s" % name

def gen_overflow_lib(type):
    for i in range(1, 15):
        native.android_library(
            name = "generated_lib_%s_overflow_%d" % (type, i),
            srcs = [gen_src_with_refs(i, 5000, type)],
            visibility = ["PUBLIC"],
        )
    return [":" + "generated_lib_%s_overflow_%d" % (type, i) for i in range(1, 15)]

def gen_primary_dex_overflow(type, gen_deps):
    native.android_binary(
        name = "primary_dex_%s_overflow" % type,
        dex_group_lib_limit = 1,
        keystore = "//keystores:debug",
        manifest = "SimpleManifest.xml",
        primary_dex_patterns = [
            "^gen/Class",
        ],
        use_split_dex = True,
        deps = [
            "//java/com/sample/app:app",
            "//java/com/sample/lib:lib",
        ] + gen_deps,
    )

def gen_secondary_dex_overflow(type, gen_deps):
    native.android_binary(
        name = "secondary_dex_%s_overflow" % type,
        dex_group_lib_limit = 1,
        secondary_dex_weight_limit = 1024 * 1024 * 64,
        keystore = "//keystores:debug",
        manifest = "SimpleManifest.xml",
        primary_dex_patterns = [
            "/MyApplication^",
        ],
        use_split_dex = True,
        deps = [
            "//java/com/sample/app:app",
            "//java/com/sample/lib:lib",
        ] + gen_deps,
    )
