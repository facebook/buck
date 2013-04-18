/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ICodedException.java,v 1.1.1.1 2004/05/09 16:57:58 vlad_r Exp $
 */
package com.vladium.util.exception;
 
// ----------------------------------------------------------------------------
/**
 * TODO: javadoc
 * 
 * This interface is implemented by {@link AbstractException} and
 * {@link AbstractRuntimeException} to provide a common interface
 * for accessing error codes.<P>
 * 
 * An error code is a compact string representing the nature of exception
 * in a programmatic locale-independent way. It can be used as a key that maps
 * to a human-readable error message in a resource bundle. For details, see
 * the exception classes mentioned above.
 * 
 * @author Vlad Roubtsov, (C) 2002
 */
public
interface ICodedException
{
    // public: ................................................................
    
    /**
     * Returns the String that was passed as 'message' argument to an exception
     * constructor. For a coded exception this will be the compact error code
     * [and different from the result of <code>getMessage()</code>], otherwise
     * this will be traditional error message.
     *
     * @return message code string [can be null]
     */
    String getErrorCode ();

} // end of interface
// ----------------------------------------------------------------------------
