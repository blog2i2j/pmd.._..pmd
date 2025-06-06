/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */


package net.sourceforge.pmd.lang.java.symbols;

import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.Substitution;
import net.sourceforge.pmd.lang.java.types.TypeSystem;


/**
 * Represents a constructor declaration.
 *
 * @since 7.0.0
 */
public interface JConstructorSymbol extends JExecutableSymbol, BoundToNode<ASTConstructorDeclaration> {

    /** Common dummy name for constructor symbols. */
    String CTOR_NAME = "new";


    /** For constructors, this returns the special name {@value CTOR_NAME}. */
    @Override
    default String getSimpleName() {
        return CTOR_NAME;
    }

    @Override
    default JTypeMirror getReturnType(Substitution subst) {
        TypeSystem ts = getTypeSystem();
        return ts.declaration(getEnclosingClass()).subst(subst);
    }

    @Override
    default <R, P> R acceptVisitor(SymbolVisitor<R, P> visitor, P param) {
        return visitor.visitCtor(this, param);
    }

}
