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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.AbstractBuildRuleFactory;
import com.facebook.buck.parser.BuildRuleFactoryParams;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.util.HumanReadableException;

public final class ApkGenruleBuildRuleFactory extends AbstractBuildRuleFactory {

  @Override
  public ApkGenrule.Builder newBuilder() {
    return ApkGenrule.newApkGenruleBuilder();
  }

  @Override
  protected void amendBuilder(AbstractBuildRuleBuilder abstractBuilder,
                              BuildRuleFactoryParams params) {
    ApkGenrule.Builder builder = ((ApkGenrule.Builder)abstractBuilder);

    String cmd = params.getRequiredStringAttribute("cmd");
    builder.setCmd(cmd);

    String apk = params.getRequiredStringAttribute("apk");
    ParseContext buildFileParseContext = ParseContext.forBaseName(params.target.getBaseName());
    try {
      BuildTarget buildTarget = params.buildTargetParser.parse(apk, buildFileParseContext);
      builder.setApk(buildTarget.getFullyQualifiedName());
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(String.format(
            "Couldn't resolve APK dependency %s for rule %s",
            apk,
            params.target.getFullyQualifiedName()
          )
      );
    }
  }
}
