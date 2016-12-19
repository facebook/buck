#include "gtest.h"

#include <fstream>
#include <iostream>
#include <string>

int main(int argc, char* argv[]) {
  const std::string output_arg = "--gtest_output=xml:";
  std::string filename = "buck-out/gen/test/__test_cxxtest_output__/results";

  for (int i = 1; i < argc; i++) {
    std::string arg(argv[i]);
    if (arg.compare(0, output_arg.length(), output_arg) == 0) {
      filename = arg.substr(output_arg.length());
    }
  }

  std::ofstream output;
  output.open(filename);
  output << R"(<?xml version="1.0" encoding="UTF-8"?>
<testsuites tests="1" failures="0" disabled="0" errors="0" timestamp="2016-12-14T00:00:00" time="0" name="AllTests">
  <testsuite name="DummyTests/0" tests="1" failures="0" disabled="0" errors="0" time="0">
    <testcase name="DummyTest_0" type_param="short" status="run" time="0" classname="DummyTests/0" />
  </testsuite>
</testsuites>
)";
  output.close();

  return 0;
}
