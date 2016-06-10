[
{
  "bash" : null,
  "buck.base_path" : "",
  "buck.direct_dependencies" : [ "//:A", "//:test-library" ],
  "buck.type" : "genrule",
  "cmd" : "$(classpath :test-library)",
  "cmdExe" : null,
  "executable" : null,
  "fully_qualified_name": "//:B",
  "licenses" : [ ],
  "name" : "B",
  "out" : "B.txt",
  "srcs" : [":A"],
  "tests" : [ ],
  "visibility" : [ ]
}
]
