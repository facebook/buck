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

package com.facebook.buck.file;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.BuildTargetSourcePathToArtifactConverter;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.LegacyProviderCompatibleDescription;
import com.facebook.buck.core.rules.ProviderCreationContext;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableRunInfo;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.util.Optional;

/**
 * A description for downloading a single HttpFile (versus the combo logic contained in {@link
 * RemoteFileDescription}.
 */
public class HttpFileDescription
    implements DescriptionWithTargetGraph<HttpFileDescriptionArg>,
        LegacyProviderCompatibleDescription<HttpFileDescriptionArg> {
  @Override
  public Class<HttpFileDescriptionArg> getConstructorArgType() {
    return HttpFileDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      HttpFileDescriptionArg args) {

    HashCode sha256 =
        HttpCommonDescriptionArg.HttpCommonDescriptionArgHelpers.parseSha256(
            args.getSha256(), buildTarget);
    HttpCommonDescriptionArg.HttpCommonDescriptionArgHelpers.validateUris(
        args.getUrls(), buildTarget);

    boolean executable = getExecutable(args);
    Downloader downloader =
        context
            .getToolchainProvider()
            .getByName(
                Downloader.DEFAULT_NAME, buildTarget.getTargetConfiguration(), Downloader.class);
    // TODO(pjameson): Pull `out` from the providers once we've defaulted to compatible/RAG mode
    String output = outputName(buildTarget, args);
    if (executable) {
      return new HttpFileBinary(
          buildTarget,
          context.getProjectFilesystem(),
          params,
          downloader,
          args.getUrls(),
          sha256,
          output);
    }
    return new HttpFile(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        downloader,
        args.getUrls(),
        sha256,
        output,
        false);
  }

  private static boolean getExecutable(HttpFileDescriptionArg args) {
    return args.getExecutable().orElse(false);
  }

  private static String outputName(BuildTarget target, HttpFileDescriptionArg args) {
    return args.getOut().orElse(target.getShortNameAndFlavorPostfix());
  }

  private static SourcePath outputSourcePath(
      ProjectFilesystem filesystem, BuildTarget target, HttpFileDescriptionArg args) {
    String outFilename = outputName(target, args);
    return ExplicitBuildTargetSourcePath.of(
        target, HttpFile.outputPath(filesystem, target, outFilename));
  }

  @Override
  public ProviderInfoCollection createProviders(
      ProviderCreationContext context, BuildTarget buildTarget, HttpFileDescriptionArg args) {
    ProviderInfoCollectionImpl.Builder builder = ProviderInfoCollectionImpl.builder();
    Artifact output =
        BuildTargetSourcePathToArtifactConverter.convert(
            context.getProjectFilesystem(),
            outputSourcePath(context.getProjectFilesystem(), buildTarget, args));
    if (getExecutable(args)) {
      builder.put(
          new ImmutableRunInfo(
              ImmutableMap.of(), CommandLineArgsFactory.from(ImmutableList.of(output))));
    }
    return builder.build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of(output)));
  }

  /** Args required for http_rule */
  @RuleArg
  interface AbstractHttpFileDescriptionArg extends HttpCommonDescriptionArg {
    Optional<Boolean> getExecutable();
  }
}
