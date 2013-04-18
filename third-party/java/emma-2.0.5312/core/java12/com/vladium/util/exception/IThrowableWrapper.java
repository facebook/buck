/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IThrowableWrapper.java,v 1.1.1.1 2004/05/09 16:57:58 vlad_r Exp $
 */
package com.vladium.util.exception;

import java.io.PrintStream;
import java.io.PrintWriter;

// ----------------------------------------------------------------------------
/**
 * TODO: javadoc
 * 
 * Any exception that wraps around another exception and wishes to be fully 
 * inspectable by {@link ExceptionCommon} should implement this interface.
 * Note that JDK 1.4+ obsoletes the need for an explicit interface like this,
 * although the implementation in {@link ExceptionCommon} is upwards compatible
 * with it.
 * 
 * @author Vlad Roubtsov, (C) 2002
 */
interface IThrowableWrapper
{
    // public: ................................................................

    /**
     * Gets the Throwable being wrapped. This method signature is the same as
     * Throwable.getCause() in J2SE 1.4.
     * 
     * @return Throwable being wrapped by this object [can be null].
     */
    Throwable getCause ();
     
    /**
     * Every exception hierarchy implementing this interface must ensure that
     * this method delegates to super.printStackTrace(pw) where 'super' is the
     * first superclass not implementing IThrowableWrapper. This is used by
     * {@link ExceptionCommon} to avoid infinite
     * recursion and is not meant to be called by other classes.
     */
    void __printStackTrace (PrintWriter pw);

    /**
     * Every exception hierarchy implementing this interface must ensure that
     * this method delegates to super.printStackTrace(ps) where 'super' is the
     * first superclass not implementing IThrowableWrapper. This is used by
     * {@link ExceptionCommon} to avoid infinite
     * recursion and is not meant to be called by other classes.
     */
    void __printStackTrace (PrintStream ps);

} // end of interface
// ----------------------------------------------------------------------------
