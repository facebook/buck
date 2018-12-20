package com.facebook.buck.jvm.java;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.AbstractJavacPluginProperties.Type;
import org.immutables.value.Value;

/**
 * Description of a rule that builds a javac {@link com.sun.source.util.Plugin}.
 */
public class JavaPluginDescription
    implements DescriptionWithTargetGraph<JavaPluginDescriptionArg> {

  @Override
  public Class<JavaPluginDescriptionArg> getConstructorArgType() {
    return JavaPluginDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget, BuildRuleParams params, JavaPluginDescriptionArg args) {

    JavacPluginProperties.Builder propsBuilder = JavacPluginProperties.builder();
    propsBuilder.addProcessorNames(args.getPluginName());

    for (BuildRule dep : params.getBuildDeps()) {
      if (!(dep instanceof JavaLibrary)) {
        throw new HumanReadableException(
            String.format(
                "%s: dependencies must produce JVM libraries; %s is a %s",
                buildTarget, dep.getBuildTarget(), dep.getType()));
      }
      propsBuilder.addDep(dep);
    }

    boolean reuseClassLoader = !args.isIsolateClassLoader();
    propsBuilder.setType(Type.JAVAC_PLUGIN);
    propsBuilder.setCanReuseClassLoader(reuseClassLoader);
    propsBuilder.setDoesNotAffectAbi(args.isDoesNotAffectAbi());
    propsBuilder.setSupportsAbiGenerationFromSource(args.isSupportsAbiGenerationFromSource());
    JavacPluginProperties properties = propsBuilder.build();

    return new StandardJavacPlugin(
        buildTarget, context.getProjectFilesystem(), params, properties);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractJavaPluginDescriptionArg
      extends JavacPluginArgs {

    String getPluginName();
  }
}
