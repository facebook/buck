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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.parser.api.PackageFileManifest;
import com.facebook.buck.parser.api.PackageFileParser;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.vfs.FileSystem;
import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/** Parser for package files written using Skylark syntax. */
public class SkylarkPackageFileParser extends AbstractSkylarkFileParser<PackageFileManifest>
    implements PackageFileParser {

  private static final Logger LOG = Logger.get(SkylarkPackageFileParser.class);

  private final BuckEventBus buckEventBus;

  private SkylarkPackageFileParser(
      ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      BuckGlobals buckGlobals,
      EventHandler eventHandler) {
    super(options, fileSystem, buckGlobals, eventHandler);
    Preconditions.checkArgument(
        options.getDescriptions().isEmpty(), "Packages do not support build rules.");
    this.buckEventBus = buckEventBus;
  }

  /** Create an instance of Skylark package file parser using provided options. */
  public static SkylarkPackageFileParser using(
      ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      BuckGlobals buckGlobals,
      EventHandler eventHandler) {
    return new SkylarkPackageFileParser(
        options, buckEventBus, fileSystem, buckGlobals, eventHandler);
  }

  @Override
  FileKind getFileKind() {
    return FileKind.BUCK;
  }

  @Override
  Globber getGlobber(java.nio.file.Path parseFile) {
    return new UnsupportedGlobber();
  }

  @Override
  ParseResult getParseResult(
      java.nio.file.Path parseFile,
      ParseContext context,
      Globber globber,
      ImmutableList<String> loadedPaths) {
    PackageMetadata pkg = context.getPackage();
    if (LOG.isVerboseEnabled()) {
      LOG.verbose("Got package: %s", pkg);
    }
    return ParseResult.of(
        pkg,
        ImmutableMap.of(),
        loadedPaths,
        context.getAccessedConfigurationOptions(),
        ImmutableList.of());
  }

  @Override
  @SuppressWarnings("unchecked")
  public PackageFileManifest getManifest(java.nio.file.Path packageFile)
      throws BuildFileParseException, InterruptedException, IOException {
    LOG.verbose("Started parsing package file file %s", packageFile);
    ParseBuckFileEvent.Started startEvent =
        ParseBuckFileEvent.started(
            packageFile, ParseBuckFileEvent.ParserKind.SKYLARK, this.getClass());
    buckEventBus.post(startEvent);
    try {
      ParseResult parseResult = parse(packageFile);

      return PackageFileManifest.of(
          parseResult.getPackage(),
          ImmutableSortedSet.copyOf(parseResult.getLoadedPaths()),
          (ImmutableMap<String, Object>)
              (ImmutableMap<String, ? extends Object>) parseResult.getReadConfigurationOptions(),
          Optional.empty(),
          ImmutableList.of());
    } finally {
      LOG.verbose("Finished parsing package file %s", packageFile);
      buckEventBus.post(ParseBuckFileEvent.finished(startEvent, 0, 0L, Optional.empty()));
    }
  }

  private class UnsupportedGlobber implements Globber {

    @Override
    public Set<String> run(
        Collection<String> include, Collection<String> exclude, boolean excludeDirectories)
        throws IOException, InterruptedException {
      throw new HumanReadableException("glob not supported in package files.");
    }
  }
}
