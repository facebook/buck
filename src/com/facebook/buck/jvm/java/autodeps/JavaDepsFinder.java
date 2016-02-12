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

package com.facebook.buck.jvm.java.autodeps;

import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.autodeps.DepsForBuildFiles;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.Console;
import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.Files;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.annotation.Nullable;

public class JavaDepsFinder {

  private static final String BUCK_CONFIG_SECTION = "autodeps";

  private static final Predicate<String> NOT_A_BUILT_IN_SYMBOL = new Predicate<String>() {
    @Override
    public boolean apply(String symbol) {
      // We can ignore things in java.*, but not javax.*, unfortunately since sometimes those are
      // provided by JSRs.
      return !symbol.startsWith("java.");
    }
  };

  /**
   * Map of symbol prefixes to [prebuilt_]java_library build rules that provide the respective
   * symbol. Keys are sorted from longest to shortest so the first match encountered wins. Note
   * that if this collection becomes large in practice, it might make sense to switch to a trie.
   */
  private final ImmutableSortedMap<String, BuildTarget> javaPackageMapping;

  private final JavaFileParser javaFileParser;

  public JavaDepsFinder(
      ImmutableSortedMap<String, BuildTarget> javaPackageMapping,
      JavaFileParser javaFileParser) {
    this.javaPackageMapping = javaPackageMapping;
    this.javaFileParser = javaFileParser;
  }

  public static JavaDepsFinder createJavaDepsFinder(
      BuckConfig buckConfig,
      final Function<Optional<String>, Path> cellNames) {
    Optional<String> javaPackageMappingOption = buckConfig.getValue(
        BUCK_CONFIG_SECTION,
        "java-package-mappings");
    ImmutableSortedMap<String, BuildTarget> javaPackageMapping;
    if (javaPackageMappingOption.isPresent()) {
      // Function that returns the key of the entry ending in `.` if it does not do so already.
      Function<Map.Entry<String, String>, Map.Entry<String, BuildTarget>> normalizePackageMapping =
          new Function<Map.Entry<String, String>, Map.Entry<String, BuildTarget>>() {
            @Override
            public Map.Entry<String, BuildTarget> apply(Map.Entry<String, String> entry) {
              String originalKey = entry.getKey().trim();
              // If the key corresponds to a Java package (not an entity), then make sure that it
              // ends with a `.` so the prefix matching will work as expected in
              // findProviderForSymbolFromBuckConfig(). Note that this heuristic could be a bit
              // tighter.
              boolean appearsToBeJavaPackage = !originalKey.endsWith(".") &&
                  CharMatcher.JAVA_UPPER_CASE.matchesNoneOf(originalKey);
              String key = appearsToBeJavaPackage ? originalKey + "." : originalKey;
              BuildTarget buildTarget = BuildTargetParser.INSTANCE.parse(
                  entry.getValue().trim(),
                  BuildTargetPatternParser.fullyQualified(),
                  cellNames);
              return Maps.immutableEntry(key, buildTarget);
            }
          };

      Iterable<Map.Entry<String, BuildTarget>> entries = FluentIterable.from(
          Splitter.on(',')
              .omitEmptyStrings()
              .withKeyValueSeparator("=>")
              .split(javaPackageMappingOption.get())
              .entrySet()
      ).transform(normalizePackageMapping);
      javaPackageMapping = ImmutableSortedMap.copyOf(entries, Ordering.natural().reverse());
    } else {
      javaPackageMapping = ImmutableSortedMap.of();
    }

    JavaBuckConfig javaBuckConfig = new JavaBuckConfig(buckConfig);
    JavacOptions javacOptions = javaBuckConfig.getDefaultJavacOptions();
    JavaFileParser javaFileParser = JavaFileParser.createJavaFileParser(javacOptions);

    return new JavaDepsFinder(javaPackageMapping, javaFileParser);
  }

  private static final Set<BuildRuleType> RULES_TO_VISIT = ImmutableSet.of(
      AndroidLibraryDescription.TYPE,
      JavaLibraryDescription.TYPE,
      JavaTestDescription.TYPE);

