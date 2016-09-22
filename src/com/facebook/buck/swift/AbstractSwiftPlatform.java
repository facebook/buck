package com.facebook.buck.swift;


import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.Set;

/**
 * Interface describing a Swift toolchain and platform to build for.
 */
@Value.Immutable
@BuckStyleImmutable
interface AbstractSwiftPlatform {

  Tool getSwift();
  Tool getSwiftStdlibTool();
  Set<Path> getSwiftRuntimePaths();
  Set<Path> getSwiftStaticRuntimePaths();
}
