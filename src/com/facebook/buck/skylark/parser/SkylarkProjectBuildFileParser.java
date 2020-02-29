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

import com.facebook.buck.core.starlark.rule.names.UserDefinedRuleNames;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.BuildFileManifestPojoizer;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.api.UserDefinedRuleLoader;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.parser.syntax.SelectorValue;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.io.GlobberFactory;
import com.facebook.buck.skylark.io.impl.CachingGlobber;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.syntax.SkylarkImport;
import com.google.devtools.build.lib.vfs.FileSystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
      FileSystem fileSystem,
      BuckGlobals buckGlobals,
      EventHandler eventHandler,
      GlobberFactory globberFactory) {
    super(options, fileSystem, buckGlobals, eventHandler);
    this.buckEventBus = buckEventBus;
    this.globberFactory = globberFactory;
  }

  /** Create an instance of Skylark project build file parser using provided options. */
  public static SkylarkProjectBuildFileParser using(
      ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      BuckGlobals buckGlobals,
      EventHandler eventHandler,
      GlobberFactory globberFactory) {
    return new SkylarkProjectBuildFileParser(
        options, buckEventBus, fileSystem, buckGlobals, eventHandler, globberFactory);
  }

  @VisibleForTesting
  protected SkylarkProjectBuildFileParser(SkylarkProjectBuildFileParser other) {
    this(
        other.options,
        other.buckEventBus,
        other.fileSystem,
        other.buckGlobals,
        other.eventHandler,
        other.globberFactory);
  }

  @Override
  FileKind getFileKind() {
    return FileKind.BUCK;
  }

  @Override
  CachingGlobber getGlobber(Path parseFile) {
    return newGlobber(parseFile);
  }

  @Override
  ParseResult getParseResult(
      Path parseFile, ParseContext context, Globber globber, ImmutableList<String> loadedPaths) {
    Preconditions.checkState(globber instanceof CachingGlobber);
    ImmutableMap<String, ImmutableMap<String, Object>> rules = context.getRecordedRules();
    if (LOG.isVerboseEnabled()) {
      LOG.verbose("Got rules: %s", rules.values());
    }
    LOG.verbose("Parsed %d rules from %s", rules.size(), parseFile);
    return ParseResult.of(
        PackageMetadata.EMPTY_SINGLETON,
        rules,
        loadedPaths,
        context.getAccessedConfigurationOptions(),
        ((CachingGlobber) globber).createGlobManifest());
  }

  @Override
  @SuppressWarnings("unchecked")
  public BuildFileManifest getManifest(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    LOG.verbose("Started parsing build file %s", buildFile);
    ParseBuckFileEvent.Started startEvent =
        ParseBuckFileEvent.started(
            buildFile, ParseBuckFileEvent.ParserKind.SKYLARK, this.getClass());
    buckEventBus.post(startEvent);
    int rulesParsed = 0;
    try {
      ParseResult parseResult = parse(buildFile);

      ImmutableMap<String, Map<String, Object>> rawRules = parseResult.getRawRules();
      rulesParsed = rawRules.size();

      // By contract, BuildFileManifestPojoizer converts any Map to ImmutableMap.
      // ParseResult.getRawRules() returns ImmutableMap<String, Map<String, Object>>, so it is
      // a safe downcast here
      ImmutableMap<String, ImmutableMap<String, Object>> targets =
          (ImmutableMap<String, ImmutableMap<String, Object>>)
              getBuildFileManifestPojoizer().convertToPojo(rawRules);

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

  private static BuildFileManifestPojoizer getBuildFileManifestPojoizer() {
    // Convert Skylark-specific classes to Buck API POJO classes to decouple them from parser
    // implementation. BuildFileManifest should only have POJO classes.
    BuildFileManifestPojoizer pojoizer = BuildFileManifestPojoizer.of();
    pojoizer.addPojoTransformer(
        BuildFileManifestPojoizer.PojoTransformer.of(
            com.google.devtools.build.lib.syntax.SelectorList.class,
            obj -> {
              com.google.devtools.build.lib.syntax.SelectorList skylarkSelectorList =
                  (com.google.devtools.build.lib.syntax.SelectorList) obj;
              // recursively convert list elements
              @SuppressWarnings("unchecked")
              ImmutableList<Object> elements =
                  (ImmutableList<Object>) pojoizer.convertToPojo(skylarkSelectorList.getElements());
              return ListWithSelects.of(elements, skylarkSelectorList.getType());
            }));
    pojoizer.addPojoTransformer(
        BuildFileManifestPojoizer.PojoTransformer.of(
            com.google.devtools.build.lib.syntax.SelectorValue.class,
            obj -> {
              com.google.devtools.build.lib.syntax.SelectorValue skylarkSelectorValue =
                  (com.google.devtools.build.lib.syntax.SelectorValue) obj;
              // recursively convert dictionary elements
              @SuppressWarnings("unchecked")
              ImmutableMap<String, Object> dictionary =
                  (ImmutableMap<String, Object>)
                      pojoizer.convertToPojo(skylarkSelectorValue.getDictionary());
              return SelectorValue.of(dictionary, skylarkSelectorValue.getNoMatchError());
            }));
    pojoizer.addPojoTransformer(
        BuildFileManifestPojoizer.PojoTransformer.of(
            com.google.devtools.build.lib.syntax.SkylarkNestedSet.class,
            obj -> {
              com.google.devtools.build.lib.syntax.SkylarkNestedSet skylarkNestedSet =
                  (com.google.devtools.build.lib.syntax.SkylarkNestedSet) obj;
              // recursively convert set elements
              return pojoizer.convertToPojo(skylarkNestedSet.toCollection());
            }));
    return pojoizer;
  }

  /** Creates a globber for the package defined by the provided build file path. */
  private CachingGlobber newGlobber(Path buildFile) {
    return CachingGlobber.of(
        globberFactory.create(fileSystem.getPath(buildFile.getParent().toString())));
  }

  @Override
  public void reportProfile() {
    // this method is a noop since Skylark profiling is completely orthogonal to parsing and is
    // controlled by com.google.devtools.build.lib.profiler.Profiler
  }

  @Override
  public boolean globResultsMatchCurrentState(
      Path buildFile, ImmutableList<GlobSpecWithResult> existingGlobsWithResults)
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
  public void loadExtensionsForUserDefinedRules(Path buildFile, BuildFileManifest manifest) {
    ImmutableList<SkylarkImport> extensionsToLoad =
        manifest.getTargets().values().stream()
            .map(
                props -> UserDefinedRuleNames.importFromIdentifier((String) props.get("buck.type")))
            .filter(Objects::nonNull)
            .distinct()
            .collect(ImmutableList.toImmutableList());

    if (extensionsToLoad.isEmpty()) {
      return;
    }

    try {
      Label containingLabel = createContainingLabel(buildFile.getParent().toString());
      loadExtensions(containingLabel, extensionsToLoad);
    } catch (IOException | InterruptedException e) {
      throw BuildFileParseException.createForUnknownParseError("Could not parse %s", buildFile);
    }
  }
}
