package com.supplymind.agent.report;

import com.supplymind.agent.evidence.*;
import com.supplymind.foundation.codec.*;
import com.supplymind.foundation.model.*;
import com.supplymind.foundation.storage.*;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Captures only the reachable evidence closure. The report is committed LAST by ReportStore. */
final class ReportSnapshotStore {
    private final DataRoot root;
    private final AtomicFileStore files;
    ReportSnapshotStore(DataRoot root, AtomicFileStore files) { this.root=root; this.files=files; }
    ReportSnapshotV2 capture(AgentReportV1 report, Runnable checkpoint) {
        Map<String, byte[]> captured=new TreeMap<>(), manifests=new TreeMap<>();
        var pending=new ArrayDeque<String>();
        report.evidencePack().evidenceRefs().stream().filter(e->e.status()==EvidenceStatus.VERIFIED).forEach(e->pending.add(e.ref()));
        long size=0;
        try {
            while(!pending.isEmpty()) {
                checkpoint.run(); String ref=pending.removeFirst(); if(captured.containsKey(ref))continue;
                byte[] bytes=readBounded(root.resolveDataRef(ref),32L*1024*1024-size);
                byte[] metadata=readBounded(root.resolveDataRef(DataPaths.manifestRef(ref)),32L*1024*1024-size-bytes.length);
                size+=bytes.length+metadata.length;
                if(captured.size()>=512 || size>32L*1024*1024)throw new StorageException("REPORT_EVIDENCE_LIMIT_EXCEEDED");
                captured.put(ref,bytes); manifests.put(ref,metadata); pending.addAll(dependencies(ref,bytes));
            }
            List<ReportSnapshotV2.Binding> bindings=new ArrayList<>();
            for(var entry:captured.entrySet()) {
                checkpoint.run(); String ref=entry.getKey(); byte[] bytes=entry.getValue(),metadata=manifests.get(ref);
                String snapshot=null,metaSnapshot=null;
                if(!ref.startsWith("raw/")) {
                    snapshot=storeBlob(bytes,ref.substring(ref.lastIndexOf('/')+1),report.createdAt());
                    metaSnapshot=storeBlob(metadata,"metadata.json",report.createdAt());
                }
                bindings.add(new ReportSnapshotV2.Binding(ref,FileDigest.sha256(bytes),FileDigest.sha256(metadata),snapshot,metaSnapshot));
            }
            var envelope=new ReportSnapshotV2("AGENT-REPORT-V2",report,bindings);
            DataRoot view=verify(envelope);
            // Two observations plus dependency SHA checks reject a mixed generation; no silent retry/rebase.
            for(var binding:bindings) {
                checkpoint.run();
                if(!FileDigest.sha256(root.resolveDataRef(binding.originalRef())).equals(binding.sha256())
                    || !FileDigest.sha256(root.resolveDataRef(DataPaths.manifestRef(binding.originalRef()))).equals(binding.manifestSha256()))
                    throw new StorageException("REPORT_EVIDENCE_CHANGED_DURING_CAPTURE");
            }
            requireReportBinding(envelope,view);
            checkpoint.run(); return envelope;
        } catch(java.io.IOException ex) { throw new StorageException("REPORT_SNAPSHOT_IO_FAILED",ex); }
    }
    private static byte[] readBounded(Path path,long remaining) throws java.io.IOException {
        if(remaining<0)throw new StorageException("REPORT_EVIDENCE_LIMIT_EXCEEDED");
        try(var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes((int)remaining+1);
            if(bytes.length>remaining)throw new StorageException("REPORT_EVIDENCE_LIMIT_EXCEEDED");
            return bytes;
        }
    }
    private String storeBlob(byte[] bytes,String name,OffsetDateTime at) {
        String ref="report-evidence/"+FileDigest.sha256(bytes)+"/"+name;
        if(Files.exists(root.resolveDataRef(ref))) {
            if(!FileDigest.bytesEqual(root.resolveDataRef(ref),bytes)||!ManifestVerifier.matches(root,ref,root.resolveDataRef(ref),root.resolveDataRef(DataPaths.manifestRef(ref))))
                throw new StorageException("REPORT_SNAPSHOT_DAMAGED");
            return ref;
        }
        files.commit("snapshot-"+UUID.randomUUID(),DirtyTransactionType.SINGLE_FILE,at,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE,ref,bytes,
                        JsonV1Codec.encodeFile(ManifestFactory.json(ref,bytes,List.of(),at)),true)));
        return ref;
    }
    DataRoot verify(ReportSnapshotV2 envelope) {
        Map<String,String> mapping=new HashMap<>();
        Set<String> identities=new HashSet<>();
        for(var binding:envelope.evidenceBindings()) {
            String ref=binding.originalRef(),meta=DataPaths.manifestRef(ref);
            String data=binding.snapshotRef()==null?ref:binding.snapshotRef();
            String metadata=binding.manifestSnapshotRef()==null?meta:binding.manifestSnapshotRef();
            if(!FileDigest.sha256(root.resolveDataRef(data)).equals(binding.sha256())
                ||!FileDigest.sha256(root.resolveDataRef(metadata)).equals(binding.manifestSha256()))throw new StorageException("REPORT_SNAPSHOT_HASH_MISMATCH");
            if(binding.snapshotRef()!=null)for(String blob:List.of(data,metadata))
                if(!ManifestVerifier.matches(root,blob,root.resolveDataRef(blob),root.resolveDataRef(DataPaths.manifestRef(blob))))throw new StorageException("REPORT_SNAPSHOT_MANIFEST_MISMATCH");
            mapping.put(ref,data);mapping.put(meta,metadata);identities.add(ref);
        }
        DataRoot view=root.evidenceView(mapping);
        try {
            for(String ref:identities) {
                byte[] bytes=Files.readAllBytes(view.resolveDataRef(ref));
                if(!identities.containsAll(dependencies(ref,bytes)))throw new StorageException("REPORT_SNAPSHOT_CLOSURE_INCOMPLETE");
                if(!ManifestVerifier.matches(view,ref,view.resolveDataRef(ref),view.resolveDataRef(DataPaths.manifestRef(ref))))throw new StorageException("REPORT_SNAPSHOT_LINEAGE_INVALID");
                if(ref.startsWith("processed/daily/"))for(var row:CsvV1Codec.decodeDaily(bytes))for(var input:row.inputRefs()) {
                    var raw=JsonV1Codec.decodeFile(Files.readAllBytes(view.resolveDataRef(input.rawRef())),RawReceiptV1.class);
                    var timeline=JsonV1Codec.decodeFile(Files.readAllBytes(view.resolveDataRef(DataPaths.stagingRef(input.runId()))),LifecycleTimelineV1.class);
                    if(!raw.runId().equals(input.runId())||!timeline.rawRef().equals(input.rawRef())||!timeline.isPublishedForDailyInput())throw new StorageException("REPORT_SNAPSHOT_PUBLISH_BINDING_INVALID");
                }
            }
            requireReportBinding(envelope,view);
            return view;
        }catch(java.io.IOException ex){throw new StorageException("REPORT_SNAPSHOT_UNREADABLE",ex);}
    }
    private static void requireReportBinding(ReportSnapshotV2 envelope,DataRoot view) {
        var verified=envelope.report().evidencePack().evidenceRefs().stream().filter(e->e.status()==EvidenceStatus.VERIFIED).toList();
        var actual=new EvidenceRefVerifier(view).verifyAllWithAuthoritativeLineage(verified.stream().map(EvidencePackV1.EvidenceRefEntry::ref).toList());
        if(ReportStore.bindingMismatch(verified,actual)!=null || actual.stream().anyMatch(e->e.status()!=EvidenceStatus.VERIFIED))throw new StorageException("REPORT_SNAPSHOT_REPORT_BINDING_INVALID");
        Set<String> refs=new HashSet<>();verified.forEach(e->refs.add(e.ref()));
        for(var claim:envelope.report().claims())if(claim.evidenceRefs().isEmpty()||!refs.containsAll(claim.evidenceRefs()))throw new StorageException("REPORT_CLAIM_UNVERIFIED");
    }
    private static List<String> dependencies(String ref,byte[] bytes) {
        Set<String> result=new LinkedHashSet<>();
        if(ref.startsWith("processed/aggregate/"))for(var row:CsvV1Codec.decodeAggregate(bytes))for(var input:row.inputRefs())result.add(input.dailyFileRef());
        else if(ref.startsWith("processed/daily/"))for(var row:CsvV1Codec.decodeDaily(bytes))for(var input:row.inputRefs()){result.add(input.rawRef());result.add(DataPaths.stagingRef(input.runId()));}
        else if(ref.startsWith("staging/"))result.add(JsonV1Codec.decodeFile(bytes,LifecycleTimelineV1.class).rawRef());
        else if(ref.startsWith("raw/")&&!ref.startsWith("raw/source/")&&!ref.startsWith("raw/import/")) {
            var raw=JsonV1Codec.decodeFile(bytes,RawReceiptV1.class);
            if(raw.acquisitionRef()!=null)result.add(raw.acquisitionRef());
            result.add(DataPaths.configHistoryRef(raw.configVersion()));
        }else if(ref.startsWith("warning/")&&ref.endsWith(".ack.json"))result.add(JsonV1Codec.decodeFile(bytes,com.supplymind.warning.WarningAcknowledgementV1.class).warningRef());
        else if(ref.startsWith("warning/"))result.addAll(JsonV1Codec.decodeFile(bytes,com.supplymind.warning.WarningRecordV1.class).evidenceRefs());
        return List.copyOf(result);
    }
}
