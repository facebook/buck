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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.description.arg.DataTransferObject;
import com.facebook.buck.core.linkgroup.CxxLinkGroupMappingTarget;
import com.facebook.buck.core.linkgroup.UnconfiguredCxxLinkGroupMappingTarget;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcher;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.core.starlark.coercer.SkylarkDescriptionArgFactory;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.rules.macros.AbsoluteOutputMacro;
import com.facebook.buck.rules.macros.CcFlagsMacro;
import com.facebook.buck.rules.macros.CcMacro;
import com.facebook.buck.rules.macros.ClasspathAbiMacro;
import com.facebook.buck.rules.macros.ClasspathMacro;
import com.facebook.buck.rules.macros.CppFlagsMacro;
import com.facebook.buck.rules.macros.CudaFlagsMacro;
import com.facebook.buck.rules.macros.CudaMacro;
import com.facebook.buck.rules.macros.CudappFlagsMacro;
import com.facebook.buck.rules.macros.CxxFlagsMacro;
import com.facebook.buck.rules.macros.CxxHeaderTreeMacro;
import com.facebook.buck.rules.macros.CxxMacro;
import com.facebook.buck.rules.macros.CxxppFlagsMacro;
import com.facebook.buck.rules.macros.EnvMacro;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.ExecutableTargetMacro;
import com.facebook.buck.rules.macros.LdMacro;
import com.facebook.buck.rules.macros.LdflagsSharedFilterMacro;
import com.facebook.buck.rules.macros.LdflagsSharedMacro;
import com.facebook.buck.rules.macros.LdflagsStaticFilterMacro;
import com.facebook.buck.rules.macros.LdflagsStaticMacro;
import com.facebook.buck.rules.macros.LdflagsStaticPicFilterMacro;
import com.facebook.buck.rules.macros.LdflagsStaticPicMacro;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.LocationPlatformMacro;
import com.facebook.buck.rules.macros.MavenCoordinatesMacro;
import com.facebook.buck.rules.macros.OutputMacro;
import com.facebook.buck.rules.macros.PlatformNameMacro;
import com.facebook.buck.rules.macros.QueryOutputsMacro;
import com.facebook.buck.rules.macros.QueryPathsMacro;
import com.facebook.buck.rules.macros.QueryTargetsAndOutputsMacro;
import com.facebook.buck.rules.macros.QueryTargetsMacro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.UnconfiguredClasspathAbiMacro;
import com.facebook.buck.rules.macros.UnconfiguredClasspathMacro;
import com.facebook.buck.rules.macros.UnconfiguredCppFlagsMacro;
import com.facebook.buck.rules.macros.UnconfiguredCudappFlagsMacro;
import com.facebook.buck.rules.macros.UnconfiguredCxxppFlagsMacro;
import com.facebook.buck.rules.macros.UnconfiguredExecutableMacro;
import com.facebook.buck.rules.macros.UnconfiguredExecutableTargetMacro;
import com.facebook.buck.rules.macros.UnconfiguredLdflagsSharedFilterMacro;
import com.facebook.buck.rules.macros.UnconfiguredLdflagsSharedMacro;
import com.facebook.buck.rules.macros.UnconfiguredLdflagsStaticFilterMacro;
import com.facebook.buck.rules.macros.UnconfiguredLdflagsStaticMacro;
import com.facebook.buck.rules.macros.UnconfiguredLdflagsStaticPicFilterMacro;
import com.facebook.buck.rules.macros.UnconfiguredLdflagsStaticPicMacro;
import com.facebook.buck.rules.macros.UnconfiguredMavenCoordinatesMacro;
import com.facebook.buck.rules.macros.UnconfiguredQueryOutputsMacro;
import com.facebook.buck.rules.macros.UnconfiguredQueryPathsMacro;
import com.facebook.buck.rules.macros.UnconfiguredQueryTargetsMacro;
import com.facebook.buck.rules.macros.UnconfiguredStringWithMacros;
import com.facebook.buck.rules.macros.UnconfiguredWorkerMacro;
import com.facebook.buck.rules.macros.WorkerMacro;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.UnconfiguredQuery;
import com.facebook.buck.util.Types;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.primitives.Primitives;
import com.google.common.reflect.TypeToken;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Create {@link TypeCoercer}s that can convert incoming java structures (from json) into particular
 * types.
 */
