package net.starlark.java.eval;

import javax.annotation.Nullable;

/** Utilities to call functions using various conventions. */
class BcCall {

  private static EvalException handleException(Throwable throwable, StarlarkThread thread)
      throws InterruptedException {
    if (throwable instanceof Starlark.UncheckedEvalException) {
      // already wrapped
      throw (Starlark.UncheckedEvalException) throwable;
    } else if (throwable instanceof RuntimeException
        || throwable instanceof StackOverflowError
        || throwable instanceof AssertionError) {
      throw new Starlark.UncheckedEvalException(throwable, thread.getCallStack());
    } else if (throwable instanceof EvalException) {
      // If this exception was newly thrown, set its stack.
      return ((EvalException) throwable).ensureStack(thread);
    } else if (throwable instanceof InterruptedException) {
      throw (InterruptedException) throwable;
    } else if (throwable instanceof Error) {
      throw (Error) throwable;
    } else {
      // impossible
      throw new Error(throwable);
    }
  }

  static Object fastcall(StarlarkThread thread, Object fn, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {
    StarlarkCallable callable = callable(fn);

    thread.push(callable);
    try {
      return callable.fastcall(thread, positional, named);
    } catch (Throwable e) {
      throw handleException(e, thread);
    } finally {
      thread.pop();
    }
  }

  static StarlarkCallable callable(Object fn) throws EvalException {
    StarlarkCallable callable;
    if (fn instanceof StarlarkCallable) {
      callable = (StarlarkCallable) fn;
    } else {
      // @StarlarkMethod(selfCall)?
      MethodDescriptor desc =
          CallUtils.getSelfCallMethodDescriptor(fn.getClass());
      if (desc == null) {
        throw Starlark.errorf("'%s' object is not callable", Starlark.type(fn));
      }
      callable = new BuiltinFunction(fn, desc.getName(), desc);
    }
    return callable;
  }

  static Object callLinked(
      StarlarkThread thread,
      StarlarkCallableLinked fn,
      Object[] args,
      @Nullable Sequence<?> varargs,
      @Nullable Dict<?, ?> kwargs)
      throws EvalException, InterruptedException {
    thread.push(fn.orig);
    try {
      return fn.callLinked(thread, args, varargs, kwargs);
    } catch (Throwable e) {
      throw handleException(e, thread);
    } finally {
      thread.pop();
    }
  }

  static Object callLinked1(
      StarlarkThread thread,
      StarlarkCallableLinked fn,
      Object arg0) throws EvalException, InterruptedException {
    return callLinked(thread, fn, new Object[] { arg0 }, null, null);
  }

  static Object callLinked2(
      StarlarkThread thread,
      StarlarkCallableLinked fn,
      Object arg0,
      Object arg1) throws EvalException, InterruptedException {
    return callLinked(thread, fn, new Object[] { arg0, arg1 }, null, null);
  }

  static Object linkAndCall(
      StarlarkThread thread,
      StarlarkCallable fn,
      StarlarkCallableLinkSig linkSig,
      Object[] args,
      @Nullable Sequence<?> varargs,
      @Nullable Dict<?, ?> kwargs)
      throws EvalException, InterruptedException {
    thread.push(fn);
    try {
      return fn.linkAndCall(linkSig, thread, args, varargs, kwargs);
    } catch (Throwable e) {
      throw handleException(e, thread);
    } finally {
      thread.pop();
    }
  }

  static Object linkAndCallCs(
      StarlarkThread thread,
      StarlarkCallable fn,
      BcDynCallSite callSite,
      Object[] args,
      @Nullable Sequence<?> varargs,
      @Nullable Dict<?, ?> kwargs)
      throws EvalException, InterruptedException {
    thread.push(fn);
    try {
      return callSite.call(fn, thread, args, varargs, kwargs);
    } catch (Throwable e) {
      throw handleException(e, thread);
    } finally {
      thread.pop();
    }
  }

  static Object linkAndCall1Cs(
      StarlarkThread thread,
      StarlarkCallable fn,
      BcDynCallSite callSite,
      Object arg0)
      throws EvalException, InterruptedException {
    return linkAndCallCs(thread, fn, callSite, new Object[] { arg0 }, null, null);
  }

  static Object linkAndCall2Cs(
      StarlarkThread thread,
      StarlarkCallable fn,
      BcDynCallSite callSite,
      Object arg0,
      Object arg1)
      throws EvalException, InterruptedException {
    return linkAndCallCs(thread, fn, callSite, new Object[] { arg0, arg1 }, null, null);
  }

}
