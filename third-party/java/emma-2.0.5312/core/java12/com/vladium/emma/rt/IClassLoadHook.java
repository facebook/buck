/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IClassLoadHook.java,v 1.1.1.1 2004/05/09 16:57:44 vlad_r Exp $
 */
package com.vladium.emma.rt;

import java.io.IOException;

import com.vladium.util.ByteArrayOStream;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, 2003
 */
public
interface IClassLoadHook
{
    // public: ................................................................

//    /**
//     * The hook returns 'true' or 'false' based on 'className' only. If it
//     * returns false, the current loader will load the class bytes itself and
//     * pass them to ClassLoader.defineClass() unchanged. This is an optimization
//     * to let the JVM native code do the parsing of class definitions for classes
//     * that do not match coverage filters instead of doing this in bytecode.  
//     */
//    boolean interested (String className);
    
    // TODO: figure out a way to avoid all this excessive copying of byte arrays
    
    /* TODO: finish
     * 
     * The hook reads in the original class definition from 'in' and [possibly]
     * instruments it, returning the modified class definion [which should
     * correspond to the original class name]. Only class definitions with names
     * that were successfully filtered via a previous (although not necessarily
     * <em>immediately</em> so) call to {@link #interested} will be passed to
     * this method.<P>
     * 
     * It is possible that the hook will determine that it is not interested in
     * intrumenting the pending class definition [or is unable to do so] only
     * after reading some content from 'in'. An example would be when the class
     * definition turns out to be for an interface and the hook does not process
     * interfaces. Because 'in' can then be left in an uncertain state, the hook
     * must follow these rules for the two possible outcomes:
     * <ol>
     *  <li> if the hook can successfully recover the unmodified class definion
     * [perhaps because it cloned the original definition or never modified it]:
     * it should write it into 'out' and return 'true';
     *  <li> if the hook has lost the original class definion: it should return 'false'.
     * Following that, the current loader will close() and discard 'in' and load
     * the class from another, equivalent in content, data stream instance. 
     * </ol>
     * 
     * In any case 'in' and 'out' remain owned [and will be close()d] by the caller.
     * 
     * NOTE: the hook should only write to 'out' after reading the entire
     * class definition in 'bytes' ('out' could be backed by the same array as
     * 'bytes')
     * 
     * @param out [buffered by the caller]
     */
    boolean processClassDef (String className,
                             byte [] bytes, int length,
                             ByteArrayOStream out)
        throws IOException;

} // end of interface
// ----------------------------------------------------------------------------