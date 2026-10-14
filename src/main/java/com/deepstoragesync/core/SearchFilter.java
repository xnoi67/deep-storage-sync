package com.deepstoragesync.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Search bar logic: plain keywords, "@text" for id contains, "/regex/" for regular expressions. */
public final class SearchFilter {

    private SearchFilter() { }

    public static List<ContextSorter.Entry> filter(Collection<ContextSorter.Entry> input, String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) return new ArrayList<>(input);

        List<ContextSorter.Entry> out = new ArrayList<>();
        if (q.length() > 2 && q.startsWith("/") && q.endsWith("/")) {
            Pattern p;
            try {
                p = Pattern.compile(q.substring(1, q.length() - 1));
            } catch (PatternSyntaxException ex) {
                return out;
            }
            for (ContextSorter.Entry e : input) if (p.matcher(e.key().id().toLowerCase()).find()) out.add(e);
        } else if (q.startsWith("@")) {
            String t = q.substring(1);
            for (ContextSorter.Entry e : input) if (e.key().id().toLowerCase().contains(t)) out.add(e);
        } else {
            for (ContextSorter.Entry e : input) if (e.key().path().toLowerCase().contains(q)) out.add(e);
        }
        return out;
    }
}
