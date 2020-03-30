package com.facebook.buck.logd.proto;

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
 * <pre>
 * Manages LogdService APIs
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.10.1)",
    comments = "Source: src/com/facebook/buck/logd/proto/logdservice.proto")
public final class LogdServiceGrpc {

  private LogdServiceGrpc() {}

  public static final String SERVICE_NAME = "logd.v1.LogdService";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getCreateLogFileMethod()} instead. 
  public static final io.grpc.MethodDescriptor<com.facebook.buck.logd.proto.CreateLogRequest,
      com.facebook.buck.logd.proto.CreateLogResponse> METHOD_CREATE_LOG_FILE = getCreateLogFileMethodHelper();

  private static volatile io.grpc.MethodDescriptor<com.facebook.buck.logd.proto.CreateLogRequest,
      com.facebook.buck.logd.proto.CreateLogResponse> getCreateLogFileMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<com.facebook.buck.logd.proto.CreateLogRequest,
      com.facebook.buck.logd.proto.CreateLogResponse> getCreateLogFileMethod() {
    return getCreateLogFileMethodHelper();
  }

  private static io.grpc.MethodDescriptor<com.facebook.buck.logd.proto.CreateLogRequest,
      com.facebook.buck.logd.proto.CreateLogResponse> getCreateLogFileMethodHelper() {
    io.grpc.MethodDescriptor<com.facebook.buck.logd.proto.CreateLogRequest, com.facebook.buck.logd.proto.CreateLogResponse> getCreateLogFileMethod;
    if ((getCreateLogFileMethod = LogdServiceGrpc.getCreateLogFileMethod) == null) {
      synchronized (LogdServiceGrpc.class) {
        if ((getCreateLogFileMethod = LogdServiceGrpc.getCreateLogFileMethod) == null) {
          LogdServiceGrpc.getCreateLogFileMethod = getCreateLogFileMethod = 
              io.grpc.MethodDescriptor.<com.facebook.buck.logd.proto.CreateLogRequest, com.facebook.buck.logd.proto.CreateLogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "logd.v1.LogdService", "CreateLogFile"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.facebook.buck.logd.proto.CreateLogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.facebook.buck.logd.proto.CreateLogResponse.getDefaultInstance()))
                  .setSchemaDescriptor(new LogdServiceMethodDescriptorSupplier("CreateLogFile"))
                  .build();
          }
        }
     }
     return getCreateLogFileMethod;
  }
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getOpenLogMethod()} instead. 
  public static final io.grpc.MethodDescriptor<com.facebook.buck.logd.proto.LogMessage,
      com.google.rpc.Status> METHOD_OPEN_LOG = getOpenLogMethodHelper();

  private static volatile io.grpc.MethodDescriptor<com.facebook.buck.logd.proto.LogMessage,
      com.google.rpc.Status> getOpenLogMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<com.facebook.buck.logd.proto.LogMessage,
      com.google.rpc.Status> getOpenLogMethod() {
    return getOpenLogMethodHelper();
  }

  private static io.grpc.MethodDescriptor<com.facebook.buck.logd.proto.LogMessage,
      com.google.rpc.Status> getOpenLogMethodHelper() {
    io.grpc.MethodDescriptor<com.facebook.buck.logd.proto.LogMessage, com.google.rpc.Status> getOpenLogMethod;
    if ((getOpenLogMethod = LogdServiceGrpc.getOpenLogMethod) == null) {
      synchronized (LogdServiceGrpc.class) {
        if ((getOpenLogMethod = LogdServiceGrpc.getOpenLogMethod) == null) {
          LogdServiceGrpc.getOpenLogMethod = getOpenLogMethod = 
              io.grpc.MethodDescriptor.<com.facebook.buck.logd.proto.LogMessage, com.google.rpc.Status>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(
                  "logd.v1.LogdService", "OpenLog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.facebook.buck.logd.proto.LogMessage.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.rpc.Status.getDefaultInstance()))
                  .setSchemaDescriptor(new LogdServiceMethodDescriptorSupplier("OpenLog"))
                  .build();
          }
        }
     }
     return getOpenLogMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static LogdServiceStub newStub(io.grpc.Channel channel) {
    return new LogdServiceStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static LogdServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new LogdServiceBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static LogdServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new LogdServiceFutureStub(channel);
  }

  /**
   * <pre>
   * Manages LogdService APIs
   * </pre>
   */
  public static abstract class LogdServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * A simple, unary RPC.
     * Client sends a CreateLogRequest to logD, after which logD creates a corresponding log file in
     * file-system and/or storage and returns a CreateLogResponse with a generated id.
     * </pre>
     */
    public void createLogFile(com.facebook.buck.logd.proto.CreateLogRequest request,
        io.grpc.stub.StreamObserver<com.facebook.buck.logd.proto.CreateLogResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getCreateLogFileMethodHelper(), responseObserver);
    }

    /**
     * <pre>
     * A client-to-server streaming RPC.
     * Upon receiving a logId that corresponds with a log file created by logD, client can proceed to
     * stream LogMessage(s) to logD.
     * After receiving an OpenLog call from client, logD will
     * proceed to open a writer stream to the log file identified by logId
     * and return a StreamObserver that can observe and process incoming logs from client.
     * Client can then use the returned StreamObserver to stream LogMessages to logD.
     * After the client finishes sending logs, it should call onCompleted() on the returned
     * StreamObserver to signal logD to close the corresponding writer stream to log file.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<com.facebook.buck.logd.proto.LogMessage> openLog(
        io.grpc.stub.StreamObserver<com.google.rpc.Status> responseObserver) {
      return asyncUnimplementedStreamingCall(getOpenLogMethodHelper(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getCreateLogFileMethodHelper(),
            asyncUnaryCall(
              new MethodHandlers<
                com.facebook.buck.logd.proto.CreateLogRequest,
                com.facebook.buck.logd.proto.CreateLogResponse>(
                  this, METHODID_CREATE_LOG_FILE)))
          .addMethod(
            getOpenLogMethodHelper(),
            asyncClientStreamingCall(
              new MethodHandlers<
                com.facebook.buck.logd.proto.LogMessage,
                com.google.rpc.Status>(
                  this, METHODID_OPEN_LOG)))
          .build();
    }
  }

  /**
   * <pre>
   * Manages LogdService APIs
   * </pre>
   */
  public static final class LogdServiceStub extends io.grpc.stub.AbstractStub<LogdServiceStub> {
    private LogdServiceStub(io.grpc.Channel channel) {
      super(channel);
    }

    private LogdServiceStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LogdServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new LogdServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * A simple, unary RPC.
     * Client sends a CreateLogRequest to logD, after which logD creates a corresponding log file in
     * file-system and/or storage and returns a CreateLogResponse with a generated id.
     * </pre>
     */
    public void createLogFile(com.facebook.buck.logd.proto.CreateLogRequest request,
        io.grpc.stub.StreamObserver<com.facebook.buck.logd.proto.CreateLogResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getCreateLogFileMethodHelper(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * A client-to-server streaming RPC.
     * Upon receiving a logId that corresponds with a log file created by logD, client can proceed to
     * stream LogMessage(s) to logD.
     * After receiving an OpenLog call from client, logD will
     * proceed to open a writer stream to the log file identified by logId
     * and return a StreamObserver that can observe and process incoming logs from client.
     * Client can then use the returned StreamObserver to stream LogMessages to logD.
     * After the client finishes sending logs, it should call onCompleted() on the returned
     * StreamObserver to signal logD to close the corresponding writer stream to log file.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<com.facebook.buck.logd.proto.LogMessage> openLog(
        io.grpc.stub.StreamObserver<com.google.rpc.Status> responseObserver) {
      return asyncClientStreamingCall(
          getChannel().newCall(getOpenLogMethodHelper(), getCallOptions()), responseObserver);
    }
  }

  /**
   * <pre>
   * Manages LogdService APIs
   * </pre>
   */
  public static final class LogdServiceBlockingStub extends io.grpc.stub.AbstractStub<LogdServiceBlockingStub> {
    private LogdServiceBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private LogdServiceBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LogdServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new LogdServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * A simple, unary RPC.
     * Client sends a CreateLogRequest to logD, after which logD creates a corresponding log file in
     * file-system and/or storage and returns a CreateLogResponse with a generated id.
     * </pre>
     */
    public com.facebook.buck.logd.proto.CreateLogResponse createLogFile(com.facebook.buck.logd.proto.CreateLogRequest request) {
      return blockingUnaryCall(
          getChannel(), getCreateLogFileMethodHelper(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * Manages LogdService APIs
   * </pre>
   */
  public static final class LogdServiceFutureStub extends io.grpc.stub.AbstractStub<LogdServiceFutureStub> {
    private LogdServiceFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private LogdServiceFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LogdServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new LogdServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * A simple, unary RPC.
     * Client sends a CreateLogRequest to logD, after which logD creates a corresponding log file in
     * file-system and/or storage and returns a CreateLogResponse with a generated id.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.facebook.buck.logd.proto.CreateLogResponse> createLogFile(
        com.facebook.buck.logd.proto.CreateLogRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getCreateLogFileMethodHelper(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CREATE_LOG_FILE = 0;
  private static final int METHODID_OPEN_LOG = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final LogdServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(LogdServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_CREATE_LOG_FILE:
          serviceImpl.createLogFile((com.facebook.buck.logd.proto.CreateLogRequest) request,
              (io.grpc.stub.StreamObserver<com.facebook.buck.logd.proto.CreateLogResponse>) responseObserver);
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
        case METHODID_OPEN_LOG:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.openLog(
              (io.grpc.stub.StreamObserver<com.google.rpc.Status>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class LogdServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    LogdServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.facebook.buck.logd.proto.LogdServiceOuterFile.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("LogdService");
    }
  }

  private static final class LogdServiceFileDescriptorSupplier
      extends LogdServiceBaseDescriptorSupplier {
    LogdServiceFileDescriptorSupplier() {}
  }

  private static final class LogdServiceMethodDescriptorSupplier
      extends LogdServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    LogdServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (LogdServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new LogdServiceFileDescriptorSupplier())
              .addMethod(getCreateLogFileMethodHelper())
              .addMethod(getOpenLogMethodHelper())
              .build();
        }
      }
    }
    return result;
  }
}
