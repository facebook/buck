/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IJREVersion.java,v 1.1.1.1.2.1 2004/07/10 03:34:52 vlad_r Exp $
 */
package com.vladium.util;

// ----------------------------------------------------------------------------
/**
 * A constant collection to detect the current JRE version. Note that this code
 * is more sophisticated then a mere check of "java.version" or "java.class.version"
 * and similar system properties because some JVMs allow overriding those
 * by the user (e.g., from the command line). This implementation relies on
 * the core classes only and tries to minimize the number of security-sensitive
 * methods it uses.<P>
 * 
 * This interface is supported in Java 1.1+ and should be compiled with class
 * version stamp 45.3 (-target 1.1).
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IJREVersion
{
    // public: ................................................................

    /** 'true' iff the current runtime version is 1.2 or later */
    boolean JRE_1_2_PLUS = _JREVersion._JRE_1_2_PLUS; // static final but not inlinable
    /** 'true' iff the current runtime version is 1.3 or later */
    boolean JRE_1_3_PLUS = _JREVersion._JRE_1_3_PLUS; // static final but not inlinable
    /** 'true' iff the current runtime version is 1.4 or later */
    boolean JRE_1_4_PLUS = _JREVersion._JRE_1_4_PLUS; // static final but not inlinable
    
    // supporting Java 1.5 is trivial...
    
    boolean JRE_SUN_SIGNAL_COMPATIBLE = _JREVersion._JRE_SUN_SIGNAL_COMPATIBLE;
    
    /*
     * Use a dummy nested class to fake a static initializer for the outer
     * interface (I want IJREVersion as an interface and not a class so that
     * all JRE_XXX constants could be imported via "implements").
     */
    abstract class _JREVersion
    {
        static final boolean _JRE_1_2_PLUS; // set in <clinit>
        static final boolean _JRE_1_3_PLUS; // set in <clinit>    
        static final boolean _JRE_1_4_PLUS; // set in <clinit>
        
        static final boolean _JRE_SUN_SIGNAL_COMPATIBLE; // set in <clinit>
        
        private _JREVersion () {} // prevent subclassing
    
        static
        {
            _JRE_1_2_PLUS = ((SecurityManager.class.getModifiers () & 0x0400) == 0);

            boolean temp = false;            
            if (_JRE_1_2_PLUS)
            {
                try
                {
                    StrictMath.abs (1.0);
                    temp = true;
                }
                catch (Error ignore) {}
            }
            _JRE_1_3_PLUS = temp;
            
            if (temp)
            {
                temp = false;
                try
                {
                    " ".subSequence (0, 0);
                    temp = true;
                }
                catch (NoSuchMethodError ignore) {}
            }
            _JRE_1_4_PLUS = temp;
            
            temp = false;
            try
            {
                Class.forName ("sun.misc.Signal");
                Class.forName ("sun.misc.SignalHandler");
                
                temp = true;
            }
            catch (Throwable ignore) {}
            
            _JRE_SUN_SIGNAL_COMPATIBLE = temp;
        }

    } // end of nested class

} // end of interface
// ----------------------------------------------------------------------------

