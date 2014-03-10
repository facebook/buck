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

package com.facebook.buck.shell;

import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildRuleFactory;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.google.common.base.Optional;

public final class GenruleBuildRuleFactory extends AbstractBuildRuleFactory<Genrule.Builder> {

  @Override
  public Genrule.Builder newBuilder(AbstractBuildRuleBuilderParams params) {
    return Genrule.newGenruleBuilder(params);
  }

  @Override
  protected void amendBuilder(Genrule.Builder builder, BuildRuleFactoryParams params) {
    // cmd
    Optional<String> cmd = params.getOptionalStringAttribute("cmd");
    builder.setCmd(cmd);

    // bash
    Optional<String> bash = params.getOptionalStringAttribute("bash");
    builder.setBash(bash);

    // cmd_exe
    Optional<String> cmdExe = params.getOptionalStringAttribute("cmd_exe");
    builder.setCmdExe(cmdExe);

    // out
    String out = params.getRequiredStringAttribute("out");
    builder.setOut(out);
  }
}
