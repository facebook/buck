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

package com.facebook.buck.shell;

import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.SourceSet;
import com.facebook.buck.rules.macros.ClasspathAbiMacroExpander;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.ExecutableTargetMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroContainer;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MavenCoordinatesMacroExpander;
import com.facebook.buck.rules.macros.QueryOutputsMacroExpander;
import com.facebook.buck.rules.macros.QueryPathsMacroExpander;
import com.facebook.buck.rules.macros.QueryTargetsAndOutputsMacroExpander;
import com.facebook.buck.rules.macros.QueryTargetsMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.rules.macros.WorkerMacro;
import com.facebook.buck.rules.macros.WorkerMacroArg;
import com.facebook.buck.rules.macros.WorkerMacroExpander;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.string.MoreStrings;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.immutables.value.Value;

public abstract class AbstractGenruleDescription<T extends AbstractGenruleDescription.CommonArg>
    implements DescriptionWithTargetGraph<T>, ImplicitDepsInferringDescription<T> {

  protected final ToolchainProvider toolchainProvider;
  protected final BuckConfig buckConfig;
  protected final SandboxExecutionStrategy sandboxExecutionStrategy;
  protected final boolean enableSandbox;

  protected AbstractGenruleDescription(
      ToolchainProvider toolchainProvider,
      BuckConfig buckConfig,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      boolean enableSandbox) {
    this.toolchainProvider = toolchainProvider;
    this.buckConfig = buckConfig;
    this.sandboxExecutionStrategy = sandboxExecutionStrategy;
    this.enableSandbox = enableSandbox;
  }

  protected abstract BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      T args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe);

  /** @return a {@link String} representing the type of the genrule */
  protected String getGenruleType() {
    String base =
        MoreStrings.stripSuffix(getClass().getSimpleName(), "GenruleDescription")
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Expected `AbstractGenruleDescription` child class `%s` to end with \"GenruleDescription\""));
    return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, base + "Genrule");
  }

  /** @return whether this genrule can use remote execution. */
  protected boolean canExecuteRemotely(T args) {
    boolean executeRemotely = args.getRemote().orElse(false);
    if (executeRemotely) {
      RemoteExecutionConfig reConfig = buckConfig.getView(RemoteExecutionConfig.class);
      executeRemotely =
          reConfig.shouldUseRemoteExecutionForGenruleIfRequested(
              args.getType().map(type -> getGenruleType() + "_" + type).orElse(getGenruleType()));
    }
    return executeRemotely;
  }

  protected BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      T args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe,
      Optional<String> outputFileName,
      Optional<ImmutableMap<String, ImmutableSet<String>>> outputFileNames) {
    return new Genrule(
        buildTarget,
        projectFilesystem,
        resolver,
        sandboxExecutionStrategy,
        args.getSrcs(),
        cmd,
        bash,
        cmdExe,
        args.getType(),
        outputFileName,
        outputFileNames,
        args.getEnableSandbox().orElse(enableSandbox),
        args.getCacheable().orElse(true),
        args.getEnvironmentExpansionSeparator(),
        getAndroidToolsOptional(args, buildTarget.getTargetConfiguration()),
        canExecuteRemotely(args));
  }

  /**
   * Returns android tools if {@code args} has need_android_tools option set or empty optional
   * otherwise.
   */
  protected Optional<AndroidTools> getAndroidToolsOptional(
      T args, TargetConfiguration toolchainTargetConfiguration) {
    return args.isNeedAndroidTools()
        ? Optional.of(AndroidTools.getAndroidTools(toolchainProvider, toolchainTargetConfiguration))
        : Optional.empty();
  }

  /**
   * @return the {@link com.facebook.buck.rules.macros.MacroExpander}s which apply to the macros in
   *     this description.
   */
  protected Optional<ImmutableList<MacroExpander<? extends Macro, ?>>> getMacroHandler(
      @SuppressWarnings("unused") BuildTarget buildTarget,
      @SuppressWarnings("unused") ProjectFilesystem filesystem,
      @SuppressWarnings("unused") BuildRuleResolver resolver,
      TargetGraph targetGraph,
      @SuppressWarnings("unused") T args) {
    return Optional.of(
        ImmutableList.of(
            new ClasspathMacroExpander(),
            new ClasspathAbiMacroExpander(),
            new ExecutableMacroExpander<>(ExecutableMacro.class),
            new ExecutableMacroExpander<>(ExecutableTargetMacro.class),
            new WorkerMacroExpander(),
            LocationMacroExpander.INSTANCE,
            new MavenCoordinatesMacroExpander(),
            new QueryTargetsMacroExpander(targetGraph),
            new QueryOutputsMacroExpander(targetGraph),
            new QueryPathsMacroExpander(targetGraph),
            new QueryTargetsAndOutputsMacroExpander(targetGraph)));
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      T args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    Optional<ImmutableList<MacroExpander<? extends Macro, ?>>> maybeExpanders =
        getMacroHandler(
            buildTarget,
            context.getProjectFilesystem(),
            graphBuilder,
            context.getTargetGraph(),
            args);
    if (maybeExpanders.isPresent()) {
      ImmutableList<MacroExpander<? extends Macro, ?>> expanders = maybeExpanders.get();
      StringWithMacrosConverter converter =
          StringWithMacrosConverter.of(
              buildTarget,
              context.getCellPathResolver().getCellNameResolver(),
              graphBuilder,
              expanders);
      Function<StringWithMacros, Arg> toArg =
          str -> {
            Arg arg = converter.convert(str);
            if (RichStream.from(str.getMacros())
                .map(MacroContainer::getMacro)
                .anyMatch(WorkerMacro.class::isInstance)) {
              arg = WorkerMacroArg.fromStringWithMacros(arg, buildTarget, graphBuilder, str);
            }
            return arg;
          };
      Optional<Arg> cmd = args.getCmd().map(toArg);
      Optional<Arg> bash = args.getBash().map(toArg);
      Optional<Arg> cmdExe = args.getCmdExe().map(toArg);
      return createBuildRule(
          buildTarget,
          context.getProjectFilesystem(),
          params.withExtraDeps(
              Stream.concat(
                      graphBuilder.filterBuildRuleInputs(args.getSrcs().getPaths()).stream(),
                      Stream.of(cmd, bash, cmdExe)
                          .flatMap(RichStream::from)
                          .flatMap(input -> BuildableSupport.getDeps(input, graphBuilder)))
                  .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()))),
          graphBuilder,
          args,
          cmd,
          bash,
          cmdExe);
    }
    return createBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        graphBuilder,
        args,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      T constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (constructorArg.isNeedAndroidTools()) {
      AndroidTools.addParseTimeDepsToAndroidTools(
          toolchainProvider, buildTarget, targetGraphOnlyDepsBuilder);
    }
  }

  @SuppressFieldNotInitialized
  public interface CommonArg extends BuildRuleArg, HasTests {
    Optional<StringWithMacros> getBash();

    Optional<StringWithMacros> getCmd();

    Optional<StringWithMacros> getCmdExe();

    Optional<String> getType();

    @Value.Default
    default SourceSet getSrcs() {
      return SourceSet.EMPTY;
    }

    Optional<Boolean> getEnableSandbox();

    Optional<String> getEnvironmentExpansionSeparator();

    /**
     * If present and true, requests that Buck run this genrule remotely if possible. Defaults to
     * false for now.
     */
    Optional<Boolean> getRemote();

    /**
     * This functionality only exists to get around the lack of extensibility in our current build
     * rule / build file apis. It may go away at some point. Also, make sure that you understand
     * what {@link BuildRule#isCacheable} does with respect to caching if you decide to use this
     * attribute
     */
    Optional<Boolean> getCacheable();

    /**
     * This argument allows genrule to specify if it needs android tools (like dex, aapt, ndk, sdk).
     */
    @Value.Default
    default boolean isNeedAndroidTools() {
      return false;
    }
  }
}
