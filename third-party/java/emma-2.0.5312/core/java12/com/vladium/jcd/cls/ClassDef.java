/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ClassDef.java,v 1.1.1.1.2.1 2004/07/16 23:32:30 vlad_r Exp $
 */
package com.vladium.jcd.cls;

import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import com.vladium.jcd.cls.attribute.AttributeElementFactory;
import com.vladium.jcd.cls.attribute.Attribute_info;
import com.vladium.jcd.cls.attribute.CodeAttribute_info;
import com.vladium.jcd.cls.attribute.InnerClassesAttribute_info;
import com.vladium.jcd.cls.constant.CONSTANT_Class_info;
import com.vladium.jcd.cls.constant.CONSTANT_Fieldref_info;
import com.vladium.jcd.cls.constant.CONSTANT_NameAndType_info;
import com.vladium.jcd.cls.constant.CONSTANT_String_info;
import com.vladium.jcd.cls.constant.CONSTANT_Utf8_info;
import com.vladium.jcd.compiler.IClassFormatOutput;
import com.vladium.jcd.lib.Types;
import com.vladium.jcd.lib.UDataOutputStream;
import com.vladium.util.ByteArrayOStream;

// ----------------------------------------------------------------------------
/**
 * This class represents the abstract syntax table (AST) that {@link com.vladium.jcd.parser.ClassDefParser}
 * produces from bytecode. Most elements are either settable or extendible.
 * This class also implements {@link com.vladium.jcd.compiler.IClassFormatOutput}
 * and works with {@link com.vladium.jcd.compiler.ClassWriter} to produce
 * bytecode without an external compiler.<P>
 * 
 * MT-safety: this class and all interfaces used by it are not safe for
 * access from multiple concurrent threads. 
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class ClassDef implements Cloneable, IAccessFlags, IClassFormatOutput
{
    // public: ................................................................


    public ClassDef ()
    {
        m_version = new int [2];

        m_constants = ElementFactory.newConstantCollection (-1);
        m_interfaces = ElementFactory.newInterfaceCollection (-1);
        m_fields = ElementFactory.newFieldCollection (-1);
        m_methods = ElementFactory.newMethodCollection (-1);
        m_attributes = ElementFactory.newAttributeCollection (-1);
    }
    
    // Visitor:
    
    public void accept (final IClassDefVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }


    public long getMagic ()
    {
        return m_magic;
    }
    
    public void setMagic (final long magic)
    {
        m_magic = magic;
    }
    
    
    public int [] getVersion ()
    {
        return m_version;
    }
    
    public void setVersion (final int [] version)
    {
        m_version [0] = version [0];
        m_version [1] = version [1];
    }
    
    public final void setDeclaredSUID (final long suid)
    {
        m_declaredSUID = suid;
    }
    

    public int getThisClassIndex ()
    {
        return m_this_class_index;
    }
    
    public void setThisClassIndex (final int this_class_index)
    {
        m_this_class_index = this_class_index;
    }
    
    public CONSTANT_Class_info getThisClass ()
    {
        return (CONSTANT_Class_info) m_constants.get (m_this_class_index);
    }
    
    public CONSTANT_Class_info getSuperClass ()
    {
        return (CONSTANT_Class_info) m_constants.get (m_super_class_index);
    }
    
    public String getName ()
    {
        return getThisClass ().getName (this);
    }


    public int getSuperClassIndex ()
    {
        return m_super_class_index;
    }
    
    public void setSuperClassIndex (final int super_class_index)
    {
        m_super_class_index = super_class_index;
    }
    
    // IAccessFlags:

    public final int getAccessFlags ()
    {
        return m_access_flags;
    }

    public final void setAccessFlags (final int flags)
    {
        m_access_flags = flags;
    }
    
    public boolean isInterface ()
    {
        return (m_access_flags & ACC_INTERFACE) != 0;
    }
    
    public boolean isSynthetic ()
    {
        return m_attributes.hasSynthetic ();
    }
    
    public boolean isNested (final int [] nestedAccessFlags)
    {
        final InnerClassesAttribute_info innerClassesAttribute = m_attributes.getInnerClassesAttribute ();
        
        if (innerClassesAttribute == null)
            return false;
        else
            return innerClassesAttribute.makesClassNested (m_this_class_index, nestedAccessFlags);
    }
    
    // methods for getting various nested tables:
    
    public IConstantCollection getConstants ()
    {
        return m_constants;
    }
    
    public IInterfaceCollection getInterfaces ()
    {
        return m_interfaces;
    }
    
    public IFieldCollection getFields ()
    {
        return m_fields;
    }
    
    public IMethodCollection getMethods ()
    {
        return m_methods;
    }
    
    public IAttributeCollection getAttributes ()
    {
        return m_attributes;
    }
    
    public int [] getFields (final String name)
    {
        return m_fields.get (this, name);
    }
    
    public int [] getMethods (final String name)
    {
        return m_methods.get (this, name);
    }
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        try
        {
            final ClassDef _clone = (ClassDef) super.clone ();
            
            // do deep copy:
            _clone.m_version = (int []) m_version.clone ();
            _clone.m_constants = (IConstantCollection) m_constants.clone ();
            _clone.m_interfaces = (IInterfaceCollection) m_interfaces.clone ();
            _clone.m_fields = (IFieldCollection) m_fields.clone ();
            _clone.m_methods = (IMethodCollection) m_methods.clone ();
            _clone.m_attributes = (IAttributeCollection) m_attributes.clone ();
            
            return _clone;
        }
        catch (CloneNotSupportedException e)
        {
            throw new InternalError (e.toString ());
        }
    }
    
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        if (out == null) throw new IllegalArgumentException ("null input: out");
        
        out.writeU4 (m_magic);
        
        out.writeU2 (m_version [1]);
        out.writeU2 (m_version [0]);
        
        m_constants.writeInClassFormat (out);
        
        out.writeU2 (m_access_flags);
        
        out.writeU2 (m_this_class_index);
        out.writeU2 (m_super_class_index);
        
        m_interfaces.writeInClassFormat (out);
        m_fields.writeInClassFormat (out);
        m_methods.writeInClassFormat (out);
        m_attributes.writeInClassFormat (out);
    }
    
    public final long getDeclaredSUID ()
    {
        return m_declaredSUID;
    }
    
    /**
     * This follows the spec at http://java.sun.com/j2se/1.4.1/docs/guide/serialization/spec/class.doc6.html#4100
     * as well as undocumented hacks used by Sun's 1.4.2 J2SDK
     */
    public final long computeSUID (final boolean skipCLINIT)
    {
        long result = m_declaredSUID;
        if (result != 0L)
            return result;
        else
        {
            try
            {
                final ByteArrayOStream bout = new ByteArrayOStream (1024); // TODO: reuse these 
                final DataOutputStream dout = new DataOutputStream (bout);
                
                // (1) The class name written using UTF encoding: 

                dout.writeUTF (Types.vmNameToJavaName (getName ())); // [in Java format]
                
                // (2) The class modifiers written as a 32-bit integer:
                
                // ACC_STATIC is never written for nested classes/interfaces;
                // however, ACC_SUPER must be ignored:
                {
                    // this is tricky: for static/non-static nested classes that
                    // were declared protected in the source the usual access flags
                    // will have ACC_PUBLIC set; the only way to achieve J2SDK
                    // compatibility is to recover the source access flags
                    // from the InnerClasses attribute:
                    
                    final int [] nestedAccessFlags = new int [1];
                    
                    final int modifiers = (isNested (nestedAccessFlags)
                            ? nestedAccessFlags [0]
                            : getAccessFlags ())
                        & (ACC_PUBLIC | ACC_FINAL | ACC_INTERFACE | ACC_ABSTRACT);

                    // if/when emma decides to instrument interfaces for <clinit>
                    // coverage, compensate for javac bug in which ABSTRACT bit
                    // was set for an interface only if the interface declared methods
                    // [Sun's J2SDK]:
                    
                    dout.writeInt (modifiers);
                }
                
                // not doing another J2SDK compensation for arrays, because
                // we never load/instrument those 
                
                // (3) The name of each interface sorted by name written using UTF encoding
                {
                    final IInterfaceCollection interfaces = getInterfaces ();
                    final String [] ifcs = new String [interfaces.size ()];
                    
                    final int iLimit = ifcs.length;
                    for (int i = 0; i < iLimit; ++ i)
                    {
                        // [in Java format]
                        ifcs [i] = Types.vmNameToJavaName (((CONSTANT_Class_info) m_constants.get (interfaces.get (i))).getName (this));
                    }
                    
                    Arrays.sort (ifcs);
                    for (int i = 0; i < iLimit; ++ i)
                    {
                        dout.writeUTF (ifcs [i]);
                    }
                }
                
                // (4) For each field of the class sorted by field name (except
                // private static and private transient fields):
                //      a. The name of the field in UTF encoding. 
                //      b. The modifiers of the field written as a 32-bit integer. 
                //      c. The descriptor of the field in UTF encoding 
                {
                    final IFieldCollection fields = getFields ();
                    final FieldDescriptor [] fds = new FieldDescriptor [fields.size ()];
                    
                    int fcount = 0;
                    for (int f = 0, fLimit = fds.length; f < fLimit; ++ f)
                    {
                        final Field_info field = fields.get (f);
                        final int modifiers = field.getAccessFlags ();
                        
                        if (((modifiers & ACC_PRIVATE) == 0) ||
                            ((modifiers & (ACC_STATIC | ACC_TRANSIENT)) == 0))
                            fds [fcount ++] = new FieldDescriptor (field.getName (this), modifiers, field.getDescriptor (this));
                    }
                    
                    if (fcount > 0)
                    {
                        Arrays.sort (fds, 0, fcount);
                        for (int i = 0; i < fcount; ++ i)
                        {
                            final FieldDescriptor fd = fds [i];
                            
                            dout.writeUTF (fd.m_name);
                            dout.writeInt (fd.m_modifiers);
                            dout.writeUTF (fd.m_descriptor);
                        }
                    }
                }
                
                // (5) If a class initializer exists, write out the following: 
                //      a. The name of the method, <clinit>, in UTF encoding. 
                //      b. The modifier of the method, ACC_STATIC, written as a 32-bit integer. 
                //      c. The descriptor of the method, ()V, in UTF encoding. 
                // (6) For each non-private constructor sorted by method name and signature: 
                //      a. The name of the method, <init>, in UTF encoding. 
                //      b. The modifiers of the method written as a 32-bit integer. 
                //      c. The descriptor of the method in UTF encoding. 
                // (7) For each non-private method sorted by method name and signature: 
                //      a. The name of the method in UTF encoding. 
                //      b. The modifiers of the method written as a 32-bit integer. 
                //      c. The descriptor of the method in UTF encoding.
                
                // note: although this is not documented, J2SDK code uses '.''s as
                // descriptor separators (this is done for methods only, not for fields)
                {
                    final IMethodCollection methods = getMethods ();
                    
                    boolean hasCLINIT = false;
                    final ConstructorDescriptor [] cds = new ConstructorDescriptor [methods.size ()];
                    final MethodDescriptor [] mds = new MethodDescriptor [cds.length];
                    
                    int ccount = 0, mcount = 0;
                    
                    for (int i = 0, iLimit = cds.length; i < iLimit; ++ i)
                    {
                        final Method_info method = methods.get (i);
                        
                        final String name = method.getName (this);
                        
                        if (! hasCLINIT && IClassDefConstants.CLINIT_NAME.equals (name))
                        {
                            hasCLINIT  = true;
                            continue;
                        }
                        else
                        {
                            final int modifiers = method.getAccessFlags ();
                            if ((modifiers & ACC_PRIVATE) == 0)
                            {
                                if (IClassDefConstants.INIT_NAME.equals (name))
                                    cds [ccount ++] = new ConstructorDescriptor (modifiers, method.getDescriptor (this));
                                else
                                    mds [mcount ++] = new MethodDescriptor (name, modifiers, method.getDescriptor (this));
                            }
                        }
                    }
                    
                    if (hasCLINIT && ! skipCLINIT)
                    {
                        dout.writeUTF (IClassDefConstants.CLINIT_NAME);
                        dout.writeInt (ACC_STATIC);
                        dout.writeUTF (IClassDefConstants.CLINIT_DESCRIPTOR);
                    }
                    
                    if (ccount > 0)
                    {
                        Arrays.sort (cds, 0, ccount);
                        
                        for (int i = 0; i < ccount; ++ i)
                        {
                            final ConstructorDescriptor cd = cds [i];
                        
                            dout.writeUTF (IClassDefConstants.INIT_NAME);
                            dout.writeInt (cd.m_modifiers);
                            dout.writeUTF (cd.m_descriptor.replace ('/', '.'));
                        }
                    }
                    
                    if (mcount > 0)
                    {
                        Arrays.sort (mds, 0, mcount);
                        
                        for (int i = 0; i < mcount; ++ i)
                        {
                            final MethodDescriptor md = mds [i];
                        
                            dout.writeUTF (md.m_name);
                            dout.writeInt (md.m_modifiers);
                            dout.writeUTF (md.m_descriptor.replace ('/', '.'));
                        }
                    }
                }
        
                dout.flush();
                
                
                if (DEBUG_SUID)
                {
                    byte [] dump = bout.copyByteArray ();
                    for (int x = 0; x < dump.length; ++ x)
                    {
                        System.out.println ("DUMP[" + x + "] = " + dump [x] + "\t" + (char) dump[x]);
                    }
                }
                
                final MessageDigest md = MessageDigest.getInstance ("SHA");
                
                md.update (bout.getByteArray (), 0, bout.size ());
                final byte [] hash = md.digest ();

                if (DEBUG_SUID)
                {                    
                    for (int x = 0; x < hash.length; ++ x)
                    {
                        System.out.println ("HASH[" + x + "] = " + hash [x]);
                    }
                }
                
//                    final int hash0 = hash [0];
//                    final int hash1 = hash [1];
//                    result = ((hash0 >>> 24) & 0xFF) | ((hash0 >>> 16) & 0xFF) << 8 | ((hash0 >>> 8) & 0xFF) << 16 | ((hash0 >>> 0) & 0xFF) << 24 |
//                             ((hash1 >>> 24) & 0xFF) << 32 | ((hash1 >>> 16) & 0xFF) << 40 | ((hash1 >>> 8) & 0xFF) << 48 | ((hash1 >>> 0) & 0xFF) << 56;

                for (int i = Math.min (hash.length, 8) - 1; i >= 0; -- i)
                {
                    result = (result << 8) | (hash [i] & 0xFF);
                }
                
                return result;
            }
            catch (IOException ioe)
            {
                throw new Error (ioe.getMessage ());
            }
            catch (NoSuchAlgorithmException nsae)
            {
                throw new SecurityException (nsae.getMessage());
            }
        }
    }
    
    
    public int addCONSTANT_Utf8 (final String value, final boolean keepUnique)
    {
        if (keepUnique)
        {
            final int existing = m_constants.findCONSTANT_Utf8 (value);
            if (existing > 0)
            {
                return existing;
            }
                
            // [else fall through]
        }

        return m_constants.add (new CONSTANT_Utf8_info (value));
    }
    
    public int addStringConstant (final String value)
    {
        final int value_index = addCONSTANT_Utf8 (value, true);
        
        // TODO: const uniqueness
        return m_constants.add (new CONSTANT_String_info (value_index));
    }
    
    public int addNameType (final String name, final String typeDescriptor)
    {
        final int name_index = addCONSTANT_Utf8 (name, true);
        final int descriptor_index = addCONSTANT_Utf8 (typeDescriptor, true);
        
        return m_constants.add (new CONSTANT_NameAndType_info (name_index, descriptor_index));
    }


    public int addClassref (final String classJVMName)
    {
        final int name_index = addCONSTANT_Utf8 (classJVMName, true);
        // TODO: this should do uniqueness checking:
        
        return m_constants.add (new CONSTANT_Class_info (name_index));
    }

    
    /**
     * Adds a new declared field to this class [with no attributes]
     */
    public int addField (final String name, final String descriptor, final int access_flags)
    {
        // TODO: support Fields with initializer attributes?
        // TODO: no "already exists" check done here
        
        final int name_index = addCONSTANT_Utf8 (name, true);
        final int descriptor_index = addCONSTANT_Utf8 (descriptor, true);
        
        final Field_info field = new Field_info (access_flags, name_index, descriptor_index,
            ElementFactory.newAttributeCollection (0));
        
        return m_fields.add (field);
    }
    
    /**
     * Adds a new declared field to this class [with given attributes]
     */
    public int addField (final String name, final String descriptor, final int access_flags,
                         final IAttributeCollection attributes)
    {
        // TODO: support Fields with initializer attributes?
        // TODO: no "already exists" check done here
        
        final int name_index = addCONSTANT_Utf8 (name, true);
        final int descriptor_index = addCONSTANT_Utf8 (descriptor, true);
        
        final Field_info field = new Field_info (access_flags, name_index, descriptor_index, attributes);
        
        return m_fields.add (field);
    }

    
    // TODO: rework this API
    
    public Method_info newEmptyMethod (final String name, final String descriptor, final int access_flags)
    {
        // TODO: flag for making synthetic etc
        final int attribute_name_index = addCONSTANT_Utf8 (Attribute_info.ATTRIBUTE_CODE, true);
        final int name_index = addCONSTANT_Utf8 (name, true);
        final int descriptor_index = addCONSTANT_Utf8 (descriptor, true);
        
        final IAttributeCollection attributes = ElementFactory.newAttributeCollection (0);
        final CodeAttribute_info code = new CodeAttribute_info (attribute_name_index, 0, 0,
            CodeAttribute_info.EMPTY_BYTE_ARRAY,
            AttributeElementFactory.newExceptionHandlerTable (0),
            ElementFactory.newAttributeCollection (0));
            
        attributes.add (code);
        
        final Method_info method = new Method_info (access_flags, name_index, descriptor_index, attributes);
        
        return method;
    }
    
    public int addMethod (final Method_info method)
    {
        return m_methods.add (method);
    }
    
    /**
     * Adds a reference to a field declared by this class.
     * 
     * @return constant pool index of the reference
     */
    public int addFieldref (final Field_info field)
    {
        // TODO: keepUnique flag
        
        final CONSTANT_NameAndType_info nametype = new CONSTANT_NameAndType_info (field.m_name_index, field.m_descriptor_index);
        final int nametype_index = m_constants.add (nametype); // TODO: unique logic
        
        return m_constants.add (new CONSTANT_Fieldref_info (m_this_class_index, nametype_index));
    }
    
    /**
     * Adds a reference to a field declared by this class.
     * 
     * @return constant pool index of the reference
     */
    public int addFieldref (final int offset)
    {
        // TODO: keepUnique flag
        
        final Field_info field = m_fields.get (offset); 
        
        final CONSTANT_NameAndType_info nametype = new CONSTANT_NameAndType_info (field.m_name_index, field.m_descriptor_index);
        final int nametype_index = m_constants.add (nametype); // TODO: unique logic
        
        return m_constants.add (new CONSTANT_Fieldref_info (m_this_class_index, nametype_index));
    }
    
    // protected: .............................................................
    
    // package: ...............................................................

    // private: ...............................................................
    
    
    private static final class FieldDescriptor implements Comparable
    {
        // Comparable:
        
        public final int compareTo (final Object obj)
        {
            return m_name.compareTo (((FieldDescriptor) obj).m_name);
        }

        FieldDescriptor (final String name, final int modifiers, final String descriptor)
        {
            m_name = name;
            m_modifiers = modifiers;
            m_descriptor = descriptor;
        }

        
        final String m_name;
        final int m_modifiers;
        final String m_descriptor; 
        
    } // end of nested class
    
    
    private static final class ConstructorDescriptor implements Comparable
    {
        // Comparable:
        
        public final int compareTo (final Object obj)
        {
            return m_descriptor.compareTo (((ConstructorDescriptor) obj).m_descriptor);
        }
        
        ConstructorDescriptor (final int modifiers, final String descriptor)
        {
            m_modifiers = modifiers;
            m_descriptor = descriptor;
        }
        

        final int m_modifiers;
        final String m_descriptor; 
        
    } // end of nested class
    
    
    private static final class MethodDescriptor implements Comparable
    {
        // Comparable:
        
        public final int compareTo (final Object obj)
        {
            final MethodDescriptor rhs = (MethodDescriptor) obj;
            
            int result = m_name.compareTo (rhs.m_name);
            if (result == 0)
                result = m_descriptor.compareTo (rhs.m_descriptor);
            
            return result;
        }

        MethodDescriptor (final String name, final int modifiers, final String descriptor)
        {
            m_name = name;
            m_modifiers = modifiers;
            m_descriptor = descriptor;
        }

        
        final String m_name;
        final int m_modifiers;
        final String m_descriptor; 
        
    } // end of nested class   
    

    // TODO: final fields
    
    private long m_magic;
    private int [] /* major, minor */ m_version;
    private int m_access_flags;
    
    private int m_this_class_index, m_super_class_index;
    
    private IConstantCollection m_constants;
    private IInterfaceCollection m_interfaces;
    private IFieldCollection m_fields;
    private IMethodCollection m_methods;
    private IAttributeCollection m_attributes;
    
    private long m_declaredSUID;
    
    private static final boolean DEBUG_SUID = false;

} // end of class
// ----------------------------------------------------------------------------
