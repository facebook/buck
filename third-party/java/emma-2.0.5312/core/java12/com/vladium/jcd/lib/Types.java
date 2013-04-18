/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Types.java,v 1.1.1.1 2004/05/09 16:57:50 vlad_r Exp $
 */
package com.vladium.jcd.lib;

import java.io.IOException;
import java.lang.reflect.*;

import com.vladium.jcd.cls.IAccessFlags;

// ----------------------------------------------------------------------------
/**
 * Utility methods for manipulating type signatures and descriptors.
 * 
 * TODO: fix usage of chars in parsers
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public abstract class Types
{
    // public: ................................................................
    
    /**
     * Returns 'c''s package name [does not include trailing '.'] or ""
     * if 'c' is in the default package.
     */
    public static String getClassPackageName (final Class c)
    {
        // TODO: handle array and other types
        
        final String className = c.getName ();
        final int lastDot = className.lastIndexOf ('.');
        return lastDot >= 0 ? className.substring (0, lastDot) : "";
    }
    
    
    public static String accessFlagsToString (final int flags, final boolean isClass)
    {
        final StringBuffer result = new StringBuffer ();
      
        boolean first = true;
        
        if (isClass)
        {
            for (int f = 0; f < IAccessFlags.ALL_ACC.length; ++ f)
            {
                final int bit = IAccessFlags.ALL_ACC [f];
                
                if ((flags & bit) != 0)
                {
                    if (first)
                        first = false;
                    else
                        result.append (" ");
                    
                    if (bit == IAccessFlags.ACC_SUPER)
                        result.append ("super");
                    else
                        result.append (IAccessFlags.ALL_ACC_NAMES [f]);
                }
            }
        }
        else
        {
            for (int f = 0; f < IAccessFlags.ALL_ACC.length; ++ f)
            {
                final int bit = IAccessFlags.ALL_ACC [f];
                
                if ((flags & bit) != 0)
                {
                    if (first)
                        first = false;
                    else
                        result.append (" ");
                        
                    result.append (IAccessFlags.ALL_ACC_NAMES [f]);
                }
            }
        }
        
        return result.toString ();
    } 

    
    /**
     * Converts Java-styled package/class name to how it would be
     * represented in the VM.<P>
     * 
     * Example:<BR>
     * javaNameToVMName("java.lang.Object") = "java/lang/Object"
     * 
     * @see #vmNameToJavaName
     */
    public static String javaNameToVMName (final String javaName)
    {
        if (javaName == null) return null;
        return javaName.replace ('.', '/');
    }
    
    
    /**
     * Converts a VM-styled package/class name to how it would be
     * represented in Java.<P>
     * 
     * Example:<BR>
     * vmNameToJavaName("java/lang/Object") = "java.lang.Object"
     * 
     * @see #javaNameToVMName
     */
    public static String vmNameToJavaName (final String vmName)
    {
        if (vmName == null) return null;
        return vmName.replace ('/', '.');
    }
    
    
    /**
     * Converts a method signature to its VM descriptor representation.
     * See $4.3 of the VM spec 1.0 for the descriptor grammar.<P>
     * 
     * Example:<BR>
     * signatureToDescriptor(new Object().getClass().getMethod("equals" ,new Class[0])) = "(Ljava/lang/Object;)Z"
     * <P>
     * 
     * Equivalent to
     * <CODE>signatureToDescriptor(method.getParameterTypes (), method.getReturnType ())</CODE>.
     */
    public static String signatureToDescriptor (Method method)
    {
        if (method == null) throw new IllegalArgumentException ("null input: method");
        return signatureToDescriptor (method.getParameterTypes (), method.getReturnType ());
    }
    
    
    /**
     * Converts a method signature (parameter types + return type) to its VM descriptor
     * representation. See $4.3 of the VM spec 1.0 for the descriptor grammar.<P>
     */
    public static String signatureToDescriptor (Class [] parameterTypes, Class returnType)
    {
        return new signatureCompiler ().signatureDescriptor (parameterTypes, returnType);
    }
    
    
    /**
     * Converts a type (a Class) to its VM descriptor representation.<P>
     * 
     * Example:<BR>
     * typeToDescriptor(Object.class) = "Ljava/lang/Object;" <BR>
     * typeToDescriptor(boolean.class) = "Z"
     * <P>
     * Note the invariant typeToDescriptor(descriptorToType(desc)) == desc.
     * 
     * @see #descriptorToType
     */
    public static String typeToDescriptor (Class type)
    {
        return new signatureCompiler ().typeDescriptor (type);
    }
    
    
    /**
     * Converts a VM descriptor to the corresponding type.<P>
     * 
     * Example:<BR>
     * descriptorToType("[[I") = int[][].class <BR>
     * descriptorToType("B") = byte.class
     * <P>
     * Note the invariant descriptorToType(typeToDescriptor(c)) == c.
     * 
     * @see #descriptorToType
     */
    public static Class descriptorToType (String typedescriptor) throws ClassNotFoundException
    {
        return new typeDescriptorCompiler ().descriptorToClass (typedescriptor);
    }
    
    
    
    public static String descriptorToReturnType (String methoddescriptor)
    {
        final int i1 = methoddescriptor.indexOf ('(');
        final int i2 = methoddescriptor.lastIndexOf (')');
        
        if ((i1 < 0) || (i2 <= 0) || (i1 >= i2) || (i2 >= methoddescriptor.length () - 1))
            throw new IllegalArgumentException ("malformed method descriptor: [" + methoddescriptor + "]");

        return methoddescriptor.substring (i2 + 1);                                                                                                
    }
    
    
    public static String [] descriptorToParameterTypes (String methoddescriptor)
    {
        //System.out.println ("METHOD DESCRIPTOR: [" + methoddescriptor + "]");
    
        try
        {
            final methodDescriptorCompiler compiler = new methodDescriptorCompiler (methoddescriptor);
            compiler.methodDescriptor ();
            return compiler.getResult ();
        }
        catch (IOException e)
        {
            throw new IllegalArgumentException ("error parsing [" + methoddescriptor + "]: " + e.toString ());
        }
        
        /*
        final java.util.Vector _result = new java.util.Vector ();
        final StringBuffer token = new StringBuffer ();

        char c = '*';
        int scan = 0;
        
        for (int state = 0; state != 4; )
        {
            try
            {
                switch (state)
                {
                case 0:
                    c = methoddescriptor.charAt (scan++);
                    if (c == '(')
                        state = 1;
                    else
                        throw new IllegalArgumentException ("malformed method descriptor: [" + methoddescriptor + "]");
                    break;
                    
                case 1:
                    c = methoddescriptor.charAt (scan);
                    switch (c)
                    {
                    case 'B':
                    case 'C':
                    case 'D':
                    case 'F':
                    case 'I':
                    case 'J':
                    case 'S':
                    case 'Z':
                        token.append (c);
                        _result.addElement (token.toString ());
                        token.setLength (0);
                        scan++;
                        break;
                        
                    case 'L':
                        state = 2;
                        token.append (c);
                        scan++;
                        break;
                        
                    case '[':
                        state = 3;    
                        token.append (c);
                        scan++;
                        break;
                        
                    case ')':
                        if (token.length () > 0)
                        {
                            _result.addElement (token.toString ());
                            token.setLength (0);
                        }
                        state = 4;
                        break;
                            
                        
                    default:
                        throw new IllegalArgumentException ("[state = " + state + ", c = " + c + "] malformed method descriptor: [" + methoddescriptor + "]");
                        
                    } // end of nested switch
                    break;
                    
                case 2:
                    c = methoddescriptor.charAt (scan++);
                    token.append (c);
                    if (c == ';')
                    {
                        _result.addElement (token.toString ());
                        token.setLength (0);
                        state = 1;
                    }
                    break;
                    
                case 3:
                    c = methoddescriptor.charAt (scan++);
                    token.append (c);
                    if (c != '[')
                    {
                        state = 1;
                    }
                    break;    
                    
                } // end of switch
                
                //System.out.println ("[state = " + state + ", c = " + c + "]");
            }
            catch (StringIndexOutOfBoundsException e)
            {
                throw new IllegalArgumentException ("malformed method descriptor: [" + methoddescriptor + "]");
            }
        }
        
        String [] result = new String [_result.size ()];
        _result.copyInto (result);
        
        return result;
        */
    }
    
    
    public static String signatureToMethodDescriptor (final String [] parameterTypeDescriptors, final String returnTypeDescriptor)
    {
        final StringBuffer result = new StringBuffer ("(");
        
        for (int p = 0; p < parameterTypeDescriptors.length; p++)
        {
            result.append (parameterTypeDescriptors [p]);
        }
        
        result.append (')');
        result.append (returnTypeDescriptor);
        
        return result.toString (); 
    }
    
    
    public static String typeDescriptorToUserName (final String typedescriptor)
    {
        return new typeDescriptorCompiler2 ().descriptorToClass (typedescriptor);
    }
    
    public static String methodDescriptorToUserName (final String methoddescriptor)
    {
        final String [] parameterTypes = descriptorToParameterTypes (methoddescriptor);
        
        final StringBuffer result = new StringBuffer ("(");
        
        for (int p = 0; p < parameterTypes.length; p++)
        {
            //System.out.println ("DESCRIPTOR: [" + parameterTypes [p] + "]");
            
            if (p > 0) result.append (", ");
            
            final String typeUserName = typeDescriptorToUserName (parameterTypes [p]);
            int lastDot = typeUserName.lastIndexOf ('.');
            
            if ((lastDot < 0) || ! "java.lang.".equals (typeUserName.substring (0, lastDot + 1)))
                result.append (typeUserName);
            else
                result.append (typeUserName.substring (lastDot + 1));
        }
        
        result.append (')');
        return result.toString (); 
    }
    
    public static String fullMethodDescriptorToUserName (final String classJavaName, String methodName, final String methoddescriptor)
    {
        if ("<init>".equals (methodName))
            methodName = simpleClassName (classJavaName);
        if ("<clinit>".equals (methodName))
            methodName = "<static class initializer>";
        
        return methodName + ' ' + methodDescriptorToUserName (methoddescriptor);
    }
    
    // TODO: added most recently
    public static String fullMethodDescriptorToFullUserName (final String classJavaName, String methodName, final String methoddescriptor)
    {
        if ("<init>".equals (methodName))
            methodName = simpleClassName (classJavaName);
        if ("<clinit>".equals (methodName))
            methodName = "<static class initializer>";
        
        return classJavaName + '.' + methodName + ' ' + methodDescriptorToUserName (methoddescriptor);
    }
    
    // protected: .............................................................
    
    // package: ...............................................................

    // private: ...............................................................

    
    private static String simpleClassName (final String classJavaName)
    {
        int lastDot = classJavaName.lastIndexOf ('.');
        
        if (lastDot < 0)
            return classJavaName;
        else
            return classJavaName.substring (lastDot + 1);
    }
                                     
    
    
    private static final class signatureCompiler
    {
        String signatureDescriptor (Class [] _parameterTypes, Class _returnType)
        {
            emit ('(');    parameterTypes (_parameterTypes); emit (')'); returnType (_returnType);
            
            return m_desc.toString ();
        }
        
        String typeDescriptor (Class type)
        {
            parameterType (type);
            
            return m_desc.toString ();
        }
        
        
        private void parameterTypes (Class [] _parameterTypes)
        {
            if (_parameterTypes != null)
            {
                for (int p = 0; p < _parameterTypes.length; p++)
                {
                    parameterType (_parameterTypes [p]);
                }
            }
        }
        
        
        private void returnType (Class _returnType)
        {
            if ((_returnType == null) || (_returnType == Void.TYPE))
                emit ('V');
            else
                parameterType (_returnType);
        }
        
        
        private void parameterType (Class _parameterType)
        {
            if (_parameterType != null)
            {
                if (_parameterType.isPrimitive ()) // base type:
                {
                    if (byte.class == _parameterType)            emit ('B');
                    else if (char.class == _parameterType)        emit ('C');
                    else if (double.class == _parameterType)    emit ('D');
                    else if (float.class == _parameterType)        emit ('F');
                    else if (int.class == _parameterType)        emit ('I');
                    else if (long.class == _parameterType)        emit ('J');
                    else if (short.class == _parameterType)        emit ('S');
                    else if (boolean.class == _parameterType)    emit ('Z');
                }
                else if (_parameterType.isArray ()) // array type:
                {
                    emit ('[');    parameterType (_parameterType.getComponentType ());
                }
                else // object type:
                {
                    emit ('L');    emit (javaNameToVMName (_parameterType.getName ())); emit (';');
                }
            }
        }
        
        
        private void emit (String s)
        {
            m_desc.append (s);
        }
        
        private void emit (char c)
        {
            m_desc.append (c);
        }
        
        
        private StringBuffer m_desc = new StringBuffer ();
        
    } // end of static class
    
    
    
    private static class typeDescriptorCompiler
    {
        /*
        NOTE: the following would be a very simple solution to this problem
        
            Class.forName ('[' + descriptor).getComponentType ();
        
        except it only works in MS VM.
        */
        
        Class descriptorToClass (String typedescriptor) throws ClassNotFoundException
        {
            char first = typedescriptor.charAt (0);
            
            if (first == '[')
                // array type:
                return arrayOf (typedescriptor.substring (1));
            else if (first == 'L')
                // object type:
                return Class.forName (vmNameToJavaName (typedescriptor.substring (1, typedescriptor.length() - 1)));
            else // primitive type
            {
                return primitive (first);
            }
        }
        
        
        Class arrayOf (String typedescriptor) throws ClassNotFoundException
        {
            char first = typedescriptor.charAt (0);
            Class component;
            
            if (first == '[')
                // array type:
                component = arrayOf (typedescriptor.substring (1));
            else if (first == 'L')
                // object type:
                component =  Class.forName (vmNameToJavaName (typedescriptor.substring (1, typedescriptor.length() - 1)));
            else // primitive type
            {
                component = primitive (first);
            }
            
            Object array = Array.newInstance (component, 0);
            return array.getClass ();
        }
        
        
        Class primitive (char c) throws ClassNotFoundException
        {
            if (c == 'B') return byte.class;
            else if (c == 'C') return char.class;
            else if (c == 'D') return double.class;
            else if (c == 'F') return float.class;
            else if (c == 'I') return int.class;
            else if (c == 'J') return long.class;
            else if (c == 'S') return short.class;
            else if (c == 'Z') return boolean.class;
            else throw new ClassNotFoundException ("unknown base type: " + c);
        }
        
    } // end of static class

    
    private static class typeDescriptorCompiler2
    {
        String descriptorToClass (String typedescriptor)
        {
            //System.out.println ("typedesc1 -> " + typedescriptor);
            
            char first = typedescriptor.charAt (0);
            
            if (first == '[')
                // array type:
                return arrayOf (typedescriptor.substring (1));
            else if (first == 'L')
                // object type:
                return vmNameToJavaName (typedescriptor.substring (1, typedescriptor.length() - 1));
            else // primitive type
                return primitive (first);
        }
        
        
        String arrayOf (String typedescriptor)
        {
            //System.out.println ("typedesc2 -> " + typedescriptor);
            
            char first = typedescriptor.charAt (0);
            String component;
            
            if (first == '[')
                // array type:
                component = arrayOf (typedescriptor.substring (1));
            else if (first == 'L')
                // object type:
                component = vmNameToJavaName (typedescriptor.substring (1, typedescriptor.length() - 1));
            else // primitive type
                component = primitive (first);
            
            String array = component + " []";
            return array;
        }
        
        
        String primitive (char c)
        {
            switch (c)
            {
            case 'B': return "byte";
            case 'C': return "char";
            case 'D': return "double";
            case 'F': return "float";
            case 'I': return "int";
            case 'J': return "long";
            case 'S': return "short";
            case 'Z': return "boolean";
            default:          
                throw new IllegalArgumentException ("unknown primitive: " + c);
            }
        }
        
    } // end of static class

    
    private static class methodDescriptorCompiler
    {
        methodDescriptorCompiler (String methoddescriptor)
        {
            m_in = new java.io.PushbackReader (new java.io.StringReader (methoddescriptor));
        }
        
        String [] getResult ()
        {
            final String [] result = new String [m_result.size ()];
            m_result.toArray (result);
            
            return result;
        }
        
        void methodDescriptor () throws IOException
        {
            consume ('(');
            
            char c;
            while ((c = (char) m_in.read ()) != ')')
            {
                m_in.unread (c);
                parameterDescriptor ();
            }
            returnDescriptor ();
        }
        
        void parameterDescriptor () throws IOException
        {
            fieldType ();
            newToken ();
        }
        
        void returnDescriptor () throws IOException
        {
            char c = (char) m_in.read ();
            
            switch (c)
            {
            case 'V':
                m_token.append (c);
                break;
                
            default:
                m_in.unread (c);
                fieldType ();
                
            }
            // ignore return type for now: newToken ();
        }
        
        void componentType () throws IOException
        {
            fieldType ();
        }
        
        void objectType () throws IOException
        {
            consume ('L');
            m_token.append ('L');
            
            char c;
            while ((c = (char) m_in.read ()) != ';')
            {
                m_token.append (c);
            }
            m_token.append (';');
        }
        
        void arrayType () throws IOException
        {
            consume ('[');
            m_token.append ('[');
        
            componentType ();
        }
        
        void fieldType () throws IOException
        {
            char c = (char) m_in.read ();
            m_in.unread (c);
            
            switch (c)
            {
            case 'L':
                objectType ();
                break;
                
            case '[':
                arrayType ();
                break;
                
            default:
                baseType ();
                break;
            }
        }

        
        void baseType () throws IOException
        {
            char c = (char) m_in.read ();
            
            switch (c)
            {
            case 'B': 
            case 'C': 
            case 'D':
            case 'F':
            case 'I':
            case 'J':
            case 'S':
            case 'Z':
                m_token.append (c);
                break;
                
            default:          
                throw new IllegalArgumentException ("unknown base type: " + c);
            }
        }
        
        
        private void consume (char expected) throws IOException
        {
            char c = (char) m_in.read ();
            
            if (c != expected)
                throw new IllegalArgumentException ("consumed '" + c + "' while expecting '" + expected + "'");
        }
        
        
        
        private void newToken ()
        {
            //System.out.println ("NEW TOKEN [" + m_token.toString () + "]");
            
            m_result.add (m_token.toString ());
            m_token.setLength (0);
        }
    
        final java.util.List m_result = new java.util.ArrayList ();
        private StringBuffer m_token = new StringBuffer ();
        private java.io.PushbackReader m_in;
    } // end of nested class
    
} // end of class
// ----------------------------------------------------------------------------
