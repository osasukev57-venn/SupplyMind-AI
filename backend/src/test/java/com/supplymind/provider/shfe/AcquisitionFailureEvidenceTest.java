package com.supplymind.provider.shfe;
import com.supplymind.foundation.model.*;
import com.supplymind.foundation.storage.*;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.agent.evidence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AcquisitionFailureEvidenceTest {
    @TempDir Path temp;
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-08-21T04:00:00Z"), ZoneOffset.UTC);
    @ParameterizedTest @ValueSource(strings={"empty","missing","blank","status","none","partial"})
    void failuresRetainTruthAndNeverProduceBusinessReceipts(String kind) throws Exception {
        var root=DataRoot.forTest(temp); AtomicMoveSupport.probeOrFail(root);
        var files=new AtomicFileStore(root,new DirtyMarkerCodec()); var configs=new ConfigActivationStore(root,files,CLOCK); configs.ensureInitialDefault();
        var instant=new java.util.concurrent.atomic.AtomicReference<>(CLOCK.instant());
        Clock requestClock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return instant.get();}};
        ShfeHttpTransport transport=uri->{
            instant.set(CLOCK.instant().plusSeconds(7));
            if(kind.equals("none")) throw new IllegalStateException("secret-transport-token");
            return new ShfeHttpResponse(uri, kind.equals("status")?503:200,
                kind.equals("missing")?null:kind.equals("blank")?"":"application/json",
                kind.equals("empty")?new byte[0]:"partial-or-invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8), !kind.equals("partial"));
        };
        var provider=new ShfeAdFreePublicDataProvider(configs,new RawReceiptStore(root,files,CLOCK),new RawAcquisitionStore(root,files,CLOCK),requestClock,transport,new ShfeDailyMarketParser());
        assertThrows(RuntimeException.class,()->provider.collect(ProviderCollectRequest.current(List.of(MonitorSeriesDefaults.ADC12_SMM_ITEM_ID))));
        Path path;
        try(var paths=Files.list(temp.resolve("diagnostic/acquisition"))){path=paths.filter(p->!p.toString().endsWith(".manifest.json")).findFirst().orElseThrow();}
        var evidence=JsonV1Codec.decodeFile(Files.readAllBytes(path),AcquisitionFailureEvidenceV1.class);
        assertEquals(CLOCK.instant(),evidence.startedAt().toInstant());
        assertEquals(CLOCK.instant().plusSeconds(7),evidence.failedAt().toInstant());
        assertEquals(kind.equals("none")?"NONE":kind.equals("partial")?"PARTIAL":"COMPLETE",evidence.responseState());
        assertTrue(ManifestVerifier.matches(root,evidence.evidenceRef(),path,root.resolveDataRef(DataPaths.manifestRef(evidence.evidenceRef()))));
        if(kind.equals("none")){assertNull(evidence.httpStatus());assertNull(evidence.capturedLength());assertNull(evidence.payloadBase64());}
        if(kind.equals("empty")){assertEquals(0L,evidence.capturedLength());assertEquals(FileDigest.sha256(new byte[0]),evidence.capturedSha256());}
        if(kind.equals("missing")) assertNull(evidence.contentType());
        if(kind.equals("blank")) assertEquals("",evidence.contentType());
        if(kind.equals("status")){assertEquals(503,evidence.httpStatus());assertNotNull(evidence.sourceEvidenceRef());}
        assertFalse(Files.readString(path).contains("secret-transport-token"));
        assertNotEquals(EvidenceStatus.VERIFIED,new EvidenceRefVerifier(root).verify(evidence.evidenceRef()).status());
        assertFalse(Files.exists(temp.resolve("raw/formal"))); assertFalse(Files.exists(temp.resolve("processed")));
        var store=new AcquisitionFailureStore(root,files); byte[] before=Files.readAllBytes(path);
        assertEquals(evidence.evidenceRef(),store.store(evidence)); assertArrayEquals(before,Files.readAllBytes(path));
        var conflict=new AcquisitionFailureEvidenceV1(evidence.schemaVersion(), evidence.attemptId(), evidence.requestUrl(),
                evidence.startedAt(), evidence.failedAt().plusSeconds(1), evidence.responseState(), evidence.httpStatus(),
                evidence.contentType(), evidence.payloadBase64(), evidence.capturedLength(), evidence.capturedSha256(),
                evidence.failureCode(), evidence.sourceEvidenceRef());
        assertThrows(StorageException.class,()->store.store(conflict));
        assertArrayEquals(before,Files.readAllBytes(path));
        Files.writeString(path,"damaged");
        assertFalse(ManifestVerifier.matches(root,evidence.evidenceRef(),path,root.resolveDataRef(DataPaths.manifestRef(evidence.evidenceRef()))));
        assertThrows(StorageException.class,()->store.store(evidence));
    }
    @Test void partialSubscriberPreservesOnlyActuallyReceivedBytes() {
        var capture=new JdkShfeHttpTransport.CapturedBody();
        capture.info=new java.net.http.HttpResponse.ResponseInfo(){
            public int statusCode(){return 200;} public java.net.http.HttpHeaders headers(){return java.net.http.HttpHeaders.of(Map.of(),(a,b)->true);}
            public java.net.http.HttpClient.Version version(){return java.net.http.HttpClient.Version.HTTP_1_1;}
        };
        capture.onSubscribe(new java.util.concurrent.Flow.Subscription(){public void request(long n){}public void cancel(){}});
        capture.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[]{1,2,3})));
        capture.onError(new java.io.IOException("broken response"));
        var response=capture.partial(ShfeAdFreePublicDataProvider.CURRENT_TRADING_DAY_URI);
        assertFalse(response.complete());assertNull(response.contentType());assertArrayEquals(new byte[]{1,2,3},response.entityBytes());
    }
    @Test void diagnosticStorageFailureIsVisibleAndCannotReturnSuccess() throws Exception {
        var root=DataRoot.forTest(temp); AtomicMoveSupport.probeOrFail(root);
        var files=new AtomicFileStore(root,new DirtyMarkerCodec());var configs=new ConfigActivationStore(root,files,CLOCK);configs.ensureInitialDefault();
        Files.writeString(temp.resolve("diagnostic"),"blocked directory in temporary fixture");
        var provider=new ShfeAdFreePublicDataProvider(configs,new RawReceiptStore(root,files,CLOCK),new RawAcquisitionStore(root,files,CLOCK),CLOCK,
                uri->new ShfeHttpResponse(uri,200,null,new byte[0]),new ShfeDailyMarketParser());
        var error=assertThrows(StorageException.class,()->provider.collect(ProviderCollectRequest.current(List.of(MonitorSeriesDefaults.ADC12_SMM_ITEM_ID))));
        assertEquals("ACQUISITION_FAILURE_EVIDENCE_SAVE_FAILED",error.getMessage());
    }
}
