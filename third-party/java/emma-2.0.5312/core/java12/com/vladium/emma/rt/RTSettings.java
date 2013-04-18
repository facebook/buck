/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: RTSettings.java,v 1.1.1.1 2004/05/09 16:57:44 vlad_r Exp $
 */
package com.vladium.emma.rt;

// ----------------------------------------------------------------------------
/**
 * Conceptually, this class is an extention of class RT. This is a separate class,
 * however, to help load RT in a lazy manner.
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class RTSettings
{
    // public: ................................................................
    
    
    public static synchronized boolean isStandaloneMode ()
    {
        return ! s_not_standalone;
    }
    
    public static synchronized void setStandaloneMode (final boolean standalone)
    {
        s_not_standalone = ! standalone;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private RTSettings () {} // prevent subclassing
        
    private static boolean s_not_standalone;

} // end of class
// ----------------------------------------------------------------------------