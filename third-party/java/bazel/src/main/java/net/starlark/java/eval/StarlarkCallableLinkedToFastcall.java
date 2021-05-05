package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import java.util.Arrays;
import java.util.Map;
import javax.annotation.Nullable;

/** Simple (and inefficient) implementation of linked callable which delegates to fastcalll. */
class StarlarkCallableLinkedToFastcall extends StarlarkCallableLinked {

  public StarlarkCallableLinkedToFastcall(StarlarkCallable orig, StarlarkCallableLinkSig linkSig) {
    super(linkSig, orig);
  }

  @Override
  public Object callLinked(StarlarkThread thread, Object[] args,
      @Nullable Sequence<?> starArgs, @Nullable Dict<?, ?> starStarArgs) throws EvalException, InterruptedException {

    // Cheap self-check
    Preconditions.checkState(linkSig.numPositionals + linkSig.namedNames.length == args.length,
        "Linked function called with incorrect number of arguments");

    // Fast track for calls like `list(x)`
    if (starArgs == null && starStarArgs == null && linkSig.namedNames.length == 0) {
      return orig.fastcall(thread, args, ArraysForStarlark.EMPTY_OBJECT_ARRAY);
    }

    int fastcallNPositional = linkSig.numPositionals + (starArgs != null ? starArgs.size() : 0);
    int fastcallNNamed = linkSig.namedNames.length + (starStarArgs != null ? starStarArgs.size() : 0);

    Object[] fastcallPositional;
    if (args.length == linkSig.numPositionals && args.length == fastcallNPositional) {
      // Reuse args array
      fastcallPositional = args;
    } else {
      fastcallPositional = Arrays.copyOf(args, fastcallNPositional);
    }
    Object[] fastcallNamed = ArraysForStarlark.newObjectArray(fastcallNNamed * 2);

    if (starArgs != null) {
      int i = linkSig.numPositionals;
      for (Object starArg : starArgs) {
        fastcallPositional[i++] = starArg;
      }
      Verify.verify(i == fastcallNPositional, "Positional arguments populated incorrectly");
    }

    int i = 0;
    for (String name : linkSig.namedNames) {
      fastcallNamed[i * 2] = name;
      fastcallNamed[i * 2 + 1] = args[linkSig.numPositionals + i];
      ++i;
    }
    if (starStarArgs != null) {
      for (Map.Entry<?, ?> entry : starStarArgs.contents.entrySet()) {
        fastcallNamed[i * 2] = entry.getKey();
        fastcallNamed[i * 2 + 1] = entry.getValue();
        ++i;
      }
    }
    Verify.verify(i == fastcallNNamed, "Named arguments populated incorrectly");

    // We know nothing about external functions, so record side effects.
    thread.recordSideEffect();

    return orig.fastcall(thread, fastcallPositional, fastcallNamed);
  }
}
