/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: AbstractException.java,v 1.1.1.1 2004/05/09 16:57:57 vlad_r Exp $
 */
package com.vladium.util.exception;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;

// ----------------------------------------------------------------------------
/**
 * Based on code published by me in <a href="http://www.fawcette.com/javapro/2002_12/online/exception_vroubtsov_12_16_02/default_pf.asp">JavaPro, 2002</a>.<P>
 * 
 * This checked exception class is designed as a base/expansion point for the
 * entire hierarchy of checked exceptions in a project.<P>
 * 
 * It provides the following features:
 * <UL>
 *    <LI> ability to take in compact error codes that map to full text messages
 *    in a resource bundle loaded at this class' instantiation time. This avoids
 *    hardcoding the error messages in product code and allows for easy
 *    localization of such text if required. Additionally, these messages
 *    can be parametrized in the java.text.MessageFormat style;
 *    <LI> exception chaining in J2SE versions prior to 1.4
 * </UL>
 * 
 * See {@link AbstractRuntimeException} for an unchecked version of the same class.<P> 
 *
 * TODO: javadoc
 *
 * Each constructor that accepts a String 'message' parameter accepts an error
 * code as well. You are then responsible for ensuring that either the root
 * <CODE>com.vladium.exception.exceptions</CODE> resource bundle
 * or your project/exception class-specific resource bundle [see
 * <A HREF="#details">below</A> for details] contains a mapping for this error
 * code. When this lookup fails the passed String value itself will be used as
 * the error message.<P>
 *
 * All constructors taking an 'arguments' parameter supply parameters to the error
 * message used as a java.text.MessageFormat pattern.<P>
 * 
 * Example:
 * <PRE><CODE>
 *  File file = ...
 *  try
 *  ...
 *  catch (Exception e)
 *  {
 *      throw new AbstractException ("FILE_NOT_FOUND", new Object[] {file, e}, e);
 *  }
 * </CODE></PRE>
 *      where <CODE>com.vladium.util.exception.exceptions</CODE> contains:
 * <PRE><CODE>
 * FILE_NOT_FOUND: file {0} could not be opened: {1}
 * </CODE></PRE>
 *
 * To log exception data use {@link #getMessage} or <CODE>printStackTrace</CODE>
 * family of methods. You should never have to use toString().<P>
 *
 * <A NAME="details"> It is also possible to use project- or exception
 * subhierarchy-specific message resource bundles without maintaining all error
 * codes in <CODE>com.vladium.exception.exceptions</CODE>. To do so, create a
 * custom resource bundle and add the following static initializer code to your
 * base exception class:
 * <PRE><CODE>
 *  static
 *  {
 *      addExceptionResource (MyException.class, "my_custom_resource_bundle");
 *  }
 * </CODE></PRE>
 * The bundle name is relative to MyException package. This step can omitted if
 * the bundle name is "exceptions".
 * 
 * Note that the implementation correctly resolves error code name collisions
 * across independently developed exception families, as long as resource bundles
 * use unique names. Specifically, error codes follow inheritance and hiding rules
 * similar to Java class static methods. See {@link ExceptionCommon#addExceptionResource}
 * for further details.
 *
 * @author Vlad Roubtsov, (C) 2002
 */
public
abstract class AbstractException extends Exception implements ICodedException, IThrowableWrapper
{
    // public: ................................................................

    /**
     * Constructs an exception with null message and null cause.
     */    
    public AbstractException ()
    {
        m_cause = null;
        m_arguments = null;
    }
    
    /**
     * Constructs an exception with given error message/code and null cause.
     *
     * @param message the detail message [can be null]
     */
    public AbstractException (final String message)
    {
        super (message);
        m_cause = null;
        m_arguments = null;
    }
    
    /**
     * Constructs an exception with given error message/code and null cause.
     *   
     * @param message the detail message [can be null]
     * @param arguments message format parameters [can be null or empty]
     *
     * @see java.text.MessageFormat
     */
    public AbstractException (final String message, final Object [] arguments)
    {
        super (message);
        m_cause = null;
        m_arguments = arguments == null ? null : (Object []) arguments.clone ();
    }
    
    /**
     * Constructs an exception with null error message/code and given cause.
     *
     * @param cause the cause [nested exception] [can be null]
     */
    public AbstractException (final Throwable cause)
    {
        super ();
        m_cause = cause;
        m_arguments = null;
    }
    
