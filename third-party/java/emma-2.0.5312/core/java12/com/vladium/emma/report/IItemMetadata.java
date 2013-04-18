/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IItemMetadata.java,v 1.1.1.1 2004/05/09 16:57:37 vlad_r Exp $
 */
package com.vladium.emma.report;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IItemMetadata
{
    // public: ................................................................

    // note: order is in sync with Factory init code    
    int TYPE_ID_ALL         = 0;
    int TYPE_ID_PACKAGE     = 1;
    int TYPE_ID_SRCFILE     = 2;
    int TYPE_ID_CLASS       = 3;
    int TYPE_ID_METHOD      = 4;
    
    int getTypeID ();
    String getTypeName ();
    
    /**
     * Using a long is only ok for less than 64 global attributes, but this limit
     * seems ok for a long time to come. 
     * 
     * @return bitmask for valid attributes
     */
    long getAttributeIDs ();
    
    abstract class Factory
    {
        public static IItemMetadata getTypeMetadata (final int typeID)
        {
            if ((typeID < TYPE_ID_ALL) || (typeID > TYPE_ID_METHOD))
                throw new IllegalArgumentException ("invalid type ID: " + typeID);
                
            return METADATA [typeID];
        }
        
        public static IItemMetadata [] getAllTypes ()
        {
            return METADATA;
        }
        
        private Factory () {}
        
        
        private static final IItemMetadata [] METADATA; // set in <clinit>
        
        static
        {
            // this establishes the mapping TYPE_ID_xxx->metadata for type xxx:
            
            METADATA = new IItemMetadata []
            {
                AllItem.getTypeMetadata (),
                PackageItem.getTypeMetadata (),
                SrcFileItem.getTypeMetadata (),
                ClassItem.getTypeMetadata (),
                MethodItem.getTypeMetadata (),
            };
        }
        
    } // end of nested class 
    

} // end of interface
// ----------------------------------------------------------------------------