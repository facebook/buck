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

package com.facebook.buck.java;

import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.util.Console;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class is responsible for finding Java source files and the buck rules that own them, given a
 * fully-qualified Java symbol like "com.example.foo.Bar". It does this by looking at expected
 * locations based on the package name of the symbol and the source roots listed in the project
 * config. This functionality is used to automatically generate dependency information.
 */
public class JavaSymbolFinder {

  private static ImmutableSet<String> javaRuleTypes = ImmutableSet.of(
      JavaLibraryDescription.TYPE.getName(),
      AndroidLibraryDescription.TYPE.getName(),
      JavaTestDescription.TYPE.getName());

  private final ProjectFilesystem projectFilesystem;
  private final SrcRootsFinder srcRootsFinder;
  private final JavacOptions javacOptions;
  private final ProjectBuildFileParserFactory projectBuildFileParserFactory;
  private final BuckConfig config;
  private final BuckEventBus buckEventBus;
  private final Console console;
  private final ImmutableMap<String, String> environment;

  public JavaSymbolFinder(
      ProjectFilesystem projectFilesystem,
      SrcRootsFinder srcRootsFinder,
      JavacOptions javacOptions,
      ProjectBuildFileParserFactory projectBuildFileParserFactory,
      BuckConfig config,
      BuckEventBus buckEventBus,
      Console console,
      ImmutableMap<String, String> environment) {
    this.projectFilesystem = projectFilesystem;
    this.srcRootsFinder = srcRootsFinder;
    this.javacOptions = javacOptions;
    this.projectBuildFileParserFactory = projectBuildFileParserFactory;
    this.config = config;
    this.buckEventBus = buckEventBus;
    this.console = console;
    this.environment = environment;
  }

  /**
   * Figure out the build targets that provide a set of Java symbols.
   * @param symbols The set of symbols (e.g. "com.example.foo.Bar") to find defining targets for.
   *                This is taken as a collection, rather than as an individual string, because
   *                instantiating a ProjectBuildFileParser is expensive (it spawns a Python
   *                subprocess), and we don't want to encourage the caller to do it more than once.
   * @return A multimap of symbols to the targets that define them, of the form:
   *         {"com.example.a.A": set("//com/example/a:a", "//com/another/a:a")}
   */
  public ImmutableSetMultimap<String, BuildTarget> findTargetsForSymbols(Set<String> symbols)
      throws InterruptedException {
    // TODO(jacko): Handle files that aren't included in any rule.

    // First find all the source roots in the current project.
    Collection<Path> srcRoots;
    try {
      srcRoots = srcRootsFinder.getAllSrcRootPaths(config.getSrcRoots());
    } catch (IOException e) {
      buckEventBus.post(ThrowableConsoleEvent.create(e, "Error while searching for source roots."));
      return ImmutableSetMultimap.of();
    }

    // Now collect all the code files that define our symbols.
    Multimap<String, Path> symbolsToSourceFiles = HashMultimap.create();
    for (String symbol : symbols) {
      symbolsToSourceFiles.putAll(symbol, getDefiningPaths(symbol, srcRoots));
    }

    // Now find all the targets that define all those code files. We do this in one pass because we
    // don't want to instantiate a new parser subprocess for every symbol.
    Set<Path> allSourceFilePaths = ImmutableSet.copyOf(symbolsToSourceFiles.values());
    Multimap<Path, BuildTarget> sourceFilesToTargets = getTargetsForSourceFiles(allSourceFilePaths);

    // Now build the map from from symbols to build targets.
    ImmutableSetMultimap.Builder<String, BuildTarget> symbolsToTargets =
        ImmutableSetMultimap.builder();
    for (String symbol : symbolsToSourceFiles.keySet()) {
      for (Path sourceFile : symbolsToSourceFiles.get(symbol)) {
        symbolsToTargets.putAll(symbol, sourceFilesToTargets.get(sourceFile));
      }
    }

    return symbolsToTargets.build();
  }

  /**
   * For all the possible BUCK files above each of the given source files, parse them to JSON to
   * find the targets that actually include these source files, and return a map of them. We do this
   * over a collection of source files, rather than a single file at a time, because instantiating
   * the BUCK file parser is expensive. (It spawns a Python subprocess.)
   */
  private ImmutableMultimap<Path, BuildTarget> getTargetsForSourceFiles(
      Collection<Path> sourceFilePaths) throws InterruptedException {
    Map<Path, List<Map<String, Object>>> parsedBuildFiles = Maps.newHashMap();
    ImmutableSetMultimap.Builder<Path, BuildTarget> sourceFileTargetsMultimap =
        ImmutableSetMultimap.builder();
    try (ProjectBuildFileParser parser = projectBuildFileParserFactory.createParser(
        console,
        environment,
        buckEventBus)) {
      for (Path sourceFile : sourceFilePaths) {
        for (Path buckFile : possibleBuckFilesForSourceFile(sourceFile)) {
          List<Map<String, Object>> rules;
          // Avoid parsing the same BUCK file twice.
          if (parsedBuildFiles.containsKey(buckFile)) {
            rules = parsedBuildFiles.get(buckFile);
          } else {
            rules = parser.getAll(buckFile);
            parsedBuildFiles.put(buckFile, rules);
          }

          for (Map<String, Object> ruleMap : rules) {
            String type = (String) ruleMap.get("type");
            if (javaRuleTypes.contains(type)) {
              @SuppressWarnings("unchecked")
              List<String> srcs = (List<String>) Preconditions.checkNotNull(ruleMap.get("srcs"));
              if (isSourceFilePathInSrcsList(sourceFile, srcs, buckFile.getParent())) {
                Path buckFileDir = buckFile.getParent();
                String baseName = "//" + (buckFileDir != null ? buckFileDir : "");
                String shortName = (String) ruleMap.get("name");
                sourceFileTargetsMultimap.put(
                    sourceFile,
                    BuildTarget.builder(baseName, shortName).build());
              }
            }
          }
        }
      }
    } catch (BuildFileParseException e) {
      buckEventBus.post(ThrowableConsoleEvent.create(e, "Error while searching for targets."));
    }
    return sourceFileTargetsMultimap.build();
  }

