package net.starlark.java.annot.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Instruction handler method. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface BcOpcodeHandler {
  // This should be enum
  BcOpcodeNumber opcode();
  /** Should be false for instructions like {@code BR} or {@code CONTINUE} which jump. */
  boolean mayJump() default false;
}
