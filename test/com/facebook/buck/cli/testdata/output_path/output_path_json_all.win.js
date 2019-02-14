[
{
  "buck.base_path":"",
  "buck.direct_dependencies":[],
  "buck.outputPath":"buck-out\\gen\\another-test\\test-output",
  "buck.ruleType":"genrule",
  "buck.type":"genrule",
  "fully_qualified_name": "//:another-test",
  "name":"another-test",
  "out":"test-output"
},
{
  "buck.base_path":"",
  "buck.direct_dependencies":["//:plugin"],
  "buck.generatedSourcePath":"buck-out\\annotation\\__java_lib_gen__",
  "buck.outputPath":"buck-out\\gen\\lib__java_lib__output\\java_lib.jar",
  "buck.ruleType":"default_java_library",
  "buck.type":"java_library",
  "fully_qualified_name": "//:java_lib",
  "name":"java_lib",
  "plugins":[":plugin"],
  "srcs":["A.java"]
},
{
  "buck.base_path":"",
  "buck.direct_dependencies":[],
  "buck.ruleType":"java_annotation_processor",
  "buck.type":"java_annotation_processor",
  "fully_qualified_name": "//:plugin",
  "name":"plugin",
  "processorClass":"com.example.Plugin"
},
{
  "buck.base_path":"",
  "buck.direct_dependencies":[],
  "buck.outputPath":"buck-out\\gen\\test\\test-output",
  "buck.ruleType":"genrule",
  "buck.type":"genrule",
  "fully_qualified_name": "//:test",
  "name":"test",
  "out":"test-output"
}
]