public class DefaultTypeCoercerFactory implements TypeCoercerFactory {

  private final CoercedTypeCache coercedTypeCache = new CoercedTypeCache(this);

  private final TypeCoercer<UnconfiguredBuildTarget, UnconfiguredBuildTarget>
      unconfiguredBuildTargetTypeCoercer;
  private final TypeCoercer<UnflavoredBuildTarget, UnflavoredBuildTarget>
      unflavoredBuildTargetTypeCoercer;
  private final TypeCoercer<Pattern, Pattern> patternTypeCoercer = new PatternTypeCoercer();

  private final TypeCoercer<Object, ?>[] nonParameterizedTypeCoercers;
  private final ParsingUnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;

  @SuppressWarnings("unchecked")
  public DefaultTypeCoercerFactory() {
    StringTypeCoercer stringTypeCoercer = new StringTypeCoercer();
    TypeCoercer<Flavor, Flavor> flavorTypeCoercer = new FlavorTypeCoercer();
    // This has no implementation, but is here so that constructor succeeds so that it can be
    // queried. This is only used for the visibility field, which is not actually handled by the
    // coercer.
    TypeCoercer<BuildTargetMatcher, BuildTargetMatcher> buildTargetPatternTypeCoercer =
        new BuildTargetMatcherTypeCoercer();
    unconfiguredBuildTargetFactory = new ParsingUnconfiguredBuildTargetViewFactory();
    unconfiguredBuildTargetTypeCoercer =
        new UnconfiguredBuildTargetTypeCoercer(unconfiguredBuildTargetFactory);
    unflavoredBuildTargetTypeCoercer =
        new UnflavoredBuildTargetTypeCoercer(unconfiguredBuildTargetFactory);
    BuildTargetTypeCoercer buildTargetTypeCoercer =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer);
    BuildTargetWithOutputsTypeCoercer buildTargetWithOutputsTypeCoercer =
        new BuildTargetWithOutputsTypeCoercer(
            new UnconfiguredBuildTargetWithOutputsTypeCoercer(unconfiguredBuildTargetTypeCoercer));
    TypeCoercer<Path, Path> pathTypeCoercer = new PathTypeCoercer();
    TypeCoercer<UnconfiguredSourcePath, SourcePath> sourcePathTypeCoercer =
        new SourcePathTypeCoercer(buildTargetWithOutputsTypeCoercer, pathTypeCoercer);

