/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.macros.CcFlagsMacro;
import com.facebook.buck.rules.macros.CcMacro;
import com.facebook.buck.rules.macros.ClasspathAbiMacro;
import com.facebook.buck.rules.macros.ClasspathMacro;
import com.facebook.buck.rules.macros.CppFlagsMacro;
import com.facebook.buck.rules.macros.CxxFlagsMacro;
import com.facebook.buck.rules.macros.CxxMacro;
import com.facebook.buck.rules.macros.CxxppFlagsMacro;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.LdMacro;
import com.facebook.buck.rules.macros.LdflagsSharedFilterMacro;
import com.facebook.buck.rules.macros.LdflagsSharedMacro;
import com.facebook.buck.rules.macros.LdflagsStaticFilterMacro;
import com.facebook.buck.rules.macros.LdflagsStaticMacro;
import com.facebook.buck.rules.macros.LdflagsStaticPicFilterMacro;
import com.facebook.buck.rules.macros.LdflagsStaticPicMacro;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MavenCoordinatesMacro;
import com.facebook.buck.rules.macros.OutputMacro;
import com.facebook.buck.rules.macros.PlatformNameMacro;
import com.facebook.buck.rules.macros.QueryOutputsMacro;
import com.facebook.buck.rules.macros.QueryPathsMacro;
import com.facebook.buck.rules.macros.QueryTargetsAndOutputsMacro;
import com.facebook.buck.rules.macros.QueryTargetsMacro;
import com.facebook.buck.rules.macros.WorkerMacro;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.util.Types;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.primitives.Primitives;
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

  private final PathTypeCoercer.PathExistenceVerificationMode pathExistenceVerificationMode;

  private final TypeCoercer<Pattern> patternTypeCoercer = new PatternTypeCoercer();

  private final TypeCoercer<?>[] nonParameterizedTypeCoercers;

  public DefaultTypeCoercerFactory() {
    this(PathTypeCoercer.PathExistenceVerificationMode.VERIFY);
  }

  public DefaultTypeCoercerFactory(
      PathTypeCoercer.PathExistenceVerificationMode pathExistenceVerificationMode) {
    this.pathExistenceVerificationMode = pathExistenceVerificationMode;
    TypeCoercer<String> stringTypeCoercer = new IdentityTypeCoercer<>(String.class);
    TypeCoercer<Flavor> flavorTypeCoercer = new FlavorTypeCoercer();
    // This has no implementation, but is here so that constructor succeeds so that it can be
    // queried. This is only used for the visibility field, which is not actually handled by the
    // coercer.
    TypeCoercer<BuildTargetPattern> buildTargetPatternTypeCoercer =
        new IdentityTypeCoercer<BuildTargetPattern>(BuildTargetPattern.class) {
          @Override
          public BuildTargetPattern coerce(
              CellPathResolver cellRoots,
              ProjectFilesystem filesystem,
              Path pathRelativeToProjectRoot,
              Object object) {
            // This is only actually used directly by ConstructorArgMarshaller, for parsing the
            // groups list. It's also queried (but not actually used) when Descriptions declare
            // deps fields.
            // TODO(csarbora): make this work for all types of BuildTargetPatterns
            // probably differentiate them by inheritance
            return BuildTargetPatternParser.forVisibilityArgument()
                .parse(cellRoots, (String) object);
          }
        };
    TypeCoercer<BuildTarget> buildTargetTypeCoercer = new BuildTargetTypeCoercer();
    PathTypeCoercer pathTypeCoercer = new PathTypeCoercer(pathExistenceVerificationMode);
    TypeCoercer<SourcePath> sourcePathTypeCoercer =
        new SourcePathTypeCoercer(buildTargetTypeCoercer, pathTypeCoercer);
    TypeCoercer<SourceWithFlags> sourceWithFlagsTypeCoercer =
        new SourceWithFlagsTypeCoercer(
            sourcePathTypeCoercer, new ListTypeCoercer<>(stringTypeCoercer));
    TypeCoercer<OcamlSource> ocamlSourceTypeCoercer =
        new OcamlSourceTypeCoercer(sourcePathTypeCoercer);
    TypeCoercer<Float> floatTypeCoercer = new NumberTypeCoercer<>(Float.class);
    TypeCoercer<NeededCoverageSpec> neededCoverageSpecTypeCoercer =
        new NeededCoverageSpecTypeCoercer(
            floatTypeCoercer, buildTargetTypeCoercer, stringTypeCoercer);
    TypeCoercer<Query> queryTypeCoercer = new QueryCoercer();
    TypeCoercer<ImmutableList<BuildTarget>> buildTargetsTypeCoercer =
        new ListTypeCoercer<>(buildTargetTypeCoercer);
    nonParameterizedTypeCoercers =
        new TypeCoercer<?>[] {
          // special classes
          pathTypeCoercer,
          flavorTypeCoercer,
          sourcePathTypeCoercer,
          buildTargetTypeCoercer,
          buildTargetPatternTypeCoercer,

          // identity
          stringTypeCoercer,
          new IdentityTypeCoercer<>(Boolean.class),

          // numeric
          new NumberTypeCoercer<>(Integer.class),
          new NumberTypeCoercer<>(Double.class),
          floatTypeCoercer,
          new NumberTypeCoercer<>(Long.class),
          new NumberTypeCoercer<>(Short.class),
          new NumberTypeCoercer<>(Byte.class),

          // other simple
          sourceWithFlagsTypeCoercer,
          ocamlSourceTypeCoercer,
          new BuildConfigFieldsTypeCoercer(),
          new UriTypeCoercer(),
          new FrameworkPathTypeCoercer(sourcePathTypeCoercer),
          new SourceWithFlagsListTypeCoercer(stringTypeCoercer, sourceWithFlagsTypeCoercer),
          new SourceListTypeCoercer(stringTypeCoercer, sourcePathTypeCoercer),
          new LogLevelTypeCoercer(),
          new ManifestEntriesTypeCoercer(),
          patternTypeCoercer,
          neededCoverageSpecTypeCoercer,
          new ConstraintTypeCoercer(),
          new VersionTypeCoercer(),
          queryTypeCoercer,
          StringWithMacrosTypeCoercer.from(
              ImmutableMap.<String, Class<? extends Macro>>builder()
                  .put("classpath", ClasspathMacro.class)
                  .put("classpath_abi", ClasspathAbiMacro.class)
                  .put("exe", ExecutableMacro.class)
                  .put("location", LocationMacro.class)
                  .put("maven_coords", MavenCoordinatesMacro.class)
                  .put("output", OutputMacro.class)
                  .put("query_targets", QueryTargetsMacro.class)
                  .put("query_outputs", QueryOutputsMacro.class)
                  .put("query_paths", QueryPathsMacro.class)
                  .put("query_targets_and_outputs", QueryTargetsAndOutputsMacro.class)
                  .put("worker", WorkerMacro.class)
                  .put("cc", CcMacro.class)
                  .put("cflags", CcFlagsMacro.class)
                  .put("cppflags", CppFlagsMacro.class)
                  .put("cxx", CxxMacro.class)
                  .put("cxxflags", CxxFlagsMacro.class)
                  .put("cxxppflags", CxxppFlagsMacro.class)
                  .put("ld", LdMacro.class)
                  .put("ldflags-shared", LdflagsSharedMacro.class)
                  .put("ldflags-shared-filter", LdflagsSharedFilterMacro.class)
                  .put("ldflags-static", LdflagsStaticMacro.class)
                  .put("ldflags-static-filter", LdflagsStaticFilterMacro.class)
                  .put("ldflags-static-pic", LdflagsStaticPicMacro.class)
                  .put("ldflags-static-pic-filter", LdflagsStaticPicFilterMacro.class)
                  .put("platform-name", PlatformNameMacro.class)
                  .build(),
              ImmutableList.of(
                  new BuildTargetMacroTypeCoercer<>(
                      buildTargetTypeCoercer, ClasspathMacro.class, ClasspathMacro::of),
                  new BuildTargetMacroTypeCoercer<>(
                      buildTargetTypeCoercer, ClasspathAbiMacro.class, ClasspathAbiMacro::of),
                  new BuildTargetMacroTypeCoercer<>(
                      buildTargetTypeCoercer, ExecutableMacro.class, ExecutableMacro::of),
                  new LocationMacroTypeCoercer(buildTargetTypeCoercer),
                  new BuildTargetMacroTypeCoercer<>(
                      buildTargetTypeCoercer,
                      MavenCoordinatesMacro.class,
                      MavenCoordinatesMacro::of),
                  new OutputMacroTypeCoercer(),
                  new QueryMacroTypeCoercer<>(
                      queryTypeCoercer, QueryTargetsMacro.class, QueryTargetsMacro::of),
                  new QueryMacroTypeCoercer<>(
                      queryTypeCoercer, QueryOutputsMacro.class, QueryOutputsMacro::of),
                  new QueryMacroTypeCoercer<>(
                      queryTypeCoercer, QueryPathsMacro.class, QueryPathsMacro::of),
                  new QueryTargetsAndOutputsMacroTypeCoercer(queryTypeCoercer),
                  new BuildTargetMacroTypeCoercer<>(
                      buildTargetTypeCoercer, WorkerMacro.class, WorkerMacro::of),
                  new ZeroArgMacroTypeCoercer<>(CcMacro.class, CcMacro.of()),
                  new ZeroArgMacroTypeCoercer<>(CcFlagsMacro.class, CcFlagsMacro.of()),
                  new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                      Optional.empty(),
                      buildTargetsTypeCoercer,
                      CppFlagsMacro.class,
                      CppFlagsMacro::of),
                  new ZeroArgMacroTypeCoercer<>(CxxMacro.class, CxxMacro.of()),
                  new ZeroArgMacroTypeCoercer<>(CxxFlagsMacro.class, CxxFlagsMacro.of()),
                  new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                      Optional.empty(),
                      buildTargetsTypeCoercer,
                      CxxppFlagsMacro.class,
                      CxxppFlagsMacro::of),
                  new ZeroArgMacroTypeCoercer<>(LdMacro.class, LdMacro.of()),
                  new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                      Optional.empty(),
                      buildTargetsTypeCoercer,
                      LdflagsSharedMacro.class,
                      LdflagsSharedMacro::of),
                  new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                      Optional.of(patternTypeCoercer),
                      buildTargetsTypeCoercer,
                      LdflagsSharedFilterMacro.class,
                      LdflagsSharedFilterMacro::of),
                  new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                      Optional.empty(),
                      buildTargetsTypeCoercer,
                      LdflagsStaticMacro.class,
                      LdflagsStaticMacro::of),
                  new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                      Optional.of(patternTypeCoercer),
                      buildTargetsTypeCoercer,
                      LdflagsStaticFilterMacro.class,
                      LdflagsStaticFilterMacro::of),
                  new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                      Optional.empty(),
                      buildTargetsTypeCoercer,
                      LdflagsStaticPicMacro.class,
                      LdflagsStaticPicMacro::of),
                  new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
                      Optional.of(patternTypeCoercer),
                      buildTargetsTypeCoercer,
                      LdflagsStaticPicFilterMacro.class,
                      LdflagsStaticPicFilterMacro::of),
                  new ZeroArgMacroTypeCoercer<>(PlatformNameMacro.class, PlatformNameMacro.of()))),
        };
  }

  @Override
  public TypeCoercer<?> typeCoercerForType(Type type) {
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
        return new EnumTypeCoercer<>(rawClass);
      }

      TypeCoercer<?> selectedTypeCoercer = null;
      for (TypeCoercer<?> typeCoercer : nonParameterizedTypeCoercers) {
        if (rawClass.isAssignableFrom(typeCoercer.getOutputClass())) {
          if (selectedTypeCoercer == null) {
            selectedTypeCoercer = typeCoercer;
          } else {
            throw new IllegalArgumentException("multiple coercers matched for type: " + type);
          }
        }
      }
      if (selectedTypeCoercer == null
          && Types.getSupertypes(rawClass)
              .stream()
              .anyMatch(c -> c.getAnnotation(BuckStyleImmutable.class) != null)) {
        selectedTypeCoercer =
            new ImmutableTypeCoercer<>(
                rawClass, CoercedTypeCache.INSTANCE.getAllParamInfo(this, rawClass).values());
      }
      if (selectedTypeCoercer != null) {
        return selectedTypeCoercer;
      } else {
        throw new IllegalArgumentException("no type coercer for type: " + type);
      }
    } else if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;

      Type rawType = parameterizedType.getRawType();
      if (!(rawType instanceof Class<?>)) {
        throw new RuntimeException(
            "expected getRawType() to return a class for type: " + parameterizedType);
      }

      Class<?> rawClass = (Class<?>) rawType;
      if (rawClass.equals(Either.class)) {
        Preconditions.checkState(
            parameterizedType.getActualTypeArguments().length == 2,
            "expected type '%s' to have two parameters",
            parameterizedType);
        return new EitherTypeCoercer<>(
            typeCoercerForType(parameterizedType.getActualTypeArguments()[0]),
            typeCoercerForType(parameterizedType.getActualTypeArguments()[1]));
      } else if (rawClass.equals(Pair.class)) {
        Preconditions.checkState(
            parameterizedType.getActualTypeArguments().length == 2,
            "expected type '%s' to have two parameters",
            parameterizedType);
        return new PairTypeCoercer<>(
            typeCoercerForType(parameterizedType.getActualTypeArguments()[0]),
            typeCoercerForType(parameterizedType.getActualTypeArguments()[1]));
      } else if (rawClass.isAssignableFrom(ImmutableList.class)) {
        return new ListTypeCoercer<>(
            typeCoercerForType(getSingletonTypeParameter(parameterizedType)));
      } else if (rawClass.isAssignableFrom(ImmutableSet.class)) {
        return new SetTypeCoercer<>(
            typeCoercerForType(getSingletonTypeParameter(parameterizedType)));
      } else if (rawClass.isAssignableFrom(ImmutableSortedSet.class)) {
        // SortedSet is tested second because it is a subclass of Set, and therefore can
        // be assigned to something of type Set, but not vice versa.
        Type elementType = getSingletonTypeParameter(parameterizedType);
        @SuppressWarnings({"rawtypes", "unchecked"})
        SortedSetTypeCoercer<?> sortedSetTypeCoercer =
            new SortedSetTypeCoercer(typeCoercerForComparableType(elementType));
        return sortedSetTypeCoercer;
      } else if (rawClass.isAssignableFrom(ImmutableMap.class)) {
        Preconditions.checkState(
            parameterizedType.getActualTypeArguments().length == 2,
            "expected type '%s' to have two parameters",
            parameterizedType);
        return new MapTypeCoercer<>(
            typeCoercerForType(parameterizedType.getActualTypeArguments()[0]),
            typeCoercerForType(parameterizedType.getActualTypeArguments()[1]));
      } else if (rawClass.isAssignableFrom(ImmutableSortedMap.class)) {
        Preconditions.checkState(
            parameterizedType.getActualTypeArguments().length == 2,
            "expected type '%s' to have two parameters",
            parameterizedType);
        @SuppressWarnings({"rawtypes", "unchecked"})
        SortedMapTypeCoercer<?, ?> sortedMapTypeCoercer =
            new SortedMapTypeCoercer(
                typeCoercerForComparableType(parameterizedType.getActualTypeArguments()[0]),
                typeCoercerForType(parameterizedType.getActualTypeArguments()[1]));
        return sortedMapTypeCoercer;
      } else if (rawClass.isAssignableFrom(PatternMatchedCollection.class)) {
        return new PatternMatchedCollectionTypeCoercer<>(
            patternTypeCoercer, typeCoercerForType(getSingletonTypeParameter(parameterizedType)));
      } else if (rawClass.isAssignableFrom(VersionMatchedCollection.class)) {
        return new VersionMatchedCollectionTypeCoercer<>(
            new MapTypeCoercer<>(new BuildTargetTypeCoercer(), new VersionTypeCoercer()),
            typeCoercerForType(getSingletonTypeParameter(parameterizedType)));
      } else if (rawClass.isAssignableFrom(Optional.class)) {
        return new OptionalTypeCoercer<>(
            typeCoercerForType(getSingletonTypeParameter(parameterizedType)));
      } else {
        throw new IllegalArgumentException("Unhandled type: " + type);
      }
    } else {
      throw new IllegalArgumentException("Cannot create type coercer for type: " + type);
    }
  }

  private <T extends Comparable<T>> TypeCoercer<T> typeCoercerForComparableType(Type type) {
    Preconditions.checkState(
        type instanceof Class && Comparable.class.isAssignableFrom((Class<?>) type),
        "type '%s' should be a class implementing Comparable",
        type);

    @SuppressWarnings("unchecked")
    TypeCoercer<T> typeCoercer = (TypeCoercer<T>) typeCoercerForType(type);
    return typeCoercer;
  }

  private static Type getSingletonTypeParameter(ParameterizedType type) {
    Preconditions.checkState(
        type.getActualTypeArguments().length == 1,
        "expected type '%s' to have one parameter",
        type);
    return type.getActualTypeArguments()[0];
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof DefaultTypeCoercerFactory)) {
      return false;
    }
    return pathExistenceVerificationMode
        == ((DefaultTypeCoercerFactory) obj).pathExistenceVerificationMode;
  }

  @Override
  public int hashCode() {
    return pathExistenceVerificationMode.hashCode();
  }
}
