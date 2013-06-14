/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.parser.AbstractBuildRuleFactory;
import com.facebook.buck.parser.BuildRuleFactoryParams;

import java.io.File;

public class GenAidlBuildRuleFactory extends AbstractBuildRuleFactory<GenAidlRule.Builder> {

  @Override
  public GenAidlRule.Builder newBuilder() {
    return GenAidlRule.newGenAidlRuleBuilder();
  }

  @Override
  protected void amendBuilder(GenAidlRule.Builder builder, BuildRuleFactoryParams params) {
    // aidl
    String aidlAttribute = params.getRequiredStringAttribute("aidl");
    String aidlPath = params.resolveFilePathRelativeToBuildFileDirectory(aidlAttribute);
    builder.setAidl(aidlPath);

    // import_path
    String importPath = params.getRequiredStringAttribute("import_path");
    // import_path is an anomaly: it is a path that is relative to the project root rather than
    // relative to the build file directory.
    File importPathFile = new File(importPath);
    if (!importPathFile.isDirectory()) {
      throw new RuntimeException("Directory does not exist: " + importPathFile.getAbsolutePath());
    }
    builder.setImportPath(importPath);
  }
}
