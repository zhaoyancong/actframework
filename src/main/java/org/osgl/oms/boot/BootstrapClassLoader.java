package org.osgl.oms.boot;

import org.osgl._;
import org.osgl.logging.L;
import org.osgl.logging.Logger;
import org.osgl.oms.BytecodeEnhancerManager;
import org.osgl.oms.OMS;
import org.osgl.oms.asm.ClassReader;
import org.osgl.oms.asm.ClassWriter;
import org.osgl.oms.boot.app.FullStackAppBootstrapClassLoader;
import org.osgl.oms.boot.server.ServerBootstrapClassLoader;
import org.osgl.oms.util.BytecodeVisitor;
import org.osgl.util.C;
import org.osgl.util.E;

import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Base class for OMS class loaders
 */
public abstract class BootstrapClassLoader extends ClassLoader implements PluginClassProvider {

    protected static final Logger logger = L.get(BootstrapClassLoader.class);

    private BytecodeEnhancerManager enhancerManager = new BytecodeEnhancerManager();

    protected BootstrapClassLoader(ClassLoader parent) {
        super(parent);
    }

    protected BootstrapClassLoader() {
        super(_getParent());
    }


    private static ClassLoader _getParent() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (null == cl) {
            cl = ClassLoader.getSystemClassLoader();
        }
        return cl;
    }

    protected Class<?> defineClassX(String name, byte[] b, int off, int len,
                                         ProtectionDomain protectionDomain) {
        int i = name.lastIndexOf('.');
        if (i != -1) {
            String pkgName = name.substring(0, i);
            // Check if package already loaded.
            if (getPackage(pkgName) == null) {
                try {
                        definePackage(pkgName, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException iae) {
                        throw new AssertionError("Cannot find package " +
                                pkgName);
                }
            }
        }
        return super.defineClass(name, b, off, len, protectionDomain);
    }

    protected Class<?> defineClass(String name, byte[] ba) {
        Class<?> c;
        _.Var<ClassWriter> cw = _.val(null);
        BytecodeVisitor enhancer = enhancerManager.generalEnhancer(name, cw);
        if (null == enhancer) {
            c = defineClassX(name, ba, 0, ba.length, DOMAIN);
        } else {
            ClassWriter w = new ClassWriter(0);
            cw.set(w);
            enhancer.commitDownstream();
            ClassReader r;
            r = new ClassReader(ba);
            try {
                r.accept(enhancer, 0);
                byte[] baNew = w.toByteArray();
                c = defineClassX(name, baNew, 0, baNew.length, DOMAIN);
            } catch (RuntimeException e) {
                throw e;
            } catch (Error e) {
                throw e;
            } catch (Exception e) {
                throw E.unexpected("Error processing class " + name);
            }
        }
        return c;
    }

    protected static java.security.ProtectionDomain DOMAIN;

    static {
        DOMAIN = (java.security.ProtectionDomain)
                java.security.AccessController.doPrivileged(
                        new java.security.PrivilegedAction() {
                            public Object run() {
                                return BootstrapClassLoader.class.getProtectionDomain();
                            }
                        });
    }

    protected static final Set<String> protectedClasses = C.set(
            ServerBootstrapClassLoader.class.getName(),
            FullStackAppBootstrapClassLoader.class.getName(),
            PluginClassProvider.class.getName()
            //Plugin.class.getName(),
            //ClassFilter.class.getName()
    );

}
