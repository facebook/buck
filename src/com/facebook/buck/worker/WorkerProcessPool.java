package com.facebook.buck.worker;

import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.Closeable;
import java.io.IOException;

public interface WorkerProcessPool extends Closeable {
  HashCode getPoolHash();

  int getCapacity();

  ListenableFuture<WorkerJobResult> submitJob(String expandedJobArgs)
      throws IOException, InterruptedException;

  @Override
  void close();
}
