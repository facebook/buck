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

import com.facebook.buck.core.description.arg.DataTransferObject;
import com.facebook.buck.core.linkgroup.CxxLinkGroupMapping;
import com.facebook.buck.core.linkgroup.CxxLinkGroupMappingTarget;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcher;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.rules.macros.AbsoluteOutputMacro;
import com.facebook.buck.rules.macros.CcFlagsMacro;
import com.facebook.buck.rules.macros.CcMacro;
import com.facebook.buck.rules.macros.ClasspathAbiMacro;
import com.facebook.buck.rules.macros.ClasspathMacro;
import com.facebook.buck.rules.macros.CppFlagsMacro;
import com.facebook.buck.rules.macros.CxxFlagsMacro;
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
import com.facebook.buck.rules.macros.WorkerMacro;
import com.facebook.buck.rules.query.Query;
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
import java.util.regex.Pattern;

/**
 * Create {@link TypeCoercer}s that can convert incoming java structures (from json) into particular
 * types.
 */
public class DefaultTypeCoercerFactory implements TypeCoercerFactory {

  private final CoercedTypeCache coercedTypeCache = new CoercedTypeCache(this);

  private final TypeCoercer<UnconfiguredBuildTarget, UnconfiguredBuildTarget>
      unconfiguredBuildTargetTypeCoercer;
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
    BuildTargetTypeCoercer buildTargetTypeCoercer =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer);
    BuildTargetWithOutputsTypeCoercer buildTargetWithOutputsTypeCoercer =
        new BuildTargetWithOutputsTypeCoercer(
            new UnconfiguredBuildTargetWithOutputsTypeCoercer(unconfiguredBuildTargetTypeCoercer));
    TypeCoercer<Path, Path> pathTypeCoercer = new PathTypeCoercer();
    TypeCoercer<UnconfiguredSourcePath, SourcePath> sourcePathTypeCoercer =
        new SourcePathTypeCoercer(buildTargetWithOutputsTypeCoercer, pathTypeCoercer);
    TypeCoercer<Object, SourceWithFlags> sourceWithFlagsTypeCoercer =
        new SourceWithFlagsTypeCoercer(
            sourcePathTypeCoercer, new ListTypeCoercer<>(stringTypeCoercer));
    TypeCoercer<Integer, Integer> intTypeCoercer = new NumberTypeCoercer<>(Integer.class);
    TypeCoercer<Double, Double> doubleTypeCoercer = new NumberTypeCoercer<>(Double.class);
    TypeCoercer<Boolean, Boolean> booleanTypeCoercer = new IdentityTypeCoercer<>(Boolean.class);
    TypeCoercer<Object, NeededCoverageSpec> neededCoverageSpecTypeCoercer =
        new NeededCoverageSpecTypeCoercer(
            intTypeCoercer, buildTargetTypeCoercer, stringTypeCoercer);
    TypeCoercer<Object, Query> queryTypeCoercer =
        new QueryCoercer(this, unconfiguredBuildTargetFactory);
    TypeCoercer<ImmutableList<UnconfiguredBuildTarget>, ImmutableList<BuildTarget>>
        buildTargetsTypeCoercer = new ListTypeCoercer<>(buildTargetTypeCoercer);
    TypeCoercer<Object, CxxLinkGroupMappingTarget.Traversal> linkGroupMappingTraversalCoercer =
        new CxxLinkGroupMappingTargetTraversalCoercer();
    TypeCoercer<Object, CxxLinkGroupMappingTarget> linkGroupMappingTargetCoercer =
        new CxxLinkGroupMappingTargetCoercer(
            buildTargetTypeCoercer, linkGroupMappingTraversalCoercer, patternTypeCoercer);
    TypeCoercer<ImmutableList<Object>, ImmutableList<CxxLinkGroupMappingTarget>>
        linkGroupMappingTargetsCoercer = new ListTypeCoercer<>(linkGroupMappingTargetCoercer);
    TypeCoercer<Object, CxxLinkGroupMapping> linkGroupMappingCoercer =
        new CxxLinkGroupMappingCoercer(stringTypeCoercer, linkGroupMappingTargetsCoercer);
    TypeCoercer<Object, StringWithMacros> stringWithMacrosCoercer =
        StringWithMacrosTypeCoercer.builder()
            .put(
                "classpath",
                ClasspathMacro.class,
                new BuildTargetMacroTypeCoercer<>(
                    buildTargetWithOutputsTypeCoercer,
                    ClasspathMacro.class,
                    BuildTargetMacroTypeCoercer.TargetOrHost.TARGET,
                    ClasspathMacro::of))
            .put(
                "classpath_abi",
                ClasspathAbiMacro.class,
                new BuildTargetMacroTypeCoercer<>(
                    buildTargetWithOutputsTypeCoercer,
                    ClasspathAbiMacro.class,
                    BuildTargetMacroTypeCoercer.TargetOrHost.TARGET,
                    ClasspathAbiMacro::of))
            .put(
                "exe",
                ExecutableMacro.class,
                new BuildTargetMacroTypeCoercer<>(
                    buildTargetWithOutputsTypeCoercer,
                    ExecutableMacro.class,
                    // TODO(nga): switch to host
                    BuildTargetMacroTypeCoercer.TargetOrHost.TARGET,
                    ExecutableMacro::of))
            .put(
                "exe_target",
                ExecutableTargetMacro.class,
                new BuildTargetMacroTypeCoercer<>(
                    buildTargetWithOutputsTypeCoercer,
                    ExecutableTargetMacro.class,
                    BuildTargetMacroTypeCoercer.TargetOrHost.TARGET,
                    ExecutableTargetMacro::of))
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
                    MavenCoordinatesMacro.class,
                    BuildTargetMacroTypeCoercer.TargetOrHost.TARGET,
                    MavenCoordinatesMacro::of))
            .put("output", OutputMacro.class, new OutputMacroTypeCoercer())
            .put("abs_output", AbsoluteOutputMacro.class, new AbsoluteOutputMacroTypeCoercer())
            .put(
                "query_targets",
                QueryTargetsMacro.class,
                new QueryMacroTypeCoercer<>(
                    queryTypeCoercer, QueryTargetsMacro.class, QueryTargetsMacro::of))
            .put(
                "query_outputs",
                QueryOutputsMacro.class,
                new QueryMacroTypeCoercer<>(
                    queryTypeCoercer, QueryOutputsMacro.class, QueryOutputsMacro::of))
            .put(
                "query_paths",
                QueryPathsMacro.class,
                new QueryMacroTypeCoercer<>(
                    queryTypeCoercer, QueryPathsMacro.class, QueryPathsMacro::of))
            .put(
                "query_targets_and_outputs",
                QueryTargetsAndOutputsMacro.class,
                new QueryTargetsAndOutputsMacroTypeCoercer(queryTypeCoercer))
            .put(
                "worker",
                WorkerMacro.class,
                new BuildTargetMacroTypeCoercer<>(
                    buildTargetWithOutputsTypeCoercer,
                    WorkerMacro.class,
                    BuildTargetMacroTypeCoercer.TargetOrHost.TARGET,
                    WorkerMacro::of))
            .put("cc", CcMacro.class, new ZeroArgMacroTypeCoercer<>(CcMacro.class, CcMacro.of()))
            .put(
                "cflags",
                CcFlagsMacro.class,
                new ZeroArgMacroTypeCoercer<>(CcFlagsMacro.class, CcFlagsMacro.of()))
            .put(
                "cppflags",
                CppFlagsMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.empty(),
                    buildTargetsTypeCoercer,
                    CppFlagsMacro.class,
                    CppFlagsMacro::of))
            .put(
                "cxx", CxxMacro.class, new ZeroArgMacroTypeCoercer<>(CxxMacro.class, CxxMacro.of()))
            .put(
                "cxxflags",
                CxxFlagsMacro.class,
                new ZeroArgMacroTypeCoercer<>(CxxFlagsMacro.class, CxxFlagsMacro.of()))
            .put(
                "cxxppflags",
                CxxppFlagsMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.empty(),
                    buildTargetsTypeCoercer,
                    CxxppFlagsMacro.class,
                    CxxppFlagsMacro::of))
            .put("ld", LdMacro.class, new ZeroArgMacroTypeCoercer<>(LdMacro.class, LdMacro.of()))
            .put(
                "ldflags-shared",
                LdflagsSharedMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.empty(),
                    buildTargetsTypeCoercer,
                    LdflagsSharedMacro.class,
                    LdflagsSharedMacro::of))
            .put(
                "ldflags-shared-filter",
                LdflagsSharedFilterMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.of(patternTypeCoercer),
                    buildTargetsTypeCoercer,
                    LdflagsSharedFilterMacro.class,
                    LdflagsSharedFilterMacro::of))
            .put(
                "ldflags-static",
                LdflagsStaticMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.empty(),
                    buildTargetsTypeCoercer,
                    LdflagsStaticMacro.class,
                    LdflagsStaticMacro::of))
            .put(
                "ldflags-static-filter",
                LdflagsStaticFilterMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.of(patternTypeCoercer),
                    buildTargetsTypeCoercer,
                    LdflagsStaticFilterMacro.class,
                    LdflagsStaticFilterMacro::of))
            .put(
                "ldflags-static-pic",
                LdflagsStaticPicMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.empty(),
                    buildTargetsTypeCoercer,
                    LdflagsStaticPicMacro.class,
                    LdflagsStaticPicMacro::of))
            .put(
                "ldflags-static-pic-filter",
                LdflagsStaticPicFilterMacro.class,
                new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                    Optional.of(patternTypeCoercer),
                    buildTargetsTypeCoercer,
                    LdflagsStaticPicFilterMacro.class,
                    LdflagsStaticPicFilterMacro::of))
            .put(
                "platform-name",
                PlatformNameMacro.class,
                new ZeroArgMacroTypeCoercer<>(PlatformNameMacro.class, PlatformNameMacro.of()))
            .build();
    nonParameterizedTypeCoercers =
        (TypeCoercer<Object, ?>[])
            new TypeCoercer<?, ?>[] {
              // special classes
              pathTypeCoercer,
              flavorTypeCoercer,
              sourcePathTypeCoercer,
              unconfiguredBuildTargetTypeCoercer,
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
              new SourceWithFlagsListTypeCoercer(stringTypeCoercer, sourceWithFlagsTypeCoercer),
              new SourceSetTypeCoercer(stringTypeCoercer, sourcePathTypeCoercer),
              new SourceSortedSetTypeCoercer(stringTypeCoercer, sourcePathTypeCoercer),
              new LogLevelTypeCoercer(),
              new ManifestEntriesTypeCoercer(),
              patternTypeCoercer,
              neededCoverageSpecTypeCoercer,
              new ConstraintTypeCoercer(),
              new VersionTypeCoercer(),
              queryTypeCoercer,
              stringWithMacrosCoercer,
              new TestRunnerSpecCoercer(stringWithMacrosCoercer),
            };
  }

  @Override
  public <T> TypeCoercer<?, T> typeCoercerForType(TypeToken<T> typeToken) {
    return typeCoercerForTypeUnchecked(typeToken).checkOutputAssignableTo(typeToken);
  }

  @SuppressWarnings("unchecked")
  private <T> TypeCoercer<?, ?> typeCoercerForTypeUnchecked(TypeToken<T> typeToken) {
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
        if (rawClass.isAssignableFrom(typeCoercer.getOutputType().getRawType())) {
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
                getConstructorArgDescriptor((Class<? extends DataTransferObject>) rawClass));
      }
      if (selectedTypeCoercer != null) {
        return selectedTypeCoercer;
      } else {
        throw new IllegalArgumentException("no type coercer for type: " + type);
      }
    } else if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;
      return typeCoercerForParameterizedType(
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
      String typeName, Type rawType, Type[] actualTypeArguments) {
    if (!(rawType instanceof Class<?>)) {
      throw new RuntimeException("expected raw type to be a class for type: " + typeName);
    }

    Class<?> rawClass = (Class<?>) rawType;
    if (rawClass.equals(Either.class)) {
      Preconditions.checkState(
          actualTypeArguments.length == 2, "expected type '%s' to have two parameters", typeName);
      return new EitherTypeCoercer<>(
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[0])),
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[1])));
    } else if (rawClass.equals(Pair.class)) {
      Preconditions.checkState(
          actualTypeArguments.length == 2, "expected type '%s' to have two parameters", typeName);
      return new PairTypeCoercer<>(
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[0])),
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[1])));
    } else if (rawClass.isAssignableFrom(ImmutableList.class)) {
      return new ListTypeCoercer<>(
          typeCoercerForTypeUnchecked(
              TypeToken.of(getSingletonTypeParameter(typeName, actualTypeArguments))));
    } else if (rawClass.isAssignableFrom(ImmutableSet.class)) {
      return new SetTypeCoercer<>(
          typeCoercerForTypeUnchecked(
              TypeToken.of(getSingletonTypeParameter(typeName, actualTypeArguments))));
    } else if (rawClass.isAssignableFrom(ImmutableSortedSet.class)) {
      // SortedSet is tested second because it is a subclass of Set, and therefore can
      // be assigned to something of type Set, but not vice versa.
      Type elementType = getSingletonTypeParameter(typeName, actualTypeArguments);
      @SuppressWarnings({"rawtypes", "unchecked"})
      SortedSetTypeCoercer<?, ?> sortedSetTypeCoercer =
          new SortedSetTypeCoercer(typeCoercerForComparableType(TypeToken.of(elementType)));
      return sortedSetTypeCoercer;
    } else if (rawClass.isAssignableFrom(ImmutableMap.class)) {
      Preconditions.checkState(
          actualTypeArguments.length == 2, "expected type '%s' to have two parameters", typeName);
      return new MapTypeCoercer<>(
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[0])),
          typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[1])));
    } else if (rawClass.isAssignableFrom(ImmutableSortedMap.class)) {
      Preconditions.checkState(
          actualTypeArguments.length == 2, "expected type '%s' to have two parameters", typeName);
      @SuppressWarnings({"rawtypes", "unchecked"})
      SortedMapTypeCoercer<?, ?, ?, ?> sortedMapTypeCoercer =
          new SortedMapTypeCoercer(
              typeCoercerForComparableType(TypeToken.of(actualTypeArguments[0])),
              typeCoercerForTypeUnchecked(TypeToken.of(actualTypeArguments[1])));
      return sortedMapTypeCoercer;
    } else if (rawClass.isAssignableFrom(PatternMatchedCollection.class)) {
      return new PatternMatchedCollectionTypeCoercer<>(
          patternTypeCoercer,
          typeCoercerForTypeUnchecked(
              TypeToken.of(getSingletonTypeParameter(typeName, actualTypeArguments))));
    } else if (rawClass.isAssignableFrom(VersionMatchedCollection.class)) {
      return new VersionMatchedCollectionTypeCoercer<>(
          new MapTypeCoercer<>(
              new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer),
              new VersionTypeCoercer()),
          typeCoercerForTypeUnchecked(
              TypeToken.of(getSingletonTypeParameter(typeName, actualTypeArguments))));
    } else if (rawClass.isAssignableFrom(Optional.class)) {
      return new OptionalTypeCoercer<>(
          typeCoercerForTypeUnchecked(
              TypeToken.of(getSingletonTypeParameter(typeName, actualTypeArguments))));
    } else {
      throw new IllegalArgumentException("Unhandled type: " + typeName);
    }
  }

  @Override
  public <T extends DataTransferObject> DataTransferObjectDescriptor<T> getConstructorArgDescriptor(
      Class<T> dtoType) {
    return coercedTypeCache.getConstructorArgDescriptor(dtoType);
  }

  private TypeCoercer<?, ?> typeCoercerForComparableType(TypeToken<?> type) {
    Preconditions.checkState(
        type.getType() instanceof Class
            && Comparable.class.isAssignableFrom((Class<?>) type.getType()),
        "type '%s' should be a class implementing Comparable",
        type);

    return typeCoercerForTypeUnchecked(type);
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
