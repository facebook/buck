# Copyright 2017 Facebook. All Rights Reserved.
#
#!/usr/local/bin/thrift -cpp -py -java
#
# Whenever you change this file please run the following command to refresh the java source code:
# $ thrift --gen java  -out src-gen/ src/com/facebook/buck/parser/thrift/daemonic_parser_state.thrift

namespace java com.facebook.buck.parser.thrift

struct RemoteDaemonicParserState {
  1: optional map<string, list<string>> cachedIncludes;
  2: optional map<string, RemoteDaemonicCellState> cellPathToDaemonicState;
  3: optional list<string> cellPaths;
}

struct RemoteDaemonicCellState {
  1: optional map<string, string> allRawNodesJsons;
  2: optional map<string, list<string>> buildFileDependents;
  3: optional map<string, map<string, BuildFileEnvProperty>> buildFileEnv;
}

struct BuildFileEnvProperty {
  1: optional string value;
}