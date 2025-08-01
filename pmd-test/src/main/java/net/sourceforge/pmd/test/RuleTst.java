/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.xml.sax.InputSource;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.internal.util.ClasspathClassLoader;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.lang.rule.RuleSetLoadException;
import net.sourceforge.pmd.lang.rule.RuleSetLoader;
import net.sourceforge.pmd.properties.PropertyDescriptor;
import net.sourceforge.pmd.renderers.TextRenderer;
import net.sourceforge.pmd.reporting.GlobalAnalysisListener;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import net.sourceforge.pmd.test.schema.RuleTestCollection;
import net.sourceforge.pmd.test.schema.RuleTestDescriptor;
import net.sourceforge.pmd.test.schema.TestSchemaParser;

/**
 * Advanced methods for test cases
 */
public abstract class RuleTst {

    protected void setUp() {
        // This method is intended to be overridden by subclasses.
    }

    /**
     * Return the rules that will be tested. Each rule must have a corresponding XML file containing a test collection.
     * Test collections for all these rules are run separately.
     */
    protected List<Rule> getRules() {
        return Collections.emptyList();
    }

    /** Return extra rules that will be run while running the tests. */
    protected Collection<? extends Rule> getExtraRules() {
        return Collections.emptyList();
    }

    /**
     * Find a rule in a certain ruleset by name.
     */
    public static Rule findRule(String ruleSet, String ruleName) {
        try {
            RuleSet parsedRset = new RuleSetLoader().warnDeprecated(false).loadFromResource(ruleSet);
            Rule rule = parsedRset.getRuleByName(ruleName);
            if (rule == null) {
                fail("Rule " + ruleName + " not found in ruleset " + ruleSet);
            } else {
                rule.setRuleSetName(ruleSet);
            }
            return rule;
        } catch (RuleSetLoadException e) {
            e.printStackTrace();
            fail("Couldn't find ruleset " + ruleSet);
            return null;
        }
    }

    /**
     * Run the rule on the given code, and check the expected number of violations.
     */
    void runTest(RuleTestDescriptor test) {
        Rule rule = test.getRule();

        // always reinitialize the rule, regardless of test.getReinitializeRule() (#3976 / #3302)
        rule = reinitializeRule(rule);

        Map<PropertyDescriptor<?>, Object> oldProperties = rule.getPropertiesByPropertyDescriptor();
        Report report = null;
        try {
            int res;
            try {
                // Set test specific properties onto the Rule
                if (test.getProperties() != null) {
                    for (Map.Entry<Object, Object> entry : test.getProperties().entrySet()) {
                        String propertyName = (String) entry.getKey();
                        PropertyDescriptor propertyDescriptor = rule.getPropertyDescriptor(propertyName);
                        if (propertyDescriptor == null) {
                            throw new IllegalArgumentException(
                                    "No such property '" + propertyName + "' on Rule " + rule.getName());
                        }

                        Object value = propertyDescriptor.serializer().fromString((String) entry.getValue());
                        rule.setProperty(propertyDescriptor, value);
                    }
                }

                String dysfunctionReason = rule.dysfunctionReason();
                if (StringUtils.isNotBlank(dysfunctionReason)) {
                    throw new RuntimeException("Rule is not configured correctly: " + dysfunctionReason);
                }

                report = processUsingStringReader(test, rule);
                res = report.getViolations().size();
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException('"' + test.getDescription() + "\" failed", e);
            }
            assertEquals(test.getExpectedProblems(), res,
                    '"' + test.getDescription() + "\" resulted in wrong number of failures,");
            assertMessages(report, test);
            assertLineNumbers(report, test);
            assertSuppressions(report, test);
        } catch (AssertionError e) {
            printReport(test, report);
            throw e;
        } finally {
            // Restore old properties
            for (Map.Entry<PropertyDescriptor<?>, Object> entry : oldProperties.entrySet()) {
                rule.setProperty((PropertyDescriptor) entry.getKey(), entry.getValue());
            }
        }
    }


    /**
     * Code to be executed if the rule is reinitialised.
     *
     * @param rule The rule to reinitialise
     *
     * @return The rule once it has been reinitialised
     */
    private Rule reinitializeRule(Rule rule) {
        return rule.deepCopy();
    }

    private void assertSuppressions(Report report, RuleTestDescriptor test) {
        if (!test.hasExpectedSuppressions()) {
            return;
        }
        List<RuleTestDescriptor.SuppressionDescriptor> expectedSuppressions = test.getExpectedSuppressions();
        assertEquals(expectedSuppressions.size(), report.getSuppressedViolations().size(), "wrong number of suppressed violations");
        for (int i = 0; i < expectedSuppressions.size(); i++) {
            RuleTestDescriptor.SuppressionDescriptor expectedSuppression = expectedSuppressions.get(i);
            Report.SuppressedViolation actualSuppression = report.getSuppressedViolations().get(i);
            assertEquals(expectedSuppression.getLine(), actualSuppression.getRuleViolation().getBeginLine(), "wrong line for suppression");
            if (StringUtils.isNotBlank(expectedSuppression.getSuppressorId())) {
                assertEquals(expectedSuppression.getSuppressorId(), actualSuppression.getSuppressor().getId(), "wrong suppressor id");
            }
        }
    }

