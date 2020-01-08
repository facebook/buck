"""
A rule that takes in a series of "foods" and srcs then produces text files that declares those foods are yummy.

`foods` is a list of arbitrary strings. An output label will be created for each of these items.
`srcs` is a list of targets (in this case we assume an output label exists). An output label is created for each of these srcs that matches that src's output label, and the output is copied from the src rule.

e.g. for `delicious(name = "fuud", foods=["saba"], srcs = [":other[toro]"])`, `//:fuud` would have the output labels `saba` and `toro`.

The output can be referred to in the target graph or built in the command line. For example, for a food named "sushi":
  buck build //:target_name[sushi]

The String in the square brackets following the target name is referred to as an "output label."
  In the example above, the output label is "sushi."

The set of outputs provided by a build target with output label is referred to as a "named output group."
  In the example above, the outputs produced from building //:target_name[sushi] would be a named output group.

If no output label is specified, the outputs are referred to as the default output group.
  In the example above, the outputs produced from building //:target_name would be the default output group.

To refer to a rule's named outputs, a mapping from an output's name to its outputs must be defined.

For genrule, this mapping is defined in its 'outs' arg. The genrule contract enforces that any outputs declared in 'outs' must be created after executing the genrule. Genrule's default output group is currently an empty output group, but it will eventually be the set of all named outputs. Each rule has its own default output group, so the default group for other rules may not be the set of empty outputs or the set of all named outputs.
"""

def delicious(name, foods = [], srcs = {}):
    if len(foods) == 0 and len(srcs) == 0:
        fail("One of foods or srcs must be provided")
    outputs = {}
    bash = []
    cmd_exe = []
    for food in foods:
        file_name = food + ".txt"
        outputs[food] = [file_name]
        bash.append("echo \'{} is yummy\' > $OUT/{}".format(food, file_name))
        cmd_exe.append("(echo {} is yummy)> $OUT\\{}".format(food, file_name))
    for src in srcs:
        # Parse the output label out of the src, then put the output label into the outputs map
        # where the output file is the output label with ".txt" appended
        #   e.g. "//:source_target[group]" parses into "group", so we put
        #    { "group": ["group.txt"] }
        #   into the outputs map
        output_label_to_use = parse_output_label(src)

        file_name = output_label_to_use + ".txt"
        outputs[output_label_to_use] = [file_name]
        bash.append("cp -R $(location {}) $OUT/{}".format(src, file_name))
        cmd_exe.append("cp -R $(location {}) $OUT\\{}".format(src, file_name))
    genrule(
        name = name,
        srcs = srcs,
        outs = outputs,
        bash = " && ".join(bash),
        cmd_exe = " && ".join(cmd_exe),
    )

# Returns the output label from a target given its target name and output label.
#   E.g. "//:source_target[group]" -> "group"
def parse_output_label(src):
    # Take the part of the src after the opening square bracket
    output_label_to_use = src.split("[", 2)[1]

    # Now take the part before the closing square bracket
    return output_label_to_use.split("]", 2)[0]
