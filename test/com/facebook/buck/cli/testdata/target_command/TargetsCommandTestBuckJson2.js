[
{
  "bash" : null,
  "buck.base_path" : "",
  "buck.direct_dependencies" : [ "//:A", "//:test-library" ],
  "buck.type" : "genrule",
  "cmd" : "$(classpath :test-library)",
  "cmdExe" : null,
  "name" : "B",
  "out" : "B.txt",
  "srcs" : [":A"],
  "visibility" : [ ]
}
]
