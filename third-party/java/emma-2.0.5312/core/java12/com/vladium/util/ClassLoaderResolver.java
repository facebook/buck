/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ClassLoaderResolver.java,v 1.1.1.1.2.2 2004/07/10 03:34:53 vlad_r Exp $
 */
package com.vladium.util;

// ----------------------------------------------------------------------------
/**
 * This non-instantiable non-subclassable class acts as the global point for
 * choosing a ClassLoader for dynamic class/resource loading at any point
 * in an application.
 * 
 * @see ResourceLoader
 * @see IClassLoadStrategy
 * @see DefaultClassLoadStrategy
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class ClassLoaderResolver
{
    // public: ................................................................
    
    // NOTE: don't use Logger in this class to avoid infinite recursion

    /**
     * This method selects the "best" classloader instance to be used for
     * class/resource loading by whoever calls this method. The decision
     * typically involves choosing between the caller's current, thread context,
     * system, and other classloaders in the JVM and is made by the {@link IClassLoadStrategy}
     * instance established by the last call to {@link #setStrategy}.<P>
     * 
     * This method does not throw.
     * 
     * @param caller [null input eliminates the caller's current classloader
     * from consideration]
     * 
     * @return classloader to be used by the caller ['null' indicates the
     * primordial loader]
     */
    public static synchronized ClassLoader getClassLoader (final Class caller)
    {
        final ClassLoadContext ctx = new ClassLoadContext (caller);
        
        return s_strategy.getClassLoader (ctx); 
    }

    /**
     * This method selects the "best" classloader instance to be used for
     * class/resource loading by whoever calls this method. The decision
     * typically involves choosing between the caller's current, thread context,
     * system, and other classloaders in the JVM and is made by the {@link IClassLoadStrategy}
     * instance established by the last call to {@link #setStrategy}.<P>
     * 
     * This method uses its own caller to set the call context. To be able to
     * override this decision explicitly, use {@link #getClassLoader(Class)}.<P> 
     * 
     * This method does not throw.
     * 
     * @return classloader to be used by the caller ['null' indicates the
     * primordial loader]
     */
    public static synchronized ClassLoader getClassLoader ()
    {
        final Class caller = getCallerClass (1); // 'caller' can be set to null
        final ClassLoadContext ctx = new ClassLoadContext (caller);
        
        return s_strategy.getClassLoader (ctx); 
    }

    /*
     * Indexes into the current method call context with a given offset. Offset 0
     * corresponds to the immediate caller, offset 1 corresponds to its caller,
     * etc.<P>
     * 
     * Invariant: getCallerClass(0) == class containing code that performs this call 
     */
    public static Class getCallerClass (final int callerOffset)
    {
        if (CALLER_RESOLVER == null) return null; // only happens if <clinit> failed

        return CALLER_RESOLVER.getClassContext () [CALL_CONTEXT_OFFSET + callerOffset];
    }

    /**
     * Returns 'true' if 'loader2' is a delegation child of 'loader1' [or if
     * 'loader1'=='loader2']. Of course, this works only for classloaders that
     * set their parent pointers correctly. 'null' is interpreted as the
     * primordial loader [i.e., everybody's parent].
     */ 
    public static boolean isChild (final ClassLoader loader1, ClassLoader loader2)
    {
        if (loader1 == loader2) return true; 
        if (loader2 == null) return false; 
        if (loader1 == null) return true;
        
        for ( ; loader2 != null; loader2 = loader2.getParent ())
        {
            if (loader2 == loader1) return true;
        }   

        return false;
    }

    /**
     * Gets the current classloader selection strategy setting. 
     */
    public static synchronized IClassLoadStrategy getStrategy ()
    {
        return s_strategy;
    }

    /**
     * Sets the classloader selection strategy to be used by subsequent calls
     * to {@link #getClassLoader()}. An instance of {@link ClassLoaderResolver.DefaultClassLoadStrategy}
     * is in effect if this method is never called.
     *  
     * @param strategy new strategy [may not be null] 
     * @return previous setting
     */    
    public static synchronized IClassLoadStrategy setStrategy (final IClassLoadStrategy strategy)
    {
        if (strategy == null) throw new IllegalArgumentException ("null input: strategy");
        
        final IClassLoadStrategy old = s_strategy;
        s_strategy = strategy;
        
        return old;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private static final class DefaultClassLoadStrategy implements IClassLoadStrategy
    {
        public ClassLoader getClassLoader (final ClassLoadContext ctx)
        {
            if (ctx == null) throw new IllegalArgumentException ("null input: ctx");
            
            final Class caller = ctx.getCallerClass ();
            final ClassLoader contextLoader = Thread.currentThread ().getContextClassLoader ();
            
            ClassLoader result;
            
            if (caller == null)
                result = contextLoader;
            else
            {
                final ClassLoader callerLoader = caller.getClassLoader ();
                
                // if 'callerLoader' and 'contextLoader' are in a parent-child
                // relationship, always choose the child:
                
                // SF BUG 975080: change the sibling case to use 'callerLoader'
                // to work around ANT 1.6.x incorrect classloading model:
                
                if (isChild (callerLoader, contextLoader))
                    result = contextLoader;
                else
                    result = callerLoader;
            }
            
            final ClassLoader systemLoader = ClassLoader.getSystemClassLoader ();
            
            // precaution for when deployed as a bootstrap or extension class:
            if (isChild (result, systemLoader))
                result = systemLoader;
            
            return result;
        }
    
    } // end of nested class
    
    
    /**
     * A helper class to get the call context. It subclasses SecurityManager
     * to make getClassContext() accessible. An instance of CallerResolver
     * only needs to be created, not installed as an actual security
     * manager.
     */
    private static final class CallerResolver extends SecurityManager
    {
        protected Class [] getClassContext ()
        {
            return super.getClassContext ();
        }
        
    } // end of nested class 
    
    
    private ClassLoaderResolver () {} // prevent subclassing

    
    private static IClassLoadStrategy s_strategy; // initialized in <clinit>
    
    private static final int CALL_CONTEXT_OFFSET = 2; // may need to change if this class is redesigned
    private static final CallerResolver CALLER_RESOLVER; // set in <clinit>
    //private static Throwable CLINIT_FAILURE;
    
    static
    {
        CallerResolver temp = null;
        try
        {
            // this can fail if the current SecurityManager does not allow
            // RuntimePermission ("createSecurityManager"):
            
            temp = new CallerResolver ();
        }
        catch (Throwable t)
        {
            //CLINIT_FAILURE = t;
        }
        CALLER_RESOLVER = temp;
        
        s_strategy = new DefaultClassLoadStrategy ();
    }

} // end of class
// ----------------------------------------------------------------------------