  public DepsForBuildFiles findDepsForBuildFiles(final TargetGraph graph, Console console) {
    final Set<BuildTarget> rulesWithAutodeps = new HashSet<>();

    // Keys are rules with autodeps = True. Values are symbols that are referenced by Java files in
    // the rule. These need to satisfied by one of the following:
    // * The hardcoded deps for the rule defined in the build file.
    // * The provided_deps of the rule.
    // * The auto-generated deps provided by this class.
    // * The exported_deps of one of the above.
    final HashMultimap<BuildTarget, String> ruleToRequiredSymbols = HashMultimap.create();

    final HashMultimap<String, BuildTarget> symbolToProviders = HashMultimap.create();

    // Keys are rules with autodeps = True. Values are the provided_deps for the rule.
    // Note that not every entry in rulesWithAutodeps will have an entry in this multimap.
    final HashMultimap<BuildTarget, BuildTarget> rulesWithAutodepsToProvidedDeps =
        HashMultimap.create();

    final HashMultimap<BuildTarget, BuildTarget> ruleToRulesThatExportIt = HashMultimap.create();

    // Walk the graph and for each Java rule we find, do the following:
    // 1. Make note if it has autodeps = True.
    // 2. If it does, record its required symbols.
    // 3. Record the Java entities it provides (regardless of whether autodeps = True).
    //
    // Currently, we traverse the entire target graph using a single thread. However, the work to
    // visit each node could be done in parallel, so long as the updates to the above collections
    // were thread-safe.
    new AbstractBottomUpTraversal<TargetNode<?>, Void>(graph) {
      @Override
      public void visit(TargetNode<?> node) {
        BuildRuleType buildRuleType = node.getDescription().getBuildRuleType();
        if (!RULES_TO_VISIT.contains(buildRuleType)) {
          return;
        }

        JavaLibraryDescription.Arg arg = (JavaLibraryDescription.Arg) node.getConstructorArg();
        boolean autodeps = arg.autodeps.or(false);
        BuildTarget buildTarget = node.getBuildTarget();
        if (autodeps) {
          rulesWithAutodeps.add(buildTarget);
          rulesWithAutodepsToProvidedDeps.putAll(
              buildTarget,
              arg.providedDeps.or(ImmutableSortedSet.<BuildTarget>of()));
        }

        for (BuildTarget exportedDep : arg.exportedDeps.or(ImmutableSortedSet.<BuildTarget>of())) {
          ruleToRulesThatExportIt.put(exportedDep, buildTarget);
        }

        // The build target should be recorded as a provider for every symbol in its
        // generated_symbols set (if it exists). It is common to use this for symbols that are
        // generated via annotation processors.
        if (arg.generatedSymbols.isPresent()) {
          for (String symbol : arg.generatedSymbols.get()) {
            symbolToProviders.put(symbol, buildTarget);
          }
        }

        // As a performance improvement, we should graph enhance a rule for the java_library() rule
        // that depends on the following inputs:
        //
        // 1. The javaPackageMapping
        // 2. The JavacOptions used to create the JavaFileParser.
        // 3. The names and contents of the srcs.
        //
        // The output file for the enhanced rule would be the total set of required/provided symbols
        // across all of the java_library() rule's srcs. If we could pull that result from cache,
        // that would avoid a lot of work here because it would eliminate all of the Java parsing.
        for (SourcePath src : arg.srcs.get()) {
          if (!(src instanceof PathSourcePath)) {
            continue;
          }

          PathSourcePath sourcePath = (PathSourcePath) src;
          ProjectFilesystem filesystem = sourcePath.getFilesystem();
          Path absolutePath = filesystem.resolve(sourcePath.getRelativePath());
          String code;
          try {
            code = Files.toString(absolutePath.toFile(), Charsets.UTF_8);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }

          JavaFileParser.JavaFileFeatures features = javaFileParser
              .extractFeaturesFromJavaCode(code);
          if (autodeps) {
            ruleToRequiredSymbols.putAll(
                buildTarget,
                FluentIterable.from(features.requiredSymbols).filter(NOT_A_BUILT_IN_SYMBOL));
          }

          for (String providedEntity : features.providedSymbols) {
            symbolToProviders.put(providedEntity, buildTarget);
          }
        }
      }
    }.traverse();

    // For the rules that expect to have their deps generated, look through all of their required
    // symbols and try to find the build rule that provides each symbols. Store these build rules in
    // the depsForBuildFiles data structure.
    //
    // Currently, we process each rule with autodeps=True on a single thread. See the class overview
    // for DepsForBuildFiles about what it would take to do this work in a multi-threaded way.
    DepsForBuildFiles depsForBuildFiles = new DepsForBuildFiles();
    for (final BuildTarget buildTarget : rulesWithAutodeps) {
      final Set<BuildTarget> providedDeps = rulesWithAutodepsToProvidedDeps.get(buildTarget);
      final Predicate<BuildTarget> isVisibleDepNotAlreadyInProvidedDeps =
          new Predicate<BuildTarget>() {
            @Override
            public boolean apply(BuildTarget provider) {
              TargetNode<?> node = graph.get(provider);
              return node.isVisibleTo(buildTarget) && !providedDeps.contains(provider);
            }
          };

      for (String requiredSymbol : ruleToRequiredSymbols.get(buildTarget)) {
        BuildTarget provider = findProviderForSymbolFromBuckConfig(requiredSymbol);
        if (provider != null) {
          depsForBuildFiles.addDep(buildTarget, provider);
          continue;
        }

        Set<BuildTarget> providers = symbolToProviders.get(requiredSymbol);
        SortedSet<BuildTarget> candidateProviders = FluentIterable.from(providers)
            .filter(isVisibleDepNotAlreadyInProvidedDeps)
            .toSortedSet(Ordering.<BuildTarget>natural());

        int numCandidates = candidateProviders.size();
        if (numCandidates == 1) {
          depsForBuildFiles.addDep(buildTarget, Iterables.getOnlyElement(candidateProviders));
        } else if (numCandidates > 1) {
          // Warn the user that there is an ambiguity. This could be very common with macros that
          // generate multiple versions of a java_library() with the same sources.
          // If numProviders is 0, then hopefully the dep is provided by something the user
          // hardcoded in the BUCK file.
          console.printErrorText(String.format(
              "WARNING: Multiple providers for %s: %s. " +
                  "Consider adding entry to .buckconfig to eliminate ambiguity:\n" +
                  "[autodeps]\n" +
                  "java-package-mappings = %s => %s",
              requiredSymbol,
              Joiner.on(", ").join(candidateProviders),
              requiredSymbol,
              Iterables.getFirst(candidateProviders, null)));
        } else {
          // If there aren't any candidates, then see if there is a visible rule that can provide
          // the symbol via its exported_deps. We make this a secondary check because we prefer to
          // depend on the rule that defines the symbol directly rather than one of possibly many
          // rules that provides it via its exported_deps.
          SortedSet<BuildTarget> newCandidates = new TreeSet<>();
          for (BuildTarget candidate : providers) {
            Set<BuildTarget> rulesThatExportCandidate = ruleToRulesThatExportIt.get(candidate);
            for (BuildTarget ruleThatExportsCandidate : rulesThatExportCandidate) {
              TargetNode<?> node = graph.get(ruleThatExportsCandidate);
              if (node.isVisibleTo(buildTarget)) {
                newCandidates.add(ruleThatExportsCandidate);
              }
            }
          }

          int numNewCandidates = newCandidates.size();
          if (numNewCandidates == 1) {
            depsForBuildFiles.addDep(buildTarget, Iterables.getOnlyElement(newCandidates));
          } else if (numNewCandidates > 1) {
            console.printErrorText(String.format(
                "WARNING: No providers found for '%s' for build rule %s, " +
                    "but there are multiple rules that export a rule to provide %s: %s",
                requiredSymbol,
                buildTarget,
                requiredSymbol,
                Joiner.on(", ").join(newCandidates)
            ));
          }
          // In the case that numNewCandidates is 0, we assume that the user is taking
          // responsibility for declaring a provider for the symbol by hardcoding it in the deps.
        }
      }
    }

    return depsForBuildFiles;
  }

  /**
   * Look through java-package-mappings in .buckconfig and see if the requested symbol has a
   * hardcoded provider.
   */
  @Nullable
  private BuildTarget findProviderForSymbolFromBuckConfig(String symbol) {
    for (String prefix : javaPackageMapping.keySet()) {
      if (symbol.startsWith(prefix)) {
        return javaPackageMapping.get(prefix);
      }
    }
    return null;
  }
}
