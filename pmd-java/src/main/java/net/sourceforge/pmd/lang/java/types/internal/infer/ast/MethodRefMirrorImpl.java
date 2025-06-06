/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */


package net.sourceforge.pmd.lang.java.types.internal.infer.ast;

import java.util.List;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import net.sourceforge.pmd.lang.java.ast.ASTExpression;
import net.sourceforge.pmd.lang.java.ast.ASTList;
import net.sourceforge.pmd.lang.java.ast.ASTMethodReference;
import net.sourceforge.pmd.lang.java.ast.ASTTypeExpression;
import net.sourceforge.pmd.lang.java.ast.InternalApiBridge;
import net.sourceforge.pmd.lang.java.ast.TypeNode;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.TypeOps;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.InvocationMirror.MethodCtDecl;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.MethodRefMirror;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprOps;
import net.sourceforge.pmd.lang.java.types.internal.infer.ast.JavaExprMirrors.MirrorMaker;
import net.sourceforge.pmd.util.AssertionUtil;
import net.sourceforge.pmd.util.CollectionUtil;

final class MethodRefMirrorImpl extends BaseFunctionalMirror<ASTMethodReference> implements MethodRefMirror {

    private JMethodSig exactMethod;
    private MethodCtDecl ctdecl;

    MethodRefMirrorImpl(JavaExprMirrors mirrors, ASTMethodReference lambda, ExprMirror parent, MirrorMaker subexprMaker) {
        super(mirrors, lambda, parent, subexprMaker);
        exactMethod = mirrors.ts.UNRESOLVED_METHOD;

        // this is in case of failure: if the inference doesn't succeed
        // and doesn't end up calling those, then we still have a non-null
        // result in there.
        // don't call this one as it would mean to the node "don't recompute my type"
        // even if a parent conditional failed its standalone test
        // setInferredType(mirrors.ts.UNKNOWN);
    }

    @Override
    public void finishFailedInference(@Nullable JTypeMirror targetType) {
        super.finishFailedInference(targetType);
        MethodCtDecl noCtDecl = factory.infer.getMissingCtDecl().withExpr(this);
        if (!TypeOps.isUnresolved(getTypeToSearch())) {
            JMethodSig exactMethod = ExprOps.getExactMethod(this);
            if (exactMethod != null) {
                // as a fallback, if the method reference is exact,
                // we populate the compile time decl anyway.
                setCompileTimeDecl(noCtDecl.withMethod(exactMethod));
                return;
            }
        }
        setCompileTimeDecl(noCtDecl);
    }

    @Override
    public boolean isEquivalentToUnderlyingAst() {
        AssertionUtil.validateState(ctdecl != null, "overload resolution is not complete");

        // must bind to the same ctdecl.
        return myNode.getReferencedMethod().equals(ctdecl.getMethodType());
    }

    @Override
    public boolean isConstructorRef() {
        return myNode.isConstructorReference();
    }

    @Override
    public JTypeMirror getLhsIfType() {
        ASTExpression lhsType = myNode.getQualifier();
        return lhsType instanceof ASTTypeExpression
               ? lhsType.getTypeMirror()
               : null;
    }

    @Override
    public JTypeMirror getTypeToSearch() {
        return myNode.getLhs().getTypeMirror();
    }

    @Override
    public String getMethodName() {
        return myNode.getMethodName();
    }

    @Override
    public void setCompileTimeDecl(MethodCtDecl methodType) {
        this.ctdecl = methodType;
        if (mayMutateAst()) {
            InternalApiBridge.setCompileTimeDecl(myNode, methodType);
        }
    }

    @Override
    public @NonNull List<JTypeMirror> getExplicitTypeArguments() {
        return CollectionUtil.map(
            ASTList.orEmpty(myNode.getExplicitTypeArguments()),
            TypeNode::getTypeMirror
        );
    }

    @Override
    public JMethodSig getCachedExactMethod() {
        return exactMethod;
    }

    @Override
    public void setCachedExactMethod(@Nullable JMethodSig sig) {
        exactMethod = sig;
    }
}
