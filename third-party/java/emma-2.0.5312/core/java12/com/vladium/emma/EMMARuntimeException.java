/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: EMMARuntimeException.java,v 1.1.1.1 2004/05/09 16:57:29 vlad_r Exp $
 */
package com.vladium.emma;

import com.vladium.util.exception.AbstractRuntimeException;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
class EMMARuntimeException extends AbstractRuntimeException
{
    // public: ................................................................

    /**
     * Constructs an exception with null message and null cause.
     */    
    public EMMARuntimeException ()
    {
    }
    
    /**
     * Constructs an exception with given error message/code and null cause.
     *
     * @param message the detail message [can be null]
     */
    public EMMARuntimeException (final String message)
    {
        super (message);
    }
    
    /**
     * Constructs an exception with given error message/code and null cause.
     *   
     * @param message the detail message [can be null]
     * @param arguments message format parameters [can be null or empty]
     *
     * @see java.text.MessageFormat
     */
    public EMMARuntimeException (final String message, final Object [] arguments)
    {
        super (message, arguments);
    }
    
    /**
     * Constructs an exception with null error message/code and given cause.
     *
     * @param cause the cause [nested exception] [can be null]
     */
    public EMMARuntimeException (final Throwable cause)
    {
        super (cause);
    }
    
    /**
     * Constructs an exception with given error message/code and given cause.
     *
     * @param message the detail message [can be null]
     * @param cause the cause [nested exception] [can be null]
     */
    public EMMARuntimeException (final String message, final Throwable cause)
    {
        super (message, cause);
    }
    
    /**
     * Constructs an exception with given error message/code and given cause.
     *
     * @param message the detail message [can be null]
     * @param arguments message format parameters [can be null or empty]
     * @param cause the cause [nested exception] [can be null]
     *
     * @see java.text.MessageFormat
     */
    public EMMARuntimeException (final String message, final Object [] arguments, final Throwable cause)
    {
        super (message, arguments, cause);
    }
    
    // protected: .............................................................

    // package: ...............................................................

    // private: ...............................................................
    
} // end of class
// ----------------------------------------------------------------------------
