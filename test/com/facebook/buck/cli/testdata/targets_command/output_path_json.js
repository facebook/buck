[
  {
    "buck.base_path": "",
    "buck.direct_dependencies": [],
    "buck.type": "genrule",
    "fully_qualified_name": "//:A",
    "name": "A",
    "out": "A.txt"
  },
  {
    "buck.base_path": "",
    "buck.direct_dependencies": [
      "//:A",
      "//:test-library"
    ],
    "buck.type": "genrule",
    "cmd": "$(classpath :test-library)",
    "fully_qualified_name": "//:B",
    "name": "B",
    "out": "B.txt",
    "srcs": [
      ":A"
    ]
  },
  {
    "buck.base_path": "",
    "buck.direct_dependencies": [],
    "buck.type": "config_setting",
    "fully_qualified_name": "//:C",
    "name": "C",
    "values": {
      "a.b": "c"
    }
  },
  {
    "buck.base_path": "",
    "buck.direct_dependencies": [],
    "buck.type": "java_library",
    "deps": [],
    "fully_qualified_name": "//:test-library",
    "name": "test-library",
    "source": "6",
    "srcs": [],
    "target": "6",
    "visibility": [
      "PUBLIC"
    ]
  }
]