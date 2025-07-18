/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.ast;

/**
 * The name of a module. Module names look like package names, eg
 * {@code java.base}.
 */
public final class ASTModuleName extends AbstractJavaNode {
    private String name;

    ASTModuleName(int id) {
        super(id);
    }


    @Override
    public <P, R> R acceptVisitor(JavaVisitor<? super P, ? extends R> visitor, P data) {
        return visitor.visit(this, data);
    }

    /**
     * Returns the name of the declared module. Module names look
     * like package names, eg {@code java.base}.
     */
    public String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }
}
