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

import static com.facebook.buck.util.concurrent.MoreFutures.propagateCauseIfInstanceOf;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.filesystems.AbsPath;
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
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversalWithPayload;
import com.facebook.buck.core.util.graph.CycleException;
import com.facebook.buck.core.util.graph.GraphTraversableWithPayload;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.graph.TraversableGraph;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserMessages;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.UnconfiguredQueryBuildTarget;
import com.facebook.buck.query.UnconfiguredQueryTarget;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.ParamsInfo;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.param.CommonParamNames;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import org.immutables.value.Value;

/**
 * This class is an approximation of a {@code TargetGraph} except that it is Unconfigured.
 *
 * <p>Generally Buck doesn't need to create an explicit graph of {@code UnconfiguredTargetNode}s,
 * instead skipping straight to creating a configured graph (aka one of {@code TargetNode}s). This
 * class mostly exists for {@link UnconfiguredQueryEnvironment} as an explicit graph of unconfigured
 * nodes is the best way to model {@code uquery}.
 */
public class UnconfiguredTargetGraph implements TraversableGraph<UnconfiguredTargetNode> {

  private static final Logger LOG = Logger.get(UnconfiguredTargetGraph.class);

  private final Parser parser;
  private final PerBuildState perBuildState;
  private final Cell rootCell;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final TypeCoercerFactory typeCoercerFactory;

  // Query execution is single threaded, however the buildTransitiveClosure implementation
  // traverses the graph in parallel.
  private MutableDirectedGraph<UnconfiguredTargetNode> graph =
      MutableDirectedGraph.createConcurrent();
  private Map<UnflavoredBuildTarget, UnconfiguredTargetNode> targetsToNodes =
      new ConcurrentHashMap<>();
  private Map<UnflavoredBuildTarget, NodeAttributeTraversalResult> targetsToTraversalResults =
      new ConcurrentHashMap<>();

  public UnconfiguredTargetGraph(
      Parser parser,
      PerBuildState perBuildState,
      Cell rootCell,
      KnownRuleTypesProvider knownRuleTypesProvider,
      TypeCoercerFactory typeCoercerFactory) {
    this.parser = parser;
    this.perBuildState = perBuildState;
    this.rootCell = rootCell;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.typeCoercerFactory = typeCoercerFactory;
  }

  @Override
  public Iterable<UnconfiguredTargetNode> getNodes() {
    return graph.getNodes();
  }

  @Override
  public Iterable<UnconfiguredTargetNode> getNodesWithNoIncomingEdges() {
    return graph.getNodesWithNoIncomingEdges();
  }

  @Override
  public Iterable<UnconfiguredTargetNode> getNodesWithNoOutgoingEdges() {
    return graph.getNodesWithNoOutgoingEdges();
  }

  @Override
  public Iterable<UnconfiguredTargetNode> getIncomingNodesFor(UnconfiguredTargetNode sink) {
    return graph.getIncomingNodesFor(sink);
  }

  @Override
  public Iterable<UnconfiguredTargetNode> getOutgoingNodesFor(UnconfiguredTargetNode source) {
    return graph.getOutgoingNodesFor(source);
  }

  /**
   * Get the {@code UnconfiguredTargetNode} represented by {@code buildTarget}. May require invoking
   * the parser if the node is not already cached.
   */
  public UnconfiguredTargetNode getNode(UnflavoredBuildTarget buildTarget) {
    UnconfiguredTargetNode cached = targetsToNodes.get(buildTarget);
    if (cached != null) {
      return cached;
    }

    UnconfiguredBuildTarget unconfiguredBuildTarget = UnconfiguredBuildTarget.of(buildTarget);
    ListenableFuture<UnconfiguredTargetNode> nodeJob =
        parser.getUnconfiguredTargetNodeJob(
            perBuildState, unconfiguredBuildTarget, DependencyStack.top(unconfiguredBuildTarget));
    LOG.verbose("Request for node getting delegated to parser: %s", buildTarget);
    try {
      UnconfiguredTargetNode node = nodeJob.get();
      // NOTE: We are explicitly not populating the `targetsToNodes` cache here since we never went
      // looking for the recursive deps of this node. If we put the node in the cache anyway then
      // the discoverNewTargetsConcurrently method will have no way to differentiate between targets
      // that have had their transitive closure calculated already and those that haven't.
      return node;
    } catch (ExecutionException e) {
      throw new BuckUncheckedExecutionException(e, "Error occurred while calculating target node");
    } catch (InterruptedException e) {
      throw new BuckUncheckedExecutionException(e, "Interrupted while waiting for target node");
    }
  }

