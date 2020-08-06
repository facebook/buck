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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableUnconfiguredTargetNode;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.RuleDescriptor;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.param.CommonParamNames;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.rules.visibility.VisibilityDefiningPath;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.rules.visibility.parser.VisibilityPatterns;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Creates {@link UnconfiguredTargetNode} instances from raw data coming in form the {@link
 * ProjectBuildFileParser}.
 */
public class DefaultUnconfiguredTargetNodeFactory implements UnconfiguredTargetNodeFactory {

  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final BuiltTargetVerifier builtTargetVerifier;
  private final Cells cells;
  private final SelectorListFactory selectorListFactory;
  private final TypeCoercerFactory typeCoercerFactory;
  private final TypeCoercer<ImmutableList<UnflavoredBuildTarget>, ?> compatibleWithCoercer;
  private final TypeCoercer<UnflavoredBuildTarget, ?> defaultTargetPlatformCoercer;

  public DefaultUnconfiguredTargetNodeFactory(
      KnownRuleTypesProvider knownRuleTypesProvider,
      BuiltTargetVerifier builtTargetVerifier,
      Cells cells,
      SelectorListFactory selectorListFactory,
      TypeCoercerFactory typeCoercerFactory) {
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.builtTargetVerifier = builtTargetVerifier;
    this.cells = cells;
    this.selectorListFactory = selectorListFactory;
    this.typeCoercerFactory = typeCoercerFactory;
    this.compatibleWithCoercer =
        typeCoercerFactory
            .typeCoercerForType(new TypeToken<ImmutableList<UnflavoredBuildTarget>>() {})
            .checkUnconfiguredAssignableTo(
                new TypeToken<ImmutableList<UnflavoredBuildTarget>>() {});
    this.defaultTargetPlatformCoercer =
        typeCoercerFactory
            .typeCoercerForType(TypeToken.of(UnflavoredBuildTarget.class))
            .checkUnconfiguredAssignableTo(TypeToken.of(UnflavoredBuildTarget.class));
  }

  private TwoArraysImmutableHashMap<ParamName, Object> convertSelects(
      Cell cell,
      UnconfiguredBuildTarget target,
      RuleDescriptor<?> descriptor,
      RawTargetNode attrs,
      CellRelativePath pathRelativeToProjectRoot,
      DependencyStack dependencyStack) {
    DataTransferObjectDescriptor<?> constructorDescriptor =
        descriptor.dataTransferObjectDescriptor(typeCoercerFactory);

    return attrs
        .getAttrs()
        .mapValues(
            (k, v) -> {
              ParamInfo<?> paramInfo = constructorDescriptor.getParamsInfo().getByName(k);
              Preconditions.checkNotNull(
                  paramInfo, "cannot find param info for arg %s of target %s", k, target);
              return convertSelectorListInAttrValue(
                  cell, target, paramInfo, v, pathRelativeToProjectRoot, dependencyStack);
            });
  }

  /**
   * Convert attr value {@link ListWithSelects} to {@link SelectorList} or keep it as is otherwise
   */
  private Object convertSelectorListInAttrValue(
      Cell cell,
      UnconfiguredBuildTarget buildTarget,
      ParamInfo<?> paramInfo,
      Object attrValue,
      CellRelativePath pathRelativeToProjectRoot,
      DependencyStack dependencyStack) {
    ParamName attrName = paramInfo.getName();
    try {
      if (attrValue instanceof ListWithSelects) {
        if (!paramInfo.isConfigurable()) {
          throw new HumanReadableException(
              dependencyStack,
              "%s: attribute '%s' cannot be configured using select",
              buildTarget,
              attrName);
        }

        SelectorList<Object> selectorList =
            selectorListFactory.create(
                cells.getCell(pathRelativeToProjectRoot.getCellName()).getCellNameResolver(),
                pathRelativeToProjectRoot.getPath(),
                (ListWithSelects) attrValue);
        return selectorList.mapValuesThrowing(
            v ->
                paramInfo
                    .getTypeCoercer()
                    .coerceToUnconfigured(
                        cell.getCellNameResolver(),
                        cell.getFilesystem(),
                        pathRelativeToProjectRoot.getPath(),
                        v));
      } else {
        return paramInfo
            .getTypeCoercer()
            .coerceToUnconfigured(
                cell.getCellNameResolver(),
                cell.getFilesystem(),
                pathRelativeToProjectRoot.getPath(),
                attrValue);
      }
    } catch (CoerceFailedException e) {
      throw new HumanReadableException(e, dependencyStack, e.getMessage());
    }
  }

