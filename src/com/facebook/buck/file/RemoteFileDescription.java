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

package com.facebook.buck.file;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.hash.HashCode;
import java.net.URI;
import java.util.Optional;
import java.util.function.Supplier;
import org.immutables.value.Value;

public class RemoteFileDescription implements DescriptionWithTargetGraph<RemoteFileDescriptionArg> {

  private final Supplier<Downloader> downloaderSupplier;

  public RemoteFileDescription(ToolchainProvider toolchainProvider) {
    this.downloaderSupplier =
        () -> toolchainProvider.getByName(Downloader.DEFAULT_NAME, Downloader.class);
  }

  public RemoteFileDescription(Downloader downloader) {
    this.downloaderSupplier = () -> downloader;
  }

  @Override
  public Class<RemoteFileDescriptionArg> getConstructorArgType() {
    return RemoteFileDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      RemoteFileDescriptionArg args) {
    HashCode sha1;
    try {
      sha1 = HashCode.fromString(args.getSha1());
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException(
          e,
          "%s when parsing sha1 of %s",
          e.getMessage(),
          buildTarget.getUnflavoredBuildTarget().getFullyQualifiedName());
    }

    String out = args.getOut().orElse(buildTarget.getShortNameAndFlavorPostfix());

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    RemoteFile.Type type = args.getType().orElse(RemoteFile.Type.DATA);
    if (type == RemoteFile.Type.EXECUTABLE) {
      return new RemoteFileBinary(
          buildTarget,
          projectFilesystem,
          params,
          downloaderSupplier.get(),
          args.getUrl(),
          sha1,
          out,
          type);
    }
    return new RemoteFile(
        buildTarget,
        projectFilesystem,
        params,
        downloaderSupplier.get(),
        args.getUrl(),
        sha1,
        out,
        type);
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractRemoteFileDescriptionArg extends CommonDescriptionArg {
    URI getUrl();

    String getSha1();

    Optional<String> getOut();

    Optional<RemoteFile.Type> getType();
  }
}