  public ImmutableList<UnconfiguredTargetNode> getAllNodesInBuildFile(
      Cell cell, AbsPath buildFilePath) {
    return parser.getAllUnconfiguredTargetNodes(perBuildState, cell, buildFilePath);
  }

  /** The set of {@code ForwardRelativePath}`s that are used as input for {@code node} */
  public ImmutableSet<ForwardRelativePath> getInputPathsForNode(UnconfiguredTargetNode node) {
    return getTraversalResult(node).getInputs();
  }

  /**
   * Get a node from the {@code targetsToNodes} cache. This method throws an exception when the node
   * you're looking for isn't in the cache, so you should be reasonably confident the node will
   * exist there before trying to look it up.
   */
  @Nonnull
  public UnconfiguredTargetNode assertCachedNode(UnconfiguredBuildTarget buildTarget) {
    UnflavoredBuildTarget unflavored = buildTarget.getUnflavoredBuildTarget();
    return Objects.requireNonNull(
        targetsToNodes.get(unflavored),
        () -> "Couldn't find UnconfiguredTargetNode for " + unflavored);
  }

  /**
   * Expands the target graph to include the transitive closure (aka all the dependencies of) the
   * given targets.
   */
  public void buildTransitiveClosure(Set<UnconfiguredBuildTarget> targets) throws QueryException {
    ImmutableSet<UnconfiguredBuildTarget> newBuildTargets =
        targets.stream()
            .filter(t -> !targetsToNodes.containsKey(t.getUnflavoredBuildTarget()))
            .collect(ImmutableSet.toImmutableSet());

    // TODO(mkosiba): This looks more and more like the Parser.buildTargetGraph method. Unify the
    // two.

    ConcurrentHashMap<UnconfiguredBuildTarget, ListenableFuture<Unit>> jobsCache =
        new ConcurrentHashMap<>();

    try {
      List<ListenableFuture<Unit>> depsFuture = new ArrayList<>();
      for (UnconfiguredBuildTarget buildTarget : newBuildTargets) {
        discoverNewTargetsConcurrently(buildTarget, DependencyStack.top(buildTarget), jobsCache)
            .ifPresent(dep -> depsFuture.add(dep));
      }
      Futures.allAsList(depsFuture).get();
    } catch (ExecutionException e) {
      if (e.getCause() != null) {
        throw new QueryException(
            e.getCause(),
            "Failed parsing: " + MoreExceptions.getHumanReadableOrLocalizedMessage(e.getCause()));
      }
      propagateCauseIfInstanceOf(e, ExecutionException.class);
      propagateCauseIfInstanceOf(e, UncheckedExecutionException.class);
    } catch (BuildFileParseException | InterruptedException e) {
      throw new QueryException(
          e, "Failed parsing: " + MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }

    GraphTraversableWithPayload<UnconfiguredBuildTarget, UnconfiguredTargetNode> traversable =
        target -> {
          UnconfiguredTargetNode node = assertCachedNode(target);

          // If a node has been added to the graph it means it and all of its children have been
          // visited by an acyclic traversal and added to the graph. From this it follows that there
          // are no outgoing edges from the graph (as it had been "fully" explored before) back out
          // to the set of nodes we're currently exploring. Based on that:
          //  - we can't have a cycle involving the "old" nodes,
          //  - there are no new edges or nodes to be discovered by descending into the "old" nodes,
          // making this node safe to skip.
          if (graph.getNodes().contains(node)) {
            return new Pair<>(node, ImmutableSet.<UnconfiguredBuildTarget>of().iterator());
          }
          return new Pair<>(node, getTraversalResult(node).getParseDeps().iterator());
        };

    AcyclicDepthFirstPostOrderTraversalWithPayload<UnconfiguredBuildTarget, UnconfiguredTargetNode>
        targetNodeTraversal = new AcyclicDepthFirstPostOrderTraversalWithPayload<>(traversable);
    try {
      for (Pair<UnconfiguredBuildTarget, UnconfiguredTargetNode> entry :
          targetNodeTraversal.traverse(newBuildTargets)) {
        UnconfiguredTargetNode node = entry.getSecond();
        graph.addNode(node);

        for (UnconfiguredBuildTarget parseDep : getTraversalResult(node).getParseDeps()) {
          graph.addEdge(node, assertCachedNode(parseDep));
        }
      }
    } catch (CycleException e) {
      throw new QueryException(e, e.getMessage());
    }
  }

  /**
   * Traverses the object hiearchy for {@code attribute} on {@code node}, returning a set of all
   * objects where {@code predicate} returns true.
   */
  @SuppressWarnings("unchecked")
  public ImmutableSet<Object> filterAttributeContents(
      UnconfiguredTargetNode node, ParamName attribute, Predicate<Object> predicate) {
    Object value = node.getAttributes().get(attribute);
    if (value == null) {
      // Ignore if the field does not exist on this target.
      return ImmutableSet.of();
    }

    ParamInfo<?> info =
        lookupParamsInfoForRule(node.getBuildTarget(), node.getRuleType()).getByName(attribute);
    Preconditions.checkState(
        info != null,
        "Attributes not supported by the rule shouldn't have a value: %s + %s",
        node.getRuleType(),
        attribute);

    TypeCoercer<Object, ?> coercer = (TypeCoercer<Object, ?>) info.getTypeCoercer();
    ImmutableSet.Builder<Object> result = ImmutableSet.builder();

    SelectorList.traverseSelectorListValuesOrValue(
        value, v -> collectMatchingObjectsFromCoercerTraversal(result, coercer, predicate, v));
    return result.build();
  }

  public NodeAttributeTraversalResult getTraversalResult(UnconfiguredTargetNode node) {
    return targetsToTraversalResults.computeIfAbsent(
        node.getBuildTarget(), t -> this.computeTraversalResult(node));
  }

  private Optional<ListenableFuture<Unit>> discoverNewTargetsConcurrently(
      UnconfiguredBuildTarget buildTarget,
      DependencyStack dependencyStack,
      ConcurrentHashMap<UnconfiguredBuildTarget, ListenableFuture<Unit>> jobsCache)
      throws BuildFileParseException {
    ListenableFuture<Unit> job = jobsCache.get(buildTarget);
    if (job != null) {
      return Optional.empty();
    }
    SettableFuture<Unit> newJob = SettableFuture.create();
    if (jobsCache.putIfAbsent(buildTarget, newJob) != null) {
      return Optional.empty();
    }

    ListenableFuture<Unit> future =
        Futures.transformAsync(
            parser.getUnconfiguredTargetNodeJob(perBuildState, buildTarget, dependencyStack),
            targetNode -> {
              targetsToNodes.put(buildTarget.getUnflavoredBuildTarget(), targetNode);
              List<ListenableFuture<Unit>> depsFuture = new ArrayList<>();
              for (UnconfiguredBuildTarget parseDep :
                  getTraversalResult(targetNode).getParseDeps()) {
                discoverNewTargetsConcurrently(parseDep, dependencyStack.child(parseDep), jobsCache)
                    .ifPresent(
                        depWork ->
                            depsFuture.add(
                                attachParentNodeToErrorMessage(buildTarget, parseDep, depWork)));
              }
              return Futures.transform(
                  Futures.allAsList(depsFuture),
                  Functions.constant(null),
                  MoreExecutors.directExecutor());
            });
    newJob.setFuture(future);
    return Optional.of(newJob);
  }

  private static ListenableFuture<Unit> attachParentNodeToErrorMessage(
      UnconfiguredBuildTarget buildTarget,
      UnconfiguredBuildTarget parseDep,
      ListenableFuture<Unit> depWork) {
    return Futures.catchingAsync(
        depWork,
        Exception.class,
        exceptionInput -> {
          if (exceptionInput instanceof BuildFileParseException) {
            if (exceptionInput instanceof BuildTargetException) {
              throw ParserMessages.createReadableExceptionWithWhenSuffix(
                  buildTarget, parseDep, (BuildTargetException) exceptionInput);
            } else {
              throw ParserMessages.createReadableExceptionWithWhenSuffix(
                  buildTarget, parseDep, (BuildFileParseException) exceptionInput);
            }
          }
          throw exceptionInput;
        });
  }

  @SuppressWarnings("unchecked")
  private NodeAttributeTraversalResult computeTraversalResult(UnconfiguredTargetNode node) {
    ImmutableMap.Builder<ParamName, ImmutableSet<UnconfiguredQueryTarget>> targetsByParamBuilder =
        ImmutableMap.builder();
    ImmutableSet.Builder<UnconfiguredBuildTarget> declaredDepsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<UnconfiguredBuildTarget> extraDepsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<UnconfiguredBuildTarget> targetGraphOnlyDepsBuilder =
        ImmutableSet.builder();
    ImmutableSet.Builder<ForwardRelativePath> inputsBuilder = ImmutableSet.builder();

    TwoArraysImmutableHashMap<ParamName, Object> attributes = node.getAttributes();

    ParamsInfo paramsInfo = lookupParamsInfoForRule(node.getBuildTarget(), node.getRuleType());
    for (ParamName name : attributes.keySet()) {
      ImmutableSet.Builder<UnconfiguredQueryTarget> attributeResult = ImmutableSet.builder();

      ParamInfo<?> info = paramsInfo.getByName(name);
      TypeCoercer<Object, ?> coercer = (TypeCoercer<Object, ?>) info.getTypeCoercer();

      // The logic around "which builder should we use for the values in this attribute" is
      // mirroring
      // what is done in TargetNodeFactory for configured targets.
      Optional<ImmutableSet.Builder<UnconfiguredBuildTarget>> buildTargetBuilder = Optional.empty();
      if (CommonParamNames.DEPS.equals(name)) {
        buildTargetBuilder = Optional.of(declaredDepsBuilder);
      } else if (info.isDep() && info.isInput()) {
        buildTargetBuilder =
            Optional.of(
                info.isTargetGraphOnlyDep() ? targetGraphOnlyDepsBuilder : extraDepsBuilder);
      }
      // This is silly but Java doesn't want us to use a variable that gets modified post assignment
      // in a lambda, so we just make a new identical variable pointing to the same object.
      Optional<ImmutableSet.Builder<UnconfiguredBuildTarget>> finalBuildTargetBuilder =
          buildTargetBuilder;

      Optional<ImmutableSet.Builder<ForwardRelativePath>> fileBuilder =
          info.isInput() ? Optional.of(inputsBuilder) : Optional.empty();

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
                        UnconfiguredBuildTarget unconfiguredBuildTarget =
                            target.getUnconfiguredBuildTarget();
                        finalBuildTargetBuilder.ifPresent(b -> b.add(unconfiguredBuildTarget));
                        attributeResult.add(
                            UnconfiguredQueryBuildTarget.of(unconfiguredBuildTarget));
                      });
              collectQueryTargetsFromCoercerTraversal(
                  attributeResult, finalBuildTargetBuilder, fileBuilder, coercer, selectorValue);
            });
      } else {
        collectQueryTargetsFromCoercerTraversal(
            attributeResult, buildTargetBuilder, fileBuilder, coercer, value);
      }
      targetsByParamBuilder.put(name, attributeResult.build());
    }
    return ImmutableNodeAttributeTraversalResult.ofImpl(
        targetsByParamBuilder.build(),
        declaredDepsBuilder.build(),
        extraDepsBuilder.build(),
        targetGraphOnlyDepsBuilder.build(),
        inputsBuilder.build());
  }

  private ParamsInfo lookupParamsInfoForRule(UnflavoredBuildTarget buildTarget, RuleType ruleType) {
    Cell cell = rootCell.getCell(buildTarget.getCell());
    KnownRuleTypes knownRuleTypes = knownRuleTypesProvider.get(cell);
    RuleDescriptor<?> descriptor = knownRuleTypes.getDescriptorByName(ruleType.getName());
    return typeCoercerFactory
        .getNativeConstructorArgDescriptor(descriptor.getConstructorArgType())
        .getParamsInfo();
  }

  private void collectQueryTargetsFromCoercerTraversal(
      ImmutableSet.Builder<UnconfiguredQueryTarget> attributeResultBuilder,
      Optional<ImmutableSet.Builder<UnconfiguredBuildTarget>> buildTargetResultBuilder,
      Optional<ImmutableSet.Builder<ForwardRelativePath>> inputResultBuilder,
      TypeCoercer<Object, ?> coercer,
      Object value) {
    coercer.traverseUnconfigured(
        rootCell.getCellNameResolver(),
        value,
        object -> {
          if (object instanceof UnconfiguredBuildTarget) {
            UnconfiguredBuildTarget objectAsBuildTarget = (UnconfiguredBuildTarget) object;
            buildTargetResultBuilder.ifPresent(b -> b.add(objectAsBuildTarget));
            attributeResultBuilder.add(UnconfiguredQueryBuildTarget.of(objectAsBuildTarget));
          } else if (object instanceof UnflavoredBuildTarget) {
            UnconfiguredBuildTarget unconfiguredBuildTarget =
                UnconfiguredBuildTarget.of((UnflavoredBuildTarget) object);
            buildTargetResultBuilder.ifPresent(b -> b.add(unconfiguredBuildTarget));
            attributeResultBuilder.add(UnconfiguredQueryBuildTarget.of(unconfiguredBuildTarget));
          } else if (object instanceof UnconfiguredSourcePath) {
            ((UnconfiguredSourcePath) object)
                .match(
                    new UnconfiguredSourcePath.Matcher<Unit>() {
                      @Override
                      public Unit path(CellRelativePath path) {
                        // We might not actually want to use `SourcePath` to represent our paths...
                        ProjectFilesystem pathFilesystem =
                            rootCell.getCell(path.getCellName()).getFilesystem();
                        PathSourcePath psp = PathSourcePath.of(pathFilesystem, path.getPath());

                        inputResultBuilder.ifPresent(b -> b.add(path.getPath()));
                        attributeResultBuilder.add(QueryFileTarget.of(psp));
                        return Unit.UNIT;
                      }

                      @Override
                      public Unit buildTarget(
                          UnconfiguredBuildTargetWithOutputs targetWithOutputs) {
                        UnconfiguredBuildTarget target = targetWithOutputs.getBuildTarget();

                        buildTargetResultBuilder.ifPresent(b -> b.add(target));
                        attributeResultBuilder.add(UnconfiguredQueryBuildTarget.of(target));
                        return Unit.UNIT;
                      }
                    });
          }
        });
  }

  private void collectMatchingObjectsFromCoercerTraversal(
      ImmutableSet.Builder<Object> result,
      TypeCoercer<Object, ?> coercer,
      Predicate<Object> predicate,
      Object value) {
    coercer.traverseUnconfigured(
        rootCell.getCellNameResolver(),
        value,
        object -> {
          if (predicate.test(object)) {
            result.add(object);
          }
        });
  }

  /** Value object representing the data we collected when traversing the attributes of the node. */
  @BuckStyleValue
  public abstract static class NodeAttributeTraversalResult {
    public abstract ImmutableMap<ParamName, ImmutableSet<UnconfiguredQueryTarget>>
        getTargetsByParam();

    // The categorization of these four elements is taken straight from TargetNode, which organizes
    // it's build targets a similar way. The criteria for what fits each of these elements is
    // similar. If we ever diverge with TargetNodeFactory then we may get weird results.
    public abstract ImmutableSet<UnconfiguredBuildTarget> getDeclaredDeps();

    public abstract ImmutableSet<UnconfiguredBuildTarget> getExtraDeps();

    public abstract ImmutableSet<UnconfiguredBuildTarget> getTargetGraphOnlyDeps();

    public abstract ImmutableSet<ForwardRelativePath> getInputs();

    @Value.Derived
    public ImmutableSet<UnconfiguredBuildTarget> getParseDeps() {
      ImmutableSet.Builder<UnconfiguredBuildTarget> result = ImmutableSet.builder();
      result.addAll(getDeclaredDeps());
      result.addAll(getExtraDeps());
      result.addAll(getTargetGraphOnlyDeps());
      return result.build();
    }
  }
}
