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

package com.facebook.buck.features.zip.rules;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.util.zip.collect.OnDuplicateEntry;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class ZipFileDescription
    implements DescriptionWithTargetGraph<ZipFileDescriptionArg>,
        VersionPropagator<ZipFileDescriptionArg> {

  @Override
  public Class<ZipFileDescriptionArg> getConstructorArgType() {
    return ZipFileDescriptionArg.class;
  }

  @Override
  public Zip createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ZipFileDescriptionArg args) {

    ImmutableList<SourcePath> zipSources = args.getZipSrcs();

    return new Zip(
        context.getActionGraphBuilder(),
        buildTarget,
        context.getProjectFilesystem(),
        args.getOut(),
        args.getSrcs(),
        zipSources,
        args.getEntriesToExclude(),
        args.getOnDuplicateEntry());
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @RuleArg
  interface AbstractZipFileDescriptionArg extends BuildRuleArg {
    @Value.Default
    default String getOut() {
      return getName() + ".zip";
    }

    ImmutableSet<SourcePath> getSrcs();

    ImmutableSet<Pattern> getEntriesToExclude();

    ImmutableList<SourcePath> getZipSrcs();

    @Value.Default
    default OnDuplicateEntry getOnDuplicateEntry() {
      return OnDuplicateEntry.OVERWRITE;
    }
  }
}
