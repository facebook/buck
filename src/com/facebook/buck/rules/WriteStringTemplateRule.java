/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.google.common.base.Function;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.stringtemplate.v4.ST;

import java.nio.file.Path;
import java.util.Map;

public class WriteStringTemplateRule extends AbstractBuildRule {

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey
  private final SourcePath template;

  @AddToRuleKey
  private final ImmutableMap<String, String> values;

  public WriteStringTemplateRule(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Path output,
      SourcePath template,
      ImmutableMap<String, String> values) {
    super(buildRuleParams, resolver);
    this.output = output;
    this.template = template;
    this.values = values;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        new StringTemplateStep(
            getResolver().getAbsolutePath(template),
            getProjectFilesystem(),
            output,
            new Function<ST, ST>() {
              @Override
              public ST apply(ST st) {
                for (Map.Entry<String, String> ent : values.entrySet()) {
                  st = st.add(ent.getKey(), ent.getValue());
                }
                return st;
              }
            }));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  public static WriteStringTemplateRule from(
      BuildRuleParams baseParams,
      SourcePathResolver pathResolver,
      BuildTarget target,
      Path output,
      SourcePath template,
      ImmutableMap<String, String> values) {
    return new WriteStringTemplateRule(
        baseParams.copyWithChanges(
            target,
            Suppliers.ofInstance(
                ImmutableSortedSet.copyOf(pathResolver.filterBuildRuleInputs(template))),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        output,
        template,
        values);
  }

}
