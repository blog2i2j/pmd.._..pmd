/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.rule.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.sourceforge.pmd.benchmark.TimeTracker;
import net.sourceforge.pmd.benchmark.TimedOperation;
import net.sourceforge.pmd.benchmark.TimedOperationCategory;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry;
import net.sourceforge.pmd.lang.ast.RootNode;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.lang.rule.InternalApiBridge;
import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.lang.rule.RuleReference;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.lang.rule.RuleSet.RuleSetBuilder;
import net.sourceforge.pmd.lang.rule.impl.UnnecessaryPmdSuppressionRule;
import net.sourceforge.pmd.reporting.FileAnalysisListener;
import net.sourceforge.pmd.util.log.PmdReporter;

/**
 * Grouping of Rules per Language in a RuleSet.
 *
 * @author pieter_van_raemdonck - Application Engineers NV/SA - www.ae.be
 */
public class RuleSets {

    private final List<RuleSet> ruleSets;

    private RuleApplicator ruleApplicator;

    /**
     * Copy constructor. Deep copies RuleSets.
     *
     * @param ruleSets The RuleSets to copy.
     */
    public RuleSets(final RuleSets ruleSets) {
        List<RuleSet> rsets = new ArrayList<>();
        for (final RuleSet rs : ruleSets.ruleSets) {
            rsets.add(new RuleSet(rs));
        }
        this.ruleSets = Collections.unmodifiableList(rsets);
    }

    public RuleSets(Collection<? extends RuleSet> ruleSets) {
        List<RuleSet> rulesets = new ArrayList<>();
        List<RuleSet> suppressionRules = new ArrayList<>();

        /*
         Suppression rules are separated because they must be run last.
         They are packed into their own rulesets. and added at the end of the ruleset list.
         */

        for (RuleSet ruleSet : ruleSets) {
            RuleSetBuilder noSuppressions = ruleSet.toBuilder();
            RuleSetBuilder onlySuppressions = ruleSet.toBuilder();

            noSuppressions.removeIf(rule1 -> followReference(rule1) instanceof UnnecessaryPmdSuppressionRule);
            onlySuppressions.removeIf(rule1 -> !(followReference(rule1) instanceof UnnecessaryPmdSuppressionRule));
            rulesets.add(noSuppressions.build());
            suppressionRules.add(onlySuppressions.build());
        }
        rulesets.addAll(suppressionRules);
        this.ruleSets = Collections.unmodifiableList(rulesets);
    }

    private static Rule followReference(Rule rule) {
        if (rule instanceof RuleReference) {
            return followReference(((RuleReference) rule).getRule());
        }
        return rule;
    }

    /**
     * Public constructor. Add the given rule set.
     *
     * @param ruleSet the RuleSet
     */
    public RuleSets(RuleSet ruleSet) {
        this.ruleSets = Collections.singletonList(ruleSet);
    }

    public void initializeRules(LanguageProcessorRegistry lpReg, PmdReporter reporter) {
        // this is abusing the mutability of RuleSet, will go away eventually.
        for (RuleSet rset : ruleSets) {
            for (Iterator<Rule> iterator = rset.getRules().iterator(); iterator.hasNext();) {
                Rule rule = iterator.next();
                try {
                    rule.initialize(lpReg.getProcessor(rule.getLanguage()));
                } catch (Exception e) {
                    reporter.errorEx(
                        "Exception while initializing rule " + rule.getName() + ", the rule will not be run", e);
                    iterator.remove();
                }
            }
        }
    }

    private RuleApplicator prepareApplicator() {
        return RuleApplicator.build(ruleSets.stream().flatMap(it -> it.getRules().stream())::iterator);
    }

    /**
     * Get all the RuleSets.
     *
     * @return RuleSet[]
     */
    public RuleSet[] getAllRuleSets() {
        return ruleSets.toArray(new RuleSet[0]);
    }

    // internal
    List<RuleSet> getRuleSetsInternal() {
        return ruleSets;
    }

    public Iterator<RuleSet> getRuleSetsIterator() {
        return ruleSets.iterator();
    }

    /**
     * Return all rules from all rulesets.
     *
     * @return Set
     */
    public Set<Rule> getAllRules() {
        Set<Rule> result = new HashSet<>();
        for (RuleSet r : ruleSets) {
            result.addAll(r.getRules());
        }
        return result;
    }

    /**
     * Check if a given source file should be checked by rules in this RuleSets.
     *
     * @param file
     *            the source file to check
     * @return <code>true</code> if the file should be checked,
     *         <code>false</code> otherwise
     */
    public boolean applies(TextFile file) {
        for (RuleSet ruleSet : ruleSets) {
            if (InternalApiBridge.ruleSetApplies(ruleSet, file.getFileId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Apply all applicable rules to the compilation units. Applicable means the
     * language of the rules must match the language of the source (@see
     * applies).
     *
     * @param root     the List of compilation units; the type these must have,
     *                 depends on the source language
     * @param listener Listener that will handle events while analysing.
     */
    public void apply(RootNode root, FileAnalysisListener listener) {
        if (ruleApplicator == null) {
            // initialize here instead of ctor, because some rules properties
            // are set after creating the ruleset, and jaxen xpath queries
            // initialize their XPath expressions when calling getRuleChainVisits()... fixme
            this.ruleApplicator = prepareApplicator();
        }

        try (TimedOperation ignored = TimeTracker.startOperation(TimedOperationCategory.RULE_AST_INDEXATION)) {
            ruleApplicator.index(root);
        }

        for (RuleSet ruleSet : ruleSets) {
            if (InternalApiBridge.ruleSetApplies(ruleSet, root.getTextDocument().getFileId())) {
                ruleApplicator.apply(ruleSet.getRules(), listener);
            }
        }
    }

    /**
     * Returns the first Rule found with the given name.
     *
     * Note: Since we support multiple languages, rule names are not expected to
     * be unique within any specific ruleset.
     *
     * @param ruleName
     *            the exact name of the rule to find
     * @return the rule or null if not found
     */
    public Rule getRuleByName(String ruleName) {
        Rule rule = null;
        for (Iterator<RuleSet> i = ruleSets.iterator(); i.hasNext() && rule == null;) {
            RuleSet ruleSet = i.next();
            rule = ruleSet.getRuleByName(ruleName);
        }
        return rule;
    }

    /**
     * Determines the total count of rules that are used in all rule sets.
     *
     * @return the count
     */
    public int ruleCount() {
        int count = 0;
        for (RuleSet r : ruleSets) {
            count += r.getRules().size();
        }
        return count;
    }


    /**
     * Remove and collect any rules that report problems.
     *
     * @param collector
     */
    public void removeDysfunctionalRules(Collection<Rule> collector) {
        for (RuleSet ruleSet : ruleSets) {
            ruleSet.removeDysfunctionalRules(collector);
        }
    }

    /**
     * Retrieves a checksum of the rulesets being used. Any change to any rule
     * of any ruleset should trigger a checksum change.
     *
     * @return The checksum for this ruleset collection.
     */
    public long getChecksum() {
        long checksum = 1;
        for (final RuleSet ruleSet : ruleSets) {
            checksum = checksum * 31 + ruleSet.getChecksum();
        }
        return checksum;
    }
}
