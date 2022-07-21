// @generated
package com.facebook.buck.install.model;

import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.10.1)",
    comments = "Source: installer/proto/install.proto")
public final class InstallerGrpc {

  private InstallerGrpc() {}

  public static final String SERVICE_NAME = "install.Installer";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getInstallMethod()} instead. 
  public static final io.grpc.MethodDescriptor<com.facebook.buck.install.model.InstallInfo,
      com.facebook.buck.install.model.InstallResponse> METHOD_INSTALL = getInstallMethodHelper();

  private static volatile io.grpc.MethodDescriptor<com.facebook.buck.install.model.InstallInfo,
      com.facebook.buck.install.model.InstallResponse> getInstallMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<com.facebook.buck.install.model.InstallInfo,
      com.facebook.buck.install.model.InstallResponse> getInstallMethod() {
    return getInstallMethodHelper();
  }

  private static io.grpc.MethodDescriptor<com.facebook.buck.install.model.InstallInfo,
      com.facebook.buck.install.model.InstallResponse> getInstallMethodHelper() {
    io.grpc.MethodDescriptor<com.facebook.buck.install.model.InstallInfo, com.facebook.buck.install.model.InstallResponse> getInstallMethod;
    if ((getInstallMethod = InstallerGrpc.getInstallMethod) == null) {
      synchronized (InstallerGrpc.class) {
        if ((getInstallMethod = InstallerGrpc.getInstallMethod) == null) {
          InstallerGrpc.getInstallMethod = getInstallMethod = 
              io.grpc.MethodDescriptor.<com.facebook.buck.install.model.InstallInfo, com.facebook.buck.install.model.InstallResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "install.Installer", "Install"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.facebook.buck.install.model.InstallInfo.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.facebook.buck.install.model.InstallResponse.getDefaultInstance()))
                  .setSchemaDescriptor(new InstallerMethodDescriptorSupplier("Install"))
                  .build();
          }
        }
     }
     return getInstallMethod;
  }
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getFileReadyRequestMethod()} instead. 
  public static final io.grpc.MethodDescriptor<com.facebook.buck.install.model.FileReady,
      com.facebook.buck.install.model.FileResponse> METHOD_FILE_READY_REQUEST = getFileReadyRequestMethodHelper();

  private static volatile io.grpc.MethodDescriptor<com.facebook.buck.install.model.FileReady,
      com.facebook.buck.install.model.FileResponse> getFileReadyRequestMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<com.facebook.buck.install.model.FileReady,
      com.facebook.buck.install.model.FileResponse> getFileReadyRequestMethod() {
    return getFileReadyRequestMethodHelper();
  }

  private static io.grpc.MethodDescriptor<com.facebook.buck.install.model.FileReady,
      com.facebook.buck.install.model.FileResponse> getFileReadyRequestMethodHelper() {
    io.grpc.MethodDescriptor<com.facebook.buck.install.model.FileReady, com.facebook.buck.install.model.FileResponse> getFileReadyRequestMethod;
    if ((getFileReadyRequestMethod = InstallerGrpc.getFileReadyRequestMethod) == null) {
      synchronized (InstallerGrpc.class) {
        if ((getFileReadyRequestMethod = InstallerGrpc.getFileReadyRequestMethod) == null) {
          InstallerGrpc.getFileReadyRequestMethod = getFileReadyRequestMethod = 
              io.grpc.MethodDescriptor.<com.facebook.buck.install.model.FileReady, com.facebook.buck.install.model.FileResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "install.Installer", "FileReadyRequest"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.facebook.buck.install.model.FileReady.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.facebook.buck.install.model.FileResponse.getDefaultInstance()))
                  .setSchemaDescriptor(new InstallerMethodDescriptorSupplier("FileReadyRequest"))
                  .build();
          }
        }
     }
     return getFileReadyRequestMethod;
  }
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getShutdownServerMethod()} instead. 
  public static final io.grpc.MethodDescriptor<com.facebook.buck.install.model.Shutdown,
      com.facebook.buck.install.model.ShutdownResponse> METHOD_SHUTDOWN_SERVER = getShutdownServerMethodHelper();

  private static volatile io.grpc.MethodDescriptor<com.facebook.buck.install.model.Shutdown,
      com.facebook.buck.install.model.ShutdownResponse> getShutdownServerMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<com.facebook.buck.install.model.Shutdown,
      com.facebook.buck.install.model.ShutdownResponse> getShutdownServerMethod() {
    return getShutdownServerMethodHelper();
  }

  private static io.grpc.MethodDescriptor<com.facebook.buck.install.model.Shutdown,
      com.facebook.buck.install.model.ShutdownResponse> getShutdownServerMethodHelper() {
    io.grpc.MethodDescriptor<com.facebook.buck.install.model.Shutdown, com.facebook.buck.install.model.ShutdownResponse> getShutdownServerMethod;
    if ((getShutdownServerMethod = InstallerGrpc.getShutdownServerMethod) == null) {
      synchronized (InstallerGrpc.class) {
        if ((getShutdownServerMethod = InstallerGrpc.getShutdownServerMethod) == null) {
          InstallerGrpc.getShutdownServerMethod = getShutdownServerMethod = 
              io.grpc.MethodDescriptor.<com.facebook.buck.install.model.Shutdown, com.facebook.buck.install.model.ShutdownResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "install.Installer", "ShutdownServer"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.facebook.buck.install.model.Shutdown.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.facebook.buck.install.model.ShutdownResponse.getDefaultInstance()))
                  .setSchemaDescriptor(new InstallerMethodDescriptorSupplier("ShutdownServer"))
                  .build();
          }
        }
     }
     return getShutdownServerMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static InstallerStub newStub(io.grpc.Channel channel) {
    return new InstallerStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static InstallerBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new InstallerBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static InstallerFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new InstallerFutureStub(channel);
  }

  /**
   */
  public static abstract class InstallerImplBase implements io.grpc.BindableService {

    /**
     */
    public void install(com.facebook.buck.install.model.InstallInfo request,
        io.grpc.stub.StreamObserver<com.facebook.buck.install.model.InstallResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getInstallMethodHelper(), responseObserver);
    }

    /**
     */
    public void fileReadyRequest(com.facebook.buck.install.model.FileReady request,
        io.grpc.stub.StreamObserver<com.facebook.buck.install.model.FileResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getFileReadyRequestMethodHelper(), responseObserver);
    }

    /**
     */
    public void shutdownServer(com.facebook.buck.install.model.Shutdown request,
        io.grpc.stub.StreamObserver<com.facebook.buck.install.model.ShutdownResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getShutdownServerMethodHelper(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getInstallMethodHelper(),
            asyncUnaryCall(
              new MethodHandlers<
                com.facebook.buck.install.model.InstallInfo,
                com.facebook.buck.install.model.InstallResponse>(
                  this, METHODID_INSTALL)))
          .addMethod(
            getFileReadyRequestMethodHelper(),
            asyncUnaryCall(
              new MethodHandlers<
                com.facebook.buck.install.model.FileReady,
                com.facebook.buck.install.model.FileResponse>(
                  this, METHODID_FILE_READY_REQUEST)))
          .addMethod(
            getShutdownServerMethodHelper(),
            asyncUnaryCall(
              new MethodHandlers<
                com.facebook.buck.install.model.Shutdown,
                com.facebook.buck.install.model.ShutdownResponse>(
                  this, METHODID_SHUTDOWN_SERVER)))
          .build();
    }
  }

  /**
   */
  public static final class InstallerStub extends io.grpc.stub.AbstractStub<InstallerStub> {
    private InstallerStub(io.grpc.Channel channel) {
      super(channel);
    }

    private InstallerStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected InstallerStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new InstallerStub(channel, callOptions);
    }

    /**
     */
    public void install(com.facebook.buck.install.model.InstallInfo request,
        io.grpc.stub.StreamObserver<com.facebook.buck.install.model.InstallResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getInstallMethodHelper(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void fileReadyRequest(com.facebook.buck.install.model.FileReady request,
        io.grpc.stub.StreamObserver<com.facebook.buck.install.model.FileResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getFileReadyRequestMethodHelper(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void shutdownServer(com.facebook.buck.install.model.Shutdown request,
        io.grpc.stub.StreamObserver<com.facebook.buck.install.model.ShutdownResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getShutdownServerMethodHelper(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class InstallerBlockingStub extends io.grpc.stub.AbstractStub<InstallerBlockingStub> {
    private InstallerBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private InstallerBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected InstallerBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new InstallerBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.facebook.buck.install.model.InstallResponse install(com.facebook.buck.install.model.InstallInfo request) {
      return blockingUnaryCall(
          getChannel(), getInstallMethodHelper(), getCallOptions(), request);
    }

    /**
     */
    public com.facebook.buck.install.model.FileResponse fileReadyRequest(com.facebook.buck.install.model.FileReady request) {
      return blockingUnaryCall(
          getChannel(), getFileReadyRequestMethodHelper(), getCallOptions(), request);
    }

    /**
     */
    public com.facebook.buck.install.model.ShutdownResponse shutdownServer(com.facebook.buck.install.model.Shutdown request) {
      return blockingUnaryCall(
          getChannel(), getShutdownServerMethodHelper(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class InstallerFutureStub extends io.grpc.stub.AbstractStub<InstallerFutureStub> {
    private InstallerFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private InstallerFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected InstallerFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new InstallerFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.facebook.buck.install.model.InstallResponse> install(
        com.facebook.buck.install.model.InstallInfo request) {
      return futureUnaryCall(
          getChannel().newCall(getInstallMethodHelper(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.facebook.buck.install.model.FileResponse> fileReadyRequest(
        com.facebook.buck.install.model.FileReady request) {
      return futureUnaryCall(
          getChannel().newCall(getFileReadyRequestMethodHelper(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.facebook.buck.install.model.ShutdownResponse> shutdownServer(
        com.facebook.buck.install.model.Shutdown request) {
      return futureUnaryCall(
          getChannel().newCall(getShutdownServerMethodHelper(), getCallOptions()), request);
    }
  }

  private static final int METHODID_INSTALL = 0;
  private static final int METHODID_FILE_READY_REQUEST = 1;
  private static final int METHODID_SHUTDOWN_SERVER = 2;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final InstallerImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(InstallerImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_INSTALL:
          serviceImpl.install((com.facebook.buck.install.model.InstallInfo) request,
              (io.grpc.stub.StreamObserver<com.facebook.buck.install.model.InstallResponse>) responseObserver);
          break;
        case METHODID_FILE_READY_REQUEST:
          serviceImpl.fileReadyRequest((com.facebook.buck.install.model.FileReady) request,
              (io.grpc.stub.StreamObserver<com.facebook.buck.install.model.FileResponse>) responseObserver);
          break;
        case METHODID_SHUTDOWN_SERVER:
          serviceImpl.shutdownServer((com.facebook.buck.install.model.Shutdown) request,
              (io.grpc.stub.StreamObserver<com.facebook.buck.install.model.ShutdownResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class InstallerBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    InstallerBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.facebook.buck.install.model.InstallerProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Installer");
    }
  }

  private static final class InstallerFileDescriptorSupplier
      extends InstallerBaseDescriptorSupplier {
    InstallerFileDescriptorSupplier() {}
  }

  private static final class InstallerMethodDescriptorSupplier
      extends InstallerBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    InstallerMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (InstallerGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new InstallerFileDescriptorSupplier())
              .addMethod(getInstallMethodHelper())
              .addMethod(getFileReadyRequestMethodHelper())
              .addMethod(getShutdownServerMethodHelper())
              .build();
        }
      }
    }
    return result;
  }
}
