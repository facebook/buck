/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.python;

import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;

import java.nio.file.Path;

public class PythonEnvironment implements RuleKeyAppendable {
  private final Path pythonPath;
  private final PythonVersion pythonVersion;

  public PythonEnvironment(Path pythonPath, PythonVersion pythonVersion) {
    this.pythonPath = pythonPath;
    this.pythonVersion = pythonVersion;
  }

  public Path getPythonPath() {
    return pythonPath;
  }

  public PythonVersion getPythonVersion() {
    return pythonVersion;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder, String key) {
    return builder.setReflectively(key + ".python-version", getPythonVersion().getVersionString());
  }
}
