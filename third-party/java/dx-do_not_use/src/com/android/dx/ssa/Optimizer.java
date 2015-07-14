/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dx.ssa;

import com.android.dx.rop.code.RopMethod;
import com.android.dx.rop.code.TranslationAdvice;
import com.android.dx.ssa.back.LivenessAnalyzer;
import com.android.dx.ssa.back.SsaToRop;
import java.util.EnumSet;

/**
 * Runs a method through the SSA form conversion, any optimization algorithms,
 * and returns it to rop form.
 */
public class Optimizer {
    private boolean preserveLocals = true;

    private TranslationAdvice advice;

    /** optional optimizer steps */
    public enum OptionalStep {
        MOVE_PARAM_COMBINER, SCCP, LITERAL_UPGRADE, CONST_COLLECTOR,
            ESCAPE_ANALYSIS
    }

    /**
     * @return true if local variable information should be preserved, even
     * at code size/register size cost
     */
    public boolean getPreserveLocals() {
        return preserveLocals;
    }

    /**
     * @return {@code non-null;} translation advice
     */
    public TranslationAdvice getAdvice() {
        return advice;
    }

    /**
     * Runs optimization algorthims over this method, and returns a new
     * instance of RopMethod with the changes.
     *
     * @param rmeth method to process
     * @param paramWidth the total width, in register-units, of this method's
     * parameters
     * @param isStatic true if this method has no 'this' pointer argument.
     * @param inPreserveLocals true if local variable info should be preserved,
     * at the cost of some registers and insns
     * @param inAdvice {@code non-null;} translation advice
     * @return optimized method
     */
    public RopMethod optimize(RopMethod rmeth, int paramWidth,
            boolean isStatic, boolean inPreserveLocals,
            TranslationAdvice inAdvice) {

        return optimize(rmeth, paramWidth, isStatic, inPreserveLocals, inAdvice,
                EnumSet.allOf(OptionalStep.class));
    }

    /**
     * Runs optimization algorthims over this method, and returns a new
     * instance of RopMethod with the changes.
     *
     * @param rmeth method to process
     * @param paramWidth the total width, in register-units, of this method's
     * parameters
     * @param isStatic true if this method has no 'this' pointer argument.
     * @param inPreserveLocals true if local variable info should be preserved,
     * at the cost of some registers and insns
     * @param inAdvice {@code non-null;} translation advice
     * @param steps set of optional optimization steps to run
     * @return optimized method
     */
    public RopMethod optimize(RopMethod rmeth, int paramWidth,
            boolean isStatic, boolean inPreserveLocals,
            TranslationAdvice inAdvice, EnumSet<OptionalStep> steps) {
        SsaMethod ssaMeth = null;

        preserveLocals = inPreserveLocals;
        advice = inAdvice;

        ssaMeth = SsaConverter.convertToSsaMethod(this, rmeth, paramWidth, isStatic);
        runSsaFormSteps(ssaMeth, steps);

        RopMethod resultMeth = SsaToRop.convertToRopMethod(this, ssaMeth, false);

        if (resultMeth.getBlocks().getRegCount()
                > advice.getMaxOptimalRegisterCount()) {
            // Try to see if we can squeeze it under the register count bar
            resultMeth = optimizeMinimizeRegisters(rmeth, paramWidth, isStatic,
                    steps);
        }
        return resultMeth;
    }

    /**
     * Runs the optimizer with a strategy to minimize the number of rop-form
     * registers used by the end result. Dex bytecode does not have instruction
     * forms that take register numbers larger than 15 for all instructions.
     * If we've produced a method that uses more than 16 registers, try again
     * with a different strategy to see if we can get under the bar. The end
     * result will be much more efficient.
     *
     * @param rmeth method to process
     * @param paramWidth the total width, in register-units, of this method's
     * parameters
     * @param isStatic true if this method has no 'this' pointer argument.
     * @param steps set of optional optimization steps to run
     * @return optimized method
     */
    private RopMethod optimizeMinimizeRegisters(RopMethod rmeth,
            int paramWidth, boolean isStatic,
            EnumSet<OptionalStep> steps) {
        SsaMethod ssaMeth;
        RopMethod resultMeth;

        ssaMeth = SsaConverter.convertToSsaMethod(
                this, rmeth, paramWidth, isStatic);

        EnumSet<OptionalStep> newSteps = steps.clone();

        /*
         * CONST_COLLECTOR trades insns for registers, which is not an
         * appropriate strategy here.
         */
        newSteps.remove(OptionalStep.CONST_COLLECTOR);

        runSsaFormSteps(ssaMeth, newSteps);

        resultMeth = SsaToRop.convertToRopMethod(this, ssaMeth, true);
        return resultMeth;
    }

