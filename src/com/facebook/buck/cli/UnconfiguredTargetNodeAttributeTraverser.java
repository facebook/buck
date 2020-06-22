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

package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.RuleDescriptor;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.ParamsInfo;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** A class which is capable of traversing through attributes on {code UnconfiguredTargetNode}s */
public class UnconfiguredTargetNodeAttributeTraverser {

  private final Cell rootCell;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final TypeCoercerFactory typeCoercerFactory;

  public UnconfiguredTargetNodeAttributeTraverser(
      Cell rootCell,
      KnownRuleTypesProvider knownRuleTypesProvider,
      TypeCoercerFactory typeCoercerFactory) {
    this.rootCell = rootCell;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.typeCoercerFactory = typeCoercerFactory;
  }

  /**
   * Traverses through all the attributes on the node, invoking the relevant consumer each time we
   * encounter a build target / path.
   *
   * <p>Since the consumer likely needs to handle targets/paths differently based on which param
   * they are a part of, this method actually takes a {@link Function} which returns the consumer
   * that will be used later on. These functions will only be invoked once per param, and the
   * consumer they return will be invoked multiple times depending on how many targets / paths are
   * found.
   *
   * @param node The node to traverse
   * @param buildTargetConsumerForParam A function that takes the current param as input and returns
   *     a consumer to be invoked on every build target in that param
   * @param pathConsumerForParam A function that takes the current param as input and returns a
   *     consumer to be invoked on every path in that param
   */
  @SuppressWarnings("unchecked")
  public void traverseAttributes(
      UnconfiguredTargetNode node,
      Function<ParamInfo<?>, Consumer<UnconfiguredBuildTarget>> buildTargetConsumerForParam,
      Function<ParamInfo<?>, Consumer<CellRelativePath>> pathConsumerForParam) {

    CanonicalCellName cellName = node.getBuildTarget().getCell();
    TwoArraysImmutableHashMap<ParamName, Object> attributes = node.getAttributes();
    ParamsInfo paramsInfo = lookupParamsInfoForNode(node);

    for (ParamName name : attributes.keySet()) {
      ParamInfo<?> info = paramsInfo.getByName(name);

      Consumer<UnconfiguredBuildTarget> buildTargetConsumer =
          buildTargetConsumerForParam.apply(info);
      Consumer<CellRelativePath> pathConsumer = pathConsumerForParam.apply(info);

      TypeCoercer<Object, ?> coercer = (TypeCoercer<Object, ?>) info.getTypeCoercer();
      Object value = attributes.get(name);
      // `selects` play a bit of a funny role, because our `ParamsInfo` _says_ that an attribute
      // should be of type X, but instead it's a `SelectorList<X>`. Handle this case explicitly.
      if (value instanceof SelectorList) {
        SelectorList<Object> valueAsSelectorList = (SelectorList<Object>) value;
        valueAsSelectorList.traverseSelectors(
            (selectorKey, selectorValue) -> {
              selectorKey
                  .getBuildTarget()
                  .ifPresent(
                      target -> {
                        buildTargetConsumer.accept(target.getUnconfiguredBuildTarget());
                      });
              consumeAttributeValue(
                  cellName, buildTargetConsumer, pathConsumer, coercer, selectorValue);
            });
      } else {
        consumeAttributeValue(cellName, buildTargetConsumer, pathConsumer, coercer, value);
      }
    }
  }

  /**
   * Traverses through the value of the {@code attribute} attribute on {@code node}, returning a set
   * of all objects for which {@code predicate} returns true.
   */
  @SuppressWarnings("unchecked")
  public ImmutableSet<Object> traverseAndCollectMatchingObjects(
      UnconfiguredTargetNode node, ParamName attribute, Predicate<Object> predicate) {
    Object value = node.getAttributes().get(attribute);
    if (value == null) {
      // Ignore if the field does not exist on this target.
      return ImmutableSet.of();
    }

    ParamInfo<?> info = lookupParamsInfoForNode(node).getByName(attribute);
    Preconditions.checkState(
        info != null,
        "Attributes not supported by the rule shouldn't have a value: %s + %s",
        node.getRuleType(),
        attribute);

    TypeCoercer<Object, ?> coercer = (TypeCoercer<Object, ?>) info.getTypeCoercer();
    ImmutableSet.Builder<Object> result = ImmutableSet.builder();

    SelectorList.traverseSelectorListValuesOrValue(
        value,
        v ->
            coercer.traverseUnconfigured(
                rootCell.getCellNameResolver(),
                v,
                object -> {
                  if (predicate.test(object)) {
                    result.add(object);
                  }
                }));
    return result.build();
  }

  private void consumeAttributeValue(
      CanonicalCellName currentCellName,
      Consumer<UnconfiguredBuildTarget> buildTargetConsumer,
      Consumer<CellRelativePath> pathConsumer,
      TypeCoercer<Object, ?> coercer,
      Object value) {
    coercer.traverseUnconfigured(
        rootCell.getCellNameResolver(),
        value,
        object -> {
          if (object instanceof UnconfiguredBuildTarget) {
            buildTargetConsumer.accept((UnconfiguredBuildTarget) object);
          } else if (object instanceof UnflavoredBuildTarget) {
            buildTargetConsumer.accept(UnconfiguredBuildTarget.of((UnflavoredBuildTarget) object));
          } else if (object instanceof UnconfiguredSourcePath) {
            ((UnconfiguredSourcePath) object)
                .match(
                    new UnconfiguredSourcePath.Matcher<Unit>() {
                      @Override
                      public Unit path(CellRelativePath path) {
                        pathConsumer.accept(path);
                        return Unit.UNIT;
                      }

                      @Override
                      public Unit buildTarget(
                          UnconfiguredBuildTargetWithOutputs targetWithOutputs) {
                        buildTargetConsumer.accept(targetWithOutputs.getBuildTarget());
                        return Unit.UNIT;
                      }
                    });
          } else if (object instanceof Path) {
            ForwardRelativePath path = ForwardRelativePath.ofPath((Path) object);
            pathConsumer.accept(CellRelativePath.of(currentCellName, path));
          }
        });
  }

  private ParamsInfo lookupParamsInfoForNode(UnconfiguredTargetNode node) {
    return lookupParamsInfoForRule(node.getBuildTarget(), node.getRuleType());
  }

  private ParamsInfo lookupParamsInfoForRule(UnflavoredBuildTarget buildTarget, RuleType ruleType) {
    Cell cell = rootCell.getCell(buildTarget.getCell());
    KnownRuleTypes knownRuleTypes = knownRuleTypesProvider.get(cell);
    RuleDescriptor<?> descriptor = knownRuleTypes.getDescriptorByName(ruleType.getName());
    return typeCoercerFactory
        .getNativeConstructorArgDescriptor(descriptor.getConstructorArgType())
        .getParamsInfo();
  }
}
