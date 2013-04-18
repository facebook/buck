/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ExceptionCommon.java,v 1.1.1.1.2.2 2004/07/10 03:34:52 vlad_r Exp $
 */
package com.vladium.util.exception;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import com.vladium.util.IJREVersion;

// TODO: embed build # in error codes

// ----------------------------------------------------------------------------
/**
 * TODO: javadoc
 * 
 * Based on code <a href="http://www.fawcette.com/javapro/2002_12/online/exception_vroubtsov_12_16_02/default_pf.asp">published</a>
 * by me in JavaPro, 2002.<P>
 * 
 * This non-instantiable class provides static support functions common to
 * {@link AbstractException} and {@link AbstractRuntimeException}.<P>
 *
 * @author Vlad Roubtsov, (C) 2002
 */
abstract class ExceptionCommon implements IJREVersion
{
    // public: ................................................................
               
    /**
     * This method can be called by static initializers of {@link AbstractException}
     * and {@link AbstractRuntimeException} subclasses in order to add another
     * resource bundle to the set that is used to look up error codes. This makes
     * it possible to extend the set of exception error codes across independently
     * maintained and built projects.<P>
     *
     * <BLOCKQUOTE>
     * Note that this introduces a possibility of error code name clashes. This
     * is resolved in the following way:
     * <UL>
     *     <LI> when <CODE>getMessage(namespace, code)</CODE> is called, 'code'
     *     is attempted to be looked up in the resource bundle previously keyed
     *     under 'namespace';
     *
     *     <LI> if no such bundle it found or if it does not contain a value for
     *     key 'code', the same step is repeated for the superclass of 'namespace';
     *
     *     <LI> finally, if all of the above steps fail, the root resource bundle
     *     specified by {@link #ROOT_RESOURCE_BUNDLE_NAME} is searched.
     * </UL>
     * 
     * This strategy ensures that error codes follow inheritance and hiding rules
     * similar to Java static methods.<P>
     * </BLOCKQUOTE>
     *
     * <B>IMPORTANT:</B> this method must be called from static class initializers
     * <I>only</I>.<P>
     *
     * There is no visible state change if the indicated resource is not found
     * or if it has been added already under the same key.<P>
     *
     * @param namespace the Class object acting as the namespace key for the
     * resource bundle identified by 'messageResourceBundleName'. <I>This should
     * be the calling class.</I> [the method is a no-op if this is null]
     *
     * @param messageResourceBundleName name of a bundle (path relative to 'namespace'
     * package) to add to the set from which error code mappings are retrieved
     * [the method is a no-op if this is null or an empty string]
     * 
     * @return ResourceBundle that corresponds to 'namespace' key or null if
     * no such bundle could be loaded
     *
     * @throws Error if 'namespace' does not correspond to an exception class derived
     * from {@link AbstractException} or {@link AbstractRuntimeException}.
     *
     * @see #lookup
     */
    public static ResourceBundle addExceptionResource (final Class namespace,
                                                       final String messageResourceBundleName)
    {
        if ((namespace != null) && (messageResourceBundleName != null)
            && (messageResourceBundleName.length () > 0))
        {
            // bail out if the some other exception hierarchy attempts
            // to use this functionality:
            if (! ABSTRACT_EXCEPTION.isAssignableFrom (namespace)
                && ! ABSTACT_RUNTIME_EXCEPTION.isAssignableFrom (namespace))
            {
                throw new Error ("addExceptionResource(): class [" + namespace +
                    "] is not a subclass of " + ABSTRACT_EXCEPTION.getName () +
                    " or " + ABSTACT_RUNTIME_EXCEPTION.getName ());
            }
            
            // try to load resource bundle
            
            ResourceBundle temprb = null;
            String nameInNamespace = null;
            try
            {
                nameInNamespace = getNameInNamespace (namespace, messageResourceBundleName);
                
                //temprb = ResourceBundle.getBundle (nameInNamespace);
                
                ClassLoader loader = namespace.getClassLoader ();
                if (loader == null) loader = ClassLoader.getSystemClassLoader ();
                
                temprb = ResourceBundle.getBundle (nameInNamespace, Locale.getDefault (), loader);
            }
            catch (Throwable ignore)
            {
               // ignored intentionally: if the exception codes rb is absent,
               // we are still in a supported configuration
               temprb = null;
            }
            
            if (temprb != null)
            {
                synchronized (s_exceptionCodeMap)
                {
                    final ResourceBundle currentrb =
                        (ResourceBundle) s_exceptionCodeMap.get (namespace); 
                    if (currentrb != null)
                        return currentrb;
                    else
                    {
                        s_exceptionCodeMap.put (namespace, temprb);
                        return temprb;
                    }
                }
            }
        }
        
        return null;
    }
    
    // protected: .............................................................

    // package: ...............................................................  


