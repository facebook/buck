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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AndroidInstrumentationApk;

public class AndroidInstrumentationApkRuleFactory extends AbstractBuildRuleFactory {

  @Override
  public AndroidInstrumentationApk.Builder newBuilder() {
    return AndroidInstrumentationApk.newAndroidInstrumentationApkRuleBuilder();
  }

  @Override
  protected void amendBuilder(AbstractBuildRuleBuilder abstractBuilder,
      BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    AndroidInstrumentationApk.Builder builder = (AndroidInstrumentationApk.Builder)abstractBuilder;
    BuildTarget target = params.target;

    // manifest
    String manifestAttribute = params.getRequiredStringAttribute("manifest");
    String manifestPath = params.resolveFilePathRelativeToBuildFileDirectory(manifestAttribute);
    builder.setManifest(manifestPath);

    // apk
    String apk = params.getRequiredStringAttribute("apk");
    ParseContext buildFileParseContext = ParseContext.forBaseName(target.getBaseName());
    BuildTarget buildTarget = params.buildTargetParser.parse(apk, buildFileParseContext);
    builder.setApk(buildTarget.getFullyQualifiedName());
  }
}
