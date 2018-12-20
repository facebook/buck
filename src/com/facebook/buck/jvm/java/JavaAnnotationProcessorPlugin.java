package com.facebook.buck.jvm.java;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

/**
 * Represents a Java Annotation Processor Plugin for the Java Compiler
 */
public class JavaAnnotationProcessorPlugin extends JavacPlugin {

  public JavaAnnotationProcessorPlugin(BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      JavacPluginProperties properties) {
    super(buildTarget, projectFilesystem, params, properties);
  }
}
