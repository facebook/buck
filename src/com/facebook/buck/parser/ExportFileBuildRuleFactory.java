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

package com.facebook.buck.parser;

import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.ExportFileRule;
import com.google.common.base.Optional;

public class ExportFileBuildRuleFactory extends AbstractBuildRuleFactory {
  @Override
  public ExportFileRule.Builder newBuilder() {
    return ExportFileRule.newExportFileBuilder();
  }

  @Override
  protected void amendBuilder(AbstractBuildRuleBuilder abstractBuilder, BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException {
    ExportFileRule.Builder builder = (ExportFileRule.Builder) abstractBuilder;

    Optional<String> src = params.getOptionalStringAttribute("src");

    if (src.isPresent()) {
      String srcPath = params.resolveFilePathRelativeToBuildFileDirectory(src.get());
      builder.setSrc(Optional.of(srcPath));
    } else {
      builder.setSrc(src);
    }

    Optional<String> out = params.getOptionalStringAttribute("out");
    builder.setOut(out);
  }
}
