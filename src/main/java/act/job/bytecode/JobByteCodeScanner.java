package act.job.bytecode;

import act.app.AppByteCodeScannerBase;
import act.app.event.AppEventId;
import act.asm.AnnotationVisitor;
import act.asm.MethodVisitor;
import act.asm.Opcodes;
import act.asm.Type;
import act.job.JobAnnotationProcessor;
import act.job.meta.JobClassMetaInfo;
import act.job.meta.JobClassMetaInfoManager;
import act.job.meta.JobMethodMetaInfo;
import act.sys.Env;
import act.sys.meta.EnvAnnotationVisitor;
import act.util.AsmTypes;
import act.util.ByteCodeVisitor;
import org.osgl.$;
import org.osgl.util.C;
import org.osgl.util.E;
import org.osgl.util.S;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Scan class to collect Job class meta info
 */
public class JobByteCodeScanner extends AppByteCodeScannerBase {

    private JobAnnotationProcessor annotationProcessor;
    private JobClassMetaInfo classInfo;
    private volatile JobClassMetaInfoManager classInfoBase;

    @Override
    protected boolean shouldScan(String className) {
        classInfo = new JobClassMetaInfo();
        return true;
    }

    @Override
    protected void onAppSet() {
        annotationProcessor = new JobAnnotationProcessor(app());
    }

    @Override
    public ByteCodeVisitor byteCodeVisitor() {
        return new _ByteCodeVisitor();
    }

    @Override
    public void scanFinished(String className) {
        classInfoBase().registerJobMetaInfo(classInfo);
    }

    private JobClassMetaInfoManager classInfoBase() {
        if (null == classInfoBase) {
            synchronized (this) {
                if (null == classInfoBase) {
                    classInfoBase = app().classLoader().jobClassMetaInfoManager();
                }
            }
        }
        return classInfoBase;
    }

    private class _ByteCodeVisitor extends ByteCodeVisitor {
        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            classInfo.className(name);
            Type superType = Type.getObjectType(superName);
            classInfo.superType(superType);
            if (isAbstract(access)) {
                classInfo.setAbstract();
            }
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            if (!isEligibleMethod(access, name, desc)) {
                return mv;
            }
            return new JobMethodVisitor(mv, access, name, desc, signature, exceptions);
        }

        private boolean isEligibleMethod(int access, String name, String desc) {
            // TODO: analyze parameters
            return isPublic(access);
        }

        private class JobMethodVisitor extends MethodVisitor implements Opcodes {

            private String methodName;
            private int access;
            private boolean requireScan;
            private JobMethodMetaInfo methodInfo;
            private ActionAnnotationVisitor aav;
            private EnvAnnotationVisitor eav;
            private List<String> paramTypes;

            JobMethodVisitor(MethodVisitor mv, int access, String methodName, String desc, String signature, String[] exceptions) {
                super(ASM5, mv);
                this.access = access;
                this.methodName = methodName;
                Type[] arguments = Type.getArgumentTypes(desc);
                paramTypes = C.newList();
                if (null != arguments) {
                    for (Type type : arguments) {
                        paramTypes.add(type.getClassName());
                    }
                }
            }

            @SuppressWarnings("unchecked")
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                AnnotationVisitor av = super.visitAnnotation(desc, visible);
                Type type = Type.getType(desc);
                String className = type.getClassName();
                try {
                    Class<? extends Annotation> c = (Class<? extends Annotation>)Class.forName(className);
                    if (JobClassMetaInfo.isActionAnnotation(c)) {
                        markRequireScan();
                        JobMethodMetaInfo tmp = new JobMethodMetaInfo(classInfo, paramTypes);
                        methodInfo = tmp;
                        classInfo.addAction(tmp);
                        this.aav = new ActionAnnotationVisitor(av, c, methodInfo);
                        return this.aav;
                    } else if (Env.isEnvAnnotation(c)) {
                        this.eav = new EnvAnnotationVisitor(av, c);
                        return this.eav;
                    }
                } catch (Exception e) {
                    throw E.unexpected(e);
                }
                //markNotTargetClass();
                return av;
            }

            @Override
            public void visitEnd() {
                if (!requireScan()) {
                    super.visitEnd();
                    return;
                }
                JobMethodMetaInfo info = methodInfo;
                info.name(methodName);
                boolean isStatic = AsmTypes.isStatic(access);
                if (isStatic) {
                    info.invokeStaticMethod();
                } else {
                    info.invokeInstanceMethod();
                }

                if (null != aav) {
                    if (null == eav || eav.matched()) {
                        aav.doRegistration();
                    }
                }
                super.visitEnd();
            }

            private void markRequireScan() {
                this.requireScan = true;
            }

            private boolean requireScan() {
                return requireScan;
            }

            private class ActionAnnotationVisitor extends AnnotationVisitor implements Opcodes {

                Object value;
                Object async;
                JobMethodMetaInfo method;
                Class<? extends Annotation> c;

                public ActionAnnotationVisitor(AnnotationVisitor av, Class<? extends Annotation> c, JobMethodMetaInfo methodMetaInfo) {
                    super(ASM5, av);
                    this.c = c;
                    this.method = methodMetaInfo;
                }

                @Override
                public void visitEnum(String name, String desc, String value) {
                    if (desc.contains("AppEventId")) {
                        this.value = AppEventId.valueOf(value);
                    }
                    super.visitEnum(name, desc, value);
                }

                @Override
                public void visit(String name, Object value) {
                    if ("value".equals(name)) {
                        this.value = value;
                    } else if ("async".equals(name)) {
                        this.async = value;
                    } else if ("id".equals(name)) {
                        this.method.id(S.string(value));
                    }
                    super.visit(name, value);
                }

                public void doRegistration() {
                    if (value != null && async != null) {
                        value = $.T2(value, async);
                    } else if (value == null) {
                        value = async;
                    }
                    annotationProcessor.register(method, c, value);
                }
            }
        }
    }

}
