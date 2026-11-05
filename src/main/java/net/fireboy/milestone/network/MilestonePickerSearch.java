package net.fireboy.milestone.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Shared local/remote search: words AND, -word excludes, @modid, id: and name:. */
public final class MilestonePickerSearch {
    private MilestonePickerSearch() {}
    public enum Field { BOTH, NAME, ID }
    public record Query(String raw, List<String> terms, List<String> excluded,
                        List<String> ids, List<String> names, List<String> namespaces) {
        public boolean matches(String id, String label, Field field) {
            String key=id.toLowerCase(Locale.ROOT), name=label.toLowerCase(Locale.ROOT);
            String namespace=key.contains(":")?key.substring(0,key.indexOf(':')):"";
            for(String mod:namespaces)if(!namespace.contains(mod))return false;
            for(String term:terms)if(!((field!=Field.ID && name.contains(term))
                    ||(field!=Field.NAME && key.contains(term))))return false;
            for(String term:ids)if(!key.contains(term))return false;
            for(String term:names)if(!name.contains(term))return false;
            for(String term:excluded)if(key.contains(term)||name.contains(term))return false;
            return true;
        }
        public int rank(String id,String label) {
            String key=id.toLowerCase(Locale.ROOT),name=label.toLowerCase(Locale.ROOT);
            String first=!terms.isEmpty()?terms.get(0):!ids.isEmpty()?ids.get(0)
                    :!names.isEmpty()?names.get(0):"";
            if(first.isBlank())return 4;
            if(key.equals(first)||name.equals(first))return 0;
            if(name.startsWith(first)||key.substring(key.indexOf(':')+1).startsWith(first))return 1;
            if(key.startsWith(first)||name.contains(" "+first))return 2;
            if(name.contains(first))return 3;
            return 4;
        }
    }
    public static Query parse(String input) {
        String value=input==null?"":input.toLowerCase(Locale.ROOT).trim();
        List<String> plain=new ArrayList<>(),no=new ArrayList<>(),ids=new ArrayList<>();
        List<String> names=new ArrayList<>(),mods=new ArrayList<>();
        for(String token:value.split("\\s+")) {
            if(token.isBlank())continue;
            if(token.startsWith("@")&&token.length()>1)mods.add(token.substring(1));
            else if(token.startsWith("mod:")&&token.length()>4)mods.add(token.substring(4));
            else if(token.startsWith("id:")&&token.length()>3)ids.add(token.substring(3));
            else if(token.startsWith("name:")&&token.length()>5)names.add(token.substring(5));
            else if(token.startsWith("-")&&token.length()>1)no.add(token.substring(1));
            else plain.add(token);
        }
        return new Query(value,List.copyOf(plain),List.copyOf(no),
                List.copyOf(ids),List.copyOf(names),List.copyOf(mods));
    }
    public static Comparator<String> idComparator(Query query) {
        return Comparator.comparingInt((String id)->query.rank(id,id))
                .thenComparing(String::compareTo);
    }
}