    TypeCoercer<Integer, Integer> intTypeCoercer = new NumberTypeCoercer<>(Integer.class);
    TypeCoercer<Double, Double> doubleTypeCoercer = new NumberTypeCoercer<>(Double.class);
    TypeCoercer<Boolean, Boolean> booleanTypeCoercer = new IdentityTypeCoercer<>(Boolean.class);
    TypeCoercer<UnconfiguredNeededCoverageSpec, NeededCoverageSpec> neededCoverageSpecTypeCoercer =
        new NeededCoverageSpecTypeCoercer(
            intTypeCoercer, buildTargetTypeCoercer, stringTypeCoercer);
    TypeCoercer<UnconfiguredQuery, Query> queryTypeCoercer = new QueryCoercer();
    TypeCoercer<ImmutableList<UnconfiguredBuildTarget>, ImmutableList<BuildTarget>>
        buildTargetsTypeCoercer = new ListTypeCoercer<>(buildTargetTypeCoercer);
    TypeCoercer<CxxLinkGroupMappingTarget.Traversal, CxxLinkGroupMappingTarget.Traversal>
        linkGroupMappingTraversalCoercer = new CxxLinkGroupMappingTargetTraversalCoercer();
    CxxLinkGroupMappingTargetCoercer linkGroupMappingTargetCoercer =
        new CxxLinkGroupMappingTargetCoercer(
            buildTargetTypeCoercer, linkGroupMappingTraversalCoercer, patternTypeCoercer);
    TypeCoercer<
            ImmutableList<UnconfiguredCxxLinkGroupMappingTarget>,
            ImmutableList<CxxLinkGroupMappingTarget>>
        linkGroupMappingTargetsCoercer = new ListTypeCoercer<>(linkGroupMappingTargetCoercer);
    CxxLinkGroupMappingCoercer linkGroupMappingCoercer =
        new CxxLinkGroupMappingCoercer(stringTypeCoercer, linkGroupMappingTargetsCoercer);
    TypeCoercer<UnconfiguredStringWithMacros, StringWithMacros> stringWithMacrosCoercer =
        StringWithMacrosTypeCoercer.builder()
            .put(
                "classpath",
                ClasspathMacro.class,
                new BuildTargetMacroTypeCoercer<>(
                    buildTargetWithOutputsTypeCoercer,
                    UnconfiguredClasspathMacro.class,
                    ClasspathMacro.class,
                    UnconfiguredClasspathMacro::of))
            .put(
                "classpath_abi",
                ClasspathAbiMacro.class,
                new BuildTargetMacroTypeCoercer<>(
                    buildTargetWithOutputsTypeCoercer,
                    UnconfiguredClasspathAbiMacro.class,
                    ClasspathAbiMacro.class,
                    UnconfiguredClasspathAbiMacro::of))
            .put(
                "exe",
                ExecutableMacro.class,
                new BuildTargetMacroTypeCoercer<>(
                    buildTargetWithOutputsTypeCoercer,
                    UnconfiguredExecutableMacro.class,
                    ExecutableMacro.class,
                    // TODO(nga): switch to host
                    UnconfiguredExecutableMacro::of))
            .put(
                "exe_target",
                ExecutableTargetMacro.class,
                new BuildTargetMacroTypeCoercer<>(
                    buildTargetWithOutputsTypeCoercer,
                    UnconfiguredExecutableTargetMacro.class,
                    ExecutableTargetMacro.class,
                    UnconfiguredExecutableTargetMacro::of))
            .put("env", EnvMacro.class, new EnvMacroTypeCoercer())
            .put(
                "location",
                LocationMacro.class,
                new LocationMacroTypeCoercer(buildTargetWithOutputsTypeCoercer))
            .put(
                "location-platform",
                LocationPlatformMacro.class,
                new LocationPlatformMacroTypeCoercer(buildTargetWithOutputsTypeCoercer))
            .put(
                "maven_coords",
                MavenCoordinatesMacro.class,
                new BuildTargetMacroTypeCoercer<>(
                    buildTargetWithOutputsTypeCoercer,
                    UnconfiguredMavenCoordinatesMacro.class,
                    MavenCoordinatesMacro.class,
                    UnconfiguredMavenCoordinatesMacro::of))
            .put("output", OutputMacro.class, new OutputMacroTypeCoercer())
            .put("abs_output", AbsoluteOutputMacro.class, new AbsoluteOutputMacroTypeCoercer())
            .put(
                "query_targets",
                QueryTargetsMacro.class,
                new QueryMacroTypeCoercer<>(
                    queryTypeCoercer,
                    UnconfiguredQueryTargetsMacro.class,
                    QueryTargetsMacro.class,
                    UnconfiguredQueryTargetsMacro::of))
            .put(
                "query_outputs",
                QueryOutputsMacro.class,
                new QueryMacroTypeCoercer<>(
                    queryTypeCoercer,
                    UnconfiguredQueryOutputsMacro.class,
                    QueryOutputsMacro.class,
                    UnconfiguredQueryOutputsMacro::of))
            .put(
                "query_paths",
                QueryPathsMacro.class,
                new QueryMacroTypeCoercer<>(
                    queryTypeCoercer,
                    UnconfiguredQueryPathsMacro.class,
                    QueryPathsMacro.class,
                    UnconfiguredQueryPathsMacro::of))
            .put(
                "query_targets_and_outputs",
                QueryTargetsAndOutputsMacro.class,
                new QueryTargetsAndOutputsMacroTypeCoercer(queryTypeCoercer))
            .put(
                "worker",
                WorkerMacro.class,
                new BuildTargetMacroTypeCoercer<>(
                    buildTargetWithOutputsTypeCoercer,
                    UnconfiguredWorkerMacro.class,
                    WorkerMacro.class,
                    UnconfiguredWorkerMacro::of))
            .put(
                "cc",
                CcMacro.class,
                new ZeroArgMacroTypeCoercer<>(CcMacro.class, CcMacro.class, CcMacro.of()))
            .put(
                "cflags",
                CcFlagsMacro.class,
                new ZeroArgMacroTypeCoercer<>(
                    CcFlagsMacro.class, CcFlagsMacro.class, CcFlagsMacro.of()))
            .put(
                "cppflags",
                CppFlagsMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.empty(),
                    buildTargetsTypeCoercer,
                    UnconfiguredCppFlagsMacro.class,
                    CppFlagsMacro.class,
                    UnconfiguredCppFlagsMacro::of))
            .put(
                "cxx",
                CxxMacro.class,
                new ZeroArgMacroTypeCoercer<>(CxxMacro.class, CxxMacro.class, CxxMacro.of()))
            .put(
                "cxxflags",
                CxxFlagsMacro.class,
                new ZeroArgMacroTypeCoercer<>(
                    CxxFlagsMacro.class, CxxFlagsMacro.class, CxxFlagsMacro.of()))
            .put(
                "cxxppflags",
                CxxppFlagsMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.empty(),
                    buildTargetsTypeCoercer,
                    UnconfiguredCxxppFlagsMacro.class,
                    CxxppFlagsMacro.class,
                    UnconfiguredCxxppFlagsMacro::of))
            .put(
                "cuda",
                CudaMacro.class,
                new ZeroArgMacroTypeCoercer<>(CudaMacro.class, CudaMacro.class, CudaMacro.of()))
            .put(
                "cudaflags",
                CudaFlagsMacro.class,
                new ZeroArgMacroTypeCoercer<>(
                    CudaFlagsMacro.class, CudaFlagsMacro.class, CudaFlagsMacro.of()))
            .put(
                "cudappflags",
                CudappFlagsMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.empty(),
                    buildTargetsTypeCoercer,
                    UnconfiguredCudappFlagsMacro.class,
                    CudappFlagsMacro.class,
                    UnconfiguredCudappFlagsMacro::of))
            .put(
                "ld",
                LdMacro.class,
                new ZeroArgMacroTypeCoercer<>(LdMacro.class, LdMacro.class, LdMacro.of()))
            .put(
                "ldflags-shared",
                LdflagsSharedMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.empty(),
                    buildTargetsTypeCoercer,
                    UnconfiguredLdflagsSharedMacro.class,
                    LdflagsSharedMacro.class,
                    UnconfiguredLdflagsSharedMacro::of))
            .put(
                "ldflags-shared-filter",
                LdflagsSharedFilterMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.of(patternTypeCoercer),
                    buildTargetsTypeCoercer,
                    UnconfiguredLdflagsSharedFilterMacro.class,
                    LdflagsSharedFilterMacro.class,
                    UnconfiguredLdflagsSharedFilterMacro::of))
            .put(
                "ldflags-static",
                LdflagsStaticMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.empty(),
                    buildTargetsTypeCoercer,
                    UnconfiguredLdflagsStaticMacro.class,
                    LdflagsStaticMacro.class,
                    UnconfiguredLdflagsStaticMacro::of))
            .put(
                "ldflags-static-filter",
                LdflagsStaticFilterMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.of(patternTypeCoercer),
                    buildTargetsTypeCoercer,
                    UnconfiguredLdflagsStaticFilterMacro.class,
                    LdflagsStaticFilterMacro.class,
                    UnconfiguredLdflagsStaticFilterMacro::of))
            .put(
                "ldflags-static-pic",
                LdflagsStaticPicMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.empty(),
                    buildTargetsTypeCoercer,
                    UnconfiguredLdflagsStaticPicMacro.class,
                    LdflagsStaticPicMacro.class,
                    UnconfiguredLdflagsStaticPicMacro::of))
            .put(
                "ldflags-static-pic-filter",
                LdflagsStaticPicFilterMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.of(patternTypeCoercer),
                    buildTargetsTypeCoercer,
                    UnconfiguredLdflagsStaticPicFilterMacro.class,
                    LdflagsStaticPicFilterMacro.class,
                    UnconfiguredLdflagsStaticPicFilterMacro::of))
            .put(
                "platform-name",
                PlatformNameMacro.class,
                new ZeroArgMacroTypeCoercer<>(
                    PlatformNameMacro.class, PlatformNameMacro.class, PlatformNameMacro.of()))
            .put(
                "cxx-header-tree",
                CxxHeaderTreeMacro.class,
                new ZeroArgMacroTypeCoercer<>(
                    CxxHeaderTreeMacro.class, CxxHeaderTreeMacro.class, CxxHeaderTreeMacro.of()))
            .build();

    SourceWithFlagsTypeCoercer sourceWithFlagsTypeCoercer =
        new SourceWithFlagsTypeCoercer(
            sourcePathTypeCoercer, new ListTypeCoercer<>(stringWithMacrosCoercer));

    nonParameterizedTypeCoercers =
        (TypeCoercer<Object, ?>[])
            new TypeCoercer<?, ?>[] {
              // special classes
              pathTypeCoercer,
              flavorTypeCoercer,
              sourcePathTypeCoercer,
              unconfiguredBuildTargetTypeCoercer,
              unflavoredBuildTargetTypeCoercer,
              buildTargetTypeCoercer,
              buildTargetWithOutputsTypeCoercer,
              buildTargetPatternTypeCoercer,

              // apple link groups
              linkGroupMappingCoercer,

              // identity
              stringTypeCoercer,
              booleanTypeCoercer,

              // numeric
              intTypeCoercer,
              doubleTypeCoercer,
              new NumberTypeCoercer<>(Float.class),
              new NumberTypeCoercer<>(Long.class),
              new NumberTypeCoercer<>(Short.class),
              new NumberTypeCoercer<>(Byte.class),

              // other simple
              sourceWithFlagsTypeCoercer,
              new BuildConfigFieldsTypeCoercer(),
              new UriTypeCoercer(),
              new FrameworkPathTypeCoercer(sourcePathTypeCoercer),
              new SourceSetTypeCoercer(stringTypeCoercer, sourcePathTypeCoercer),
              new SourceSortedSetTypeCoercer(stringTypeCoercer, sourcePathTypeCoercer),
              new LogLevelTypeCoercer(),
              new ManifestEntriesTypeCoercer(),
              patternTypeCoercer,
              neededCoverageSpecTypeCoercer,
              new VersionTypeCoercer(),
              queryTypeCoercer,
              stringWithMacrosCoercer,
              new TestRunnerSpecCoercer(stringWithMacrosCoercer),
            };
  }

  @Override
  public <U> TypeCoercer<U, ?> typeCoercerForUnconfiguredType(TypeToken<U> typeToken) {
    return typeCoercerForTypeUnchecked(typeToken, TypeCoercer::getUnconfiguredType)
        .checkUnconfiguredAssignableTo(typeToken);
  }

  @Override
  public <T> TypeCoercer<?, T> typeCoercerForType(TypeToken<T> typeToken) {
    return typeCoercerForTypeUnchecked(typeToken, TypeCoercer::getOutputType)
        .checkOutputAssignableTo(typeToken);
  }

  @Override
  public ParamsInfo paramInfos(ConstructorArg constructorArg) {
    if (constructorArg instanceof SkylarkDescriptionArgFactory) {
      return ((SkylarkDescriptionArgFactory) constructorArg).getAllParamInfo();
    }
    return getNativeConstructorArgDescriptor(constructorArg.getClass()).getParamsInfo();
  }

  @SuppressWarnings("unchecked")
  private <T> TypeCoercer<?, ?> typeCoercerForTypeUnchecked(
      TypeToken<T> typeToken, Function<TypeCoercer<?, ?>, TypeToken<?>> typeToMatchOnCoercer) {
    Type type = typeToken.getType();
    if (type instanceof TypeVariable) {
      type = ((TypeVariable<?>) type).getBounds()[0];
      if (Object.class.equals(type)) {
        throw new IllegalArgumentException("Generic types must be specific: " + type);
      }
    }

    if (type instanceof WildcardType) {
      type = ((WildcardType) type).getUpperBounds()[0];
      if (Object.class.equals(type)) {
        throw new IllegalArgumentException("Generic types must be specific: " + type);
      }
    }

    if (type instanceof Class) {
      Class<?> rawClass = Primitives.wrap((Class<?>) type);

      if (rawClass.isEnum()) {
        return enumCoercer(rawClass);
      }

      TypeCoercer<Object, ?> selectedTypeCoercer = null;
      for (TypeCoercer<Object, ?> typeCoercer : nonParameterizedTypeCoercers) {
        TypeToken<?> needle = typeToMatchOnCoercer.apply(typeCoercer);
        if (rawClass.isAssignableFrom(needle.getRawType())) {
          if (selectedTypeCoercer == null) {
            selectedTypeCoercer = typeCoercer;
          } else {
            throw new IllegalArgumentException("multiple coercers matched for type: " + type);
          }
        }
      }
      if (selectedTypeCoercer == null
          && DataTransferObject.class.isAssignableFrom(rawClass)
          && Types.getSupertypes(rawClass).stream()
              .anyMatch(c -> c.getAnnotation(RuleArg.class) != null)) {
        selectedTypeCoercer =
            new ImmutableTypeCoercer<>(
                getNativeConstructorArgDescriptor((Class<? extends DataTransferObject>) rawClass));
      }
      if (selectedTypeCoercer != null) {
        return selectedTypeCoercer;
      } else {
        throw new IllegalArgumentException("no type coercer for type: " + type);
      }
    } else if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;
      return typeCoercerForParameterizedType(
          typeToMatchOnCoercer,
          type.toString(),
          parameterizedType.getRawType(),
          parameterizedType.getActualTypeArguments());
    } else {
      throw new IllegalArgumentException("Cannot create type coercer for type: " + type);
    }
  }

  @SuppressWarnings("unchecked")
  private <E extends Enum<E>> TypeCoercer<?, ?> enumCoercer(Class<?> rawClass) {
    return new EnumTypeCoercer<>((Class<E>) rawClass);
  }

  private TypeCoercer<?, ?> typeCoercerForParameterizedType(
      Function<TypeCoercer<?, ?>, TypeToken<?>> typeToMatchOnCoercer,
      String typeName,
      Type rawType,
      Type[] actualTypeArguments) {
    if (!(rawType instanceof Class<?>)) {
      throw new RuntimeException("expected raw type to be a class for type: " + typeName);
    }

    Class<?> rawClass = (Class<?>) rawType;
    if (rawClass.equals(Either.class)) {
      Preconditions.checkState(
          actualTypeArguments.length == 2, "expected type '%s' to have two parameters", typeName);
      return new EitherTypeCoercer<>(
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[0]), typeToMatchOnCoercer),
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[1]), typeToMatchOnCoercer));
    } else if (rawClass.equals(Pair.class)) {
      Preconditions.checkState(
          actualTypeArguments.length == 2, "expected type '%s' to have two parameters", typeName);
      return new PairTypeCoercer<>(
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[0]), typeToMatchOnCoercer),
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[1]), typeToMatchOnCoercer));
    } else if (rawClass.isAssignableFrom(ImmutableList.class)) {
      return new ListTypeCoercer<>(
          typeCoercerForTypeUnchecked(
              TypeToken.of(getSingletonTypeParameter(typeName, actualTypeArguments)),
              typeToMatchOnCoercer));
    } else if (rawClass.isAssignableFrom(ImmutableSet.class)) {
      return new SetTypeCoercer<>(
          typeCoercerForTypeUnchecked(
              TypeToken.of(getSingletonTypeParameter(typeName, actualTypeArguments)),
              typeToMatchOnCoercer));
    } else if (rawClass.isAssignableFrom(ImmutableSortedSet.class)) {
      // SortedSet is tested second because it is a subclass of Set, and therefore can
      // be assigned to something of type Set, but not vice versa.
      Type elementType = getSingletonTypeParameter(typeName, actualTypeArguments);
      @SuppressWarnings({"rawtypes", "unchecked"})
      SortedSetTypeCoercer<?, ?> sortedSetTypeCoercer =
          new SortedSetTypeCoercer(
              typeCoercerForComparableType(TypeToken.of(elementType), typeToMatchOnCoercer));
      return sortedSetTypeCoercer;
    } else if (rawClass.isAssignableFrom(ImmutableMap.class)) {
      Preconditions.checkState(
          actualTypeArguments.length == 2, "expected type '%s' to have two parameters", typeName);
      return new MapTypeCoercer<>(
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[0]), typeToMatchOnCoercer),
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[1]), typeToMatchOnCoercer));
    } else if (rawClass.isAssignableFrom(ImmutableSortedMap.class)) {
      Preconditions.checkState(
          actualTypeArguments.length == 2, "expected type '%s' to have two parameters", typeName);
      @SuppressWarnings({"rawtypes", "unchecked"})
      SortedMapTypeCoercer<?, ?, ?, ?> sortedMapTypeCoercer =
          new SortedMapTypeCoercer(
              typeCoercerForComparableType(
                  TypeToken.of(actualTypeArguments[0]), typeToMatchOnCoercer),
              typeCoercerForTypeUnchecked(
                  TypeToken.of(actualTypeArguments[1]), typeToMatchOnCoercer));
      return sortedMapTypeCoercer;
    } else if (rawClass.isAssignableFrom(PatternMatchedCollection.class)) {
      return new PatternMatchedCollectionTypeCoercer<>(
          patternTypeCoercer,
          typeCoercerForTypeUnchecked(
              TypeToken.of(getSingletonTypeParameter(typeName, actualTypeArguments)),
              typeToMatchOnCoercer));
    } else if (rawClass.isAssignableFrom(VersionMatchedCollection.class)) {
      return new VersionMatchedCollectionTypeCoercer<>(
          new MapTypeCoercer<>(
              new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer),
              new VersionTypeCoercer()),
          typeCoercerForTypeUnchecked(
              TypeToken.of(getSingletonTypeParameter(typeName, actualTypeArguments)),
              typeToMatchOnCoercer));
    } else if (rawClass.isAssignableFrom(Optional.class)) {
      return new OptionalTypeCoercer<>(
          typeCoercerForTypeUnchecked(
              TypeToken.of(getSingletonTypeParameter(typeName, actualTypeArguments)),
              typeToMatchOnCoercer));
    } else {
      throw new IllegalArgumentException("Unhandled type: " + typeName);
    }
  }

  @Override
  public <T extends DataTransferObject>
      DataTransferObjectDescriptor<T> getNativeConstructorArgDescriptor(Class<T> dtoType) {
    return coercedTypeCache.getConstructorArgDescriptor(dtoType);
  }

  private TypeCoercer<?, ?> typeCoercerForComparableType(
      TypeToken<?> type, Function<TypeCoercer<?, ?>, TypeToken<?>> typeToMatchOnCoercer) {
    Preconditions.checkState(
        type.getType() instanceof Class
            && Comparable.class.isAssignableFrom((Class<?>) type.getType()),
        "type '%s' should be a class implementing Comparable",
        type);

    return typeCoercerForTypeUnchecked(type, typeToMatchOnCoercer);
  }

  private static Type getSingletonTypeParameter(String typeName, Type[] actualTypeArguments) {
    Preconditions.checkState(
        actualTypeArguments.length == 1, "expected type '%s' to have one parameter", typeName);
    return actualTypeArguments[0];
  }

  @VisibleForTesting
  CoercedTypeCache getCoercedTypeCache() {
    return coercedTypeCache;
  }
}
