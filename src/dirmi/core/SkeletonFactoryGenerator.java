/*
 *  Copyright 2006 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dirmi.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import java.io.DataInput;

import java.rmi.Remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cojen.classfile.ClassFile;
import org.cojen.classfile.CodeBuilder;
import org.cojen.classfile.Label;
import org.cojen.classfile.LocalVariable;
import org.cojen.classfile.MethodInfo;
import org.cojen.classfile.Modifiers;
import org.cojen.classfile.TypeDesc;

import org.cojen.util.ClassInjector;
import org.cojen.util.SoftValuedHashMap;

import dirmi.AsynchronousInvocationException;

import dirmi.info.RemoteInfo;
import dirmi.info.RemoteIntrospector;
import dirmi.info.RemoteMethod;
import dirmi.info.RemoteParameter;

import dirmi.io.RemoteConnection;
import dirmi.io.RemoteInputStream;
import dirmi.io.RemoteOutputStream;

/**
 * Generates {@link SkeletonFactory} instances for any given Remote type.
 *
 * @author Brian S O'Neill
 */
public class SkeletonFactoryGenerator<R extends Remote> {
    private static final String REMOTE_FIELD_NAME = "remote";

    private static final Map<Class<?>, SkeletonFactory<?>> cCache;

    static {
        cCache = new SoftValuedHashMap();
    }

    /**
     * Returns a new or cached SkeletonFactory.
     *
     * @param type
     * @throws IllegalArgumentException if type is null or malformed
     */
    public static <R extends Remote> SkeletonFactory<R> getSkeletonFactory(Class<R> type)
        throws IllegalArgumentException
    {
        synchronized (cCache) {
            SkeletonFactory<R> factory = (SkeletonFactory<R>) cCache.get(type);
            if (factory == null) {
                factory = new SkeletonFactoryGenerator<R>(type).generateFactory();
                cCache.put(type, factory);
            }
            return factory;
        }
    }

    private final Class<R> mType;
    private final RemoteInfo mInfo;

    private SkeletonFactoryGenerator(Class<R> type) {
        mType = type;
        mInfo = RemoteIntrospector.examine(type);
    }

    private SkeletonFactory<R> generateFactory() {
        Class<? extends Skeleton> skeletonClass = generateSkeleton();

        try {
            CodeBuilderUtil.invokeMethodIDInitMethod(skeletonClass, mInfo);
            return new Factory<R>(mType, skeletonClass);
        } catch (IllegalAccessException e) {
            throw new Error(e);
        } catch (InvocationTargetException e) {
            throw new Error(e);
        } catch (NoSuchMethodException e) {
            NoSuchMethodError nsme = new NoSuchMethodError();
            nsme.initCause(e);
            throw nsme;
        }
    }

