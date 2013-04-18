/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ClassPathCacheEntry.java,v 1.1.1.1 2004/05/09 16:57:43 vlad_r Exp $
 */
package com.vladium.emma.rt;

import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class ClassPathCacheEntry
{
    // public: ................................................................
    
    // getters not provided [the fields are final]
    
    public final byte [] m_bytes;
    public final String m_srcURL; // a String is more compact that java.net.URL
    
    
    public ClassPathCacheEntry (final byte [] bytes, final String srcURL)
    {
        if ($assert.ENABLED)
        {
            $assert.ASSERT (bytes != null, "bytes = null");
            $assert.ASSERT (srcURL != null, "srcURL = null");
        }
        
        m_bytes = bytes;
        m_srcURL = srcURL;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------