  @Override
  public UnconfiguredTargetNode create(
      Cell cell,
      AbsPath buildFile,
      UnconfiguredBuildTarget target,
      DependencyStack dependencyStack,
      RawTargetNode rawAttributes,
      Package pkg) {
    KnownRuleTypes knownRuleTypes = knownRuleTypesProvider.get(cell);
    RuleDescriptor<?> descriptor = parseRuleTypeFromRawRule(knownRuleTypes, rawAttributes);

    // Because of the way that the parser works, we know this can never return null.
    BaseDescription<?> description = descriptor.getDescription();

    builtTargetVerifier.verifyBuildTarget(
        cell, descriptor.getRuleType(), buildFile, target, description, rawAttributes);

    ImmutableSet<VisibilityPattern> visibilityPatterns =
        getProcessedVisibilityEntries(
            cell,
            buildFile,
            target,
            rawAttributes.getVisibility(),
            CommonParamNames.VISIBILITY,
            pkg.getVisibilityPatterns());

    ImmutableSet<VisibilityPattern> withinViewPatterns =
        getProcessedVisibilityEntries(
            cell,
            buildFile,
            target,
            rawAttributes.getWithinView(),
            CommonParamNames.WITHIN_VIEW,
            pkg.getWithinViewPatterns());

    TwoArraysImmutableHashMap<ParamName, Object> withSelects =
        convertSelects(
            cell,
            target,
            descriptor,
            rawAttributes,
            target.getCellRelativeBasePath(),
            dependencyStack);

    Optional<UnflavoredBuildTarget> defaultTargetPlatform = Optional.empty();
    Object rawDefaultTargetPlatform = rawAttributes.get(CommonParamNames.DEFAULT_TARGET_PLATFORM);
    // TODO(nga): old rules vs UDR rules mess
    if (rawDefaultTargetPlatform != null
        && !rawDefaultTargetPlatform.equals("")
        && !rawDefaultTargetPlatform.equals(Optional.empty())
        && !rawDefaultTargetPlatform.equals(Optional.of(""))) {
      if (rawDefaultTargetPlatform instanceof Optional<?>) {
        rawDefaultTargetPlatform = ((Optional<?>) rawDefaultTargetPlatform).get();
      }
      try {
        defaultTargetPlatform =
            Optional.of(
                defaultTargetPlatformCoercer.coerceToUnconfigured(
                    cell.getCellNameResolver(),
                    cell.getFilesystem(),
                    target.getCellRelativeBasePath().getPath(),
                    rawDefaultTargetPlatform));
      } catch (CoerceFailedException e) {
        throw new HumanReadableException(dependencyStack, e.getMessage(), e);
      }
    }

    ImmutableList<UnflavoredBuildTarget> compatibleWith = ImmutableList.of();

    Object rawCompatibleWith = rawAttributes.getAttrs().get(CommonParamNames.COMPATIBLE_WITH);
    if (rawCompatibleWith != null) {
      try {
        compatibleWith =
            compatibleWithCoercer.coerceToUnconfigured(
                cell.getCellNameResolver(),
                cell.getFilesystem(),
                target.getCellRelativeBasePath().getPath(),
                rawCompatibleWith);
      } catch (CoerceFailedException e) {
        throw new HumanReadableException(dependencyStack, e.getMessage(), e);
      }
    }

    return ImmutableUnconfiguredTargetNode.of(
        target.getUnflavoredBuildTarget(),
        descriptor.getRuleType(),
        withSelects,
        visibilityPatterns,
        withinViewPatterns,
        defaultTargetPlatform,
        compatibleWith);
  }

  private static RuleDescriptor<?> parseRuleTypeFromRawRule(
      KnownRuleTypes knownRuleTypes, RawTargetNode attributes) {
    return knownRuleTypes.getDescriptorByName(attributes.getBuckType());
  }

  private static ImmutableSet<VisibilityPattern> getProcessedVisibilityEntries(
      Cell cell,
      AbsPath buildFile,
      UnconfiguredBuildTarget target,
      ImmutableList<String> targetEntries,
      ParamName entryType,
      ImmutableSet<VisibilityPattern> packageEntries) {
    ArrayList<String> entries = new ArrayList<>(targetEntries);
    boolean addPackageEntries = entries.remove(Package.EXTEND_PACKAGE);

    ImmutableSet<VisibilityPattern> entryPatterns =
        VisibilityPatterns.createFromStringList(
            cell.getCellPathResolver(),
            entryType.getSnakeCase(),
            entries,
            VisibilityDefiningPath.of(
                ForwardRelativePath.ofRelPath(cell.getRoot().relativize(buildFile.getPath())),
                true),
            target::getFullyQualifiedName);

    if (entryPatterns.isEmpty() || addPackageEntries) {
      entryPatterns =
          ImmutableSet.<VisibilityPattern>builder()
              .addAll(entryPatterns)
              .addAll(packageEntries)
              .build();
    }
    return entryPatterns;
  }
}
