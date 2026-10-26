package com.supplymind.agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.supplymind.agent.support.NarrativeAttackCases;
/** Migrated to the sole sections contract; each attack asserts the intended validation branch. */
class Day6FinalStageStructuredClaimsAttackTest {
 @TempDir Path temp;
 @Test void structuredSectionsAreAdoptedSeparatelyFromJavaClaims() throws Exception { NarrativeAttackCases.run(temp,"valid","FULL",null); }
 @Test void phaseBReceivesExactFactIdentityAndBusinessMetadata() throws Exception { NarrativeAttackCases.run(temp,"metadata","FULL",null); }
 @Test void fabricatedNumberInsideValidEnvelopeReachesNumericGuard() throws Exception { NarrativeAttackCases.run(temp,"number","NONE","UNSUPPORTED_NUMBER"); }
 @Test void unrelatedFactCannotSupportLatestRole() throws Exception { NarrativeAttackCases.run(temp,"crossRole","NONE","WRONG_FACT_ROLE"); }
 @Test void unknownFactReferenceRejected() throws Exception { NarrativeAttackCases.run(temp,"unknown","NONE","UNKNOWN_FACT_ID"); }
 @Test void percentageIsNeverEquatedToPlainNumber() throws Exception { NarrativeAttackCases.run(temp,"percent","NONE","UNSUPPORTED_NUMBER"); }
 @Test void dateMustBeSupportedBySelectedRole() throws Exception { NarrativeAttackCases.run(temp,"wrongDate","NONE","WRONG_DATE_ROLE"); }
 @Test void plainFreeTextCannotBecomeNarrative() throws Exception { NarrativeAttackCases.run(temp,"plain","NONE","MALFORMED_STRUCTURE"); }
}
