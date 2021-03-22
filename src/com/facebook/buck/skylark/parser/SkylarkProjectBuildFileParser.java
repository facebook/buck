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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.label.Label;
import com.facebook.buck.core.starlark.eventhandler.EventHandler;
import com.facebook.buck.core.starlark.rule.names.UserDefinedRuleNames;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.api.UserDefinedRuleLoader;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.io.GlobberFactory;
import com.facebook.buck.skylark.io.impl.CachingGlobber;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.facebook.buck.skylark.parser.context.ReadConfigContext;
import com.facebook.buck.skylark.parser.context.RecordedRule;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.starlark.java.syntax.Location;

/**
 * Parser for build files written using Skylark syntax.
 *
 * <p>NOTE: This parser is still a work in progress and does not support some functions provided by
 * Python DSL parser like {@code include_defs}, so use in production at your own risk.
 */
public class SkylarkProjectBuildFileParser extends AbstractSkylarkFileParser<BuildFileManifest>
    implements ProjectBuildFileParser, UserDefinedRuleLoader {

  private static final Logger LOG = Logger.get(SkylarkProjectBuildFileParser.class);

  private final BuckEventBus buckEventBus;
  private final GlobberFactory globberFactory;

  SkylarkProjectBuildFileParser(
      ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      BuckGlobals buckGlobals,
      EventHandler eventHandler,
      GlobberFactory globberFactory) {
    super(options, buckGlobals, eventHandler);
    this.buckEventBus = buckEventBus;
    this.globberFactory = globberFactory;
  }

  /** Create an instance of Skylark project build file parser using provided options. */
  public static SkylarkProjectBuildFileParser using(
      ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      BuckGlobals buckGlobals,
      EventHandler eventHandler,
      GlobberFactory globberFactory) {
    return new SkylarkProjectBuildFileParser(
        options, buckEventBus, buckGlobals, eventHandler, globberFactory);
  }

  @VisibleForTesting
  protected SkylarkProjectBuildFileParser(SkylarkProjectBuildFileParser other) {
    this(
        other.options,
        other.buckEventBus,
        other.buckGlobals,
        other.eventHandler,
        other.globberFactory);
  }

  @Override
  BuckOrPackage getBuckOrPackage() {
    return BuckOrPackage.BUCK;
  }

  @Override
  CachingGlobber getGlobber(AbsPath parseFile) {
    return newGlobber(parseFile);
  }

  @Override
  ParseResult getParseResult(
      Path parseFile,
      ParseContext context,
      ReadConfigContext readConfigContext,
      Globber globber,
      ImmutableList<String> loadedPaths) {
    Preconditions.checkState(globber instanceof CachingGlobber);
    TwoArraysImmutableHashMap<String, RecordedRule> rules = context.getRecordedRules();
    if (LOG.isVerboseEnabled()) {
      LOG.verbose("Got rules: %s", rules.values());
    }
    LOG.verbose("Parsed %d rules from %s", rules.size(), parseFile);
    return ParseResult.of(
        PackageMetadata.EMPTY_SINGLETON,
        rules,
        loadedPaths,
        readConfigContext.getAccessedConfigurationOptions(),
        ((CachingGlobber) globber).createGlobManifest());
  }

  @Override
  @SuppressWarnings("unchecked")
  public BuildFileManifest getManifest(AbsPath buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    LOG.verbose("Started parsing build file %s", buildFile);
    ParseBuckFileEvent.Started startEvent =
        ParseBuckFileEvent.started(
            buildFile.getPath(), ParseBuckFileEvent.ParserKind.SKYLARK, this.getClass());
    buckEventBus.post(startEvent);
    int rulesParsed = 0;
    try {
      ParseResult parseResult = parse(buildFile);

      TwoArraysImmutableHashMap<String, RecordedRule> rawRules = parseResult.getRawRules();
      rulesParsed = rawRules.size();

      // By contract, BuildFileManifestPojoizer converts any Map to TwoArraysImmutableHashMap.
      TwoArraysImmutableHashMap<String, RawTargetNode> targets =
          rawRules.mapValues(
              (k, r) ->
                  // TODO(nga): avoid creating RecordedRule
                  RawTargetNode.of(
                      r.getBasePath(),
                      r.getBuckType(),
                      r.getVisibility(),
                      r.getWithinView(),
                      r.getRawRule()));

      rulesParsed = targets.size();

      return BuildFileManifest.of(
          targets,
          ImmutableSortedSet.copyOf(parseResult.getLoadedPaths()),
          (ImmutableMap<String, Object>)
              (ImmutableMap<String, ? extends Object>) parseResult.getReadConfigurationOptions(),
          Optional.empty(),
          parseResult.getGlobManifestWithResult(),
          ImmutableList.of());
    } finally {
      LOG.verbose("Finished parsing build file %s", buildFile);
      buckEventBus.post(ParseBuckFileEvent.finished(startEvent, rulesParsed, 0L, Optional.empty()));
    }
  }

  /** Creates a globber for the package defined by the provided build file path. */
  private CachingGlobber newGlobber(AbsPath buildFile) {
    return CachingGlobber.of(globberFactory.create(buildFile.getParent()));
  }

  @Override
  public void reportProfile() {
    // this method is a noop since Skylark profiling is completely orthogonal to parsing and is
    // controlled by com.google.devtools.build.lib.profiler.Profiler
  }

  @Override
  public boolean globResultsMatchCurrentState(
      AbsPath buildFile, ImmutableList<GlobSpecWithResult> existingGlobsWithResults)
      throws IOException, InterruptedException {
    CachingGlobber globber = newGlobber(buildFile);
    for (GlobSpecWithResult globSpecWithResult : existingGlobsWithResults) {
      final GlobSpec globSpec = globSpecWithResult.getGlobSpec();
      Set<String> globResult =
          globber.run(
              globSpec.getInclude(), globSpec.getExclude(), globSpec.getExcludeDirectories());
      if (!globSpecWithResult.getFilePaths().equals(globResult)) {
        return false;
      }
    }

    return true;
  }

  @Override
  public void loadExtensionsForUserDefinedRules(AbsPath buildFile, BuildFileManifest manifest) {
    Label containingLabel = createContainingLabel(getBasePath(buildFile));
    ImmutableList<LoadImport> extensionsToLoad =
        manifest.getTargets().values().stream()
            .flatMap(
                props -> {
                  String importS = UserDefinedRuleNames.importFromIdentifier(props.getBuckType());
                  return importS != null
                      ? Stream.of(
                          ImmutableLoadImport.ofImpl(
                              containingLabel, importS, Location.fromFile(buildFile.toString())))
                      : Stream.empty();
                })
            .distinct()
            .collect(ImmutableList.toImmutableList());

    if (extensionsToLoad.isEmpty()) {
      return;
    }

    try {
      loadExtensions(containingLabel, extensionsToLoad, LoadStack.EMPTY);
    } catch (IOException | InterruptedException e) {
      throw BuildFileParseException.createForUnknownParseError("Could not parse %s", buildFile);
    }
  }

  @Override
  public void close() {
    try {
      globberFactory.close();
    } catch (Exception e) {
      LOG.warn(e, "failed to close globber factory");
    }
  }
}
