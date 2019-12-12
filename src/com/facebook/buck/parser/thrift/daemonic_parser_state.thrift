/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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