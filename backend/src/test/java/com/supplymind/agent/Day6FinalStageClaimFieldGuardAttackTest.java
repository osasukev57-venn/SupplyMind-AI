package com.supplymind.agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.supplymind.agent.support.NarrativeAttackCases;
/** Migrated to the sole sections contract; each attack asserts the intended validation branch. */
class Day6FinalStageClaimFieldGuardAttackTest {
 @TempDir Path temp;
 @Test void secretInAnswerRejectsWithZeroPersistence() throws Exception { NarrativeAttackCases.run(temp,"secret","NONE","SECRET_INJECTION"); }
 @Test void secretInFactIdsRejects() throws Exception { NarrativeAttackCases.run(temp,"secretId","NONE","SECRET_INJECTION"); }
 @Test void standaloneNumberNextToSupportedDateMustBeFactBacked() throws Exception { NarrativeAttackCases.run(temp,"dateFragmentNumber","NONE","UNSUPPORTED_NUMBER"); }
 @Test void dateTokenFragmentsAreNotBusinessNumbers() throws Exception { NarrativeAttackCases.run(temp,"validDate","FULL",null); }
 @Test void fabricatedSourceDeclarationRejects() throws Exception { NarrativeAttackCases.run(temp,"source","NONE","UNSUPPORTED_SOURCE"); }
 @Test void fabricatedEnglishSourceDeclarationRejects() throws Exception { NarrativeAttackCases.run(temp,"sourceEnglish","NONE","UNSUPPORTED_SOURCE"); }
 @Test void authoritativeSourcePrefixCannotValidateExtendedFakeSource() throws Exception { NarrativeAttackCases.run(temp,"sourcePrefix","NONE","UNSUPPORTED_SOURCE"); }
 @Test void realSourceDeclarationBackedBySelectedFactPasses() throws Exception { NarrativeAttackCases.run(temp,"validSource","FULL",null); }
}
