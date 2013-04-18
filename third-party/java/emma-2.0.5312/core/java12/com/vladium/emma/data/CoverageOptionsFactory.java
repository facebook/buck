/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CoverageOptionsFactory.java,v 1.1.2.1 2004/06/27 22:58:26 vlad_r Exp $
 */
package com.vladium.emma.data;

import java.util.Properties;

import com.vladium.emma.instr.InstrProcessor;
import com.vladium.util.IProperties;
import com.vladium.util.Property;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2004
 */
public
abstract class CoverageOptionsFactory
{
    // public: ................................................................

    public static CoverageOptions create (final Properties properties)
    {
        final boolean excludeSyntheticMethods =
            Property.toBoolean (properties.getProperty (InstrProcessor.PROPERTY_EXCLUDE_SYNTHETIC_METHODS,
                                                        InstrProcessor.DEFAULT_EXCLUDE_SYNTHETIC_METHODS));
                                                                        
        final boolean excludeBridgeMethods =
            Property.toBoolean (properties.getProperty (InstrProcessor.PROPERTY_EXCLUDE_BRIDGE_METHODS,
                                                        InstrProcessor.DEFAULT_EXCLUDE_BRIDGE_METHODS));
                                                                                            
        final boolean doSUIDCompensaton =
            Property.toBoolean (properties.getProperty (InstrProcessor.PROPERTY_DO_SUID_COMPENSATION,
                                                        InstrProcessor.DEFAULT_DO_SUID_COMPENSATION));
        
        return new CoverageOptions (excludeSyntheticMethods, excludeBridgeMethods, doSUIDCompensaton);
    }
    
    public static CoverageOptions create (final IProperties properties)
    {
        final boolean excludeSyntheticMethods =
            Property.toBoolean (properties.getProperty (InstrProcessor.PROPERTY_EXCLUDE_SYNTHETIC_METHODS,
                                                        InstrProcessor.DEFAULT_EXCLUDE_SYNTHETIC_METHODS));
        
        final boolean excludeBridgeMethods =
            Property.toBoolean (properties.getProperty (InstrProcessor.PROPERTY_EXCLUDE_BRIDGE_METHODS,
                                                        InstrProcessor.DEFAULT_EXCLUDE_BRIDGE_METHODS));
                                                                                         
        final boolean doSUIDCompensaton =
            Property.toBoolean (properties.getProperty (InstrProcessor.PROPERTY_DO_SUID_COMPENSATION,
                                                        InstrProcessor.DEFAULT_DO_SUID_COMPENSATION));
        
        return new CoverageOptions (excludeSyntheticMethods, excludeBridgeMethods, doSUIDCompensaton);
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................


    private CoverageOptionsFactory () {} // this class is not extendible

} // end of class
// ----------------------------------------------------------------------------