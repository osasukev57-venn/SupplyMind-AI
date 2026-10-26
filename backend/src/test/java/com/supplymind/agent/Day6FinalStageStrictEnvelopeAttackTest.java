package com.supplymind.agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.supplymind.agent.support.NarrativeAttackCases;
/** Migrated to the sole sections contract; each attack asserts the intended validation branch. */
class Day6FinalStageStrictEnvelopeAttackTest {
 @TempDir Path temp;
 @Test void plainFreeTextEvenWithValuesIsMalformed() throws Exception { NarrativeAttackCases.run(temp,"plain","NONE","MALFORMED_STRUCTURE"); }
 @Test void malformedJsonIsRejectedWithoutStringSalvage() throws Exception { NarrativeAttackCases.run(temp,"malformed","NONE","MALFORMED_STRUCTURE"); }
 @Test void emptyFactIdsRejectedInValidJson() throws Exception { NarrativeAttackCases.run(temp,"emptyIds","NONE","MALFORMED_SECTION"); }
 @Test void oneBadSectionPreservesIndependentGoodSection() throws Exception { NarrativeAttackCases.run(temp,"partial","PARTIAL","UNSUPPORTED_NUMBER"); }
 @Test void twoValuesReferencingOnlyOneFactRejected() throws Exception { NarrativeAttackCases.run(temp,"twoValuesOneRef","NONE","UNSUPPORTED_NUMBER"); }
 @Test void globalFactsCannotSupportUnreferencedNumber() throws Exception { NarrativeAttackCases.run(temp,"unrelatedFact","NONE","UNSUPPORTED_NUMBER"); }
 @Test void oppositeTrendNotAdopted() throws Exception { NarrativeAttackCases.run(temp,"trend","NONE","UNSUPPORTED_TREND"); }
 @Test void twoRolesWithTheirOwnFactsPass() throws Exception { NarrativeAttackCases.run(temp,"twoRoles","FULL",null); }
 @Test void wrongValueInsideOtherwiseValidJsonRejected() throws Exception { NarrativeAttackCases.run(temp,"number","NONE","UNSUPPORTED_NUMBER"); }
 @Test void unknownSourceInsideValidSectionRejected() throws Exception { NarrativeAttackCases.run(temp,"source","NONE","UNSUPPORTED_SOURCE"); }
 @Test void unsupportedBusinessDateRejected() throws Exception { NarrativeAttackCases.run(temp,"wrongDate","NONE","WRONG_DATE_ROLE"); }
 @Test void retiredTemplateNotASecondProductionProtocol() throws Exception { NarrativeAttackCases.run(temp,"retiredTemplate","NONE","UNSAFE_CONTENT"); }
 @Test void chineseMonetaryNumberCannotBypassNumericCheck() throws Exception { NarrativeAttackCases.run(temp,"chineseNumber","NONE","UNSUPPORTED_NUMBER_FORMAT"); }
 @Test void wrongCurrencyInChineseRejected() throws Exception { NarrativeAttackCases.run(temp,"currency","NONE","WRONG_UNIT"); }
 @Test void futureCertaintyIsNotAJavaTrendFact() throws Exception { NarrativeAttackCases.run(temp,"prediction","NONE","UNSUPPORTED_PREDICTION"); }
 @Test void unknownKindDoesNotEraseIndependentGoodSection() throws Exception { NarrativeAttackCases.run(temp,"invalidKind","PARTIAL","MALFORMED_SECTION"); }
}
