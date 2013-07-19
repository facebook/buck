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
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;

public final class ApkGenruleBuildRuleFactory extends AbstractBuildRuleFactory<ApkGenrule.Builder> {

  @Override
  public ApkGenrule.Builder newBuilder(AbstractBuildRuleBuilderParams params) {
    return ApkGenrule.newApkGenruleBuilder(params);
  }

  @Override
  protected void amendBuilder(ApkGenrule.Builder builder, BuildRuleFactoryParams params) {
    String cmd = params.getRequiredStringAttribute("cmd");
    builder.setCmd(cmd);

    BuildTarget apk = params.getRequiredBuildTarget("apk");
    builder.setApk(apk);
  }
}
