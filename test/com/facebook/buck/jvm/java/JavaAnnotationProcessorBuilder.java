package com.facebook.buck.jvm.java;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;

public class JavaAnnotationProcessorBuilder
  extends AbstractNodeBuilder<
    JavaAnnotationProcessorDescriptionArg.Builder,
    JavaAnnotationProcessorDescriptionArg,
    JavaAnnotationProcessorDescription,
    JavaAnnotationProcessorPlugin> {

  private JavaAnnotationProcessorBuilder(BuildTarget target) {
    super(new JavaAnnotationProcessorDescription(), target);
    setIsolateClassloader(true);
    setSupportsAbiGenerationFromSource(true);
    setDoesNotAffectAbi(true);
  }

  public static JavaAnnotationProcessorBuilder createBuilder(BuildTarget target) {
    return new JavaAnnotationProcessorBuilder(target);
  }

  public JavaAnnotationProcessorBuilder addDep(BuildTarget dep) {
    getArgForPopulating().addDeps(dep);
    return this;
  }

  public JavaAnnotationProcessorBuilder setIsolateClassloader(boolean isolateClassloader) {
    getArgForPopulating().setIsolateClassLoader(isolateClassloader);
    return this;
  }

  public JavaAnnotationProcessorBuilder setSupportsAbiGenerationFromSource(
      boolean supportsAbiGenerationFromSource) {
    getArgForPopulating()
        .setSupportsAbiGenerationFromSource(supportsAbiGenerationFromSource);
    return this;
  }

  public JavaAnnotationProcessorBuilder setDoesNotAffectAbi(boolean doesNotAffectAbi) {
    getArgForPopulating().setDoesNotAffectAbi(doesNotAffectAbi);
    return this;
  }

  public JavaAnnotationProcessorBuilder addProcessorClass(String processorClass) {
    getArgForPopulating().addProcessorClasses(processorClass);
    return this;
  }
}
