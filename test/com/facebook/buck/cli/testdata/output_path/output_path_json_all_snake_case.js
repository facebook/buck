[
{
  "buck.base_path":"",
  "buck.direct_dependencies":[],
  "buck.output_path":"buck-out/gen/another-test/test-output",
  "buck.rule_type":"genrule",
  "buck.type":"genrule",
  "fully_qualified_name": "//:another-test",
  "name":"another-test",
  "out":"test-output"
},
{
  "buck.base_path":"",
  "buck.direct_dependencies":["//:plugin"],
  "buck.generated_source_path":"buck-out/annotation/__java_lib_gen__",
  "buck.output_path":"buck-out/gen/lib__java_lib__output/java_lib.jar",
  "buck.rule_type":"default_java_library",
  "buck.type":"java_library",
  "fully_qualified_name": "//:java_lib",
  "name":"java_lib",
  "plugins":[":plugin"],
  "srcs":["A.java"]
},
{
  "buck.base_path":"",
  "buck.direct_dependencies":[],
  "buck.rule_type":"java_annotation_processor",
  "buck.type":"java_annotation_processor",
  "fully_qualified_name": "//:plugin",
  "name":"plugin",
  "processor_class":"com.example.Plugin"
},
{
  "buck.base_path":"",
  "buck.direct_dependencies":[],
  "buck.output_path":"buck-out/gen/test/test-output",
  "buck.rule_type":"genrule",
  "buck.type":"genrule",
  "fully_qualified_name": "//:test",
  "name":"test",
  "out":"test-output"
}
]