    /**
     * Constructs an exception with given error message/code and given cause.
     *
     * @param message the detail message [can be null]
     * @param cause the cause [nested exception] [can be null]
     */
    public AbstractException (final String message, final Throwable cause)
    {
        super (message);
        m_cause = cause;
        m_arguments = null;
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
    public AbstractException (final String message, final Object [] arguments, final Throwable cause)
    {
        super (message);
        m_cause = cause;
        m_arguments = arguments == null ? null : (Object []) arguments.clone ();
    }
    

    /**
     * Overrides base method to support error code lookup and avoid returning nulls.
     * Note that this does not recurse into any 'cause' for message lookup, it only
     * uses the data passed into the constructor. Subclasses cannot override.<P>
     *
     * Equivalent to {@link #getLocalizedMessage}.
     *
     * @return String error message provided at construction time or the result
     * of toString() if no/null message was provided [never null].
     */  
    public final String getMessage ()
    {
        if (m_message == null) // not synchronized by design
        {
            String msg;
            final String supermsg = super.getMessage ();
            final Class _class = getClass ();
            
            if (m_arguments == null)
            {
                msg = ExceptionCommon.getMessage (_class, supermsg);
            }
            else
            {
                msg = ExceptionCommon.getMessage (_class, supermsg, m_arguments);
            }
            
            if (msg == null)
            {
                // this is the same as what's done in Throwable.toString() [copied here to be independent of future JDK changes]
                final String className = _class.getName ();
                
                msg = (supermsg != null) ? (className + ": " + supermsg) : className;
            }
            
            m_message = msg;
        }
        
        return m_message;
    }
    
    /**
     * Overrides base method for the sole purpose of making it final.<P>
     * 
     * Equivalent to {@link #getMessage}.
     */
    public final String getLocalizedMessage ()
    {
        // this is the same as what's done in Throwable
        // [copied here to be independent of future JDK changes]
        return getMessage ();
    }

    /**
     * Overrides Exception.printStackTrace() to (a) force the output to go
     * to System.out and (b) handle nested exceptions in JDKs prior to 1.4.<P>
     * 
     * Subclasses cannot override.
     */    
    public final void printStackTrace ()
    {
        // NOTE: unlike the JDK implementation, force the output to go to System.out:
        ExceptionCommon.printStackTrace (this, System.out);
    }
    
    /**
     * Overrides Exception.printStackTrace() to handle nested exceptions in JDKs prior to 1.4.<P>
     * 
     * Subclasses cannot override.
     */    
    public final void printStackTrace (final PrintStream s)
    {
        ExceptionCommon.printStackTrace (this, s);
    }
    
    /**
     * Overrides Exception.printStackTrace() to handle nested exceptions in JDKs prior to 1.4.<P>
     * 
     * Subclasses cannot override.
     */ 
    public final void printStackTrace (final PrintWriter s)
    {
        ExceptionCommon.printStackTrace (this, s);
    }

    // ICodedException:
    
    /**
     * Returns the String that was passed as 'message' constructor argument.
     * Can be null.
     *
     * @return message code string [can be null]
     */
    public final String getErrorCode ()
    {
        return super.getMessage ();
    }

    // IThrowableWrapper:
    
    /**
     * This implements {@link IThrowableWrapper}
     * and also overrides the base method in JDK 1.4+.
     */
    public final Throwable getCause ()
    {
        return m_cause;
    }

    public void __printStackTrace (final PrintStream ps)
    {
        super.printStackTrace (ps);
    }
    
    public void __printStackTrace (final PrintWriter pw)
    {
        super.printStackTrace (pw);
    }

    /**
     * Equivalent to {@link ExceptionCommon#addExceptionResource}, repeated here for
     * convenience. Subclasses should invoke from static initializers <I>only</I>.
     * 'namespace' should be YourException.class.
     */
    public static void addExceptionResource (final Class namespace,
                                             final String messageResourceBundleName)
    {
        // note: 'namespace' will be the most derived class; it is possible to
        // auto-detect that in a static method but that requires some security
        // permissions
        ExceptionCommon.addExceptionResource (namespace, messageResourceBundleName);
    }
    
    // protected: .............................................................

    // package: ...............................................................

    // private: ...............................................................


    /*
     * Ensures that this instance can be serialized even if some message parameters
     * are not serializable objects.
     */
    private void writeObject (final ObjectOutputStream out)
        throws IOException
    {
        getMessage (); // transform this instance to serializable form
        out.defaultWriteObject ();
    }

    
    private String m_message; // marshalled/cached result of getMessage()
    private transient final Object [] m_arguments;
    // note: this field duplicates functionality available in stock Throwable in JRE 1.4+
    private final Throwable m_cause;
    
} // end of class
// ----------------------------------------------------------------------------
