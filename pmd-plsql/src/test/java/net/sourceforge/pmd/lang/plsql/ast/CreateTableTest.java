/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.plsql.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.sourceforge.pmd.lang.plsql.AbstractPLSQLParserTst;

class CreateTableTest extends AbstractPLSQLParserTst {

    @Test
    void parseCreateTable() {
        ASTInput input = plsql.parseResource("CreateTable.pls");

        // 5th column of first table statement has a inline constraint of type check
        ASTTableColumn columnStatus = input.firstChild(ASTTable.class).children(ASTTableColumn.class).get(4);
        assertEquals("status", columnStatus.firstChild(ASTID.class).getImage());
        assertEquals(ConstraintType.CHECK, columnStatus.firstChild(ASTInlineConstraint.class).getType());
    }

    @Test
    void parseCreateOrganizedTable() {
        plsql.parseResource("CreateOrganizedTable.pls");
    }
}
