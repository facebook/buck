/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.Tool;
import com.google.common.base.Preconditions;

import java.nio.file.Path;
import java.nio.file.Paths;

class CxxInferTools implements RuleKeyAppendable {

  final Tool topLevel;
  final Tool frontend;
  final Tool backend;
  final Tool reporter;
  final Tool compiler;
  final Tool plugin;

  final Path inferBin;

  CxxInferTools(InferBuckConfig config) {
    this.compiler = HashedFileTool.FROM_PATH.apply(
        Preconditions.checkNotNull(
            config.getPath("clang_compiler").orNull(),
            "clang_compiler path not found on the current configuration"));
    this.plugin = HashedFileTool.FROM_PATH.apply(
        Preconditions.checkNotNull(
            config.getPath("clang_plugin").orNull(),
            "clang_plugin path not found on the current configuration"));

    this.inferBin = Preconditions.checkNotNull(
        config.getPath("infer_bin").orNull(),
        "path to infer bin/ folder not found on the current configuration");

    this.topLevel = HashedFileTool.FROM_PATH.apply(Paths.get(inferBin.toString(), "infer"));

    this.frontend = HashedFileTool.FROM_PATH.apply(Paths.get(inferBin.toString(), "InferClang"));

    this.backend = HashedFileTool.FROM_PATH.apply(Paths.get(inferBin.toString(), "InferAnalyze"));

    this.reporter = HashedFileTool.FROM_PATH.apply(Paths.get(inferBin.toString(), "InferPrint"));
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("topLevel", topLevel)
        .setReflectively("frontend", frontend)
        .setReflectively("backend", backend)
        .setReflectively("reporter", reporter)
        .setReflectively("compiler", compiler)
        .setReflectively("plugin", plugin);
  }
}
