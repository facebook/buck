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
import com.facebook.buck.autodeps.DepsForBuildFiles.DependencyType;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.PrebuiltJarDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Console;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
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
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.annotation.Nullable;

public class JavaDepsFinder {

  private static final String BUCK_CONFIG_SECTION = "autodeps";

  /**
   * Map of symbol prefixes to [prebuilt_]java_library build rules that provide the respective
   * symbol. Keys are sorted from longest to shortest so the first match encountered wins. Note
   * that if this collection becomes large in practice, it might make sense to switch to a trie.
   */
  private final ImmutableSortedMap<String, BuildTarget> javaPackageMapping;

  private final JavaFileParser javaFileParser;
  private final ObjectMapper objectMapper;
  private final BuildContext buildContext;
  private final ExecutionContext executionContext;
  private final BuildEngine buildEngine;

  public JavaDepsFinder(
      ImmutableSortedMap<String, BuildTarget> javaPackageMapping,
      JavaFileParser javaFileParser,
      ObjectMapper objectMapper,
      BuildContext buildContext,
      ExecutionContext executionContext,
      BuildEngine buildEngine) {
    this.javaPackageMapping = javaPackageMapping;
    this.javaFileParser = javaFileParser;
    this.objectMapper = objectMapper;
    this.buildContext = buildContext;
    this.executionContext = executionContext;
    this.buildEngine = buildEngine;
  }

  public JavaFileParser getJavaFileParser() {
    return javaFileParser;
  }

