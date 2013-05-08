[
{
  "buck.output_file" : "{$OUTPUT_FILE}",
  "buck_base_path" : "testdata/com/facebook/buck/cli/TargetsCommandTestBuckFile",
  "deps" : [ "//:dependency", "//third-party/guava:guava" ],
  "export_deps": false,
  "name" : "test-library",
  "proguard_config" : "proguard.cfg",
  "resources" : [ ],
  "srcs" : [ "src/foobar.java" ],
  "type" : "java_library",
  "visibility" : [ "PUBLIC" ],
  "source" : "6",
  "target" : "6"
}
]