    private void runSsaFormSteps(SsaMethod ssaMeth,
            EnumSet<OptionalStep> steps) {
        boolean needsDeadCodeRemover = true;

        if (steps.contains(OptionalStep.MOVE_PARAM_COMBINER)) {
            MoveParamCombiner.process(ssaMeth);
        }

        if (steps.contains(OptionalStep.SCCP)) {
            SCCP.process(ssaMeth);
            DeadCodeRemover.process(this, ssaMeth);
            needsDeadCodeRemover = false;
        }

        if (steps.contains(OptionalStep.LITERAL_UPGRADE)) {
            LiteralOpUpgrader.process(this, ssaMeth);
            DeadCodeRemover.process(this, ssaMeth);
            needsDeadCodeRemover = false;
        }

        /*
         * ESCAPE_ANALYSIS impacts debuggability, so left off by default
         */
        steps.remove(OptionalStep.ESCAPE_ANALYSIS);
        if (steps.contains(OptionalStep.ESCAPE_ANALYSIS)) {
            EscapeAnalysis.process(this, ssaMeth);
            DeadCodeRemover.process(this, ssaMeth);
            needsDeadCodeRemover = false;
        }

        if (steps.contains(OptionalStep.CONST_COLLECTOR)) {
            ConstCollector.process(ssaMeth);
            DeadCodeRemover.process(this, ssaMeth);
            needsDeadCodeRemover = false;
        }

        // dead code remover must be run before phi type resolver
        if (needsDeadCodeRemover) {
            DeadCodeRemover.process(this, ssaMeth);
        }

        PhiTypeResolver.process(ssaMeth);
    }

    public SsaMethod debugEdgeSplit(RopMethod rmeth, int paramWidth,
            boolean isStatic, boolean inPreserveLocals,
            TranslationAdvice inAdvice) {

        preserveLocals = inPreserveLocals;
        advice = inAdvice;

        return SsaConverter.testEdgeSplit(rmeth, paramWidth, isStatic);
    }

    public SsaMethod debugPhiPlacement(RopMethod rmeth, int paramWidth,
            boolean isStatic, boolean inPreserveLocals,
            TranslationAdvice inAdvice) {

        preserveLocals = inPreserveLocals;
        advice = inAdvice;

        return SsaConverter.testPhiPlacement(rmeth, paramWidth, isStatic);
    }

    public SsaMethod debugRenaming(RopMethod rmeth, int paramWidth,
            boolean isStatic, boolean inPreserveLocals,
            TranslationAdvice inAdvice) {

        preserveLocals = inPreserveLocals;
        advice = inAdvice;

        return SsaConverter.convertToSsaMethod(this, rmeth, paramWidth, isStatic);
    }

    public SsaMethod debugDeadCodeRemover(RopMethod rmeth,
            int paramWidth, boolean isStatic, boolean inPreserveLocals,
            TranslationAdvice inAdvice) {

        SsaMethod ssaMeth;

        preserveLocals = inPreserveLocals;
        advice = inAdvice;

        ssaMeth = SsaConverter.convertToSsaMethod(this, rmeth, paramWidth, isStatic);
        DeadCodeRemover.process(this, ssaMeth);

        return ssaMeth;
    }

    public SsaMethod debugNoRegisterAllocation(RopMethod rmeth,
            int paramWidth, boolean isStatic, boolean inPreserveLocals,
            TranslationAdvice inAdvice, EnumSet<OptionalStep> steps) {

        SsaMethod ssaMeth;

        preserveLocals = inPreserveLocals;
        advice = inAdvice;

        ssaMeth = SsaConverter.convertToSsaMethod(this, rmeth, paramWidth, isStatic);

        runSsaFormSteps(ssaMeth, steps);

        LivenessAnalyzer.constructInterferenceGraph(ssaMeth);

        return ssaMeth;
    }
}
