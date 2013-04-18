/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CoverageOptions.java,v 1.1.1.1.2.1 2004/06/27 22:58:26 vlad_r Exp $
 */
package com.vladium.emma.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class CoverageOptions implements Serializable
{
    // public: ................................................................
    
    public boolean excludeSyntheticMethods ()
    {
        return m_excludeSyntheticMethods;
    }
    
    public boolean excludeBridgeMethods ()
    {
        return m_excludeBridgeMethods;
    }
    
    public boolean doSUIDCompensation ()
    {
        return m_doSUIDCompensation;
    }
    
    // protected: .............................................................

    // package: ...............................................................

    /*
     * Package-private to be accessble by CoverageOptionsFactory
     * (the factory is in a separate source file to avoid spurious
     * classloading dependency via InnerClasses attr)
     */
    CoverageOptions (final boolean excludeSyntheticMethods,
                     final boolean excludeBridgeMethods,
                     final boolean doSUIDCompensation)
    {
        m_excludeSyntheticMethods = excludeSyntheticMethods;
        m_excludeBridgeMethods = excludeBridgeMethods;
        m_doSUIDCompensation = doSUIDCompensation;
    }
    
    
    static CoverageOptions readExternal (final DataInput in)
        throws IOException
    {
        return new CoverageOptions (in.readBoolean (),
                                    in.readBoolean (),
                                    in.readBoolean ());
    }
    
    static void writeExternal (final CoverageOptions options, final DataOutput out)
        throws IOException
    {
        out.writeBoolean (options.m_excludeSyntheticMethods);
        out.writeBoolean (options.m_excludeBridgeMethods);
        out.writeBoolean (options.m_doSUIDCompensation);
    }
    
    // private: ...............................................................

    
    private final boolean m_excludeSyntheticMethods;
    private final boolean m_excludeBridgeMethods;
    private final boolean m_doSUIDCompensation;

} // end of class
// ----------------------------------------------------------------------------