  public static JavaDepsFinder createJavaDepsFinder(
      BuckConfig buckConfig,
      final CellPathResolver cellNames,
      ObjectMapper objectMapper,
      BuildContext buildContext,
      ExecutionContext executionContext,
      BuildEngine buildEngine) {
    Optional<String> javaPackageMappingOption = buckConfig.getValue(
        BUCK_CONFIG_SECTION,
        "java-package-mappings");
    ImmutableSortedMap<String, BuildTarget> javaPackageMapping;
    if (javaPackageMappingOption.isPresent()) {
      // Function that returns the key of the entry ending in `.` if it does not do so already.
      Function<Map.Entry<String, String>, Map.Entry<String, BuildTarget>> normalizePackageMapping =
          entry -> {
            String originalKey = entry.getKey().trim();
            // If the key corresponds to a Java package (not an entity), then make sure that it
            // ends with a `.` so the prefix matching will work as expected in
            // findProviderForSymbolFromBuckConfig(). Note that this heuristic could be a bit
            // tighter.
            boolean appearsToBeJavaPackage = !originalKey.endsWith(".") &&
                CharMatcher.javaUpperCase().matchesNoneOf(originalKey);
            String key = appearsToBeJavaPackage ? originalKey + "." : originalKey;
            BuildTarget buildTarget = BuildTargetParser.INSTANCE.parse(
                entry.getValue().trim(),
                BuildTargetPatternParser.fullyQualified(),
                cellNames);
            return Maps.immutableEntry(key, buildTarget);
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

    return new JavaDepsFinder(
        javaPackageMapping,
        javaFileParser,
        objectMapper,
        buildContext,
        executionContext,
        buildEngine);
  }

  private static final Set<BuildRuleType> RULES_TO_VISIT = ImmutableSet.of(
      AndroidLibraryDescription.TYPE,
      JavaLibraryDescription.TYPE,
      JavaTestDescription.TYPE,
      PrebuiltJarDescription.TYPE);

  /**
   * Java dependency information that is extracted from a {@link TargetGraph}.
   */
  public static class DependencyInfo {
    final Set<TargetNode<?>> rulesWithAutodeps = new HashSet<>();

    /**
     * Keys are rules with autodeps = True. Values are symbols that are referenced by Java files in
     * the rule. These need to satisfied by one of the following:
     * <ul>
     * <li>The hardcoded deps for the rule defined in the build file.
     * <li>The provided_deps of the rule.
     * <li>The auto-generated deps provided by this class.
     * <li>The exported_deps of one of the above.
     * </ul>
     */
    final HashMultimap<TargetNode<?>, String> ruleToRequiredSymbols = HashMultimap.create();
    final HashMultimap<TargetNode<?>, String> ruleToExportedSymbols = HashMultimap.create();

    public final HashMultimap<String, TargetNode<?>> symbolToProviders = HashMultimap.create();

    /**
     * Keys are rules with {@code autodeps = True}. Values are the provided_deps for the rule.
     * Note that not every entry in rulesWithAutodeps will have an entry in this multimap.
     */
    final HashMultimap<TargetNode<?>, BuildTarget> rulesWithAutodepsToProvidedDeps =
        HashMultimap.create();

    final HashMultimap<TargetNode<?>, TargetNode<?>> ruleToRulesThatExportIt =
        HashMultimap.create();
  }

  public DepsForBuildFiles findDepsForBuildFiles(final TargetGraph graph, Console console) {
    DependencyInfo dependencyInfo = findDependencyInfoForGraph(graph);
    return findDepsForBuildFiles(graph, dependencyInfo, console);
  }

  public DependencyInfo findDependencyInfoForGraph(final TargetGraph graph) {
    final DependencyInfo dependencyInfo = new DependencyInfo();

    // Walk the graph and for each Java rule we find, do the following:
    // 1. Make note if it has autodeps = True.
    // 2. If it does, record its required symbols.
    // 3. Record the Java entities it provides (regardless of whether autodeps = True).
    //
    // Currently, we traverse the entire target graph using a single thread. However, the work to
    // visit each node could be done in parallel, so long as the updates to the above collections
    // were thread-safe.
    new AbstractBottomUpTraversal<TargetNode<?>, RuntimeException>(graph) {
      @Override
      public void visit(TargetNode<?> node) {
        BuildRuleType buildRuleType = node.getDescription().getBuildRuleType();
        if (!RULES_TO_VISIT.contains(buildRuleType)) {
          return;
        }

        // Set up the appropriate fields for java_library() vs. prebuilt_jar().
        boolean autodeps;
        ImmutableSortedSet<BuildTarget> providedDeps;
        ImmutableSortedSet<BuildTarget> exportedDeps;
        if (node.getConstructorArg() instanceof JavaLibraryDescription.Arg) {
          JavaLibraryDescription.Arg arg = (JavaLibraryDescription.Arg) node.getConstructorArg();
          autodeps = arg.autodeps.orElse(false);
          providedDeps = arg.providedDeps;
          exportedDeps = arg.exportedDeps;
        } else if (node.getConstructorArg() instanceof PrebuiltJarDescription.Arg) {
          autodeps = false;
          providedDeps = ImmutableSortedSet.of();
          exportedDeps = ImmutableSortedSet.of();
        } else {
          throw new IllegalStateException("This rule is not supported by autodeps: " + node);
        }

        if (autodeps) {
          dependencyInfo.rulesWithAutodeps.add(node);
          dependencyInfo.rulesWithAutodepsToProvidedDeps.putAll(node, providedDeps);
        }

        for (BuildTarget exportedDep : exportedDeps) {
          dependencyInfo.ruleToRulesThatExportIt.put(graph.get(exportedDep), node);
        }

        Symbols symbols = getJavaFileFeatures(node, autodeps);
        if (autodeps) {
          dependencyInfo.ruleToRequiredSymbols.putAll(node, symbols.required);
          dependencyInfo.ruleToExportedSymbols.putAll(node, symbols.exported);
        }
        for (String providedEntity : symbols.provided) {
          dependencyInfo.symbolToProviders.put(providedEntity, node);
        }
      }
    }.traverse();

    return dependencyInfo;
  }

  private DepsForBuildFiles findDepsForBuildFiles(
      final TargetGraph graph,
      final DependencyInfo dependencyInfo,
      final Console console) {
    // For the rules that expect to have their deps generated, look through all of their required
    // symbols and try to find the build rule that provides each symbols. Store these build rules in
    // the depsForBuildFiles data structure.
    //
    // Currently, we process each rule with autodeps=True on a single thread. See the class overview
    // for DepsForBuildFiles about what it would take to do this work in a multi-threaded way.
    DepsForBuildFiles depsForBuildFiles = new DepsForBuildFiles();
    for (final TargetNode<?> rule : dependencyInfo.rulesWithAutodeps) {
      final Set<BuildTarget> providedDeps = dependencyInfo.rulesWithAutodepsToProvidedDeps.get(
          rule);
      final Predicate<TargetNode<?>> isVisibleDepNotAlreadyInProvidedDeps =
          provider -> provider.isVisibleTo(graph, rule) &&
              !providedDeps.contains(provider.getBuildTarget());
      final boolean isJavaTestRule = rule.getType() == JavaTestDescription.TYPE;

      for (DependencyType type : DependencyType.values()) {
        HashMultimap<TargetNode<?>, String> ruleToSymbolsMap;
        switch (type) {
        case DEPS:
          ruleToSymbolsMap = dependencyInfo.ruleToRequiredSymbols;
          break;
        case EXPORTED_DEPS:
          ruleToSymbolsMap = dependencyInfo.ruleToExportedSymbols;
          break;
        default:
          throw new IllegalStateException("Unrecognized type: " + type);
        }

        final DependencyType typeOfDepToAdd;
        if (isJavaTestRule) {
          // java_test rules do not honor exported_deps: add all dependencies to the ordinary deps.
          typeOfDepToAdd = DependencyType.DEPS;
        } else {
          typeOfDepToAdd = type;
        }

        for (String requiredSymbol : ruleToSymbolsMap.get(rule)) {
          BuildTarget provider = findProviderForSymbolFromBuckConfig(requiredSymbol);
          if (provider != null) {
            depsForBuildFiles.addDep(rule.getBuildTarget(), provider, typeOfDepToAdd);
            continue;
          }

          Set<TargetNode<?>> providers = dependencyInfo.symbolToProviders.get(requiredSymbol);
          SortedSet<TargetNode<?>> candidateProviders = FluentIterable.from(providers)
              .filter(isVisibleDepNotAlreadyInProvidedDeps)
              .toSortedSet(Ordering.natural());

          int numCandidates = candidateProviders.size();
          if (numCandidates == 1) {
            depsForBuildFiles.addDep(
                rule.getBuildTarget(),
                Iterables.getOnlyElement(candidateProviders).getBuildTarget(),
                typeOfDepToAdd);
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
            SortedSet<TargetNode<?>> newCandidates = new TreeSet<>();
            for (TargetNode<?> candidate : providers) {
              Set<TargetNode<?>> rulesThatExportCandidate = dependencyInfo.ruleToRulesThatExportIt
                  .get(candidate);
              for (TargetNode<?> ruleThatExportsCandidate : rulesThatExportCandidate) {
                if (ruleThatExportsCandidate.isVisibleTo(graph, rule)) {
                  newCandidates.add(ruleThatExportsCandidate);
                }
              }
            }

            int numNewCandidates = newCandidates.size();
            if (numNewCandidates == 1) {
              depsForBuildFiles.addDep(
                  rule.getBuildTarget(),
                  Iterables.getOnlyElement(newCandidates).getBuildTarget(),
                  typeOfDepToAdd);
            } else if (numNewCandidates > 1) {
              console.printErrorText(String.format(
                  "WARNING: No providers found for '%s' for build rule %s, " +
                      "but there are multiple rules that export a rule to provide %s: %s",
                  requiredSymbol,
                  rule.getBuildTarget(),
                  requiredSymbol,
                  Joiner.on(", ").join(newCandidates)
              ));
            }
            // In the case that numNewCandidates is 0, we assume that the user is taking
            // responsibility for declaring a provider for the symbol by hardcoding it in the deps.
          }
        }
      }
    }

    return depsForBuildFiles;
  }

  private Symbols getJavaFileFeatures(TargetNode<?> node, boolean shouldRecordRequiredSymbols) {
    // Build a JavaLibrarySymbolsFinder to create the JavaFileFeatures. By making use of Buck's
    // build cache, we can often avoid running a Java parser.
    BuildTarget buildTarget = node.getBuildTarget();
    Object argForNode = node.getConstructorArg();
    JavaSymbolsRule.SymbolsFinder symbolsFinder;
    ImmutableSortedSet<String> generatedSymbols;
    if (argForNode instanceof JavaLibraryDescription.Arg) {
      JavaLibraryDescription.Arg arg = (JavaLibraryDescription.Arg) argForNode;
      // The build target should be recorded as a provider for every symbol in its
      // generated_symbols set (if it exists). It is common to use this for symbols that are
      // generated via annotation processors.
      generatedSymbols = arg.generatedSymbols;
      symbolsFinder = new JavaLibrarySymbolsFinder(
          arg.srcs,
          javaFileParser,
          shouldRecordRequiredSymbols);
    } else {
      PrebuiltJarDescription.Arg arg = (PrebuiltJarDescription.Arg) argForNode;
      generatedSymbols = ImmutableSortedSet.of();
      symbolsFinder = new PrebuiltJarSymbolsFinder(arg.binaryJar);
    }

    // Build the rule, leveraging Buck's build cache.
    JavaSymbolsRule buildRule = new JavaSymbolsRule(
        buildTarget,
        symbolsFinder,
        generatedSymbols,
        objectMapper,
        node.getRuleFactoryParams().getProjectFilesystem());
    ListenableFuture<BuildResult> future =
        buildEngine.build(buildContext, executionContext, buildRule);
    BuildResult result = Futures.getUnchecked(future);

    Symbols features;
    if (result.getSuccess() != null) {
      features = buildRule.getFeatures();
    } else {
      Throwable failure = result.getFailure();
      Preconditions.checkNotNull(failure);
      throw new RuntimeException("Failed to extract Java symbols for " + buildTarget, failure);
    }
    return features;
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
