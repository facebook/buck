[
{
  "buck.base_path" : "",
  "buck.direct_dependencies" : [ "//:A", "//:test-library" ],
  "buck.type" : "genrule",
  "cmd" : "$(classpath :test-library)",
  "fully_qualified_name": "//:B",
  "name" : "B",
  "out" : "B.txt",
  "srcs" : [":A"]
}
]