  /**
   * The "srcs" list of a rule is given relative to the path of the BUCK file. Resolve and normalize
   * these paths to see if a given source file (given relative to the project root) is among them.
   */
  private boolean isSourceFilePathInSrcsList(
      Path candidateFilePath,
      Collection<String> srcs,
      Path srcsDir) {
    Path normalizedCandidatePath = candidateFilePath.normalize();
    for (String src : srcs) {
      Path pathForSrc = Paths.get(src).normalize();
      Path projectRelativePathForSrc = (srcsDir != null ? srcsDir.resolve(pathForSrc) : pathForSrc);
      Path normalizedPathForSrc = projectRelativePathForSrc.normalize();
      if (normalizedCandidatePath.equals(normalizedPathForSrc)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Look at all the directories above a given source file, up to the project root, and return the
   * paths to any BUCK files that exist at those locations. These files are the only ones that could
   * define a rule that includes the given source file.
   */
  private ImmutableList<Path> possibleBuckFilesForSourceFile(Path sourceFilePath) {
    ImmutableList.Builder<Path> possibleBuckFiles = ImmutableList.builder();
    Path dir = sourceFilePath.getParent();
    ParserConfig parserConfig = new ParserConfig(config);

    // For a source file like foo/bar/example.java, add paths like foo/bar/BUCK and foo/BUCK.
    while (dir != null) {
      Path buckFile = dir.resolve(parserConfig.getBuildFileName());
      if (projectFilesystem.isFile(buckFile)) {
        possibleBuckFiles.add(buckFile);
      }
      dir = dir.getParent();
    }

    // Finally, add ./BUCK in the root directory.
    Path rootBuckFile = Paths.get(parserConfig.getBuildFileName());
    if (projectFilesystem.exists(rootBuckFile)) {
      possibleBuckFiles.add(rootBuckFile);
    }

    return possibleBuckFiles.build();
  }

  /**
   * Find all Java source files that define a given fully-qualified symbol (like "com.example.a.A").
   * To do this, open up all the Java files that could define it (see {@link #getCandidatePaths})
   * and parse them with our Eclipse-based {@link JavaFileParser}.
   */
  private ImmutableSortedSet<Path> getDefiningPaths(String symbol, Collection<Path> srcRoots) {
    ImmutableSortedSet.Builder<Path> definingPaths = ImmutableSortedSet.naturalOrder();
    // TODO(simons): This should use the same javac env as was used for compiling the code.
    JavaFileParser parser = JavaFileParser.createJavaFileParser(javacOptions);

    for (Path candidatePath : getCandidatePaths(symbol, srcRoots)) {
      try {
        String content = projectFilesystem.readFileIfItExists(
            projectFilesystem.getPathForRelativeExistingPath(candidatePath)).get();
        Set<String> symbols = parser.getExportedSymbolsFromString(content);
        if (symbols.contains(symbol)) {
          definingPaths.add(candidatePath);
        }
      } catch (IOException e) {
        buckEventBus.post(
            ThrowableConsoleEvent.create(e, "Error while searching for source files."));
      }
    }
    return definingPaths.build();
  }

  /**
   * Guessing file names from fully-qualified Java symbols is ambiguous, because we don't know ahead
   * of time exactly what part of the symbol is the package, and what part is class names or static
   * members. This returns the set of all possible Java files for a given symbol, given different
   * possibilities for the package name and resolving against all the available source roots.
   * Returns only those candidates that actually exist.
   */
  private ImmutableSortedSet<Path> getCandidatePaths(String symbol, Collection<Path> srcRoots) {
    ImmutableSortedSet.Builder<Path> candidatePaths = ImmutableSortedSet.naturalOrder();
    List<String> symbolParts = Lists.newArrayList(symbol.split("\\."));
    for (int symbolIndex = 0; symbolIndex < symbolParts.size(); symbolIndex++) {
      List<String> pathPartsList = symbolParts.subList(0, symbolIndex);
      String[] pathParts = pathPartsList.toArray(new String[pathPartsList.size()]);
      String candidateFileName = symbolParts.get(symbolIndex) + ".java";
      for (Path srcRoot : srcRoots) {
        Path candidatePath = srcRoot.resolve(Paths.get("", pathParts)).resolve(candidateFileName);
        if (projectFilesystem.exists(candidatePath)) {
          candidatePaths.add(candidatePath);
        }
      }
    }
    return candidatePaths.build();
  }
}