    private void assertMessages(Report report, RuleTestDescriptor test) {
        if (report == null || test.getExpectedMessages().isEmpty()) {
            return;
        }

        List<String> expectedMessages = test.getExpectedMessages();
        if (report.getViolations().size() != expectedMessages.size()) {
            throw new RuntimeException("Test setup error: number of expected messages doesn't match "
                                           + "number of violations for test case '" + test.getDescription() + "'");
        }

        int index = 0;
        for (RuleViolation violation : report.getViolations()) {
            String actual = violation.getDescription();
            assertEquals(expectedMessages.get(index), actual,
                         '"' + test.getDescription() + "\" produced wrong message on violation number " + (index + 1)
                             + ".");
            index++;
        }
    }

    private void assertLineNumbers(Report report, RuleTestDescriptor test) {
        if (report == null || test.getExpectedLineNumbers().isEmpty()) {
            return;
        }

        List<Integer> expected = test.getExpectedLineNumbers();
        List<Integer> expectedEndLines = test.getExpectedEndLineNumbers();
        if (report.getViolations().size() != expected.size()) {
            throw new RuntimeException("Test setup error: number of expected line numbers " + expected.size()
                                           + " doesn't match number of violations " + report.getViolations().size()
                                           + " for test case '"
                                           + test.getDescription() + "'");
        }

        int index = 0;
        for (RuleViolation violation : report.getViolations()) {
            Integer actualBeginLine = violation.getBeginLine();
            Integer actualEndLine = violation.getEndLine();

            assertEquals(expected.get(index), actualBeginLine,
                         '"' + test.getDescription() + "\" violation on wrong line number: violation number "
                             + (index + 1) + ".");
            if (!expectedEndLines.isEmpty()) {
                assertEquals(expectedEndLines.get(index), actualEndLine,
                        '"' + test.getDescription() + "\" violation on wrong end line number: violation number "
                            + (index + 1) + ".");
            }
            index++;
        }
    }

