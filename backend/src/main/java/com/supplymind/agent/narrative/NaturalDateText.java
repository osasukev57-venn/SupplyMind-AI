package com.supplymind.agent.narrative;

import com.supplymind.agent.evidence.EvidencePackV1;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Normalize date typography for checks only; the adopted Chinese paragraph remains untouched. */
final class NaturalDateText {
    private NaturalDateText() {}
    static String checked(String text,List<EvidencePackV1.Fact> facts) {
        try {
            var dates=new HashSet<String>();
            for(var f:facts) {
                for(String date:Arrays.asList(f.businessDate(),f.periodStart(),f.periodEnd()))if(date!=null)dates.add(date);
                if(f.factType().equals("warning.acknowledgedAt"))dates.add(java.time.OffsetDateTime.parse(f.value()).toLocalDate().toString());
            }
            var years=new HashSet<String>();var months=new HashSet<String>();
            dates.forEach(d->{if(d.length()>=10){years.add(d.substring(0,4));months.add(d.substring(0,7));}});
            var full=Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日").matcher(text);
            String result=full.replaceAll(m->LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))).toString());
            var shortDate=Pattern.compile("(?<![\\d年])(\\d{1,2})月(\\d{1,2})日").matcher(result);
            if(shortDate.find()) {
                if(years.size()!=1)return null;
                String year=years.iterator().next();shortDate.reset();
                result=shortDate.replaceAll(m->LocalDate.of(Integer.parseInt(year),Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))).toString());
            }
            result=Pattern.compile("(\\d{4})年(\\d{1,2})月").matcher(result)
                .replaceAll(m->java.time.YearMonth.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))).toString());
            var month=Pattern.compile("(?<![\\d-])(\\d{4}-\\d{2})(?![-\\d])").matcher(result);
            while(month.find())if(!months.contains(month.group(1)))return null;
            return month.replaceAll("");
        }catch(java.time.DateTimeException|NumberFormatException invalid) {return null;}
    }
}
