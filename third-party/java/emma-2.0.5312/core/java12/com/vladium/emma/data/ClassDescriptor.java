/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ClassDescriptor.java,v 1.1.1.1 2004/05/09 16:57:30 vlad_r Exp $
 */
package com.vladium.emma.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;

import com.vladium.util.IConstants;
import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class ClassDescriptor implements IConstants, Serializable
{
    // public: ................................................................
    
    
    public ClassDescriptor (final String packageVMName, final String name, final long stamp,
                            final String srcFileName,
                            final MethodDescriptor [] methods)
    {
        if (packageVMName == null)
            throw new IllegalArgumentException ("null input: packageVMName");
        if (name == null)
            throw new IllegalArgumentException ("null input: name");
        if (methods == null)
            throw new IllegalArgumentException ("null input: methods");
        
        if ($assert.ENABLED)
        {
            for (int m = 0; m < methods.length; ++ m)
            {
                $assert.ASSERT (methods [m] != null, "methods [" + m + "] = null (length = " + methods.length + ")");
            }
        }
        
        m_packageVMName = packageVMName;
        m_name = name;
        m_stamp = stamp;
        m_srcFileName = srcFileName;
        m_methods = methods; // TODO: defensive copy?
        
        boolean completeLineNumberInfo = true;
        for (int m = 0; m < m_methods.length; ++ m)
        {
            final MethodDescriptor method = methods [m];
            
            if (((method.getStatus () & IMetadataConstants.METHOD_NO_BLOCK_DATA) == 0) && ! m_methods [m].hasLineNumberInfo ())
            {
                completeLineNumberInfo = false;
                break;
            }
        }
        
        m_hasCompleteLineNumberInfo = completeLineNumberInfo;
    }
    
    
    // equality is defined based on <m_packageVMName, m_name> only (m_stamp not mixed in by design):
    
    public final boolean equals (final Object rhs)
    {
        if (! (rhs instanceof ClassDescriptor)) return false;
        
        final ClassDescriptor _rhs = (ClassDescriptor) rhs;
        
        if (hashCode () != _rhs.hashCode ()) return false;
        
        if (! m_name.equals (_rhs.m_name)) return false;
        if (! m_packageVMName.equals (_rhs.m_packageVMName)) return false;
        
        return true;
    }
    
    public final int hashCode ()
    {
        if (m_hash == 0)
        {
            final int hash = m_name.hashCode () + 16661 * m_packageVMName.hashCode ();
            m_hash = hash;
            
            return hash;
        }
        
        return m_hash;
    }
    
    
    public final String getPackageVMName ()
    {
        return m_packageVMName;
    }
    
    public final String getName ()
    {
        return m_name;
    }
    
    public final long getStamp ()
    {
        return m_stamp;
    }
    
    public final String getClassVMName ()
    {
        // TODO: use Descriptors API?
        if (m_packageVMName.length () == 0)
            return m_name;
        else
            return new StringBuffer (m_packageVMName).append ("/").append (m_name).toString ();
    }
    
    public final String getSrcFileName ()
    {
        return m_srcFileName;
    }
    
    public final MethodDescriptor [] getMethods ()
    {
        return m_methods; // no defensive copy
    }
    
    public final boolean hasSrcFileInfo ()
    {
        return m_srcFileName != null;
    }
    
    public final boolean hasCompleteLineNumberInfo ()
    {
        return m_hasCompleteLineNumberInfo;
    }
    
    
    public String toString ()
    {
        return toString ("");
    }
    
    public String toString (final String indent)
    {
        StringBuffer s = new StringBuffer (indent + "class [" + (m_packageVMName.length () > 0 ? m_packageVMName + "/" : "") + m_name + "] descriptor:");
        
        for (int m = 0; m < m_methods.length; ++ m)
        {
            s.append (EOL);
            s.append (m_methods [m].toString (indent + INDENT_INCREMENT));
        }
        
        return s.toString ();
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    
    static ClassDescriptor readExternal (final DataInput in)
        throws IOException
    {
        final String packageVMName = in.readUTF ();
        final String name = in.readUTF ();
        
        final long stamp = in.readLong ();
        
        final byte srcFileNameFlag = in.readByte ();
        final String srcFileName = srcFileNameFlag != 0 ? in.readUTF () : null;
        
        final int length = in.readInt ();
        final MethodDescriptor [] methods = new MethodDescriptor [length];
        for (int i = 0; i < length; ++ i)
        {
            methods [i] = MethodDescriptor.readExternal (in);
        }
        
        return new ClassDescriptor (packageVMName, name, stamp, srcFileName, methods);
    }
    
    static void writeExternal (final ClassDescriptor cls, final DataOutput out)
        throws IOException
    {
        out.writeUTF (cls.m_packageVMName);
        out.writeUTF (cls.m_name);
        
        out.writeLong (cls.m_stamp);
        
        if (cls.m_srcFileName != null)
        {
            out.writeByte (1);
            out.writeUTF (cls.m_srcFileName);
        }
        else
        {
            out.writeByte (0);
        }
        
        final MethodDescriptor [] methods = cls.m_methods;
        final int length = methods.length;
        out.writeInt (length);
        for (int i = 0; i < length; ++ i)
        {
            MethodDescriptor.writeExternal (methods [i], out);
        }
        
        // [m_hash and m_hasCompleteLineNumberInfo are transient data]
    }
    
    // private: ...............................................................
    
    
    private final String m_packageVMName; // in JVM format, no trailing '/' [never null]
    private final String m_name; // relative to (m_packageName + '/') [never null]
    private final long m_stamp;
    private final String m_srcFileName; // relative to (m_packageName + '/') [can be null]
    private final MethodDescriptor [] m_methods; // [never null, could be empty]
    
    private final boolean m_hasCompleteLineNumberInfo;
    private transient int m_hash;

} // end of class
// ----------------------------------------------------------------------------