    private Class<? extends Skeleton> generateSkeleton() {
        ClassInjector ci =
            ClassInjector.create(mType.getName() + "$Skeleton", mType.getClassLoader());

        ClassFile cf = new ClassFile(ci.getClassName());
        cf.addInterface(Skeleton.class);
        cf.setSourceFile(SkeletonFactoryGenerator.class.getName());
        cf.markSynthetic();
        cf.setTarget("1.5");

        final TypeDesc remoteType = TypeDesc.forClass(mType);
        final TypeDesc identifierType = TypeDesc.forClass(Identifier.class);
        final TypeDesc remoteConnectionType = TypeDesc.forClass(RemoteConnection.class);
        final TypeDesc remoteInType = TypeDesc.forClass(RemoteInputStream.class);
        final TypeDesc remoteOutType = TypeDesc.forClass(RemoteOutputStream.class);
        final TypeDesc noSuchMethodExType = TypeDesc.forClass(NoSuchMethodException.class);

        // Add fields
        {
            cf.addField(Modifiers.PRIVATE.toFinal(true), REMOTE_FIELD_NAME, remoteType);

            CodeBuilderUtil.addMethodIDFields(cf, mInfo);
        }

        // Add static method to assign identifiers.
        CodeBuilderUtil.addMethodIDInitMethod(cf, mInfo);

        // Add constructor
        {
            MethodInfo mi = cf.addConstructor
                (Modifiers.PUBLIC, new TypeDesc[] {remoteType});

            CodeBuilder b = new CodeBuilder(mi);

            b.loadThis();
            b.invokeSuperConstructor(null);

            b.loadThis();
            b.loadLocal(b.getParameter(0));
            b.storeField(REMOTE_FIELD_NAME, remoteType);

            b.returnVoid();
        }

        // Add the all-important invoke method
        MethodInfo mi = cf.addMethod(Modifiers.PUBLIC, "invoke", null,
                                     new TypeDesc[] {remoteConnectionType});
        CodeBuilder b = new CodeBuilder(mi);

        // Read method identifier from connection.
        LocalVariable conVar = b.getParameter(0);

        b.loadLocal(conVar);
        b.invokeInterface(remoteConnectionType, "getInputStream", remoteInType, null);
        LocalVariable remoteInVar = b.createLocalVariable(null, remoteInType);
        b.storeLocal(remoteInVar);

        b.loadLocal(remoteInVar);
        b.invokeStatic(Identifier.class.getName(), "read", identifierType,
                       new TypeDesc[] {TypeDesc.forClass(DataInput.class)});
        LocalVariable methodIDVar = b.createLocalVariable(null, identifierType);
        b.storeLocal(methodIDVar);

        Set<? extends RemoteMethod> methods = mInfo.getRemoteMethods();

        // Create a switch statement that operates on method identifier
        // hashcodes, accounting for possible collisions.

        Map<Integer, List<RemoteMethod>> hashToMethodMap =
            new LinkedHashMap<Integer, List<RemoteMethod>>(methods.size());
        for (RemoteMethod method : methods) {
            Integer key = method.getMethodID().hashCode();
            List<RemoteMethod> matches = hashToMethodMap.get(key);
            if (matches == null) {
                matches = new ArrayList<RemoteMethod>(2);
                hashToMethodMap.put(key, matches);
            }
            matches.add(method);
        }

        int caseCount = hashToMethodMap.size();
        int[] cases = new int[caseCount];
        Label[] switchLabels = new Label[caseCount];
        Label defaultLabel = b.createLabel();

        {
            int i = 0;
            for (Integer key : hashToMethodMap.keySet()) {
                cases[i] = key;
                switchLabels[i] = b.createLabel();
                i++;
            }
        }

        // Each case operates on the remote server first, so put it on the stack early.
        b.loadThis();
        b.loadField(REMOTE_FIELD_NAME, remoteType);

        b.loadLocal(methodIDVar);
        b.invokeVirtual(methodIDVar.getType(), "hashCode", TypeDesc.INT, null);
        b.switchBranch(cases, switchLabels, defaultLabel);

        // Generate case for each set of matches.

        int methodCount = methods.size();
        Label[] tryStarts = new Label[methodCount];
        Label[] tryEnds = new Label[methodCount];
        boolean[] isAsync = new boolean[methodCount];
        int asyncCount = 0;

        int ordinal = 0;
        int entryIndex = 0;
        for (Map.Entry<Integer, List<RemoteMethod>> entry : hashToMethodMap.entrySet()) {
            switchLabels[entryIndex].setLocation();

            List<RemoteMethod> matches = entry.getValue();

            for (int j=0; j<matches.size(); j++) {
                RemoteMethod method = matches.get(j);

                if (method.isAsynchronous()) {
                    isAsync[ordinal] = true;
                    asyncCount++;
                }

                // Make sure identifier matches before proceeding.
                b.loadLocal(methodIDVar);
                CodeBuilderUtil.loadMethodID(b, ordinal);
                b.invokeVirtual(methodIDVar.getType(), "equals",
                                TypeDesc.BOOLEAN, new TypeDesc[] {TypeDesc.OBJECT});

                Label collision = null;
                if (j + 1 < matches.size()) {
                    // Branch to next possibly matching method.
                    collision = b.createLabel();
                    b.ifZeroComparisonBranch(collision, "==");
                } else {
                    // Branch to default label to throw exception.
                    b.ifZeroComparisonBranch(defaultLabel, "==");
                }

                List<? extends RemoteParameter> paramTypes = method.getParameterTypes();

                if (paramTypes.size() != 0) {
                    // Read parameters onto stack.
                    for (RemoteParameter paramType : paramTypes) {
                        CodeBuilderUtil.readParam(b, paramType, remoteInVar);
                    }
                }

                TypeDesc returnDesc = CodeBuilderUtil.getTypeDesc(method.getReturnType());

                {
                    tryStarts[ordinal] = b.createLabel().setLocation();
                    TypeDesc[] params = CodeBuilderUtil.getTypeDescs(paramTypes);
                    b.invokeInterface(remoteType, method.getName(), returnDesc, params);
                    tryEnds[ordinal] = b.createLabel().setLocation();
                }

                // Write response and close connection.

                if (method.isAsynchronous()) {
                    if (returnDesc != null) {
                        if (returnDesc.isDoubleWord()) {
                            b.pop2();
                        } else {
                            b.pop();
                        }
                    }
                    // Assume caller has closed connection.
                } else {
                    LocalVariable retVar = null;
                    if (returnDesc != null) {
                        retVar = b.createLocalVariable(null, returnDesc);
                        b.storeLocal(retVar);
                    }

                    b.loadLocal(conVar);
                    b.invokeInterface
                        (remoteConnectionType, "getOutputStream", remoteOutType, null);
                    LocalVariable remoteOutVar = b.createLocalVariable(null, remoteOutType);
                    b.storeLocal(remoteOutVar);

                    if (returnDesc == TypeDesc.BOOLEAN) {
                        b.loadLocal(remoteOutVar);
                        b.loadLocal(retVar);
                        b.invokeVirtual(remoteOutType, "writeOk", null,
                                        new TypeDesc[] {TypeDesc.BOOLEAN});
                    } else {
                        b.loadLocal(remoteOutVar);
                        b.invokeVirtual(remoteOutType, "writeOk", null, null);
                        if (retVar != null) {
                            CodeBuilderUtil.writeParam
                                (b, method.getReturnType(), remoteOutVar, retVar);
                        }
                    }

                    b.loadLocal(conVar);
                    b.invokeInterface(remoteConnectionType, "close", null, null);
                }

                b.returnVoid();
                ordinal++;

                if (collision != null) {
                    collision.setLocation();
                }
            }

            entryIndex++;
        }

        // For default case, throw a NoSuchMethodException.
        defaultLabel.setLocation();
        b.pop(); // pop remote server
        b.newObject(noSuchMethodExType);
        b.dup();
        b.loadLocal(methodIDVar);
        b.invokeStatic(TypeDesc.STRING, "valueOf",
                       TypeDesc.STRING, new TypeDesc[] {TypeDesc.OBJECT});
        b.invokeConstructor(noSuchMethodExType, new TypeDesc[] {TypeDesc.STRING});
        b.throwObject();

        // Create common exception handlers. One for regular methods, the other
        // for asynchronous methods.

        LocalVariable throwableVar =
            b.createLocalVariable(null, TypeDesc.forClass(Throwable.class));

        // Handler for asynchronous methods (if any). Re-throw exception
        // wrapped in AsynchronousInvocationException.
        if (asyncCount > 0) {
            for (ordinal=0; ordinal<methodCount; ordinal++) {
                if (!isAsync[ordinal]) {
                    continue;
                }
                b.exceptionHandler
                    (tryStarts[ordinal], tryEnds[ordinal], Throwable.class.getName());
            }

            b.storeLocal(throwableVar);

            TypeDesc asyncExType = TypeDesc.forClass(AsynchronousInvocationException.class);
            b.newObject(asyncExType);
            b.dup();
            b.loadLocal(throwableVar);
            b.invokeConstructor(asyncExType, new TypeDesc[] {throwableVar.getType()});
            b.throwObject();
        }

        // Handler for synchronous methods (if any). Write exception to connection.
        if (caseCount - asyncCount > 0) {
            for (ordinal=0; ordinal<methodCount; ordinal++) {
                if (isAsync[ordinal]) {
                    continue;
                }
                b.exceptionHandler
                    (tryStarts[ordinal], tryEnds[ordinal], Throwable.class.getName());
            }

            b.storeLocal(throwableVar);

            b.loadLocal(conVar);
            b.invokeInterface(remoteConnectionType, "getOutputStream", remoteOutType, null);
            b.loadLocal(throwableVar);
            b.invokeVirtual(remoteOutType, "writeThrowable",
                            null, new TypeDesc[] {throwableVar.getType()});
            b.loadLocal(conVar);
            b.invokeInterface(remoteConnectionType, "close", null, null);
            
            b.returnVoid();
        }

        return ci.defineClass(cf);
    }

    private static class Factory<R extends Remote> implements SkeletonFactory<R> {
        private final Class<R> mType;
        private final Constructor<? extends Skeleton> mSkeletonCtor;

        Factory(Class<R> type, Class<? extends Skeleton> skeletonClass)
            throws NoSuchMethodException
        {
            mType = type;
            mSkeletonCtor = skeletonClass.getConstructor(type);
        }

        public Class<R> getRemoteType() {
            return mType;
        }

        public Class<? extends Skeleton> getSkeletonClass() {
            return mSkeletonCtor.getDeclaringClass();
        }

        public Skeleton createSkeleton(R remoteServer) {
            Throwable error;
            try {
                return mSkeletonCtor.newInstance(remoteServer);
            } catch (InstantiationException e) {
                error = e;
            } catch (IllegalAccessException e) {
                error = e;
            } catch (InvocationTargetException e) {
                error = e.getCause();
            }
            InternalError ie = new InternalError();
            ie.initCause(error);
            throw ie;
        }
    }
}