    static void printStackTrace (Throwable t, final PrintWriter out)
    {
        if (JRE_1_4_PLUS)
        {
            if (t instanceof IThrowableWrapper)
            {
                final IThrowableWrapper tw = (IThrowableWrapper) t;
                
                tw.__printStackTrace (out); 
            }
            else
            {
                t.printStackTrace (out);
            }
        }
        else
        {
            for (boolean first = true; t != null; )
            {
                if (first)
                    first = false;
                else
                {
                    out.println ();
                    out.println (NESTED_THROWABLE_HEADER);                
                }
                
                if (t instanceof IThrowableWrapper)
                {
                    final IThrowableWrapper tw = (IThrowableWrapper) t;
                    
                    tw.__printStackTrace (out);
                    t = tw.getCause (); 
                }
                else
                {
                    t.printStackTrace (out);
                    break;
                }
            }
        }
    }
    
    
    static void printStackTrace (Throwable t, final PrintStream out)
    {
        if (JRE_1_4_PLUS)
        {
            if (t instanceof IThrowableWrapper)
            {
                final IThrowableWrapper tw = (IThrowableWrapper) t;
                
                tw.__printStackTrace (out); 
            }
            else
            {
                t.printStackTrace (out);
            }
        }
        else
        {
            for (boolean first = true; t != null; )
            {
                if (first)
                    first = false;
                else
                {
                    out.println ();
                    out.println (NESTED_THROWABLE_HEADER);                
                }
                
                if (t instanceof IThrowableWrapper)
                {
                    final IThrowableWrapper tw = (IThrowableWrapper) t;
                    
                    tw.__printStackTrace (out);
                    t = tw.getCause (); 
                }
                else
                {
                    t.printStackTrace (out);
                    break;
                }
            }
        }
    }
    

    /**
     * Provides support for lookup of exception error codes from {@link AbstractException}
     * and {@link AbstractRuntimeException} and their subclasses.
     *
     * @param namespace the Class object acting as the key to the namespace from
     * which to retrieve the description for 'code' [can be null, in which case
     * only the root namespace is used for lookup]
     *
     * @param code the message string value that was passed into exception
     * constructor [can be null, in which case null is returned].
     *
     * @return looked-up error message or the error code if it could not be
     * looked up [null is returned on null 'code' input only].
     *
     * This method does not throw.
     *
     * @see AbstractException#getMessage
     * @see AbstractRuntimeException#getMessage
     */
    static String getMessage (final Class namespace, final String code)
    {
        if (code == null) return null;
        
        try
        {                      
            if (code.length () > 0)
            {
                // look the code up in the resource bundle:
                final String msg = lookup (namespace, code);     
                if (msg == null)
                {
                    // if code lookup failed, return 'code' as is:
                    return code;
                }
                else
                {
                    return EMBED_ERROR_CODE ? "[" + code + "] " + msg : msg;
                }
           }
           else
           {
               return "";
           }
        }
        catch (Throwable t)
        {
            // this method must never fail: default to returning the input
            // verbatim on all unexpected problems
            return code;
        }
    }
    
    /**
     * Provides support for lookup of exception error codes from {@link AbstractException}
     * and {@link AbstractRuntimeException} and their subclasses.
     *
     * @param namespace the Class object acting as the key to the namespace from
     * which to retrieve the description for 'code' [can be null, in which case
     * only the root namespace is used for lookup]
     *
     * @param code the message string value that was passed into exception
     * constructor [can be null, in which case null is returned].
     *
     * @param arguments java.text.MessageFormat-style parameters to be substituted
     * into the error message once it is looked up. 
     *
     * @return looked-up error message or the error code if it could not be
     * looked up [null is returned on null 'code' input only].
     *
     * This method does not throw.
     *
     * @see AbstractException#getMessage
     * @see AbstractRuntimeException#getMessage
     */
    static String getMessage (final Class namespace, final String code, final Object [] arguments)
    {
        if (code == null) return null;
        final String pattern = getMessage (namespace, code);

        // assertion: pattern != null
        
        if ((arguments == null) || (arguments.length == 0))
        {
            return pattern;
        }
        else
        {
            try
            {
                return MessageFormat.format (pattern, arguments);
            }
            catch (Throwable t)
            {
                // this method cannot fail: default to returning the input
                // verbatim on all unexpected problems:
                
                final StringBuffer msg = new StringBuffer (code + EOL);
                
                for (int a = 0; a < arguments.length; a ++)
                {
                    msg.append ("\t{" + a + "} = [");
                    final Object arg = arguments [a];
                    try
                    {
                        msg.append (arg.toString ());
                    }
                    catch (Throwable e) // guard against bad toString() overrides
                    {
                        if (arg != null)
                            msg.append (arg.getClass ().getName ());
                        else
                            msg.append ("null");
                    }
                    msg.append ("]");
                    msg.append (EOL);
                }
                
                return msg.toString ();
            }
        }
    }
    
