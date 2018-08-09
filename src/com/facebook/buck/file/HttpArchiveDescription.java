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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;
import org.immutables.value.Value;

/**
 * A description for downloading an archive over http and extracting it (versus the combo logic
 * contained in {@link RemoteFileDescription}.
 */
public class HttpArchiveDescription
    implements DescriptionWithTargetGraph<HttpArchiveDescriptionArg> {

  private static final Flavor ARCHIVE_DOWNLOAD = InternalFlavor.of("archive-download");

  private final Supplier<Downloader> downloaderSupplier;

  public HttpArchiveDescription(ToolchainProvider toolchainProvider) {
    this.downloaderSupplier =
        () -> toolchainProvider.getByName(Downloader.DEFAULT_NAME, Downloader.class);
  }

  public HttpArchiveDescription(Downloader downloader) {
    this.downloaderSupplier = () -> downloader;
  }

  @Override
  public Class<HttpArchiveDescriptionArg> getConstructorArgType() {
    return HttpArchiveDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      HttpArchiveDescriptionArg args) {

    HashCode sha256 =
        HttpCommonDescriptionArg.HttpCommonDescriptionArgHelpers.parseSha256(
            args.getSha256(), buildTarget);
    HttpCommonDescriptionArg.HttpCommonDescriptionArgHelpers.validateUris(
        args.getUrls(), buildTarget);

    String out = args.getOut().orElse(buildTarget.getShortNameAndFlavorPostfix());
    ArchiveFormat format =
        args.getType()
            .map(t -> getTypeFromType(t, buildTarget))
            .orElseGet(() -> getTypeFromFilename(args.getUrls(), buildTarget));

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    // Setup the implicit download rule
    BuildTarget httpFileTarget = buildTarget.withAppendedFlavors(ARCHIVE_DOWNLOAD);
    HttpFile httpFile =
        new HttpFile(
            httpFileTarget,
            projectFilesystem,
            params,
            downloaderSupplier.get(),
            args.getUrls(),
            sha256,
            out,
            false);

    BuildRuleParams httpArchiveParams =
        new BuildRuleParams(
            () -> ImmutableSortedSet.of(httpFile), ImmutableSortedSet::of, ImmutableSortedSet.of());

    context.getActionGraphBuilder().computeIfAbsent(httpFileTarget, ignored -> httpFile);

    return new HttpArchive(
        buildTarget,
        projectFilesystem,
        httpArchiveParams,
        httpFile,
        out,
        format,
        args.getStripPrefix().map(Paths::get));
  }

  private ArchiveFormat getTypeFromType(String type, BuildTarget buildTarget) {
    return ArchiveFormat.getFormatFromShortName(type)
        .orElseThrow(
            () ->
                new HumanReadableException(
                    "%s is not a valid type of archive for %s. type must be one of %s",
                    type,
                    buildTarget.getUnflavoredBuildTarget().getFullyQualifiedName(),
                    Joiner.on(", ").join(ArchiveFormat.getShortNames())));
  }

  private ArchiveFormat getTypeFromFilename(ImmutableList<URI> uris, BuildTarget buildTarget) {
    for (URI uri : uris) {
      Optional<ArchiveFormat> format = ArchiveFormat.getFormatFromFilename(uri.getPath());
      if (format.isPresent()) {
        return format.get();
      }
    }
    throw new HumanReadableException(
        "Could not determine file type from urls of %s. One url must end with one of %s, or type "
            + "must be set to one of %s",
        buildTarget.getUnflavoredBuildTarget().getFullyQualifiedName(),
        Joiner.on(", ").join(ArchiveFormat.getFileExtensions()),
        Joiner.on(", ").join(ArchiveFormat.getShortNames()));
  }

  /** Arguments for an http_archive rule */
  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractHttpArchiveDescriptionArg extends HttpCommonDescriptionArg {

    Optional<String> getStripPrefix();

    Optional<String> getType();
  }
}