    private void printReport(RuleTestDescriptor test, Report report) {
        final String separator = "--------------------------------------------------------------";

        System.out.println(separator);
        System.out.println("Test Failure: " + test.getDescription());

        if (report == null) {
            System.out.println("There is no report!");
            System.out.println(separator);
            return;
        }

        System.out.println(
            " -> Expected " + test.getExpectedProblems() + " problem(s), " + report.getViolations().size()
                + " problem(s) found.");
        System.out.println(" -> Expected messages: " + test.getExpectedMessages());
        System.out.println(" -> Expected begin line numbers: " + test.getExpectedLineNumbers());
        if (!test.getExpectedEndLineNumbers().isEmpty()) {
            System.out.println(" -> Expected   end line numbers: " + test.getExpectedEndLineNumbers());
        }
        if (test.hasExpectedSuppressions()) {
            System.out.println(" -> Expected " + test.getExpectedSuppressions().size() + " suppression(s), "
                    + report.getSuppressedViolations().size() + " found.");
        }
        System.out.println();
        StringWriter reportOutput = new StringWriter();
        TextRenderer renderer = new TextRenderer();
        renderer.setWriter(reportOutput);
        try {
            renderer.start();
            renderer.renderFileReport(report);
            renderer.end();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println(reportOutput);
        System.out.println(separator);
    }

    private Report processUsingStringReader(RuleTestDescriptor test, Rule rule) {
        return runTestFromString(test.getCode(), rule, test.getLanguageVersion());
    }

    private static final ClassLoader TEST_AUXCLASSPATH_CLASSLOADER;

    static {
        final Path PATH_TO_JRT_FS_JAR;
        // find jrt-fs.jar to be added to auxclasspath
        // Similar logic like jdk.internal.jrtfs.SystemImage
        CodeSource codeSource = Object.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            PATH_TO_JRT_FS_JAR = Paths.get(System.getProperty("java.home"), "lib", "jrt-fs.jar");
        } else {
            URL location = codeSource.getLocation();
            if (!"file".equalsIgnoreCase(location.getProtocol())) {
                throw new IllegalStateException("Object.class loaded in unexpected way from " + location);
            }
            try {
                PATH_TO_JRT_FS_JAR = Paths.get(location.toURI());
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }

        try {
            TEST_AUXCLASSPATH_CLASSLOADER = new ClasspathClassLoader(PATH_TO_JRT_FS_JAR.toString(), PMDConfiguration.class.getClassLoader());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Run the rule on the given code and put the violations in the report.
     */
    Report runTestFromString(String code, Rule rule, LanguageVersion languageVersion) {
        PMDConfiguration configuration = new PMDConfiguration();
        configuration.setIgnoreIncrementalAnalysis(true);
        configuration.setDefaultLanguageVersion(languageVersion);
        configuration.setThreads(0); // don't use separate threads
        configuration.setClassLoader(TEST_AUXCLASSPATH_CLASSLOADER);

        try (PmdAnalysis pmd = PmdAnalysis.create(configuration)) {
            pmd.files().addFile(TextFile.forCharSeq(code, FileId.fromPathLikeString("file"), languageVersion));
            Collection<? extends Rule> extraRules = getExtraRules();
            if (!extraRules.isEmpty()) {
                pmd.addRuleSet(RuleSet.create("extra rules", "description", "file.xml", Collections.emptyList(), Collections.emptyList(), extraRules));
            }
            pmd.addRuleSet(RuleSet.forSingleRule(rule));
            pmd.addListener(GlobalAnalysisListener.exceptionThrower());
            return pmd.performAnalysisAndCollectReport();
        }
    }

    /**
     * getResourceAsStream tries to find the XML file in weird locations if the
     * ruleName includes the package, so we strip it here.
     */
    private String getCleanRuleName(Rule rule) {
        String fullClassName = rule.getClass().getName();
        if (fullClassName.equals(rule.getName())) {
            // We got the full class name, so we'll use the stripped name
            // instead
            String packageName = rule.getClass().getPackage().getName();
            return fullClassName.substring(packageName.length() + 1);
        } else {
            return rule.getName(); // Test is using findRule, smart!
        }
    }

    /**
     * Extract a set of tests from an XML file. The file should be
     * ./xml/RuleName.xml relative to the test class. The format is defined in
     * rule-tests_1_1_0.xsd in pmd-test-schema.
     */
    RuleTestCollection parseTestCollection(Rule rule) {
        String testsFileName = getCleanRuleName(rule);
        return parseTestCollection(rule, testsFileName);
    }

    private RuleTestCollection parseTestCollection(Rule rule, String testsFileName) {
        return parseTestXml(rule, testsFileName, "xml/");
    }

    /**
     * Extract a set of tests from an XML file with the given name. The file
     * should be ./xml/[testsFileName].xml relative to the test class. The
     * format is defined in test-data.xsd.
     */
    private RuleTestCollection parseTestXml(Rule rule, String testsFileName, String baseDirectory) {
        String testXmlFileName = baseDirectory + testsFileName + ".xml";
        String absoluteUriToTestXmlFile = new File(".").getAbsoluteFile().toURI() + "/src/test/resources/"
                + this.getClass().getPackage().getName().replaceAll("\\.", "/")
                + "/" + testXmlFileName;

        try (InputStream inputStream = getClass().getResourceAsStream(testXmlFileName)) {
            if (inputStream == null) {
                throw new RuntimeException("Couldn't find " + testXmlFileName);
            }
            InputSource source = new InputSource();
            source.setByteStream(inputStream);
            source.setSystemId(testXmlFileName);
            TestSchemaParser parser = new TestSchemaParser();
            RuleTestCollection ruleTestCollection = parser.parse(rule, source);
            ruleTestCollection.setAbsoluteUriToTestXmlFile(absoluteUriToTestXmlFile);
            return ruleTestCollection;
        } catch (Exception e) {
            throw new RuntimeException("Couldn't parse " + testXmlFileName + ", due to: " + e, e);
        }
    }

    /**
     * Run a set of tests defined in an XML test-data file for a rule. The file
     * should be ./xml/RuleName.xml relative to the test-class. The format is
     * defined in test-data.xsd.
     */
    public void runTests(Rule rule) {
        runTests(parseTestCollection(rule));
    }

    /**
     * Run a set of tests defined in a XML test-data file. The file should be
     * ./xml/[testsFileName].xml relative to the test-class. The format is
     * defined in test-data.xsd.
     */
    public void runTests(Rule rule, String testsFileName) {
        runTests(parseTestCollection(rule, testsFileName));
    }

    private void runTests(RuleTestCollection tests) {
        for (RuleTestDescriptor test : tests.getTests()) {
            runTest(test);
        }
    }

    @TestFactory
    Collection<DynamicTest> ruleTests() {
        setUp();
        final List<Rule> rules = new ArrayList<>(getRules());
        rules.sort(Comparator.comparing(Rule::getName));

        List<DynamicTest> tests = new ArrayList<>();
        for (Rule r : rules) {
            RuleTestCollection ruleTests = parseTestCollection(r);
            RuleTestDescriptor focused = ruleTests.getFocusedTestOrNull();
            for (RuleTestDescriptor t : ruleTests.getTests()) {
                if (focused != null && !focused.equals(t)) {
                    t.setDisabled(true); // disable it
                }
                tests.add(toDynamicTest(ruleTests, t));
            }
        }
        return tests;
    }

    private DynamicTest toDynamicTest(RuleTestCollection collection, RuleTestDescriptor testDescriptor) {
        URI testSourceUri = URI.create(
            collection.getAbsoluteUriToTestXmlFile() + "?line=" + testDescriptor.getLineNumber());
        if (testDescriptor.isDisabled()) {
            return DynamicTest.dynamicTest("[IGNORED] " + testDescriptor.getDescription(),
                                           testSourceUri,
                                           () -> { });
        }
        return DynamicTest.dynamicTest(testDescriptor.getDescription(),
                                       testSourceUri,
                                       () -> runTest(testDescriptor));
    }
}