    // private: ...............................................................


    private ExceptionCommon () {} // prevent subclassing
        
    /**
     * Internal property lookup method. It implements the lookup scheme described
     * in {@link #addExceptionResource}.
     * 
     * @return property value corresponding to 'propertyName' [null if lookup fails]
     */
    private static String lookup (Class namespace, final String propertyName)
    {
        if (propertyName == null) return null;
        
        // note: the following does not guard against exceptions that do not subclass
        // our base classes [done elsewhere], however it will not crash either
        
        // check extension bundles:
        if (namespace != null)
        {
            ResourceBundle rb;
            while (namespace != ABSTRACT_EXCEPTION && namespace != ABSTACT_RUNTIME_EXCEPTION
                   && namespace != THROWABLE && namespace != null)
            {
                synchronized (s_exceptionCodeMap)
                {
                    rb = (ResourceBundle) s_exceptionCodeMap.get (namespace);
                    if (rb == null)
                    {
                        // check if there is a default bundle to be loaded for this namespace: 
                        if ((rb = addExceptionResource (namespace, "exceptions")) == null)
                        {
                            // add an immutable empty bundle to avoid this check in the future:
                            s_exceptionCodeMap.put (namespace, EMPTY_RESOURCE_BUNDLE);
                        }
                    }
                }
                
                if (rb != null)
                {
                    String propertyValue = null;
                    try
                    {
                        propertyValue = rb.getString (propertyName);
                    }
                    catch (Throwable ignore) {}
                    if (propertyValue != null) return propertyValue;
                }
                
                // walk the inheritance chain for 'namespace':
                namespace = namespace.getSuperclass ();
            }
        }
        
        // if everything fails, check the root bundle:
        if (ROOT_RESOURCE_BUNDLE != null)
        {
            String propertyValue = null;
            try
            {
                propertyValue = ROOT_RESOURCE_BUNDLE.getString (propertyName);
            }
            catch (Throwable ignore) {}
            if (propertyValue != null) return propertyValue;
        }
        
        return null;
    }
    
    private static String getNameInNamespace (final Class namespace, final String name)
    {
        if (namespace == null) return name;
        
        final String namespaceName = namespace.getName ();
        final int lastDot = namespaceName.lastIndexOf ('.');
        
        if (lastDot <= 0)
            return name;
        else
            return namespaceName.substring (0, lastDot + 1)  + name;
    }
    

    // changes this to 'false' to eliminate repetition of error codes in
    // the output of getMessage(): 
    private static final boolean EMBED_ERROR_CODE = true;

    // the name of the 'root' message resource bundle, derived as
    // [this package name + ".exceptions"]:
    private static final String ROOT_RESOURCE_BUNDLE_NAME;      // set in <clinit>
    
    // the root resource bundle; always checked if all other lookups fail:    
    private static final ResourceBundle ROOT_RESOURCE_BUNDLE;   // set in <clinit>
    
    // static cache of all loaded resource bundles, populated via addExceptionResource():
    private static final Map /* Class -> ResourceBundle */ s_exceptionCodeMap = new HashMap ();
    
    // misc constants:
    
    private static final String NESTED_THROWABLE_HEADER = "[NESTED EXCEPTION]:";
    private static final Class THROWABLE                    = Throwable.class;
    private static final Class ABSTRACT_EXCEPTION           = AbstractException.class;
    private static final Class ABSTACT_RUNTIME_EXCEPTION    = AbstractRuntimeException.class;
    /*private*/ static final Enumeration EMPTY_ENUMERATION  = Collections.enumeration (Collections.EMPTY_SET);    
    private static final ResourceBundle EMPTY_RESOURCE_BUNDLE = new ResourceBundle ()
    {
        public Object handleGetObject (final String key)
        {
            return null;
        }
        
        public Enumeration getKeys ()
        {
            return EMPTY_ENUMERATION;
        }
    };
    
    // end-of-line terminator for the current platform:
    private static final String EOL = System.getProperty ("line.separator", "\n");
    
    
    static
    {
        // set the name of ROOT_RESOURCE_BUNDLE_NAME:
        ROOT_RESOURCE_BUNDLE_NAME = getNameInNamespace (ExceptionCommon.class, "exceptions");
                
        // set the root resource bundle:
        ResourceBundle temprb = null;
        try
        {
            temprb = ResourceBundle.getBundle (ROOT_RESOURCE_BUNDLE_NAME);
        }
        catch (Throwable ignore)
        {
            // if the exception codes rb is absent, we are still in a supported configuration
        }
        ROOT_RESOURCE_BUNDLE = temprb;        
    }
    
} // end of class
// ----------------------------------------------------------------------------
