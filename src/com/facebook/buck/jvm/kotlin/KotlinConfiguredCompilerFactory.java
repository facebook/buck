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

package com.facebook.buck.jvm.kotlin;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;

import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.AnnotationProcessingTool;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.CoreArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;

public class KotlinConfiguredCompilerFactory extends ConfiguredCompilerFactory {

  private final KotlinBuckConfig kotlinBuckConfig;
  private final Function<ToolchainProvider, ExtraClasspathProvider> extraClasspathProviderSupplier;
  private final JavacFactory javacFactory;

  public KotlinConfiguredCompilerFactory(
      KotlinBuckConfig kotlinBuckConfig, JavacFactory javacFactory) {
    this(kotlinBuckConfig, (toolchainProvider) -> ExtraClasspathProvider.EMPTY, javacFactory);
  }

  public KotlinConfiguredCompilerFactory(
      KotlinBuckConfig kotlinBuckConfig,
      Function<ToolchainProvider, ExtraClasspathProvider> extraClasspathProviderSupplier,
      JavacFactory javacFactory) {
    super();
    this.kotlinBuckConfig = kotlinBuckConfig;
    this.extraClasspathProviderSupplier = extraClasspathProviderSupplier;
    this.javacFactory = javacFactory;
  }

  @Override
  public CompileToJarStepFactory configure(
      @Nullable JvmLibraryArg args,
      JavacOptions javacOptions,
      BuildRuleResolver buildRuleResolver,
      ToolchainProvider toolchainProvider) {
    CoreArg kotlinArgs = Objects.requireNonNull((CoreArg) args);
    return new KotlincToJarStepFactory(
        kotlinBuckConfig.getKotlinc(),
        kotlinBuckConfig.getKotlinHomeLibraries(),
        condenseCompilerArguments(kotlinArgs),
        kotlinArgs.getFriendPaths(),
        kotlinArgs.getAnnotationProcessingTool().orElse(AnnotationProcessingTool.KAPT),
        extraClasspathProviderSupplier.apply(toolchainProvider),
        getJavac(buildRuleResolver, args),
        javacOptions);
  }

  private Javac getJavac(BuildRuleResolver resolver, @Nullable JvmLibraryArg arg) {
    return javacFactory.create(new SourcePathRuleFinder(resolver), arg);
  }

  private ImmutableList<String> condenseCompilerArguments(CoreArg kotlinArgs) {
    ImmutableMap.Builder<String, Optional<String>> optionBuilder = ImmutableMap.builder();
    LinkedHashMap<String, Optional<String>> freeArgs = Maps.newLinkedHashMap();
    kotlinArgs.getFreeCompilerArgs().forEach(arg -> freeArgs.put(arg, Optional.empty()));
    optionBuilder.putAll(freeArgs);

    // Args from CommonToolArguments.kt and KotlinCommonToolOptions.kt
    if (kotlinArgs.getAllWarningsAsErrors()) {
      optionBuilder.put("-Werror", Optional.empty());
    }
    if (kotlinArgs.getSuppressWarnings()) {
      optionBuilder.put("-nowarn", Optional.empty());
    }
    if (kotlinArgs.getVerbose()) {
      optionBuilder.put("-verbose", Optional.empty());
    }

    // Args from K2JVMCompilerArguments.kt and KotlinJvmOptions.kt
    optionBuilder.put("-jvm-target", Optional.of(kotlinArgs.getJvmTarget()));
    if (kotlinArgs.getIncludeRuntime()) {
      optionBuilder.put("-include-runtime", Optional.empty());
    }
    kotlinArgs
        .getJdkHome()
        .ifPresent(jdkHome -> optionBuilder.put("-jdk-home", Optional.of(jdkHome)));
    if (kotlinArgs.getNoJdk()) {
      optionBuilder.put("-no-jdk", Optional.empty());
    }
    if (kotlinArgs.getNoStdlib()) {
      optionBuilder.put("-no-stdlib", Optional.empty());
    }
    if (kotlinArgs.getNoReflect()) {
      optionBuilder.put("-no-reflect", Optional.empty());
    }
    if (kotlinArgs.getJavaParameters()) {
      optionBuilder.put("-java-parameters", Optional.empty());
    }
    kotlinArgs
        .getApiVersion()
        .ifPresent(apiVersion -> optionBuilder.put("-api-version", Optional.of(apiVersion)));
    kotlinArgs
        .languageVersion()
        .ifPresent(
            languageVersion ->
                optionBuilder.put("-language-version", Optional.of(languageVersion)));

    // Return de-duping keys and sorting by them.
    return optionBuilder.build().entrySet().stream()
        .filter(distinctByKey(Map.Entry::getKey))
        .sorted(comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
        .flatMap(
            entry -> {
              if (entry.getValue().isPresent()) {
                return ImmutableList.of(entry.getKey(), entry.getValue().get()).stream();
              } else {
                return ImmutableList.of(entry.getKey()).stream();
              }
            })
        .collect(toImmutableList());
  }

  private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
    Set<Object> seen = ConcurrentHashMap.newKeySet();
    return t -> seen.add(keyExtractor.apply(t));
  }
}
