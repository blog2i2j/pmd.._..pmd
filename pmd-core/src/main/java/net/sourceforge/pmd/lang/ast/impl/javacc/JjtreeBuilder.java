/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.ast.impl.javacc;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared implementation of the tree builder generated by JJTree.
 *
 * @param <N> Internal base class for nodes
 */
public final class JjtreeBuilder<N extends AbstractJjtreeNode<N, ?>> {

    private final List<N> nodes = new ArrayList<>();
    private final List<Integer> marks = new ArrayList<>();

    private int sp = 0;        // number of nodes on stack
    private int mk = 0;        // current mark
    private boolean nodeCreated;

    /*** If non-zero, then the top "n" nodes of the stack will be injected as the first children of the next
     * node to be opened. This is not very flexible, but it's enough. The grammar needs to take
     * care of the order in which nodes are opened in a few places, in most cases this just means using
     * eg A() B() #N(2) instead of (A() B()) #N, so as not to open N before A.
     */
    private int numPendingInjection;


    /**
     * Determines whether the current node was actually closed and
     * pushed.  This should only be called in the final user action of a
     * node scope.
     */
    public boolean nodeCreated() {
        return nodeCreated;
    }

    /**
     * Call this to reinitialize the node stack.  It is called
     * automatically by the parser's ReInit() method.
     */
    public void reset() {
        nodes.clear();
        marks.clear();
        sp = 0;
        mk = 0;
    }

    /**
     * Returns the root node of the AST.  It only makes sense to call
     * this after a successful parse.
     */
    public N rootNode() {
        return nodes.get(0);
    }

    /***
     * Peek the nth node from the top of the stack.
     * peekNode(0) == peekNode()
     */
    public N peekNode(int n) {
        return nodes.get(nodes.size() - n - 1);
    }

    public boolean isInjectionPending() {
        return numPendingInjection > 0;
    }

    public void injectRight(int n) {
        numPendingInjection = n;
    }

    /** Pushes a node on to the stack. */

    public void pushNode(N n) {
        nodes.add(n);
        ++sp;
    }

    /**
     * Returns the node on the top of the stack, and remove it from the
     * stack.
     */
    public N popNode() {
        --sp;
        if (sp < mk) {
            mk = marks.remove(marks.size() - 1);
        }
        return nodes.remove(nodes.size() - 1);
    }

    /** Returns the node currently on the top of the stack. */
    public N peekNode() {
        return nodes.get(nodes.size() - 1);
    }

    /**
     * Returns the number of children on the stack in the current node
     * scope.
     */
    public int nodeArity() {
        return sp - mk;
    }


    public void clearNodeScope(N n) {
        while (sp > mk) {
            popNode();
        }
        mk = marks.remove(marks.size() - 1);
    }


    public void openNodeScope(N n, JavaccToken firstToken) {
        marks.add(mk);
        mk = sp;

        if (isInjectionPending()) {
            mk -= numPendingInjection;
            numPendingInjection = 0;
        }

        n.setFirstToken(firstToken);
        n.jjtOpen();
    }


    /**
     * Close the node scope and adds the given number of children to the
     * node. A definite node is constructed from a specified number of
     * children.  That number of nodes are popped from the stack and
     * made the children of the definite node.  Then the definite node
     * is pushed on to the stack.
     */
    public void closeNodeScope(N n, final int num, JavaccToken lastToken) {
        int a = nodeArity();
        mk = marks.remove(marks.size() - 1);
        N child = null;
        int i = num;
        while (i-- > 0) {
            child = popNode();
            n.addChild(child, i);
        }

        if (child != null && num > a) {
            // this node has more children that what was in its node scope
            // (ie first token is wrong)
            n.setFirstToken(child.getFirstToken());
        }

        closeImpl(n, lastToken);
    }


    /**
     * Close the node scope if the condition is true.
     * All the nodes that have been pushed since the node was opened are
     * made children of the conditional node, which is then pushed on to
     * the stack.  If the condition is false the node is not constructed
     * and they are left on the stack.
     *
     * @param n         Node to close
     * @param condition Whether to close the node or not
     * @param lastToken Last token that was consumed while the node scope was open
     */
    public void closeNodeScope(N n, boolean condition, JavaccToken lastToken) {
        if (condition) {
            int a = nodeArity();
            mk = marks.remove(marks.size() - 1);
            while (a-- > 0) {
                n.addChild(popNode(), a);
            }
            closeImpl(n, lastToken);
        } else {
            mk = marks.remove(marks.size() - 1);
            nodeCreated = false;
        }
    }


    private void closeImpl(N n, JavaccToken lastToken) {
        if (lastToken.getNext() == n.getFirstToken()) {
            // this means, that the node has zero length.
            // create an implicit token to represent this case.
            JavaccToken implicit = JavaccToken.implicitBefore(lastToken.getNext());

            n.setFirstToken(implicit);
            n.setLastToken(implicit);
        } else {
            n.setLastToken(lastToken);
        }
        // note that the last token has been set before jjtClose
        n.jjtClose();
        pushNode(n);
        nodeCreated = true;
    }
}
